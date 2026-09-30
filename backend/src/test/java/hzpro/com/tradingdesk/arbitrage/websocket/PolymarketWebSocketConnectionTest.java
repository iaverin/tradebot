package hzpro.com.tradingdesk.arbitrage.websocket;

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
class PolymarketWebSocketConnectionTest {

    private static final String YES_TOKEN_ID = "21742633143463906290569050155826241533067272736897614950488156847949938836455";
    private static final String NO_TOKEN_ID = "43764674987764506182400484268047460882518914814228086497454652442430480759562";

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
    @DisplayName("Polymarket WebSocket - Connect and receive price data (no proxy)")
    void testConnectWithoutProxy() throws Exception {
        CountDownLatch priceLatch = new CountDownLatch(1);
        AtomicReference<PriceSnapshot> receivedPrice = new AtomicReference<>();

        PolymarketWebSocketClient client = new PolymarketWebSocketClient(
                List.of(YES_TOKEN_ID, NO_TOKEN_ID),
                (assetId, snapshot) -> {
                    log.info("Received PM price for {}: YES={}, NO={}", assetId, snapshot.getYesAsk(), snapshot.getNoAsk());
                    if (snapshot.getYesAsk() != null && snapshot.getNoAsk() != null) {
                        receivedPrice.set(snapshot);
                        priceLatch.countDown();
                    }
                },
                null
        );

        client.connect();

        assertThat(client.isConnected()).isTrue();
        log.info("Polymarket WebSocket connected successfully (no proxy)");

        boolean received = priceLatch.await(30, TimeUnit.SECONDS);
        if (received) {
            PriceSnapshot price = receivedPrice.get();
            assertThat(price).isNotNull();
            assertThat(price.getYesAsk()).isNotNull();
            log.info("Polymarket price received: YES={}, NO={}", price.getYesAsk(), price.getNoAsk());
        } else {
            log.warn("No price update received within timeout - market may be inactive");
        }

        client.disconnect();
        Thread.sleep(1000);
        assertThat(client.isConnected()).isFalse();
    }

    @Test
    @DisplayName("Polymarket WebSocket - Connect with proxy configuration")
    void testConnectWithProxy() throws Exception {
        assertThat(proxyConfig.isConfigured())
                .withFailMessage("No proxy configuration found - ensure PROXY_HOST/PROXY_PORT are set in .env")
                .isTrue();

        log.info("Using proxy: {}", proxyConfig.toProxyUri());

        CountDownLatch priceLatch = new CountDownLatch(1);
        AtomicReference<PriceSnapshot> receivedPrice = new AtomicReference<>();

        PolymarketWebSocketClient client = new PolymarketWebSocketClient(
                List.of(YES_TOKEN_ID, NO_TOKEN_ID),
                (assetId, snapshot) -> {
                    log.info("Received PM price via proxy for {}: YES={}, NO={}", assetId, snapshot.getYesAsk(), snapshot.getNoAsk());
                    if (snapshot.getYesAsk() != null && snapshot.getNoAsk() != null) {
                        receivedPrice.set(snapshot);
                        priceLatch.countDown();
                    }
                },
                proxyConfig
        );

        client.connect();

        assertThat(client.isConnected()).isTrue();
        log.info("Polymarket WebSocket connected successfully via proxy");

        boolean received = priceLatch.await(30, TimeUnit.SECONDS);
        if (received) {
            PriceSnapshot price = receivedPrice.get();
            assertThat(price).isNotNull();
            log.info("Polymarket price via proxy: YES={}, NO={}", price.getYesAsk(), price.getNoAsk());
        } else {
            log.warn("No price update received within timeout - market may be inactive");
        }

        client.disconnect();
    }

    @Test
    @DisplayName("Polymarket WebSocket - Verify disconnect and reconnect")
    void testDisconnectAndReconnect() throws Exception {
        PolymarketWebSocketClient client = new PolymarketWebSocketClient(
                List.of(YES_TOKEN_ID, NO_TOKEN_ID),
                (assetId, snapshot) -> log.info("Received update during reconnect: YES={}", snapshot.getYesAsk()),
                null
        );

        try {
            client.connect();
        } catch (Exception e) {
            log.warn("Polymarket connection failed: {}", e.getMessage());
            return;
        }

        assertThat(client.isConnected()).isTrue();
        log.info("First connection established");

        client.disconnect();
        Thread.sleep(1000);
        assertThat(client.isConnected()).isFalse();
        log.info("Disconnected successfully");

        client = new PolymarketWebSocketClient(
                List.of(YES_TOKEN_ID, NO_TOKEN_ID),
                (assetId, snapshot) -> log.info("Received update after reconnect: YES={}", snapshot.getYesAsk()),
                null
        );

        client.connect();
        assertThat(client.isConnected()).isTrue();
        log.info("Reconnected successfully");

        client.disconnect();
    }
}