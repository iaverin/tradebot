import logging
import typing

import fastapi
from modern_di_fastapi import FromDI

from app.services import FindSimilarEventsService, FindSimilarMarketsService


logger = logging.getLogger(__name__)

ROUTER: typing.Final = fastapi.APIRouter()

@ROUTER.post("/similar-markets/produce-similar-events")
async def produce_similar_events(
    similar_markets_service: FindSimilarEventsService = FromDI(FindSimilarEventsService),
) -> dict[str, str]:
    logger.info("Got request to produce similar events")

    await similar_markets_service.execute()
    return {"result": "ok"}


@ROUTER.post("/similar-markets/produce-similar-markets", summary="Produce similar markets staging data")
async def produce_similar_markets(
    service: FindSimilarMarketsService = FromDI(FindSimilarMarketsService),
) -> dict[str, str]:
    logger.info("Got request to produce similar markets staging data")

    await service.execute()

    return {"result": "ok"}
