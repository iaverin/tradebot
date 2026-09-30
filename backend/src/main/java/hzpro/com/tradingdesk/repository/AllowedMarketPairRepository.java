package hzpro.com.tradingdesk.repository;

import hzpro.com.tradingdesk.entity.AllowedMarketPair;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import java.util.List;

@Repository
public interface AllowedMarketPairRepository extends JpaRepository<AllowedMarketPair, Long> {

    boolean existsByPolymarketTickerAndKalshiTicker(String polymarketTicker, String kalshiTicker);

    void deleteByPolymarketTickerAndKalshiTicker(String polymarketTicker, String kalshiTicker);

    @Query(nativeQuery = true, value = "SELECT polymarket_ticker FROM allowed_market_pairs")
    List<String> findAllPolymarketTickers();

    @Query(nativeQuery = true, value = """
        SELECT COUNT(*)
        FROM allowed_market_pairs amp
        WHERE EXISTS (
            SELECT 1
            FROM similar_markets sm
            WHERE sm.polymarket_market_ticker = amp.polymarket_ticker
              AND sm.kalshi_market_ticker = amp.kalshi_ticker
        )
        """)
    long countMatchingSimilarMarkets();

    @Modifying
    @Query(nativeQuery = true, value = """
        DELETE FROM allowed_market_pairs
        WHERE (polymarket_ticker, kalshi_ticker) IN (
            SELECT sm.polymarket_market_ticker, sm.kalshi_market_ticker
            FROM similar_markets sm
            WHERE sm.polymarket_event_ticker = :pmEventTicker
              AND sm.kalshi_event_ticker = :ksEventTicker
        )
        """)
    int deleteByEventTickers(
            @Param("pmEventTicker") String pmEventTicker,
            @Param("ksEventTicker") String ksEventTicker);
}
