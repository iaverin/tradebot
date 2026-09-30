package hzpro.com.tradingdesk.service.account;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.KalshiAuthorizedClient;
import hzpro.com.tradingdesk.controller.dto.PortfolioVenueDto;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Aggregates portfolio indicators from the Kalshi API.
 *
 * <p>Each indicator is fetched independently via {@link KalshiAuthorizedClient}.
 * Failures are logged and the corresponding field is set to {@code null}.
 */
@Slf4j
@Service
public class KalshiPortfolioService {

    private static final int SETTLEMENTS_LIMIT = 200;

    private final KalshiAuthorizedClient client;
    private final ObjectMapper objectMapper;

    public KalshiPortfolioService(KalshiAuthorizedClient client, ObjectMapper objectMapper) {
        this.client = client;
        this.objectMapper = objectMapper;
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
        return client.execute(HttpMethod.GET, "/portfolio/balance", response -> {
            if (response.getCode() != 200) {
                log.warn("Kalshi /portfolio/balance returned {}", response.getCode());
                return null;
            }
            JsonNode json = objectMapper.readTree(response.getEntity().getContent());
            long balanceCents = json.path("balance").asLong();
            return BigDecimal.valueOf(balanceCents, 2);
        });
    }

    BigDecimal getInAssetsUsd() {
        return client.execute(HttpMethod.GET, "/portfolio/balance", response -> {
            if (response.getCode() != 200) {
                log.warn("Kalshi /portfolio/balance returned {}", response.getCode());
                return null;
            }
            JsonNode json = objectMapper.readTree(response.getEntity().getContent());
            long portfolioValueCents = json.path("portfolio_value").asLong();
            return BigDecimal.valueOf(portfolioValueCents, 2);
        });
    }

    BigDecimal getInOrdersUsd() {
        return client.execute(HttpMethod.GET, "/portfolio/orders?status=resting&limit=200", response -> {
            if (response.getCode() != 200) {
                log.warn("Kalshi /portfolio/orders returned {}", response.getCode());
                return null;
            }
            JsonNode json = objectMapper.readTree(response.getEntity().getContent());
            return sumOrderValues(json.path("orders"));
        });
    }

    BigDecimal getResolvedProfit() {
        return aggregateSettlements(true);
    }

    BigDecimal getResolvedLoss() {
        return aggregateSettlements(false);
    }

    // ---- helpers ----

    private BigDecimal fetchSafely(java.util.function.Supplier<BigDecimal> supplier, String name) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Failed to fetch Kalshi {}: {}", name, e.getMessage());
            return null;
        }
    }

    /**
     * Sums the USD value of all resting orders.
     * Value = remaining_count_fp × price (yes_price_dollars or no_price_dollars).
     */
    private BigDecimal sumOrderValues(JsonNode orders) {
        BigDecimal total = BigDecimal.ZERO;
        if (orders == null || !orders.isArray()) {
            return total;
        }
        for (JsonNode order : orders) {
            String remainingStr = order.path("remaining_count_fp").asText();
            if (remainingStr.isEmpty()) continue;
            BigDecimal remaining = new BigDecimal(remainingStr);

            String yesPriceStr = order.path("yes_price_dollars").asText();
            String noPriceStr = order.path("no_price_dollars").asText();
            BigDecimal price;
            if (!yesPriceStr.isEmpty()) {
                price = new BigDecimal(yesPriceStr);
            } else if (!noPriceStr.isEmpty()) {
                price = new BigDecimal(noPriceStr);
            } else {
                continue;
            }
            total = total.add(remaining.multiply(price));
        }
        return total;
    }

    /**
     * Paginates through {@code /portfolio/settlements} and sums net P&L.
     *
     * @param profit if {@code true}, sum positive net P&L; if {@code false}, sum negative (absolute)
     */
    private BigDecimal aggregateSettlements(boolean profit) {
        try {
            BigDecimal total = BigDecimal.ZERO;
            String cursor = null;

            while (true) {
                String path = "/portfolio/settlements?limit=" + SETTLEMENTS_LIMIT;
                if (cursor != null && !cursor.isEmpty()) {
                    path += "&cursor=" + cursor;
                }
                String finalPath = path;
                SettlementPage page = client.execute(HttpMethod.GET, finalPath, response -> {
                    if (response.getCode() != 200) {
                        log.warn("Kalshi /portfolio/settlements returned {}", response.getCode());
                        return null;
                    }
                    JsonNode json = objectMapper.readTree(response.getEntity().getContent());
                    BigDecimal pageSum = BigDecimal.ZERO;
                    JsonNode settlements = json.path("settlements");
                    if (settlements.isArray()) {
                        for (JsonNode s : settlements) {
                            BigDecimal netPnl = computeNetPnl(s);
                            if (profit && netPnl.compareTo(BigDecimal.ZERO) > 0) {
                                pageSum = pageSum.add(netPnl);
                            } else if (!profit && netPnl.compareTo(BigDecimal.ZERO) < 0) {
                                pageSum = pageSum.add(netPnl.abs());
                            }
                        }
                    }
                    String nextCursor = json.path("cursor").asText();
                    return new SettlementPage(pageSum, nextCursor);
                });

                if (page == null) break;
                total = total.add(page.sum);
                if (page.cursor == null || page.cursor.isEmpty()) break;
                cursor = page.cursor;
            }
            return total;
        } catch (Exception e) {
            log.warn("Failed to aggregate Kalshi settlements: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Computes net P&L for a single settlement.
     * net = revenue_cents/100 - yes_total_cost_dollars - no_total_cost_dollars - fee_cost
     */
    private BigDecimal computeNetPnl(JsonNode s) {
        long revenueCents = s.path("revenue").asLong();
        BigDecimal revenue = BigDecimal.valueOf(revenueCents, 2);
        BigDecimal yesCost = parseDecimal(s, "yes_total_cost_dollars");
        BigDecimal noCost = parseDecimal(s, "no_total_cost_dollars");
        BigDecimal fees = parseDecimal(s, "fee_cost");
        return revenue.subtract(yesCost).subtract(noCost).subtract(fees);
    }

    private BigDecimal parseDecimal(JsonNode parent, String field) {
        String text = parent.path(field).asText();
        if (text == null || text.isEmpty()) return BigDecimal.ZERO;
        return new BigDecimal(text);
    }

    private record SettlementPage(BigDecimal sum, String cursor) {}
}
