from datetime import timedelta

from temporalio import workflow
from temporalio.common import RetryPolicy


with workflow.unsafe.imports_passed_through():
    from app.temporal.activities import find_similar_events_activity, find_similar_markets_activity


WORKFLOW_NAME = "find_similar_market"


@workflow.defn(name=WORKFLOW_NAME)
class FindSimilarMarketWorkflow:
    @workflow.run
    async def run(self) -> None:
        retry_policy = RetryPolicy(maximum_attempts=1, initial_interval=timedelta(seconds=5))

        await workflow.execute_activity(
            find_similar_events_activity,
            start_to_close_timeout=timedelta(hours=20),
            retry_policy=retry_policy,
        )

        await workflow.execute_activity(
            find_similar_markets_activity,
            start_to_close_timeout=timedelta(hours=20),
            retry_policy=retry_policy,
        )
