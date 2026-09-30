package hzpro.com.tradingdesk.portfolio.model;

import java.math.BigDecimal;
import java.time.Instant;

public record CollectedPortfolioPosition(
        String sourcePositionId,
        String marketTicker,
        String conditionId,
        String eventTicker,
        String eventTitle,
        String marketTitle,
        String outcome,
        BigDecimal quantity,
        BigDecimal spentUsd,
        BigDecimal currentValueUsd,
        Instant sourceUpdatedAt
) {
}
