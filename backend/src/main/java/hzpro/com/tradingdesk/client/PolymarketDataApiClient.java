package hzpro.com.tradingdesk.client;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.client.dto.PolymarketClosedPositionDto;
import hzpro.com.tradingdesk.client.dto.PolymarketPortfolioValueDto;
import hzpro.com.tradingdesk.client.dto.PolymarketPositionDto;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.net.URIBuilder;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.net.URI;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * REST client for the public Polymarket Data API.
 *
 * <p>No authentication is required — all endpoints accept a wallet address
 * as a query parameter and return public portfolio data.
 *
 * <p>Follows the same {@code null}-on-failure pattern as the other venue clients.
 */
@Slf4j
@Component
public class PolymarketDataApiClient {

    private static final String DEFAULT_BASE_URL = "https://data-api.polymarket.com";
    private static final int CLOSED_POSITIONS_LIMIT = 50;
    private static final int CLOSED_POSITIONS_MAX_PAGES = 10;
    private static final int POSITIONS_LIMIT = 500;
    private static final int POSITIONS_MAX_OFFSET = 10_000;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ArbitrageConfig config;

    public PolymarketDataApiClient(CloseableHttpClient httpClient,
                                   ObjectMapper objectMapper,
                                   ArbitrageConfig config) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    private String baseUrl() {
        String url = config.getPolymarketDataApiUrl();
        return url != null ? url : DEFAULT_BASE_URL;
    }

    private String walletAddress() {
        return config.getPolymarketDepositWalletAddress();
    }

    // ---- public methods ----

    /**
     * Fetches the total value of open positions for the configured wallet.
     *
     * @return the portfolio value in USDC, or {@code null} on failure
     */
    public BigDecimal getPortfolioValue() {
        String addr = walletAddress();
        if (addr == null || addr.isEmpty()) {
            log.warn("Polymarket deposit wallet address not configured; cannot fetch portfolio value");
            return null;
        }
        try {
            URI uri = new URIBuilder(baseUrl() + "/value")
                    .addParameter("user", addr)
                    .build();
            HttpGet get = new HttpGet(uri);

            return httpClient.execute(get, response -> {
                if (response.getCode() != 200) {
                    log.warn("Data API /value returned {}", response.getCode());
                    return null;
                }
                JsonNode root = objectMapper.readTree(response.getEntity().getContent());
                if (root.isArray() && !root.isEmpty()) {
                    return objectMapper.treeToValue(root.get(0), PolymarketPortfolioValueDto.class).value();
                }
                return BigDecimal.ZERO;
            });
        } catch (Exception e) {
            log.warn("Failed to fetch Polymarket portfolio value: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Fetches a complete current-position snapshot for the configured wallet.
     *
     * @return all pages, an empty list for a successful empty account, or {@code null} on failure
     */
    public List<PolymarketPositionDto> getCurrentPositions() {
        String addr = walletAddress();
        if (addr == null || addr.isBlank()) {
            log.warn("Polymarket deposit wallet address not configured; cannot fetch positions");
            return null;
        }

        List<PolymarketPositionDto> all = new ArrayList<>();
        try {
            for (int offset = 0; offset <= POSITIONS_MAX_OFFSET; offset += POSITIONS_LIMIT) {
                URI uri = new URIBuilder(baseUrl() + "/positions")
                        .addParameter("user", addr)
                        .addParameter("limit", String.valueOf(POSITIONS_LIMIT))
                        .addParameter("offset", String.valueOf(offset))
                        .addParameter("sizeThreshold", "0")
                        .addParameter("includeArchived", "true")
                        .build();

                List<PolymarketPositionDto> pageItems = httpClient.execute(
                        new HttpGet(uri),
                        response -> {
                            if (response.getCode() != 200) {
                                log.warn("Data API /positions returned {}", response.getCode());
                                return null;
                            }
                            JsonNode root = objectMapper.readTree(response.getEntity().getContent());
                            if (!root.isArray()) {
                                log.warn("Data API /positions returned a non-array response");
                                return null;
                            }
                            List<PolymarketPositionDto> items = new ArrayList<>();
                            for (JsonNode node : root) {
                                items.add(objectMapper.treeToValue(node, PolymarketPositionDto.class));
                            }
                            return items;
                        });

                if (pageItems == null) {
                    return null;
                }
                all.addAll(pageItems);
                if (pageItems.size() < POSITIONS_LIMIT) {
                    return all;
                }
                if (offset == POSITIONS_MAX_OFFSET) {
                    log.warn("Polymarket positions exceed the supported API offset ceiling");
                    return null;
                }
            }
        } catch (Exception exception) {
            log.warn("Failed to fetch Polymarket positions: {}", exception.getMessage());
            return null;
        }
        return null;
    }

    /**
     * Fetches closed positions for the configured wallet, paginating up to
     * {@value #CLOSED_POSITIONS_MAX_PAGES} pages.
     *
     * @return list of closed positions, or an empty list on failure
     */
    public List<PolymarketClosedPositionDto> getClosedPositions() {
        String addr = walletAddress();
        if (addr == null || addr.isEmpty()) {
            log.warn("Polymarket deposit wallet address not configured; cannot fetch closed positions");
            return Collections.emptyList();
        }
        List<PolymarketClosedPositionDto> all = new ArrayList<>();
        try {
            for (int page = 0; page < CLOSED_POSITIONS_MAX_PAGES; page++) {
                URI uri = new URIBuilder(baseUrl() + "/closed-positions")
                        .addParameter("user", addr)
                        .addParameter("limit", String.valueOf(CLOSED_POSITIONS_LIMIT))
                        .addParameter("offset", String.valueOf(page * CLOSED_POSITIONS_LIMIT))
                        .build();
                HttpGet get = new HttpGet(uri);

                List<PolymarketClosedPositionDto> pageItems = httpClient.execute(get, response -> {
                    if (response.getCode() != 200) {
                        log.warn("Data API /closed-positions returned {}", response.getCode());
                        return Collections.<PolymarketClosedPositionDto>emptyList();
                    }
                    JsonNode root = objectMapper.readTree(response.getEntity().getContent());
                    if (root.isArray()) {
                        List<PolymarketClosedPositionDto> items = new ArrayList<>();
                        for (JsonNode node : root) {
                            items.add(objectMapper.treeToValue(node, PolymarketClosedPositionDto.class));
                        }
                        return items;
                    }
                    return Collections.<PolymarketClosedPositionDto>emptyList();
                });

                if (pageItems == null || pageItems.isEmpty()) {
                    break; // no more results
                }
                all.addAll(pageItems);
                if (pageItems.size() < CLOSED_POSITIONS_LIMIT) {
                    break; // last page
                }
            }
            return all;
        } catch (Exception e) {
            log.warn("Failed to fetch Polymarket closed positions: {}", e.getMessage());
            return Collections.emptyList();
        }
    }
}
