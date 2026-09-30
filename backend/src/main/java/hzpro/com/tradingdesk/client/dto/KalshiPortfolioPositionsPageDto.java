package hzpro.com.tradingdesk.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KalshiPortfolioPositionsPageDto(
        @JsonProperty("market_positions") List<KalshiMarketPositionDto> marketPositions,
        String cursor
) {
    public KalshiPortfolioPositionsPageDto {
        marketPositions = marketPositions == null ? List.of() : List.copyOf(marketPositions);
    }
}
