package hzpro.com.tradingdesk.controller.dto;

public record MarketPairInfo(
        Long id,
        String polymarketMarketTicker,
        String kalshiMarketTicker
) {}
