import tempfile
import typing
import uuid
from collections.abc import Awaitable, Callable
from pathlib import Path

import pytest
from polyfactory.factories.sqlalchemy_factory import SQLAlchemyFactory
from sqlalchemy.ext.asyncio import AsyncSession
from temporalio.testing import WorkflowEnvironment
from temporalio.worker import Worker

from app import ioc
from app.resources.db import create_sa_engine
from app.temporal.activities import container, find_similar_events_activity, find_similar_markets_activity
from app.temporal.workflows import FindSimilarMarketWorkflow


RunWorker = Callable[[], Awaitable[None]]


@pytest.fixture
async def db_session() -> typing.AsyncIterator[AsyncSession]:
    engine = create_sa_engine()
    connection = await engine.connect()
    transaction = await connection.begin()
    await connection.begin_nested()
    container.override(ioc.Dependencies.database_engine, connection)

    try:
        yield AsyncSession(connection, expire_on_commit=False, autoflush=False)
    finally:
        container.reset_override()
        if connection.in_transaction():
            await transaction.rollback()
        await connection.close()
        await engine.dispose()


@pytest.fixture
async def set_async_session_in_base_sqlalchemy_factory(
    db_session: AsyncSession,
) -> typing.AsyncIterator[None]:
    try:
        SQLAlchemyFactory.__async_session__ = db_session
        yield
    finally:
        SQLAlchemyFactory.__async_session__ = None


@pytest.fixture
async def temporal_environment() -> typing.AsyncIterator[WorkflowEnvironment]:
    download_directory = Path(tempfile.gettempdir()) / "similar-markets-temporal-test-server"
    download_directory.mkdir(exist_ok=True)
    environment = await WorkflowEnvironment.start_time_skipping(download_dest_dir=str(download_directory))
    try:
        yield environment
    finally:
        await environment.shutdown()


@pytest.fixture
async def run_worker(
    db_session: AsyncSession,  # noqa: ARG001 — keep the DI container override active while the worker runs
    temporal_environment: WorkflowEnvironment,
) -> typing.AsyncIterator[RunWorker]:
    task_queue = f"similar-markets-test-{uuid.uuid4()}"

    async def execute_workflow() -> None:
        await temporal_environment.client.execute_workflow(
            FindSimilarMarketWorkflow.run,
            id=f"find-similar-markets-test-{uuid.uuid4()}",
            task_queue=task_queue,
        )

    async with Worker(
        temporal_environment.client,
        task_queue=task_queue,
        workflows=[FindSimilarMarketWorkflow],
        activities=[find_similar_events_activity, find_similar_markets_activity],
    ):
        yield execute_workflow
