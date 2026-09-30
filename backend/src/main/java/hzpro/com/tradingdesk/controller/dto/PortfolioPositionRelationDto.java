package hzpro.com.tradingdesk.controller.dto;

public record PortfolioPositionRelationDto(
        long similarMarketId,
        String polymarketMarketTicker,
        String polymarketOutcome,
        String kalshiMarketTicker,
        String kalshiOutcome
) {
}
