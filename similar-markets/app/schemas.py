import datetime

import pydantic
from pydantic import BaseModel


class Base(BaseModel):
    model_config = pydantic.ConfigDict(from_attributes=True)


class PredictionMarketEntity(Base):
    datasource: str
    event_id: str | None = None
    event_ticker: str | None = None
    event_title: str | None = None
    event_subtitle: str | None = None
    event_description: str | None = None
    market_ticker: str | None = None
    market_open_datetime: datetime.datetime | None = None
    market_close_datetime: datetime.datetime | None = None
    market_title: str | None = None
    market_description: str | None = None
    market_status: str | None = None
    market_result: str | None = None
    market_raw_data: str | None = None
