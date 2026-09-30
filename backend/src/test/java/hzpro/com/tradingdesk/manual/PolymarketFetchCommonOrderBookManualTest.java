package hzpro.com.tradingdesk.manual;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.rest.PolymarketPriceRestClient;
import hzpro.com.tradingdesk.arbitrage.service.PolymarketFetchCommonOrderBookService;
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
import org.apache.hc.core5.net.URIBuilder;
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
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PolymarketFetchCommonOrderBookService Tests")
@Slf4j
class PolymarketFetchCommonOrderBookManualTest {

    @MockitoBean
    private CloseableHttpClient httpClient;

    @MockitoBean
    private PredictionMarketRepository predictionMarketRepository;

    @Autowired
    private PolymarketFetchCommonOrderBookService service;

    @Autowired
    private ProxyConfig proxyConfig;

    @Autowired
    private ObjectMapper objectMapper;

    private ClassicHttpResponse httpResponse;
    private String mockResponse;
    private PredictionMarket mockMarket;

    private static final String MARKET_TICKER = "TEST-MARKET";
    private static final String YES_TOKEN_ID = "yes-token-123";
    private static final String NO_TOKEN_ID = "no-token-456";

    @BeforeEach
    void setUp() throws Exception {
        httpResponse = mock(ClassicHttpResponse.class);
        mockResponse = Files.readString(
                Paths.get(getClass().getClassLoader().getResource("json/polymarket_orderbook.json").toURI()));

        mockMarket = PredictionMarket.builder()
                .datasource(DataSource.POLYMARKET)
                .marketTicker(MARKET_TICKER)
                .yesTokenId(YES_TOKEN_ID)
                .noTokenId(NO_TOKEN_ID)
                .build();
    }


    @Test
    @Tag("manual")
    @DisplayName("MANUAL: fetch real Polymarket orderbook for will-bruno-mars-...-743")
    void manualTestRealPolymarketOrderBook() throws Exception {
        final String realSlug =
                "ucl-dgj-kau-2026-07-14-second-half-result-home";

        CloseableHttpClient realHttpClient = new HttpClientWithHttpsProxyConfig(proxyConfig).httpClient();
        try {
            // Step 1: resolve slug -> [yesTokenId, noTokenId] via Gamma.
            String[] tokenIds = resolveClobTokenIds(realHttpClient, realSlug);
            assertThat(tokenIds)
                    .withFailMessage("Could not resolve clobTokenIds from Gamma for slug %s", realSlug)
                    .isNotNull();
            assertThat(tokenIds).hasSize(2);
            String yesTokenId = tokenIds[0];
            String noTokenId = tokenIds[1];
            log.info("Resolved Polymarket slug {} -> YES tokenId={}, NO tokenId={}",
                    realSlug, yesTokenId, noTokenId);

            // Step 2: stub the repository to return a real PredictionMarket carrying
            // the resolved token ids, so the service uses them in the REST call.
            PredictionMarket realMarket = PredictionMarket.builder()
                    .datasource(DataSource.POLYMARKET)
                    .marketTicker(realSlug)
                    .yesTokenId(yesTokenId)
                    .noTokenId(noTokenId)
                    .build();
            when(predictionMarketRepository
                    .findFirstByMarketTickerAndDatasourceOrderByCreatedAtDesc(realSlug, DataSource.POLYMARKET))
                    .thenReturn(Optional.of(realMarket));

            // Step 3: build a real Polymarket client and service, bypassing the mocked
            // CloseableHttpClient bean.
            PolymarketPriceRestClient realRestClient =
                    new PolymarketPriceRestClient(realHttpClient, objectMapper);
            PolymarketFetchCommonOrderBookService realService =
                    new PolymarketFetchCommonOrderBookService(realRestClient, predictionMarketRepository);

            // Step 4: fetch and log both sides.
            OrderBook yesBook = realService.fetchCommonOrderBook(realSlug, YesOrNoResult.YES);
            OrderBook noBook = realService.fetchCommonOrderBook(realSlug, YesOrNoResult.NO);


            assertThat(yesBook != null || noBook != null)
                    .withFailMessage("Both YES and NO orderbooks were null — likely a network issue or invalid slug")
                    .isTrue();

            // Every ask price must be a valid probability in [0, 1].
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

    private String[] resolveClobTokenIds(CloseableHttpClient client, String slug) throws Exception {
        URI gammaUri = new URIBuilder("https://gamma-api.polymarket.com/markets")
                .addParameter("slug", slug)
                .build();
        HttpGet get = new HttpGet(gammaUri);
        return client.execute(get, response -> {
            if (response.getCode() < 200 || response.getCode() >= 300) {
                log.warn("Gamma lookup failed for slug {}: status {}", slug, response.getCode());
                return null;
            }
            List<?> markets = objectMapper.readValue(
                    response.getEntity().getContent(),
                    new TypeReference<List<?>>() {});
            if (markets == null || markets.isEmpty()) {
                log.warn("Gamma returned no markets for slug {}", slug);
                return null;
            }
            // First element is the market we want; clobTokenIds is a JSON-encoded string array.
            @SuppressWarnings("unchecked")
            String clobTokenIdsJson = (String) ((java.util.Map<String, Object>) markets.get(0))
                    .get("clobTokenIds");
            if (clobTokenIdsJson == null || clobTokenIdsJson.isEmpty()) {
                log.warn("Market for slug {} has no clobTokenIds", slug);
                return null;
            }
            List<String> ids = objectMapper.readValue(clobTokenIdsJson, new TypeReference<List<String>>() {});
            if (ids.size() < 2) {
                log.warn("Market for slug {} has only {} clobTokenIds", slug, ids.size());
                return null;
            }
            return new String[]{ids.get(0), ids.get(1)};
        });
    }
}
