package hzpro.com.tradingdesk.repository;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import hzpro.com.tradingdesk.entity.PredictionMarket;
import hzpro.com.tradingdesk.entity.enums.DataSource;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

@Repository
public interface PredictionMarketRepository extends JpaRepository<PredictionMarket, Long> {

    List<PredictionMarket> findByDatasource(DataSource datasource);
    List<PredictionMarket> findByEventId(String eventId);
    List<PredictionMarket> findByMarketOpenDatetimeBetween(ZonedDateTime start, ZonedDateTime end);
    List<PredictionMarket> findByMarketCloseDatetimeBetween(ZonedDateTime start, ZonedDateTime end);
    List<PredictionMarket> findByDatasourceAndMarketStatus(DataSource datasource, String status);

    Optional<PredictionMarket> findFirstByMarketTickerAndDatasourceOrderByCreatedAtDesc(String marketTicker, DataSource datasource);

    @Query(nativeQuery = true, value = "SELECT COUNT(*) FROM prediction_markets WHERE datasource = :datasource")
    long countPublishedByDatasource(@Param("datasource") String datasource);

    @Query(nativeQuery = true, value = """
            SELECT
            sm.id AS similarMarketId,
            sm.polymarket_market_ticker AS polymarketTicker,
            sm.kalshi_market_ticker AS kalshiTicker,
            pm.yes_token_id AS yesTokenId,
            pm.no_token_id AS noTokenId,
            pm.condition_id AS conditionId,
            pm.market_status AS marketStatus
        FROM similar_markets sm
        JOIN prediction_markets pm ON pm.market_ticker = sm.polymarket_market_ticker
            AND pm.datasource = 'POLYMARKET'
            AND pm.yes_token_id IS NOT NULL
            AND pm.no_token_id IS NOT NULL
            WHERE EXISTS (
            SELECT 1 FROM allowed_market_pairs ep
            WHERE sm.polymarket_market_ticker = ep.polymarket_ticker
              AND sm.kalshi_market_ticker = ep.kalshi_ticker
            )
        ORDER BY sm.id
        LIMIT :limit
        """)
    List<Object[]> findActivePairs(@Param("limit") int limit);

    @Modifying
    @Query(nativeQuery = true, value = "TRUNCATE TABLE fetching_prediction_markets RESTART IDENTITY")
    void truncateFetchingTable();

    @Modifying
    @Query(nativeQuery = true, value = "TRUNCATE TABLE prediction_markets, similar_markets")
    void truncatePublishedTables();

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO prediction_markets (
                datasource, event_id, event_ticker, event_title, event_subtitle,
                event_description, market_ticker, market_open_datetime, market_close_datetime,
                market_title, market_description, market_status, market_result, market_raw_data,
                created_at, updated_at, yes_token_id, no_token_id, condition_id
            )
            SELECT
                datasource, event_id, event_ticker, event_title, event_subtitle,
                event_description, market_ticker, market_open_datetime, market_close_datetime,
                market_title, market_description, market_status, market_result, market_raw_data,
                created_at, updated_at, yes_token_id, no_token_id, condition_id
            FROM fetching_prediction_markets
            """)
    void copyFetchingToPredictionMarkets();

    @Modifying
    @Query(nativeQuery = true, value = """
            INSERT INTO similar_markets (
                created_at, updated_at, similarity,
                polymarket_event_ticker, polymarket_event_title,
                polymarket_market_ticker, polymarket_market_title,
                kalshi_event_ticker, kalshi_event_title,
                kalshi_market_ticker, kalshi_market_title, enabled
            )
            SELECT
                created_at, updated_at, similarity,
                polymarket_event_ticker, polymarket_event_title,
                polymarket_market_ticker, polymarket_market_title,
                kalshi_event_ticker, kalshi_event_title,
                kalshi_market_ticker, kalshi_market_title, enabled
            FROM similar_markets_staging
            """)
    void copyStagingToSimilarMarkets();
}
