ALTER TABLE arbitrage_events
    ADD COLUMN close_reason VARCHAR(50);

UPDATE arbitrage_events
SET close_reason = 'UNKNOWN'
WHERE event_type = 'OPPORTUNITY_END';

ALTER TABLE arbitrage_events
    ADD CONSTRAINT chk_arbitrage_events_close_reason
    CHECK (
        (event_type = 'OPPORTUNITY_END' AND close_reason IN (
            'SPREAD_BELOW_THRESHOLD',
            'PRICE_DATA_STALE',
            'PRICE_DATA_UNAVAILABLE',
            'ORDER_CREATION_FAILED',
            'ORDERS_CREATED',
            'UNKNOWN'
        ))
        OR (event_type <> 'OPPORTUNITY_END' AND close_reason IS NULL)
    );
