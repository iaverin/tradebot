-- Новые колонки
ALTER TABLE arbitrage_orders ADD COLUMN IF NOT EXISTS filled_quantity BIGINT;
ALTER TABLE arbitrage_orders ADD COLUMN IF NOT EXISTS filled_amount NUMERIC(10,2);
ALTER TABLE arbitrage_orders ADD COLUMN IF NOT EXISTS executed_at TIMESTAMPTZ;

-- Маппинг старых статусов в новые
UPDATE arbitrage_orders SET status = 'PLACED' WHERE status IN ('RESTING', 'LIVE', 'MATCHED', 'DELAYED');
UPDATE arbitrage_orders SET status = 'EXECUTED' WHERE status IN ('FILLED', 'PARTIAL');
UPDATE arbitrage_orders SET status = 'CANCELED' WHERE status = 'EXPIRED';