CREATE TABLE IF NOT EXISTS excluded_market_pairs (
    id BIGSERIAL PRIMARY KEY,
    polymarket_ticker VARCHAR(500) NOT NULL,
    kalshi_ticker VARCHAR(500) NOT NULL,
    created_at TIMESTAMPTZ DEFAULT NOW(),
    UNIQUE(polymarket_ticker, kalshi_ticker)
);