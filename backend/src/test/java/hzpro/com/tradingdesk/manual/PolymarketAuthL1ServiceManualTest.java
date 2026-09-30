package hzpro.com.tradingdesk.manual;

import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.arbitrage.service.PolymarketAuthL1Service;
import hzpro.com.tradingdesk.arbitrage.service.PolymarketAuthL1Service.ApiCredentials;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

@Tag("manual")
@SpringBootTest
@ActiveProfiles("test")
class PolymarketAuthL1ServiceManualTest {

    @Autowired
    private PolymarketAuthL1Service authL1Service;

    @Autowired
    private ArbitrageConfig config;

    @Test
    void createApiKeyActualRequest() {
        String privateKey = config.getPolymarketPrivateKey();
        long chainId = config.getPolymarketChainId();

        var credentials = authL1Service.createOrDeriveApiKey(privateKey, chainId);

        assertNotNull(credentials);

        System.out.println("API Credentials Created:");
        System.out.println("  API Key: " + credentials.apiKey());
        System.out.println("  Secret: " + credentials.secret());
        System.out.println("  Passphrase: " + credentials.passphrase());


    }
}