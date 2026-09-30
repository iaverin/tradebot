package hzpro.com.tradingdesk.arbitrage.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.model.PriceSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;


@Slf4j
@Component
public class KalshiPriceRestClient {

    private static final String BASE_URL = "https://api.elections.kalshi.com/trade-api/v2/markets";

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public KalshiPriceRestClient(CloseableHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public Map<String, PriceSnapshot> fetchPrices(List<String> tickers, long timeoutMs) {
        Map<String, PriceSnapshot> results = new ConcurrentHashMap<>();
        AtomicInteger completed = new AtomicInteger(0);
        Semaphore semaphore = new Semaphore(5);
        ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor();

        List<CompletableFuture<Void>> futures = tickers.stream()
                .map(ticker -> CompletableFuture.runAsync(() -> {
                    try {
                        semaphore.acquire();
                        try {
                            PriceSnapshot price = fetchSinglePrice(ticker);
                            if (price != null) {
                                results.put(ticker, price);
                            }
                        } finally {
                            semaphore.release();
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } catch (Exception e) {
                        log.warn("Failed to fetch Kalshi price for {}: {}", ticker, e.getMessage());
                    } finally {
                        completed.incrementAndGet();
                    }
                }, executor))
                .toList();

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(ex -> {
                        log.warn("Kalshi prefetch timed out: {}", ex.getMessage());
                        return null;
                    })
                    .join();
        } catch (Exception e) {
            log.warn("Kalshi prefetch interrupted: {}", e.getMessage());
        } finally {
            executor.shutdownNow();
        }

        log.info("Kalshi prefetch completed: {}/{} prices fetched", results.size(), tickers.size());
        return results;
    }

    private PriceSnapshot fetchSinglePrice(String ticker) {
        try {
            URI uri = new URIBuilder(BASE_URL + "/" + ticker).build();
            HttpGet httpGet = new HttpGet(uri);

            return httpClient.execute(httpGet, response -> {
                int status = response.getCode();
                if (status < 200 || status >= 300) {
                    log.warn("Kalshi REST failed for {}: status {}", ticker, status);
                    return null;
                }

                String body = new String(response.getEntity().getContent().readAllBytes());
                JsonNode root = objectMapper.readTree(body);
                JsonNode market = root.path("market");

                BigDecimal yesAsk = parseBigDecimal(market.path("yes_ask_dollars").asText());
                BigDecimal noAsk = parseBigDecimal(market.path("no_ask_dollars").asText());

                if (yesAsk == null && noAsk == null) {
                    return null;
                }

                if (yesAsk == null && noAsk != null) {
                    yesAsk = BigDecimal.ONE.subtract(noAsk);
                } else if (noAsk == null && yesAsk != null) {
                    noAsk = BigDecimal.ONE.subtract(yesAsk);
                }

                return PriceSnapshot.builder()
                        .platform("KALSHI")
                        .yesAsk(yesAsk)
                        .noAsk(noAsk)
                        .updatedAt(Instant.now())
                        .build();
            });
        } catch (Exception e) {
            log.warn("Error fetching Kalshi price for {}: {}", ticker, e.getMessage());
            return null;
        }
    }

    private BigDecimal parseBigDecimal(String value) {
        if (value == null || value.isEmpty()) return null;
        try {
            return new BigDecimal(value);
        } catch (NumberFormatException e) {
            return null;
        }
    }
}