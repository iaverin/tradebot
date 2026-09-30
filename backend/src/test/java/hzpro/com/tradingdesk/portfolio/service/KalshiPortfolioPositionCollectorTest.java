package hzpro.com.tradingdesk.portfolio.service;

import hzpro.com.tradingdesk.client.dto.KalshiMarketPositionDto;
import hzpro.com.tradingdesk.client.dto.KalshiMarketQuoteDto;
import hzpro.com.tradingdesk.portfolio.client.KalshiPortfolioPositionClient;
import hzpro.com.tradingdesk.portfolio.model.VenueCollectionResult;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioMarketMetadataRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KalshiPortfolioPositionCollectorTest {

    private KalshiPortfolioPositionClient client;
    private PortfolioMarketMetadataRepository metadataRepository;
    private KalshiPortfolioPositionCollector collector;

    @BeforeEach
    void setUp() {
        client = mock(KalshiPortfolioPositionClient.class);
        metadataRepository = mock(PortfolioMarketMetadataRepository.class);
        collector = new KalshiPortfolioPositionCollector(client, metadataRepository);
        when(metadataRepository.findLatestByMarketTickers(eq("KALSHI"), anyList())).thenReturn(List.of());
    }

    @Test
    void mapsSignedPositionsAndUsesTheOutcomeSpecificBid() {
        when(client.getCurrentPositions()).thenReturn(List.of(
                position("YES-TICKER", "2.5", "1.50"),
                position("NO-TICKER", "-3.25", "-2.00")));
        when(client.getMarkets(anyList())).thenReturn(Map.of(
                "YES-TICKER", quote("YES-TICKER", "0.60", "0.39"),
                "NO-TICKER", quote("NO-TICKER", "0.21", "0.78")));

        VenueCollectionResult result = collector.collect();

        assertThat(result.successful()).isTrue();
        assertThat(result.positions()).hasSize(2);
        assertThat(result.positions().get(0).outcome()).isEqualTo("YES");
        assertThat(result.positions().get(0).currentValueUsd()).isEqualByComparingTo("1.500");
        assertThat(result.positions().get(1).outcome()).isEqualTo("NO");
        assertThat(result.positions().get(1).quantity()).isEqualByComparingTo("3.25");
        assertThat(result.positions().get(1).spentUsd()).isEqualByComparingTo("2.00");
        assertThat(result.positions().get(1).currentValueUsd()).isEqualByComparingTo("2.5350");
    }

    @Test
    void missingQuoteLeavesCurrentValueNull() {
        when(client.getCurrentPositions()).thenReturn(List.of(position("KS", "1.5", "0.5")));
        when(client.getMarkets(anyList())).thenReturn(Map.of());

        VenueCollectionResult result = collector.collect();

        assertThat(result.successful()).isTrue();
        assertThat(result.positions().getFirst().currentValueUsd()).isNull();
    }

    private KalshiMarketPositionDto position(String ticker, String quantity, String exposure) {
        return new KalshiMarketPositionDto(
                ticker,
                new BigDecimal(quantity),
                new BigDecimal(exposure),
                Instant.parse("2026-09-10T10:15:30Z"));
    }

    private KalshiMarketQuoteDto quote(String ticker, String yesBid, String noBid) {
        return new KalshiMarketQuoteDto(
                ticker,
                "EVENT",
                "Event title",
                "Market title",
                new BigDecimal(yesBid),
                new BigDecimal(noBid));
    }
}
