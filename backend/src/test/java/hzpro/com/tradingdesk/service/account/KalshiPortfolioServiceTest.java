package hzpro.com.tradingdesk.service.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.KalshiAuthorizedClient;
import hzpro.com.tradingdesk.controller.dto.PortfolioVenueDto;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("KalshiPortfolioService Unit Tests")
class KalshiPortfolioServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private KalshiAuthorizedClient client;
    private KalshiPortfolioService service;

    @BeforeEach
    void setUp() {
        client = mock(KalshiAuthorizedClient.class);
        service = new KalshiPortfolioService(client, MAPPER);
    }

    @Test
    @DisplayName("getPortfolio returns all indicators when all API calls succeed")
    void returnsAllIndicators() throws Exception {
        // Balance: balance=50000 cents ($500), portfolio_value=80000 cents ($800)
        mockBalance(50000, 80000);
        // Orders: two resting orders
        mockOrders("""
            {"orders":[
              {"remaining_count_fp":"10","yes_price_dollars":"0.55","no_price_dollars":"0.45"},
              {"remaining_count_fp":"20","yes_price_dollars":"","no_price_dollars":"0.30"}
            ]}""");
        // Settlements: one profit, one loss
        mockSettlements("""
            {"settlements":[
              {"revenue":10000,"yes_total_cost_dollars":"30.00","no_total_cost_dollars":"10.00","fee_cost":"1.50"},
              {"revenue":5000,"yes_total_cost_dollars":"50.00","no_total_cost_dollars":"20.00","fee_cost":"2.00"}
            ],"cursor":""}""");

        PortfolioVenueDto result = service.getPortfolio();

        // In Cash = 500.00
        assertThat(result.inCashUsd()).isEqualByComparingTo("500.00");
        // In Assets = 800.00
        assertThat(result.inAssetsUsd()).isEqualByComparingTo("800.00");
        // In Orders = 10*0.55 + 20*0.30 = 5.50 + 6.00 = 11.50
        assertThat(result.inOrdersUsd()).isEqualByComparingTo("11.50");
        // Total = 500 + 800 = 1300
        assertThat(result.totalPortfolioUsd()).isEqualByComparingTo("1300");

        // Settlement 1: 10000/100 - 30 - 10 - 1.50 = 100 - 41.50 = 58.50 (profit)
        // Settlement 2: 5000/100 - 50 - 20 - 2.00 = 50 - 72 = -22.00 (loss)
        assertThat(result.resolvedProfit()).isEqualByComparingTo("58.50");
        assertThat(result.resolvedLoss()).isEqualByComparingTo("22.00");
    }

    @Test
    @DisplayName("totalPortfolio is null when inOrders fails")
    void totalPortfolioNullWhenOrdersFails() throws Exception {
        mockBalance(50000, 80000);
        // Orders call fails
        when(client.execute(eq(HttpMethod.GET), eq("/portfolio/orders?status=resting&limit=200"), any()))
                .thenReturn(null);
        mockSettlements("{\"settlements\":[],\"cursor\":\"\"}");

        PortfolioVenueDto result = service.getPortfolio();

        assertThat(result.inCashUsd()).isEqualByComparingTo("500.00");
        assertThat(result.inAssetsUsd()).isEqualByComparingTo("800.00");
        assertThat(result.inOrdersUsd()).isNull();
        assertThat(result.totalPortfolioUsd()).isNull(); // component null → total null
    }

    @Test
    @DisplayName("returns zeros for empty orders and settlements")
    void emptyDataReturnsZero() throws Exception {
        mockBalance(50000, 80000);
        mockOrders("{\"orders\":[]}");
        mockSettlements("{\"settlements\":[],\"cursor\":\"\"}");

        PortfolioVenueDto result = service.getPortfolio();

        assertThat(result.inOrdersUsd()).isEqualByComparingTo("0.00");
        assertThat(result.resolvedProfit()).isEqualByComparingTo("0.00");
        assertThat(result.resolvedLoss()).isEqualByComparingTo("0.00");
        assertThat(result.totalPortfolioUsd()).isEqualByComparingTo("1300.00");
    }

    // ---- helpers to set up mocks ----

    @SuppressWarnings("unchecked")
    private void mockBalance(long balanceCents, long portfolioValueCents) throws Exception {
        String json = String.format(
                "{\"balance\":%d,\"portfolio_value\":%d}", balanceCents, portfolioValueCents);
        when(client.execute(eq(HttpMethod.GET), eq("/portfolio/balance"), any(HttpClientResponseHandler.class)))
                .thenAnswer(inv -> {
                    ClassicHttpResponse resp = mockResponse(200, json);
                    HttpClientResponseHandler<Object> handler = inv.getArgument(2);
                    return handler.handleResponse(resp);
                });
    }

    @SuppressWarnings("unchecked")
    private void mockOrders(String json) throws Exception {
        when(client.execute(eq(HttpMethod.GET), eq("/portfolio/orders?status=resting&limit=200"), any(HttpClientResponseHandler.class)))
                .thenAnswer(inv -> {
                    ClassicHttpResponse resp = mockResponse(200, json);
                    HttpClientResponseHandler<Object> handler = inv.getArgument(2);
                    return handler.handleResponse(resp);
                });
    }

    @SuppressWarnings("unchecked")
    private void mockSettlements(String json) throws Exception {
        when(client.execute(eq(HttpMethod.GET), org.mockito.ArgumentMatchers.startsWith("/portfolio/settlements"), any(HttpClientResponseHandler.class)))
                .thenAnswer(inv -> {
                    ClassicHttpResponse resp = mockResponse(200, json);
                    HttpClientResponseHandler<Object> handler = inv.getArgument(2);
                    return handler.handleResponse(resp);
                });
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
