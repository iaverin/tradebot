package hzpro.com.tradingdesk.marketsfetcher.dto.kalshi;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KalshiEventDto {

    @JsonProperty("event_ticker")
    private String eventTicker;

    private String title;

    @JsonProperty("sub_title")
    private String subTitle;

    private List<KalshiMarketDto> markets = new ArrayList<>();
}
