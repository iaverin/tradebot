package hzpro.com.tradingdesk.arbitrage.websocket;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.security.cert.X509Certificate;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.function.BiConsumer;

import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManager;
import javax.net.ssl.X509TrustManager;

import org.glassfish.tyrus.client.ClientManager;
import org.glassfish.tyrus.client.ClientProperties;
import org.glassfish.tyrus.client.SslEngineConfigurator;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.arbitrage.model.PriceSnapshot;
import hzpro.com.tradingdesk.config.ProxyConfig;
import jakarta.websocket.ClientEndpoint;
import jakarta.websocket.ClientEndpointConfig;
import jakarta.websocket.CloseReason;
import jakarta.websocket.OnClose;
import jakarta.websocket.OnError;
import jakarta.websocket.OnMessage;
import jakarta.websocket.OnOpen;
import jakarta.websocket.Session;
import lombok.extern.slf4j.Slf4j;

@Slf4j
@ClientEndpoint(configurator = PolymarketWebSocketClient.ProxyAuthConfigurator.class)
public class PolymarketWebSocketClient {

    private static final String WS_URL = "wss://ws-subscriptions-clob.polymarket.com/ws/market";
    private static volatile String proxyAuthorizationHeader;

    private final List<String> assetIds;
    private final BiConsumer<String, PriceSnapshot> onPriceUpdate;
    private final ObjectMapper objectMapper;
    private final ProxyConfig proxyConfig;

    private Session session;

    private final ExecutorService workerPool = WebSocketMessageExecutor.create("polymarket");


    public PolymarketWebSocketClient(List<String> assetIds,
                                     BiConsumer<String, PriceSnapshot> onPriceUpdate,
                                     ProxyConfig proxyConfig) {
        this.assetIds = assetIds;
        this.onPriceUpdate = onPriceUpdate;
        this.proxyConfig = proxyConfig;
        this.objectMapper = new ObjectMapper();
        if (proxyConfig != null && proxyConfig.isConfigured() && proxyConfig.hasAuth()) {
            String auth = proxyConfig.getUsername() + ":" + proxyConfig.getPassword();
            proxyAuthorizationHeader = "Basic " + Base64.getEncoder().encodeToString(auth.getBytes());
        }
    }

    public static class ProxyAuthConfigurator extends ClientEndpointConfig.Configurator {
        @Override
        public void beforeRequest(Map<String, List<String>> headers) {
            if (proxyAuthorizationHeader != null) {
                headers.put("Proxy-Authorization", List.of(proxyAuthorizationHeader));
            }
        }
    }

    public void connect() throws Exception {
        ClientManager client = ClientManager.createClient();
        SSLContext sslContext = createTrustAllSslContext();
        SslEngineConfigurator sslConfig = new SslEngineConfigurator(sslContext);
        sslConfig.setHostVerificationEnabled(false);
        client.getProperties().put(ClientProperties.SSL_ENGINE_CONFIGURATOR, sslConfig);

        if (proxyConfig != null && proxyConfig.isConfigured()) {
            client.getProperties().put(ClientProperties.PROXY_URI, proxyConfig.toProxyUri());
            if (proxyConfig.hasAuth()) {
                String auth = proxyConfig.getUsername() + ":" + proxyConfig.getPassword();
                Map<String, String> headers = Map.of("Proxy-Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes()));
                client.getProperties().put(ClientProperties.PROXY_HEADERS, headers);
                setProxyAuthenticator();
            }
        }
        session = client.connectToServer(this, URI.create(WS_URL));
        log.info("Polymarket WebSocket connected with {} asset IDs", assetIds.size());
    }

    private SSLContext createTrustAllSslContext() throws Exception {
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new X509TrustManager() {
            public void checkClientTrusted(X509Certificate[] c, String a) {}
            public void checkServerTrusted(X509Certificate[] c, String a) {}
            public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
        }}, null);
        return ctx;
    }

    private void setProxyAuthenticator() {
        Authenticator.setDefault(new Authenticator() {
            protected PasswordAuthentication getPasswordAuthentication() {
                if (getRequestorType() == RequestorType.PROXY)
                    return new PasswordAuthentication(proxyConfig.getUsername(), proxyConfig.getPassword().toCharArray());
                return null;
            }
        });
    }

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        log.info("PM WebSocket opened, subscribing");
        subscribeToMarket();
    }

    private void subscribeToMarket() {
        try {
            Map<String, Object> msg = Map.of("type", "market", "assets_ids", assetIds);
            session.getBasicRemote().sendText(objectMapper.writeValueAsString(msg));
            log.info("Subscribed {} assets", assetIds.size());
        } catch (Exception e) {
            log.error("Failed to subscribe", e);
        }
    }

    @OnMessage
    public void onMessage(String message) {
         workerPool.submit(() -> {
            try {
                try {
                    JsonNode root = objectMapper.readTree(message);
                    String eventType = root.path("event_type").asText();
                    if ("price_change".equals(eventType) || "book".equals(eventType)) {
                        handlePriceChange(root);
                    }
                } catch (Exception e) {
                     log.error("PM parse error: {} {}", message, e.getStackTrace());
                }

        } catch (Exception e) {
                        log.error("Error proccessing PM message update {} ", e.getMessage());
                    }
                }
         );
    }

    private void handlePriceChange(JsonNode root) {
        JsonNode changes = root.path("price_changes");
        if (!changes.isArray()) return;

        for (JsonNode change : changes) {
            String assetId = change.path("asset_id").asText();
            if (assetId == null || assetId.isEmpty()) continue;

            String bestAskStr = change.path("best_ask").asText();
            if (bestAskStr == null || bestAskStr.isEmpty()) continue;

            try {
                BigDecimal price = new BigDecimal(bestAskStr);

                PriceSnapshot snap = new PriceSnapshot();
                snap.setPlatform("POLYMARKET");
                snap.setUpdatedAt(Instant.now());
                snap.setYesAsk(price);

                onPriceUpdate.accept(assetId, snap);
            } catch (NumberFormatException e) {
                log.debug("Failed to parse price for asset {}: {}", assetId, bestAskStr);

            }
        }
    }

    @OnClose
    public void onClose(Session s, CloseReason r) {
        log.warn("PM closed: {}", r.getReasonPhrase());
        session = null;
    }

    @OnError
    public void onError(Session s, Throwable e) {
        log.error("PM error", e);
    }

    public boolean isConnected() { return session != null && session.isOpen(); }
    public void disconnect() throws IOException {
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } finally {
            session = null;
            WebSocketMessageExecutor.shutdown(workerPool, "Polymarket");
        }
    }
}
