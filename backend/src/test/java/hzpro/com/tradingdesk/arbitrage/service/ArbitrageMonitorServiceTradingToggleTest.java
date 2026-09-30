package hzpro.com.tradingdesk.arbitrage.service;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageDirection;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventMarketsInfoRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;
import hzpro.com.tradingdesk.config.SettingsService;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.domain.orderbook.OrderBook;
import hzpro.com.tradingdesk.domain.orderbook.OrderBookEntry;
import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.Setting;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import hzpro.com.tradingdesk.repository.SettingsRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Integration test verifying that the {@code tradingEnabled} flag — managed through
 * {@link SettingsService} and persisted in the {@code settings} table — is respected
 * by {@link ArbitrageMonitorService#checkArbitrage}.
 *
 * <p>Runs against Testcontainers PostgreSQL via the {@code test} profile.
 * Order-book fetching and venue trading are mocked so no live orders are placed.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Trading toggle integration tests")
class ArbitrageMonitorServiceTradingToggleTest {

    private static final Long PAIR_ID = 7777L;
    private static final String PM_TICKER = "PM-TOGGLE";
    private static final String KS_TICKER = "KS-TOGGLE";
    private static final String PM_YES_TOKEN_ID = "YES-TOKEN-777";

    @Autowired
    private ArbitrageMonitorService monitorService;

    @Autowired
    private PairPriceCache priceCache;

    @Autowired
    private ArbitrageEventRepository eventRepository;

    @Autowired
    private ArbitrageEventMarketsInfoRepository marketsInfoRepository;

    @Autowired
    private PredictionMarketRepository pmRepo;

    @Autowired
    private ArbitrageOrderRepository orderRepository;

    @Autowired
    private ArbitrageConfig config;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private SettingsRepository settingsRepository;

    @MockitoBean
    private PolymarketFetchCommonOrderBookService pmOrderBookService;

    @MockitoBean
    private KalshiFetchCommonOrderBookService ksOrderBookService;

    @MockitoBean
    private PolymarketTradingService polymarketTradingService;

    @MockitoBean
    private KalshiTradingService kalshiTradingService;

    @MockitoBean
    private BalanceService balanceService;

    @BeforeEach
    void setUp() {
        clearState();
        when(balanceService.hasSufficientBalance(any(), any())).thenReturn(true);
    }

    @AfterEach
    void tearDown() {
        clearState();
    }

    private void clearState() {
        marketsInfoRepository.deleteAll();
        orderRepository.deleteAll();
        eventRepository.deleteAll();
        pmRepo.deleteAll();
        settingsRepository.deleteAll();
        priceCache.clear();
        serviceMap("activeOpps").clear();
        serviceMap("activeOrderPairs").clear();
        serviceMap("pmSlugs").clear();
        serviceMap("ksTickers").clear();
        ReflectionTestUtils.setField(monitorService, "activePairRegistryReady", true);
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> serviceMap(String field) {
        return (Map<Object, Object>) ReflectionTestUtils.getField(monitorService, field);
    }

    private void seedPrices(String pmYes, String pmNo, String ksYes, String ksNo) {
        priceCache.updatePmPrice(PAIR_ID, true, new BigDecimal(pmYes));
        priceCache.updatePmPrice(PAIR_ID, false, new BigDecimal(pmNo));
        priceCache.updateKsPrice(PAIR_ID, true, new BigDecimal(ksYes));
        priceCache.updateKsPrice(PAIR_ID, false, new BigDecimal(ksNo));
    }

    private void invokeCheckArbitrage() {
        ReflectionTestUtils.invokeMethod(monitorService, "checkArbitrage", PAIR_ID);
    }

    private void seedDbTradingSetting(boolean enabled) {
        Setting s = new Setting();
        s.setKey(SettingsService.TRADING_ENABLED_KEY);
        s.setValue(String.valueOf(enabled));
        settingsRepository.save(s);
    }

    private void seedPairAndOrderBooks() {
        serviceMap("pmSlugs").put(PAIR_ID, PM_TICKER);
        serviceMap("ksTickers").put(PAIR_ID, KS_TICKER);

        pmRepo.save(PredictionMarket.builder()
                .datasource(DataSource.POLYMARKET)
                .marketTicker(PM_TICKER)
                .yesTokenId(PM_YES_TOKEN_ID)
                .build());

        OrderBook pmBook = new OrderBook(List.of(new OrderBookEntry(new BigDecimal("0.10"), 11L)));
        OrderBook ksBook = new OrderBook(List.of(new OrderBookEntry(new BigDecimal("0.20"), 12L)));
        when(pmOrderBookService.fetchCommonOrderBook(PM_TICKER, YesOrNoResult.YES)).thenReturn(pmBook);
        when(ksOrderBookService.fetchCommonOrderBook(KS_TICKER, YesOrNoResult.NO)).thenReturn(ksBook);

        when(polymarketTradingService.placeOrder(any(), any(), anyLong(), any()))
                .thenReturn(new PolymarketTradingService.OrderResult(true, "PM-ORDER", "placed", null));
        when(kalshiTradingService.placeOrder(any(), any(), anyInt(), any()))
                .thenReturn(new KalshiTradingService.OrderResult(true, "KS-ORDER", "placed", null));
    }

    // ── Test cases ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("tradingEnabled=true → orders are placed on opportunity")
    void tradingEnabledTruePlacesOrders() {
        seedDbTradingSetting(true);
        settingsService.loadSettings();

        seedPairAndOrderBooks();

        // Spread PM_YES_KS_NO = 1 - (0.40 + 0.40) = 0.20 > 0.01
        seedPrices("0.40", "0.62", "0.62", "0.40");

        invokeCheckArbitrage();

        assertThat(eventRepository.findUnclosedOpportunities()).hasSize(0);
        verify(polymarketTradingService).placeOrder(any(), any(), anyLong(), any());
        verify(kalshiTradingService).placeOrder(any(), any(), anyInt(), any());

        // Verify orders were actually persisted in the DB
        ArbitrageEvent event = eventRepository.findAll().get(0);
        List<ArbitrageOrder> orders = orderRepository.findByOpportunityUuid(event.getUuid());
        assertThat(orders).hasSize(2);

        ArbitrageOrder pmOrder = orders.stream()
                .filter(o -> o.getPlatform().equals("POLYMARKET"))
                .findFirst().orElseThrow();
        ArbitrageOrder ksOrder = orders.stream()
                .filter(o -> o.getPlatform().equals("KALSHI"))
                .findFirst().orElseThrow();

        assertThat(pmOrder.getContractType()).isEqualTo(YesOrNoResult.YES.name());
        assertThat(pmOrder.getPrice()).isEqualTo(new BigDecimal("0.10").setScale(pmOrder.getPrice().scale()));
        assertThat(pmOrder.getStatus()).isEqualTo(OrderState.PLACED);

        assertThat(ksOrder.getContractType()).isEqualTo(YesOrNoResult.NO.name());
        assertThat(ksOrder.getPrice()).isEqualTo(new BigDecimal("0.200").setScale(ksOrder.getPrice().scale()));
        assertThat(ksOrder.getStatus()).isEqualTo(OrderState.PLACED);
    }

    @Test
    @DisplayName("tradingEnabled=false → orders are NOT placed")
    void tradingEnabledFalseSkipsOrders() {
        seedDbTradingSetting(false);
        settingsService.loadSettings();

        seedPairAndOrderBooks();

        // Spread above threshold but trading is disabled
        seedPrices("0.40", "0.62", "0.62", "0.40");

        invokeCheckArbitrage();

        // Opportunity is detected and persisted...
        assertThat(eventRepository.findUnclosedOpportunities()).hasSize(1);
        // ...but no orders are placed
        verify(polymarketTradingService, never()).placeOrder(any(), any(), anyLong(), any());
        verify(kalshiTradingService, never()).placeOrder(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("no DB row → fallback to ArbitrageConfig default")
    void noDbRowFallsBackToEnvDefault() {
        // Don't seed any DB row; set the config default to simulate env var behavior
        config.setTradingEnabled(false);

        settingsService.loadSettings();

        assertThat(config.isTradingEnabled()).isFalse();
    }

    @Test
    @DisplayName("DB row overrides env default")
    void dbRowOverridesEnvDefault() {
        // Env default says false, DB says true
        config.setTradingEnabled(false);
        seedDbTradingSetting(true);

        settingsService.loadSettings();

        assertThat(config.isTradingEnabled()).isTrue();
    }

    @Test
    @DisplayName("runtime toggle via setTradingEnabled() updates DB and bean")
    void runtimeToggleUpdatesDbAndBean() {
        seedDbTradingSetting(false);
        settingsService.loadSettings();
        assertThat(config.isTradingEnabled()).isFalse();

        boolean result = settingsService.setTradingEnabled(true);

        assertThat(result).isTrue();
        assertThat(config.isTradingEnabled()).isTrue();

        Setting dbRow = settingsRepository.findByKey(SettingsService.TRADING_ENABLED_KEY).orElseThrow();
        assertThat(dbRow.getValue()).isEqualTo("true");
    }

    @Test
    @DisplayName("getTradingEnabled() returns DB value when present")
    void getTradingEnabledReturnsDbValue() {
        seedDbTradingSetting(true);
        settingsService.loadSettings();

        assertThat(settingsService.getTradingEnabled()).isTrue();

        seedDbTradingSetting(false);
        settingsService.loadSettings();

        assertThat(settingsService.getTradingEnabled()).isFalse();
    }
}
