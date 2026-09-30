package hzpro.com.tradingdesk.controller.dto;

import java.time.Instant;

public record SettingsErrorResponseDto(String message, Instant timestamp) {
}
