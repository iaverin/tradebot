package hzpro.com.tradingdesk.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.Spy;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.model.PriceSnapshot;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventMarketsInfoRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventRepository;
import hzpro.com.tradingdesk.arbitrage.rest.KalshiPriceRestClient;
import hzpro.com.tradingdesk.arbitrage.rest.PolymarketPriceRestClient;
import hzpro.com.tradingdesk.config.ProxyConfig;
import hzpro.com.tradingdesk.config.SettingsService;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import hzpro.com.tradingdesk.similarmarkets.repository.SimilarMarketsRepository;

@ExtendWith(MockitoExtension.class)
class ArbitrageMonitorPriceUpdateTest {

    private static final long PAIR_ID = 42L;
    private static final String PM_YES_ASSET_ID = "pm-yes";
    private static final String PM_NO_ASSET_ID = "pm-no";
    private static final String KS_TICKER = "ks-ticker";

    @Mock private ArbitrageConfig config;
    @Mock private ArbitrageEventRepository eventRepository;
    @Mock private ArbitrageEventMarketsInfoRepository marketsInfoRepository;
    @Mock private PredictionMarketRepository predictionMarketRepository;
    @Mock private SimilarMarketsRepository similarMarketsRepository;
    @Mock private ProxyConfig proxyConfig;
    @Spy private PairPriceCache priceCache = new PairPriceCache();
    @Mock private KalshiPriceRestClient kalshiPriceRestClient;
    @Mock private PolymarketPriceRestClient polymarketPriceRestClient;
    @Mock private PolymarketFetchCommonOrderBookService polymarketOrderBookService;
    @Mock private KalshiFetchCommonOrderBookService kalshiOrderBookService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ArbitrageOrderService arbitrageOrderService;
    @Mock private BalanceService balanceService;
    @Mock private SettingsService settingsService;

    @InjectMocks
    private ArbitrageMonitorService monitorService;

    @BeforeEach
    void setUp() {
        priceCache.registerPair(PAIR_ID, PM_YES_ASSET_ID, PM_NO_ASSET_ID, KS_TICKER);
    }

    @Test
    void polymarketUpdateAddsMissingPairSnapshotsWithoutCheckingIncompletePrices() {
        PriceSnapshot update = PriceSnapshot.builder()
                .yesAsk(new BigDecimal("0.42"))
                .build();

        ReflectionTestUtils.invokeMethod(monitorService, "handlePmUpdate", PM_YES_ASSET_ID, update);

        assertThat(priceCache.getPmPrice(PAIR_ID)).isNotNull();
        assertThat(priceCache.getPmPrice(PAIR_ID).getYesAsk()).isEqualByComparingTo("0.42");
        assertThat(priceCache.getKsPrice(PAIR_ID)).isNotNull();
        assertThat(priceCache.getKsPrice(PAIR_ID).isComplete()).isFalse();
        verifyNoInteractions(eventRepository);
    }

    @Test
    void kalshiUpdateAddsMissingPairSnapshotsWithoutCheckingIncompletePrices() {
        PriceSnapshot update = PriceSnapshot.builder()
                .yesAsk(new BigDecimal("0.55"))
                .noAsk(new BigDecimal("0.47"))
                .build();

        ReflectionTestUtils.invokeMethod(monitorService, "handleKsUpdate", KS_TICKER, update);

        assertThat(priceCache.getKsPrice(PAIR_ID)).isNotNull();
        assertThat(priceCache.getKsPrice(PAIR_ID).getYesAsk()).isEqualByComparingTo("0.55");
        assertThat(priceCache.getKsPrice(PAIR_ID).getNoAsk()).isEqualByComparingTo("0.47");
        assertThat(priceCache.getPmPrice(PAIR_ID)).isNotNull();
        assertThat(priceCache.getPmPrice(PAIR_ID).isComplete()).isFalse();
        verifyNoInteractions(eventRepository);
    }
}
