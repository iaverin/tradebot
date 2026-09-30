package hzpro.com.tradingdesk.marketsfetcher.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.marketsfetcher.dto.kalshi.KalshiEventDto;
import hzpro.com.tradingdesk.marketsfetcher.dto.kalshi.KalshiMarketDto;
import hzpro.com.tradingdesk.marketsfetcher.dto.kalshi.KalshiResponseDto;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.List;

@Component
@RequiredArgsConstructor
@Slf4j
public class KalshiMapper {

    private final ObjectMapper objectMapper;

    public List<PredictionMarket> mapToPredictionMarketList(String eventsRawResponse) {
        List<PredictionMarket> predictionMarkets = new ArrayList<>();

        try {
            KalshiResponseDto response = objectMapper.readValue(eventsRawResponse, KalshiResponseDto.class);

            for (KalshiEventDto event : response.getEvents()) {
                for (KalshiMarketDto market : event.getMarkets()) {
                    PredictionMarket predictionMarket = PredictionMarket.builder()
                            .datasource(DataSource.KALSHI)
                            .eventId(null)
                            .eventTicker(event.getEventTicker())
                            .eventTitle(event.getTitle())
                            .eventSubtitle(event.getSubTitle())
                            .eventDescription(null)
                            .marketTicker(market.getTicker())
                            .marketOpenDatetime(market.getOpenTime())
                            .marketCloseDatetime(market.getCloseTime())
                            .marketTitle(market.getYesSubTitle())
                            .marketDescription(combineStrings(market.getRulesPrimary(), market.getRulesSecondary()))
                            .marketStatus(market.getStatus())
                            .marketResult(market.getResult())
                            .marketRawData("")
                            .build();

                    predictionMarkets.add(predictionMarket);
                }
            }

            log.info("Successfully mapped {} Kalshi markets", predictionMarkets.size());

        } catch (JsonProcessingException e) {
            log.error("Failed to parse Kalshi response", e);
            return new ArrayList<>();
        }

        return predictionMarkets;
    }

    private String combineStrings(String str1, String str2) {
        StringBuilder sb = new StringBuilder();
        if (str1 != null && !str1.isBlank()) {
            sb.append(str1);
        }
        if (str2 != null && !str2.isBlank()) {
            if (sb.length() > 0) {
                sb.append(" ");
            }
            sb.append(str2);
        }
        return sb.length() > 0 ? sb.toString() : null;
    }
}
