package hzpro.com.tradingdesk.arbitrage.model;

import java.util.UUID;

public record ArbitrageOrderLifecycleEvent(
        UUID opportunityUuid,
        String polymarketTicker,
        String kalshiTicker,
        Long databaseOrderId,
        String platform,
        OrderState status) {
}
