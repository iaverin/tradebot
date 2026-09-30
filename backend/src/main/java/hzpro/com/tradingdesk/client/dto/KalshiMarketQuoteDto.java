package hzpro.com.tradingdesk.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KalshiMarketQuoteDto(
        String ticker,
        @JsonProperty("event_ticker") String eventTicker,
        String title,
        String subtitle,
        @JsonProperty("yes_bid_dollars") BigDecimal yesBidDollars,
        @JsonProperty("no_bid_dollars") BigDecimal noBidDollars
) {
}
