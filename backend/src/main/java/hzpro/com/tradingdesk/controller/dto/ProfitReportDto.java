package hzpro.com.tradingdesk.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;

public record ProfitReportDto(
        Instant closeDate,
        long uniquePairs,
        long opportunityCount,
        BigDecimal orderTotal,
        BigDecimal filledTotal,
        BigDecimal orderProfit,
        BigDecimal filledProfit
) {}