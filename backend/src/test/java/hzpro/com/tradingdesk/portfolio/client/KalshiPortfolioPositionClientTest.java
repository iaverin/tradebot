package hzpro.com.tradingdesk.portfolio.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.KalshiAuthorizedClient;
import hzpro.com.tradingdesk.client.dto.KalshiMarketPositionDto;
import hzpro.com.tradingdesk.client.dto.KalshiMarketQuoteDto;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class KalshiPortfolioPositionClientTest {

    private KalshiAuthorizedClient authorizedClient;
    private KalshiPortfolioPositionClient client;

    @BeforeEach
    void setUp() {
        authorizedClient = mock(KalshiAuthorizedClient.class);
        client = new KalshiPortfolioPositionClient(
                authorizedClient,
                new ObjectMapper().findAndRegisterModules());
    }

    @Test
    @SuppressWarnings("unchecked")
    void followsCursorAndParsesFixedPointFields() throws Exception {
        when(authorizedClient.execute(eq(HttpMethod.GET), anyString(), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1);
                    HttpClientResponseHandler<Object> handler = invocation.getArgument(2);
                    if (path.contains("cursor=next-cursor")) {
                        return handler.handleResponse(response(200, """
                                {"market_positions":[{"ticker":"KS-2","position_fp":"-3.125",
                                "market_exposure_dollars":"-1.75","last_updated_ts":"2026-09-10T10:15:30Z"}],
                                "cursor":""}
                                """));
                    }
                    assertThat(path).contains("count_filter=position", "limit=1000");
                    return handler.handleResponse(response(200, """
                            {"market_positions":[{"ticker":"KS-1","position_fp":"2.5000000001",
                            "market_exposure_dollars":"1.25"}],"cursor":"next-cursor"}
                            """));
                });

        List<KalshiMarketPositionDto> positions = client.getCurrentPositions();

        assertThat(positions).hasSize(2);
        assertThat(positions.get(0).positionFp()).isEqualByComparingTo("2.5000000001");
        assertThat(positions.get(1).positionFp()).isEqualByComparingTo("-3.125");
        assertThat(positions.get(1).lastUpdatedTs()).hasToString("2026-09-10T10:15:30Z");
    }

    @Test
    @SuppressWarnings("unchecked")
    void parsesOutcomeSpecificDollarBids() throws Exception {
        when(authorizedClient.execute(eq(HttpMethod.GET), anyString(), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1);
                    assertThat(path).contains("/markets?tickers=KS-1,KS-2").doesNotContain("null");
                    HttpClientResponseHandler<Object> handler = invocation.getArgument(2);
                    return handler.handleResponse(response(200, """
                            {"markets":[
                              {"ticker":"KS-1","yes_bid_dollars":"0.61","no_bid_dollars":"0.38"},
                              {"ticker":"KS-2","yes_bid_dollars":"0.27","no_bid_dollars":"0.72"}
                            ],"cursor":""}
                            """));
                });

        Map<String, KalshiMarketQuoteDto> quotes = client.getMarkets(List.of("KS-1", "KS-2"));

        assertThat(quotes.get("KS-1").yesBidDollars()).isEqualByComparingTo("0.61");
        assertThat(quotes.get("KS-2").noBidDollars()).isEqualByComparingTo("0.72");
    }

    @Test
    void returnsNullWhenPositionPageFails() {
        when(authorizedClient.execute(eq(HttpMethod.GET), anyString(), any())).thenReturn(null);

        assertThat(client.getCurrentPositions()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsNullWhenAnIntermediateCursorPageFails() throws Exception {
        when(authorizedClient.execute(eq(HttpMethod.GET), anyString(), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    String path = invocation.getArgument(1);
                    if (path.contains("cursor=next")) {
                        return null;
                    }
                    HttpClientResponseHandler<Object> handler = invocation.getArgument(2);
                    return handler.handleResponse(response(200, """
                            {"market_positions":[],"cursor":"next"}
                            """));
                });

        assertThat(client.getCurrentPositions()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void distinguishesSuccessfulEmptyPositionSnapshot() throws Exception {
        when(authorizedClient.execute(eq(HttpMethod.GET), anyString(), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    HttpClientResponseHandler<Object> handler = invocation.getArgument(2);
                    return handler.handleResponse(response(200, """
                            {"market_positions":[],"cursor":""}
                            """));
                });

        assertThat(client.getCurrentPositions()).isEmpty();
    }

    private ClassicHttpResponse response(int status, String body) throws Exception {
        ClassicHttpResponse response = mock(ClassicHttpResponse.class);
        HttpEntity entity = mock(HttpEntity.class);
        when(response.getCode()).thenReturn(status);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);
        return response;
    }
}
