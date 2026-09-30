package hzpro.com.tradingdesk.arbitrage.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

import hzpro.com.tradingdesk.domain.orderbook.OrderBookEntry;

public class ArbitrageOrderUtils {
    private ArbitrageOrderUtils() {}

    public static long produceOrderSharesQuantityCappedByMaxOrderCost(
        OrderBookEntry  entry1,
        OrderBookEntry entry2,
        BigDecimal maxOrderCost
    ) {

        long minQuantity = Math.min(entry1.amount(), entry2.amount());
        BigDecimal maxPrice = entry1.price().max(entry2.price());

        BigDecimal orderCost = maxPrice.multiply(BigDecimal.valueOf(minQuantity));

        if (orderCost.compareTo(maxOrderCost) <= 0) {
            return minQuantity;
        }

        return maxOrderCost.divide(maxPrice, RoundingMode.FLOOR).longValue();
    }

    public static boolean checkOrderCostGreaterThanMinimum(
        OrderBookEntry entry1,
        OrderBookEntry entry2,
        long orderSharesQuantity,
        BigDecimal minOrderCost

    ) {
        BigDecimal minPrice = entry1.price().min(entry2.price());
        BigDecimal orderCostForMinPrice = minPrice.multiply(BigDecimal.valueOf(orderSharesQuantity));
        return orderCostForMinPrice.compareTo(minOrderCost) >= 0;
    }

    /**
     * Polymarket enforces a per-market {@code min_order_size} (default 5 CTF tokens).
     * @see <a href="https://docs.polymarket.com/trading/orderbook">Polymarket Orderbook docs</a>
     */
    public static boolean checkOrderSizeGreaterThanMinimum(long orderSharesQuantity, long minOrderSize) {
        return orderSharesQuantity >= minOrderSize;
    }

}
