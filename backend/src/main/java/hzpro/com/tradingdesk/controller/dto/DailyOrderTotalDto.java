package hzpro.com.tradingdesk.controller.dto;

import java.math.BigDecimal;
import java.time.LocalDate;

public record DailyOrderTotalDto(LocalDate date, BigDecimal totalUsd) {}
