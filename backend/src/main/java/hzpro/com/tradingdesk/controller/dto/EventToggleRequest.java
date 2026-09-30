package hzpro.com.tradingdesk.controller.dto;

public record EventToggleRequest(
        String polymarketEventTicker,
        String kalshiEventTicker,
        boolean enabled
) {}
