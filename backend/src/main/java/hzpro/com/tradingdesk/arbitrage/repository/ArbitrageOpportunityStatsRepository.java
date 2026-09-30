package hzpro.com.tradingdesk.arbitrage.repository;

import hzpro.com.tradingdesk.arbitrage.entity.ArbitrageEvent;
import hzpro.com.tradingdesk.controller.dto.DailyOpportunityStatsDto;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.OffsetDateTime;
import java.util.List;

@Repository
public interface ArbitrageOpportunityStatsRepository extends JpaRepository<ArbitrageEvent, Long> {

    @Query(nativeQuery = true, value = """
            WITH daily_opportunities AS (
                SELECT DISTINCT
                    CAST(ae.detected_at AT TIME ZONE 'UTC' AS DATE) AS opportunity_date,
                    ae.uuid
                FROM arbitrage_events ae
                WHERE ae.event_type = 'OPPORTUNITY_START'
                  AND ae.detected_at >= :startInclusive
                  AND ae.detected_at < :endExclusive
            )
            SELECT
                daily_opportunities.opportunity_date,
                COUNT(DISTINCT daily_opportunities.uuid) AS opportunity_count,
                COUNT(DISTINCT ao.id) AS order_count,
                ROUND(
                    COUNT(DISTINCT ao.id)::NUMERIC / COUNT(DISTINCT daily_opportunities.uuid),
                    4
                ) AS orders_per_opportunity,
                COALESCE(SUM(ao.price * ao.quantity), 0) AS created_order_volume
            FROM daily_opportunities
            LEFT JOIN arbitrage_orders ao ON ao.opportunity_uuid = daily_opportunities.uuid
            GROUP BY daily_opportunities.opportunity_date
            ORDER BY daily_opportunities.opportunity_date
            """)
    List<DailyOpportunityStatsRow> findDailyStatsRows(
            @Param("startInclusive") OffsetDateTime startInclusive,
            @Param("endExclusive") OffsetDateTime endExclusive);

    default List<DailyOpportunityStatsDto> findDailyStats(
            OffsetDateTime startInclusive,
            OffsetDateTime endExclusive) {
        return findDailyStatsRows(startInclusive, endExclusive).stream()
                .map(row -> new DailyOpportunityStatsDto(
                        row.opportunityDate().toLocalDate(),
                        row.opportunityCount(),
                        row.orderCount(),
                        row.ordersPerOpportunity(),
                        row.createdOrderVolume()))
                .toList();
    }

    record DailyOpportunityStatsRow(
            Date opportunityDate,
            long opportunityCount,
            long orderCount,
            BigDecimal ordersPerOpportunity,
            BigDecimal createdOrderVolume) {
    }
}
