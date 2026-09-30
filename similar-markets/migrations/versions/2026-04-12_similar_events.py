"""add similar_events table.

Revision ID: a1b2c3d4e5f6
Revises:
Create Date: 2026-04-12 12:00:00.000000

"""

import sqlalchemy as sa
from alembic import op


# revision identifiers, used by Alembic.
revision = "a1b2c3d4e5f6"
down_revision = None
branch_labels = None
depends_on = None


def upgrade() -> None:
    op.create_table(
        "similar_events",
        sa.Column("id", sa.BigInteger(), nullable=False, autoincrement=True),
        sa.Column("created_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("updated_at", sa.DateTime(timezone=True), nullable=False),
        sa.Column("similarity", sa.Float(), nullable=False),
        sa.Column("polymarket_event_ticker", sa.String(), nullable=False),
        sa.Column("polymarket_event_title", sa.String(), nullable=False),
        sa.Column("kalshi_event_ticker", sa.String(), nullable=False),
        sa.Column("kalshi_event_title", sa.String(), nullable=False),
        sa.PrimaryKeyConstraint("id"),
        if_not_exists=True
    )


def downgrade() -> None:
    op.drop_table("similar_events")
