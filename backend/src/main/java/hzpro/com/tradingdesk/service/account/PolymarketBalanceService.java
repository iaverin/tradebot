package hzpro.com.tradingdesk.service.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.PolymarketAuthorizedClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;

/**
 * Fetches the Polymarket USDC collateral balance via {@code GET /balance-allowance}.
 * The CLOB returns the balance in 6-decimal base units; here it is scaled back to
 * whole USDC.
 */
@Slf4j
@Service
public class PolymarketBalanceService {

    private static final BigDecimal SCALE = new BigDecimal("1000000"); // 6 decimals (USDC / CTF shares)

    private final PolymarketAuthorizedClient polymarketClient;
    private final ObjectMapper objectMapper;

    public PolymarketBalanceService(PolymarketAuthorizedClient polymarketClient,
                                     ObjectMapper objectMapper) {
        this.polymarketClient = polymarketClient;
        this.objectMapper = objectMapper;
    }

    public record Balance(BigDecimal balance) {}

    public Balance getBalance() {
        try {
            String path = "/balance-allowance?asset_type=COLLATERAL&signature_type="
                    + polymarketClient.getSignatureType();

            Balance result = polymarketClient.execute(HttpMethod.GET, path, response -> {
                String responseBody = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                if (response.getCode() == 200) {
                    JsonNode json = objectMapper.readTree(responseBody);
                    BigInteger base = new BigInteger(json.path("balance").asText("0"));
                    return new Balance(new BigDecimal(base).divide(SCALE));
                }
                throw new RuntimeException("HTTP " + response.getCode() + ": " + responseBody);
            });
            if (result == null) {
                throw new RuntimeException("Failed to get Polymarket balance: request returned null");
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to get Polymarket balance: {}", e.getMessage());
            throw new RuntimeException("Failed to get Polymarket balance: " + e.getMessage(), e);
        }
    }
}
