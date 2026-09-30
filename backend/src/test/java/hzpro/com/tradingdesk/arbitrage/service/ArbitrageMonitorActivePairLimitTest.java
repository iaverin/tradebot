package hzpro.com.tradingdesk.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageOrderLifecycleEvent;
import hzpro.com.tradingdesk.arbitrage.model.OpportunityCloseReason;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.model.OrderStatusRefreshResult;
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
class ArbitrageMonitorActivePairLimitTest {

    private static final long FIRST_PAIR_ID = 101L;
    private static final long SECOND_PAIR_ID = 202L;
    private static final String PM_TICKER = "pm-market";
    private static final String KS_TICKER = "ks-market";

    @Mock private ArbitrageConfig config;
    @Mock private ArbitrageEventRepository eventRepository;
    @Mock private ArbitrageEventMarketsInfoRepository marketsInfoRepository;
    @Mock private PredictionMarketRepository predictionMarketRepository;
    @Mock private SimilarMarketsRepository similarMarketsRepository;
    @Mock private ProxyConfig proxyConfig;
    @Mock private PairPriceCache priceCache;
    @Mock private KalshiPriceRestClient kalshiPriceRestClient;
    @Mock private PolymarketPriceRestClient polymarketPriceRestClient;
    @Mock private PolymarketFetchCommonOrderBookService polymarketOrderBookService;
    @Mock private KalshiFetchCommonOrderBookService kalshiOrderBookService;
    @Mock private ObjectMapper objectMapper;
    @Mock private ArbitrageOrderService arbitrageOrderService;
    @Mock private BalanceService balanceService;
    @Mock private SettingsService settingsService;
    @Mock private OrderStatusChecker orderStatusChecker;

    @InjectMocks
    private ArbitrageMonitorService monitorService;

    @BeforeEach
    void setUp() {
        PriceSnapshot pm = PriceSnapshot.builder()
                .yesAsk(new BigDecimal("0.40"))
                .noAsk(new BigDecimal("0.80"))
                .build();
        PriceSnapshot ks = PriceSnapshot.builder()
                .yesAsk(new BigDecimal("0.80"))
                .noAsk(new BigDecimal("0.40"))
                .build();
        lenient().when(priceCache.getPmPrice(any())).thenReturn(pm);
        lenient().when(priceCache.getKsPrice(any())).thenReturn(ks);
        lenient().when(config.getThreshold()).thenReturn(new BigDecimal("0.01"));
        lenient().when(config.isTradingEnabled()).thenReturn(true);
        lenient().when(config.getMaxOrderCost()).thenReturn(BigDecimal.TEN);
        lenient().when(balanceService.hasSufficientBalance(any(), any())).thenReturn(true);
        lenient().when(settingsService.getActivePairsLimit()).thenReturn(1);
        lenient().when(orderStatusChecker.checkUnfinishedOrdersForPair(PM_TICKER, KS_TICKER))
                .thenReturn(new OrderStatusRefreshResult(true, 1, 0));
        lenient().when(eventRepository.save(any(ArbitrageEvent.class)))
                .thenAnswer(invocation -> invocation.getArgument(0));
        lenient().when(arbitrageOrderService.createOrders(any(), any(), any(), any()))
                .thenReturn(OpportunityCloseReason.ORDERBOOK_EMPTY);

        registerPair(FIRST_PAIR_ID, PM_TICKER, KS_TICKER);
        registerPair(SECOND_PAIR_ID, PM_TICKER, KS_TICKER);
        ReflectionTestUtils.setField(monitorService, "activePairRegistryReady", true);
    }

    @Test
    void belowLimitCallsOrderServiceWithoutCreatingSyntheticRegistryState() {
        AtomicBoolean sawEmptyRegistry = new AtomicBoolean();
        when(arbitrageOrderService.createOrders(any(), any(), any(), any())).thenAnswer(invocation -> {
            sawEmptyRegistry.set(activePairCount() == 0);
            return OpportunityCloseReason.ORDERBOOK_EMPTY;
        });

        invokeCheckArbitrage(FIRST_PAIR_ID);

        assertThat(sawEmptyRegistry).isTrue();
        assertThat(activePairCount()).isZero();
        verify(arbitrageOrderService).createOrders(any(), any(), any(), any());
    }

    @Test
    void activeLifecycleStateBlocksAndRefreshesBeforeWritingLimitCloseReason() {
        addOrder(UUID.randomUUID(), 10L, "POLYMARKET", OrderState.PENDING);

        invokeCheckArbitrage(FIRST_PAIR_ID);

        verify(orderStatusChecker).checkUnfinishedOrdersForPair(PM_TICKER, KS_TICKER);
        verify(arbitrageOrderService, never()).createOrders(any(), any(), any(), any());
        assertThat(savedEndReason()).isEqualTo(OpportunityCloseReason.ACTIVE_PAIR_LIMIT_REACHED);
        assertThat(activePairCount()).isEqualTo(1);
        verify(settingsService, never()).addAndGet(org.mockito.ArgumentMatchers.anyInt());
        verify(balanceService, never()).refreshBalances();
    }

    @ParameterizedTest
    @EnumSource(value = OrderState.class, names = {"CREATED", "PENDING", "PLACED", "UNKNOWN"})
    void everyActiveStateConsumesCapacity(OrderState activeState) {
        addOrder(UUID.randomUUID(), 10L, "POLYMARKET", activeState);

        invokeCheckArbitrage(FIRST_PAIR_ID);

        verify(arbitrageOrderService, never()).createOrders(any(), any(), any(), any());
        assertThat(savedEndReason()).isEqualTo(OpportunityCloseReason.ACTIVE_PAIR_LIMIT_REACHED);
    }

    @ParameterizedTest
    @EnumSource(value = OrderState.class, names = {"EXECUTED", "CANCELED", "ERROR"})
    void terminalStatesDoNotConsumeCapacity(OrderState terminalState) {
        addOrder(UUID.randomUUID(), 10L, "POLYMARKET", terminalState);

        invokeCheckArbitrage(FIRST_PAIR_ID);

        verify(arbitrageOrderService).createOrders(any(), any(), any(), any());
    }

    @Test
    void terminalRefreshReleasesCapacityAndAllowsOrderCreation() {
        UUID activeUuid = UUID.randomUUID();
        addOrder(activeUuid, 10L, "POLYMARKET", OrderState.PLACED);
        when(orderStatusChecker.checkUnfinishedOrdersForPair(PM_TICKER, KS_TICKER))
                .thenAnswer(invocation -> {
                    addOrder(activeUuid, 10L, "POLYMARKET", OrderState.EXECUTED);
                    return new OrderStatusRefreshResult(true, 1, 1);
                });

        invokeCheckArbitrage(FIRST_PAIR_ID);

        verify(arbitrageOrderService).createOrders(any(), any(), any(), any());
        assertThat(savedEndReason()).isEqualTo(OpportunityCloseReason.ORDERBOOK_EMPTY);
        assertThat(activePairCount()).isZero();
    }

    @Test
    void incompleteRefreshFailsClosedAndRetainsCapacity() {
        addOrder(UUID.randomUUID(), 10L, "POLYMARKET", OrderState.UNKNOWN);
        when(orderStatusChecker.checkUnfinishedOrdersForPair(PM_TICKER, KS_TICKER))
                .thenReturn(OrderStatusRefreshResult.failed());

        invokeCheckArbitrage(FIRST_PAIR_ID);

        verify(arbitrageOrderService, never()).createOrders(any(), any(), any(), any());
        assertThat(savedEndReason()).isEqualTo(OpportunityCloseReason.ACTIVE_PAIR_LIMIT_REACHED);
        assertThat(activePairCount()).isEqualTo(1);
    }

    @Test
    void oneTerminalLegDoesNotRemoveUuidUntilTheLastActiveLegIsTerminal() {
        UUID uuid = UUID.randomUUID();
        addOrder(uuid, 10L, "POLYMARKET", OrderState.PLACED);
        addOrder(uuid, 11L, "KALSHI", OrderState.UNKNOWN);

        assertThat(activePairCount()).isEqualTo(1);
        addOrder(uuid, 10L, "POLYMARKET", OrderState.EXECUTED);
        assertThat(activePairCount()).isEqualTo(1);

        addOrder(uuid, 11L, "KALSHI", OrderState.CANCELED);
        assertThat(activePairCount()).isZero();
    }

    @Test
    void differentTickerPairHasIndependentCapacity() {
        addOrder(UUID.randomUUID(), 10L, "POLYMARKET", OrderState.PLACED);
        registerPair(SECOND_PAIR_ID, "pm-other", "ks-other");

        invokeCheckArbitrage(SECOND_PAIR_ID);

        verify(arbitrageOrderService).createOrders(any(), any(), any(), any());
        verify(orderStatusChecker, never()).checkUnfinishedOrdersForPair("pm-other", "ks-other");
    }

    @Test
    void configuredLimitAboveCurrentCountAllowsAnotherPairWithoutRefreshing() {
        when(settingsService.getActivePairsLimit()).thenReturn(2);
        addOrder(UUID.randomUUID(), 10L, "POLYMARKET", OrderState.PLACED);

        invokeCheckArbitrage(FIRST_PAIR_ID);

        verify(arbitrageOrderService).createOrders(any(), any(), any(), any());
        verify(orderStatusChecker, never()).checkUnfinishedOrdersForPair(any(), any());
        assertThat(activePairCount()).isEqualTo(1);
    }

    @Test
    void oppositeDirectionForTheSameTickersIsBlocked() {
        PriceSnapshot pm = PriceSnapshot.builder()
                .yesAsk(new BigDecimal("0.80"))
                .noAsk(new BigDecimal("0.40"))
                .build();
        PriceSnapshot ks = PriceSnapshot.builder()
                .yesAsk(new BigDecimal("0.40"))
                .noAsk(new BigDecimal("0.80"))
                .build();
        when(priceCache.getPmPrice(any())).thenReturn(pm);
        when(priceCache.getKsPrice(any())).thenReturn(ks);
        addOrder(UUID.randomUUID(), 10L, "KALSHI", OrderState.PLACED);

        invokeCheckArbitrage(FIRST_PAIR_ID);

        verify(arbitrageOrderService, never()).createOrders(any(), any(), any(), any());
        assertThat(savedEndReason()).isEqualTo(OpportunityCloseReason.ACTIVE_PAIR_LIMIT_REACHED);
    }

    @Test
    void missingTickerFailsClosedBeforeAnOpportunityIsSaved() {
        tickerMap("ksTickers").remove(FIRST_PAIR_ID);

        invokeCheckArbitrage(FIRST_PAIR_ID);

        verify(eventRepository, never()).save(any());
        verify(arbitrageOrderService, never()).createOrders(any(), any(), any(), any());
        verify(settingsService, never()).getActivePairsLimit();
    }


    @Test
    void incompleteRegistryBlocksOrderSubmission() {
        ReflectionTestUtils.setField(monitorService, "activePairRegistryReady", false);

        invokeCheckArbitrage(FIRST_PAIR_ID);

        verify(arbitrageOrderService, never()).createOrders(any(), any(), any(), any());
        verify(orderStatusChecker, never()).checkUnfinishedOrdersForPair(any(), any());
        assertThat(savedEndReason()).isEqualTo(OpportunityCloseReason.ORDER_CREATION_FAILED);
    }

    @Test
    void settingsLookupFailureFailsClosed() {
        when(settingsService.getActivePairsLimit()).thenThrow(new IllegalStateException("invalid setting"));

        invokeCheckArbitrage(FIRST_PAIR_ID);

        verify(arbitrageOrderService, never()).createOrders(any(), any(), any(), any());
        assertThat(activePairCount()).isZero();
        assertThat(savedEndReason()).isEqualTo(OpportunityCloseReason.ORDER_CREATION_FAILED);
    }

    @Test
    void twoSimilarityIdsForOneTickerPairReachOrderServiceOnlyOnceAtLimitOne() throws Exception {
        CountDownLatch firstCreationEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstCreation = new CountDownLatch(1);
        doAnswer(invocation -> {
            UUID uuid = invocation.getArgument(0);
            addOrder(uuid, 99L, "POLYMARKET", OrderState.PENDING);
            firstCreationEntered.countDown();
            assertThat(releaseFirstCreation.await(2, TimeUnit.SECONDS)).isTrue();
            return OpportunityCloseReason.ORDERS_CREATED;
        }).when(arbitrageOrderService).createOrders(any(), any(), any(), any());

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<?> first = executor.submit(() -> invokeCheckArbitrage(FIRST_PAIR_ID));
            assertThat(firstCreationEntered.await(1, TimeUnit.SECONDS)).isTrue();
            Future<?> second = executor.submit(() -> invokeCheckArbitrage(SECOND_PAIR_ID));

            releaseFirstCreation.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);

            verify(arbitrageOrderService, times(1)).createOrders(any(), any(), any(), any());
            verify(orderStatusChecker).checkUnfinishedOrdersForPair(PM_TICKER, KS_TICKER);
            assertThat(activePairCount()).isEqualTo(1);
            assertThat(savedEndReasons()).contains(OpportunityCloseReason.ACTIVE_PAIR_LIMIT_REACHED);
        } finally {
            releaseFirstCreation.countDown();
            executor.shutdownNow();
        }
    }

    private void addOrder(UUID uuid, long orderId, String platform, OrderState state) {
        monitorService.onArbitrageOrderLifecycle(new ArbitrageOrderLifecycleEvent(
                uuid, PM_TICKER, KS_TICKER, orderId, platform, state));
    }

    private void registerPair(long pairId, String pmTicker, String ksTicker) {
        tickerMap("pmSlugs").put(pairId, pmTicker);
        tickerMap("ksTickers").put(pairId, ksTicker);
    }

    private void invokeCheckArbitrage(long pairId) {
        ReflectionTestUtils.invokeMethod(monitorService, "checkArbitrage", pairId);
    }

    private int activePairCount() {
        return activePairs().values().stream()
                .mapToInt(value -> ((Map<?, ?>) value).size())
                .sum();
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> activePairs() {
        return (Map<Object, Object>) ReflectionTestUtils.getField(monitorService, "activeOrderPairs");
    }

    @SuppressWarnings("unchecked")
    private Map<Long, String> tickerMap(String fieldName) {
        return (Map<Long, String>) ReflectionTestUtils.getField(monitorService, fieldName);
    }

    private OpportunityCloseReason savedEndReason() {
        return savedEndReasons().getLast();
    }

    private java.util.List<OpportunityCloseReason> savedEndReasons() {
        ArgumentCaptor<ArbitrageEvent> events = ArgumentCaptor.forClass(ArbitrageEvent.class);
        verify(eventRepository, org.mockito.Mockito.atLeastOnce()).save(events.capture());
        return events.getAllValues().stream()
                .filter(event -> "OPPORTUNITY_END".equals(event.getEventType()))
                .map(ArbitrageEvent::getCloseReason)
                .toList();
    }
}
