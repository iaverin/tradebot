package hzpro.com.tradingdesk.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.math.BigDecimal;
import java.time.Instant;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KalshiMarketPositionDto(
        String ticker,
        @JsonProperty("position_fp") BigDecimal positionFp,
        @JsonProperty("market_exposure_dollars") BigDecimal marketExposureDollars,
        @JsonProperty("last_updated_ts") Instant lastUpdatedTs
) {
}
