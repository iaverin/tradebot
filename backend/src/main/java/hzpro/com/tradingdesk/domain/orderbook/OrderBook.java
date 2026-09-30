package hzpro.com.tradingdesk.domain.orderbook;
import java.util.Comparator;
import java.util.List;


public record OrderBook(
    List<OrderBookEntry> asks
) {
    public OrderBook(List<OrderBookEntry> asks) {
        List<OrderBookEntry> sortedAsks = asks.stream().sorted(
                Comparator.comparing(OrderBookEntry::price)).toList();
        this.asks = sortedAsks;
    }
}
