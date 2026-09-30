import logging
from dataclasses import dataclass

import numpy as np
from sentence_transformers import SentenceTransformer

from app import models
from app.repositories import PredictionMarketsRepository, SimilarEventsRepository, SimilarMarketsStagingRepository
from app.schemas import PredictionMarketEntity
from app.settings import settings


logger = logging.getLogger(__name__)

_model: SentenceTransformer | None = None


def get_model() -> SentenceTransformer:
    global _model  # noqa: PLW0603
    if _model is None:
        _model = SentenceTransformer("all-MiniLM-L6-v2")
    return _model


@dataclass(kw_only=True, frozen=True, slots=True)
class FindSimilarEventsService:
    prediction_markets_repository: PredictionMarketsRepository
    similar_events_repository: SimilarEventsRepository

    async def fetch_all_distinct_events(self) -> list[PredictionMarketEntity]:
        all_markets = await self.prediction_markets_repository.get_distinct_events()
        return [PredictionMarketEntity.model_validate(market) for market in all_markets]

    async def execute(self) -> None:
        logger.info("Recalculate similar events")
        await self.similar_events_repository.truncate_table()

        logger.info("Completed reset similar_events")
        logger.info("Going to fetch distinct events")
        all_markets = await self.fetch_all_distinct_events()
        logger.info(f"Fetched distinct events {len(all_markets)}")
        polymarket_events: dict[str, str] = {}
        kalshi_events: dict[str, str] = {}

        for market in all_markets:
            if market.event_ticker is None or market.event_title is None:
                continue
            if market.datasource == "POLYMARKET":
                polymarket_events[market.event_ticker] = market.event_title
            elif market.datasource == "KALSHI":
                kalshi_events[market.event_ticker] = market.event_title

        if not polymarket_events or not kalshi_events:
            logger.info("No events to compare (polymarket=%d, kalshi=%d)", len(polymarket_events), len(kalshi_events))
            return

        pm_tickers = list(polymarket_events.keys())
        pm_titles = [polymarket_events[t] for t in pm_tickers]
        kalshi_tickers = list(kalshi_events.keys())
        kalshi_titles = [kalshi_events[t] for t in kalshi_tickers]

        logger.info("Loading model")

        model = get_model()

        logger.info("Create embddings")
        pm_embeddings = model.encode(pm_titles, convert_to_numpy=True)
        kalshi_embeddings = model.encode(kalshi_titles, convert_to_numpy=True)

        logger.info("Create embddings DONE")
        # cosine similarity matrix: (len_pm, len_kalshi)
        pm_norm = pm_embeddings / (np.linalg.norm(pm_embeddings, axis=1, keepdims=True) + 1e-10)
        kalshi_norm = kalshi_embeddings / (np.linalg.norm(kalshi_embeddings, axis=1, keepdims=True) + 1e-10)
        similarity_matrix = pm_norm @ kalshi_norm.T

        threshold = settings.similarity_threshold
        similar_records: list[models.SimilarEvent] = []

        for i, pm_ticker in enumerate(pm_tickers):
            j = int(np.argmax(similarity_matrix[i]))
            score = float(similarity_matrix[i][j])
            if score >= threshold:
                similar_records.append(
                    models.SimilarEvent(
                        similarity=round(score, 4),
                        polymarket_event_ticker=pm_ticker,
                        polymarket_event_title=pm_titles[i],
                        kalshi_event_ticker=kalshi_tickers[j],
                        kalshi_event_title=kalshi_titles[j],
                    )
                )

        logger.info(f"Got {len(similar_records)} similar records")

        if similar_records:
            await self.similar_events_repository.create_many(similar_records)
            logger.info("Stored %d similar market pairs", len(similar_records))
        else:
            logger.info("No similar markets found above threshold %.2f", threshold)


@dataclass(kw_only=True, frozen=True, slots=True)
class FindSimilarMarketsService:
    prediction_markets_repository: PredictionMarketsRepository
    similar_events_repository: SimilarEventsRepository
    similar_markets_repository: SimilarMarketsStagingRepository

    async def execute(self) -> None:
        logger.info("Recalculate similar markets staging")
        await self.similar_markets_repository.truncate_table()
        logger.info("Completed resetting similar_markets_staging")
        logger.info("Going to find similar markets for all similar event pairs")
        similar_events = await self.similar_events_repository.list()
        logger.info("Fetched %d similar event pairs", len(similar_events))

        model = get_model()
        threshold = settings.similarity_threshold
        total_stored = 0

        for event_pair in similar_events:
            markets = await self.prediction_markets_repository.get_markets_for_event_pair(
                event_pair.polymarket_event_ticker,
                event_pair.kalshi_event_ticker,
            )

            pm_markets = [
                (m.market_ticker, m.market_title)
                for m in markets
                if m.datasource == "POLYMARKET" and m.market_ticker and m.market_title
            ]
            kalshi_markets = [
                (m.market_ticker, m.market_title)
                for m in markets
                if m.datasource == "KALSHI" and m.market_ticker and m.market_title
            ]

            if not pm_markets or not kalshi_markets:
                logger.info(
                    "Skipping event pair %s / %s: no markets found",
                    event_pair.polymarket_event_ticker,
                    event_pair.kalshi_event_ticker,
                )
                continue

            pm_titles = [title for _, title in pm_markets]
            kalshi_titles = [title for _, title in kalshi_markets]

            pm_emb = model.encode(pm_titles, convert_to_numpy=True)
            k_emb = model.encode(kalshi_titles, convert_to_numpy=True)

            pm_norm = pm_emb / (np.linalg.norm(pm_emb, axis=1, keepdims=True) + 1e-10)
            k_norm = k_emb / (np.linalg.norm(k_emb, axis=1, keepdims=True) + 1e-10)
            sim_matrix = pm_norm @ k_norm.T

            records: list[models.SimilarMarketStaging] = []
            for i, (pm_ticker, pm_title) in enumerate(pm_markets):
                j = int(np.argmax(sim_matrix[i]))
                score = float(sim_matrix[i][j])
                if score >= threshold:
                    records.append(
                        models.SimilarMarketStaging(
                            similarity=round(score, 4),
                            polymarket_event_ticker=event_pair.polymarket_event_ticker,
                            polymarket_event_title=event_pair.polymarket_event_title,
                            polymarket_market_ticker=pm_ticker,
                            polymarket_market_title=pm_title,
                            kalshi_event_ticker=event_pair.kalshi_event_ticker,
                            kalshi_event_title=event_pair.kalshi_event_title,
                            kalshi_market_ticker=kalshi_markets[j][0],
                            kalshi_market_title=kalshi_markets[j][1],
                        )
                    )

            if records:
                await self.similar_markets_repository.create_many(records)
                total_stored += len(records)
                logger.info(
                    "Stored %d similar market pairs for event pair %s / %s",
                    len(records),
                    event_pair.polymarket_event_ticker,
                    event_pair.kalshi_event_ticker,
                )

        logger.info("Done. Stored %d similar market pairs in similar_markets_staging", total_stored)
