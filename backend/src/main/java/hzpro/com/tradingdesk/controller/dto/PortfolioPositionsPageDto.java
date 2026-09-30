package hzpro.com.tradingdesk.controller.dto;

import java.time.Instant;
import java.util.List;

public record PortfolioPositionsPageDto(
        String selectedVenue,
        Instant lastSuccessfulRefreshAt,
        List<PortfolioPositionGroupDto> content,
        int page,
        int size,
        long totalElements,
        int totalPages
) {
    public PortfolioPositionsPageDto {
        content = List.copyOf(content);
    }
}
