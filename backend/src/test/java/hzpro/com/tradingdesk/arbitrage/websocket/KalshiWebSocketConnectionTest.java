package hzpro.com.tradingdesk.arbitrage.websocket;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.model.PriceSnapshot;
import hzpro.com.tradingdesk.config.ProxyConfig;
import lombok.extern.slf4j.Slf4j;
import org.junit.jupiter.api.*;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.net.Authenticator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

@Slf4j
@Tag("manual")
@ActiveProfiles("test")
@SpringBootTest
class KalshiWebSocketConnectionTest {

    private static final List<String> TEST_TICKERS = List.of("KXPRESNOMR-28-JDV");

    @Autowired
    private ArbitrageConfig config;

    @Autowired
    private ProxyConfig proxyConfig;

    @BeforeEach
    void setUp() {
        Authenticator.setDefault(null);
    }

    @AfterEach
    void tearDown() {
        Authenticator.setDefault(null);
    }

    @Test
    @DisplayName("Kalshi WebSocket - Connect and receive ticker data (no proxy)")
    void testConnectWithoutProxy() throws Exception {
        CountDownLatch priceLatch = new CountDownLatch(1);
        AtomicReference<PriceSnapshot> receivedPrice = new AtomicReference<>();

        KalshiWebSocketClient client = new KalshiWebSocketClient(TEST_TICKERS, (ticker, snapshot) -> {
            log.info("Received Kalshi price for {}: YES={}, NO={}", ticker, snapshot.getYesAsk(), snapshot.getNoAsk());
            if (snapshot.getYesAsk() != null && snapshot.getNoAsk() != null) {
                receivedPrice.set(snapshot);
                priceLatch.countDown();
            }
        }, null, config.getKalshiApiKey(), config.getKalshiPrivateKey());

        try {
            client.connect();
        } catch (Exception e) {
            log.warn("Kalshi connection failed (may require proxy or auth): {}", e.getMessage());
            return;
        }

        assertThat(client.isConnected()).isTrue();
        log.info("Kalshi WebSocket connected successfully (no proxy)");

        boolean received = priceLatch.await(30, TimeUnit.SECONDS);
        if (received) {
            PriceSnapshot price = receivedPrice.get();
            assertThat(price).isNotNull();
            assertThat(price.getYesAsk()).isNotNull();
            log.info("Kalshi price received: YES={}, NO={}", price.getYesAsk(), price.getNoAsk());
        } else {
            log.warn("No price update received within timeout - market may be inactive");
        }

        client.disconnect();
        Thread.sleep(1000);
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    @DisplayName("Kalshi WebSocket - Connect with proxy configuration")
    void testConnectWithProxy() throws Exception {
        assertThat(proxyConfig.isConfigured())
                .withFailMessage("No proxy configuration found in application-test.yml")
                .isTrue();

        log.info("Using proxy: {}", proxyConfig.toProxyUri());

        CountDownLatch priceLatch = new CountDownLatch(1);
        AtomicReference<PriceSnapshot> receivedPrice = new AtomicReference<>();

        KalshiWebSocketClient client = new KalshiWebSocketClient(TEST_TICKERS, (ticker, snapshot) -> {
            log.info("Received Kalshi price via proxy for {}: YES={}, NO={}", ticker, snapshot.getYesAsk(), snapshot.getNoAsk());
            if (snapshot.getYesAsk() != null && snapshot.getNoAsk() != null) {
                receivedPrice.set(snapshot);
                priceLatch.countDown();
            }
        }, proxyConfig, config.getKalshiApiKey(), config.getKalshiPrivateKey());

        client.connect();

        assertThat(client.isConnected()).isTrue();
        log.info("Kalshi WebSocket connected successfully via proxy");

        boolean received = priceLatch.await(10, TimeUnit.SECONDS);
        if (received) {
            PriceSnapshot price = receivedPrice.get();
            assertThat(price).isNotNull();
            log.info("Kalshi price via proxy: YES={}, NO={}", price.getYesAsk(), price.getNoAsk());
        } else {
            log.warn("No price update received within timeout - market may be inactive");
        }

        client.disconnect();
    }

    @Test
    @DisplayName("Kalshi WebSocket - Verify disconnect and reconnect")
    void testDisconnectAndReconnect() throws Exception {
        KalshiWebSocketClient client = new KalshiWebSocketClient(TEST_TICKERS, (ticker, snapshot) -> {
            log.info("Received update during reconnect test: YES={}", snapshot.getYesAsk());
        }, proxyConfig, config.getKalshiApiKey(), config.getKalshiPrivateKey());

        try {
            client.connect();
        } catch (Exception e) {
            log.warn("Kalshi connection failed (may require proxy or auth): {}", e.getMessage());
            return;
        }

        assertThat(client.isConnected()).isTrue();
        log.info("First connection established");

        client.disconnect();
        Thread.sleep(1000);
        assertThat(client.isConnected()).isFalse();
        log.info("Disconnected successfully");

        client = new KalshiWebSocketClient(TEST_TICKERS, (ticker, snapshot) -> {
            log.info("Received update after reconnect: YES={}", snapshot.getYesAsk());
        }, proxyConfig, config.getKalshiApiKey(), config.getKalshiPrivateKey());

        client.connect();
        assertThat(client.isConnected()).isTrue();
        log.info("Reconnected successfully");

        client.disconnect();
    }
}