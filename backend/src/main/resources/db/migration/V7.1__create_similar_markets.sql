CREATE TABLE IF NOT EXISTS public.similar_events (
    id bigserial PRIMARY KEY,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    similarity float8 NOT NULL,
    polymarket_event_ticker varchar NOT NULL,
    polymarket_event_title varchar NOT NULL,
    kalshi_event_ticker varchar NOT NULL,
    kalshi_event_title varchar NOT NULL
);

-- public.similar_markets definition
-- Drop table
-- DROP TABLE public.similar_markets;
CREATE TABLE IF NOT EXISTS public.similar_markets (
    id bigserial PRIMARY KEY,
    created_at timestamptz NOT NULL,
    updated_at timestamptz NOT NULL,
    similarity float8 NOT NULL,
    polymarket_event_ticker varchar NOT NULL,
    polymarket_event_title varchar NOT NULL,
    polymarket_market_ticker varchar NOT NULL,
    polymarket_market_title varchar NOT NULL,
    kalshi_event_ticker varchar NOT NULL,
    kalshi_event_title varchar NOT NULL,
    kalshi_market_ticker varchar NOT NULL,
    kalshi_market_title varchar NOT NULL
);