-- Add token_id columns to staging table
ALTER TABLE fetching_prediction_markets
    ADD COLUMN IF NOT EXISTS yes_token_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS no_token_id VARCHAR(255),
    ADD COLUMN IF NOT EXISTS condition_id VARCHAR(255);