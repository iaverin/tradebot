package hzpro.com.tradingdesk.controller.dto;

public record FetcherStatusResponse(
    Long polymarketFetchedCount,
    Long polymarketRequestsCompleted,
    Long kalshiFetchedCount,
    Long kalshiRequestsCompleted,
    Long opinionFetchedCount,
    Long opinionRequestsCompleted
) {
}


