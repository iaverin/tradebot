package hzpro.com.tradingdesk.arbitrage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.client.KalshiAuthorizedClient;
import hzpro.com.tradingdesk.client.config.HttpClientWithHttpsProxyConfig;
import hzpro.com.tradingdesk.config.ProxyConfig;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.domain.orderbook.OrderBook;
import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import lombok.extern.slf4j.Slf4j;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("KalshiFetchCommonOrderBookService Tests")
@Slf4j
class KalshiFetchCommonOrderBookServiceTest {

    @MockitoBean
    private CloseableHttpClient httpClient;

    @MockitoBean
    private PredictionMarketRepository predictionMarketRepository;

    @Autowired
    private KalshiFetchCommonOrderBookService service;

    @Autowired
    private ArbitrageConfig arbitrageConfig;

    @Autowired
    private ProxyConfig proxyConfig;

    @Autowired
    private ObjectMapper objectMapper;

    private ClassicHttpResponse httpResponse;
    private String mockResponse;
    private PredictionMarket mockMarket;

    private static final String MARKET_TICKER = "KXTEST-99XYZ-T50";

    @BeforeEach
    void setUp() throws Exception {
        httpResponse = mock(ClassicHttpResponse.class);
        mockResponse = Files.readString(
                Paths.get(getClass().getClassLoader().getResource("json/kalshi_orderbook.json").toURI()));

        mockMarket = PredictionMarket.builder()
                .datasource(DataSource.KALSHI)
                .marketTicker(MARKET_TICKER)
                .build();
    }

    @Test
    @DisplayName("fetchCommonOrderBook() - YES asks derived from no_dollars (1 - no_bid_price)")
    void testFetchCommonOrderBookYes() throws Exception {
        when(predictionMarketRepository
                .findFirstByMarketTickerAndDatasourceOrderByCreatedAtDesc(MARKET_TICKER, DataSource.KALSHI))
                .thenReturn(Optional.of(mockMarket));

        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenReturn(new StringEntity(mockResponse));
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });

        OrderBook result = service.fetchCommonOrderBook(MARKET_TICKER, YesOrNoResult.YES);

        assertThat(result).isNotNull();
        assertThat(result.asks()).hasSize(2);
        // no_dollars[1] = ["0.5600", "17.00"]  -> YES ask = 1 - 0.56 = 0.44, size 17

        assertThat(result.asks().get(0).price()).isEqualByComparingTo(new BigDecimal("0.44"));
        assertThat(result.asks().get(0).amount()).isEqualTo(17L);

        // no_dollars[0] = ["0.4500", "20.00"]  -> YES ask = 1 - 0.45 = 0.55, size 20
        // Best YES ask is the LAST element (matches Polymarket convention).
        assertThat(result.asks().get(1).price()).isEqualByComparingTo(new BigDecimal("0.55"));
        assertThat(result.asks().get(1).amount()).isEqualTo(20L);


        verify(httpClient).execute(any(), any(HttpClientResponseHandler.class));
    }

    @Test
    @DisplayName("fetchCommonOrderBook() - NO asks derived from yes_dollars (1 - yes_bid_price); URL contains ticker")
    void testFetchCommonOrderBookNo() throws Exception {
        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenReturn(new StringEntity(mockResponse));
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });

        OrderBook result = service.fetchCommonOrderBook(MARKET_TICKER, YesOrNoResult.NO);

        assertThat(result).isNotNull();
        assertThat(result.asks()).hasSize(2);
        // OrderBook sorts asks ascending by price.
        // yes_dollars[1] = ["0.4200", "13.00"] -> NO ask = 1 - 0.42 = 0.58, size 13
        assertThat(result.asks().get(0).price()).isEqualByComparingTo(new BigDecimal("0.58"));
        assertThat(result.asks().get(0).amount()).isEqualTo(13L);
        // yes_dollars[0] = ["0.3000", "11.00"] -> NO ask = 1 - 0.30 = 0.70, size 11
        assertThat(result.asks().get(1).price()).isEqualByComparingTo(new BigDecimal("0.70"));
        assertThat(result.asks().get(1).amount()).isEqualTo(11L);

        ArgumentCaptor<HttpGet> requestCaptor = ArgumentCaptor.forClass(HttpGet.class);
        verify(httpClient).execute(requestCaptor.capture(), any(HttpClientResponseHandler.class));
        // URL is /markets/{ticker}/orderbook (no query string).
        assertThat(requestCaptor.getValue().getUri().getPath()).endsWith("/markets/" + MARKET_TICKER + "/orderbook");
    }

    @Test
    @DisplayName("fetchCommonOrderBook() - Should return null when REST returns a non-2xx response")
    void testFetchCommonOrderBookNotFound() throws Exception {
        // The REST client maps any non-2xx response to a null orderbook, and the
        // service returns null when the orderbook is null.
        when(httpResponse.getCode()).thenReturn(404);
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });

        OrderBook result = service.fetchCommonOrderBook(MARKET_TICKER, YesOrNoResult.YES);

        assertThat(result).isNull();
        verify(httpClient).execute(any(), any(HttpClientResponseHandler.class));
    }

    @Test
    @DisplayName("fetchCommonOrderBook() - Should return null when HTTP request throws exception")
    void testFetchCommonOrderBookHttpFailure() throws Exception {
        when(predictionMarketRepository
                .findFirstByMarketTickerAndDatasourceOrderByCreatedAtDesc(MARKET_TICKER, DataSource.KALSHI))
                .thenReturn(Optional.of(mockMarket));

        when(httpClient.execute(any(), any(HttpClientResponseHandler.class)))
                .thenThrow(new RuntimeException("Network error"));

        OrderBook result = service.fetchCommonOrderBook(MARKET_TICKER, YesOrNoResult.YES);

        assertThat(result).isNull();
        verify(httpClient).execute(any(), any(HttpClientResponseHandler.class));
    }

    /**
     * Manual integration test — hits the real Kalshi REST endpoint for ticker
     * {@code KXTOPMONTHLY-26MAY-BRU}. Excluded from the default {@code ./gradlew test}
     * task by the {@code @Tag("manual")} marker; run via {@code ./gradlew manualTest}.
     *
     * <p>This test bypasses the class-level {@code @MockitoBean CloseableHttpClient} by
     * building its own real HTTP client (honoring {@link ProxyConfig}) and constructing
     * fresh {@link KalshiAuthorizedClient} + {@link KalshiFetchCommonOrderBookService}
     * instances. The {@link PredictionMarketRepository} mock is stubbed to return a
     * stand-in {@link PredictionMarket} so the service proceeds to the real REST call.
     */
    @Test
    @Tag("manual")
    @DisplayName("MANUAL: fetch real Kalshi orderbook for KXTOPMONTHLY-26MAY-BRU")
    void manualTestRealKalshiOrderBook() throws Exception {
        final String realTicker = "KXGDP-26JUL30-T2.0";

        PredictionMarket realMarket = PredictionMarket.builder()
                .datasource(DataSource.KALSHI)
                .marketTicker(realTicker)
                .build();
        when(predictionMarketRepository
                .findFirstByMarketTickerAndDatasourceOrderByCreatedAtDesc(realTicker, DataSource.KALSHI))
                .thenReturn(Optional.of(realMarket));

        // Build a real HTTP client locally so we sidestep the @MockitoBean override.
        CloseableHttpClient realHttpClient = new HttpClientWithHttpsProxyConfig(proxyConfig).httpClient();
        try {
            KalshiAuthorizedClient realAuthClient =
                    new KalshiAuthorizedClient(realHttpClient, arbitrageConfig, objectMapper);
            // @PostConstruct does not fire on manually-instantiated beans — invoke it.
            realAuthClient.init();

            KalshiFetchCommonOrderBookService realService =
                    new KalshiFetchCommonOrderBookService(realAuthClient, objectMapper);

            OrderBook yesBook = realService.fetchCommonOrderBook(realTicker, YesOrNoResult.YES);
            OrderBook noBook = realService.fetchCommonOrderBook(realTicker, YesOrNoResult.NO);

            assertThat(yesBook != null || noBook != null)
                    .withFailMessage("Both YES and NO orderbooks were null — likely a network/auth issue or invalid ticker")
                    .isTrue();

            // Every ask price must be a valid probability in (0, 1] (asks are 1 - bid_price,
            // bids are in [0, 1)).
            if (yesBook != null) {
                yesBook.asks().forEach(e -> assertThat(e.price())
                        .isBetween(BigDecimal.ZERO, BigDecimal.ONE));
            }
            if (noBook != null) {
                noBook.asks().forEach(e -> assertThat(e.price())
                        .isBetween(BigDecimal.ZERO, BigDecimal.ONE));
            }
        } finally {
            realHttpClient.close();
        }
    }
}
