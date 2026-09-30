package hzpro.com.tradingdesk.portfolio.model;

import java.time.Instant;
import java.util.List;

public record PortfolioRefreshResult(
        Instant requestedAt,
        boolean allSucceeded,
        List<PortfolioVenueRefreshResult> venues
) {
    public PortfolioRefreshResult {
        venues = List.copyOf(venues);
    }
}
