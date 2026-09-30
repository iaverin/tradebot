package hzpro.com.tradingdesk.service.account;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import static org.junit.jupiter.api.Assertions.assertNotNull;

@Tag("manual")
@SpringBootTest
@ActiveProfiles("test")
class PolymarketBalanceServiceManualTest {

    @Autowired
    private PolymarketBalanceService polymarketBalanceService;

    @Test
    void fetchBalanceActualRequest() {
        var balance = polymarketBalanceService.getBalance();

        assertNotNull(balance);

        System.out.println("Polymarket Balance:");
        System.out.println("  USDC: " + balance.balance());
    }
}
