package hzpro.com.tradingdesk.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import jakarta.annotation.PostConstruct;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpPost;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.MGF1ParameterSpec;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.PSSParameterSpec;
import java.time.Instant;
import java.util.Base64;

/**
 * Generic HTTP client for authorized Kalshi API endpoints.
 *
 * <p>Encapsulates the Kalshi authentication scheme:
 * <ul>
 *   <li>RSA private key loading (Base64-encoded PKCS#1 DER, converted to PKCS#8)</li>
 *   <li>RSASSA-PSS signature over {@code timestamp + method + path}</li>
 *   <li>Header attachment ({@code KALSHI-ACCESS-KEY}, {@code KALSHI-ACCESS-SIGNATURE},
 *       {@code KALSHI-ACCESS-TIMESTAMP})</li>
 * </ul>
 *
 * <p>Auth headers are optional — if {@code arbitrage.kalshi-api-key} or
 * {@code arbitrage.kalshi-private-key} is not configured, {@link #addAuthHeaders}
 * silently skips. This accommodates endpoints that are technically public but
 * support optional authentication.
 *
 * <p>Also provides a convenience {@link #execute} method with null-on-failure
 * semantics matching the existing orderbook client pattern.
 */
@Slf4j
@Component
public class KalshiAuthorizedClient {

    private final CloseableHttpClient httpClient;
    private final ArbitrageConfig arbitrageConfig;
    private final ObjectMapper objectMapper;

    private PrivateKey privateKey;

    public KalshiAuthorizedClient(CloseableHttpClient httpClient,
                                  ArbitrageConfig arbitrageConfig,
                                  ObjectMapper objectMapper) {
        this.httpClient = httpClient;
        this.arbitrageConfig = arbitrageConfig;
        this.objectMapper = objectMapper;
    }

    @PostConstruct
    public void init() {
        this.privateKey = loadPrivateKey(arbitrageConfig.getKalshiPrivateKey());
    }

    /**
     * Returns the base URL for Kalshi API calls (from config, with fallback).
     */
    public String getBaseUrl() {
        return arbitrageConfig.getKalshiApiUrl() != null
                ? arbitrageConfig.getKalshiApiUrl()
                : "https://api.elections.kalshi.com/trade-api/v2";
    }

    /**
     * Attaches {@code KALSHI-ACCESS-KEY}, {@code KALSHI-ACCESS-SIGNATURE}, and
     * {@code KALSHI-ACCESS-TIMESTAMP} headers to the given request. Skips silently
     * if the API key or private key is not configured (the Kalshi orderbook endpoint
     * is public, so auth is optional).
     *
     * @param request the HTTP request to decorate
     * @param method  HTTP method
     * @param path    API path for the signature (e.g. {@code "/trade-api/v2/markets/KXTEST/orderbook"})
     */
    public void addAuthHeaders(HttpUriRequest request, HttpMethod method, String path) {
        String apiKey = arbitrageConfig.getKalshiApiKey();
        if (apiKey == null || apiKey.isEmpty() || privateKey == null) {
            return;
        }

        String timestamp = String.valueOf(Instant.now().toEpochMilli());
        String signature = createSignature(timestamp, method, path);
        if (signature.isEmpty()) {
            return;
        }

        request.setHeader("KALSHI-ACCESS-KEY", apiKey);
        request.setHeader("KALSHI-ACCESS-SIGNATURE", signature);
        request.setHeader("KALSHI-ACCESS-TIMESTAMP", timestamp);
    }

    /**
     * Attaches auth headers and executes the request in one call, returning
     * {@code null} on any exception. Use this when the caller already has an
     * {@link HttpUriRequest} (e.g. a POST with a body). For simple GET/DELETE
     * calls prefer {@link #execute(String, String, HttpClientResponseHandler)}.
     *
     * @param request the HTTP request to decorate and execute
     * @param method  HTTP method (e.g. {@code "GET"}, {@code "POST"})
     * @param path    API path for the signature (e.g. {@code "/trade-api/v2/markets/KXTEST/orderbook"})
     * @param handler the response handler
     * @param <T>     the response type
     * @return the response, or {@code null} on failure
     */
    @Nullable
    public <T> T execute(HttpUriRequest request, HttpMethod method, String path,
                         HttpClientResponseHandler<T> handler) {
        addAuthHeaders(request, method, path);
        try {
            return httpClient.execute(request, handler);
        } catch (Exception e) {
            log.warn("Kalshi API request failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds the request from {@code method} and {@code path}, attaches auth
     * headers, and executes — all in one call. Returns {@code null} on any
     * exception.
     *
     * <p>This is the simplest API for callers that don't need to customise the
     * request (no extra headers, no body). {@code path} is the API endpoint
     * relative to {@link #getBaseUrl()} (e.g. {@code "/markets/KXTEST/orderbook"}).
     *
     * @param method  HTTP method ({@code "GET"}, {@code "POST"}, or {@code "DELETE"})
     * @param path    API endpoint relative to the base URL
     * @param handler the response handler
     * @param <T>     the response type
     * @return the response, or {@code null} on failure
     */
    @Nullable
    public <T> T execute(HttpMethod method, String path,
                         HttpClientResponseHandler<T> handler) {
        try {
            URI uri = URI.create(getBaseUrl() + path);
            HttpUriRequest request = method.createRequest(uri);
            String sigPath = uri.getPath();
            return execute(request, method, sigPath, handler);
        } catch (Exception e) {
            log.warn("Kalshi API request failed: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Builds the request from {@code method} and {@code path}, serialises
     * {@code body} as JSON, attaches auth headers, and executes — all in one
     * call. Returns {@code null} on any exception.
     *
     * @param method  HTTP method
     * @param path    API endpoint relative to the base URL
     * @param body    object to serialise as the JSON request body
     * @param handler the response handler
     * @param <T>     the response type
     * @return the response, or {@code null} on failure
     */
    @Nullable
    public <T> T execute(HttpMethod method, String path, Object body,
                         HttpClientResponseHandler<T> handler) {
        try {
            URI uri = URI.create(getBaseUrl() + path);
            HttpUriRequest request = method.createRequest(uri);
            if (request instanceof HttpPost post) {
                post.setHeader("Content-Type", "application/json");
                post.setEntity(new StringEntity(objectMapper.writeValueAsString(body),
                        StandardCharsets.UTF_8));
            }
            String sigPath = uri.getPath();
            return execute(request, method, sigPath, handler);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise request body: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Kalshi API request failed: {}", e.getMessage());
            return null;
        }
    }

    // --- private helpers (extracted from KalshiRestOrderBookClient) ---

    private String createSignature(String timestamp, HttpMethod method, String path) {
        if (privateKey == null) {
            return "";
        }
        try {
            String msg = timestamp + method.name() + path;
            Signature signer = Signature.getInstance("RSASSA-PSS");
            signer.setParameter(new PSSParameterSpec("SHA-256", "MGF1",
                    MGF1ParameterSpec.SHA256, 32, 1));
            signer.initSign(privateKey);
            signer.update(msg.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            log.error("Failed to sign Kalshi request", e);
            return "";
        }
    }

    private PrivateKey loadPrivateKey(String base64Key) {
        if (base64Key == null || base64Key.isEmpty()) {
            return null;
        }
        try {
            byte[] pkcs1Bytes = Base64.getDecoder().decode(base64Key);
            byte[] pkcs8Bytes = convertPkcs1ToPkcs8(pkcs1Bytes);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
        } catch (Exception e) {
            log.error("Failed to load Kalshi private key for REST client", e);
            return null;
        }
    }

    private byte[] convertPkcs1ToPkcs8(byte[] pkcs1Bytes) {
        byte[] header = new byte[]{
                0x30, (byte) 0x82, 0x00, 0x00, 0x02, 0x01, 0x00, 0x30, 0x0D,
                0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01,
                0x05, 0x00, 0x04, (byte) 0x82, 0x00, 0x00};
        int len = pkcs1Bytes.length;
        int total = header.length + len;
        header[2] = (byte) ((total - 4) >> 8);
        header[3] = (byte) (total - 4);
        header[header.length - 2] = (byte) (len >> 8);
        header[header.length - 1] = (byte) len;
        byte[] pkcs8 = new byte[total];
        System.arraycopy(header, 0, pkcs8, 0, header.length);
        System.arraycopy(pkcs1Bytes, 0, pkcs8, header.length, len);
        return pkcs8;
    }
}
