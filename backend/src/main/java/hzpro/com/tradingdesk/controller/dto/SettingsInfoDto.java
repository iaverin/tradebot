package hzpro.com.tradingdesk.controller.dto;

import java.math.BigDecimal;

public record SettingsInfoDto(
        BigDecimal maxOrderCost,
        int minimumOrderShares,
        int errorsCount,
        int activePairsLimit) {
}
