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
            'ORDERS_CREATED',
            'UNKNOWN'
        ))
        OR (event_type <> 'OPPORTUNITY_END' AND close_reason IS NULL)
    );
