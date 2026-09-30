package hzpro.com.tradingdesk.arbitrage.util;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import hzpro.com.tradingdesk.domain.orderbook.OrderBookEntry;

class ArbitrageOrderUtilsTest {

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
        // description, price1, amount1, price2, amount2, maxOrderCost, expectedQuantity
        "cost below cap returns minQuantity,                       0.50, 100,  0.40, 200,  100, 100",
        "cost equal to cap returns minQuantity,                    0.50, 100,  0.40, 200,  50,  100",
        "cost above cap is capped (exact division),               0.50, 1000, 0.40, 1000, 100, 200",
        "cap division rounds down,                                   0.30, 1000, 0.30, 1000, 100, 333",
        "minQuantity from entry2 and maxPrice from entry1,         0.60, 500,  0.20, 10,   100, 10",
        "maxPrice from entry2 when capping,                        0.10, 1000, 0.80, 1000, 400, 500",
        "minQuantity from entry1 and capped with rounding,         0.70, 50,   0.70, 80,   33,  47",
    })
    void produceOrderSharesQuantityCappedByMaxOrderCost(
        String description,
        BigDecimal price1,
        long amount1,
        BigDecimal price2,
        long amount2,
        BigDecimal maxOrderCost,
        long expectedQuantity
    ) {
        OrderBookEntry entry1 = new OrderBookEntry(price1, amount1);
        OrderBookEntry entry2 = new OrderBookEntry(price2, amount2);

        long actual = ArbitrageOrderUtils.produceOrderSharesQuantityCappedByMaxOrderCost(
            entry1, entry2, maxOrderCost);

        assertThat(actual).isEqualTo(expectedQuantity);
    }

    @ParameterizedTest(name = "[{index}] {0}")
    @CsvSource({
        // description, price1, price2, orderSharesQuantity, minOrderCost, expected
        "cost above minimum is accepted,            0.50, 0.40, 100, 30, true",
        "cost equal to minimum is accepted,         0.50, 0.40, 100, 40, true",
        "cost below minimum is rejected,            0.50, 0.40, 100, 50, false",
        "minPrice taken from entry1,                0.20, 0.60, 50,  5,  true",
        "minPrice from entry1 below minimum,        0.20, 0.60, 50,  20, false",
        "zero shares never meets positive minimum,  0.50, 0.40, 0,   10, false",
    })
    void checkOrderCostGreaterThanMinimum(
        String description,
        BigDecimal price1,
        BigDecimal price2,
        long orderSharesQuantity,
        BigDecimal minOrderCost,
        boolean expected
    ) {
        OrderBookEntry entry1 = new OrderBookEntry(price1, 1L);
        OrderBookEntry entry2 = new OrderBookEntry(price2, 1L);

        boolean actual = ArbitrageOrderUtils.checkOrderCostGreaterThanMinimum(
            entry1, entry2, orderSharesQuantity, minOrderCost);

        assertThat(actual).isEqualTo(expected);
    }
}
