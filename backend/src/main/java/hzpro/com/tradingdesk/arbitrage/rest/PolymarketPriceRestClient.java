package hzpro.com.tradingdesk.arbitrage.rest;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.arbitrage.dto.PolymarketOrderBookDto;
import hzpro.com.tradingdesk.arbitrage.model.PriceSnapshot;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.URI;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

@Slf4j
@Component
public class PolymarketPriceRestClient {

    private static final String CLOB_BOOK_URL = "https://clob.polymarket.com/book";
    private static final int MAX_CONCURRENT_REQUESTS = 20;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public PolymarketPriceRestClient(CloseableHttpClient httpClient, ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
    }

    public Map<String, PriceSnapshot> fetchPrices(Map<String, String> tokenIds, long timeoutMs) {
        Map<String, PriceSnapshot> results = new ConcurrentHashMap<>();
        AtomicInteger successCount = new AtomicInteger(0);
        Semaphore semaphore = new Semaphore(MAX_CONCURRENT_REQUESTS);

        List<CompletableFuture<Void>> futures;
        try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
            futures = tokenIds.entrySet().stream()
                    .map(entry -> CompletableFuture.runAsync(() -> {
                        try {
                            String tokenId = entry.getKey();
                            String tokenType = entry.getValue();
                            semaphore.acquire();
                            try {
                                BigDecimal bestAsk = fetchBestAsk(tokenId);
                                if (bestAsk != null) {
                                    PriceSnapshot snapshot = new PriceSnapshot();
                                    snapshot.setPlatform("POLYMARKET");
                                    snapshot.setUpdatedAt(Instant.now());
                                    if ("YES".equalsIgnoreCase(tokenType)) {
                                        snapshot.setYesAsk(bestAsk);
                                    } else {
                                        snapshot.setNoAsk(bestAsk);
                                    }
                                    results.put(tokenId, snapshot);
                                    successCount.incrementAndGet();
                                }
                            } finally {
                                semaphore.release();
                            }
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } catch (Exception e) {
                            log.debug("Failed to fetch PM price for token {}: {}", entry.getKey(), e.getMessage());
                        }
                    }, executor))
                    .toList();
        }

        try {
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                    .orTimeout(timeoutMs, TimeUnit.MILLISECONDS)
                    .exceptionally(_ -> {
                        log.debug("Polymarket prefetch timed out after {} successful fetches", successCount.get());
                        return null;
                    })
                    .join();
        } catch (Exception e) {
            log.debug("Polymarket prefetch interrupted: {}", e.getMessage());
        }

        log.info("Polymarket prefetch completed: {}/{} prices fetched", results.size(), tokenIds.size());
        return results;
    }

    public PolymarketOrderBookDto fetchOrderBook(String tokenId) {
        try {
            URI uri = new URIBuilder(CLOB_BOOK_URL)
                    .addParameter("token_id", tokenId)
                    .build();
            HttpGet httpGet = new HttpGet(uri);

            return httpClient.execute(httpGet, response -> {
                int status = response.getCode();
                if (status < 200 || status >= 300) {
                    log.debug("CLOB book API returned {} for token {}", status, tokenId);
                    return null;
                }
                return objectMapper.readValue(response.getEntity().getContent(), PolymarketOrderBookDto.class);
            });
        } catch (Exception e) {
            log.info("CLOB book fetch failed for token {}: {}", tokenId, e.getMessage());
            return null;
        }
    }

    private BigDecimal fetchBestAsk(String tokenId) {
        try {
            URI uri = new URIBuilder(CLOB_BOOK_URL)
                    .addParameter("token_id", tokenId)
                    .build();
            HttpGet httpGet = new HttpGet(uri);

            return httpClient.execute(httpGet, response -> {
                int status = response.getCode();
                if (status < 200 || status >= 300) {
                    log.debug("CLOB book API returned {} for token {}", status, tokenId);
                    return null;
                }

                String body = new String(response.getEntity().getContent().readAllBytes());
                JsonNode root = objectMapper.readTree(body);

                JsonNode midpoint = root.path("midpoint");
                String midStr = midpoint.asText();
                if (midStr != null && !midStr.isEmpty()) {
                    BigDecimal midPrice = new BigDecimal(midStr).setScale(4, RoundingMode.HALF_UP);
                    log.debug("Using midpoint for token {}: {}", tokenId, midPrice);
                    return midPrice;
                }

                JsonNode asks = root.path("asks");
                if (asks.isArray() && !asks.isEmpty()) {
                    JsonNode bestAskNode = asks.get(asks.size() - 1);
                    String priceStr = bestAskNode.path("price").asText();
                    if (priceStr != null && !priceStr.isEmpty()) {
                        BigDecimal price = new BigDecimal(priceStr).setScale(4, RoundingMode.HALF_UP);
                        log.debug("Fallback to best ask (last element) for token {}: {}", tokenId, price);
                        return price;
                    }
                }

                log.debug("No price found for token {}", tokenId);
                return null;
            });
        } catch (Exception e) {
            log.debug("CLOB book fetch failed for token {}: {}", tokenId, e.getMessage());
            return null;
        }
    }
}