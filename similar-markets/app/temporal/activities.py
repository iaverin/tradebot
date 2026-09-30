import logging

import modern_di
from temporalio import activity

from app import ioc
from app.services import FindSimilarEventsService, FindSimilarMarketsService


logger = logging.getLogger(__name__)
container = modern_di.Container(groups=[ioc.Dependencies])


@activity.defn(name="find_similar_events")
async def find_similar_events_activity() -> None:
    logger.info("Activity find_similar_events started")
    activity_container = container.build_child_container(scope=modern_di.Scope.STEP)
    service = activity_container.resolve(FindSimilarEventsService)
    await service.execute()
    logger.info("Activity find_similar_events finished")
    await activity_container.close_async()


@activity.defn(name="find_similar_markets")
async def find_similar_markets_activity() -> None:
    logger.info("Activity find_similar_markets started")
    activity_container = container.build_child_container(scope=modern_di.Scope.STEP)
    service = activity_container.resolve(FindSimilarMarketsService)
    await service.execute()
    logger.info("Activity find_similar_markets finished")
    await activity_container.close_async()
