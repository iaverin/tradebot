package hzpro.com.tradingdesk.service.account;

import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.PolymarketAuthorizedClient;
import hzpro.com.tradingdesk.client.PolymarketDataApiClient;
import hzpro.com.tradingdesk.client.dto.PolymarketClosedPositionDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioVenueDto;
import org.apache.hc.core5.http.ClassicHttpResponse;
import org.apache.hc.core5.http.HttpEntity;
import org.apache.hc.core5.http.io.HttpClientResponseHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.math.BigDecimal;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@DisplayName("PolymarketPortfolioService Unit Tests")
class PolymarketPortfolioServiceTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private PolymarketAuthorizedClient clobClient;
    private PolymarketDataApiClient dataClient;
    private ArbitrageConfig config;
    private PolymarketPortfolioService service;

    @BeforeEach
    void setUp() {
        clobClient = mock(PolymarketAuthorizedClient.class);
        dataClient = mock(PolymarketDataApiClient.class);
        config = new ArbitrageConfig();
        config.setPolymarketSignatureType(3);
        service = new PolymarketPortfolioService(clobClient, dataClient, MAPPER, config);
    }

    @Test
    @DisplayName("getPortfolio returns all indicators when all API calls succeed")
    void returnsAllIndicators() throws Exception {
        // Balance: 1234567890 / 1e6 = 1234.567890
        mockBalanceAllowance("1234567890");
        // Orders: two LIVE orders
        mockOrders("""
            {"data":[
              {"status":"LIVE","original_size":"100","size_matched":"25","price":"0.55"},
              {"status":"LIVE","original_size":"50","size_matched":"0","price":"0.30"}
            ]}""");
        // Portfolio value = 2000.00
        when(dataClient.getPortfolioValue()).thenReturn(new BigDecimal("2000.00"));
        // Closed positions
        when(dataClient.getClosedPositions()).thenReturn(List.of(
                new PolymarketClosedPositionDto("c1", "a1", "Market 1",
                        BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("150.00"), BigDecimal.ONE, 1700000000L, "Yes"),
                new PolymarketClosedPositionDto("c2", "a2", "Market 2",
                        BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("-45.00"), BigDecimal.ONE, 1700000000L, "No"),
                new PolymarketClosedPositionDto("c3", "a3", "Market 3",
                        BigDecimal.ONE, BigDecimal.TEN, new BigDecimal("200.00"), BigDecimal.ONE, 1700000000L, "Yes")
        ));

        PortfolioVenueDto result = service.getPortfolio();

        // In Cash = 1234567890 / 1000000 = 1234.567890
        assertThat(result.inCashUsd()).isEqualByComparingTo("1234.567890");

        // In Orders:
        //   Order 1: (100000000 - 25000000) / 1e6 * 0.55 = 75 * 0.55 = 41.25
        //   Order 2: (50000000 - 0) / 1e6 * 0.30 = 50 * 0.30 = 15.00
        //   Total = 56.25
        assertThat(result.inOrdersUsd()).isEqualByComparingTo("56.25");

        // In Assets = 2000.00
        assertThat(result.inAssetsUsd()).isEqualByComparingTo("2000.00");

        // Total = 1234.567890 + 2000 = 3234.567890
        assertThat(result.totalPortfolioUsd()).isEqualByComparingTo("3234.567890");

        // Resolved Profit = 150 + 200 = 350
        assertThat(result.resolvedProfit()).isEqualByComparingTo("350.00");

        // Resolved Loss = | -45 | = 45
        assertThat(result.resolvedLoss()).isEqualByComparingTo("45.00");
    }

    @Test
    @DisplayName("totalPortfolio is null when balance fails")
    void totalPortfolioNullWhenBalanceFails() throws Exception {
        // Balance fails
        when(clobClient.execute(eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.startsWith("/balance-allowance"),
                any(HttpClientResponseHandler.class))).thenReturn(null);
        mockOrders("{\"data\":[]}");
        when(dataClient.getPortfolioValue()).thenReturn(new BigDecimal("2000.00"));
        when(dataClient.getClosedPositions()).thenReturn(List.of());

        PortfolioVenueDto result = service.getPortfolio();

        assertThat(result.inCashUsd()).isNull();
        assertThat(result.inAssetsUsd()).isEqualByComparingTo("2000.00");
        assertThat(result.totalPortfolioUsd()).isNull();
    }

    @Test
    @DisplayName("empty orders and positions return zeros")
    void emptyDataReturnsZero() throws Exception {
        mockBalanceAllowance("5000000");
        mockOrders("{\"data\":[]}");
        when(dataClient.getPortfolioValue()).thenReturn(BigDecimal.ZERO);
        when(dataClient.getClosedPositions()).thenReturn(List.of());

        PortfolioVenueDto result = service.getPortfolio();

        assertThat(result.inOrdersUsd()).isEqualByComparingTo("0.00");
        assertThat(result.resolvedProfit()).isEqualByComparingTo("0.00");
        assertThat(result.resolvedLoss()).isEqualByComparingTo("0.00");
    }

    // ---- helpers ----

    @SuppressWarnings("unchecked")
    private void mockBalanceAllowance(String balance) throws Exception {
        String json = String.format("{\"balance\":\"%s\"}", balance);
        ClassicHttpResponse resp = mockResponse(200, json);
        when(clobClient.execute(eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.startsWith("/balance-allowance"),
                any(HttpClientResponseHandler.class)))
                .thenAnswer(inv -> {
                    HttpClientResponseHandler<Object> handler = inv.getArgument(2);
                    return handler.handleResponse(resp);
                });
    }

    @SuppressWarnings("unchecked")
    private void mockOrders(String json) throws Exception {
        ClassicHttpResponse resp = mockResponse(200, json);
        when(clobClient.execute(eq(HttpMethod.GET),
                org.mockito.ArgumentMatchers.startsWith("/data/orders"),
                any(HttpClientResponseHandler.class)))
                .thenAnswer(inv -> {
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
