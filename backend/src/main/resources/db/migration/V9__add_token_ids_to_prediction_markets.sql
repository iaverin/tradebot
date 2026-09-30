-- V9__add_token_ids_to_prediction_markets.sql
ALTER TABLE prediction_markets
    ADD COLUMN IF NOT EXISTS yes_token_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS no_token_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS condition_id VARCHAR(255);