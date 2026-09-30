import datetime

import sqlalchemy as sa
from advanced_alchemy.repository import SQLAlchemyAsyncRepository
from advanced_alchemy.service import SQLAlchemyAsyncRepositoryService
from sqlalchemy import orm


class TestRepositoryBase(orm.DeclarativeBase):
    pass


class ProductionPredictionMarket(TestRepositoryBase):
    __tablename__ = "prediction_markets"

    id: orm.Mapped[int] = orm.mapped_column(sa.BigInteger, primary_key=True, autoincrement=True)
    datasource: orm.Mapped[str] = orm.mapped_column(sa.String(50), nullable=False)
    event_ticker: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)
    market_ticker: orm.Mapped[str | None] = orm.mapped_column(sa.String, nullable=True)
    created_at: orm.Mapped[datetime.datetime] = orm.mapped_column(sa.DateTime(timezone=True), nullable=False)
    updated_at: orm.Mapped[datetime.datetime] = orm.mapped_column(sa.DateTime(timezone=True), nullable=False)


class ProductionSimilarMarket(TestRepositoryBase):
    __tablename__ = "similar_markets"

    id: orm.Mapped[int] = orm.mapped_column(sa.BigInteger, primary_key=True, autoincrement=True)
    created_at: orm.Mapped[datetime.datetime] = orm.mapped_column(sa.DateTime(timezone=True), nullable=False)
    updated_at: orm.Mapped[datetime.datetime] = orm.mapped_column(sa.DateTime(timezone=True), nullable=False)
    similarity: orm.Mapped[float] = orm.mapped_column(sa.Float, nullable=False)
    polymarket_event_ticker: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    polymarket_event_title: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    polymarket_market_ticker: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    polymarket_market_title: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    kalshi_event_ticker: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    kalshi_event_title: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    kalshi_market_ticker: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)
    kalshi_market_title: orm.Mapped[str] = orm.mapped_column(sa.String, nullable=False)


class ProductionPredictionMarketsRepository(
    SQLAlchemyAsyncRepositoryService[ProductionPredictionMarket],
):
    class BaseRepository(SQLAlchemyAsyncRepository[ProductionPredictionMarket]):
        model_type = ProductionPredictionMarket

    repository_type = BaseRepository


class ProductionSimilarMarketsRepository(
    SQLAlchemyAsyncRepositoryService[ProductionSimilarMarket],
):
    class BaseRepository(SQLAlchemyAsyncRepository[ProductionSimilarMarket]):
        model_type = ProductionSimilarMarket

    repository_type = BaseRepository
