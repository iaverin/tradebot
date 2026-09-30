package hzpro.com.tradingdesk.client.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record KalshiMarketsPageDto(List<KalshiMarketQuoteDto> markets, String cursor) {
    public KalshiMarketsPageDto {
        markets = markets == null ? List.of() : List.copyOf(markets);
    }
}
