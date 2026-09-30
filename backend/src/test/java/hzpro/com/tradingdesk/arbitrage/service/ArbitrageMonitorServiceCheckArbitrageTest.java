package hzpro.com.tradingdesk.arbitrage.service;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageDirection;
import hzpro.com.tradingdesk.arbitrage.model.OpportunityCloseReason;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventMarketsInfoRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.domain.orderbook.OrderBook;
import hzpro.com.tradingdesk.domain.orderbook.OrderBookEntry;
import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
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
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Integration test for the private {@code checkArbitrage(pairId)} logic of
 * {@link ArbitrageMonitorService}.
 *
 * <p>Runs against a real (Testcontainers) PostgreSQL via the {@code test} profile — DB data is NOT
 * mocked, it is seeded/asserted through the real repositories. The only thing mocked is the
 * <em>order-book fetching</em> ({@link PolymarketFetchCommonOrderBookService} /
 * {@link KalshiFetchCommonOrderBookService}); their default {@code null} return makes
 * {@code ArbitrageOrderService.createOrders} bail out before any real venue call, so no live order
 * is placed.
 *
 * <p>Prices are injected straight into {@link PairPriceCache} and {@code checkArbitrage} is invoked
 * by reflection (it is private and normally driven by WebSocket updates).
 */
@SpringBootTest
@ActiveProfiles("test")
@DisplayName("ArbitrageMonitorService.checkArbitrage Integration Tests")
class ArbitrageMonitorServiceCheckArbitrageTest {

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
    private ArbitrageOrderRepository orderRepository;

    @Autowired
    private PredictionMarketRepository pmRepo;

    @Autowired
    private ArbitrageConfig config;

    // Mock ONLY the order-book retrieval. Default (null) return keeps createOrders from placing
    // any real order while still exercising the full checkArbitrage / DB persistence path.
    @MockitoBean
    private PolymarketFetchCommonOrderBookService pmOrderBookService;

    @MockitoBean
    private KalshiFetchCommonOrderBookService ksOrderBookService;

    // Mock the venue order placement so no live order is ever sent.
    @MockitoBean
    private PolymarketTradingService polymarketTradingService;

    @MockitoBean
    private KalshiTradingService kalshiTradingService;

    // Mock the balance service so the pre-trade balance check passes by default.
    @MockitoBean
    private BalanceService balanceService;

    @BeforeEach
    void setUp() {
        clearState();
        config.setTradingEnabled(true);
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
        priceCache.clear();
        // checkArbitrage mutates the service's in-memory maps (activeOpps on a START, and the
        // ticker lookups we seed below); reset them so the singleton service does not leak state.
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
    @DisplayName("opens an OPPORTUNITY_START and places venue orders when a spread exceeds the threshold")
    void detectsAndPersistsOpportunity() {

        // The pair's tickers, normally populated from the DB at monitor start-up.
        serviceMap("pmSlugs").put(PAIR_ID, PM_TICKER);
        serviceMap("ksTickers").put(PAIR_ID, KS_TICKER);

        // Real PM market row so createOrders can resolve the Polymarket YES token id (no DB mocking).
        pmRepo.save(PredictionMarket.builder()
                .datasource(DataSource.POLYMARKET)
                .marketTicker(PM_TICKER)
                .yesTokenId(PM_YES_TOKEN_ID)
                .build());

        // Order books returned at order-create time (the only mocked retrieval). Prices/sizes are
        // small so the resulting order cost stays under MIN_ORDER_COST and an order is actually placed.
        OrderBook pmBook = new OrderBook(List.of(new OrderBookEntry(new BigDecimal("0.10"), 11L)));
        OrderBook ksBook = new OrderBook(List.of(new OrderBookEntry(new BigDecimal("0.10"), 12L)));
        // Direction PM_YES_KS_NO buys PM YES and KS NO.
        when(pmOrderBookService.fetchCommonOrderBook(PM_TICKER, YesOrNoResult.YES)).thenReturn(pmBook);
        when(ksOrderBookService.fetchCommonOrderBook(KS_TICKER, YesOrNoResult.NO)).thenReturn(ksBook);

        when(polymarketTradingService.placeOrder(any(), any(), anyLong(), any()))
                .thenReturn(new PolymarketTradingService.OrderResult(true, "PM-ORDER-1", "placed", null));
        when(kalshiTradingService.placeOrder(any(), any(), anyInt(), any()))
                .thenReturn(new KalshiTradingService.OrderResult(true, "KS-ORDER-1", "placed", null));

        // PM_YES_KS_NO spread = 1 - (pmYesAsk + ksNoAsk) = 1 - (0.40 + 0.40) = 0.20  -> above 0.01
        // PM_NO_KS_YES spread = 1 - (pmNoAsk + ksYesAsk) = 1 - (0.62 + 0.62) = -0.24 -> below 0.01
        seedPrices("0.40", "0.62", "0.62", "0.40");

        invokeCheckArbitrage();

        List<ArbitrageEvent> events = eventRepository.findAll();
        assertThat(events).hasSize(2);

        ArbitrageEvent start = events.get(0);
        assertThat(start.getEventType()).isEqualTo("OPPORTUNITY_START");
        assertThat(start.getSimilarMarketId()).isEqualTo(PAIR_ID);
        assertThat(start.getDirection()).isEqualTo(ArbitrageDirection.PM_YES_KS_NO.getCode());
        assertThat(start.getSpread()).isEqualByComparingTo("0.20");
        assertThat(start.getThreshold()).isEqualByComparingTo(config.getThreshold());

        ArbitrageEvent end = events.stream()
                .filter(event -> "OPPORTUNITY_END".equals(event.getEventType()))
                .findFirst()
                .orElseThrow();
        assertThat(end.getCloseReason()).isEqualTo(OpportunityCloseReason.ORDERS_CREATED);

        // Successful order creation closes the opportunity immediately.
        assertThat(eventRepository.findUnclosedOpportunities()).isEmpty();

        // Both venue orders are placed with the arguments derived from the order books.
        // qty: minQuantity=3, maxPrice=0.10 -> cost 0.30 <= maxOrderCost(2) -> qty=3.
        verify(polymarketTradingService).placeOrder(PM_YES_TOKEN_ID, new BigDecimal("0.10"), 11L, "BUY");
        verify(kalshiTradingService).placeOrder(KS_TICKER, YesOrNoResult.NO, 11, new BigDecimal("0.10"));

        assertThat(serviceMap("activeOrderPairs")).hasSize(1);
        Map<?, ?> attemptsForPair = (Map<?, ?>) serviceMap("activeOrderPairs").values().iterator().next();
        assertThat(attemptsForPair).hasSize(1);
        assertThat(attemptsForPair.containsKey(start.getUuid())).isTrue();
    }

    @Test
    @DisplayName("does nothing when no direction's spread exceeds the threshold")
    void noOpportunityWhenSpreadBelowThreshold() {
        // Both directions: 1 - (0.62 + 0.62) = -0.24 -> below threshold.
        seedPrices("0.62", "0.62", "0.62", "0.62");

        invokeCheckArbitrage();

        assertThat(eventRepository.findAll()).isEmpty();
    }

    @Test
    @DisplayName("does not reconcile an untracked DB opportunity when the spread is below the threshold")
    void leavesUntrackedDbOpportunityOpenWhenSpreadIsBelowThreshold() {
        ArbitrageEvent start = seedUnclosedStart(ArbitrageDirection.PM_YES_KS_NO);

        // The opportunity exists only in the DB, not in activeOpps. Without DB reconciliation,
        // a below-threshold check must leave it untouched.
        seedPrices("0.62", "0.62", "0.62", "0.62");

        invokeCheckArbitrage();

        List<ArbitrageEvent> ends = eventRepository.findAll().stream()
                .filter(e -> "OPPORTUNITY_END".equals(e.getEventType()))
                .toList();

        assertThat(ends).isEmpty();
        List<ArbitrageEvent> unclosed = eventRepository.findUnclosedOpportunities();
        assertThat(unclosed).hasSize(1);
        assertThat(unclosed.getFirst().getId()).isEqualTo(start.getId());
    }

    @Test
    @DisplayName("ends the just-opened opportunity when orders are not created (order-create re-validation fails)")
    void endsOpportunityWhenOrdersNotCreated() {
        serviceMap("pmSlugs").put(PAIR_ID, PM_TICKER);
        serviceMap("ksTickers").put(PAIR_ID, KS_TICKER);

        // Order books are left unstubbed -> mocks return null -> createOrders bails ("empty orderbook")
        // and returns false, so no venue order is placed and the opportunity must be reset.

        // PM_YES_KS_NO spread = 1 - (0.40 + 0.40) = 0.20 -> above 0.01 (the other direction stays below).
        seedPrices("0.40", "0.62", "0.62", "0.40");

        invokeCheckArbitrage();

        List<ArbitrageEvent> events = eventRepository.findAll();
        assertThat(events).extracting(ArbitrageEvent::getEventType)
                .containsExactlyInAnyOrder("OPPORTUNITY_START", "OPPORTUNITY_END");

        ArbitrageEvent start = events.stream()
                .filter(e -> "OPPORTUNITY_START".equals(e.getEventType())).findFirst().orElseThrow();
        ArbitrageEvent end = events.stream()
                .filter(e -> "OPPORTUNITY_END".equals(e.getEventType())).findFirst().orElseThrow();

        assertThat(end.getOpportunityStart().getId()).isEqualTo(start.getId());
        assertThat(end.getDirection()).isEqualTo(ArbitrageDirection.PM_YES_KS_NO.getCode());
        assertThat(end.getCloseReason()).isEqualTo(OpportunityCloseReason.ORDERBOOK_EMPTY);

        // Opportunity is closed: not reported as unclosed and not tracked in memory.
        assertThat(eventRepository.findUnclosedOpportunities()).isEmpty();
        assertThat(serviceMap("activeOpps")).isEmpty();

        // No order was placed on either venue.
        verifyNoInteractions(polymarketTradingService, kalshiTradingService);
    }

    @Test
    @DisplayName("restart rebuilds the active-pair registry from persisted unfinished orders via status events")
    void restartRebuildsRegistryFromPersistedUnfinishedOrders() {
        UUID uuid = UUID.randomUUID();
        eventRepository.saveAndFlush(ArbitrageEvent.builder()
                .uuid(uuid)
                .eventType("OPPORTUNITY_START")
                .similarMarketId(PAIR_ID)
                .polymarketMarketTicker(PM_TICKER)
                .kalshiMarketTicker(KS_TICKER)
                .direction(ArbitrageDirection.PM_YES_KS_NO.getCode())
                .spread(new BigDecimal("0.20"))
                .threshold(config.getThreshold())
                .polymarketOrderbook("{}")
                .kalshiOrderbook("{}")
                .build());
        orderRepository.saveAndFlush(ArbitrageOrder.builder()
                .opportunityUuid(uuid)
                .platform("POLYMARKET")
                .contractType("YES")
                .price(new BigDecimal("0.40"))
                .quantity(10L)
                .orderId("recovery-order")
                .status(OrderState.PLACED)
                .build());
        when(polymarketTradingService.getOrderStatus("recovery-order"))
                .thenReturn(new PolymarketTradingService.OrderStatus(
                        "recovery-order", "live", "0", null));

        monitorService.restart();

        assertThat(serviceMap("activeOrderPairs")).hasSize(1);
        assertThat(serviceMap("activeOrderPairs").values()).anyMatch(
                value -> ((Map<?, ?>) value).containsKey(uuid));
        assertThat(ReflectionTestUtils.getField(monitorService, "activePairRegistryReady")).isEqualTo(true);
        verify(polymarketTradingService).getOrderStatus("recovery-order");
    }

    private ArbitrageEvent seedUnclosedStart(ArbitrageDirection direction) {
        return eventRepository.save(ArbitrageEvent.builder()
                .eventType("OPPORTUNITY_START")
                .similarMarketId(PAIR_ID)
                .polymarketMarketTicker("PM-TEST")
                .kalshiMarketTicker("KS-TEST")
                .direction(direction.getCode())
                .spread(new BigDecimal("0.20"))
                .threshold(config.getThreshold())
                .polymarketOrderbook("{}")
                .kalshiOrderbook("{}")
                .build());
    }
}
