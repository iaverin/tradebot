package hzpro.com.tradingdesk.arbitrage.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.KalshiAuthorizedClient;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("KalshiTradingService Unit Tests")
class KalshiTradingServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KalshiAuthorizedClient client;
    private KalshiTradingService service;

    @BeforeEach
    void setUp() {
        client = mock(KalshiAuthorizedClient.class);
        service = new KalshiTradingService(client, MAPPER);
    }

    @ParameterizedTest
    @ValueSource(strings = {"YES", "NO"})
    @DisplayName("placeOrder sends correct Kalshi price for each side")
    void placeOrderSendsCorrectPrice(String sideString) throws Exception {
        YesOrNoResult side = YesOrNoResult.valueOf(sideString);
        BigDecimal price = new BigDecimal("0.55");
        int count = 1;
        String ticker = "KXTEST-26JUL30-T2.0";

        BigDecimal expectedKalshiPrice = side == YesOrNoResult.YES
                ? price
                : BigDecimal.ONE.subtract(price);
        String expectedPriceString = expectedKalshiPrice.setScale(4, RoundingMode.HALF_UP).toPlainString();
        String expectedSideString = side == YesOrNoResult.YES ? "bid" : "ask";

        var capturedBody = new AtomicReference<Map<String, Object>>();

        when(client.execute(
                eq(HttpMethod.POST),
                eq("/portfolio/events/orders"),
                any(),
                any(HttpClientResponseHandler.class)
        )).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> body = inv.getArgument(2);
            capturedBody.set(body);
            ClassicHttpResponse resp = mockResponse(201, "{\"order_id\":\"test-order-123\"}");
            @SuppressWarnings("unchecked")
            HttpClientResponseHandler<Object> handler = inv.getArgument(3);
            return handler.handleResponse(resp);
        });

        var result = service.placeOrder(ticker, side, count, price);

        assertThat(result.success()).isTrue();
        assertThat(result.orderId()).isEqualTo("test-order-123");

        assertThat(capturedBody.get())
                .isNotNull()
                .containsEntry("price", expectedPriceString)
                .containsEntry("ticker", ticker)
                .containsEntry("side", expectedSideString)
                .containsEntry("count", String.valueOf(count));
    }

    private ClassicHttpResponse mockResponse(int code, String body) throws Exception {
        ClassicHttpResponse resp = mock(ClassicHttpResponse.class);
        when(resp.getCode()).thenReturn(code);
        HttpEntity entity = mock(HttpEntity.class);
        when(entity.getContent()).thenReturn(new ByteArrayInputStream(body.getBytes()));
        when(resp.getEntity()).thenReturn(entity);
        return resp;
    }
}
