package hzpro.com.tradingdesk.marketsfetcher.dto.kalshi;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.time.ZonedDateTime;

@Data
public class KalshiMarketDto {

    private String ticker;

    @JsonProperty("open_time")
    private ZonedDateTime openTime;

    @JsonProperty("close_time")
    private ZonedDateTime closeTime;

    private String title;

    private String subtitle;

    @JsonProperty("rules_primary")
    private String rulesPrimary;

    @JsonProperty("rules_secondary")
    private String rulesSecondary;

    private String status;

    private String result;

    @JsonProperty("yes_sub_title")
    private String yesSubTitle;
}
