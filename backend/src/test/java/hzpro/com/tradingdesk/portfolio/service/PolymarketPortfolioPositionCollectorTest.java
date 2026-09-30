package hzpro.com.tradingdesk.portfolio.service;

import hzpro.com.tradingdesk.client.PolymarketDataApiClient;
import hzpro.com.tradingdesk.client.dto.PolymarketPositionDto;
import hzpro.com.tradingdesk.portfolio.model.VenueCollectionResult;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioMarketMetadataRepository;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioMarketMetadataRepository.PortfolioMarketMetadata;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolymarketPortfolioPositionCollectorTest {

    private PolymarketDataApiClient client;
    private PortfolioMarketMetadataRepository metadataRepository;
    private PolymarketPortfolioPositionCollector collector;

    @BeforeEach
    void setUp() {
        client = mock(PolymarketDataApiClient.class);
        metadataRepository = mock(PortfolioMarketMetadataRepository.class);
        collector = new PolymarketPortfolioPositionCollector(client, metadataRepository);
        when(metadataRepository.findLatestByConditionIds(eq("POLYMARKET"), any())).thenReturn(List.of());
        when(metadataRepository.findLatestByMarketTickers(eq("POLYMARKET"), any())).thenReturn(List.of());
    }

    @Test
    void prefersGrossBasisAndConditionMetadata() {
        PolymarketPositionDto source = position(
                "asset", "condition", "source-slug", "Yes", "1.2500000001", "0.50", "0.55");
        when(client.getCurrentPositions()).thenReturn(List.of(source));
        when(metadataRepository.findLatestByConditionIds(eq("POLYMARKET"), any())).thenReturn(List.of(
                new PortfolioMarketMetadata(
                        "POLYMARKET", "condition", "local-event", "Local event",
                        "local-market", "Local market", Instant.now())));
        when(metadataRepository.findLatestByMarketTickers(eq("POLYMARKET"), any())).thenReturn(List.of(
                new PortfolioMarketMetadata(
                        "POLYMARKET", "other-condition", "slug-event", "Slug event",
                        "source-slug", "Slug market", Instant.now())));

        VenueCollectionResult result = collector.collect();

        assertThat(result.successful()).isTrue();
        assertThat(result.positions()).singleElement().satisfies(position -> {
            assertThat(position.marketTicker()).isEqualTo("source-slug");
            assertThat(position.eventTicker()).isEqualTo("local-event");
            assertThat(position.eventTitle()).isEqualTo("Local event");
            assertThat(position.marketTitle()).isEqualTo("Local market");
            assertThat(position.spentUsd()).isEqualByComparingTo("0.55");
            assertThat(position.quantity()).isEqualByComparingTo("1.2500000001");
        });
    }

    @Test
    void fallsBackToInitialValueAndVenueMetadataWhenDatabaseLookupFails() {
        when(client.getCurrentPositions()).thenReturn(List.of(
                position("asset", "condition", "slug", "No", "2", "0.75", null)));
        when(metadataRepository.findLatestByConditionIds(eq("POLYMARKET"), any()))
                .thenThrow(new RuntimeException("database unavailable"));

        VenueCollectionResult result = collector.collect();

        assertThat(result.successful()).isTrue();
        assertThat(result.positions()).singleElement().satisfies(position -> {
            assertThat(position.eventTicker()).isEqualTo("source-event");
            assertThat(position.marketTitle()).isEqualTo("Source market");
            assertThat(position.spentUsd()).isEqualByComparingTo("0.75");
        });
    }

    @Test
    void treatsMissingRequiredIdentityAsFailedSnapshot() {
        when(client.getCurrentPositions()).thenReturn(List.of(
                position(null, "condition", null, "Yes", "1", "0.50", null)));

        VenueCollectionResult result = collector.collect();

        assertThat(result.successful()).isFalse();
        assertThat(result.message()).contains("required identity");
    }

    @Test
    void fallsBackToConditionMatchedTickerWhenSourceSlugIsMissing() {
        when(client.getCurrentPositions()).thenReturn(List.of(
                position("asset", "condition", null, "Yes", "1", "0.50", null)));
        when(metadataRepository.findLatestByConditionIds(eq("POLYMARKET"), any())).thenReturn(List.of(
                new PortfolioMarketMetadata(
                        "POLYMARKET", "condition", "event", "Event",
                        "canonical-ticker", "Market", Instant.now())));

        VenueCollectionResult result = collector.collect();

        assertThat(result.successful()).isTrue();
        assertThat(result.positions()).singleElement()
                .extracting(position -> position.marketTicker())
                .isEqualTo("canonical-ticker");
    }

    private PolymarketPositionDto position(
            String asset,
            String condition,
            String slug,
            String outcome,
            String size,
            String initialValue,
            String grossInitialValue
    ) {
        return new PolymarketPositionDto(
                asset,
                condition,
                new BigDecimal(size),
                new BigDecimal("0.40"),
                new BigDecimal(initialValue),
                grossInitialValue == null ? null : new BigDecimal(grossInitialValue),
                new BigDecimal("0.80"),
                "Source market",
                slug,
                "source-event",
                outcome);
    }
}
