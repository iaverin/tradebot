package hzpro.com.tradingdesk.controller.dto;

import java.util.List;

public record EventToggleResponse(
        String polymarketEventTicker,
        String kalshiEventTicker,
        boolean enabled,
        List<Long> affectedIds
) {}
