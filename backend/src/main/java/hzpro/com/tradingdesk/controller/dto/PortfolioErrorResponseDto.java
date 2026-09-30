package hzpro.com.tradingdesk.controller.dto;

import java.time.Instant;

public record PortfolioErrorResponseDto(String message, Instant timestamp) {
}
