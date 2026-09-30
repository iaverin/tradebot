package hzpro.com.tradingdesk.controller.dto;

import java.util.List;

public record PortfolioPositionGroupDto(
        Long groupId,
        PortfolioPositionDto primary,
        List<PortfolioPositionDto> related
) {
    public PortfolioPositionGroupDto {
        related = List.copyOf(related);
    }
}
