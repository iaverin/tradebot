package hzpro.com.tradingdesk.arbitrage.dto;

import java.util.List;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;



@JsonIgnoreProperties(ignoreUnknown = true)
public record PolymarketOrderBookDto(
    String market,
    @JsonAlias("asset_id") String assetId,
    String timestamp,
    String hash,
    List<PolymarketOrderBookDtoEntry> asks,
    List<PolymarketOrderBookDtoEntry> bids
) {

}
