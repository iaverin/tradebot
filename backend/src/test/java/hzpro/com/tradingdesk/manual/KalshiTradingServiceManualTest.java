package hzpro.com.tradingdesk.manual;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.arbitrage.service.KalshiTradingService;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.KalshiAuthorizedClient;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;

@Tag("manual")
@SpringBootTest
@ActiveProfiles("test")
class KalshiTradingServiceManualTest {

    @Autowired
    private KalshiTradingService tradingService;

    @Autowired
    private KalshiAuthorizedClient kalshiAuthorizedClient;

    @Autowired
    private ObjectMapper objectMapper;


    @ParameterizedTest
    @ValueSource(strings = { "YES", "NO"})
    void placeAndCancelOrder_actualRequest(String sideString) {

        String ticker ="KXATP-26USO-ALC";
        BigDecimal price = new BigDecimal("0.01");
        int count = 1;
        YesOrNoResult side =  YesOrNoResult.valueOf(sideString);

        var result = tradingService.placeOrder(
            ticker,
            side,
            count,
            price
        );

        System.out.println("Order Result:");
        System.out.println("  Success: " + result.success());
        System.out.println("  Order ID: " + result.orderId());
        System.out.println("  Status: " + result.status());
        System.out.println("  Error: " + result.error());

        String priceInOrderStatus = kalshiAuthorizedClient.execute(HttpMethod.GET, "/portfolio/orders/"+result.orderId(), response -> {
            JsonNode json = objectMapper.readTree(response.getEntity().getContent());
            if (side.equals(YesOrNoResult.YES)) {
                return json.path("order").path("yes_price_dollars").asText();
            } else {
                return json.path("order").path("no_price_dollars").asText();
            }
        });

        assertEquals(price, new BigDecimal(priceInOrderStatus).setScale(price.scale()));


        if (result.success() && result.orderId() != null) {
            var status = tradingService.getOrderStatus(result.orderId());
            System.out.println("Order Status:");
            System.out.println("  Order ID: " + status.orderId());
            System.out.println("  Status: " + status.status());
            System.out.println("  Filled: " + status.filledCount());
            assertEquals(result.orderId(), status.orderId());

            boolean isCancelled = tradingService.cancelOrder(result.orderId());
            assertTrue(isCancelled);

        }
        assertNotNull(result.orderId());

    }

}