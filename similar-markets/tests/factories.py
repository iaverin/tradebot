from polyfactory.factories.pydantic_factory import ModelFactory
from polyfactory.factories.sqlalchemy_factory import SQLAlchemyFactory

from app import models, schemas


class PredictionMarketModelFactory(SQLAlchemyFactory[models.PredictionMarket]):
    __set_association_proxy__ = False
    __set_relationships__ = False
    __check_model__ = False
    id = None


class PredictionMarketEntityFactory(ModelFactory[schemas.PredictionMarketEntity]):
    __set_association_proxy__ = False
    __set_relationships__ = False
    __check_model__ = False


class SimilarEventModelFactory(SQLAlchemyFactory[models.SimilarEvent]):
    __set_association_proxy__ = False
    __set_relationships__ = False
    __check_model__ = False
    id = None
