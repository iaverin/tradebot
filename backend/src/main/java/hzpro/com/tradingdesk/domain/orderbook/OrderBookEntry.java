package hzpro.com.tradingdesk.domain.orderbook;

import java.math.BigDecimal;

public record OrderBookEntry(
    BigDecimal price,
    Long amount
) {

}
