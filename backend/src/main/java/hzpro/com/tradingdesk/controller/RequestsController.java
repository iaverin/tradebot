package hzpro.com.tradingdesk.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.KalshiAuthorizedClient;
import hzpro.com.tradingdesk.client.PolymarketAuthorizedClient;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;
import java.util.Map;

@RestController
@RequestMapping("/api/requests")
@Slf4j
public class RequestsController {

    private static final String POLYMARKET_DATA_API_URL = "https://data-api.polymarket.com";

    private final KalshiAuthorizedClient kalshiAuthorizedClient;
    private final PolymarketAuthorizedClient polymarketAuthorizedClient;
    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ArbitrageConfig arbitrageConfig;

    public RequestsController(KalshiAuthorizedClient kalshiAuthorizedClient,
                               PolymarketAuthorizedClient polymarketAuthorizedClient,
                               CloseableHttpClient httpClient,
                               ObjectMapper objectMapper,
                               ArbitrageConfig arbitrageConfig) {
        this.kalshiAuthorizedClient = kalshiAuthorizedClient;
        this.polymarketAuthorizedClient = polymarketAuthorizedClient;
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.arbitrageConfig = arbitrageConfig;
    }

    @GetMapping("/config")
    public ResponseEntity<Map<String, String>> getConfig() {
        return ResponseEntity.ok(Map.of(
                "polymarketWalletAddress", arbitrageConfig.getPolymarketDepositWalletAddress() != null
                        ? arbitrageConfig.getPolymarketDepositWalletAddress() : ""
        ));
    }

    @PostMapping("/proxy")
    public ResponseEntity<ProxyResponse> proxyRequest(@RequestBody ProxyRequest request) {
        log.info("Proxying {} request to {}: {}", request.method(), request.venue(), request.path());

        String venue = request.venue().toUpperCase();
        HttpMethod httpMethod;
        try {
            httpMethod = HttpMethod.valueOf(request.method().toUpperCase());
        } catch (IllegalArgumentException e) {
            return ResponseEntity.badRequest().body(
                    new ProxyResponse(400, "Unsupported HTTP method: " + request.method()));
        }

        try {
            JsonNode responseBody = switch (venue) {
                case "KALSHI" -> executeWithKalshi(httpMethod, request);
                case "POLYMARKET" -> executeWithPolymarket(httpMethod, request);
                case "POLYMARKET_DATA" -> executeDataApi(request.path());
                default -> null;
            };

            if (responseBody == null) {
                if (!venue.equals("KALSHI") && !venue.equals("POLYMARKET") && !venue.equals("POLYMARKET_DATA")) {
                    return ResponseEntity.badRequest().body(
                            new ProxyResponse(400, "Unsupported venue: " + request.venue()));
                }
                return ResponseEntity.status(502).body(
                        new ProxyResponse(502, "Upstream request returned null (network/auth error)"));
            }

            return ResponseEntity.ok(new ProxyResponse(200, responseBody));
        } catch (Exception e) {
            log.error("Proxy request failed", e);
            return ResponseEntity.status(500).body(
                    new ProxyResponse(500, "Internal error: " + e.getMessage()));
        }
    }

    private JsonNode executeDataApi(String path) {
        try {
            URI uri = URI.create(POLYMARKET_DATA_API_URL + path);
            HttpGet get = new HttpGet(uri);
            return httpClient.execute(get, response -> {
                int status = response.getCode();
                if (status >= 200 && status < 300) {
                    return objectMapper.readTree(response.getEntity().getContent());
                }
                log.warn("Polymarket Data API returned status {}", status);
                return null;
            });
        } catch (Exception e) {
            log.warn("Polymarket Data API request failed: {}", e.getMessage());
            return null;
        }
    }

    private JsonNode executeWithKalshi(HttpMethod method, ProxyRequest request) {
        if (request.body() != null && method != HttpMethod.GET) {
            return kalshiAuthorizedClient.execute(method, request.path(), request.body(), response -> {
                int status = response.getCode();
                if (status >= 200 && status < 300) {
                    return objectMapper.readTree(response.getEntity().getContent());
                }
                log.warn("Kalshi upstream returned status {}", status);
                return null;
            });
        }
        return kalshiAuthorizedClient.execute(method, request.path(), response -> {
            int status = response.getCode();
            if (status >= 200 && status < 300) {
                return objectMapper.readTree(response.getEntity().getContent());
            }
            log.warn("Kalshi upstream returned status {}", status);
            return null;
        });
    }

    private JsonNode executeWithPolymarket(HttpMethod method, ProxyRequest request) {
        if (request.body() != null && method != HttpMethod.GET) {
            return polymarketAuthorizedClient.execute(method, request.path(), request.body(), response -> {
                int status = response.getCode();
                if (status >= 200 && status < 300) {
                    return objectMapper.readTree(response.getEntity().getContent());
                }
                log.warn("Polymarket upstream returned status {}", status);
                return null;
            });
        }
        return polymarketAuthorizedClient.execute(method, request.path(), response -> {
            int status = response.getCode();
            if (status >= 200 && status < 300) {
                return objectMapper.readTree(response.getEntity().getContent());
            }
            log.warn("Polymarket upstream returned status {}", status);
            return null;
        });
    }

    public record ProxyRequest(String venue, String method, String path, JsonNode body) {
        public ProxyRequest {
            if (method == null || method.isBlank()) {
                method = "GET";
            }
        }
    }

    public record ProxyResponse(int statusCode, Object body) {}
}
