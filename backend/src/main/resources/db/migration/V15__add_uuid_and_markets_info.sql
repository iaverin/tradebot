ALTER TABLE arbitrage_events ADD COLUMN IF NOT EXISTS uuid UUID DEFAULT gen_random_uuid();
CREATE INDEX IF NOT EXISTS idx_arbitrage_events_uuid ON arbitrage_events(uuid);

CREATE TABLE IF NOT EXISTS arbitrage_events_markets_info (
    id BIGSERIAL PRIMARY KEY,
    uuid UUID NOT NULL,
    datasource VARCHAR(50) NOT NULL,
    markets_info JSONB NOT NULL DEFAULT '{}',
    similar_markets_info JSONB NOT NULL DEFAULT '{}',
    created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_markets_info_uuid ON arbitrage_events_markets_info(uuid);