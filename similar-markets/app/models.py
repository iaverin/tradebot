import typing

import sqlalchemy as sa
from advanced_alchemy.base import BigIntAuditBase, orm_registry
from sqlalchemy import orm


METADATA: typing.Final = orm_registry.metadata
orm.DeclarativeBase.metadata = METADATA


class PredictionMarket(BigIntAuditBase):
    __tablename__ = "fetching_prediction_markets"

    datasource: orm.Mapped[str] = orm.mapped_column(sa.String(50), nullable=False)
    event_id: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)
    event_ticker: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)
    event_title: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)
    event_subtitle: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)
    event_description: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)
    market_ticker: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)
    market_open_datetime: orm.Mapped[sa.DateTime | None] = orm.mapped_column(sa.DateTime(timezone=True), nullable=True)
    market_close_datetime: orm.Mapped[sa.DateTime | None] = orm.mapped_column(sa.DateTime(timezone=True), nullable=True)
    market_title: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)
    market_description: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)
    market_status: orm.Mapped[str | None] = orm.mapped_column(sa.String(100), nullable=True)
    market_result: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)
    market_raw_data: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)


class SimilarEvent(BigIntAuditBase):
    __tablename__ = "similar_events"

    similarity: orm.Mapped[float] = orm.mapped_column(sa.Float, nullable=False)
    polymarket_event_ticker: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    polymarket_event_title: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    kalshi_event_ticker: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    kalshi_event_title: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)


class SimilarMarketStaging(BigIntAuditBase):
    __tablename__ = "similar_markets_staging"

    similarity: orm.Mapped[float] = orm.mapped_column(sa.Float, nullable=False)
    polymarket_event_ticker: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    polymarket_event_title: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    polymarket_market_ticker: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    polymarket_market_title: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    kalshi_event_ticker: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    kalshi_event_title: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    kalshi_market_ticker: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    kalshi_market_title: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    enabled: orm.Mapped[bool] = orm.mapped_column(sa.Boolean, nullable=False, default=True, server_default=sa.true())
