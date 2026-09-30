-- Add indexes to speed up lookups of prediction_markets by market_ticker and event_ticker.
CREATE INDEX IF NOT EXISTS idx_prediction_markets_market_ticker
    ON prediction_markets (market_ticker);

CREATE INDEX IF NOT EXISTS idx_prediction_markets_event_ticker
    ON prediction_markets (event_ticker);
