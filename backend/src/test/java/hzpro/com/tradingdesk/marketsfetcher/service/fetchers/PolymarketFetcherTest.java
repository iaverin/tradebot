package hzpro.com.tradingdesk.marketsfetcher.service.fetchers;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.marketsfetcher.lib.FetcherState;
import hzpro.com.tradingdesk.marketsfetcher.mapper.PolymarketMapper;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.apache.hc.core5.http.io.entity.StringEntity;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.ActiveProfiles;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("PolymarketFetcher Tests")
class PolymarketFetcherTest {

    @MockitoBean
    private CloseableHttpClient httpClient;

    @Autowired
    private PredictionMarketRepository repository;

    @Autowired
    private PolymarketFetcher polymarketFetcher;

    private static final String NEXT_CURSOR = "next-page-cursor";

    private ClassicHttpResponse httpResponse;
    private ObjectMapper objectMapper;
    private String eventsArrayJson;
    private String mockResponse;
    private List<PredictionMarket> marketsFromJson;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        httpResponse = mock(ClassicHttpResponse.class);
        repository.deleteAll();
        // Fixture is a bare array of events; the keyset endpoint wraps it in
        // {"events":[...], "next_cursor":"..."}, so build that envelope for HTTP mocks.
        eventsArrayJson = Files.readString(Paths.get(getClass().getClassLoader().getResource("json/polymarket_events.json").toURI()));
        mockResponse = "{\"events\":" + eventsArrayJson + ",\"next_cursor\":\"" + NEXT_CURSOR + "\"}";
        marketsFromJson = new PolymarketMapper(objectMapper).mapToPredictionMarketList(eventsArrayJson);
    }

    @Test
    @DisplayName("fetch() - Should successfully fetch and parse Polymarket markets")
    void testFetchSuccess() throws Exception {
        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenReturn(new StringEntity(mockResponse));
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });

        FetcherState initialState = polymarketFetcher.initialState();
        FetcherState result = polymarketFetcher.fetchAndPersist(initialState);

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.SUCCESS);
        assertThat(result.getMarketsFetched()).isEqualTo(marketsFromJson.size());
        assertThat(result.getRequestNumber()).isEqualTo(1);
        assertThat(result.isHasMoreData()).isTrue();

        verify(httpClient).execute(any(), any(HttpClientResponseHandler.class));

        // DB verification
        List<PredictionMarket> saved = repository.findByDatasource(DataSource.POLYMARKET);
        assertThat(saved).hasSize(marketsFromJson.size());
        assertThat(saved).allMatch(m -> m.getDatasource() == DataSource.POLYMARKET);
        assertThat(saved).extracting(PredictionMarket::getMarketTicker)
            .containsExactlyInAnyOrderElementsOf(
                marketsFromJson.stream().map(PredictionMarket::getMarketTicker).toList()
            );
    }

    @Test
    @DisplayName("fetch() - Should handle empty response")
    void testFetchEmptyResponse() throws Exception {
        // Empty array with extra metadata to make it 100+ characters for logging
        String emptyResponse = "[{\"_meta\":{\"message\":\"no events available\",\"offset_exceeded\":true,\"timestamp\":\"2026-03-12T00:00:00Z\"}}]";

        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenAnswer(inv -> new StringEntity(emptyResponse));
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });

        FetcherState initialState = polymarketFetcher.initialState();
        FetcherState result = polymarketFetcher.fetchAndPersist(initialState);

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.SUCCESS);
        assertThat(result.getMarketsFetched()).isEqualTo(0);
        assertThat(result.isHasMoreData()).isFalse();

        assertThat(repository.findByDatasource(DataSource.POLYMARKET)).isEmpty();
    }

    @Test
    @DisplayName("fetch() - Should handle cursor-based (keyset) pagination")
    void testFetchWithCursor() throws Exception {
        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenAnswer(inv -> new StringEntity(mockResponse));
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });

        FetcherState initialState = polymarketFetcher.initialState();
        FetcherState firstResult = polymarketFetcher.fetchAndPersist(initialState);

        // First request has no cursor yet (initial state seeds an empty next_cursor).
        assertThat(firstResult.getRequestQueryParameters()).doesNotContainKey("after_cursor");

        FetcherState secondResult = polymarketFetcher.fetchAndPersist(firstResult);

        // Second request carries the cursor extracted from the first response.
        assertThat(secondResult.getRequestQueryParameters()).containsEntry("after_cursor", NEXT_CURSOR);
    }

    @Test
    @DisplayName("fetch() - Should detect no more data when response is empty")
    void testFetchNoMoreData() throws Exception {
        // Empty array with metadata to make it 100+ characters
        String emptyResponse = "[{\"_meta\":{\"message\":\"pagination complete\",\"total\":500,\"offset\":520,\"timestamp\":\"2026-03-12T00:00:00Z\"}}]";

        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenAnswer(inv -> new StringEntity(emptyResponse));
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });

        FetcherState initialState = polymarketFetcher.initialState();
        FetcherState result = polymarketFetcher.fetchAndPersist(initialState);

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.SUCCESS);
        assertThat(result.isHasMoreData()).isFalse();
    }

    @Test
    @DisplayName("fetch() - Should handle errors gracefully")
    void testFetchError() throws Exception {
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class)))
            .thenThrow(new RuntimeException("API timeout"));

        FetcherState initialState = polymarketFetcher.initialState();
        FetcherState result = polymarketFetcher.fetchAndPersist(initialState);

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.ERROR);
        assertThat(result.getErrorMessage()).contains("API timeout");
        assertThat(result.isHasMoreData()).isFalse();

        assertThat(repository.findByDatasource(DataSource.POLYMARKET)).isEmpty();
    }

    @Test
    @DisplayName("initialState() - Should return valid initial state for keyset endpoint")
    void testInitialState() {
        FetcherState initialState = polymarketFetcher.initialState();

        assertThat(initialState.getUrl()).isEqualTo("https://gamma-api.polymarket.com/events/keyset");
        assertThat(initialState.isHasMoreData()).isTrue();
        assertThat(initialState.getRequestNumber()).isEqualTo(0);
        assertThat(initialState.getMarketsFetched()).isEqualTo(0);
        // Seeds an empty cursor envelope so the first request omits after_cursor.
        assertThat(initialState.getResponseText()).contains("\"next_cursor\":\"\"");
    }

    @Test
    @DisplayName("hasMoreData() - Should return true when items exist and a cursor is present")
    void testHasMoreDataTrue() {
        boolean result = polymarketFetcher.hasMoreData(
            "{\"events\":[],\"next_cursor\":\"" + NEXT_CURSOR + "\"}", 1);

        assertThat(result).isTrue();
    }

    @Test
    @DisplayName("hasMoreData() - Should return false when no items")
    void testHasMoreDataFalse() {
        boolean result = polymarketFetcher.hasMoreData("{\"events\":[],\"next_cursor\":\"\"}", 0);

        assertThat(result).isFalse();
    }
}
