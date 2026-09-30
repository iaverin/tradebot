package hzpro.com.tradingdesk.controller.dto;

import java.time.Instant;

public record OpportunityReportDto(
        Long similarMarketId,
        String polymarketEventTicker,
        String polymarketEventTitle,
        String polymarketMarketTitle,
        String polymarketMarketTicker,
        String kalshiEventTicker,
        String kalshiEventTitle,
        String kalshiMarketTitle,
        String kalshiMarketTicker,
        Instant polymarketMarketCloseDatetime,
        Instant kalshiMarketCloseDatetime,
        Instant opportunityCreatedAt,
        long opportunityCount,
        long oppsWithOrders,
        long oppsWithUnexecuted,
        long pmOrders,
        long ksOrders,
        long pmExecutedCount,
        long ksExecutedCount,
        long spreadBelowThresholdCloseCount,
        long priceDataStaleCloseCount,
        long priceDataUnavailableCloseCount,
        long orderCreationFailedCloseCount,
        long orderbookPriceBelowThresholdCloseCount,
        long orderBookNotEnoughAmountCloseCount,
        long orderbookEmptyCloseCount,
        long minimumOrderCostNotMetCloseCount,
        long activePairLimitReachedCloseCount,
        long ordersCreatedCloseCount,
        long unknownCloseCount
) {}
