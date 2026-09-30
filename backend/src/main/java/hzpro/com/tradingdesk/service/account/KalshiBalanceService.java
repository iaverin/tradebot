package hzpro.com.tradingdesk.service.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.KalshiAuthorizedClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;

/**
 * Fetches the Kalshi portfolio cash balance via {@code GET /portfolio/balance}.
 * Balance is returned in cents from the API and converted to dollars here.
 */
@Slf4j
@Service
public class KalshiBalanceService {

    private final KalshiAuthorizedClient kalshiAuthorizedClient;
    private final ObjectMapper objectMapper;

    public KalshiBalanceService(KalshiAuthorizedClient kalshiAuthorizedClient,
                                 ObjectMapper objectMapper) {
        this.kalshiAuthorizedClient = kalshiAuthorizedClient;
        this.objectMapper = objectMapper;
    }

    public record KalshiBalance(long balanceCents, BigDecimal balanceDollars) {}

    public KalshiBalance getBalance() {
        try {
            String path = "/portfolio/balance";

            KalshiBalance result = kalshiAuthorizedClient.execute(HttpMethod.GET, path, response -> {
                if (response.getCode() == 200) {
                    JsonNode json = objectMapper.readTree(response.getEntity().getContent());
                    long cents = json.path("balance").asLong();
                    return new KalshiBalance(cents, BigDecimal.valueOf(cents, 2));
                }
                String errorBody = new String(response.getEntity().getContent().readAllBytes());
                throw new RuntimeException("HTTP " + response.getCode() + ": " + errorBody);
            });
            if (result == null) {
                throw new RuntimeException("Failed to get Kalshi balance: request returned null");
            }
            return result;
        } catch (Exception e) {
            log.error("Failed to get Kalshi balance: {}", e.getMessage());
            throw new RuntimeException("Failed to get Kalshi balance: " + e.getMessage(), e);
        }
    }
}
