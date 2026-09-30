package hzpro.com.tradingdesk.marketsfetcher.service.fetchers;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.marketsfetcher.lib.FetcherState;
import hzpro.com.tradingdesk.marketsfetcher.mapper.KalshiMapper;
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
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@SpringBootTest
@ActiveProfiles("test")
@DisplayName("KalshiFetcher Tests")
class KalshiFetcherTest {

    @MockitoBean
    private CloseableHttpClient httpClient;

    @Autowired
    private PredictionMarketRepository repository;

    @Autowired
    private KalshiMapper mapper;

    @Autowired
    private KalshiFetcher kalshiFetcher;

    private ClassicHttpResponse httpResponse;
    private ObjectMapper objectMapper;
    private String mockResponse;
    private List<PredictionMarket> mockMarkets;

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        httpResponse = mock(ClassicHttpResponse.class);
        repository.deleteAll();
        mockResponse = Files.readString(Paths.get(getClass().getClassLoader().getResource("json/kalshi_events_with_nested_markets.json").toURI()));
        mockMarkets = mapper.mapToPredictionMarketList(mockResponse);
    }

    @Test
    @DisplayName("fetch() - Should successfully fetch and parse Kalshi markets")
    void testFetchSuccess() throws Exception {
        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenReturn(new StringEntity(mockResponse));
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });

        FetcherState initialState = kalshiFetcher.initialState();
        FetcherState result = kalshiFetcher.fetchAndPersist(initialState);

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.SUCCESS);
        assertThat(result.getMarketsFetched()).isEqualTo(mockMarkets.size());
        assertThat(result.getRequestNumber()).isEqualTo(1);
        assertThat(result.isHasMoreData()).isTrue();

        verify(httpClient).execute(any(), any(HttpClientResponseHandler.class));

        // DB verification
        List<PredictionMarket> saved = repository.findByDatasource(DataSource.KALSHI);
        assertThat(saved).hasSize(mockMarkets.size());
        assertThat(saved).allMatch(m -> m.getDatasource() == DataSource.KALSHI);
        assertThat(saved).extracting(PredictionMarket::getMarketTicker)
            .containsExactlyInAnyOrderElementsOf(
                mockMarkets.stream().map(PredictionMarket::getMarketTicker).toList()
            );
    }

    @Test
    @DisplayName("fetch() - Should handle empty response")
    void testFetchEmptyResponse() throws Exception {
        String emptyResponse = "{\"cursor\":\"\",\"events\":[],\"metadata\":{\"timestamp\":\"2026-03-12T00:00:00Z\",\"total_count\":0,\"page_size\":0}}";

        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenAnswer(inv -> new StringEntity(emptyResponse));
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });

        FetcherState initialState = kalshiFetcher.initialState();
        FetcherState result = kalshiFetcher.fetchAndPersist(initialState);

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.SUCCESS);
        assertThat(result.getMarketsFetched()).isEqualTo(0);
        assertThat(result.isHasMoreData()).isFalse();

        assertThat(repository.findByDatasource(DataSource.KALSHI)).isEmpty();
    }

    @Test
    @DisplayName("fetch() - Should handle cursor-based pagination")
    void testFetchWithCursor() throws Exception {
        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenAnswer(inv -> new StringEntity(mockResponse));
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });

        FetcherState stateWithResponse = FetcherState.initial("https://api.elections.kalshi.com/trade-api/v2/events")
            .toBuilder()
            .responseText(mockResponse)
            .build();

        FetcherState result = kalshiFetcher.fetchAndPersist(stateWithResponse);

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.SUCCESS);
        assertThat(result.isHasMoreData()).isTrue();
        assertThat(result.getRequestQueryParameters()).containsEntry("cursor", "CgYI8Nj99wYSC1NFTkFURU9SLTI4");
    }

    @Test
    @DisplayName("fetch() - Should detect no more data when cursor is empty")
    void testFetchNoMoreData() throws Exception {
        String responseNoCursor = "{\"cursor\":\"\",\"events\":[],\"metadata\":{\"timestamp\":\"2026-03-12T00:00:00Z\",\"total_count\":0,\"page\":123}}";

        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenAnswer(inv -> new StringEntity(responseNoCursor));
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });

        FetcherState initialState = kalshiFetcher.initialState();
        FetcherState result = kalshiFetcher.fetchAndPersist(initialState);

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.SUCCESS);
        assertThat(result.isHasMoreData()).isFalse();
    }

    @Test
    @DisplayName("fetch() - Should handle errors gracefully")
    void testFetchError() throws Exception {
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class)))
            .thenThrow(new RuntimeException("Network error"));

        FetcherState initialState = kalshiFetcher.initialState();
        FetcherState result = kalshiFetcher.fetchAndPersist(initialState);

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.ERROR);
        assertThat(result.getErrorMessage()).contains("Network error");
        assertThat(result.isHasMoreData()).isFalse();

        assertThat(repository.findByDatasource(DataSource.KALSHI)).isEmpty();
    }

    @Test
    @DisplayName("initialState() - Should return valid initial state")
    void testInitialState() {
        FetcherState initialState = kalshiFetcher.initialState();

        assertThat(initialState.getUrl()).isEqualTo("https://api.elections.kalshi.com/trade-api/v2/events");
        assertThat(initialState.isHasMoreData()).isTrue();
        assertThat(initialState.getRequestNumber()).isEqualTo(0);
        assertThat(initialState.getMarketsFetched()).isEqualTo(0);
        assertThat(initialState.getResponseText()).isEqualTo("{\"cursor\":\"\",\"events\":[]}");
    }
}
