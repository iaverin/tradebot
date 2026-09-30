package hzpro.com.tradingdesk.portfolio.service;

import hzpro.com.tradingdesk.client.PolymarketDataApiClient;
import hzpro.com.tradingdesk.client.dto.PolymarketPositionDto;
import hzpro.com.tradingdesk.portfolio.model.CollectedPortfolioPosition;
import hzpro.com.tradingdesk.portfolio.model.PortfolioVenue;
import hzpro.com.tradingdesk.portfolio.model.VenueCollectionResult;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioMarketMetadataRepository;
import hzpro.com.tradingdesk.portfolio.repository.PortfolioMarketMetadataRepository.PortfolioMarketMetadata;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
public class PolymarketPortfolioPositionCollector implements PortfolioVenueCollector {

    private final PolymarketDataApiClient client;
    private final PortfolioMarketMetadataRepository metadataRepository;

    public PolymarketPortfolioPositionCollector(
            PolymarketDataApiClient client,
            PortfolioMarketMetadataRepository metadataRepository
    ) {
        this.client = client;
        this.metadataRepository = metadataRepository;
    }

    @Override
    public PortfolioVenue venue() {
        return PortfolioVenue.POLYMARKET;
    }

    @Override
    public VenueCollectionResult collect() {
        List<PolymarketPositionDto> sourcePositions = client.getCurrentPositions();
        if (sourcePositions == null) {
            return VenueCollectionResult.failure("Polymarket position fetch failed");
        }

        List<PolymarketPositionDto> openPositions = sourcePositions.stream()
                .filter(position -> position.size() != null && position.size().compareTo(BigDecimal.ZERO) > 0)
                .toList();
        Map<String, PortfolioMarketMetadata> byCondition;
        Map<String, PortfolioMarketMetadata> byTicker;
        try {
            byCondition = indexBy(
                    metadataRepository.findLatestByConditionIds(
                            venue().name(),
                            nonBlankValues(openPositions.stream().map(PolymarketPositionDto::conditionId).toList())),
                    PortfolioMarketMetadata::conditionId);
            byTicker = indexBy(
                    metadataRepository.findLatestByMarketTickers(
                            venue().name(),
                            nonBlankValues(openPositions.stream().map(PolymarketPositionDto::slug).toList())),
                    PortfolioMarketMetadata::marketTicker);
        } catch (RuntimeException exception) {
            log.warn("Polymarket metadata enrichment failed: {}", exception.getMessage());
            byCondition = Map.of();
            byTicker = Map.of();
        }

        java.util.ArrayList<CollectedPortfolioPosition> normalized = new java.util.ArrayList<>();
        for (PolymarketPositionDto source : openPositions) {
            String outcome = normalizeOutcome(source.outcome());
            if (outcome == null) {
                log.warn("Skipping unsupported Polymarket outcome {}", source.outcome());
                continue;
            }
            PortfolioMarketMetadata metadata = firstNonNull(
                    byCondition.get(source.conditionId()),
                    byTicker.get(source.slug()));
            String marketTicker = firstNonBlank(source.slug(), metadata == null ? null : metadata.marketTicker());
            if (isBlank(source.asset()) || isBlank(marketTicker)) {
                return VenueCollectionResult.failure("Polymarket returned a position without a required identity");
            }
            normalized.add(new CollectedPortfolioPosition(
                    source.asset(),
                    marketTicker,
                    source.conditionId(),
                    metadata == null ? source.eventSlug() : firstNonBlank(metadata.eventTicker(), source.eventSlug()),
                    metadata == null ? null : metadata.eventTitle(),
                    metadata == null ? source.title() : firstNonBlank(metadata.marketTitle(), source.title()),
                    outcome,
                    source.size(),
                    source.grossInitialValue() == null ? source.initialValue() : source.grossInitialValue(),
                    source.currentValue(),
                    null));
        }
        return VenueCollectionResult.success(normalized);
    }

    private Map<String, PortfolioMarketMetadata> indexBy(
            List<PortfolioMarketMetadata> metadata,
            Function<PortfolioMarketMetadata, String> keyExtractor
    ) {
        return metadata.stream().filter(item -> !isBlank(keyExtractor.apply(item))).collect(Collectors.toMap(
                keyExtractor,
                Function.identity(),
                (first, second) -> first));
    }

    private Collection<String> nonBlankValues(List<String> values) {
        return values.stream().filter(value -> !isBlank(value)).distinct().toList();
    }

    private String normalizeOutcome(String value) {
        if (value == null) {
            return null;
        }
        String normalized = value.trim().toUpperCase(Locale.ROOT);
        return "YES".equals(normalized) || "NO".equals(normalized) ? normalized : null;
    }

    private String firstNonBlank(String first, String second) {
        return !isBlank(first) ? first : second;
    }

    private <T> T firstNonNull(T first, T second) {
        return first != null ? first : second;
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
