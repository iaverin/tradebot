package hzpro.com.tradingdesk.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.client.dto.PolymarketPositionDto;
import org.apache.hc.client5.http.classic.methods.HttpGet;
import org.apache.hc.client5.http.impl.classic.CloseableHttpClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class PolymarketDataApiClientPositionsTest {

    private CloseableHttpClient httpClient;
    private PolymarketDataApiClient client;

    @BeforeEach
    void setUp() {
        httpClient = mock(CloseableHttpClient.class);
        ArbitrageConfig config = new ArbitrageConfig();
        config.setPolymarketDepositWalletAddress("0xwallet");
        config.setPolymarketDataApiUrl("https://data.example");
        client = new PolymarketDataApiClient(
                httpClient,
                new ObjectMapper().findAndRegisterModules(),
                config);
    }

    @Test
    @SuppressWarnings("unchecked")
    void fetchesEveryOffsetPageAndPreservesDecimals() throws Exception {
        AtomicInteger requestNumber = new AtomicInteger();
        when(httpClient.execute(any(HttpGet.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    HttpGet request = invocation.getArgument(0);
                    HttpClientResponseHandler<List<PolymarketPositionDto>> handler = invocation.getArgument(1);
                    int number = requestNumber.getAndIncrement();
                    if (number == 0) {
                        assertThat(request.getUri().getQuery()).contains(
                                "offset=0", "limit=500", "sizeThreshold=0", "includeArchived=true");
                        return handler.handleResponse(response(200, positionsJson(500)));
                    }
                    assertThat(request.getUri().getQuery()).contains("offset=500");
                    return handler.handleResponse(response(200, positionsJson(1)));
                });

        List<PolymarketPositionDto> positions = client.getCurrentPositions();

        assertThat(positions).hasSize(501);
        assertThat(positions.getFirst().size()).isEqualByComparingTo("1.2500000001");
        assertThat(positions.getFirst().grossInitialValue()).isEqualByComparingTo("0.625");
        assertThat(requestNumber).hasValue(2);
    }

    @Test
    @SuppressWarnings("unchecked")
    void returnsNullWhenAnIntermediatePageFails() throws Exception {
        AtomicInteger requestNumber = new AtomicInteger();
        when(httpClient.execute(any(HttpGet.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    HttpClientResponseHandler<List<PolymarketPositionDto>> handler = invocation.getArgument(1);
                    if (requestNumber.getAndIncrement() == 0) {
                        return handler.handleResponse(response(200, positionsJson(500)));
                    }
                    return handler.handleResponse(response(503, "{}"));
                });

        assertThat(client.getCurrentPositions()).isNull();
    }

    @Test
    @SuppressWarnings("unchecked")
    void distinguishesSuccessfulEmptySnapshot() throws Exception {
        when(httpClient.execute(any(HttpGet.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    HttpClientResponseHandler<List<PolymarketPositionDto>> handler = invocation.getArgument(1);
                    return handler.handleResponse(response(200, "[]"));
                });

        assertThat(client.getCurrentPositions()).isEmpty();
    }

    @Test
    @SuppressWarnings("unchecked")
    void failsInsteadOfTruncatingAtTheApiOffsetCeiling() throws Exception {
        AtomicInteger requests = new AtomicInteger();
        PolymarketPositionDto item = new PolymarketPositionDto(
                "asset", "condition", BigDecimal.ONE, BigDecimal.ONE,
                BigDecimal.ONE, BigDecimal.ONE, BigDecimal.ONE,
                "title", "slug", "event", "Yes");
        when(httpClient.execute(any(HttpGet.class), any(HttpClientResponseHandler.class)))
                .thenAnswer(invocation -> {
                    requests.incrementAndGet();
                    return java.util.Collections.nCopies(500, item);
                });

        assertThat(client.getCurrentPositions()).isNull();
        assertThat(requests).hasValue(21);
    }

    private String positionsJson(int count) {
        StringBuilder json = new StringBuilder("[");
        for (int index = 0; index < count; index++) {
            if (index > 0) {
                json.append(',');
            }
            json.append("""
                    {"asset":"asset-%d","conditionId":"condition-%d","size":"1.2500000001",
                     "grossInitialValue":"0.625","currentValue":"0.75","slug":"market-%d",
                     "eventSlug":"event-%d","title":"Market %d","outcome":"Yes"}
                    """.formatted(index, index, index, index, index));
        }
        return json.append(']').toString();
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
