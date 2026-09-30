-- Restore the published markets table if it is missing, using the fetching table as its schema.
CREATE TABLE IF NOT EXISTS prediction_markets (
    LIKE fetching_prediction_markets INCLUDING ALL
);
