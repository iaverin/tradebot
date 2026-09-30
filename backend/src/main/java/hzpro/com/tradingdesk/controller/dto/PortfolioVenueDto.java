package hzpro.com.tradingdesk.controller.dto;

import java.math.BigDecimal;

/**
 * Six portfolio indicators for a single trading venue.
 * Any field may be {@code null} if the underlying API call failed.
 */
public record PortfolioVenueDto(
        BigDecimal totalPortfolioUsd,
        BigDecimal inCashUsd,
        BigDecimal inOrdersUsd,
        BigDecimal inAssetsUsd,
        BigDecimal resolvedProfit,
        BigDecimal resolvedLoss
) {}
