package hzpro.com.tradingdesk.portfolio.model;

import java.time.Instant;

public record PortfolioVenueRefreshResult(
        PortfolioVenue venue,
        PortfolioRefreshStatus status,
        Instant lastSuccessfulRefreshAt,
        String message
) {
}
