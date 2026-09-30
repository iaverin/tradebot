package hzpro.com.tradingdesk.arbitrage.service;

import java.math.BigDecimal;

import org.springframework.stereotype.Service;

import hzpro.com.tradingdesk.arbitrage.dto.PolymarketOrderBookDto;
import hzpro.com.tradingdesk.arbitrage.rest.PolymarketPriceRestClient;
import hzpro.com.tradingdesk.domain.enums.YesOrNoResult;
import hzpro.com.tradingdesk.domain.orderbook.OrderBook;
import hzpro.com.tradingdesk.domain.orderbook.OrderBookEntry;
import hzpro.com.tradingdesk.entity.enums.DataSource;
import hzpro.com.tradingdesk.repository.PredictionMarketRepository;
import hzpro.com.tradingdesk.entity.PredictionMarket;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NullMarked;
import org.jspecify.annotations.Nullable;

@Service
@RequiredArgsConstructor
@NullMarked
public class PolymarketFetchCommonOrderBookService {

    private final PolymarketPriceRestClient polymarketRestClient;
    private final PredictionMarketRepository predictionMarketRepository;

    @Nullable
    public OrderBook fetchCommonOrderBook(String marketTicker, YesOrNoResult result) {
        PredictionMarket market = predictionMarketRepository
                .findFirstByMarketTickerAndDatasourceOrderByCreatedAtDesc(marketTicker, DataSource.POLYMARKET)
                .orElse(null);

        if (market == null) {
            return null;
        }

        String tokenId = result == YesOrNoResult.YES ? market.getYesTokenId() : market.getNoTokenId();

        PolymarketOrderBookDto polymarketOrderBook = polymarketRestClient.fetchOrderBook(tokenId);
        if (polymarketOrderBook == null) {
            return null;
        }
        return convertPolyMarkerOrderBookToCommon(polymarketOrderBook);
    }


    private OrderBook convertPolyMarkerOrderBookToCommon(PolymarketOrderBookDto polymarketOrderBookDto) {
        return new OrderBook(
                polymarketOrderBookDto.asks().stream().map(
                        e -> new OrderBookEntry(
                                new BigDecimal(e.price()),
                                parseSize(e.size())))
                        .toList());
    }

    /**
     * Polymarket CLOB returns sizes as decimal strings (e.g. "106.02"). The common
     * {@link OrderBookEntry} stores {@code amount} as {@code Long}, so we parse via
     * {@link BigDecimal} and truncate the fractional part. Returns 0 for null/blank input.
     */
    private Long parseSize(String sizeString) {
        if (sizeString == null || sizeString.isEmpty()) {
            return 0L;
        }
        return new BigDecimal(sizeString).longValue();
    }

}
