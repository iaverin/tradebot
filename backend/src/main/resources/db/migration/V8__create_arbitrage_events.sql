-- Create arbitrage_events table for tracking arbitrage opportunities
CREATE TABLE arbitrage_events (
        id                       BIGSERIAL PRIMARY KEY,
        detected_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
        event_type               VARCHAR(20) NOT NULL,
        opportunity_start_id     BIGINT REFERENCES arbitrage_events(id),
        similar_market_id        BIGINT,
        polymarket_market_ticker TEXT NOT NULL,
        kalshi_market_ticker     TEXT NOT NULL,
        direction                VARCHAR(20) NOT NULL,
        polymarket_yes_ask       NUMERIC(8, 6),
        polymarket_no_ask        NUMERIC(8, 6),
        kalshi_yes_ask           NUMERIC(8, 6),
        kalshi_no_ask            NUMERIC(8, 6),
        spread                   NUMERIC(8, 6) NOT NULL,
        threshold                NUMERIC(8, 6) NOT NULL,
        polymarket_orderbook     JSONB NOT NULL DEFAULT '{}',
        kalshi_orderbook         JSONB NOT NULL DEFAULT '{}'
);

CREATE INDEX idx_arb_events_detected_at    ON arbitrage_events(detected_at DESC);
CREATE INDEX idx_arb_events_start_id       ON arbitrage_events(opportunity_start_id);
CREATE INDEX idx_arb_events_type           ON arbitrage_events(event_type);
CREATE INDEX idx_arb_events_direction      ON arbitrage_events(direction);
