package hzpro.com.tradingdesk.marketsfetcher.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.marketsfetcher.dto.polymarket.PolymarketEventDto;
import hzpro.com.tradingdesk.marketsfetcher.dto.polymarket.PolymarketMarketDto;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
@Slf4j
public class PolymarketMapper {

    private final ObjectMapper objectMapper;

    public List<PredictionMarket> mapToPredictionMarketList(String eventsRawResponse) {
        List<PredictionMarket> predictionMarkets = new ArrayList<>();

        try {
            List<PolymarketEventDto> events = objectMapper.readValue(
                    eventsRawResponse,
                    new TypeReference<List<PolymarketEventDto>>() {}
            );

            for (PolymarketEventDto event : events) {
                for (PolymarketMarketDto market : event.getMarkets()) {
                    PredictionMarket predictionMarket = PredictionMarket.builder()
                            .datasource(DataSource.POLYMARKET)
                            .eventId(event.getId())
                            .eventTicker(event.getTicker())
                            .eventTitle(event.getTitle())
                            .eventSubtitle(null)
                            .eventDescription(event.getDescription())
                            .marketTicker(market.getSlug())
                            .marketOpenDatetime(market.getStartDate())
                            .marketCloseDatetime(market.getEndDate())
                            .marketTitle(market.getGroupItemTitle())
                            .marketDescription(market.getDescription())
                            .marketStatus(market.getUmaResolutionStatus())
                            .marketResult(produceMarketResult(market))
                            .marketRawData("")
                            .build();

                    parseAndSetTokenIds(predictionMarket, market.getClobTokenIds());
                    predictionMarket.setConditionId(market.getConditionId());
                    predictionMarkets.add(predictionMarket);
                }
            }

            log.info("Successfully mapped {} Polymarket markets", predictionMarkets.size());

        } catch (JsonProcessingException e) {
            log.error("Failed to parse Polymarket response", e);
            return new ArrayList<>();
        }

        return predictionMarkets;
    }

    // ========== ДОБАВИТЬ ЭТОТ МЕТОД ==========
    private void parseAndSetTokenIds(PredictionMarket market, String clobTokenIdsJson) {
        if (clobTokenIdsJson == null || clobTokenIdsJson.isEmpty()) {
            return;
        }

        try {
            List<String> tokenIds = objectMapper.readValue(
                    clobTokenIdsJson,
                    new TypeReference<List<String>>() {}
            );

            if (tokenIds.size() >= 2) {
                market.setYesTokenId(tokenIds.get(0));
                market.setNoTokenId(tokenIds.get(1));
                log.debug("Set token IDs for {}: YES={}, NO={}",
                        market.getMarketTicker(), tokenIds.get(0), tokenIds.get(1));
            }
        } catch (Exception e) {
            log.warn("Failed to parse clobTokenIds for {}: {}",
                    market.getMarketTicker(), e.getMessage());
        }
    }
    // =========================================

    private String produceMarketResult(PolymarketMarketDto market) {
        try {
            Map<String, Object> result = new HashMap<>();

            Object marketOutcomes = parseJsonString(market.getOutcomes());
            result.put("market_outcomes", marketOutcomes);

            Object outcomePrices = parseJsonString(market.getOutcomePrices());
            result.put("outcome_prices", outcomePrices);

            return objectMapper.writeValueAsString(result);

        } catch (Exception e) {
            log.error("Failed to produce market result", e);
            return null;
        }
    }

    private Object parseJsonString(String jsonString) {
        if (jsonString == null || jsonString.isEmpty()) {
            return null;
        }

        try {
            JsonNode node = objectMapper.readTree(jsonString);
            return objectMapper.convertValue(node, Object.class);
        } catch (JsonProcessingException e) {
            return jsonString;
        }
    }
}