package hzpro.com.tradingdesk.arbitrage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.PolymarketAuthorizedClient;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PolymarketTradingService Unit Tests")
class PolymarketTradingServiceTest {

    private PolymarketAuthorizedClient client;
    private PolymarketTradingService service;

    @BeforeEach
    void setUp() {
        ArbitrageConfig config = new ArbitrageConfig();
        config.setPolymarketSignatureType(0);

        client = mock(PolymarketAuthorizedClient.class);
        when(client.eoaAddress()).thenReturn("0x0000000000000000000000000000000000000001");
        when(client.orderVersion()).thenReturn(2);
        when(client.signOrder(
                anyString(), any(), any(), anyInt(), anyString(), anyString(), anyInt(),
                anyString(), anyString(), any(), anyLong()))
                .thenReturn("signature");
        when(client.getApiKey()).thenReturn("api-key");

        service = new PolymarketTradingService(config, client, new ObjectMapper());
    }

    @ParameterizedTest(name = "{0} rounds maker down and taker up")
    @CsvSource({
            "BUY, 370370, 3000000",
            "SELL, 3000000, 370371"
    })
    @DisplayName("placeOrder tailors rounding precision to the input price")
    void placeOrderPreservesPricePrecision(
            String side, String expectedMakerAmount, String expectedTakerAmount) throws Exception {
        AtomicReference<Map<String, Object>> capturedBody = new AtomicReference<>();

        when(client.execute(
                eq(HttpMethod.POST),
                eq("/order"),
                any(),
                any(HttpClientResponseHandler.class)
        )).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = invocation.getArgument(2);
            capturedBody.set(body);

            @SuppressWarnings("unchecked")
            HttpClientResponseHandler<Object> handler = invocation.getArgument(3);
            return handler.handleResponse(mockResponse(
                    201, "{\"success\":true,\"orderID\":\"order-123\",\"status\":\"live\"}"));
        });

        PolymarketTradingService.OrderResult result = service.placeOrder(
                "token-id", new BigDecimal("0.1234567"), 3, side);

        assertThat(result.success()).isTrue();
        assertThat(result.orderId()).isEqualTo("order-123");

        assertThat(capturedBody.get()).isNotNull();
        @SuppressWarnings("unchecked")
        Map<String, Object> order = (Map<String, Object>) capturedBody.get().get("order");
        assertThat(order)
                .containsEntry("makerAmount", expectedMakerAmount)
                .containsEntry("takerAmount", expectedTakerAmount);
    }

    private ClassicHttpResponse mockResponse(int code, String body) throws Exception {
        ClassicHttpResponse response = mock(ClassicHttpResponse.class);
        when(response.getCode()).thenReturn(code);
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenReturn(
                new ByteArrayInputStream(body.getBytes(StandardCharsets.UTF_8)));
        when(response.getEntity()).thenReturn(entity);
        return response;
    }
}
