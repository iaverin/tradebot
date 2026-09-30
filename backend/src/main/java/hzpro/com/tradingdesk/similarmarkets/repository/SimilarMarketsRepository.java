package hzpro.com.tradingdesk.similarmarkets.repository;

import hzpro.com.tradingdesk.controller.dto.MarketPairInfo;
import hzpro.com.tradingdesk.entity.SimilarMarket;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import java.util.List;
import java.util.Map;
import java.util.Optional;

@Repository
public interface SimilarMarketsRepository extends JpaRepository<SimilarMarket, Long> {

    long countByPolymarketEventTicker(String polymarketEventTicker);

    Optional<SimilarMarket> findFirstByPolymarketEventTickerOrderByIdDesc(String polymarketEventTicker);

    @Query(nativeQuery = true, value = "SELECT datasource, COUNT(*) AS count FROM prediction_markets GROUP BY datasource")
    List<Map<String, Object>> countFetchedMarketsByDatasource();

    @Query(nativeQuery = true, value = "SELECT COUNT(*) FROM similar_events")
    long countSimilarEvents();

    @Query(nativeQuery = true, value = "SELECT COUNT(*) FROM similar_markets")
    long countSimilarMarkets();

    @Query(
            nativeQuery = true,
            value = """
            SELECT
                sm.id,
                sm.similarity,
                sm.polymarket_event_ticker,
                sm.polymarket_event_title,
                sm.polymarket_market_ticker,
                sm.polymarket_market_title,
                sm.kalshi_event_ticker,
                sm.kalshi_event_title,
                sm.kalshi_market_ticker,
                sm.kalshi_market_title,
                pm_pm.market_close_datetime AS polymarket_market_close_datetime,
                pm_ks.market_close_datetime AS kalshi_market_close_datetime,
                CASE WHEN ep.id IS NOT NULL THEN TRUE ELSE FALSE END AS enabled
            FROM similar_markets sm
            LEFT JOIN allowed_market_pairs ep
                ON sm.polymarket_market_ticker = ep.polymarket_ticker
                AND sm.kalshi_market_ticker = ep.kalshi_ticker
            LEFT JOIN LATERAL (
                SELECT market_close_datetime
                FROM prediction_markets
                WHERE market_ticker = sm.polymarket_market_ticker
                  AND datasource = 'POLYMARKET'
                ORDER BY created_at DESC
                LIMIT 1
            ) pm_pm ON TRUE
            LEFT JOIN LATERAL (
                SELECT market_close_datetime
                FROM prediction_markets
                WHERE market_ticker = sm.kalshi_market_ticker
                  AND datasource = 'KALSHI'
                ORDER BY created_at DESC
                LIMIT 1
            ) pm_ks ON TRUE
            ORDER BY sm.polymarket_event_ticker, sm.kalshi_event_ticker, sm.similarity DESC, sm.id
            LIMIT :#{#pageable.pageSize} OFFSET :#{#pageable.offset}
            """,
            countQuery = "SELECT COUNT(*) FROM similar_markets"
    )
    Page<Map<String, Object>> findSimilarMarketsPageable(Pageable pageable);

    @Query(nativeQuery = true, value = """
    SELECT
        sm.id,
        sm.polymarket_event_ticker,
        sm.polymarket_event_title,
        sm.polymarket_market_title,
        sm.polymarket_market_ticker,
        sm.kalshi_event_ticker,
        sm.kalshi_event_title,
        sm.kalshi_market_title,
        sm.kalshi_market_ticker,
        pm_pm.market_close_datetime AS polymarket_market_close_datetime,
        pm_ks.market_close_datetime AS kalshi_market_close_datetime
    FROM similar_markets sm
    LEFT JOIN LATERAL (
        SELECT market_close_datetime
        FROM prediction_markets
        WHERE market_ticker = sm.polymarket_market_ticker
          AND datasource = 'POLYMARKET'
        ORDER BY created_at DESC
        LIMIT 1
    ) pm_pm ON TRUE
    LEFT JOIN LATERAL (
        SELECT market_close_datetime
        FROM prediction_markets
        WHERE market_ticker = sm.kalshi_market_ticker
          AND datasource = 'KALSHI'
        ORDER BY created_at DESC
        LIMIT 1
    ) pm_ks ON TRUE
    WHERE sm.id IN (:ids)
    """)
    List<Map<String, Object>> findMetadataByIds(@Param("ids") List<Long> ids);

    @Query(nativeQuery = true, value = """
        SELECT polymarket_market_ticker, kalshi_market_ticker
        FROM similar_markets WHERE id = :id
        """)
    Map<String, Object> findSimilarMarketById(@Param("id") Long id);

    @Query(nativeQuery = true, value = "SELECT * FROM similar_markets WHERE id = :id")
    Map<String, Object> findFullSimilarMarketById(@Param("id") Long id);

    @Query(nativeQuery = true, value = """
        SELECT id, polymarket_market_ticker, kalshi_market_ticker
        FROM similar_markets
        WHERE polymarket_event_ticker = :pmEventTicker
          AND kalshi_event_ticker = :ksEventTicker
        """)
    List<MarketPairInfo> findMarketPairsByEventTickers(
            @Param("pmEventTicker") String pmEventTicker,
            @Param("ksEventTicker") String ksEventTicker);
}
