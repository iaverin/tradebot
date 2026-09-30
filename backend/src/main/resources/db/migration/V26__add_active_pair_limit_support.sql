ALTER TABLE arbitrage_events
    DROP CONSTRAINT chk_arbitrage_events_close_reason;

ALTER TABLE arbitrage_events
    ADD CONSTRAINT chk_arbitrage_events_close_reason
    CHECK (
        (event_type = 'OPPORTUNITY_END' AND close_reason IN (
            'SPREAD_BELOW_THRESHOLD',
            'PRICE_DATA_STALE',
            'PRICE_DATA_UNAVAILABLE',
            'ORDER_CREATION_FAILED',
            'ORDERBOOK_PRICE_BELOW_THRESHOLD',
            'ORDER_BOOK_NOT_ENOUGH_AMOUT',
            'ORDERBOOK_EMPTY',
            'MINIMIM_ORDER_COST_NOT_MET',
            'ACTIVE_PAIR_LIMIT_REACHED',
            'ORDERS_CREATED',
            'UNKNOWN'
        ))
        OR (event_type <> 'OPPORTUNITY_END' AND close_reason IS NULL)
    );

CREATE INDEX idx_arbitrage_events_start_market_pair
    ON arbitrage_events (polymarket_market_ticker, kalshi_market_ticker, uuid)
    WHERE event_type = 'OPPORTUNITY_START';

CREATE INDEX idx_arbitrage_orders_unfinished_opportunity
    ON arbitrage_orders (opportunity_uuid)
    WHERE status IN ('CREATED', 'PENDING', 'PLACED', 'UNKNOWN');
