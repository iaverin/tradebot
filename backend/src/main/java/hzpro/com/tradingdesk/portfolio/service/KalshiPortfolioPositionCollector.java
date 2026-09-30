package hzpro.com.tradingdesk.portfolio.service;

import hzpro.com.tradingdesk.client.dto.KalshiMarketPositionDto;
import hzpro.com.tradingdesk.client.dto.KalshiMarketQuoteDto;
import hzpro.com.tradingdesk.portfolio.client.KalshiPortfolioPositionClient;
import hzpro.com.tradingdesk.portfolio.model.CollectedPortfolioPosition;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenue;
import hzpro.com.tradingdesk.portfolio.model.VenueCollectionResult;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioMarketMetadataRepository;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioMarketMetadataRepository.PortfolioMarketMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class KalshiPortfolioPositionCollector implements PortfolioVenueCollector {

    private final KalshiPortfolioPositionClient client;
    private final PortfolioMarketMetadataRepository metadataRepository;

    public KalshiPortfolioPositionCollector(
            KalshiPortfolioPositionClient client,
            PortfolioMarketMetadataRepository metadataRepository
    ) {
        this.client = client;
        this.metadataRepository = metadataRepository;
    }

    @Override
    public PortfolioVenue venue() {
        return PortfolioVenue.KALSHI;
    }

    @Override
    public VenueCollectionResult collect() {
        List<KalshiMarketPositionDto> sourcePositions = client.getCurrentPositions();
        if (sourcePositions == null) {
            return VenueCollectionResult.failure("Kalshi position fetch failed");
        }
        List<KalshiMarketPositionDto> openPositions = sourcePositions.stream()
                .filter(position -> position.positionFp() != null
                        && position.positionFp().compareTo(BigDecimal.ZERO) != 0)
                .toList();
        List<String> tickers = openPositions.stream()
                .map(KalshiMarketPositionDto::ticker)
                .filter(ticker -> ticker != null && !ticker.isBlank())
                .distinct()
                .toList();

        Map<String, KalshiMarketQuoteDto> quotes;
        try {
            quotes = client.getMarkets(tickers);
        } catch (RuntimeException exception) {
            log.warn("Kalshi quote enrichment failed: {}", exception.getMessage());
            quotes = Map.of();
        }
        Map<String, PortfolioMarketMetadata> metadata = new LinkedHashMap<>();
        try {
            for (PortfolioMarketMetadata row : metadataRepository.findLatestByMarketTickers(venue().name(), tickers)) {
                metadata.putIfAbsent(row.marketTicker(), row);
            }
        } catch (RuntimeException exception) {
            log.warn("Kalshi metadata enrichment failed: {}", exception.getMessage());
        }

        java.util.ArrayList<CollectedPortfolioPosition> normalized = new java.util.ArrayList<>();
        for (KalshiMarketPositionDto source : openPositions) {
            if (source.ticker() == null || source.ticker().isBlank()) {
                return VenueCollectionResult.failure("Kalshi returned a position without a market ticker");
            }
            boolean yes = source.positionFp().compareTo(BigDecimal.ZERO) > 0;
            String outcome = yes ? "YES" : "NO";
            BigDecimal quantity = source.positionFp().abs();
            KalshiMarketQuoteDto quote = quotes.get(source.ticker());
            PortfolioMarketMetadata local = metadata.get(source.ticker());
            BigDecimal bid = quote == null ? null : (yes ? quote.yesBidDollars() : quote.noBidDollars());
            if (quote == null || bid == null) {
                log.warn("Kalshi quote unavailable for position {}", source.ticker());
            }
            normalized.add(new CollectedPortfolioPosition(
                    source.ticker(),
                    source.ticker(),
                    null,
                    local != null ? local.eventTicker() : quote == null ? null : quote.eventTicker(),
                    local != null ? local.eventTitle() : quote == null ? null : quote.title(),
                    local != null ? local.marketTitle() : quote == null
                            ? null
                            : firstNonBlank(quote.subtitle(), quote.title()),
                    outcome,
                    quantity,
                    source.marketExposureDollars() == null ? null : source.marketExposureDollars().abs(),
                    bid == null ? null : quantity.multiply(bid),
                    source.lastUpdatedTs()));
        }
        return VenueCollectionResult.success(normalized);
    }

    private String firstNonBlank(String first, String second) {
        return first != null && !first.isBlank() ? first : second;
    }
}
