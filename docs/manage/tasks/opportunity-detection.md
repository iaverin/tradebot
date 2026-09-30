# ТЗ: Арбитражный монитор цен (Polymarket + Kalshi)

## Цель

Разработать сервис, который в режиме реального времени отслеживает цены ордербуков
на парах маркетов Polymarket / Kalshi через WebSocket и фиксирует арбитражные
возможности, когда разница между ценами продажи противоположных исходов превышает
заданный порог `THRESHOLD`.

---

## Контекст

В таблице `similar_markets` хранятся пары маркетов (Polymarket ↔ Kalshi),
сматченных по семантическому сходству названий:

```
similar_markets
  id
  similarity                   -- cosine similarity [0..1]
  polymarket_event_ticker
  polymarket_event_title
  polymarket_market_ticker     -- slug маркета Polymarket
  polymarket_market_title
  kalshi_event_ticker
  kalshi_event_title
  kalshi_market_ticker         -- ticker маркета Kalshi (формат: EVENT-SUFFIX)
  kalshi_market_title
```

Для каждой такой пары сервис должен подписаться на обновления ордербуков
на обеих платформах и детектировать арбитраж.

---

## WebSocket API

### Polymarket — CLOB WebSocket

**Endpoint:** `wss://ws-subscriptions-clob.polymarket.com/ws/`

**Документация:** https://docs.polymarket.com/#websocket

**Авторизация:** не требуется для чтения публичного ордербука.

**Подготовка:** `polymarket_market_ticker` хранит slug маркета. Перед подпиской
нужно получить `conditionId` через REST:

```
GET https://clob.polymarket.com/markets/{slug}
```

Ответ содержит `condition_id` и токены исходов (`tokens[].token_id`):
- `tokens[0]` — YES-токен (`token_id` — assset_id для подписки)
- `tokens[1]` — NO-токен

**Подписка на ордербук (по одному токену за раз):**

```json
{
  "type": "subscribe",
  "channel": "market",
  "assets_ids": ["<YES_token_id>", "<NO_token_id>"]
}
```

**Формат входящих сообщений (price change):**

```json
{
  "event_type": "price_change",
  "market": "0x5f65177b394277fd294cd75650044e32ba009a95022d88a0c1d565897d72f8f1",
  "price_changes": [
    {
      "asset_id": "71321045679252212594626385532706912750332728571942532289631379312455583992563",
      "price": "0.5",
      "size": "200",
      "side": "BUY",
      "hash": "56621a121a47ed9333273e21c83b660cff37ae50",
      "best_bid": "0.5",
      "best_ask": "1"
    }
  ],
  "timestamp": "1757908892351"
}

```
Нам нужен `best_ask`

Цены — десятичные, диапазон [0, 1]. Лучшая цена Ask = минимальная цена среди
активных заявок на продажу (SELL). При `size == "0"` заявка удалена.

Необходимо поддерживать локальный снимок ордербука для каждого токена.

---

### Kalshi — Market Ticker WebSocket

**Endpoint:** `wss://trading-api.kalshi.com/trade-api/ws/v2`

**Документация:** https://docs.kalshi.com/websockets/market-ticker

По калши подумать


**Подписка на Маркет тикер:**

!!! Проверить запрос


```json
{
  "id": 1,
  "cmd": "subscribe",
  "params": {
    "channels": ["ticker"],
    "market_tickers": ["<kalshi_market_ticker>"]
  }
}
```


**Формат обновлений:**

```json
{
  "type": "ticker",
  "sid": 11,
  "msg": {
    "market_ticker": "FED-23DEC-T3.00",
    "market_id": "9b0f6b43-5b68-4f9f-9f02-9a2d1b8ac1a1",
    "price_dollars": "0.480",
    "yes_bid_dollars": "0.450",
    "yes_ask_dollars": "0.530",
    "volume_fp": "33896.00",
    "open_interest_fp": "20422.00",
    "dollar_volume": 16948,
    "dollar_open_interest": 10211,
    "yes_bid_size_fp": "300.00",
    "yes_ask_size_fp": "150.00",
    "last_trade_size_fp": "25.00",
    "ts": 1669149841,
    "time": "2022-11-22T20:44:01Z"
  }
}```


Лучший Ask YES = минимальная цена с ненулевым объёмом среди уровней продажи YES.
Пока что цену на NO - считаем 1 - цена на YES

Следующей итерацией запустим сбор по ордер буку

---

## Логика детектирования арбитража

На Polymarket и Kalshi исходы бинарные: YES / NO.
Цены нормализованы к [0, 1], где цена YES + цена NO ≈ 1.

При наличии разрыва между платформами возможны два направления:

**Направление 1 (PM-YES / KS-NO):**
```
spread_1 = 1 - (polymarket_yes_ask + kalshi_no_ask)
```
Если `spread_1 > THRESHOLD` — арбитражная возможность:
купить YES на Polymarket + купить NO на Kalshi.

**Направление 2 (PM-NO / KS-YES):**
```
spread_2 = 1 - (polymarket_no_ask + kalshi_yes_ask)
```
Если `spread_2 > THRESHOLD` — арбитражная возможность:
купить NO на Polymarket + купить YES на Kalshi.

> Спред > 0 означает, что в сумме за полный набор исходов заплачено менее
> 1 доллара / 1 (нормализованная единица). При исходе одна из позиций выплатит
> ровно 1 — всё выше нуля есть прибыль.

Рекомендуемое значение `THRESHOLD` по умолчанию: **0.05** (т.е. 5 цента на доллар).

---

## Хранение событий

### Жизненный цикл арбитражной возможности

Каждая арбитражная возможность имеет два события:

- **`OPPORTUNITY_START`** — момент, когда спред впервые превысил `THRESHOLD`.
  После записи пара переходит в состояние «активного мониторинга».
- **`OPPORTUNITY_END`** — момент, когда спред опустился ниже `THRESHOLD` (возможность закрылась).
  Записывается со ссылкой на соответствующий `OPPORTUNITY_START`.

Переход `START → END` должен быть атомарным в рамках одной пары и одного направления:
пока для пары есть незакрытый `OPPORTUNITY_START`, новый `OPPORTUNITY_START` для того
же направления не создаётся.

### Схема таблицы

```sql
CREATE TABLE arbitrage_events (
    id                       BIGSERIAL PRIMARY KEY,
    detected_at              TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    -- Жизненный цикл
    event_type               VARCHAR(20) NOT NULL, -- 'OPPORTUNITY_START' | 'OPPORTUNITY_END'
    opportunity_start_id     BIGINT REFERENCES arbitrage_events(id), -- NULL для START, заполнен для END

    -- Пара маркетов
    similar_market_id        BIGINT NOT NULL REFERENCES similar_markets(id),
    polymarket_market_ticker TEXT NOT NULL,
    kalshi_market_ticker     TEXT NOT NULL,

    -- Направление арбитража
    direction                VARCHAR(20) NOT NULL, -- 'PM_YES_KS_NO' | 'PM_NO_KS_YES'

    -- Лучшие цены в момент события
    polymarket_yes_ask       NUMERIC(8, 6),
    polymarket_no_ask        NUMERIC(8, 6),
    kalshi_yes_ask           NUMERIC(8, 6),
    kalshi_no_ask            NUMERIC(8, 6),

    -- Вычисленный спред
    spread                   NUMERIC(8, 6) NOT NULL,
    threshold                NUMERIC(8, 6) NOT NULL,

    -- Полный снимок ордербуков в момент события (JSON)
    polymarket_orderbook     JSONB NOT NULL, -- {"yes": [["0.62", "150"], ...], "no": [...]}
    kalshi_orderbook         JSONB NOT NULL  -- {"yes": [[62, 100], ...], "no": [...]}
);

CREATE INDEX idx_arb_events_detected_at    ON arbitrage_events(detected_at DESC);
CREATE INDEX idx_arb_events_similar_market ON arbitrage_events(similar_market_id);
CREATE INDEX idx_arb_events_start_id       ON arbitrage_events(opportunity_start_id);
CREATE INDEX idx_arb_events_type           ON arbitrage_events(event_type);
```

### Формат снимка ордербука

Ордербук сохраняется как JSONB в нормализованном виде (цены [0,1]):

```json
{
  "yes": [["0.62", "150"], ["0.60", "300"]],
  "no":  [["0.41", "200"], ["0.39", "100"]]
}
```

Уровни сортируются по цене по возрастанию. Хранятся только уровни с ненулевым объёмом.


### Алгоритм работы

1. При старте загрузить все активные пары из `similar_markets`.
2. По каждой паре:
   - (это потом вынесем в первоначальный сбор данных) Для Polymarket: GET `/markets/{slug}` → получить `conditionId`, `YES token_id`, `NO token_id`.
   - Для Kalshi: тикер уже есть в таблице.
3. Открыть два долгоживущих WebSocket-соединения (одно на платформу).
4. Подписаться на ордербуки всех нужных токенов / тикеров в одном соединении.
5. На каждое обновление:
   - Извлечь лучшие цены Ask YES/NO для обеих платформ.
   - Проверить оба направления арбитража для пары:
     - **Пара НЕ в активном мониторинге** и спред > THRESHOLD →
       записать `OPPORTUNITY_START` со снимком ордербуков обеих платформ,
       перевести пару в состояние «активного мониторинга» (хранить `start_id` в памяти).
     - **Пара в активном мониторинге** и спред ≤ THRESHOLD →
       записать `OPPORTUNITY_END` (со ссылкой на `start_id`) со снимком ордербуков,
       снять пометку активного мониторинга.
     - **Пара в активном мониторинге** и спред всё ещё > THRESHOLD → ничего не писать.
6. В момент записи события запросить ордер бук по REST по записать в базу
6. При разрыве соединения — переподключаться с exponential backoff;
   состояние «активного мониторинга» сбрасывать (считать возможность закрытой).

