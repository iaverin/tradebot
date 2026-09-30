package hzpro.com.tradingdesk.marketsfetcher.dto.kalshi;

import lombok.Data;

import java.util.ArrayList;
import java.util.List;

@Data
public class KalshiResponseDto {

    private String cursor;

    private List<KalshiEventDto> events = new ArrayList<>();
}
