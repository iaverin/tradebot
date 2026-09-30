package hzpro.com.tradingdesk.portfolio.repository;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.time.Instant;
import java.util.Collection;
import java.util.List;

@Repository
public class PortfolioMarketMetadataRepository {

    private static final String SELECT_COLUMNS = """
            SELECT datasource, condition_id, event_ticker, event_title,
                   market_ticker, market_title, created_at
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PortfolioMarketMetadataRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PortfolioMarketMetadata> findLatestByConditionIds(
            String venue,
            Collection<String> conditionIds
    ) {
        if (conditionIds == null || conditionIds.isEmpty()) {
            return List.of();
        }
        String sql = SELECT_COLUMNS + """
                FROM (
                    SELECT DISTINCT ON (condition_id)
                           datasource, condition_id, event_ticker, event_title,
                           market_ticker, market_title, created_at, id
                    FROM prediction_markets
                    WHERE datasource = :venue
                      AND condition_id IN (:conditionIds)
                    ORDER BY condition_id, created_at DESC NULLS LAST, id DESC
                ) latest
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("venue", venue)
                        .addValue("conditionIds", conditionIds),
                (resultSet, rowNumber) -> mapMetadata(resultSet));
    }

    public List<PortfolioMarketMetadata> findLatestByMarketTickers(
            String venue,
            Collection<String> marketTickers
    ) {
        if (marketTickers == null || marketTickers.isEmpty()) {
            return List.of();
        }
        String sql = SELECT_COLUMNS + """
                FROM (
                    SELECT DISTINCT ON (market_ticker)
                           datasource, condition_id, event_ticker, event_title,
                           market_ticker, market_title, created_at, id
                    FROM prediction_markets
                    WHERE datasource = :venue
                      AND market_ticker IN (:marketTickers)
                    ORDER BY market_ticker, created_at DESC NULLS LAST, id DESC
                ) latest
                """;
        return jdbcTemplate.query(
                sql,
                new MapSqlParameterSource()
                        .addValue("venue", venue)
                        .addValue("marketTickers", marketTickers),
                (resultSet, rowNumber) -> mapMetadata(resultSet));
    }

    private PortfolioMarketMetadata mapMetadata(java.sql.ResultSet resultSet)
            throws java.sql.SQLException {
        java.sql.Timestamp createdAt = resultSet.getTimestamp("created_at");
        return new PortfolioMarketMetadata(
                resultSet.getString("datasource"),
                resultSet.getString("condition_id"),
                resultSet.getString("event_ticker"),
                resultSet.getString("event_title"),
                resultSet.getString("market_ticker"),
                resultSet.getString("market_title"),
                createdAt == null ? null : createdAt.toInstant());
    }

    public record PortfolioMarketMetadata(
            String datasource,
            String conditionId,
            String eventTicker,
            String eventTitle,
            String marketTicker,
            String marketTitle,
            Instant createdAt
    ) {
    }
}
