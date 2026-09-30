package hzpro.com.tradingdesk.arbitrage.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.PolymarketAuthorizedClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

@Slf4j
@Service
public class PolymarketTradingService {

    private static final String ZERO_ADDRESS = "0x0000000000000000000000000000000000000000";
    private static final BigDecimal SCALE = new BigDecimal("1000000"); // 6 decimals (USDC / CTF shares)
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final int POLY_1271 = 3;
    private static final String BUY = "BUY";
    private static final String SELL = "SELL";

    /**
     * Rounding precision used by the Polymarket order-amount algorithm. Amount precision is two
     * decimal places greater than price precision because order size supports two decimal places.
     */
    private record RoundConfig(int price, int size, int amount) {}

    // CTF Exchange contracts on Polygon (chainId 137).
    private static final String EXCHANGE_V2 = "0xE111180000d2663C0091e4f400237545B87B996B";
    private static final String NEG_RISK_EXCHANGE_V2 = "0xe2222d279d744050d28e00520010520000310F59";
    private static final String EXCHANGE_V3 = "0xe3333700cA9d93003F00f0F71f8515005F6c00Aa";

    private final ArbitrageConfig config;
    private final PolymarketAuthorizedClient polymarketClient;
    private final ObjectMapper objectMapper;

    public PolymarketTradingService(ArbitrageConfig config,
                                     PolymarketAuthorizedClient polymarketClient,
                                     ObjectMapper objectMapper) {
        this.config = config;
        this.polymarketClient = polymarketClient;
        this.objectMapper = objectMapper;
    }

    public record OrderResult(boolean success, String orderId, String status, String error) {}
    public record OrderStatus(String orderId, String status, String filledAmount, String error) {}

    /**
     * Places a limit order on Polymarket CLOB.
     *
     * @param tokenId  the CTF token ID
     * @param price    price per share (USD, e.g. 0.55)
     * @param size     number of shares to trade (whole CTF tokens)
     * @param side     "BUY" or "SELL"
     */
    public OrderResult placeOrder(String tokenId, BigDecimal price, long size, String side) {
        try {
            RoundConfig roundConfig = roundConfigFor(price);
            boolean buy = BUY.equalsIgnoreCase(side);
            GetOrderAmountsResult amounts = getOrderAmounts(
                    buy, BigDecimal.valueOf(size), price, roundConfig);

            BigInteger makerAmount = amounts.makerAmount();
            BigInteger takerAmount = amounts.takerAmount();
            int sideInt = buy ? 0 : 1;

            int signatureType = config.getPolymarketSignatureType();
            String eoa = polymarketClient.eoaAddress();
            // maker is the funder; for POLY_1271 the signer field is the maker (the smart-contract
            // wallet), otherwise it is the EOA that produced the ECDSA signature.
            String maker = hasText(config.getPolymarketDepositWalletAddress())
                    ? config.getPolymarketDepositWalletAddress() : eoa;
            String signer = signatureType == POLY_1271 ? maker : eoa;

            BigInteger salt = BigInteger.valueOf(Math.abs(RANDOM.nextLong() % 1_000_000_000_000L));
            long timestampMillis = Instant.now().toEpochMilli();
            long expiration = 0;     // 0 = no expiry for GTC orders

            // Exchange contract and domain version from the CLOB.
            boolean negRisk = polymarketClient.isNegRisk(tokenId);
            int orderVersion = polymarketClient.orderVersion();
            String verifyingContract;
            String domainVersion;
            if (orderVersion >= 3) {
                verifyingContract = EXCHANGE_V3;
                domainVersion = "3";
            } else {
                verifyingContract = negRisk ? NEG_RISK_EXCHANGE_V2 : EXCHANGE_V2;
                domainVersion = "2";
            }

            // EIP-712 signing delegated to the authorised client.
            String signature = polymarketClient.signOrder(
                    tokenId, makerAmount, takerAmount, sideInt,
                    maker, signer, signatureType,
                    verifyingContract, domainVersion,
                    salt, timestampMillis
            );

            Map<String, Object> order = new LinkedHashMap<>();
            order.put("salt", salt);
            order.put("maker", maker);
            order.put("signer", signer);
            order.put("taker", ZERO_ADDRESS);
            order.put("tokenId", tokenId);
            order.put("makerAmount", makerAmount.toString());
            order.put("takerAmount", takerAmount.toString());
            order.put("side", buy ? BUY : SELL);
            order.put("signatureType", signatureType);
            order.put("timestamp", String.valueOf(timestampMillis));
            order.put("expiration", String.valueOf(expiration));
            order.put("metadata", "0x0000000000000000000000000000000000000000000000000000000000000000");
            order.put("builder", "0x0000000000000000000000000000000000000000000000000000000000000000");
            order.put("signature", signature);

            Map<String, Object> body = new LinkedHashMap<>();
            body.put("deferExec", false);
            body.put("postOnly", false);
            body.put("order", order);
            body.put("owner", polymarketClient.getApiKey());
            body.put("orderType", "GTC");

            OrderResult result = polymarketClient.execute(HttpMethod.POST, "/order", body, response -> {
                int code = response.getCode();
                String responseBody = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
                if (code == 200 || code == 201) {
                    JsonNode json = objectMapper.readTree(responseBody);
                    return new OrderResult(
                            json.path("success").asBoolean(),
                            json.path("orderID").asText(),
                            json.path("status").asText(),
                            null
                    );
                }
                return new OrderResult(false, null, null, "HTTP " + code + ": " + responseBody);
            });
            return result != null ? result : new OrderResult(false, null, null, "Request failed");
        } catch (Exception e) {
            log.error("Polymarket order failed: {}", e.getMessage());
            return new OrderResult(false, null, null, e.getMessage());
        }
    }

    /** Computes maker/taker amounts in 6-decimal base units without reducing input price precision. */
    private record GetOrderAmountsResult(BigInteger makerAmount, BigInteger takerAmount) {}

    private static RoundConfig roundConfigFor(BigDecimal price) {
        int priceDecimals = Math.max(0, price.scale());
        int sizeDecimals = 2;
        return new RoundConfig(priceDecimals, sizeDecimals, priceDecimals + sizeDecimals);
    }

    private GetOrderAmountsResult getOrderAmounts(
            boolean buy, BigDecimal size, BigDecimal price, RoundConfig rc) {
        BigDecimal rawPrice = roundNormal(price, rc.price());

        BigDecimal makerAmount;
        BigDecimal takerAmount;
        if (buy) {
            takerAmount = roundDown(size, rc.size());
            makerAmount = roundMakerTakerProduct(takerAmount, rawPrice, rc.amount());
        } else {
            makerAmount = roundDown(size, rc.size());
            takerAmount = roundMakerTakerProduct(makerAmount, rawPrice, rc.amount());
        }

        return new GetOrderAmountsResult(
                toTokenDecimals(makerAmount, RoundingMode.FLOOR),
                toTokenDecimals(takerAmount, RoundingMode.CEILING));
    }

    /** Rounds size × price using the existing guard-digit and amount-precision rules. */
    private BigDecimal roundMakerTakerProduct(
            BigDecimal size, BigDecimal price, int amountDecimals) {
        BigDecimal product = size.multiply(price);
        if (decimalPlaces(product) > amountDecimals) {
            product = roundUp(product, amountDecimals + 4);
            if (decimalPlaces(product) > amountDecimals) {
                product = roundDown(product, amountDecimals);
            }
        }
        return product;
    }

    private static BigDecimal roundDown(BigDecimal value, int decimals) {
        return value.setScale(decimals, RoundingMode.FLOOR);
    }

    private static BigDecimal roundNormal(BigDecimal value, int decimals) {
        return value.setScale(decimals, RoundingMode.HALF_UP);
    }

    private static BigDecimal roundUp(BigDecimal value, int decimals) {
        return value.setScale(decimals, RoundingMode.CEILING);
    }

    /** Number of fractional digits, ignoring trailing zeros. */
    private static int decimalPlaces(BigDecimal value) {
        return Math.max(0, value.stripTrailingZeros().scale());
    }

    /** Multiply by 10^6 and convert to an integer using the requested amount-side rounding. */
    private static BigInteger toTokenDecimals(BigDecimal amount, RoundingMode roundingMode) {
        return amount.multiply(SCALE).setScale(0, roundingMode).toBigIntegerExact();
    }

    public OrderStatus getOrderStatus(String orderId) {
        try {
            return polymarketClient.execute(HttpMethod.GET, "/data/order/" + orderId, response -> {
                if (response.getCode() == 200) {
                    JsonNode json = objectMapper.readTree(response.getEntity().getContent());
                    return new OrderStatus(
                            json.path("id").asText(orderId),
                            json.path("status").asText(),
                            json.path("size_matched").asText("0"),
                            null
                    );
                }
                return new OrderStatus(orderId, "error", "0", "HTTP " + response.getCode());
            });
        } catch (Exception e) {
            log.warn("Failed to get Polymarket order status for {}: {}", orderId, e.getMessage());
            return new OrderStatus(orderId, "error", "0", e.getMessage());
        }
    }

    public boolean cancelOrder(String orderId) {
        try {
            Boolean result = polymarketClient.execute(HttpMethod.DELETE, "/order",
                    Map.of("orderID", orderId),
                    response -> response.getCode() == 200);
            return result != null && result;
        } catch (Exception e) {
            log.warn("Failed to cancel Polymarket order {}: {}", orderId, e.getMessage());
            return false;
        }
    }

    /**
     * Returns the checksummed EOA address derived from the configured private key.
     */
    public String eoaAddress() {
        return polymarketClient.eoaAddress();
    }

    private static boolean hasText(String s) {
        return s != null && !s.isBlank();
    }
}
