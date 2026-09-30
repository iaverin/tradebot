"""Create staging tables for atomic market snapshot publishing.

Revision ID: c4d5e6f7a8b9
Revises: b3c4d5e6f7a8
Create Date: 2026-09-02 00:00:00.000000
"""

from alembic import op


revision = "c4d5e6f7a8b9"
down_revision = "b3c4d5e6f7a8"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.execute(
        """
        DO $$
        BEGIN
            IF to_regclass('public.fetching_prediction_markets') IS NULL
                AND to_regclass('public.prediction_markets') IS NOT NULL THEN
                CREATE TABLE fetching_prediction_markets
                    (LIKE prediction_markets INCLUDING ALL);
            END IF;
        END $$;
        """
    )
    op.execute("CREATE SEQUENCE IF NOT EXISTS fetching_prediction_markets_id_seq")
    op.execute(
        "ALTER SEQUENCE fetching_prediction_markets_id_seq OWNED BY fetching_prediction_markets.id"
    )
    op.execute(
        """
        ALTER TABLE fetching_prediction_markets
        ALTER COLUMN id SET DEFAULT nextval('fetching_prediction_markets_id_seq')
        """
    )
    op.execute(
        """
        SELECT setval(
            'fetching_prediction_markets_id_seq',
            COALESCE((SELECT MAX(id) FROM fetching_prediction_markets), 0) + 1,
            false
        )
        """
    )
    op.execute(
        """
        CREATE TABLE IF NOT EXISTS similar_markets_staging (
            id BIGSERIAL PRIMARY KEY,
            created_at TIMESTAMPTZ NOT NULL,
            updated_at TIMESTAMPTZ NOT NULL,
            similarity FLOAT8 NOT NULL,
            polymarket_event_ticker VARCHAR NOT NULL,
            polymarket_event_title VARCHAR NOT NULL,
            polymarket_market_ticker VARCHAR NOT NULL,
            polymarket_market_title VARCHAR NOT NULL,
            kalshi_event_ticker VARCHAR NOT NULL,
            kalshi_event_title VARCHAR NOT NULL,
            kalshi_market_ticker VARCHAR NOT NULL,
            kalshi_market_title VARCHAR NOT NULL,
            enabled BOOLEAN NOT NULL DEFAULT TRUE
        )
        """
    )


def downgrade() -> None:
    op.execute("DROP TABLE IF EXISTS similar_markets_staging")
