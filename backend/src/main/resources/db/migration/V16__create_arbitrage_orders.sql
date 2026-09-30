CREATE TABLE IF NOT EXISTS arbitrage_orders (
                                                id BIGSERIAL PRIMARY KEY,
                                                opportunity_uuid UUID NOT NULL,
                                                platform VARCHAR(50) NOT NULL,
                                                contract_type VARCHAR(10) NOT NULL,
                                                price NUMERIC(8,6) NOT NULL,
                                                quantity BIGINT NOT NULL,
                                                best_ask_price NUMERIC(8,6),
                                                best_ask_amount BIGINT,
                                                order_id VARCHAR(255),
                                                status VARCHAR(20) NOT NULL DEFAULT 'CREATED',
                                                created_at TIMESTAMPTZ DEFAULT NOW()
);
CREATE INDEX IF NOT EXISTS idx_orders_uuid ON arbitrage_orders(opportunity_uuid);