package hzpro.com.tradingdesk.marketsfetcher.service.fetchers;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.marketsfetcher.dto.opinion.OpinionResponseDto;
import hzpro.com.tradingdesk.marketsfetcher.dto.opinion.OpinionResultDto;
import hzpro.com.tradingdesk.marketsfetcher.lib.BaseFetcher;
import hzpro.com.tradingdesk.marketsfetcher.lib.FetcherState;
import hzpro.com.tradingdesk.marketsfetcher.mapper.OpinionMapper;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Fetches the opinion.trade market catalogue. Unlike Kalshi/Polymarket (cursor based),
 * opinion.trade uses page-number pagination and requires an {@code apikey} header on
 * every request.
 */
@Slf4j
@Service
public class OpinionFetcher extends BaseFetcher {

    private static final int LIMIT = 20; // API max page size
    private static final int SUCCESS_CODE = 0;

    private final OpinionMapper mapper;
    private final ObjectMapper objectMapper;
    private final String baseUrl;
    private final String apiKey;

    /** Page used by the most recent {@link #prepareRequest}; consumed by {@link #hasMoreData}. */
    private int currentPage = 1;

    public OpinionFetcher(CloseableHttpClient httpClient,
                          PredictionMarketRepository repository,
                          OpinionMapper mapper,
                          ObjectMapper objectMapper,
                          @Value("${fetcher.opinion.base-url}") String baseUrl,
                          @Value("${fetcher.opinion.api-key}") String apiKey) {
        super(httpClient, repository);
        this.mapper = mapper;
        this.objectMapper = objectMapper;
        this.baseUrl = baseUrl;
        this.apiKey = apiKey;
    }

    @Override
    public void fetchAll() {
        if (apiKey == null || apiKey.isBlank()) {
            log.error("OPINION_API_KEY is blank — skipping Opinion fetch. "
                    + "Set fetcher.opinion.api-key (OPINION_API_KEY) to enable it.");
            return;
        }
        super.fetchAll();
    }

    @Override
    protected RequestParameters prepareRequest(FetcherState state) {
        currentPage = (int) (state.getRequestNumber() + 1L); // 1-based page

        Map<String, Object> queryParams = new HashMap<>();
        queryParams.put("status", "activated");
        queryParams.put("marketType", 2); // 2 = All (Binary + Categorical)
        queryParams.put("limit", LIMIT);
        queryParams.put("page", currentPage);

        return RequestParameters.builder()
                .url(baseUrl + "/market")
                .queryParams(queryParams)
                .headers(Map.of("apikey", apiKey))
                .build();
    }

    @Override
    protected List<PredictionMarket> parseResponse(String response) {
        try {
            OpinionResponseDto envelope = objectMapper.readValue(response, OpinionResponseDto.class);
            if (envelope.getErrno() != SUCCESS_CODE || envelope.getResult() == null) {
                log.error("Opinion API returned errno={} msg='{}' — skipping page",
                        envelope.getErrno(), envelope.getErrmsg());
                return List.of();
            }
            List<?> list = envelope.getResult().getList();
            if (list == null || list.isEmpty()) {
                return List.of();
            }
            String listJson = objectMapper.writeValueAsString(list);
            return mapper.mapToPredictionMarketList(listJson);
        } catch (Exception e) {
            log.error("Failed to parse Opinion response", e);
            return List.of();
        }
    }

    @Override
    protected boolean hasMoreData(String response, int itemCount) {
        if (itemCount == 0) {
            return false;
        }
        long total = extractTotal(response);
        if (total <= 0) {
            // Unknown total: fall back to "stop once a page comes back empty".
            return true;
        }
        return (long) currentPage * LIMIT < total;
    }

    @Override
    protected FetcherState initialState() {
        return FetcherState.initial(baseUrl + "/market").toBuilder()
                .responseText("")
                .build();
    }

    private long extractTotal(String response) {
        try {
            OpinionResponseDto envelope = objectMapper.readValue(response, OpinionResponseDto.class);
            OpinionResultDto result = envelope.getResult();
            return result != null ? result.getTotal() : 0L;
        } catch (Exception e) {
            log.error("Failed to extract total from Opinion response", e);
            return 0L;
        }
    }
}
