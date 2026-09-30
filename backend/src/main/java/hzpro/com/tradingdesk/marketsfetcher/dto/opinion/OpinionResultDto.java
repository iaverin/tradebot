package hzpro.com.tradingdesk.marketsfetcher.dto.opinion;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class OpinionResultDto {
    private long total;
    private List<OpinionMarketDto> list = new ArrayList<>();
}
