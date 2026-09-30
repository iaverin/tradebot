package hzpro.com.tradingdesk.portfolio.repository;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PortfolioMarketMetadataRepositoryIntegrationTest {

    @Autowired
    private PortfolioMarketMetadataRepository repository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        jdbcTemplate.update("DELETE FROM prediction_markets");
        insert("POLYMARKET", "condition", "pm-old", "Old event", "Old market", "2026-09-10T09:00:00Z");
        insert("POLYMARKET", "condition", "pm-new", "New event", "New market", "2026-09-10T10:00:00Z");
        insert("KALSHI", null, "KS-TICKER", "KS old event", "KS old market", "2026-09-10T09:00:00Z");
        insert("KALSHI", null, "KS-TICKER", "KS new event", "KS new market", "2026-09-10T10:00:00Z");
    }

    @Test
    void returnsLatestPublishedRowByCondition() {
        var result = repository.findLatestByConditionIds("POLYMARKET", List.of("condition"));

        assertThat(result).singleElement().satisfies(metadata -> {
            assertThat(metadata.marketTicker()).isEqualTo("pm-new");
            assertThat(metadata.eventTitle()).isEqualTo("New event");
            assertThat(metadata.marketTitle()).isEqualTo("New market");
        });
    }

    @Test
    void returnsLatestPublishedRowByVenueAndTicker() {
        var result = repository.findLatestByMarketTickers("KALSHI", List.of("KS-TICKER"));

        assertThat(result).singleElement().satisfies(metadata -> {
            assertThat(metadata.eventTitle()).isEqualTo("KS new event");
            assertThat(metadata.marketTitle()).isEqualTo("KS new market");
        });
    }

    private void insert(
            String datasource,
            String conditionId,
            String marketTicker,
            String eventTitle,
            String marketTitle,
            String createdAt
    ) {
        jdbcTemplate.update("""
                INSERT INTO prediction_markets (
                    datasource, condition_id, event_ticker, event_title,
                    market_ticker, market_title, created_at, updated_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                datasource,
                conditionId,
                "event-" + marketTicker,
                eventTitle,
                marketTicker,
                marketTitle,
                Timestamp.from(Instant.parse(createdAt)),
                Timestamp.from(Instant.parse(createdAt)));
    }
}
