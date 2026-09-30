package hzpro.com.tradingdesk.arbitrage.websocket;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.Authenticator;
import java.net.PasswordAuthentication;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.KeyFactory;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.cert.X509Certificate;
import java.security.spec.PKCS8EncodedKeySpec;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicLong;
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
@ClientEndpoint(configurator = KalshiWebSocketClient.KalshiAuthConfigurator.class)
public class KalshiWebSocketClient {

    private static final String WS_URL = "wss://api.elections.kalshi.com/trade-api/ws/v2";
    private static final String WS_PATH = "/trade-api/ws/v2";
    private static volatile String kalshiAccessKey, kalshiAccessSignature, kalshiAccessTimestamp;

    private final List<String> tickers;
    private final BiConsumer<String, PriceSnapshot> onPriceUpdate;
    private final ObjectMapper objectMapper;
    private final AtomicLong messageIdCounter = new AtomicLong(1);
    private final ProxyConfig proxyConfig;
    private final String apiKey;
    private final PrivateKey privateKey;
    private Session session;

    private final ExecutorService workerPool = WebSocketMessageExecutor.create("kalshi");


    public KalshiWebSocketClient(List<String> tickers, BiConsumer<String, PriceSnapshot> onPriceUpdate,
                                 ProxyConfig proxyConfig, String apiKey, String privateKeyBase64) {
        this.tickers = tickers;
        this.onPriceUpdate = onPriceUpdate;
        this.proxyConfig = proxyConfig;
        this.apiKey = apiKey;
        this.objectMapper = new ObjectMapper();
        this.privateKey = loadPrivateKey(privateKeyBase64);
    }

    public static class KalshiAuthConfigurator extends ClientEndpointConfig.Configurator {
        public void beforeRequest(Map<String, List<String>> headers) {
            if (kalshiAccessKey != null) {
                headers.put("KALSHI-ACCESS-KEY", List.of(kalshiAccessKey));
                headers.put("KALSHI-ACCESS-SIGNATURE", List.of(kalshiAccessSignature));
                headers.put("KALSHI-ACCESS-TIMESTAMP", List.of(kalshiAccessTimestamp));
            }
        }
    }

    private PrivateKey loadPrivateKey(String base64Key) {
        try {
            if (base64Key == null || base64Key.isEmpty()) {
                log.warn("KALSHI_PRIVATE_KEY not set");
                return null;
            }
            byte[] pkcs1Bytes = Base64.getDecoder().decode(base64Key);
            byte[] pkcs8Bytes = convertPkcs1ToPkcs8(pkcs1Bytes);
            return KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(pkcs8Bytes));
        } catch (Exception e) {
            log.error("Failed to load Kalshi private key", e);
            return null;
        }
    }

    private byte[] convertPkcs1ToPkcs8(byte[] pkcs1Bytes) {
        byte[] header = new byte[]{
                0x30, (byte) 0x82, 0x00, 0x00, 0x02, 0x01, 0x00, 0x30, 0x0D,
                0x06, 0x09, 0x2A, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xF7, 0x0D, 0x01, 0x01, 0x01,
                0x05, 0x00, 0x04, (byte) 0x82, 0x00, 0x00};
        int len = pkcs1Bytes.length, total = header.length + len;
        header[2] = (byte) ((total - 4) >> 8); header[3] = (byte) (total - 4);
        header[header.length - 2] = (byte) (len >> 8); header[header.length - 1] = (byte) len;
        byte[] pkcs8 = new byte[total];
        System.arraycopy(header, 0, pkcs8, 0, header.length);
        System.arraycopy(pkcs1Bytes, 0, pkcs8, header.length, len);
        return pkcs8;
    }

    private String createSignature(String timestamp) {
        if (privateKey == null) return "";
        try {
            String msg = timestamp + "GET" + WS_PATH;
            Signature signer = Signature.getInstance("RSASSA-PSS");
            signer.setParameter(new java.security.spec.PSSParameterSpec("SHA-256", "MGF1",
                    java.security.spec.MGF1ParameterSpec.SHA256, 32, 1));
            signer.initSign(privateKey);
            signer.update(msg.getBytes(StandardCharsets.UTF_8));
            return Base64.getEncoder().encodeToString(signer.sign());
        } catch (Exception e) {
            log.error("Failed to sign", e);
            return "";
        }
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

    public void connect() throws Exception {
        ClientManager client = ClientManager.createClient();
        SSLContext ssl = createTrustAllSslContext();
        SslEngineConfigurator configurator = new SslEngineConfigurator(ssl);
        configurator.setHostVerificationEnabled(false);
        client.getProperties().put(ClientProperties.SSL_ENGINE_CONFIGURATOR, configurator);

        if (proxyConfig != null && proxyConfig.isConfigured()) {
            client.getProperties().put(ClientProperties.PROXY_URI, proxyConfig.toProxyUri());
            if (proxyConfig.hasAuth()) {
                String auth = proxyConfig.getUsername() + ":" + proxyConfig.getPassword();
                Map<String, String> h = Map.of("Proxy-Authorization", "Basic " + Base64.getEncoder().encodeToString(auth.getBytes()));
                client.getProperties().put(ClientProperties.PROXY_HEADERS, h);
                setProxyAuthenticator();
            }
        }
        if (apiKey != null && !apiKey.isEmpty() && privateKey != null) {
            String ts = String.valueOf(Instant.now().toEpochMilli());
            kalshiAccessKey = apiKey; kalshiAccessTimestamp = ts; kalshiAccessSignature = createSignature(ts);
        }
        session = client.connectToServer(this, URI.create(WS_URL));
        log.info("Kalshi WS connected for {} tickers", tickers.size());
    }

    @OnOpen
    public void onOpen(Session session) {
        this.session = session;
        log.info("KS opened, authenticating");
        subscribeToTickers();
    }

    private void authenticate() {
        if (apiKey == null || apiKey.isEmpty()) { log.warn("No API key, skip auth"); return; }
        try {
            String ts = String.valueOf(Instant.now().toEpochMilli());
            String sig = createSignature(ts);
            Map<String, Object> auth = new LinkedHashMap<>();
            auth.put("id", messageIdCounter.getAndIncrement());
            auth.put("cmd", "auth");
            auth.put("params", Map.of("KALSHI-ACCESS-KEY", apiKey, "KALSHI-ACCESS-SIGNATURE", sig, "KALSHI-ACCESS-TIMESTAMP", ts));
            session.getBasicRemote().sendText(objectMapper.writeValueAsString(auth));
            log.info("Sent KS auth");
        } catch (Exception e) {
            log.error("KS auth failed", e);
        }
    }

    private void subscribeToTickers() {
        try {
            Map<String, Object> msg = Map.of("id", messageIdCounter.getAndIncrement(), "cmd", "subscribe",
                    "params", Map.of("channels", List.of("ticker"), "market_tickers", tickers));
            session.getBasicRemote().sendText(objectMapper.writeValueAsString(msg));
            log.info("Subscribed {} KS tickers", tickers.size());
        } catch (Exception e) {
            log.error("KS subscribe failed", e);
        }
    }

    @OnMessage
    public void onMessage(String message) {

        workerPool.submit(() -> {
        try {
            JsonNode root = objectMapper.readTree(message);
            String type = root.path("type").asText();
            if ("ticker".equals(type)) {
                JsonNode msg = root.path("msg");
                String ticker = msg.path("market_ticker").asText();
                String yesAskStr = msg.path("yes_ask_dollars").asText();
                String yesBidStr = msg.path("yes_bid_dollars").asText();
                // if (yesAskStr == null || yesAskStr.isEmpty()) return;
                BigDecimal yesAsk = yesAskStr.isBlank() ? null : new BigDecimal(yesAskStr);
                BigDecimal yesBid = yesBidStr.isBlank() ? null : new BigDecimal(yesBidStr);
                PriceSnapshot snap = new PriceSnapshot();
                snap.setYesAsk(yesAsk);
                snap.setNoAsk(BigDecimal.ONE.subtract(yesBid));
                snap.setUpdatedAt(Instant.now());
                onPriceUpdate.accept(ticker, snap);
            } else if ("auth".equals(type)) {
                log.info("KS auth response: {}", root.path("msg").asText());
            }
        } catch (Exception e) {
                log.error("KS parse error {} {} ", message, e.getStackTrace());
        }});
    }

    @OnClose
    public void onClose(Session s, CloseReason r) { log.warn("KS closed: {}", r.getReasonPhrase()); session = null; }

    @OnError
    public void onError(Session s, Throwable e) { log.error("KS error", e); }

    public boolean isConnected() { return session != null && session.isOpen(); }

    public void disconnect() throws IOException {
        try {
            if (session != null && session.isOpen()) {
                session.close();
            }
        } finally {
            session = null;
            WebSocketMessageExecutor.shutdown(workerPool, "Kalshi");
        }
    }
}
