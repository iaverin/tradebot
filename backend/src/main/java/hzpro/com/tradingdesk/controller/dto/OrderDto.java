package hzpro.com.tradingdesk.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

public record OrderDto(
        Long id,
        UUID opportunityUuid,
        String platform,
        String contractType,
        BigDecimal price,
        Long quantity,
        String orderId,
        String status,
        Instant createdAt,
        Instant updatedAt,
        Long filledQuantity,
        BigDecimal filledAmount,
        Instant executedAt,
        String eventTicker,
        String eventTitle,
        String marketTitle,
        String marketTicker
) {}