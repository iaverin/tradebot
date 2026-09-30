package hzpro.com.tradingdesk.arbitrage.model;

import java.math.BigDecimal;
import java.util.UUID;

public record UnfinishedOrderCandidate(
        Long databaseOrderId,
        UUID opportunityUuid,
        String platform,
        String providerOrderId,
        OrderState status,
        BigDecimal price,
        String polymarketTicker,
        String kalshiTicker) {
}
