package hzpro.com.tradingdesk.marketsfetcher.service.fetchers;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.marketsfetcher.lib.FetcherState;
import hzpro.com.tradingdesk.marketsfetcher.mapper.OpinionMapper;
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
@DisplayName("OpinionFetcher Tests")
class OpinionFetcherTest {

    @MockitoBean
    private CloseableHttpClient httpClient;

    @Autowired
    private PredictionMarketRepository repository;

    @Autowired
    private OpinionFetcher opinionFetcher;

    private ClassicHttpResponse httpResponse;
    private ObjectMapper objectMapper;
    private String listJson;
    private List<PredictionMarket> marketsFromJson;

    /** Wrap the bare list fixture in the opinion.trade envelope with the given total. */
    private String envelope(String list, long total) {
        return "{\"errno\":0,\"errmsg\":\"\",\"result\":{\"total\":" + total + ",\"list\":" + list + "}}";
    }

    @BeforeEach
    void setUp() throws Exception {
        objectMapper = new ObjectMapper().findAndRegisterModules();
        httpResponse = mock(ClassicHttpResponse.class);
        repository.deleteAll();
        listJson = Files.readString(
                Paths.get(getClass().getClassLoader().getResource("json/opinion_markets.json").toURI()));
        marketsFromJson = new OpinionMapper(objectMapper).mapToPredictionMarketList(listJson);
    }

    private void mockHttp(String body) throws Exception {
        when(httpResponse.getCode()).thenReturn(200);
        when(httpResponse.getEntity()).thenAnswer(inv -> new StringEntity(body));
        when(httpClient.execute(any(), any(HttpClientResponseHandler.class))).thenAnswer(invocation -> {
            HttpClientResponseHandler<?> handler = invocation.getArgument(1);
            return handler.handleResponse(httpResponse);
        });
    }

    @Test
    @DisplayName("fetch() - persists rows and expands categorical into one row per child")
    void testFetchOnlyYesOrNoSuccess() throws Exception {
        // Fixture = 1 categorical (3 children) + 1 binary => 4 mapped rows.
        mockHttp(envelope(listJson, 4));

        FetcherState result = opinionFetcher.fetchAndPersist(opinionFetcher.initialState());

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.SUCCESS);
        assertThat(result.getMarketsFetched()).isEqualTo(marketsFromJson.size());
        assertThat(result.getMarketsFetched()).isEqualTo(3);

        List<PredictionMarket> saved = repository.findByDatasource(DataSource.OPINION);
        assertThat(saved).hasSize(3);
        assertThat(saved).allMatch(m -> m.getDatasource() == DataSource.OPINION);
        assertThat(saved).allMatch(m -> m.getEventId() != null && m.getMarketTicker() != null
                && m.getMarketTitle() != null);
        // createdAt is epoch seconds -> a sane modern year, not 1970 (which a millis read would give).
        assertThat(saved).filteredOn(m -> m.getMarketOpenDatetime() != null)
                .allMatch(m -> m.getMarketOpenDatetime().getYear() >= 2024);
    }

    @Test
    @DisplayName("hasMoreData() - stops once page*limit reaches total")
    void testPaginationTerminates() throws Exception {
        // First page: total=30, limit=20, page 1 -> 20 < 30 -> more.
        mockHttp(envelope(listJson, 30));
        FetcherState page1 = opinionFetcher.fetchAndPersist(opinionFetcher.initialState());
        assertThat(page1.isHasMoreData()).isTrue();
        assertThat(page1.getRequestNumber()).isEqualTo(1);

        // Second page (requestNumber=1 -> page 2): 2*20=40 >= 30 -> stop.
        FetcherState page2 = opinionFetcher.fetchAndPersist(page1);
        assertThat(page2.isHasMoreData()).isFalse();
    }

    @Test
    @DisplayName("fetch() - empty list yields no rows and no more data")
    void testEmptyList() throws Exception {
        mockHttp(envelope("[]", 0));

        FetcherState result = opinionFetcher.fetchAndPersist(opinionFetcher.initialState());

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.SUCCESS);
        assertThat(result.getMarketsFetched()).isEqualTo(0);
        assertThat(result.isHasMoreData()).isFalse();
        assertThat(repository.findByDatasource(DataSource.OPINION)).isEmpty();
    }

    @Test
    @DisplayName("parseResponse() - non-zero errno yields no rows")
    void testErrnoGuard() throws Exception {
        mockHttp("{\"errno\":401,\"errmsg\":\"unauthorized\",\"result\":null}");

        FetcherState result = opinionFetcher.fetchAndPersist(opinionFetcher.initialState());

        assertThat(result.getFetchStatus()).isEqualTo(FetcherState.FetchStatus.SUCCESS);
        assertThat(result.getMarketsFetched()).isEqualTo(0);
        assertThat(repository.findByDatasource(DataSource.OPINION)).isEmpty();
    }

    @Test
    @DisplayName("prepareRequest() - sends activated/all params, apikey header, page from requestNumber")
    void testPrepareRequestParams() throws Exception {
        mockHttp(envelope(listJson, 4));

        FetcherState result = opinionFetcher.fetchAndPersist(opinionFetcher.initialState());

        assertThat(result.getRequestQueryParameters())
                .containsEntry("status", "activated")
                .containsEntry("marketType", 2)
                .containsEntry("limit", 20)
                .containsEntry("page", 1);
    }

    @Test
    @DisplayName("initialState() - targets the /market endpoint")
    void testInitialState() {
        FetcherState initialState = opinionFetcher.initialState();

        assertThat(initialState.getUrl()).endsWith("/market");
        assertThat(initialState.isHasMoreData()).isTrue();
        assertThat(initialState.getRequestNumber()).isEqualTo(0);
        assertThat(initialState.getMarketsFetched()).isEqualTo(0);
    }
}
