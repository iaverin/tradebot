from modern_di import Group, Scope, providers

from app.repositories import PredictionMarketsRepository, SimilarEventsRepository, SimilarMarketsStagingRepository
from app.resources.db import close_sa_engine, close_session, create_sa_engine, create_session
from app.services import FindSimilarEventsService, FindSimilarMarketsService


class Dependencies(Group):
    database_engine = providers.Factory(
        creator=create_sa_engine, cache_settings=providers.CacheSettings(finalizer=close_sa_engine)
    )
    session = providers.Factory(
        scope=Scope.STEP, creator=create_session, cache_settings=providers.CacheSettings(finalizer=close_session)
    )

    prediction_markets_repository = providers.Factory(
        scope=Scope.STEP,
        creator=PredictionMarketsRepository,
        kwargs={"auto_commit": True, "session": session},
    )
    similar_events_repository = providers.Factory(
        scope=Scope.STEP,
        creator=SimilarEventsRepository,
        kwargs={"auto_commit": True, "session": session},
    )
    similar_markets_repository = providers.Factory(
        scope=Scope.STEP,
        creator=SimilarMarketsStagingRepository,
        kwargs={"auto_commit": True, "session": session},
    )

    similar_events_service = providers.Factory(
        scope=Scope.STEP,
        creator=FindSimilarEventsService,
    )
    find_similar_markets_service = providers.Factory(
        scope=Scope.STEP,
        creator=FindSimilarMarketsService,
    )
