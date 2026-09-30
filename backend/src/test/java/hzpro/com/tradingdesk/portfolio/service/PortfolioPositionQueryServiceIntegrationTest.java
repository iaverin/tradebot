package hzpro.com.tradingdesk.portfolio.service;

import hzpro.com.tradingdesk.controller.dto.PortfolioPositionGroupDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioPositionsPageDto;
import hzpro.com.tradingdesk.portfolio.model.CollectedPortfolioPosition;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenue;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioPositionRefreshStateRepository;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioPositionRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
@ActiveProfiles("test")
class PortfolioPositionQueryServiceIntegrationTest {

    @Autowired
    private PortfolioPositionQueryService queryService;

    @Autowired
    private PortfolioPositionSnapshotWriter writer;

    @Autowired
    private PortfolioPositionRepository positionRepository;

    @Autowired
    private PortfolioPositionRefreshStateRepository stateRepository;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void setUp() {
        positionRepository.deleteAllInBatch();
        stateRepository.deleteAllInBatch();
        jdbcTemplate.update("DELETE FROM arbitrage_orders");
        jdbcTemplate.update("DELETE FROM arbitrage_events");

        writer.replaceSnapshot(PortfolioVenue.POLYMARKET, List.of(
                position("pm-asset-yes", "pm-yes", "YES", "PM yes event", "PM yes market"),
                position("pm-asset-no", "pm-no", "NO", "PM no event", "PM no market"),
                position("pm-unrelated", "pm-unrelated", "YES", "Unrelated event", "Unrelated market")),
                Instant.parse("2026-09-10T10:00:00Z"));
        writer.replaceSnapshot(PortfolioVenue.KALSHI, List.of(
                position("ks-no", "ks-no", "NO", "KS no event", "KS no market"),
                position("ks-yes", "ks-yes", "YES", "KS yes event", "KS yes market")),
                Instant.parse("2026-09-10T10:01:00Z"));

        UUID fallbackOpportunity = insertStart("pm-yes", "ks-no", "PM_YES_KS_NO", 101L);
        insertEnd(fallbackOpportunity, "pm-yes", "ks-no", "PM_NO_KS_YES", 101L);
        UUID orderedOpportunity = insertStart("pm-no", "ks-yes", "PM_YES_KS_NO", 102L);
        insertOrder(orderedOpportunity, "POLYMARKET", "NO");
        insertOrder(orderedOpportunity, "KALSHI", "YES");

        // Repeated opportunity evidence must not duplicate the same related position.
        insertStart("pm-yes", "ks-no", "PM_YES_KS_NO", 101L);
        assertThat(fallbackOpportunity).isNotNull();
    }

    @Test
    void groupsBothDirectionsAndLeavesUnrelatedPositionsVisible() {
        PortfolioPositionsPageDto polymarket = queryService.getPositions("polymarket", 0, 50);

        assertThat(polymarket.selectedVenue()).isEqualTo("POLYMARKET");
        assertThat(polymarket.lastSuccessfulRefreshAt()).isEqualTo("2026-09-10T10:00:00Z");
        assertThat(polymarket.totalElements()).isEqualTo(3);
        assertThat(group(polymarket, "pm-yes").related()).singleElement()
                .satisfies(position -> {
                    assertThat(position.venue()).isEqualTo("KALSHI");
                    assertThat(position.marketTicker()).isEqualTo("ks-no");
                    assertThat(position.outcome()).isEqualTo("NO");
                });
        assertThat(group(polymarket, "pm-no").related()).singleElement()
                .satisfies(position -> {
                    assertThat(position.marketTicker()).isEqualTo("ks-yes");
                    assertThat(position.outcome()).isEqualTo("YES");
                });
        assertThat(group(polymarket, "pm-unrelated").related()).isEmpty();

        PortfolioPositionsPageDto kalshi = queryService.getPositions("KALSHI", 0, 50);
        assertThat(kalshi.lastSuccessfulRefreshAt()).isEqualTo("2026-09-10T10:01:00Z");
        assertThat(group(kalshi, "ks-no").related()).singleElement()
                .satisfies(position -> assertThat(position.marketTicker()).isEqualTo("pm-yes"));
        assertThat(group(kalshi, "ks-yes").related()).singleElement()
                .satisfies(position -> assertThat(position.marketTicker()).isEqualTo("pm-no"));
    }

    @Test
    void paginatesOnlyPrimaryPositions() {
        PortfolioPositionsPageDto firstPage = queryService.getPositions("POLYMARKET", 0, 2);

        assertThat(firstPage.content()).hasSize(2);
        assertThat(firstPage.totalElements()).isEqualTo(3);
        assertThat(firstPage.totalPages()).isEqualTo(2);
    }

    private PortfolioPositionGroupDto group(PortfolioPositionsPageDto page, String ticker) {
        return page.content().stream()
                .filter(group -> group.primary().marketTicker().equals(ticker))
                .findFirst()
                .orElseThrow();
    }

    private UUID insertStart(String polymarketTicker, String kalshiTicker, String direction, long similarMarketId) {
        UUID uuid = UUID.randomUUID();
        jdbcTemplate.update("""
                INSERT INTO arbitrage_events (
                    uuid, detected_at, event_type, similar_market_id,
                    polymarket_market_ticker, kalshi_market_ticker, direction,
                    spread, threshold, polymarket_orderbook, kalshi_orderbook
                ) VALUES (?, ?, 'OPPORTUNITY_START', ?, ?, ?, ?, 0.01, 0.02, '{}'::jsonb, '{}'::jsonb)
                """,
                uuid,
                Timestamp.from(Instant.now()),
                similarMarketId,
                polymarketTicker,
                kalshiTicker,
                direction);
        return uuid;
    }

    private void insertOrder(UUID opportunity, String platform, String outcome) {
        jdbcTemplate.update("""
                INSERT INTO arbitrage_orders (
                    opportunity_uuid, platform, contract_type, price, quantity, status
                ) VALUES (?, ?, ?, 0.50, 1, 'EXECUTED')
                """, opportunity, platform, outcome);
    }

    private void insertEnd(
            UUID startUuid,
            String polymarketTicker,
            String kalshiTicker,
            String direction,
            long similarMarketId
    ) {
        Long startId = jdbcTemplate.queryForObject(
                "SELECT id FROM arbitrage_events WHERE uuid = ?",
                Long.class,
                startUuid);
        jdbcTemplate.update("""
                INSERT INTO arbitrage_events (
                    uuid, detected_at, event_type, close_reason, opportunity_start_id, similar_market_id,
                    polymarket_market_ticker, kalshi_market_ticker, direction,
                    spread, threshold, polymarket_orderbook, kalshi_orderbook
                ) VALUES (?, ?, 'OPPORTUNITY_END', 'UNKNOWN', ?, ?, ?, ?, ?, 0.01, 0.02, '{}'::jsonb, '{}'::jsonb)
                """,
                UUID.randomUUID(),
                Timestamp.from(Instant.now()),
                startId,
                similarMarketId,
                polymarketTicker,
                kalshiTicker,
                direction);
    }

    private CollectedPortfolioPosition position(
            String sourceId,
            String marketTicker,
            String outcome,
            String eventTitle,
            String marketTitle
    ) {
        return new CollectedPortfolioPosition(
                sourceId,
                marketTicker,
                null,
                "event-" + marketTicker,
                eventTitle,
                marketTitle,
                outcome,
                new BigDecimal("1.2500000001"),
                new BigDecimal("0.75"),
                new BigDecimal("0.90"),
                null);
    }
}
