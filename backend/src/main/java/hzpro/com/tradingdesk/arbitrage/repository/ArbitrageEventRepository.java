package hzpro.com.tradingdesk.arbitrage.repository;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;

@Repository
public interface ArbitrageEventRepository extends JpaRepository<ArbitrageEvent, Long> {

    @Query("""
        SELECT e FROM ArbitrageEvent e
        WHERE e.eventType = 'OPPORTUNITY_START'
          AND e.similarMarketId = :similarMarketId
          AND e.direction = :direction
          AND NOT EXISTS (
              SELECT 1 FROM ArbitrageEvent e2
              WHERE e2.eventType = 'OPPORTUNITY_END'
                AND e2.opportunityStart.id = e.id
          )
        ORDER BY e.detectedAt DESC
        """)
    List<ArbitrageEvent> findActiveOpportunities(
            @Param("similarMarketId") Long similarMarketId,
            @Param("direction") String direction);

    @Query("""
        SELECT e FROM ArbitrageEvent e
        WHERE e.eventType = 'OPPORTUNITY_START'
          AND e.detectedAt BETWEEN :startTime AND :endTime
        ORDER BY e.detectedAt DESC
        """)
    List<ArbitrageEvent> findStartsInTimeRange(
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Query("""
        SELECT e FROM ArbitrageEvent e
        WHERE e.eventType = 'OPPORTUNITY_START'
          AND e.similarMarketId IN :similarMarketIds
          AND e.detectedAt BETWEEN :startTime AND :endTime
        ORDER BY e.detectedAt DESC, e.id DESC
        """)
    List<ArbitrageEvent> findStartsForOpportunityReportMetadata(
            @Param("similarMarketIds") List<Long> similarMarketIds,
            @Param("startTime") Instant startTime,
            @Param("endTime") Instant endTime);

    @Query("""
        SELECT e FROM ArbitrageEvent e
        WHERE e.eventType = 'OPPORTUNITY_START'
          AND NOT EXISTS (
              SELECT 1 FROM ArbitrageEvent e2
              WHERE e2.eventType = 'OPPORTUNITY_END'
                AND e2.opportunityStart.id = e.id
          )
        ORDER BY e.detectedAt DESC
        """)
    List<ArbitrageEvent> findUnclosedOpportunities();

    @Query("SELECT e FROM ArbitrageEvent e WHERE e.uuid IN :uuids AND e.eventType = 'OPPORTUNITY_START'")
    List<ArbitrageEvent> findByUuidInAndEventTypeStart(@Param("uuids") List<UUID> uuids);

    @Query(nativeQuery = true, value = """
    SELECT
        COALESCE(pm.market_close_datetime, ks.market_close_datetime) AS close_date,
        COUNT(DISTINCT ae.similar_market_id) AS unique_pairs,
        COUNT(DISTINCT ae.id) AS opportunity_count,
        COALESCE(SUM(ao_pm.price * ao_pm.quantity), 0) AS pm_order_total,
        COALESCE(SUM(ao_ks.price * ao_ks.quantity), 0) AS ks_order_total,
        COALESCE(SUM(CASE WHEN ao_pm.status = 'EXECUTED' THEN ao_pm.filled_amount ELSE 0 END), 0) AS pm_filled_total,
        COALESCE(SUM(CASE WHEN ao_ks.status = 'EXECUTED' THEN ao_ks.filled_amount ELSE 0 END), 0) AS ks_filled_total
    FROM arbitrage_events ae
    JOIN similar_markets sm ON sm.id = ae.similar_market_id
    LEFT JOIN prediction_markets pm ON pm.market_ticker = sm.polymarket_market_ticker
        AND pm.datasource = 'POLYMARKET'
    LEFT JOIN prediction_markets ks ON ks.market_ticker = sm.kalshi_market_ticker
        AND ks.datasource = 'KALSHI'
    LEFT JOIN arbitrage_orders ao_pm ON ao_pm.opportunity_uuid = ae.uuid AND ao_pm.platform = 'POLYMARKET'
    LEFT JOIN arbitrage_orders ao_ks ON ao_ks.opportunity_uuid = ae.uuid AND ao_ks.platform = 'KALSHI'
    WHERE ae.event_type = 'OPPORTUNITY_START'
      AND ((:closeSource = 'POLYMARKET' AND pm.market_close_datetime BETWEEN CAST(:startDate AS TIMESTAMP) AND CAST(:endDate AS TIMESTAMP))
           OR (:closeSource = 'KALSHI' AND ks.market_close_datetime BETWEEN CAST(:startDate AS TIMESTAMP) AND CAST(:endDate AS TIMESTAMP)))
    GROUP BY close_date
    ORDER BY close_date
    """)
    List<Object[]> findProfitReport(
            @Param("closeSource") String closeSource,
            @Param("startDate") Instant startDate,
            @Param("endDate") Instant endDate);
}
