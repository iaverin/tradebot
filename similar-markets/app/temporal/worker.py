import asyncio
import logging

from temporalio.client import Client
from temporalio.worker import Worker

from app.settings import settings
from app.temporal.activities import find_similar_events_activity, find_similar_markets_activity
from app.temporal.workflows import FindSimilarMarketWorkflow


logging.basicConfig(level=getattr(logging, settings.log_level.upper(), logging.INFO))
logger = logging.getLogger(__name__)


async def main() -> None:
    logger.info(
        "Connecting to Temporal at %s (namespace=%s, task_queue=%s)",
        settings.temporal_host,
        settings.temporal_namespace,
        settings.temporal_task_queue,
    )
    client = await Client.connect(
        settings.temporal_host,
        namespace=settings.temporal_namespace,
    )

    worker = Worker(
        client,
        task_queue=settings.temporal_task_queue,
        workflows=[FindSimilarMarketWorkflow],
        activities=[find_similar_events_activity, find_similar_markets_activity],
    )

    logger.info("Worker started on task queue '%s'", settings.temporal_task_queue)
    await worker.run()


if __name__ == "__main__":
    asyncio.run(main())
