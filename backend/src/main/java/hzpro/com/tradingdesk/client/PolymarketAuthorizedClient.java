package hzpro.com.tradingdesk.client;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.service.PolymarketAuthL1Service;
import hzpro.com.tradingdesk.arbitrage.service.PolymarketAuthL1Service.ApiCredentials;
import hzpro.com.tradingdesk.arbitrage.service.PolymarketEip712Signer;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpUriRequest;
import org.apache.hc.client5.http.classic.methods.HttpUriRequestBase;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import org.web3j.crypto.Credentials;
import org.web3j.crypto.Keys;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.math.BigInteger;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;

/**
 * Generic HTTP client for authorized Polymarket CLOB API endpoints.
 *
 * <p>Encapsulates the full Polymarket request lifecycle:
 * <ul>
 *   <li>Lazy L1 credential derivation via {@link PolymarketAuthL1Service}</li>
 *   <li>EOA address derivation from the configured private key</li>
 *   <li>L2 HMAC-SHA256 auth headers</li>
 *   <li>EIP-712 order signing via {@link PolymarketEip712Signer}</li>
 *   <li>Exchange metadata queries (order version, neg-risk)</li>
 *   <li>HTTP execution with null-on-failure semantics</li>
 * </ul>
 */
@Slf4j
@Component
public class PolymarketAuthorizedClient {

    private static final int DEFAULT_ORDER_VERSION = 2;

    private final CloseableHttpClient httpClient;
    private final ObjectMapper objectMapper;
    private final ArbitrageConfig config;
    private final PolymarketAuthL1Service authL1Service;
    private final PolymarketEip712Signer eip712Signer;

    private volatile ApiCredentials apiCredentials;
    private volatile String eoaAddress;
    private volatile Integer orderVersion;

    public PolymarketAuthorizedClient(CloseableHttpClient httpClient,
                                      ObjectMapper objectMapper,
                                      ArbitrageConfig config,
                                      PolymarketAuthL1Service authL1Service,
                                      PolymarketEip712Signer eip712Signer) {
        this.httpClient = httpClient;
        this.objectMapper = objectMapper;
        this.config = config;
        this.authL1Service = authL1Service;
        this.eip712Signer = eip712Signer;
    }

    // ---- public API ----

    /** Base URL for Polymarket CLOB API calls. */
    public String getBaseUrl() {
        return config.getPolymarketClobUrl();
    }

    /** Signature type used in query params. */
    public int getSignatureType() {
        return config.getPolymarketSignatureType();
    }

    /** L1-derived API key, available after the first authorised request. */
    public String getApiKey() {
        return credentials().apiKey();
    }

    /** Returns the checksummed EOA address derived from the configured private key. */
    public String eoaAddress() {
        String addr = eoaAddress;
        if (addr == null) {
            addr = Keys.toChecksumAddress(
                    Credentials.create(config.getPolymarketPrivateKey()).getAddress());
            eoaAddress = addr;
        }
        return addr;
    }

    // ---- EIP-712 order signing ----

    /**
     * Signs a Polymarket CLOB order using EIP-712 typed data.
     *
     * @return the hex-encoded signature
     */
    public String signOrder(String tokenId, BigInteger makerAmount, BigInteger takerAmount,
                             int side, String maker, String signer,
                             int signatureType, String verifyingContract, String domainVersion,
                             BigInteger salt, long timestampMillis) {
        return eip712Signer.signOrder(
                config.getPolymarketPrivateKey(),
                config.getPolymarketChainId(),
                verifyingContract, domainVersion,
                salt, maker, signer, tokenId,
                makerAmount, takerAmount, side, signatureType,
                timestampMillis,
                "0x0000000000000000000000000000000000000000000000000000000000000000",
                "0x0000000000000000000000000000000000000000000000000000000000000000",
                config.isPolymarketSessionSigner()
        );
    }

    // ---- exchange metadata (cached) ----

    /** Order version the CLOB currently expects; cached. Defaults to {@code 2}. */
    public int orderVersion() {
        Integer version = orderVersion;
        if (version == null) {
            version = execute(HttpMethod.GET, "/version", response -> {
                if (response.getCode() == 200) {
                    JsonNode json = objectMapper.readTree(response.getEntity().getContent());
                    return json.path("version").asInt(DEFAULT_ORDER_VERSION);
                }
                return DEFAULT_ORDER_VERSION;
            });
            if (version == null) {
                version = DEFAULT_ORDER_VERSION;
            }
            orderVersion = version;
        }
        return version;
    }

    /** Queries whether the given token ID belongs to a neg-risk market. */
    public boolean isNegRisk(String tokenId) {
        Boolean result = execute(HttpMethod.GET, "/neg-risk?token_id=" + tokenId, response -> {
            if (response.getCode() == 200) {
                JsonNode json = objectMapper.readTree(response.getEntity().getContent());
                return json.path("neg_risk").asBoolean(false);
            }
            return false;
        });
        return result != null && result;
    }

    // ---- auth header attachment ----

    /**
     * Attaches L2 HMAC auth headers ({@code POLY_ADDRESS}, {@code POLY_API_KEY},
     * {@code POLY_PASSPHRASE}, {@code POLY_TIMESTAMP}, {@code POLY_SIGNATURE}).
     */
    public void addAuthHeaders(HttpUriRequest request, HttpMethod method, String path,
                                @Nullable String body) {
        ApiCredentials creds = credentials();
        String timestamp = String.valueOf(Instant.now().getEpochSecond());
        String signature = createHmacSignature(timestamp, method, path, body, creds.secret());

        request.setHeader("POLY_ADDRESS", eoaAddress());
        request.setHeader("POLY_API_KEY", creds.apiKey());
        request.setHeader("POLY_PASSPHRASE", creds.passphrase());
        request.setHeader("POLY_TIMESTAMP", timestamp);
        request.setHeader("POLY_SIGNATURE", signature);
    }

    // ---- execute methods ----

    @Nullable
    public <T> T execute(HttpUriRequest request, HttpMethod method, String path,
                          @Nullable String body, HttpClientResponseHandler<T> handler) {
        addAuthHeaders(request, method, path, body);
        try {
            return httpClient.execute(request, handler);
        } catch (Exception e) {
            log.warn("Polymarket API request failed: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    public <T> T execute(HttpMethod method, String path,
                          HttpClientResponseHandler<T> handler) {
        try {
            URI uri = URI.create(getBaseUrl() + path);
            HttpUriRequest request = method.createRequest(uri);
            String sigPath = uri.getPath();
            return execute(request, method, sigPath, null, handler);
        } catch (Exception e) {
            log.warn("Polymarket API request failed: {}", e.getMessage());
            return null;
        }
    }

    @Nullable
    public <T> T execute(HttpMethod method, String path, Object body,
                          HttpClientResponseHandler<T> handler) {
        try {
            String bodyJson = objectMapper.writeValueAsString(body);
            URI uri = URI.create(getBaseUrl() + path);
            HttpUriRequest request = method.createRequest(uri);
            if (request instanceof HttpUriRequestBase base) {
                base.setHeader("Content-Type", "application/json");
                base.setEntity(new StringEntity(bodyJson, StandardCharsets.UTF_8));
            }
            String sigPath = uri.getPath();
            return execute(request, method, sigPath, bodyJson, handler);
        } catch (JsonProcessingException e) {
            log.warn("Failed to serialise request body: {}", e.getMessage());
            return null;
        } catch (Exception e) {
            log.warn("Polymarket API request failed: {}", e.getMessage());
            return null;
        }
    }

    // ---- private helpers ----

    private String createHmacSignature(String timestamp, HttpMethod method, String path,
                                        @Nullable String body, String secret) {
        try {
            String message = timestamp + method.name() + path + (body != null ? body : "");
            byte[] secretBytes = Base64.getUrlDecoder().decode(secret);
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secretBytes, "HmacSHA256"));
            byte[] hash = mac.doFinal(message.getBytes(StandardCharsets.UTF_8));
            return Base64.getUrlEncoder().encodeToString(hash);
        } catch (Exception e) {
            log.error("Failed to create HMAC signature", e);
            return "";
        }
    }

    private ApiCredentials credentials() {
        ApiCredentials creds = apiCredentials;
        if (creds == null) {
            synchronized (this) {
                creds = apiCredentials;
                if (creds == null) {
                    creds = authL1Service.createOrDeriveApiKey(
                            config.getPolymarketPrivateKey(), config.getPolymarketChainId());
                    apiCredentials = creds;
                }
            }
        }
        return creds;
    }
}
