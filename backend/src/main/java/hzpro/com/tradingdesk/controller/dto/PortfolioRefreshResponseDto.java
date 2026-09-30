package hzpro.com.tradingdesk.controller.dto;

import java.time.Instant;
import java.util.List;

public record PortfolioRefreshResponseDto(
        Instant requestedAt,
        boolean allSucceeded,
        List<PortfolioVenueRefreshResultDto> venues
) {
    public PortfolioRefreshResponseDto {
        venues = List.copyOf(venues);
    }
}
