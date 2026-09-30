package hzpro.com.tradingdesk.marketsfetcher.dto.polymarket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class ClobMarketResponse {

    @JsonProperty("condition_id")
    private String conditionId;

    @JsonProperty("tokens")
    private List<ClobToken> tokens;

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class ClobToken {
        @JsonProperty("token_id")
        private String tokenId;

        @JsonProperty("outcome")
        private String outcome;
    }
}