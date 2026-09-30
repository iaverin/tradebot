package hzpro.com.tradingdesk.manual;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.service.PolymarketTradingService;

@Tag("manual")
@SpringBootTest
@ActiveProfiles("test")
class PolymarketTradingServiceManualTest {

    @Autowired
    private PolymarketTradingService tradingService;

    @Autowired
    private ArbitrageConfig config;

    @Test
    void placeOrder_actualRequest() {

        // market ticker will-trump-be-impeached-before-his-term-ends
        // yes token
        // String tokenId = "99020283867209289889119738709824148614345922887757122199903300146152690995270";

        // no token
        String tokenId = "31627048520492227354616069874124458767757243686332730237616510640600462926383";

        BigDecimal price = new BigDecimal("0.01");
        long size = 6;
        String side = "BUY";

        var result = tradingService.placeOrder(tokenId, price, size, side);

        System.out.println("Order Result:");
        System.out.println("  Success: " + result.success());
        System.out.println("  Order ID: " + result.orderId());
        System.out.println("  Status: " + result.status());
        System.out.println("  Error: " + result.error());


        if (result.success() && result.orderId() != null) {
            var status = tradingService.getOrderStatus(result.orderId());
            System.out.println("Order Status:");
            System.out.println("  Order ID: " + status.orderId());
            System.out.println("  Status: " + status.status());
            System.out.println("  Filled: " + status.filledAmount());
        }
        assertNotNull(result.orderId());

        assertTrue(tradingService.cancelOrder(result.orderId()));

    }

    @Test
    void getOrderStatus_actualRequest() {

        String orderId = "0xf42c44ebf6200ad2381e5f2843c0b578170366c5ef5b0b26cf070a8071f863ff";

        var status = tradingService.getOrderStatus(orderId);

        System.out.println("Order Status:");
        System.out.println("  Order ID: " + status.orderId());
        System.out.println("  Status: " + status.status());
        System.out.println("  Filled: " + status.filledAmount());
        System.out.println("  Error: " + status.error());
    }

}