package hzpro.com.tradingdesk.arbitrage.repository;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.ArbitrageDirection;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.model.UnfinishedOrderCandidate;

@SpringBootTest
@ActiveProfiles("test")
class ArbitrageOrderRepositoryIntegrationTest {

    @Autowired private ArbitrageOrderRepository orderRepository;
    @Autowired private ArbitrageEventRepository eventRepository;

    @AfterEach
    void tearDown() {
        orderRepository.deleteAllInBatch();
        eventRepository.deleteAllInBatch();
    }

    @Test
    void unfinishedQueriesReturnActiveOrdersForTheRequestedExactPairOnly() {
        UUID firstDirection = saveStart("pm-a", "ks-a", ArbitrageDirection.PM_YES_KS_NO);
        UUID oppositeDirection = saveStart("pm-a", "ks-a", ArbitrageDirection.PM_NO_KS_YES);
        UUID unrelatedPair = saveStart("pm-a", "ks-b", ArbitrageDirection.PM_YES_KS_NO);

        ArbitrageOrder firstPending = saveOrder(firstDirection, "POLYMARKET", OrderState.PENDING);
        ArbitrageOrder firstPlaced = saveOrder(firstDirection, "KALSHI", OrderState.PLACED);
        ArbitrageOrder oppositeUnknown = saveOrder(oppositeDirection, "POLYMARKET", OrderState.UNKNOWN);
        ArbitrageOrder unrelatedCreated = saveOrder(unrelatedPair, "KALSHI", OrderState.CREATED);
        saveOrder(firstDirection, "POLYMARKET", OrderState.EXECUTED);
        saveOrder(oppositeDirection, "KALSHI", OrderState.CANCELED);
        saveOrder(unrelatedPair, "POLYMARKET", OrderState.ERROR);

        List<UnfinishedOrderCandidate> all =
                orderRepository.findUnfinishedOrderCandidates(OrderState.activeStates());
        assertThat(all).extracting(UnfinishedOrderCandidate::databaseOrderId)
                .containsExactly(
                        firstPending.getId(),
                        firstPlaced.getId(),
                        oppositeUnknown.getId(),
                        unrelatedCreated.getId());

        List<UnfinishedOrderCandidate> exactPair =
                orderRepository.findUnfinishedOrderCandidatesForPair(
                        "pm-a", "ks-a", OrderState.activeStates());
        assertThat(exactPair).extracting(UnfinishedOrderCandidate::databaseOrderId)
                .containsExactly(firstPending.getId(), firstPlaced.getId(), oppositeUnknown.getId());
        assertThat(exactPair).allSatisfy(candidate -> {
            assertThat(candidate.polymarketTicker()).isEqualTo("pm-a");
            assertThat(candidate.kalshiTicker()).isEqualTo("ks-a");
        });
    }

    private UUID saveStart(String pmTicker, String ksTicker, ArbitrageDirection direction) {
        UUID uuid = UUID.randomUUID();
        eventRepository.saveAndFlush(ArbitrageEvent.builder()
                .uuid(uuid)
                .eventType("OPPORTUNITY_START")
                .similarMarketId(1L)
                .polymarketMarketTicker(pmTicker)
                .kalshiMarketTicker(ksTicker)
                .direction(direction.getCode())
                .spread(new BigDecimal("0.10"))
                .threshold(new BigDecimal("0.01"))
                .polymarketOrderbook("{}")
                .kalshiOrderbook("{}")
                .build());
        return uuid;
    }

    private ArbitrageOrder saveOrder(UUID uuid, String platform, OrderState state) {
        return orderRepository.saveAndFlush(ArbitrageOrder.builder()
                .opportunityUuid(uuid)
                .platform(platform)
                .contractType("YES")
                .price(new BigDecimal("0.40"))
                .quantity(10L)
                .orderId(platform + "-" + UUID.randomUUID())
                .status(state)
                .build());
    }
}
