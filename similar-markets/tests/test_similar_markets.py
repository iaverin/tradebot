import datetime

from sqlalchemy.ext.asyncio import AsyncSession

from app import models
from app.repositories import (
    PredictionMarketsRepository,
    SimilarEventsRepository,
    SimilarMarketsStagingRepository,
)
from app.settings import settings
from tests.conftest import RunWorker
from tests.repositories import (
    ProductionPredictionMarket,
    ProductionPredictionMarketsRepository,
    ProductionSimilarMarket,
    ProductionSimilarMarketsRepository,
)


def active_market(
    *,
    datasource: str,
    event_ticker: str,
    event_title: str,
    market_ticker: str,
    market_title: str,
) -> models.PredictionMarket:
    now = datetime.datetime.now(tz=datetime.UTC)
    return models.PredictionMarket(
        datasource=datasource,
        event_ticker=event_ticker,
        event_title=event_title,
        market_ticker=market_ticker,
        market_title=market_title,
        market_open_datetime=now - datetime.timedelta(days=1),
        market_close_datetime=now
        + datetime.timedelta(days=settings.similar_events_select_events_close_date_after_days - 1),
    )


async def store_market_pair(
    db_session: AsyncSession,
    *,
    polymarket_event_title: str,
    kalshi_event_title: str,
    polymarket_market_title: str,
    kalshi_market_title: str,
) -> tuple[models.PredictionMarket, models.PredictionMarket]:
    polymarket_market = active_market(
        datasource="POLYMARKET",
        event_ticker="president-2024",
        event_title=polymarket_event_title,
        market_ticker="trump-wins-2024",
        market_title=polymarket_market_title,
    )
    kalshi_market = active_market(
        datasource="KALSHI",
        event_ticker="KXPRES-24",
        event_title=kalshi_event_title,
        market_ticker="KXPRES-24-T",
        market_title=kalshi_market_title,
    )
    repository = PredictionMarketsRepository(session=db_session)
    stored_markets = await repository.create_many([polymarket_market, kalshi_market])
    return stored_markets[0], stored_markets[1]


async def test_worker_stores_similar_event_and_market_pairs(
    run_worker: RunWorker,
    db_session: AsyncSession,
) -> None:
    event_title = "Who will win the 2024 US Presidential Election?"
    market_title = "Will Donald Trump win the 2024 US Presidential Election?"
    polymarket_market, kalshi_market = await store_market_pair(
        db_session,
        polymarket_event_title=event_title,
        kalshi_event_title=event_title,
        polymarket_market_title=market_title,
        kalshi_market_title=market_title,
    )

    await run_worker()

    similar_events_repository = SimilarEventsRepository(session=db_session)
    similar_markets_repository = SimilarMarketsStagingRepository(session=db_session)
    similar_event = (await similar_events_repository.list())[0]
    similar_market = (await similar_markets_repository.list())[0]
    assert similar_event.polymarket_event_ticker == polymarket_market.event_ticker
    assert similar_event.kalshi_event_ticker == kalshi_market.event_ticker
    assert similar_event.similarity >= settings.similarity_threshold
    assert similar_market.polymarket_market_ticker == polymarket_market.market_ticker
    assert similar_market.kalshi_market_ticker == kalshi_market.market_ticker
    assert similar_market.similarity >= settings.similarity_threshold


async def test_worker_skips_events_below_threshold(
    run_worker: RunWorker,
    db_session: AsyncSession,
) -> None:
    await store_market_pair(
        db_session,
        polymarket_event_title="Will humans land on the Moon before 2030?",
        kalshi_event_title="What will the price of coffee be in December?",
        polymarket_market_title="Moon landing before 2030",
        kalshi_market_title="December coffee price",
    )

    await run_worker()

    assert await SimilarEventsRepository(session=db_session).count() == 0
    assert await SimilarMarketsStagingRepository(session=db_session).count() == 0


async def test_worker_skips_markets_below_threshold(
    run_worker: RunWorker,
    db_session: AsyncSession,
) -> None:
    event_title = "Who will win the 2024 US Presidential Election?"
    await store_market_pair(
        db_session,
        polymarket_event_title=event_title,
        kalshi_event_title=event_title,
        polymarket_market_title="Will humans land on the Moon before 2030?",
        kalshi_market_title="What will the price of coffee be in December?",
    )

    await run_worker()

    assert await SimilarEventsRepository(session=db_session).count() == 1
    assert await SimilarMarketsStagingRepository(session=db_session).count() == 0


async def test_worker_does_not_modify_production_tables(
    run_worker: RunWorker,
    db_session: AsyncSession,
) -> None:
    event_title = "Who will win the 2024 US Presidential Election?"
    market_title = "Will Donald Trump win the 2024 US Presidential Election?"
    await store_market_pair(
        db_session,
        polymarket_event_title=event_title,
        kalshi_event_title=event_title,
        polymarket_market_title=market_title,
        kalshi_market_title=market_title,
    )
    now = datetime.datetime.now(tz=datetime.UTC)
    production_markets_repository = ProductionPredictionMarketsRepository(session=db_session)
    production_pairs_repository = ProductionSimilarMarketsRepository(session=db_session)
    await production_markets_repository.create(
        ProductionPredictionMarket(
            datasource="PRODUCTION_SENTINEL",
            event_ticker="production-event",
            market_ticker="production-market",
            created_at=now,
            updated_at=now,
        )
    )
    await production_pairs_repository.create(
        ProductionSimilarMarket(
            created_at=now,
            updated_at=now,
            similarity=1.0,
            polymarket_event_ticker="production-pm-event",
            polymarket_event_title="Production PM event",
            polymarket_market_ticker="production-pm-market",
            polymarket_market_title="Production PM market",
            kalshi_event_ticker="production-ks-event",
            kalshi_event_title="Production KS event",
            kalshi_market_ticker="production-ks-market",
            kalshi_market_title="Production KS market",
        )
    )

    await run_worker()

    production_market_count = await production_markets_repository.count(
        ProductionPredictionMarket.datasource == "PRODUCTION_SENTINEL"
    )
    production_pair_count = await production_pairs_repository.count(
        ProductionSimilarMarket.polymarket_event_ticker == "production-pm-event"
    )
    assert production_market_count == 1
    assert production_pair_count == 1
    assert await SimilarMarketsStagingRepository(session=db_session).count() == 1


async def test_worker_replaces_stale_staging_data(
    run_worker: RunWorker,
    db_session: AsyncSession,
) -> None:
    staging_repository = SimilarMarketsStagingRepository(session=db_session)
    await staging_repository.create(
        models.SimilarMarketStaging(
            similarity=1.0,
            polymarket_event_ticker="stale-pm-event",
            polymarket_event_title="Stale PM event",
            polymarket_market_ticker="stale-pm-market",
            polymarket_market_title="Stale PM market",
            kalshi_event_ticker="stale-ks-event",
            kalshi_event_title="Stale KS event",
            kalshi_market_ticker="stale-ks-market",
            kalshi_market_title="Stale KS market",
        )
    )
    await store_market_pair(
        db_session,
        polymarket_event_title="Will humans land on the Moon before 2030?",
        kalshi_event_title="What will the price of coffee be in December?",
        polymarket_market_title="Moon landing before 2030",
        kalshi_market_title="December coffee price",
    )

    await run_worker()

    assert await staging_repository.count() == 0
