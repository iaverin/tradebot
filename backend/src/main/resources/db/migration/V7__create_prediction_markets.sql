-- Recreate prediction_markets as a copy of fetching_prediction_markets
DROP TABLE IF EXISTS prediction_markets;

CREATE TABLE prediction_markets (
    id BIGSERIAL PRIMARY KEY,
    datasource VARCHAR(50) NOT NULL,
    event_id VARCHAR(255),
    event_ticker VARCHAR(255),
    event_title TEXT,
    event_subtitle TEXT,
    event_description TEXT,
    market_ticker VARCHAR(255),
    market_open_datetime TIMESTAMP WITH TIME ZONE,
    market_close_datetime TIMESTAMP WITH TIME ZONE,
    market_title TEXT,
    market_description TEXT,
    market_status VARCHAR(100),
    market_result TEXT,
    market_raw_data TEXT,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP
);

CREATE INDEX idx_prediction_markets_datasource ON prediction_markets(datasource);
CREATE INDEX idx_prediction_markets_event_id ON prediction_markets(event_id);
CREATE INDEX idx_prediction_markets_market_open_datetime ON prediction_markets(market_open_datetime);
CREATE INDEX idx_prediction_markets_market_close_datetime ON prediction_markets(market_close_datetime);
