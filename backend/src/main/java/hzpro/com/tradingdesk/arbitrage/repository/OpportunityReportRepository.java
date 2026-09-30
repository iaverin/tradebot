package hzpro.com.tradingdesk.arbitrage.repository;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import hzpro.com.tradingdesk.controller.dto.OpportunityReportAggregateDto;

@Repository
public class OpportunityReportRepository {

    private static final String OPPORTUNITY_REPORT_SQL = """
            SELECT
                ae.similar_market_id,
                MAX(ae.detected_at) AS last_opportunity_at,
                COUNT(DISTINCT ae.uuid) AS opportunity_count,
                COUNT(DISTINCT CASE WHEN ao.id IS NOT NULL THEN ae.uuid END) AS opps_with_orders,
                COUNT(DISTINCT CASE
                    WHEN ao.id IS NOT NULL AND ao.status NOT IN ('EXECUTED', 'CANCELED', 'ERROR')
                    THEN ae.uuid
                END) AS opps_with_unexecuted,
                COUNT(DISTINCT ao_pm.id) AS pm_orders,
                COUNT(DISTINCT ao_ks.id) AS ks_orders,
                COUNT(DISTINCT CASE WHEN ao_pm.status = 'EXECUTED' THEN ao_pm.id END) AS pm_executed,
                COUNT(DISTINCT CASE WHEN ao_ks.status = 'EXECUTED' THEN ao_ks.id END) AS ks_executed,
                COUNT(DISTINCT CASE
                    WHEN ae_end.close_reason = 'SPREAD_BELOW_THRESHOLD' THEN ae.uuid
                END) AS spread_below_threshold_close_count,
                COUNT(DISTINCT CASE
                    WHEN ae_end.close_reason = 'PRICE_DATA_STALE' THEN ae.uuid
                END) AS price_data_stale_close_count,
                COUNT(DISTINCT CASE
                    WHEN ae_end.close_reason = 'PRICE_DATA_UNAVAILABLE' THEN ae.uuid
                END) AS price_data_unavailable_close_count,
                COUNT(DISTINCT CASE
                    WHEN ae_end.close_reason = 'ORDER_CREATION_FAILED' THEN ae.uuid
                END) AS order_creation_failed_close_count,
                COUNT(DISTINCT CASE
                    WHEN ae_end.close_reason = 'ORDERBOOK_PRICE_BELOW_THRESHOLD' THEN ae.uuid
                END) AS orderbook_price_below_threshold_close_count,
                COUNT(DISTINCT CASE
                    WHEN ae_end.close_reason = 'ORDER_BOOK_NOT_ENOUGH_AMOUT' THEN ae.uuid
                END) AS order_book_not_enough_amount_close_count,
                COUNT(DISTINCT CASE
                    WHEN ae_end.close_reason = 'ORDERBOOK_EMPTY' THEN ae.uuid
                END) AS orderbook_empty_close_count,
                COUNT(DISTINCT CASE
                    WHEN ae_end.close_reason = 'MINIMIM_ORDER_COST_NOT_MET' THEN ae.uuid
                END) AS minimum_order_cost_not_met_close_count,
                COUNT(DISTINCT CASE
                    WHEN ae_end.close_reason = 'ACTIVE_PAIR_LIMIT_REACHED' THEN ae.uuid
                END) AS active_pair_limit_reached_close_count,
                COUNT(DISTINCT CASE
                    WHEN ae_end.close_reason = 'ORDERS_CREATED' THEN ae.uuid
                END) AS orders_created_close_count,
                COUNT(DISTINCT CASE
                    WHEN ae_end.close_reason = 'UNKNOWN' THEN ae.uuid
                END) AS unknown_close_count
            FROM arbitrage_events ae
            LEFT JOIN arbitrage_events ae_end
              ON ae_end.opportunity_start_id = ae.id
             AND ae_end.event_type = 'OPPORTUNITY_END'
            LEFT JOIN arbitrage_orders ao ON ao.opportunity_uuid = ae.uuid
            LEFT JOIN arbitrage_orders ao_pm
              ON ao_pm.opportunity_uuid = ae.uuid AND ao_pm.platform = 'POLYMARKET'
            LEFT JOIN arbitrage_orders ao_ks
              ON ao_ks.opportunity_uuid = ae.uuid AND ao_ks.platform = 'KALSHI'
            WHERE ae.event_type = 'OPPORTUNITY_START'
              AND ae.detected_at >= :startDate
              AND ae.detected_at <= :endDate
            GROUP BY ae.similar_market_id
            HAVING :onlyWithOrders = FALSE
                OR COUNT(DISTINCT CASE WHEN ao.id IS NOT NULL THEN ae.uuid END) > 0
            ORDER BY last_opportunity_at DESC
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public OpportunityReportRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<OpportunityReportAggregateDto> findOpportunityReport(
            Instant startDate,
            Instant endDate,
            boolean onlyWithOrders
    ) {
        MapSqlParameterSource parameters = new MapSqlParameterSource()
                .addValue("startDate", Timestamp.from(startDate))
                .addValue("endDate", Timestamp.from(endDate))
                .addValue("onlyWithOrders", onlyWithOrders);

        return jdbcTemplate.query(OPPORTUNITY_REPORT_SQL, parameters, (resultSet, rowNumber) ->
                new OpportunityReportAggregateDto(
                        resultSet.getLong("similar_market_id"),
                        resultSet.getTimestamp("last_opportunity_at").toInstant(),
                        resultSet.getLong("opportunity_count"),
                        resultSet.getLong("opps_with_orders"),
                        resultSet.getLong("opps_with_unexecuted"),
                        resultSet.getLong("pm_orders"),
                        resultSet.getLong("ks_orders"),
                        resultSet.getLong("pm_executed"),
                        resultSet.getLong("ks_executed"),
                        resultSet.getLong("spread_below_threshold_close_count"),
                        resultSet.getLong("price_data_stale_close_count"),
                        resultSet.getLong("price_data_unavailable_close_count"),
                        resultSet.getLong("order_creation_failed_close_count"),
                        resultSet.getLong("orderbook_price_below_threshold_close_count"),
                        resultSet.getLong("order_book_not_enough_amount_close_count"),
                        resultSet.getLong("orderbook_empty_close_count"),
                        resultSet.getLong("minimum_order_cost_not_met_close_count"),
                        resultSet.getLong("active_pair_limit_reached_close_count"),
                        resultSet.getLong("orders_created_close_count"),
                        resultSet.getLong("unknown_close_count")));
    }
}
