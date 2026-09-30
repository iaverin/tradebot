package hzpro.com.tradingdesk.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record PortfolioPositionDto(
        Long id,
        String venue,
        String eventTicker,
        String eventTitle,
        String marketTicker,
        String marketTitle,
        String outcome,
        BigDecimal quantity,
        BigDecimal spentUsd,
        BigDecimal currentValueUsd,
        Instant refreshedAt
) {
}
