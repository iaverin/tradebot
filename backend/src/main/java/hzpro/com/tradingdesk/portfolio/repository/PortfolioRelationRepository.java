package hzpro.com.tradingdesk.portfolio.repository;

import hzpro.com.tradingdesk.controller.dto.PortfolioPositionRelationDto;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenue;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;

import java.util.LinkedHashSet;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Set;

@Repository
public class PortfolioRelationRepository {

    private static final String RELATIONS_SQL = """
            SELECT DISTINCT
                ae.similar_market_id,
                ae.polymarket_market_ticker,
                ae.kalshi_market_ticker,
                ae.direction,
                pm.contract_type AS polymarket_outcome,
                ks.contract_type AS kalshi_outcome
            FROM arbitrage_events ae
            LEFT JOIN arbitrage_orders pm
              ON pm.opportunity_uuid = ae.uuid AND pm.platform = 'POLYMARKET'
            LEFT JOIN arbitrage_orders ks
              ON ks.opportunity_uuid = ae.uuid AND ks.platform = 'KALSHI'
            WHERE ae.event_type = 'OPPORTUNITY_START'
            """;

    private final NamedParameterJdbcTemplate jdbcTemplate;

    public PortfolioRelationRepository(NamedParameterJdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    public List<PortfolioPositionRelationDto> findRelations(
            PortfolioVenue selectedVenue,
            Collection<String> marketTickers
    ) {
        if (marketTickers == null || marketTickers.isEmpty()) {
            return List.of();
        }
        String selectedMarketColumn = selectedVenue == PortfolioVenue.POLYMARKET
                ? "ae.polymarket_market_ticker"
                : "ae.kalshi_market_ticker";
        List<RawPortfolioRelation> rawRelations = jdbcTemplate.query(
                RELATIONS_SQL + " AND " + selectedMarketColumn + " IN (:marketTickers)",
                new MapSqlParameterSource("marketTickers", marketTickers),
                (resultSet, rowNumber) -> new RawPortfolioRelation(
                        resultSet.getLong("similar_market_id"),
                        resultSet.getString("polymarket_market_ticker"),
                        resultSet.getString("kalshi_market_ticker"),
                        resultSet.getString("direction"),
                        resultSet.getString("polymarket_outcome"),
                        resultSet.getString("kalshi_outcome")));

        Set<PortfolioPositionRelationDto> deduplicated = new LinkedHashSet<>();
        for (RawPortfolioRelation raw : rawRelations) {
            String polymarketOutcome = normalizeOutcome(raw.polymarketOutcome());
            String kalshiOutcome = normalizeOutcome(raw.kalshiOutcome());
            if (polymarketOutcome == null || kalshiOutcome == null) {
                if ("PM_YES_KS_NO".equals(raw.direction())) {
                    polymarketOutcome = polymarketOutcome == null ? "YES" : polymarketOutcome;
                    kalshiOutcome = kalshiOutcome == null ? "NO" : kalshiOutcome;
                } else if ("PM_NO_KS_YES".equals(raw.direction())) {
                    polymarketOutcome = polymarketOutcome == null ? "NO" : polymarketOutcome;
                    kalshiOutcome = kalshiOutcome == null ? "YES" : kalshiOutcome;
                }
            }
            if (polymarketOutcome != null && kalshiOutcome != null) {
                deduplicated.add(new PortfolioPositionRelationDto(
                        raw.similarMarketId(),
                        raw.polymarketMarketTicker(),
                        polymarketOutcome,
                        raw.kalshiMarketTicker(),
                        kalshiOutcome));
            }
        }
        return List.copyOf(deduplicated);
    }

    private String normalizeOutcome(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "YES".equals(normalized) || "NO".equals(normalized) ? normalized : null;
    }

    private record RawPortfolioRelation(
            long similarMarketId,
            String polymarketMarketTicker,
            String kalshiMarketTicker,
            String direction,
            String polymarketOutcome,
            String kalshiOutcome
    ) {
    }
}
