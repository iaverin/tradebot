package hzpro.com.tradingdesk.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Response item from Polymarket Data API {@code GET /closed-positions?user={addr}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolymarketClosedPositionDto(
        @JsonProperty("conditionId") String conditionId,
        @JsonProperty("asset") String asset,
        @JsonProperty("title") String title,
        @JsonProperty("avgPrice") BigDecimal avgPrice,
        @JsonProperty("totalBought") BigDecimal totalBought,
        @JsonProperty("realizedPnl") BigDecimal realizedPnl,
        @JsonProperty("curPrice") BigDecimal curPrice,
        @JsonProperty("timestamp") Long timestamp,
        @JsonProperty("outcome") String outcome
) {}
