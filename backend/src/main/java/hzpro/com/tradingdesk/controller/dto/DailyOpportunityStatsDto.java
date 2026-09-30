package hzpro.com.tradingdesk.controller.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyOpportunityStatsDto(
        LocalDate date,
        long opportunityCount,
        long orderCount,
        BigDecimal ordersPerOpportunity,
        BigDecimal createdOrderVolume) {
}
