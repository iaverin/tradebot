CREATE TABLE portfolio_positions (
    id                  BIGSERIAL PRIMARY KEY,
    venue               VARCHAR(50) NOT NULL,
    source_position_id  TEXT NOT NULL,
    market_ticker       TEXT NOT NULL,
    condition_id        TEXT,
    event_ticker        TEXT,
    event_title         TEXT,
    market_title        TEXT,
    outcome             VARCHAR(10) NOT NULL,
    quantity            NUMERIC(30, 10) NOT NULL,
    spent_usd           NUMERIC(30, 10),
    current_value_usd   NUMERIC(30, 10),
    source_updated_at   TIMESTAMPTZ,
    refreshed_at        TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_portfolio_positions_venue
    ON portfolio_positions (venue);

CREATE UNIQUE INDEX idx_portfolio_positions_relation
    ON portfolio_positions (venue, market_ticker, outcome);

CREATE TABLE portfolio_position_refresh_state (
    venue                       VARCHAR(50) PRIMARY KEY,
    last_successful_refresh_at  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
