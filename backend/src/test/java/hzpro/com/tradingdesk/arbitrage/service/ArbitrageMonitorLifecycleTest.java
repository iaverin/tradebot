package hzpro.com.tradingdesk.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.test.util.ReflectionTestUtils;

import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageDirection;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageOrderLifecycleEvent;
import hzpro.com.tradingdesk.arbitrage.model.OpportunityCloseReason;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.model.OrderStatusRefreshResult;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventMarketsInfoRepository;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageEventRepository;
import hzpro.com.tradingdesk.arbitrage.rest.KalshiPriceRestClient;
import hzpro.com.tradingdesk.arbitrage.rest.PolymarketPriceRestClient;
import hzpro.com.tradingdesk.config.ProxyConfig;
import hzpro.com.tradingdesk.config.SettingsService;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import hzpro.com.tradingdesk.similarmarkets.repository.SimilarMarketsRepository;

@ExtendWith(MockitoExtension.class)
class ArbitrageMonitorLifecycleTest {

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
        lenient().when(predictionMarketRepository.findActivePairs(anyInt())).thenReturn(List.of());
        lenient().when(orderStatusChecker.checkUnfinishedOrders())
                .thenReturn(new OrderStatusRefreshResult(true, 0, 0));
    }

    @Test
    void serializesConcurrentRestarts() throws Exception {
        CountDownLatch firstRestartEntered = new CountDownLatch(1);
        CountDownLatch releaseFirstRestart = new CountDownLatch(1);
        AtomicBoolean firstInvocation = new AtomicBoolean(true);

        doAnswer(invocation -> {
            if (firstInvocation.compareAndSet(true, false)) {
                firstRestartEntered.countDown();
                assertThat(releaseFirstRestart.await(2, TimeUnit.SECONDS)).isTrue();
            }
            return null;
        }).when(settingsService).loadSettings();

        ExecutorService callers = Executors.newFixedThreadPool(2);
        AtomicReference<Thread> secondCaller = new AtomicReference<>();
        CountDownLatch secondRestartAttempted = new CountDownLatch(1);

        try {
            Future<?> first = callers.submit(monitorService::restart);
            assertThat(firstRestartEntered.await(1, TimeUnit.SECONDS)).isTrue();

            Future<?> second = callers.submit(() -> {
                secondCaller.set(Thread.currentThread());
                secondRestartAttempted.countDown();
                monitorService.restart();
            });
            assertThat(secondRestartAttempted.await(1, TimeUnit.SECONDS)).isTrue();
            assertThat(awaitThreadState(secondCaller.get(), Thread.State.BLOCKED)).isTrue();

            releaseFirstRestart.countDown();
            first.get(1, TimeUnit.SECONDS);
            second.get(1, TimeUnit.SECONDS);
        } finally {
            releaseFirstRestart.countDown();
            callers.shutdownNow();
        }
    }

    @Test
    void closesUnclosedOpportunitiesOnStartWithoutReadingPrices() {
        UUID uuid = UUID.randomUUID();
        ArbitrageEvent start = ArbitrageEvent.builder()
                .id(42L)
                .uuid(uuid)
                .eventType("OPPORTUNITY_START")
                .similarMarketId(7L)
                .polymarketMarketTicker("PM-TEST")
                .kalshiMarketTicker("KS-TEST")
                .direction(ArbitrageDirection.PM_YES_KS_NO.getCode())
                .polymarketYesAsk(new BigDecimal("0.41"))
                .polymarketNoAsk(new BigDecimal("0.61"))
                .kalshiYesAsk(new BigDecimal("0.62"))
                .kalshiNoAsk(new BigDecimal("0.40"))
                .spread(new BigDecimal("0.19"))
                .threshold(new BigDecimal("0.01"))
                .polymarketOrderbook("{\"source\":\"start\"}")
                .kalshiOrderbook("{\"source\":\"start\"}")
                .build();
        when(eventRepository.findUnclosedOpportunities()).thenReturn(List.of(start));
        when(config.getThreshold()).thenReturn(start.getThreshold());

        monitorService.start();

        ArgumentCaptor<ArbitrageEvent> savedEvent = ArgumentCaptor.forClass(ArbitrageEvent.class);
        verify(eventRepository).save(savedEvent.capture());
        ArbitrageEvent end = savedEvent.getValue();
        assertThat(end.getEventType()).isEqualTo("OPPORTUNITY_END");
        assertThat(end.getOpportunityStart()).isSameAs(start);
        assertThat(end.getCloseReason()).isEqualTo(OpportunityCloseReason.PRICE_DATA_UNAVAILABLE);
        assertThat(end.getUuid()).isEqualTo(uuid);
        assertThat(end.getSpread()).isEqualByComparingTo(start.getSpread());
        assertThat(end.getThreshold()).isEqualByComparingTo(start.getThreshold());
        assertThat(end.getPolymarketYesAsk()).isEqualByComparingTo(start.getPolymarketYesAsk());
        assertThat(end.getKalshiNoAsk()).isEqualByComparingTo(start.getKalshiNoAsk());
        assertThat(end.getPolymarketOrderbook()).isEqualTo("{}");
        assertThat(end.getKalshiOrderbook()).isEqualTo("{}");
        assertThat(monitorService.getActiveOpportunities()).isEmpty();
        verifyNoInteractions(priceCache);
    }

    @Test
    void startupClearsStaleRegistryAndRebuildsItOnlyFromStatusEvents() {
        UUID staleUuid = UUID.randomUUID();
        UUID refreshedUuid = UUID.randomUUID();
        monitorService.onArbitrageOrderLifecycle(new ArbitrageOrderLifecycleEvent(
                staleUuid, "PM-STALE", "KS-STALE", 1L, "POLYMARKET", OrderState.PLACED));
        when(orderStatusChecker.checkUnfinishedOrders()).thenAnswer(invocation -> {
            monitorService.onArbitrageOrderLifecycle(new ArbitrageOrderLifecycleEvent(
                    refreshedUuid, "PM-LIVE", "KS-LIVE", 2L, "KALSHI", OrderState.PENDING));
            return new OrderStatusRefreshResult(true, 1, 0);
        });

        monitorService.start();

        assertThat(registry()).hasSize(1);
        assertThat(registry().values()).anyMatch(
                value -> ((Map<?, ?>) value).containsKey(refreshedUuid));
        assertThat(registry().values()).noneMatch(
                value -> ((Map<?, ?>) value).containsKey(staleUuid));
        assertThat(ReflectionTestUtils.getField(monitorService, "activePairRegistryReady")).isEqualTo(true);
    }

    @Test
    void failedStartupRefreshLeavesRegistryUnreadyAndDoesNotStartMonitoring() {
        when(orderStatusChecker.checkUnfinishedOrders()).thenReturn(OrderStatusRefreshResult.failed());

        monitorService.start();

        assertThat(registry()).isEmpty();
        assertThat(ReflectionTestUtils.getField(monitorService, "activePairRegistryReady")).isEqualTo(false);
        assertThat(monitorService.isRunning()).isFalse();
        verify(eventRepository, never()).findUnclosedOpportunities();
        verify(predictionMarketRepository, never()).findActivePairs(anyInt());
    }

    @SuppressWarnings("unchecked")
    private Map<Object, Object> registry() {
        return (Map<Object, Object>) ReflectionTestUtils.getField(monitorService, "activeOrderPairs");
    }

    private boolean awaitThreadState(Thread thread, Thread.State expected) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(1);
        while (System.nanoTime() < deadline) {
            if (thread.getState() == expected) {
                return true;
            }
            Thread.sleep(10);
        }
        return false;
    }
}
