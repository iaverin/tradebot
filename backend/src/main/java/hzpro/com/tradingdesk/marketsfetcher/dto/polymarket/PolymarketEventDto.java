package hzpro.com.tradingdesk.marketsfetcher.dto.polymarket;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class PolymarketEventDto {

    private String id;

    private String ticker;

    private String slug;

    private String title;

    private String description;

    private List<PolymarketMarketDto> markets = new ArrayList<>();
}
