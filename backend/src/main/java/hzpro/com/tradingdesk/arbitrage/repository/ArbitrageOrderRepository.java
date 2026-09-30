package hzpro.com.tradingdesk.arbitrage.repository;

import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageOrder;
import hzpro.com.tradingdesk.arbitrage.model.OrderState;
import hzpro.com.tradingdesk.arbitrage.model.UnfinishedOrderCandidate;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Set;
import java.time.Instant;
import java.util.UUID;

@Repository
public interface ArbitrageOrderRepository extends JpaRepository<ArbitrageOrder, Long> {
    List<ArbitrageOrder> findByOpportunityUuid(UUID opportunityUuid);
    List<ArbitrageOrder> findByStatus(OrderState status);
    List<ArbitrageOrder> findAllByOrderByCreatedAtDesc();
    List<ArbitrageOrder> findByCreatedAtGreaterThanEqualAndCreatedAtLessThanAndStatusIn(Instant start, Instant end, List<String> statuses);

    @Query("""
            SELECT new hzpro.com.tradingdesk.arbitrage.model.UnfinishedOrderCandidate(
                o.id,
                o.opportunityUuid,
                o.platform,
                o.orderId,
                o.status,
                o.price,
                e.polymarketMarketTicker,
                e.kalshiMarketTicker)
            FROM ArbitrageOrder o, ArbitrageEvent e
            WHERE e.uuid = o.opportunityUuid
              AND e.eventType = 'OPPORTUNITY_START'
              AND o.status IN :statuses
            ORDER BY o.id
            """)
    List<UnfinishedOrderCandidate> findUnfinishedOrderCandidates(
            @Param("statuses") Set<OrderState> statuses);

    @Query("""
            SELECT new hzpro.com.tradingdesk.arbitrage.model.UnfinishedOrderCandidate(
                o.id,
                o.opportunityUuid,
                o.platform,
                o.orderId,
                o.status,
                o.price,
                e.polymarketMarketTicker,
                e.kalshiMarketTicker)
            FROM ArbitrageOrder o, ArbitrageEvent e
            WHERE e.uuid = o.opportunityUuid
              AND e.eventType = 'OPPORTUNITY_START'
              AND e.polymarketMarketTicker = :polymarketTicker
              AND e.kalshiMarketTicker = :kalshiTicker
              AND o.status IN :statuses
            ORDER BY o.id
            """)
    List<UnfinishedOrderCandidate> findUnfinishedOrderCandidatesForPair(
            @Param("polymarketTicker") String polymarketTicker,
            @Param("kalshiTicker") String kalshiTicker,
            @Param("statuses") Set<OrderState> statuses);

    @Query("SELECT o FROM ArbitrageOrder o WHERE " +
            "(:status IS NULL OR o.status = :status) AND " +
            "(:platform IS NULL OR o.platform = :platform) " +
            "ORDER BY o.createdAt DESC")
    Page<ArbitrageOrder> findFiltered(
            @Param("status") OrderState status,
            @Param("platform") String platform,
            Pageable pageable);
}
