package hzpro.com.tradingdesk.arbitrage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.KalshiAuthorizedClient;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;

@Slf4j
@Service
public class KalshiTradingService {

    private final KalshiAuthorizedClient kalshiAuthorizedClient;
    private final ObjectMapper objectMapper;

    public KalshiTradingService(KalshiAuthorizedClient kalshiAuthorizedClient,
                                ObjectMapper objectMapper) {
        this.kalshiAuthorizedClient = kalshiAuthorizedClient;
        this.objectMapper = objectMapper;
    }

    public record OrderResult(boolean success, String orderId, String status, String error) {}
    public record OrderStatus(String orderId, String status, String filledCount, String remainingCount) {}

    public OrderResult placeOrder(String ticker, YesOrNoResult side, int count, BigDecimal price) {
        try {
            String path = "/portfolio/events/orders";

            BigDecimal priceKalshi;
            String sideKalshi;

            if (side.equals(YesOrNoResult.YES) ) {
                priceKalshi = price;
                sideKalshi = "bid" ;
            } else {
                priceKalshi = BigDecimal.ONE.subtract(price);
                sideKalshi = "ask" ;
            }

            String priceFixedPoint = priceKalshi.setScale(4, RoundingMode.HALF_UP).toPlainString();

            Map<String, Object> body = Map.of(
                    "ticker", ticker,
                    "side", sideKalshi,
                    "count", String.valueOf(count),
                    "price", priceFixedPoint,
                    "time_in_force", "good_till_canceled",
                    "self_trade_prevention_type", "taker_at_cross"
            );

            OrderResult result = kalshiAuthorizedClient.execute(HttpMethod.POST, path, body, response -> {
                if (response.getCode() == 201) {
                    JsonNode json = objectMapper.readTree(response.getEntity().getContent());
                    return new OrderResult(true, json.path("order_id").asText(),
                            json.path("status").asText(), null);
                }
                String errorBody = new String(response.getEntity().getContent().readAllBytes());
                return new OrderResult(false, null, null, "HTTP " + response.getCode() + ": " + errorBody);
            });
            return result != null ? result : new OrderResult(false, null, null, "Request failed");
        } catch (Exception e) {
            log.error("Kalshi order failed: {}", e.getMessage());
            return new OrderResult(false, null, null, e.getMessage());
        }
    }

    public OrderStatus getOrderStatus(String orderId) {
        try {
            return kalshiAuthorizedClient.execute(HttpMethod.GET, "/portfolio/orders/" + orderId, response -> {
                if (response.getCode() == 200) {
                    JsonNode json = objectMapper.readTree(response.getEntity().getContent());
                    JsonNode order = json.path("order");
                    return new OrderStatus(
                            order.path("order_id").asText(),
                            order.path("status").asText(),
                            order.path("filled_count_fp").asText(),
                            order.path("remaining_count_fp").asText()
                    );
                }
                return null;
            });
        } catch (Exception e) {
            log.warn("Failed to get Kalshi order status for {}: {}", orderId, e.getMessage());
            return null;
        }
    }

    public boolean cancelOrder(String orderId) {
        try {
            Boolean result = kalshiAuthorizedClient.execute(HttpMethod.DELETE,
                    "/portfolio/events/orders/" + orderId,
                    response -> response.getCode() == 200);
            return result != null && result;
        } catch (Exception e) {
            log.warn("Failed to cancel Kalshi order {}: {}", orderId, e.getMessage());
            return false;
        }
    }
}
