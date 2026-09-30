package hzpro.com.tradingdesk.arbitrage.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.when;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.context.ActiveProfiles;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageDirection;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageOrderLifecycleEvent;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.model.OpportunityCloseReason;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;
import hzpro.com.tradingdesk.config.SettingsService;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.domain.orderbook.OrderBook;
import hzpro.com.tradingdesk.domain.orderbook.OrderBookEntry;
import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;

@ExtendWith(MockitoExtension.class)
@SpringBootTest
@ActiveProfiles("test")
class ArbitrageOrderServiceTest {

    @Mock
    private ArbitrageOrderRepository orderRepository;
    @Mock
    private PolymarketFetchCommonOrderBookService pmOrderBookService;
    @Mock
    private KalshiFetchCommonOrderBookService ksOrderBookService;
    @Mock
    private KalshiTradingService kalshiTradingService;
    @Mock
    private PolymarketTradingService polymarketTradingService;
    @Mock
    private PredictionMarketRepository pmRepo;
    @Mock
    private BalanceService balanceService;
    @Mock
    private ApplicationEventPublisher eventPublisher;

    @Autowired
    private SettingsService settingsService;

    @Autowired
    private ArbitrageOrderService service;

    @BeforeEach
    void setUp() {
        ArbitrageConfig config = new ArbitrageConfig();
        config.setMaxOrderCost(BigDecimal.valueOf(6));

        service = new ArbitrageOrderService(
                orderRepository, pmOrderBookService, ksOrderBookService, kalshiTradingService,
                polymarketTradingService, config, pmRepo, balanceService, Runnable::run, settingsService,
                eventPublisher);

        settingsService.resetErrorsCount();

    }

    @Test
    void addsFailedVenueCountAndResetsAfterSuccessfulOrderPair() {
        stubOrderCreation();
        when(polymarketTradingService.placeOrder(any(), any(), anyLong(), any()))
                .thenReturn(new PolymarketTradingService.OrderResult(false, null, null, "failed"));
        when(kalshiTradingService.placeOrder(any(), any(), anyInt(), any()))
                .thenReturn(new KalshiTradingService.OrderResult(true, "ks-order", "placed", null));

        assertThat(createOrders()).isEqualTo(OpportunityCloseReason.ORDERS_CREATED);
        assertThat(service.getErrorsCount()).isEqualTo(1);

    }

    @Test
    void notAddingErrorsCountIfThereIsNoErrors() {
        stubOrderCreation();

        when(kalshiTradingService.placeOrder(any(), any(), anyInt(), any()))
                .thenReturn(new KalshiTradingService.OrderResult(true, "ks-order", "placed", null));

        when(polymarketTradingService.placeOrder(any(), any(), anyLong(), any()))
                .thenReturn(new PolymarketTradingService.OrderResult(true, "pm-order", "placed", null));

        assertThat(createOrders()).isEqualTo(OpportunityCloseReason.ORDERS_CREATED);
        assertThat(service.getErrorsCount()).isZero();

    }

    @Test
    void addsTwoErrorsWhenOrderCreationThrows() {
        when(pmOrderBookService.fetchCommonOrderBook(any(), any(YesOrNoResult.class)))
                .thenThrow(new IllegalStateException("Polymarket unavailable"));

        assertThat(createOrders()).isEqualTo(OpportunityCloseReason.ORDER_CREATION_FAILED);
        assertThat(service.getErrorsCount()).isEqualTo(2);
    }

    @Test
    void returnsOrderbookEmptyWhenEitherOrderbookIsMissing() {
        assertThat(createOrders()).isEqualTo(OpportunityCloseReason.ORDERBOOK_EMPTY);
    }

    @Test
    void returnsOrderbookPriceBelowThresholdWhenRevalidatedSpreadIsTooLow() {
        stubOrderBooks(new BigDecimal("0.60"), 10L);

        assertThat(createOrders())
                .isEqualTo(OpportunityCloseReason.ORDERBOOK_PRICE_BELOW_THRESHOLD);
    }

    @Test
    void returnsNotEnoughAmountWhenOrderbookCannotMeetMinimumSize() {
        stubOrderBooks(new BigDecimal("0.20"), 4L);

        assertThat(createOrders())
                .isEqualTo(OpportunityCloseReason.ORDER_BOOK_NOT_ENOUGH_AMOUT);
    }

    @Test
    void returnsMinimumOrderCostNotMetWhenNotionalIsTooSmall() {
        stubOrderBooks(new BigDecimal("0.10"), 5L);

        assertThat(createOrders())
                .isEqualTo(OpportunityCloseReason.MINIMIM_ORDER_COST_NOT_MET);
        verifyNoInteractions(eventPublisher);
    }

    @Test
    void publishesOnlyFinalLifecycleEventsWithPersistedIds() {
        stubOrderCreationWithIds();
        when(kalshiTradingService.placeOrder(any(), any(), anyInt(), any()))
                .thenReturn(new KalshiTradingService.OrderResult(true, "ks-order", "placed", null));
        when(polymarketTradingService.placeOrder(any(), any(), anyLong(), any()))
                .thenReturn(new PolymarketTradingService.OrderResult(true, "pm-order", "placed", null));
        UUID uuid = UUID.randomUUID();

        assertThat(service.createOrders(uuid, "pm-ticker", "ks-ticker", ArbitrageDirection.PM_YES_KS_NO))
                .isEqualTo(OpportunityCloseReason.ORDERS_CREATED);

        InOrder submissionOrder = inOrder(kalshiTradingService, polymarketTradingService, eventPublisher);
        submissionOrder.verify(kalshiTradingService).placeOrder(any(), any(), anyInt(), any());
        submissionOrder.verify(polymarketTradingService).placeOrder(any(), any(), anyLong(), any());
        submissionOrder.verify(eventPublisher).publishEvent((Object) argThat(
                (ArbitrageOrderLifecycleEvent event) -> event.status() == OrderState.PLACED
                        && event.platform().equals("KALSHI")));
        submissionOrder.verify(eventPublisher).publishEvent((Object) argThat(
                (ArbitrageOrderLifecycleEvent event) -> event.status() == OrderState.PLACED
                        && event.platform().equals("POLYMARKET")));

        ArgumentCaptor<ArbitrageOrderLifecycleEvent> events =
                ArgumentCaptor.forClass(ArbitrageOrderLifecycleEvent.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues()).extracting(ArbitrageOrderLifecycleEvent::status)
                .containsExactly(OrderState.PLACED, OrderState.PLACED);
        assertThat(events.getAllValues()).allSatisfy(event -> {
            assertThat(event.opportunityUuid()).isEqualTo(uuid);
            assertThat(event.polymarketTicker()).isEqualTo("pm-ticker");
            assertThat(event.kalshiTicker()).isEqualTo("ks-ticker");
            assertThat(event.databaseOrderId()).isNotNull();
        });
        assertThat(events.getAllValues()).extracting(ArbitrageOrderLifecycleEvent::platform)
                .containsExactly("KALSHI", "POLYMARKET");
    }

    @Test
    void publishesTerminalErrorForFailedLeg() {
        stubOrderCreationWithIds();
        when(kalshiTradingService.placeOrder(any(), any(), anyInt(), any()))
                .thenReturn(new KalshiTradingService.OrderResult(true, "ks-order", "placed", null));
        when(polymarketTradingService.placeOrder(any(), any(), anyLong(), any()))
                .thenReturn(new PolymarketTradingService.OrderResult(false, null, null, "rejected"));

        assertThat(createOrders()).isEqualTo(OpportunityCloseReason.ORDERS_CREATED);

        ArgumentCaptor<ArbitrageOrderLifecycleEvent> events =
                ArgumentCaptor.forClass(ArbitrageOrderLifecycleEvent.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues()).extracting(ArbitrageOrderLifecycleEvent::status)
                .containsExactly(OrderState.PLACED, OrderState.ERROR);
    }

    @Test
    void publishesTerminalErrorsWhenBothLegsFail() {
        stubOrderCreationWithIds();
        when(kalshiTradingService.placeOrder(any(), any(), anyInt(), any()))
                .thenReturn(new KalshiTradingService.OrderResult(false, null, null, "rejected"));
        when(polymarketTradingService.placeOrder(any(), any(), anyLong(), any()))
                .thenReturn(new PolymarketTradingService.OrderResult(false, null, null, "rejected"));

        assertThat(createOrders()).isEqualTo(OpportunityCloseReason.ORDERS_CREATED);

        ArgumentCaptor<ArbitrageOrderLifecycleEvent> events =
                ArgumentCaptor.forClass(ArbitrageOrderLifecycleEvent.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues()).extracting(ArbitrageOrderLifecycleEvent::status)
                .containsExactly(OrderState.ERROR, OrderState.ERROR);
    }

    @Test
    void venueSubmissionExceptionDoesNotPersistOrPublishPendingOrders() {
        stubOrderPrerequisites();
        when(polymarketTradingService.placeOrder(any(), any(), anyLong(), any()))
                .thenThrow(new IllegalStateException("venue unavailable"));

        assertThat(createOrders()).isEqualTo(OpportunityCloseReason.ORDER_CREATION_FAILED);

        verifyNoInteractions(orderRepository, eventPublisher);
    }

    @Test
    void emptyPolymarketPlacementStatusRemainsUnknownUntilStatusCheck() {
        stubOrderCreationWithIds();
        when(kalshiTradingService.placeOrder(any(), any(), anyInt(), any()))
                .thenReturn(new KalshiTradingService.OrderResult(true, "ks-order", "resting", null));
        when(polymarketTradingService.placeOrder(any(), any(), anyLong(), any()))
                .thenReturn(new PolymarketTradingService.OrderResult(true, "pm-order", "", null));

        assertThat(createOrders()).isEqualTo(OpportunityCloseReason.ORDERS_CREATED);

        ArgumentCaptor<ArbitrageOrderLifecycleEvent> events =
                ArgumentCaptor.forClass(ArbitrageOrderLifecycleEvent.class);
        verify(eventPublisher, times(2)).publishEvent(events.capture());
        assertThat(events.getAllValues()).extracting(ArbitrageOrderLifecycleEvent::status)
                .containsExactly(OrderState.PLACED, OrderState.UNKNOWN);
    }

    private OpportunityCloseReason createOrders() {
        return service.createOrders(UUID.randomUUID(), "pm-ticker", "ks-ticker", ArbitrageDirection.PM_YES_KS_NO);
    }

    private void stubOrderCreation() {
        stubOrderPrerequisites();
        when(orderRepository.save(any(ArbitrageOrder.class))).thenAnswer(invocation -> invocation.getArgument(0));
    }

    private void stubOrderPrerequisites() {
        stubOrderBooks(new BigDecimal("0.20"), 10L);
        when(pmRepo.findFirstByMarketTickerAndDatasourceOrderByCreatedAtDesc(any(), any(DataSource.class)))
                .thenReturn(Optional.of(PredictionMarket.builder().yesTokenId("yes-token").build()));
    }

    private void stubOrderCreationWithIds() {
        stubOrderPrerequisites();
        AtomicLong ids = new AtomicLong();
        when(orderRepository.save(any(ArbitrageOrder.class))).thenAnswer(invocation -> {
            ArbitrageOrder order = invocation.getArgument(0);
            if (order.getId() == null) {
                order.setId(ids.incrementAndGet());
            }
            return order;
        });
    }

    private void stubOrderBooks(BigDecimal price, long amount) {
        OrderBook orderBook = new OrderBook(List.of(new OrderBookEntry(price, amount)));
        when(pmOrderBookService.fetchCommonOrderBook(any(), any(YesOrNoResult.class))).thenReturn(orderBook);
        when(ksOrderBookService.fetchCommonOrderBook(any(), any(YesOrNoResult.class))).thenReturn(orderBook);
    }
}
