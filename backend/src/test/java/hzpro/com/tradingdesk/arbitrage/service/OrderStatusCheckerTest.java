package hzpro.com.tradingdesk.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anySet;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageOrderLifecycleEvent;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.model.OrderStatusRefreshResult;
import hzpro.com.tradingdesk.arbitrage.model.UnfinishedOrderCandidate;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;

@ExtendWith(MockitoExtension.class)
class OrderStatusCheckerTest {

    private static final String PM_TICKER = "pm-market";
    private static final String KS_TICKER = "ks-market";

    @Mock private ArbitrageOrderRepository orderRepository;
    @Mock private KalshiTradingService kalshiTradingService;
    @Mock private PolymarketTradingService polymarketTradingService;
    @Mock private ApplicationEventPublisher eventPublisher;

    private OrderStatusChecker checker;

    @BeforeEach
    void setUp() {
        checker = new OrderStatusChecker(
                orderRepository,
                kalshiTradingService,
                polymarketTradingService,
                eventPublisher);
    }

    @Test
    void polymarketMatchedBecomesExecutedWithFillFieldsAndPublishesTerminalState() {
        ArbitrageOrder order = order(1L, "POLYMARKET", OrderState.PLACED, "pm-order");
        stubAllCandidates(order);
        when(polymarketTradingService.getOrderStatus("pm-order"))
                .thenReturn(new PolymarketTradingService.OrderStatus(
                        "pm-order", "MATCHED", "3.9", null));
        when(orderRepository.save(order)).thenReturn(order);

        OrderStatusRefreshResult result = checker.checkUnfinishedOrders();

        assertThat(result).isEqualTo(new OrderStatusRefreshResult(true, 1, 1));
        assertThat(order.getStatus()).isEqualTo(OrderState.EXECUTED);
        assertThat(order.getExecutedAt()).isNotNull();
        assertThat(order.getFilledQuantity()).isEqualTo(3L);
        assertThat(order.getFilledAmount()).isEqualByComparingTo("1.20");
        assertPublishedState(order, OrderState.EXECUTED);
    }

    @Test
    void emptyPolymarketStatusCheckBecomesExecuted() {
        ArbitrageOrder order = order(11L, "POLYMARKET", OrderState.UNKNOWN, "pm-order");
        stubAllCandidates(order);
        when(polymarketTradingService.getOrderStatus("pm-order"))
                .thenReturn(new PolymarketTradingService.OrderStatus(
                        "pm-order", "", "3.9", null));
        when(orderRepository.save(order)).thenReturn(order);

        OrderStatusRefreshResult result = checker.checkUnfinishedOrders();

        assertThat(result).isEqualTo(new OrderStatusRefreshResult(true, 1, 1));
        assertThat(order.getStatus()).isEqualTo(OrderState.EXECUTED);
        assertThat(order.getExecutedAt()).isNotNull();
        assertThat(order.getFilledQuantity()).isEqualTo(3L);
        assertThat(order.getFilledAmount()).isEqualByComparingTo("1.20");
        assertPublishedState(order, OrderState.EXECUTED);
    }

    @Test
    void kalshiMatchedRemainsPlacedAndStillPublishesEffectiveState() {
        ArbitrageOrder order = order(2L, "KALSHI", OrderState.PLACED, "ks-order");
        stubAllCandidates(order);
        when(kalshiTradingService.getOrderStatus("ks-order"))
                .thenReturn(new KalshiTradingService.OrderStatus(
                        "ks-order", "matched", "2", "8"));

        OrderStatusRefreshResult result = checker.checkUnfinishedOrders();

        assertThat(result).isEqualTo(new OrderStatusRefreshResult(true, 1, 0));
        verify(orderRepository, never()).save(order);
        assertPublishedState(order, OrderState.PLACED);
    }

    @Test
    void providerErrorRetainsPriorActiveStateAndDoesNotPersistError() {
        ArbitrageOrder order = order(3L, "POLYMARKET", OrderState.UNKNOWN, "pm-order");
        stubAllCandidates(order);
        when(polymarketTradingService.getOrderStatus("pm-order"))
                .thenReturn(new PolymarketTradingService.OrderStatus(
                        "pm-order", "error", "0", "HTTP 503"));

        OrderStatusRefreshResult result = checker.checkUnfinishedOrders();

        assertThat(result).isEqualTo(new OrderStatusRefreshResult(true, 1, 0));
        assertThat(order.getStatus()).isEqualTo(OrderState.UNKNOWN);
        verify(orderRepository, never()).save(order);
        assertPublishedState(order, OrderState.UNKNOWN);
    }

    @Test
    void providerTransportExceptionRetainsAndPublishesPriorActiveState() {
        ArbitrageOrder order = order(31L, "KALSHI", OrderState.CREATED, "ks-order");
        stubAllCandidates(order);
        when(kalshiTradingService.getOrderStatus("ks-order"))
                .thenThrow(new IllegalStateException("timeout"));

        assertThat(checker.checkUnfinishedOrders())
                .isEqualTo(new OrderStatusRefreshResult(true, 1, 0));

        verify(orderRepository, never()).save(order);
        assertPublishedState(order, OrderState.CREATED);
    }

    @Test
    void nullProviderResponseRetainsAndPublishesPriorActiveState() {
        ArbitrageOrder order = order(32L, "POLYMARKET", OrderState.PLACED, "pm-order");
        stubAllCandidates(order);
        when(polymarketTradingService.getOrderStatus("pm-order")).thenReturn(null);

        assertThat(checker.checkUnfinishedOrders())
                .isEqualTo(new OrderStatusRefreshResult(true, 1, 0));

        verify(orderRepository, never()).save(order);
        assertPublishedState(order, OrderState.PLACED);
    }

    @Test
    void activeOrderWithoutProviderIdIsPublishedWithoutVenueLookup() {
        ArbitrageOrder order = order(4L, "POLYMARKET", OrderState.PENDING, null);
        stubAllCandidates(order);

        assertThat(checker.checkUnfinishedOrders())
                .isEqualTo(new OrderStatusRefreshResult(true, 1, 0));

        verify(polymarketTradingService, never()).getOrderStatus(org.mockito.ArgumentMatchers.any());
        verify(kalshiTradingService, never()).getOrderStatus(org.mockito.ArgumentMatchers.any());
        assertPublishedState(order, OrderState.PENDING);
    }

    @Test
    void candidateSelectionFailureReturnsIncompleteResult() {
        when(orderRepository.findUnfinishedOrderCandidates(anySet()))
                .thenThrow(new IllegalStateException("database unavailable"));

        assertThat(checker.checkUnfinishedOrders()).isEqualTo(OrderStatusRefreshResult.failed());
    }

    @Test
    void allPairsAndExactPairRefreshesCannotOverlap() throws Exception {
        ArbitrageOrder order = order(5L, "KALSHI", OrderState.PLACED, "ks-order");
        UnfinishedOrderCandidate candidate = candidate(order);
        when(orderRepository.findUnfinishedOrderCandidates(anySet())).thenReturn(List.of(candidate));
        when(orderRepository.findUnfinishedOrderCandidatesForPair(PM_TICKER, KS_TICKER, OrderState.activeStates()))
                .thenReturn(List.of(candidate));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));

        CountDownLatch firstLookupStarted = new CountDownLatch(1);
        CountDownLatch releaseFirstLookup = new CountDownLatch(1);
        AtomicInteger currentLookups = new AtomicInteger();
        AtomicInteger maximumLookups = new AtomicInteger();
        when(kalshiTradingService.getOrderStatus("ks-order")).thenAnswer(invocation -> {
            int current = currentLookups.incrementAndGet();
            maximumLookups.accumulateAndGet(current, Math::max);
            try {
                firstLookupStarted.countDown();
                releaseFirstLookup.await(2, TimeUnit.SECONDS);
                return new KalshiTradingService.OrderStatus("ks-order", "live", "0", "10");
            } finally {
                currentLookups.decrementAndGet();
            }
        });

        ExecutorService executor = Executors.newFixedThreadPool(2);
        try {
            Future<OrderStatusRefreshResult> all = executor.submit(checker::checkUnfinishedOrders);
            assertThat(firstLookupStarted.await(1, TimeUnit.SECONDS)).isTrue();
            Future<OrderStatusRefreshResult> pair = executor.submit(
                    () -> checker.checkUnfinishedOrdersForPair(PM_TICKER, KS_TICKER));

            assertThatThrownBy(() -> pair.get(150, TimeUnit.MILLISECONDS))
                    .isInstanceOf(TimeoutException.class);
            releaseFirstLookup.countDown();

            assertThat(all.get(1, TimeUnit.SECONDS).completed()).isTrue();
            assertThat(pair.get(1, TimeUnit.SECONDS).completed()).isTrue();
            assertThat(maximumLookups).hasValue(1);
        } finally {
            releaseFirstLookup.countDown();
            executor.shutdownNow();
        }
    }

    private void stubAllCandidates(ArbitrageOrder order) {
        when(orderRepository.findUnfinishedOrderCandidates(anySet()))
                .thenReturn(List.of(candidate(order)));
        when(orderRepository.findById(order.getId())).thenReturn(Optional.of(order));
    }

    private UnfinishedOrderCandidate candidate(ArbitrageOrder order) {
        return new UnfinishedOrderCandidate(
                order.getId(),
                order.getOpportunityUuid(),
                order.getPlatform(),
                order.getOrderId(),
                order.getStatus(),
                order.getPrice(),
                PM_TICKER,
                KS_TICKER);
    }

    private ArbitrageOrder order(long id, String platform, OrderState state, String providerOrderId) {
        return ArbitrageOrder.builder()
                .id(id)
                .opportunityUuid(UUID.randomUUID())
                .platform(platform)
                .contractType("YES")
                .price(new BigDecimal("0.40"))
                .quantity(10L)
                .orderId(providerOrderId)
                .status(state)
                .build();
    }

    private void assertPublishedState(ArbitrageOrder order, OrderState expectedState) {
        ArgumentCaptor<ArbitrageOrderLifecycleEvent> eventCaptor =
                ArgumentCaptor.forClass(ArbitrageOrderLifecycleEvent.class);
        verify(eventPublisher).publishEvent(eventCaptor.capture());
        ArbitrageOrderLifecycleEvent event = eventCaptor.getValue();
        assertThat(event.opportunityUuid()).isEqualTo(order.getOpportunityUuid());
        assertThat(event.databaseOrderId()).isEqualTo(order.getId());
        assertThat(event.platform()).isEqualTo(order.getPlatform());
        assertThat(event.polymarketTicker()).isEqualTo(PM_TICKER);
        assertThat(event.kalshiTicker()).isEqualTo(KS_TICKER);
        assertThat(event.status()).isEqualTo(expectedState);
    }
}
