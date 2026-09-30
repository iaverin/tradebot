package hzpro.com.tradingdesk.arbitrage.service;

import java.math.BigDecimal;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageDirection;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageOrderLifecycleEvent;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.model.OpportunityCloseReason;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;
import hzpro.com.tradingdesk.arbitrage.util.ArbitrageOrderUtils;
import hzpro.com.tradingdesk.config.SettingsService;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.domain.orderbook.OrderBook;
import hzpro.com.tradingdesk.domain.orderbook.OrderBookEntry;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Service
public class ArbitrageOrderService {

    private static final BigDecimal MIN_ORDER_COST = BigDecimal.valueOf(1);
    /** Polymarket default min_order_size (5 CTF tokens). */
    private static final long MIN_ORDER_SIZE = 5;

    private final ArbitrageOrderRepository orderRepository;
    private final PolymarketFetchCommonOrderBookService pmOrderBookService;
    private final KalshiFetchCommonOrderBookService ksOrderBookService;
    private final KalshiTradingService kalshiTradingService;
    private final PolymarketTradingService polymarketTradingService;
    private final ArbitrageConfig config;
    private final PredictionMarketRepository pmRepo;
    private final BalanceService balanceService;
    private final Executor arbitrageOrdersExecutor;
    private final SettingsService settingsService;
    private final ApplicationEventPublisher eventPublisher;

    public ArbitrageOrderService(
            ArbitrageOrderRepository orderRepository,
            PolymarketFetchCommonOrderBookService pmOrderBookService,
            KalshiFetchCommonOrderBookService ksOrderBookService,
            KalshiTradingService kalshiTradingService,
            PolymarketTradingService polymarketTradingService,
            ArbitrageConfig config,
            PredictionMarketRepository pmRepo,
            BalanceService balanceService,
            @Qualifier("arbitrageOrdersExecutor") Executor arbitrageOrdersExecutor,
            SettingsService settingsService,
            ApplicationEventPublisher eventPublisher
        ) {
        this.orderRepository = orderRepository;
        this.pmOrderBookService = pmOrderBookService;
        this.ksOrderBookService = ksOrderBookService;
        this.kalshiTradingService = kalshiTradingService;
        this.polymarketTradingService = polymarketTradingService;
        this.config = config;
        this.pmRepo = pmRepo;
        this.balanceService = balanceService;
        this.arbitrageOrdersExecutor = arbitrageOrdersExecutor;
        this.settingsService = settingsService;
        this.eventPublisher = eventPublisher;
    }

    public OpportunityCloseReason createOrders(
            UUID opportunityUuid,
            String pmTicker,
            String ksTicker,
            ArbitrageDirection direction
    ) {
        try {
            YesOrNoResult pmSide = direction == ArbitrageDirection.PM_YES_KS_NO ? YesOrNoResult.YES : YesOrNoResult.NO;
            YesOrNoResult ksSide = direction == ArbitrageDirection.PM_YES_KS_NO ? YesOrNoResult.NO : YesOrNoResult.YES;

            log.info("[uuid={}] started", opportunityUuid);

            CompletableFuture<OrderBook> pmBookFuture = CompletableFuture.supplyAsync(
                    () -> pmOrderBookService.fetchCommonOrderBook(pmTicker, pmSide), arbitrageOrdersExecutor);
            CompletableFuture<OrderBook> ksBookFuture = CompletableFuture.supplyAsync(
                    () -> ksOrderBookService.fetchCommonOrderBook(ksTicker, ksSide), arbitrageOrdersExecutor);

            CompletableFuture<String> pmTokenIdFuture = CompletableFuture.supplyAsync(
                    () -> pmRepo.findFirstByMarketTickerAndDatasourceOrderByCreatedAtDesc(
                            pmTicker, DataSource.POLYMARKET)
                            .map(m -> pmSide == YesOrNoResult.YES ? m.getYesTokenId() : m.getNoTokenId())
                            .orElse(null),
                    arbitrageOrdersExecutor);

            OrderBook pmBook = pmBookFuture.join();
            OrderBook ksBook = ksBookFuture.join();

            log.info("[uuid={}] Orderbooks fetched", opportunityUuid);

            if (pmBook == null || pmBook.asks().isEmpty() || ksBook == null || ksBook.asks().isEmpty()) {
                log.warn("Empty orderbook for {}: pm={}, ks={}", opportunityUuid,
                        pmBook != null ? pmBook.asks().size() : 0,
                        ksBook != null ? ksBook.asks().size() : 0);
                return OpportunityCloseReason.ORDERBOOK_EMPTY;
            }

            OrderBookEntry pmBest = pmBook.asks().get(0);
            OrderBookEntry ksBest = ksBook.asks().get(0);

            if (BigDecimal.ONE.subtract(pmBest.price().add(ksBest.price())).compareTo(config.getThreshold()) < 0) {
                log.info("[uuid={}] Orderbook prices below arb threshold: pm={}, ks={}", opportunityUuid,
                        pmBest.price(), ksBest.price());
                return OpportunityCloseReason.ORDERBOOK_PRICE_BELOW_THRESHOLD;
            }

            long orderSharesQuantity = produceOrderSharesQuantityCappedByMaxOrderCost(pmBest, ksBest);

            if (!checkOrderSizeGreaterThanMinimum(orderSharesQuantity)) {
                log.info(
                        "[uuid={}] Order quantity {} below minimum size {}. Skipping.",
                        opportunityUuid, orderSharesQuantity, MIN_ORDER_SIZE);
                return OpportunityCloseReason.ORDER_BOOK_NOT_ENOUGH_AMOUT;
            }

            if (!checkOrderCostGreaterThanMinimum(pmBest, ksBest, orderSharesQuantity)) {
                log.info(
                        "[uuid={}] Minimum order cost should be at least {}. orderSharesQuantity: {}, pmBest.price={}, ksBest.price={}",
                        opportunityUuid, MIN_ORDER_COST, orderSharesQuantity, pmBest.price(), ksBest.price());
                return OpportunityCloseReason.MINIMIM_ORDER_COST_NOT_MET;
            }

            CompletableFuture<KalshiTradingService.OrderResult> ksResultFuture = CompletableFuture.supplyAsync(
                    () -> kalshiTradingService.placeOrder(
                            ksTicker, ksSide, (int) orderSharesQuantity, ksBest.price()),
                    arbitrageOrdersExecutor);

            log.info("[uuid={}] Kalshi order shceduled", opportunityUuid);
            String pmTokenId = pmTokenIdFuture.join();

            log.info("[uuid={}] Polymarket token fetched", opportunityUuid);

            CompletableFuture<PolymarketTradingService.OrderResult> pmResultFuture = CompletableFuture
                    .supplyAsync(() -> polymarketTradingService.placeOrder(
                            pmTokenId, pmBest.price(), orderSharesQuantity,
                            "BUY"), arbitrageOrdersExecutor);

            log.info("[uuid={}] Polymarket order scheduled", opportunityUuid);

            PolymarketTradingService.OrderResult pmResult = pmResultFuture.join();
            KalshiTradingService.OrderResult ksResult = ksResultFuture.join();

            log.info("[uuid={}] Orders requests to venues done. ", opportunityUuid);

            ArbitrageOrder ksOrder = ArbitrageOrder.builder()
                    .opportunityUuid(opportunityUuid)
                    .platform("KALSHI")
                    .contractType(ksSide.name())
                    .price(ksBest.price())
                    .quantity(orderSharesQuantity)
                    .orderId(ksResult.success() ? ksResult.orderId() : null)
                    .status(ksResult.success()
                            ? ProviderOrderStateMapper.fromPlacement("KALSHI", ksResult.status())
                            : OrderState.ERROR)
                    .build();
            saveAndPublish(ksOrder, pmTicker, ksTicker);

            ArbitrageOrder pmOrder = ArbitrageOrder.builder()
                    .opportunityUuid(opportunityUuid)
                    .platform("POLYMARKET")
                    .contractType(pmSide.name())
                    .price(pmBest.price())
                    .quantity(orderSharesQuantity)
                    .orderId(pmResult.success() ? pmResult.orderId() : null)
                    .status(pmResult.success()
                            ? ProviderOrderStateMapper.fromPlacement("POLYMARKET", pmResult.status())
                            : OrderState.ERROR)
                    .build();
            saveAndPublish(pmOrder, pmTicker, ksTicker);

            if (pmResult.success() || ksResult.success()) {
                try {
                    balanceService.refreshBalances();
                } catch (Exception e) {
                    log.warn("Balance refresh after order placement failed: {}", e.getMessage());
                }
            }

            log.info("Orders for {}: qty={}, pm={}, ks={}", opportunityUuid, orderSharesQuantity,
                    pmResult.success() ? pmResult.orderId() : pmResult.error(),
                    ksResult.success() ? ksResult.orderId() : ksResult.error());

            updateErrorsCount((pmResult.success() ? 0 : 1) + (ksResult.success() ? 0 : 1));
            return OpportunityCloseReason.ORDERS_CREATED;

        } catch (Exception e) {
            updateErrorsCount(2);
            log.error("Failed to create orders for {}: {}", opportunityUuid, e.getMessage());
            return OpportunityCloseReason.ORDER_CREATION_FAILED;
        }
    }

    public int getErrorsCount() {
        return settingsService.getErrorsCount();
    }

    private void updateErrorsCount(int errors) {
        int updatedCount = settingsService.addAndGet(errors);
        log.info("Arbitrage order errors: added={}, consecutive={}", errors, updatedCount);
    }

    private boolean checkOrderSizeGreaterThanMinimum(long orderSharesQuantity) {
        return ArbitrageOrderUtils.checkOrderSizeGreaterThanMinimum(orderSharesQuantity, MIN_ORDER_SIZE);
    }

    private boolean checkOrderCostGreaterThanMinimum(OrderBookEntry pmBest, OrderBookEntry ksBest,
            long orderSharesQuantity) {
        return ArbitrageOrderUtils.checkOrderCostGreaterThanMinimum(pmBest, ksBest, orderSharesQuantity,
                MIN_ORDER_COST);
    }

    private long produceOrderSharesQuantityCappedByMaxOrderCost(OrderBookEntry pmBest, OrderBookEntry ksBest) {
        return ArbitrageOrderUtils.produceOrderSharesQuantityCappedByMaxOrderCost(pmBest, ksBest,
                config.getMaxOrderCost());
    }

    private ArbitrageOrder saveAndPublish(ArbitrageOrder order, String pmTicker, String ksTicker) {
        ArbitrageOrder saved = orderRepository.save(order);
        eventPublisher.publishEvent(new ArbitrageOrderLifecycleEvent(
                saved.getOpportunityUuid(),
                pmTicker,
                ksTicker,
                saved.getId(),
                saved.getPlatform(),
                saved.getStatus()));
        return saved;
    }
}
