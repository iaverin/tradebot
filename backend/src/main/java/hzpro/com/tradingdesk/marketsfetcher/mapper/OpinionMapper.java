package hzpro.com.tradingdesk.marketsfetcher.mapper;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.marketsfetcher.dto.opinion.OpinionChildMarketDto;
import hzpro.com.tradingdesk.marketsfetcher.dto.opinion.OpinionMarketDto;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Maps opinion.trade market list items onto {@link PredictionMarket} rows using the
 * same event→markets decomposition Polymarket uses:
 * <ul>
 *   <li>Binary ({@code marketType == 0}) — the top-level market is both the event and
 *       a single market row.</li>
 *   <li>Categorical ({@code marketType == 1}) — the top-level market is the event; each
 *       entry in {@code childMarkets} becomes one market row.</li>
 * </ul>
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OpinionMapper {

    private static final int MARKET_TYPE_CATEGORICAL = 1;

    private final ObjectMapper objectMapper;

    public List<PredictionMarket> mapToPredictionMarketList(String rawListJson) {
        List<PredictionMarket> predictionMarkets = new ArrayList<>();

        try {
            List<OpinionMarketDto> markets = objectMapper.readValue(
                    rawListJson,
                    new TypeReference<List<OpinionMarketDto>>() {}
            );

            for (OpinionMarketDto market : markets) {
                boolean categorical = market.getMarketType() == MARKET_TYPE_CATEGORICAL
                        && market.getChildMarkets() != null
                        && !market.getChildMarkets().isEmpty();

                if (categorical) {
                    for (OpinionChildMarketDto child : market.getChildMarkets()) {
                        if (!child.getYesLabel().equalsIgnoreCase("YES")) {
                            continue;
                        }
                        predictionMarkets.add(mapChild(market, child));
                    }
                } else {
                    if (!market.getYesLabel().equalsIgnoreCase("YES")) {
                            continue;
                        }
                    predictionMarkets.add(mapBinary(market));
                }
            }

            log.info("Successfully mapped {} Opinion markets from {} list items",
                    predictionMarkets.size(), markets.size());

        } catch (JsonProcessingException e) {
            log.error("Failed to parse Opinion response", e);
            return new ArrayList<>();
        }

        return predictionMarkets;
    }

    private PredictionMarket mapBinary(OpinionMarketDto market) {
        String eventId = String.valueOf(market.getMarketId());
        return PredictionMarket.builder()
                .datasource(DataSource.OPINION)
                .eventId(eventId)
                .eventTicker(firstNonBlank(market.getQuestionId(), eventId))
                .eventTitle(market.getMarketTitle())
                .eventSubtitle(null)
                .eventDescription(market.getRules())
                .marketTicker(firstNonBlank(market.getConditionId(), eventId))
                .marketTitle(market.getMarketTitle())
                .marketDescription(market.getRules())
                .marketOpenDatetime(toZonedDateTime(market.getCreatedAt()))
                .marketCloseDatetime(toZonedDateTime(market.getCutoffAt()))
                .marketStatus(market.getStatusEnum())
                .marketResult(produceMarketResult(market.getYesLabel(), market.getNoLabel(),
                        market.getVolume(), market.getResultTokenId()))
                .marketRawData("")
                .yesTokenId(market.getYesTokenId())
                .noTokenId(market.getNoTokenId())
                .conditionId(market.getConditionId())
                .build();
    }

    private PredictionMarket mapChild(OpinionMarketDto parent, OpinionChildMarketDto child) {
        String parentEventId = String.valueOf(parent.getMarketId());
        String childId = String.valueOf(child.getMarketId());
        return PredictionMarket.builder()
                .datasource(DataSource.OPINION)
                .eventId(parentEventId)
                .eventTicker(firstNonBlank(parent.getQuestionId(), parentEventId))
                .eventTitle(parent.getMarketTitle())
                .eventSubtitle(null)
                .eventDescription(parent.getRules())
                .marketTicker(firstNonBlank(child.getConditionId(), childId))
                .marketTitle(child.getMarketTitle())
                .marketDescription(child.getRules())
                .marketOpenDatetime(toZonedDateTime(child.getCreatedAt()))
                .marketCloseDatetime(toZonedDateTime(child.getCutoffAt()))
                .marketStatus(child.getStatusEnum())
                .marketResult(produceMarketResult(child.getYesLabel(), child.getNoLabel(),
                        child.getVolume(), child.getResultTokenId()))
                .marketRawData("")
                .yesTokenId(child.getYesTokenId())
                .noTokenId(child.getNoTokenId())
                .conditionId(child.getConditionId())
                .build();
    }

    /** Epoch seconds → {@link ZonedDateTime} (UTC). Treats null/0 as "no datetime". */
    private ZonedDateTime toZonedDateTime(Long epochSeconds) {
        if (epochSeconds == null || epochSeconds <= 0) {
            return null;
        }
        return Instant.ofEpochSecond(epochSeconds).atZone(ZoneOffset.UTC);
    }

    private String produceMarketResult(String yesLabel, String noLabel, String volume, String resultTokenId) {
        try {
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("yesLabel", yesLabel);
            result.put("noLabel", noLabel);
            result.put("volume", volume);
            result.put("resultTokenId", resultTokenId);
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            log.error("Failed to produce Opinion market result", e);
            return null;
        }
    }

    private String firstNonBlank(String preferred, String fallback) {
        return (preferred != null && !preferred.isBlank()) ? preferred : fallback;
    }
}
