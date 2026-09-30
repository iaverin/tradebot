"""add similar_markets table.

Revision ID: b3c4d5e6f7a8
Revises: 8298ccf90586
Create Date: 2026-04-14 12:00:00.000000

"""

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision = "b3c4d5e6f7a8"
down_revision = "8298ccf90586"
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "similar_markets",
        sa.Column("id", sa.BigInteger(), nullable=False, autoincrement=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("similarity", sa.Float(), nullable=False),
        sa.Column("polymarket_event_ticker", sa.String(), nullable=False),
        sa.Column("polymarket_event_title", sa.String(), nullable=False),
        sa.Column("polymarket_market_ticker", sa.String(), nullable=False),
        sa.Column("polymarket_market_title", sa.String(), nullable=False),
        sa.Column("kalshi_event_ticker", sa.String(), nullable=False),
        sa.Column("kalshi_event_title", sa.String(), nullable=False),
        sa.Column("kalshi_market_ticker", sa.String(), nullable=False),
        sa.Column("kalshi_market_title", sa.String(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        if_not_exists=True
    )


def downgrade() -> None:
    op.drop_table("similar_markets")
