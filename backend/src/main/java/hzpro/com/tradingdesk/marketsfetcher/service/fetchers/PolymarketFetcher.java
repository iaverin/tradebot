package hzpro.com.tradingdesk.marketsfetcher.service.fetchers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.stereotype.Service;

import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.marketsfetcher.lib.BaseFetcher;
import hzpro.com.tradingdesk.marketsfetcher.lib.FetcherState;
import hzpro.com.tradingdesk.marketsfetcher.mapper.PolymarketMapper;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class PolymarketFetcher extends BaseFetcher {

    private static final String BASE_URL = "https://gamma-api.polymarket.com/events/keyset";
    private static final int LIMIT = 500;
    private static final int RATE_LIMIT_PAUSE_MS = 2000;
    private static final int REQUESTS_PER_PAUSE = 50;

    private final PolymarketMapper mapper;
    private final ObjectMapper objectMapper;

    public PolymarketFetcher(CloseableHttpClient httpClient,
                             PredictionMarketRepository repository,
                             PolymarketMapper mapper,
                             ObjectMapper objectMapper) {
        super(httpClient, repository);
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    protected RequestParameters prepareRequest(FetcherState state) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("limit", LIMIT);
        queryParams.put("active", true);
        queryParams.put("closed", false);
        queryParams.put("ascending", true);

        if (state.getResponseText() != null && !state.getResponseText().isEmpty()) {
            String cursor = extractCursor(state.getResponseText());
            if (cursor != null && !cursor.isEmpty()) {
                queryParams.put("after_cursor", cursor);
            }
        }

        return RequestParameters.builder()
                .url(BASE_URL)
                .queryParams(queryParams)
                .build();
    }

    @Override
    protected List<PredictionMarket> parseResponse(String response) {
        try {
            JsonNode root = objectMapper.readTree(response);
            JsonNode eventsNode = root.get("events");
            if (eventsNode != null && eventsNode.isArray()) {
                String eventsJson = objectMapper.writeValueAsString(eventsNode);
                return mapper.mapToPredictionMarketList(eventsJson);
            }
            return List.of();
        } catch (Exception e) {
            log.error("Failed to parse Polymarket response", e);
            return List.of();
        }
    }

    @Override
    protected boolean hasMoreData(String response, int itemCount) {
        if (itemCount == 0) {
            return false;
        }

        String cursor = extractCursor(response);
        return cursor != null && !cursor.isEmpty();
    }

    @Override
    protected FetcherState initialState() {
        return FetcherState.initial(BASE_URL).toBuilder()
                .responseText("{\"events\":[],\"next_cursor\":\"\"}")
                .build();
    }

    private String extractCursor(String response) {
        try {
            JsonNode jsonNode = objectMapper.readTree(response);
            JsonNode cursorNode = jsonNode.get("next_cursor");
            return cursorNode != null ? cursorNode.asText() : null;
        } catch (Exception e) {
            log.error("Failed to extract cursor from response", e);
            return null;
        }
    }

}