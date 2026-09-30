package hzpro.com.tradingdesk.controller.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.UUID;

public record ActiveArbitrageEventDto(
        Long id,
        UUID uuid,
        Instant detectedAt,
        Long similarMarketId,
        String direction,
        BigDecimal spread,
        BigDecimal polymarketYesAsk,
        BigDecimal polymarketNoAsk,
        BigDecimal kalshiYesAsk,
        BigDecimal kalshiNoAsk,
        String polymarketMarketTicker,
        String polymarketEventTicker,
        String polymarketEventTitle,
        String polymarketMarketTitle,
        OffsetDateTime polymarketMarketCloseDatetime,
        String kalshiMarketTicker,
        String kalshiEventTicker,
        String kalshiEventTitle,
        String kalshiMarketTitle,
        OffsetDateTime kalshiMarketCloseDatetime
) {
}