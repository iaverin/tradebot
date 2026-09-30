import runpy
from unittest import mock

import modern_di
import pytest
from sqlalchemy.ext.asyncio import AsyncSession

from app import ioc


async def test_session() -> None:
    container = modern_di.Container(groups=[ioc.Dependencies])
    step_container = container.build_child_container(scope=modern_di.Scope.STEP)
    try:
        step_container.resolve(AsyncSession)
    finally:
        await step_container.close_async()
        await container.close_async()
