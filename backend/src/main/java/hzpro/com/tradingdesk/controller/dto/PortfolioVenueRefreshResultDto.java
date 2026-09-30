package hzpro.com.tradingdesk.controller.dto;

import java.time.Instant;

public record PortfolioVenueRefreshResultDto(
        String venue,
        String status,
        Instant lastSuccessfulRefreshAt,
        String message
) {
}
