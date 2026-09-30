CREATE TABLE IF NOT EXISTS similar_markets_staging (
    id BIGSERIAL PRIMARY KEY,
    created_at TIMESTAMPTZ NOT NULL,
    updated_at TIMESTAMPTZ NOT NULL,
    similarity FLOAT8 NOT NULL,
    polymarket_event_ticker VARCHAR NOT NULL,
    polymarket_event_title VARCHAR NOT NULL,
    polymarket_market_ticker VARCHAR NOT NULL,
    polymarket_market_title VARCHAR NOT NULL,
    kalshi_event_ticker VARCHAR NOT NULL,
    kalshi_event_title VARCHAR NOT NULL,
    kalshi_market_ticker VARCHAR NOT NULL,
    kalshi_market_title VARCHAR NOT NULL,
    enabled BOOLEAN NOT NULL DEFAULT TRUE
);
