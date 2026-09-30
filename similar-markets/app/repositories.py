import datetime
from collections.abc import Sequence

from advanced_alchemy.repository import SQLAlchemyAsyncRepository
from advanced_alchemy.service import SQLAlchemyAsyncRepositoryService
from sqlalchemy import and_, select, text
from sqlalchemy.engine.row import Row

from app import models
from app.settings import settings


class PredictionMarketsRepository(SQLAlchemyAsyncRepositoryService[models.PredictionMarket]):
    class BaseRepository(SQLAlchemyAsyncRepository[models.PredictionMarket]):
        model_type = models.PredictionMarket

        DATES_FILTER = and_(
            model_type.market_open_datetime < datetime.datetime.now(tz=datetime.UTC),
            model_type.market_close_datetime > datetime.datetime.now(tz=datetime.UTC) + datetime.timedelta(days=1),
            model_type.market_close_datetime
            < datetime.datetime.now(tz=datetime.UTC)
            + datetime.timedelta(days=settings.similar_events_select_events_close_date_after_days),
        )

        async def get_distinct_events(
            self,
        ) -> Sequence[Row[tuple[str, str | None, str | None, str | None]]]:
            stmt = (
                select(
                    self.model_type.datasource,
                    self.model_type.event_id,
                    self.model_type.event_ticker,
                    self.model_type.event_title,
                )
                .distinct()
                .where(self.DATES_FILTER)
            )
            result = await self.session.execute(stmt)

            return result.all()

        async def get_markets_for_event_pair(
            self,
            polymarket_event_ticker: str,
            kalshi_event_ticker: str,
        ) -> Sequence[models.PredictionMarket]:
            stmt = select(self.model_type).where(
                and_(
                    self.model_type.event_ticker.in_([polymarket_event_ticker, kalshi_event_ticker]), self.DATES_FILTER
                )
            )
            result = await self.session.execute(stmt)
            return result.scalars().all()

    repository_type = BaseRepository

    async def get_distinct_events(self) -> Sequence[Row[tuple[str, str | None, str | None, str | None]]]:
        return await self.repository.get_distinct_events()

    async def get_markets_for_event_pair(
        self,
        polymarket_event_ticker: str,
        kalshi_event_ticker: str,
    ) -> Sequence[models.PredictionMarket]:
        return await self.repository.get_markets_for_event_pair(polymarket_event_ticker, kalshi_event_ticker)


class SimilarEventsRepository(SQLAlchemyAsyncRepositoryService[models.SimilarEvent]):
    class BaseRepository(SQLAlchemyAsyncRepository[models.SimilarEvent]):
        model_type = models.SimilarEvent

        async def truncate_table(self) -> None:
            await self.session.execute(text("TRUNCATE TABLE similar_events RESTART IDENTITY"))
            await self.session.commit()

    repository_type = BaseRepository

    async def truncate_table(self) -> None:
        await self.repository.truncate_table()




class SimilarMarketsStagingRepository(SQLAlchemyAsyncRepositoryService[models.SimilarMarketStaging]):
    class BaseRepository(SQLAlchemyAsyncRepository[models.SimilarMarketStaging]):
        model_type = models.SimilarMarketStaging

        async def truncate_table(self) -> None:
            await self.session.execute(text("TRUNCATE TABLE similar_markets_staging RESTART IDENTITY"))
            await self.session.commit()

    repository_type = BaseRepository

    async def truncate_table(self) -> None:
        await self.repository.truncate_table()
