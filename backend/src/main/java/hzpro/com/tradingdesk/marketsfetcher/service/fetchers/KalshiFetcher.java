package hzpro.com.tradingdesk.marketsfetcher.service.fetchers;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.marketsfetcher.lib.BaseFetcher;
import hzpro.com.tradingdesk.marketsfetcher.lib.FetcherState;
import hzpro.com.tradingdesk.marketsfetcher.mapper.KalshiMapper;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.stereotype.Service;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Service
public class KalshiFetcher extends BaseFetcher {

    private static final String BASE_URL = "https://api.elections.kalshi.com/trade-api/v2/events";
    private static final int LIMIT = 200;

    private final KalshiMapper mapper;
    private final ObjectMapper objectMapper;

    public KalshiFetcher(CloseableHttpClient httpClient,
                         PredictionMarketRepository repository,
                         KalshiMapper mapper,
                         ObjectMapper objectMapper) {
        super(httpClient, repository);
        this.mapper = mapper;
        this.objectMapper = objectMapper;
    }

    @Override
    protected RequestParameters prepareRequest(FetcherState state) {
        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("with_nested_markets", true);
        queryParams.put("limit", LIMIT);
        queryParams.put("status", "open");

        // Extract cursor from previous response
        if (state.getResponseText() != null && !state.getResponseText().isEmpty()) {
            String cursor = extractCursor(state.getResponseText());
            if (cursor != null && !cursor.isEmpty()) {
                queryParams.put("cursor", cursor);
            }
        }

        return RequestParameters.builder()
                .url(BASE_URL)
                .queryParams(queryParams)
                .build();
    }

    @Override
    protected List<PredictionMarket> parseResponse(String response) {
        return mapper.mapToPredictionMarketList(response);
    }

    @Override
    protected boolean hasMoreData(String response, int itemCount) {
        // If no items were returned, no more data
        if (itemCount == 0) {
            return false;
        }

        // Check if cursor exists in response
        String cursor = extractCursor(response);
        return cursor != null && !cursor.isEmpty();
    }

    @Override
    protected FetcherState initialState() {
        return FetcherState.initial(BASE_URL).toBuilder()
                .responseText("{\"cursor\":\"\",\"events\":[]}")
                .build();
    }

    private String extractCursor(String response) {
        try {
            JsonNode jsonNode = objectMapper.readTree(response);
            JsonNode cursorNode = jsonNode.get("cursor");
            return cursorNode != null ? cursorNode.asText() : null;
        } catch (Exception e) {
            log.error("Failed to extract cursor from response", e);
            return null;
        }
    }
}
