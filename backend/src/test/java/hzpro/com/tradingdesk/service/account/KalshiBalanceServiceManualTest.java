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
class KalshiBalanceServiceManualTest {

    @Autowired
    private KalshiBalanceService kalshiBalanceService;

    @Test
    void fetchBalanceActualRequest() {
        var balance = kalshiBalanceService.getBalance();

        assertNotNull(balance);

        System.out.println("Kalshi Balance:");
        System.out.println("  Cents: " + balance.balanceCents());
        System.out.println("  Dollars: $" + balance.balanceDollars());
    }
}
