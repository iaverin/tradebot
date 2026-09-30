package hzpro.com.tradingdesk.portfolio.service;

import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.portfolio.entity.PortfolioPosition;
import hzpro.com.tradingdesk.portfolio.model.CollectedPortfolioPosition;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenue;
import hzpro.com.tradingdesk.portfolio.model.PortfolioRefreshStatus;
import hzpro.com.tradingdesk.portfolio.model.VenueCollectionResult;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioPositionRefreshStateRepository;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest
@ActiveProfiles("test")
class PortfolioPositionSnapshotWriterIntegrationTest {

    @Autowired
    private PortfolioPositionSnapshotWriter writer;

    @Autowired
    private PortfolioPositionRepository positionRepository;

    @Autowired
    private PortfolioPositionRefreshStateRepository stateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanDatabase() {
        positionRepository.deleteAllInBatch();
        stateRepository.deleteAllInBatch();
    }

    @Test
    void insertsUpdatesPrunesAndPreservesIdForRetainedNormalizedKey() {
        Instant firstRefresh = Instant.parse("2026-09-10T10:00:00Z");
        writer.replaceSnapshot(PortfolioVenue.POLYMARKET, List.of(
                position("asset-1", "market-1", "YES", "1.25"),
                position("asset-2", "market-2", "NO", "2.50")), firstRefresh);
        Long retainedId = find("market-1", "YES").getId();

        Instant secondRefresh = Instant.parse("2026-09-10T10:05:00Z");
        CollectedPortfolioPosition replacement = position("asset-1-new", "market-1", "YES", "3.75");
        writer.replaceSnapshot(PortfolioVenue.POLYMARKET, List.of(
                replacement,
                position("asset-3", "market-3", "NO", "4.125")), secondRefresh);

        assertThat(positionRepository.findByVenue(DataSource.POLYMARKET)).hasSize(2);
        PortfolioPosition retained = find("market-1", "YES");
        assertThat(retained.getId()).isEqualTo(retainedId);
        assertThat(retained.getSourcePositionId()).isEqualTo("asset-1-new");
        assertThat(retained.getQuantity()).isEqualByComparingTo("3.75");
        assertThat(retained.getRefreshedAt()).isEqualTo(secondRefresh);
        assertThat(positionRepository.findByVenue(DataSource.POLYMARKET))
                .noneMatch(row -> row.getMarketTicker().equals("market-2"));
        assertThat(stateRepository.findById("POLYMARKET").orElseThrow().getLastSuccessfulRefreshAt())
                .isEqualTo(secondRefresh);
    }

    @Test
    void successfulEmptySnapshotDeletesPositionsAndAdvancesDurableTimestamp() {
        writer.replaceSnapshot(
                PortfolioVenue.KALSHI,
                List.of(position("KS-1", "KS-1", "YES", "1")),
                Instant.parse("2026-09-10T10:00:00Z"));

        Instant emptyRefresh = Instant.parse("2026-09-10T10:05:00Z");
        writer.replaceSnapshot(PortfolioVenue.KALSHI, List.of(), emptyRefresh);

        assertThat(positionRepository.findByVenue(DataSource.KALSHI)).isEmpty();
        assertThat(stateRepository.findById("KALSHI").orElseThrow().getLastSuccessfulRefreshAt())
                .isEqualTo(emptyRefresh);
    }

    @Test
    void rejectsDistinctSourceIdsThatCollideOnRelationKeyWithoutChangingSnapshot() {
        Instant originalRefresh = Instant.parse("2026-09-10T10:00:00Z");
        writer.replaceSnapshot(
                PortfolioVenue.POLYMARKET,
                List.of(position("asset-original", "same-market", "YES", "1")),
                originalRefresh);

        assertThatThrownBy(() -> writer.replaceSnapshot(
                PortfolioVenue.POLYMARKET,
                List.of(
                        position("asset-a", "same-market", "YES", "2"),
                        position("asset-b", "same-market", "YES", "3")),
                Instant.parse("2026-09-10T10:05:00Z")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("collide");

        assertThat(positionRepository.findByVenue(DataSource.POLYMARKET)).singleElement()
                .satisfies(row -> {
                    assertThat(row.getSourcePositionId()).isEqualTo("asset-original");
                    assertThat(row.getQuantity()).isEqualByComparingTo("1");
                });
        assertThat(stateRepository.findById("POLYMARKET").orElseThrow().getLastSuccessfulRefreshAt())
                .isEqualTo(originalRefresh);
    }

    @Test
    void outcomeReversalGetsANewDatabaseIdentity() {
        writer.replaceSnapshot(
                PortfolioVenue.KALSHI,
                List.of(position("KS-1", "KS-1", "YES", "1")),
                Instant.parse("2026-09-10T10:00:00Z"));
        Long yesId = find("KS-1", "YES").getId();

        writer.replaceSnapshot(
                PortfolioVenue.KALSHI,
                List.of(position("KS-1", "KS-1", "NO", "1")),
                Instant.parse("2026-09-10T10:05:00Z"));

        PortfolioPosition noPosition = find("KS-1", "NO");
        assertThat(noPosition.getId()).isNotEqualTo(yesId);
        assertThat(positionRepository.findByVenue(DataSource.KALSHI)).singleElement()
                .extracting(PortfolioPosition::getOutcome)
                .hasToString("NO");
    }

    @Test
    void validatesRequiredKeysOutcomeAndPositiveQuantity() {
        assertThatThrownBy(() -> writer.replaceSnapshot(
                PortfolioVenue.POLYMARKET,
                List.of(position("asset", "market", "MAYBE", "1")),
                Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("YES or NO");
        assertThatThrownBy(() -> writer.replaceSnapshot(
                PortfolioVenue.POLYMARKET,
                List.of(position("asset", "market", "YES", "0")),
                Instant.now()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("positive");
    }

    @Test
    void discardsExactDuplicateSourceRows() {
        CollectedPortfolioPosition duplicate = position("asset", "market", "YES", "1.25");

        writer.replaceSnapshot(
                PortfolioVenue.POLYMARKET,
                List.of(duplicate, duplicate),
                Instant.parse("2026-09-10T10:00:00Z"));

        assertThat(positionRepository.findByVenue(DataSource.POLYMARKET)).hasSize(1);
    }

    @Test
    void databaseEnforcesUniqueVenueMarketOutcomeKey() {
        String insert = """
                INSERT INTO portfolio_positions (
                    venue, source_position_id, market_ticker, outcome, quantity, refreshed_at
                ) VALUES ('POLYMARKET', ?, 'market', 'YES', 1, NOW())
                """;
        jdbcTemplate.update(insert, "asset-1");

        assertThatThrownBy(() -> jdbcTemplate.update(insert, "asset-2"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void failedVenueCollectionPreservesItsSnapshotWhileOtherVenueCommits() {
        Instant original = Instant.parse("2026-09-10T10:00:00Z");
        writer.replaceSnapshot(
                PortfolioVenue.POLYMARKET,
                List.of(position("pm-asset", "pm-market", "YES", "1")),
                original);
        writer.replaceSnapshot(
                PortfolioVenue.KALSHI,
                List.of(position("ks-position", "ks-market", "NO", "1")),
                original);
        PortfolioPositionRefreshCoordinator coordinator = new PortfolioPositionRefreshCoordinator(
                List.of(
                        collector(PortfolioVenue.POLYMARKET, VenueCollectionResult.failure("PM unavailable")),
                        collector(PortfolioVenue.KALSHI, VenueCollectionResult.success(List.of()))),
                writer,
                stateRepository);

        var result = coordinator.refreshAll();

        assertThat(result.venues()).extracting(item -> item.status())
                .containsExactly(PortfolioRefreshStatus.FAILED, PortfolioRefreshStatus.SUCCESS);
        assertThat(positionRepository.findByVenue(DataSource.POLYMARKET)).singleElement()
                .extracting(PortfolioPosition::getSourcePositionId)
                .isEqualTo("pm-asset");
        assertThat(stateRepository.findById("POLYMARKET").orElseThrow().getLastSuccessfulRefreshAt())
                .isEqualTo(original);
        assertThat(positionRepository.findByVenue(DataSource.KALSHI)).isEmpty();
        assertThat(stateRepository.findById("KALSHI").orElseThrow().getLastSuccessfulRefreshAt())
                .isAfter(original);
    }

    private PortfolioPosition find(String marketTicker, String outcome) {
        return positionRepository.findAll().stream()
                .filter(row -> row.getMarketTicker().equals(marketTicker)
                        && row.getOutcome().name().equals(outcome))
                .findFirst()
                .orElseThrow();
    }

    private CollectedPortfolioPosition position(
            String sourceId,
            String ticker,
            String outcome,
            String quantity
    ) {
        return new CollectedPortfolioPosition(
                sourceId,
                ticker,
                "condition-" + ticker,
                "event-" + ticker,
                "Event " + ticker,
                "Market " + ticker,
                outcome,
                new BigDecimal(quantity),
                new BigDecimal("0.50"),
                new BigDecimal("0.75"),
                null);
    }

    private PortfolioVenueCollector collector(PortfolioVenue venue, VenueCollectionResult result) {
        return new PortfolioVenueCollector() {
            @Override
            public PortfolioVenue venue() {
                return venue;
            }

            @Override
            public VenueCollectionResult collect() {
                return result;
            }
        };
    }
}
