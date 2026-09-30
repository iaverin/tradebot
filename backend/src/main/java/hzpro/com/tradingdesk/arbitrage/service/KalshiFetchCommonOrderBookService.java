package hzpro.com.tradingdesk.arbitrage.service;

import java.math.BigDecimal;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Service;

import hzpro.com.tradingdesk.arbitrage.dto.KalshiOrderBookDto;
import hzpro.com.tradingdesk.client.HttpMethod;
import hzpro.com.tradingdesk.client.KalshiAuthorizedClient;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.domain.orderbook.OrderBook;
import hzpro.com.tradingdesk.domain.orderbook.OrderBookEntry;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Fetches a Kalshi market's REST orderbook and converts it to the platform-agnostic
 * {@link OrderBook} (asks-only) used by the arbitrage layer.
 *
 * <p>Kalshi exposes only bids — see
 * <a href="https://docs.kalshi.com/getting_started/orderbook_responses">Kalshi
 * orderbook responses</a>. We materialise the requested side's asks via the standard
 * binary-market reciprocal:
 * <ul>
 *   <li>YES ask = $1.00 - NO bid, derived from {@code no_dollars}</li>
 *   <li>NO  ask = $1.00 - YES bid, derived from {@code yes_dollars}</li>
 * </ul>
 *
 * <p>Source arrays are sorted ascending by bid price. After the {@code 1 - x}
 * transformation entries become descending by implied-ask price, which matches the
 * Polymarket convention of "best ask is the last element".
 */
@Slf4j
@Service
@RequiredArgsConstructor
@NullMarked
public class KalshiFetchCommonOrderBookService {

    private static final BigDecimal ONE = BigDecimal.ONE;

    private final KalshiAuthorizedClient kalshiAuthorizedClient;
    private final ObjectMapper objectMapper;

    @Nullable
    public OrderBook fetchCommonOrderBook(String marketTicker, YesOrNoResult result) {
        try {
            String path = "/markets/" + marketTicker + "/orderbook";
            KalshiOrderBookDto kalshiOrderBook = kalshiAuthorizedClient.execute(HttpMethod.GET, path,
                    response -> {
                        int status = response.getCode();
                        if (status < 200 || status >= 300) {
                            log.warn("Kalshi orderbook REST failed for {}: status {}", marketTicker, status);
                            return null;
                        }
                        return objectMapper.readValue(response.getEntity().getContent(), KalshiOrderBookDto.class);
                    });

            if (kalshiOrderBook == null || kalshiOrderBook.orderbookFp() == null) {
                return null;
            }
            return convertKalshiOrderBookToCommon(kalshiOrderBook, result);
        } catch (Exception e) {
            log.warn("Error fetching Kalshi orderbook for {}: {}", marketTicker, e.getMessage());
            return null;
        }
    }

    private OrderBook convertKalshiOrderBookToCommon(KalshiOrderBookDto dto, YesOrNoResult result) {
        // For YES asks: use the NO bids (yes_ask = 1 - no_bid_price).
        // For NO  asks: use the YES bids (no_ask  = 1 - yes_bid_price).
        List<List<String>> sourceBids = result == YesOrNoResult.YES
                ? dto.orderbookFp().noDollars()
                : dto.orderbookFp().yesDollars();

        if (sourceBids == null || sourceBids.isEmpty()) {
            return new OrderBook(List.of());
        }

        List<OrderBookEntry> asks = sourceBids.stream()
                .filter(entry -> entry != null && entry.size() >= 2)
                .map(entry -> new OrderBookEntry(
                        ONE.subtract(new BigDecimal(entry.get(0))),
                        parseSize(entry.get(1))))
                .toList();

        return new OrderBook(asks);
    }

    /**
     * Kalshi sizes are fixed-point dollar-style strings (e.g. "100.00"). The common
     * {@link OrderBookEntry} stores {@code amount} as {@code Long}, matching Polymarket's
     * integer contract counts, so we truncate the fractional part.
     */
    private Long parseSize(String sizeString) {
        return new BigDecimal(sizeString).longValue();
    }
}
