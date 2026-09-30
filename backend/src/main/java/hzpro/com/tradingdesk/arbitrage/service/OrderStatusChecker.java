package hzpro.com.tradingdesk.arbitrage.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Supplier;

import org.springframework.context.ApplicationEventPublisher;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import hzpro.com.tradingdesk.arbitrage.config.OrderStatusSchedulerConfig;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageOrderLifecycleEvent;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.model.OrderStatusRefreshResult;
import hzpro.com.tradingdesk.arbitrage.model.UnfinishedOrderCandidate;
import hzpro.com.tradingdesk.arbitrage.repository.ArbitrageOrderRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@Component
@RequiredArgsConstructor
public class OrderStatusChecker {

    private final ArbitrageOrderRepository orderRepository;
    private final KalshiTradingService kalshiTradingService;
    private final PolymarketTradingService polymarketTradingService;
    private final ApplicationEventPublisher eventPublisher;
    private final ReentrantLock refreshLock = new ReentrantLock();

    @Scheduled(
            fixedDelay = 10_000,
            scheduler = OrderStatusSchedulerConfig.LOW_PRIORITY_SCHEDULER)
    public void checkPlacedOrders() {
        checkUnfinishedOrders();
    }

    public OrderStatusRefreshResult checkUnfinishedOrders() {
        return refresh(() -> orderRepository.findUnfinishedOrderCandidates(OrderState.activeStates()));
    }

    public OrderStatusRefreshResult checkUnfinishedOrdersForPair(
            String polymarketTicker,
            String kalshiTicker) {
        return refresh(() -> orderRepository.findUnfinishedOrderCandidatesForPair(
                polymarketTicker,
                kalshiTicker,
                OrderState.activeStates()));
    }

    private OrderStatusRefreshResult refresh(Supplier<List<UnfinishedOrderCandidate>> candidateSupplier) {
        refreshLock.lock();
        try {
            List<UnfinishedOrderCandidate> candidates = candidateSupplier.get();
            int updatedOrders = 0;
            for (UnfinishedOrderCandidate candidate : candidates) {
                if (refreshCandidate(candidate)) {
                    updatedOrders++;
                }
                Thread.yield();
            }
            return new OrderStatusRefreshResult(true, candidates.size(), updatedOrders);
        } catch (Exception exception) {
            log.warn("Failed to refresh unfinished order statuses: {}", exception.getMessage());
            return OrderStatusRefreshResult.failed();
        } finally {
            refreshLock.unlock();
        }
    }

    private boolean refreshCandidate(UnfinishedOrderCandidate candidate) {
        ArbitrageOrder order = orderRepository.findById(candidate.databaseOrderId()).orElse(null);
        if (order == null) {
            throw new IllegalStateException(
                    "Unfinished order " + candidate.databaseOrderId() + " disappeared before status refresh");
        }

        boolean updated = false;
        OrderState effectiveState = order.getStatus();
        if (order.getStatus().isActive() && order.getOrderId() != null && !order.getOrderId().isBlank()) {
            ProviderStatus providerStatus;
            try {
                providerStatus = fetchProviderStatus(order);
            } catch (Exception exception) {
                log.warn("Failed to check order {}: {}", order.getOrderId(), exception.getMessage());
                providerStatus = ProviderStatus.unconfirmed();
            }
            if (providerStatus.confirmed()) {
                OrderState normalized = ProviderOrderStateMapper.fromStatusCheck(
                        order.getPlatform(), providerStatus.status());
                if (normalized != order.getStatus()) {
                    OrderState previous = order.getStatus();
                    order.setStatus(normalized);
                    order.setUpdatedAt(Instant.now());
                    applyExecutionFields(order, normalized, providerStatus.filledCount());
                    order = orderRepository.save(order);
                    effectiveState = order.getStatus();
                    updated = true;
                    log.info("Order {} status updated: {} -> {}", order.getOrderId(), previous, normalized);
                }
            }
        }

        publishEffectiveState(candidate, order, effectiveState);
        return updated;
    }

    private ProviderStatus fetchProviderStatus(ArbitrageOrder order) {
        if ("KALSHI".equalsIgnoreCase(order.getPlatform())) {
            KalshiTradingService.OrderStatus status = kalshiTradingService.getOrderStatus(order.getOrderId());
            if (status == null || status.status() == null || status.status().isBlank()) {
                return ProviderStatus.unconfirmed();
            }
            return new ProviderStatus(true, status.status(), status.filledCount());
        }
        if ("POLYMARKET".equalsIgnoreCase(order.getPlatform())) {
            PolymarketTradingService.OrderStatus status = polymarketTradingService.getOrderStatus(order.getOrderId());
            if (status == null || status.error() != null || status.status() == null) {
                return ProviderStatus.unconfirmed();
            }
            return new ProviderStatus(true, status.status(), status.filledAmount());
        }
        log.warn("Unsupported order platform '{}' for order {}", order.getPlatform(), order.getId());
        return ProviderStatus.unconfirmed();
    }

    private void publishEffectiveState(
            UnfinishedOrderCandidate candidate,
            ArbitrageOrder order,
            OrderState effectiveState) {
        eventPublisher.publishEvent(new ArbitrageOrderLifecycleEvent(
                order.getOpportunityUuid(),
                candidate.polymarketTicker(),
                candidate.kalshiTicker(),
                order.getId(),
                order.getPlatform(),
                effectiveState));
    }

    private void applyExecutionFields(ArbitrageOrder order, OrderState state, String filledCount) {
        if (state != OrderState.EXECUTED) {
            return;
        }
        order.setExecutedAt(Instant.now());
        if (filledCount == null || filledCount.isBlank()) {
            return;
        }
        Long quantity = parseFilledCount(filledCount);
        order.setFilledQuantity(quantity);
        order.setFilledAmount(order.getPrice()
                .multiply(BigDecimal.valueOf(quantity))
                .setScale(2, RoundingMode.HALF_UP));
    }



    private Long parseFilledCount(String filledCount) {
        try {
            BigDecimal value = new BigDecimal(filledCount);
            return value.setScale(0, RoundingMode.FLOOR).longValue();
        } catch (NumberFormatException exception) {
            return 0L;
        }
    }

    private record ProviderStatus(boolean confirmed, String status, String filledCount) {
        private static ProviderStatus unconfirmed() {
            return new ProviderStatus(false, null, null);
        }
    }
}
