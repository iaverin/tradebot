package hzpro.com.tradingdesk.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;

/**
 * Response from Polymarket Data API {@code GET /value?user={addr}}.
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record PolymarketPortfolioValueDto(
        @JsonProperty("user") String user,
        @JsonProperty("value") BigDecimal value
) {}
