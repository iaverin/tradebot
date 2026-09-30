package hzpro.com.tradingdesk.marketsfetcher.dto.polymarket;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolymarketMarketDto {

    private String slug;
    private ZonedDateTime startDate;
    private ZonedDateTime endDate;
    private String question;
    private String description;
    private String outcomes;
    private String outcomePrices;
    private Boolean closed;
    private String umaResolutionStatus;
    private String groupItemTitle;

    @JsonProperty("clobTokenIds")
    private String clobTokenIds;

    @JsonProperty("conditionId")
    private String conditionId;
}