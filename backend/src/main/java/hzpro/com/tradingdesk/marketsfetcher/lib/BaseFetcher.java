package hzpro.com.tradingdesk.marketsfetcher.lib;

import java.net.URI;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Map;

import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.entity.EntityUtils;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.transaction.annotation.Transactional;

import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@RequiredArgsConstructor
public abstract class BaseFetcher {

    protected final CloseableHttpClient httpClient;
    protected final PredictionMarketRepository repository;

    @Getter
    @Setter
    private FetcherState lastState;

    @Setter
    private boolean isPaused;

    private boolean forceStop = false;

    @Retryable(maxAttempts = 3, backoff = @Backoff(delay = 4000, multiplier = 1.0, maxDelay = 10000), retryFor = {
            Exception.class })
    public FetcherState fetchAndPersist(FetcherState state) {
        try {
            log.info("Starting fetch from {}", state.getUrl());
            ZonedDateTime requestStart = ZonedDateTime.now();

            RequestParameters requestParams = prepareRequest(state);

            String response = executeRequest(requestParams);

            log.info("Got response length {}, first symbols {}", response.length(),
                    response.length() > 100 ? response.substring(0, 100) : response);
            ZonedDateTime requestEnd = ZonedDateTime.now();

            List<PredictionMarket> markets = parseResponse(response);
            if (!markets.isEmpty()) {
                persistData(markets);
            }

            boolean hasMore = hasMoreData(response, markets.size());

            log.info("Fetch completed. Retrieved {} markets. Has more data: {}", markets.size(), hasMore);

            return state.toBuilder()
                    .requestStartDatetime(requestStart)
                    .requestEndDatetime(requestEnd)
                    .url(requestParams.getUrl())
                    .requestQueryParameters(requestParams.getQueryParams())
                    .responseText(response)
                    .fetchStatus(FetcherState.FetchStatus.SUCCESS)
                    .hasMoreData(hasMore)
                    .requestNumber(state.getRequestNumber() + 1L)
                    .marketsFetched(state.getMarketsFetched() + markets.size())
                    .build();

        } catch (Exception e) {
            log.error("Error during fetch", e);
            return state.toBuilder()
                    .fetchStatus(FetcherState.FetchStatus.ERROR)
                    .errorMessage(e.getMessage())
                    .hasMoreData(false)
                    .build();
        }
    }

    protected String executeRequest(RequestParameters params) throws Exception {
        URIBuilder uriBuilder = new URIBuilder(params.getUrl());
        params.getQueryParams().forEach((key, value) -> {
            if (value != null) {
                uriBuilder.addParameter(key, value.toString());
            }
        });
        URI uri = uriBuilder.build();

        HttpGet httpGet = new HttpGet(uri);

        if (params.getHeaders() != null) {
            params.getHeaders().forEach(httpGet::addHeader);
        }

        return httpClient.execute(httpGet, response -> {
            int status = response.getCode();
            if (status >= 200 && status < 300) {
                return EntityUtils.toString(response.getEntity());
            } else {
                throw new RuntimeException("HTTP request failed with status: " + status);
            }
        });
    }

    @Transactional
    protected void persistData(List<PredictionMarket> markets) {
        log.info("Persisting {} markets to database", markets.size());
        repository.saveAll(markets);
    }

    protected abstract FetcherState initialState();

    protected abstract RequestParameters prepareRequest(FetcherState state);

    protected abstract List<PredictionMarket> parseResponse(String response);

    protected abstract boolean hasMoreData(String response, int itemCount);

    public void fetchAll() {
        isPaused = false;
        forceStop = false;
        FetcherState state = initialState();
        setLastState(state.toBuilder().build());

        int iteration = 0;
        int consecutiveErrors = 0;

        while ((state.getFetchStatus() == FetcherState.FetchStatus.ERROR || state.isHasMoreData())
                && !forceStop) {
            if (isPaused) {
                continue;
            }

            iteration++;
            log.info("Fetch iteration {}", iteration);
            state = fetchAndPersist(state);
            setLastState(state.toBuilder().build());

            if (state.getFetchStatus() == FetcherState.FetchStatus.ERROR) {
                log.error("Fetch failed with error: {}", state.getErrorMessage());
                consecutiveErrors++;
                if (consecutiveErrors >= 3) {
                    log.error("Too many consecutive errors, stopping fetch");
                    break;
                }
            } else {
                consecutiveErrors = 0;
            }
        }

        log.info("Fetch cycle completed after {} iterations", iteration);
    }

    public void setForceStop() {
        log.info("Forced to stop");
        forceStop = true;
    }

    public boolean getForceStop() {
        return forceStop;
    }

    @lombok.Value
    @lombok.Builder
    protected static class RequestParameters {
        String url;
        Map<String, Object> queryParams;
        Map<String, String> headers;
    }
}