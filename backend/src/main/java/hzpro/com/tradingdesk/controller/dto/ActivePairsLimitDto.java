package hzpro.com.tradingdesk.controller.dto;

import jakarta.validation.constraints.Min;

public record ActivePairsLimitDto(@Min(1) int activePairsLimit) {
}
