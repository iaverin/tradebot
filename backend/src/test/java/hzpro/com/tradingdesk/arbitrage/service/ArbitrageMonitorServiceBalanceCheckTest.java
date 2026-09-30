package hzpro.com.tradingdesk.arbitrage.service;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageDirection;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventMarketsInfoRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventRepository;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.domain.orderbook.OrderBook;
import hzpro.com.tradingdesk.domain.orderbook.OrderBookEntry;
import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import hzpro.com.tradingdesk.service.account.KalshiBalanceService;
import hzpro.com.tradingdesk.service.account.PolymarketBalanceService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Integration tests for balance-check logic added to
 * {@link ArbitrageMonitorService#checkArbitrage} and {@link BalanceService}.
 *
 * <p>Runs against a real (Testcontainers) PostgreSQL via the {@code test} profile.
 * {@link BalanceService} is mocked with {@code @MockitoBean} so the balance check
 * outcome is controlled without needing real venue API calls.
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("Balance Check Integration Tests")
class ArbitrageMonitorServiceBalanceCheckTest {

    private static final Long PAIR_ID = 4242L;
    private static final String PM_TICKER = "PM-TEST";
    private static final String KS_TICKER = "KS-TEST";
    private static final String PM_YES_TOKEN_ID = "YES-TOKEN-123";

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
    private ArbitrageConfig config;

    // Mock order-book retrieval so createOrders does not fail on null order books.
    @MockitoBean
    private PolymarketFetchCommonOrderBookService pmOrderBookService;

    @MockitoBean
    private KalshiFetchCommonOrderBookService ksOrderBookService;

    // Mock venue order placement so no live order is ever sent.
    @MockitoBean
    private PolymarketTradingService polymarketTradingService;

    @MockitoBean
    private KalshiTradingService kalshiTradingService;

    // Mock the balance service to control the pre-trade check outcome.
    @MockitoBean
    private BalanceService balanceService;

    private ListAppender<ILoggingEvent> listAppender;

    @BeforeEach
    void setUp() {
        clearState();
        config.setTradingEnabled(true);

        listAppender = new ListAppender<>();
        listAppender.start();
        ((Logger) LoggerFactory.getLogger(BalanceService.class)).addAppender(listAppender);
    }

    @AfterEach
    void tearDown() {
        ((Logger) LoggerFactory.getLogger(BalanceService.class)).detachAppender(listAppender);
        clearState();
    }

    private void clearState() {
        marketsInfoRepository.deleteAll();
        eventRepository.deleteAll();
        pmRepo.deleteAll();
        priceCache.clear();
        serviceMap("activeOpps").clear();
        serviceMap("activeOrderPairs").clear();
        serviceMap("pmSlugs").clear();
        serviceMap("ksTickers").clear();
        ReflectionTestUtils.setField(monitorService, "activePairRegistryReady", true);
        config.setTradingEnabled(false);
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

    @Test
    @DisplayName("places orders when balance is sufficient")
    void sufficientBalanceProceedsWithTrade() {
        serviceMap("pmSlugs").put(PAIR_ID, PM_TICKER);
        serviceMap("ksTickers").put(PAIR_ID, KS_TICKER);

        pmRepo.save(PredictionMarket.builder()
                .datasource(DataSource.POLYMARKET)
                .marketTicker(PM_TICKER)
                .yesTokenId(PM_YES_TOKEN_ID)
                .build());

        OrderBook pmBook = new OrderBook(List.of(new OrderBookEntry(new BigDecimal("0.10"), 11L)));
        OrderBook ksBook = new OrderBook(List.of(new OrderBookEntry(new BigDecimal("0.10"), 12L)));
        when(pmOrderBookService.fetchCommonOrderBook(PM_TICKER, YesOrNoResult.YES)).thenReturn(pmBook);
        when(ksOrderBookService.fetchCommonOrderBook(KS_TICKER, YesOrNoResult.NO)).thenReturn(ksBook);

        when(polymarketTradingService.placeOrder(any(), any(), anyLong(), any()))
                .thenReturn(new PolymarketTradingService.OrderResult(true, "PM-ORDER-1", "placed", null));
        when(kalshiTradingService.placeOrder(any(), any(), anyInt(), any()))
                .thenReturn(new KalshiTradingService.OrderResult(true, "KS-ORDER-1", "placed", null));

        // Balance is sufficient.
        when(balanceService.hasSufficientBalance(any(), any())).thenReturn(true);

        // PM_YES_KS_NO spread = 1 - (0.40 + 0.40) = 0.20  -> above 0.01
        seedPrices("0.40", "0.62", "0.62", "0.40");

        invokeCheckArbitrage();

        List<ArbitrageEvent> events = eventRepository.findAll();
        assertThat(events).hasSize(2);
        assertThat(events.get(0).getEventType()).isEqualTo("OPPORTUNITY_START");

        // Orders should be placed on both venues.
        verify(polymarketTradingService).placeOrder(any(), any(), anyLong(), any());
        verify(kalshiTradingService).placeOrder(any(), any(), anyInt(), any());
    }

    @Test
    @DisplayName("skips trade when balance is insufficient")
    void insufficientBalanceSkipsTrade() {
        serviceMap("pmSlugs").put(PAIR_ID, PM_TICKER);
        serviceMap("ksTickers").put(PAIR_ID, KS_TICKER);

        pmRepo.save(PredictionMarket.builder()
                .datasource(DataSource.POLYMARKET)
                .marketTicker(PM_TICKER)
                .yesTokenId(PM_YES_TOKEN_ID)
                .build());

        // Balance is insufficient.
        when(balanceService.hasSufficientBalance(any(), any())).thenReturn(false);

        // PM_YES_KS_NO spread = 1 - (0.40 + 0.40) = 0.20  -> above 0.01
        seedPrices("0.40", "0.62", "0.62", "0.40");

        invokeCheckArbitrage();

        // OPPORTUNITY_START is still persisted (opportunity was detected).
        List<ArbitrageEvent> events = eventRepository.findAll();
        assertThat(events).hasSize(1);
        assertThat(events.get(0).getEventType()).isEqualTo("OPPORTUNITY_START");

        // No orders placed on either venue (balance check blocks trading).
        verifyNoInteractions(polymarketTradingService, kalshiTradingService);
    }

    @Test
    @DisplayName("refreshBalances caches values from both venues")
    void refreshBalancesCachesVenueBalances() {
        // This test exercises the real BalanceService directly by mocking its
        // underlying venue-specific balance services.
        var mockKalshi = new KalshiBalanceService(null, null) {
            @Override
            public KalshiBalance getBalance() {
                return new KalshiBalance(50000L, BigDecimal.valueOf(500.00));
            }
        };
        var mockPolymarket = new PolymarketBalanceService(null, null) {
            @Override
            public Balance getBalance() {
                return new Balance(BigDecimal.valueOf(1000.00));
            }
        };

        BalanceService service = new BalanceService(mockKalshi, mockPolymarket);

        // Initially zero.
        assertThat(service.getKalshiBalance()).isEqualByComparingTo("0");
        assertThat(service.getPolymarketBalance()).isEqualByComparingTo("0");

        service.refreshBalances();

        assertThat(service.getKalshiBalance()).isEqualByComparingTo("500.00");
        assertThat(service.getPolymarketBalance()).isEqualByComparingTo("1000.00");

        // hasSufficientBalance should now reflect the cached values.
        assertThat(service.hasSufficientBalance(BigDecimal.valueOf(499), BigDecimal.valueOf(999)))
                .isTrue();
        assertThat(service.hasSufficientBalance(BigDecimal.valueOf(501), BigDecimal.valueOf(1000)))
                .isFalse();
        assertThat(service.hasSufficientBalance(BigDecimal.valueOf(500), BigDecimal.valueOf(1001)))
                .isFalse();
    }

    @Test
    @DisplayName("hasSufficientBalance returns false when both balances are zero and required amount is zero")
    void insufficientBalanceWhenZeroRequired() {
        BalanceService service = new BalanceService(
                new KalshiBalanceService(null, null) {
                    @Override
                    public KalshiBalance getBalance() {
                        return new KalshiBalance(0L, BigDecimal.ZERO);
                    }
                },
                new PolymarketBalanceService(null, null) {
                    @Override
                    public Balance getBalance() {
                        return new Balance(BigDecimal.ZERO);
                    }
                });

        service.refreshBalances();

        assertFalse(service.hasSufficientBalance(BigDecimal.ZERO, BigDecimal.ZERO));
    }
}
