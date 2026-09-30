package hzpro.com.tradingdesk.portfolio.model;

import java.util.List;

public record VenueCollectionResult(
        boolean successful,
        List<CollectedPortfolioPosition> positions,
        String message
) {
    public VenueCollectionResult {
        positions = positions == null ? List.of() : List.copyOf(positions);
    }

    public static VenueCollectionResult success(List<CollectedPortfolioPosition> positions) {
        return new VenueCollectionResult(true, positions, null);
    }

    public static VenueCollectionResult failure(String message) {
        return new VenueCollectionResult(false, List.of(), message);
    }
}
