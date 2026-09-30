package hzpro.com.tradingdesk.service.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.arbitrage.config.ArbitrageConfig;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.PolymarketAuthorizedClient;
import hzpro.com.tradingdesk.client.PolymarketDataApiClient;
import hzpro.com.tradingdesk.client.dto.PolymarketClosedPositionDto;
import hzpro.com.tradingdesk.controller.dto.PortfolioVenueDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.List;

/**
 * Aggregates portfolio indicators from Polymarket (CLOB API + Data API).
 *
 * <p>Each indicator is fetched independently. Failures are logged and the
 * corresponding field is set to {@code null}.
 */
@Slf4j
@Service
public class PolymarketPortfolioService {

    private static final BigDecimal SCALE = new BigDecimal("1000000");

    private final PolymarketAuthorizedClient clobClient;
    private final PolymarketDataApiClient dataClient;
    private final ObjectMapper objectMapper;
    private final ArbitrageConfig config;

    public PolymarketPortfolioService(PolymarketAuthorizedClient clobClient,
                                       PolymarketDataApiClient dataClient,
                                       ObjectMapper objectMapper,
                                       ArbitrageConfig config) {
        this.clobClient = clobClient;
        this.dataClient = dataClient;
        this.objectMapper = objectMapper;
        this.config = config;
    }

    // ---- main entry point ----

    public PortfolioVenueDto getPortfolio() {
        BigDecimal inCash = fetchSafely(this::getInCashUsd, "inCash");
        BigDecimal inOrders = fetchSafely(this::getInOrdersUsd, "inOrders");
        BigDecimal inAssets = fetchSafely(this::getInAssetsUsd, "inAssets");
        BigDecimal resolvedProfit = fetchSafely(this::getResolvedProfit, "resolvedProfit");
        BigDecimal resolvedLoss = fetchSafely(this::getResolvedLoss, "resolvedLoss");

        BigDecimal totalPortfolio = null;
        if (inCash != null && inOrders != null && inAssets != null) {
            totalPortfolio = inCash.add(inAssets);
        }

        return new PortfolioVenueDto(totalPortfolio, inCash, inOrders, inAssets, resolvedProfit, resolvedLoss);
    }

    // ---- individual indicators ----

    BigDecimal getInCashUsd() {
        String path = "/balance-allowance?asset_type=COLLATERAL&signature_type="
                + config.getPolymarketSignatureType();
        return clobClient.execute(HttpMethod.GET, path, response -> {
            if (response.getCode() != 200) {
                log.warn("Polymarket /balance-allowance returned {}", response.getCode());
                return null;
            }
            String body = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(body);
            String balanceStr = json.path("balance").asText("0");
            return new BigDecimal(new BigInteger(balanceStr)).divide(SCALE);
        });
    }

    BigDecimal getInOrdersUsd() {
        return clobClient.execute(HttpMethod.GET, "/data/orders", response -> {
            if (response.getCode() != 200) {
                log.warn("Polymarket /data/orders returned {}", response.getCode());
                return null;
            }
            String body = new String(response.getEntity().getContent().readAllBytes(), StandardCharsets.UTF_8);
            JsonNode json = objectMapper.readTree(body);
            return sumLiveOrderValues(json.path("data"));
        });
    }

    BigDecimal getInAssetsUsd() {
        return dataClient.getPortfolioValue();
    }

    BigDecimal getResolvedProfit() {
        List<PolymarketClosedPositionDto> positions = dataClient.getClosedPositions();
        if (positions == null) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (PolymarketClosedPositionDto p : positions) {
            if (p.realizedPnl() != null && p.realizedPnl().compareTo(BigDecimal.ZERO) > 0) {
                total = total.add(p.realizedPnl());
            }
        }
        return total;
    }

    BigDecimal getResolvedLoss() {
        List<PolymarketClosedPositionDto> positions = dataClient.getClosedPositions();
        if (positions == null) return null;
        BigDecimal total = BigDecimal.ZERO;
        for (PolymarketClosedPositionDto p : positions) {
            if (p.realizedPnl() != null && p.realizedPnl().compareTo(BigDecimal.ZERO) < 0) {
                total = total.add(p.realizedPnl().abs());
            }
        }
        return total;
    }

    // ---- helpers ----

    private BigDecimal fetchSafely(java.util.function.Supplier<BigDecimal> supplier, String name) {
        try {
            BigDecimal value = supplier.get();
            if (value == null) {
                log.warn("Polymarket {} returned null", name);
            }
            return value;
        } catch (Exception e) {
            log.warn("Failed to fetch Polymarket {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Sums the USD value of all LIVE orders.
     * Value = (original_size - size_matched) / 1e6 × price
     */
    private BigDecimal sumLiveOrderValues(JsonNode orders) {
        BigDecimal total = BigDecimal.ZERO;
        if (orders == null || !orders.isArray()) {
            return total;
        }
        for (JsonNode order : orders) {
            String status = order.path("status").asText();
            if (!"LIVE".equals(status)) continue;

            String originalStr = order.path("original_size").asText();
            String matchedStr = order.path("size_matched").asText();
            String priceStr = order.path("price").asText();
            if (originalStr.isEmpty() || priceStr.isEmpty()) continue;

            BigDecimal original = new BigDecimal(originalStr);
            BigDecimal matched = matchedStr.isEmpty() ? BigDecimal.ZERO : new BigDecimal(matchedStr);
            BigDecimal remaining = original.subtract(matched);
            BigDecimal price = new BigDecimal(priceStr);

            BigDecimal value = remaining.multiply(price);
            total = total.add(value);
        }
        return total;
    }
}
