# Spec: Opinion.trade Markets Fetcher

## 1. Goal

Add a third prediction-market data source — **opinion.trade** — to the backend
`marketsfetcher` module. The fetcher must fetch the full market catalogue from the
opinion.trade Open API, map it into the existing `PredictionMarket` entity, persist it
into the `fetching_prediction_markets` staging table, and participate in the existing
Temporal `MarketsRefreshWorkflow` alongside Kalshi and Polymarket.

The implementation must mirror the existing `KalshiFetcher` / `PolymarketFetcher`
patterns as closely as possible so that the new source slots into the existing
fetch → publish → similar-markets pipeline with no structural changes.

Work in the `backend/` directory.

## 2. Reference implementation

Mirror these existing classes (all under
`backend/src/main/java/hzpro/com/tradingdesk/marketsfetcher`):

- `service/fetchers/PolymarketFetcher.java`, `service/fetchers/KalshiFetcher.java`
- `lib/BaseFetcher.java`, `lib/FetcherState.java`
- `mapper/PolymarketMapper.java`, `mapper/KalshiMapper.java`
- `dto/polymarket/*`, `dto/kalshi/*`
- `temporal/activity/FetchActivity.java` + `FetchActivityImpl.java`
- `temporal/workflow/MarketsRefreshWorkflowImpl.java`
- `service/FetcherControllerService.java`
- `entity/PredictionMarket.java`, `entity/enums/DataSource.java`

## 3. opinion.trade Open API summary

Source: <https://docs.opinion.trade/developer-guide/opinion-open-api>

### 3.1 Base URL
The docs are inconsistent about the host. Observed candidates:
- `https://openapi.opinion.trade/openapi`
- `https://proxy.opinion.trade:8443/openapi`

> **Action item:** make the base URL a configurable property
> (`fetcher.opinion.base-url`) defaulting to `https://openapi.opinion.trade/openapi`,
> and confirm the live host during implementation. Do **not** hardcode it inline like
> the Kalshi/Polymarket fetchers do — the host is unverified.

### 3.2 Authentication
- Header-based: `apikey: <your_api_key>` on **every** request. HTTPS only.
- This is a new requirement: `BaseFetcher.executeRequest` currently sends **no headers**
  (Kalshi/Polymarket are unauthenticated). See §6 for the required `BaseFetcher` change.
- api key is stored in backend/.env file

### 3.3 Rate limits
- 15 requests/second per API key; `429 Too Many Requests` on exceed.
- Max **20 items per page**.

### 3.4 Endpoint used for fetching: Market List
`GET /market`

Query parameters:
| Param        | Type    | Notes |
|--------------|---------|-------|
| `page`       | integer | default 1, min 1 |
| `limit`      | integer | default 10, **max 20** |
| `status`     | string  | `activated` \| `resolved` — use `activated` (open markets) |
| `marketType` | integer | 0=Binary, 1=Categorical, 2=All — use `2` (All) |
| `sortBy`     | integer | 1=newest … (optional) |
| `chainId`    | string  | optional filter |
| `labelId`    | integer | optional filter |

Pagination is **page-number based** (unlike Kalshi/Polymarket cursors).

Response shape:
```json
{
  "code": 0,
  "msg": "ok",
  "result": {
    "total": 1234,
    "list": [ MarketData, ... ]
  }
}
```

`MarketData` fields (list item):
| Field          | Type            | Maps to |
|----------------|-----------------|---------|
| `marketId`     | int64           | event/market id |
| `marketTitle`  | string          | title |
| `status`       | int (1–6)       | numeric status |
| `statusEnum`   | string          | human-readable status |
| `marketType`   | int (0/1)       | 0=Binary, 1=Categorical |
| `childMarkets` | array           | present for categorical |
| `yesLabel`     | string          | |
| `noLabel`      | string          | |
| `rules`        | string          | description |
| `yesTokenId`   | string          | yes token id |
| `noTokenId`    | string          | no token id |
| `conditionId`  | string          | condition id |
| `resultTokenId`| string          | resolved result token |
| `volume`       | string          | |
| `volume24h`    | string          | |
| `volume7d`     | string          | |
| `quoteToken`   | string          | |
| `chainId`      | string          | |
| `questionId`   | string          | |
| `createdAt`    | int64 (epoch)   | open datetime |
| `cutoffAt`     | int64 (epoch)   | close datetime |
| `resolvedAt`   | int64 (epoch)   | |
| `labels`       | string[]        | |
| `labelIds`     | int64[]         | |

`childMarkets[]` (`ChildMarketData`) carries the same core fields:
`marketId, marketTitle, status, statusEnum, yesLabel, noLabel, rules,
yesTokenId, noTokenId, conditionId, resultTokenId, volume, quoteToken, chainId,
questionId, createdAt, cutoffAt, resolvedAt`.

### 3.5 Enums
- **Market status:** 1=Created, 2=Activated, 3=Resolving, 4=Resolved, 5=Failed, 6=Deleted
- **Market type:** 0=Binary, 1=Categorical
- **Outcome side:** 1=Yes, 2=No
- **Trade status:** 1=Pending, 2=Filled, 3=Canceled, 4=Expired, 5=Failed

> **Timestamp note:** `createdAt`/`cutoffAt`/`resolvedAt` are epoch integers. Confirm
> seconds vs milliseconds against live data before converting to `ZonedDateTime`
> (a value ~1.7e9 is seconds; ~1.7e12 is millis).

> The Trade endpoint (`GET /trade/user/{walletAddress}`) is **out of scope** for this
> fetcher (it is per-wallet, not a market catalogue). Documented here only for context.

## 4. Domain mapping

Map opinion.trade markets onto `PredictionMarket` using the same event→markets
decomposition Polymarket uses (`PolymarketMapper`):

- **Binary market** (`marketType == 0`): the top-level market is both the "event" and
  a single market row.
- **Categorical market** (`marketType == 1`): the top-level market is the "event"; each
  entry in `childMarkets` becomes one market row.

`PredictionMarket` field assignment:

| PredictionMarket field   | Source (binary)            | Source (categorical child)        |
|--------------------------|----------------------------|-----------------------------------|
| `datasource`             | `DataSource.OPINION`       | `DataSource.OPINION`              |
| `eventId`                | `marketId` (string)        | parent `marketId` (string)        |
| `eventTicker`            | `questionId` (or `marketId`) | parent `questionId`             |
| `eventTitle`             | `marketTitle`              | parent `marketTitle`              |
| `eventSubtitle`          | `null`                     | `null`                            |
| `eventDescription`       | `rules`                    | parent `rules`                    |
| `marketTicker`           | `conditionId` (fallback `marketId`) | child `conditionId`      |
| `marketTitle`            | `marketTitle`              | child `marketTitle`               |
| `marketDescription`      | `rules`                    | child `rules`                     |
| `marketOpenDatetime`     | `createdAt` → ZonedDateTime| child `createdAt`                 |
| `marketCloseDatetime`    | `cutoffAt` → ZonedDateTime | child `cutoffAt`                  |
| `marketStatus`           | `statusEnum`               | child `statusEnum`                |
| `marketResult`           | JSON (see below)           | JSON (see below)                  |
| `marketRawData`          | `""`                       | `""`                              |
| `yesTokenId`             | `yesTokenId`               | child `yesTokenId`                |
| `noTokenId`              | `noTokenId`                | child `noTokenId`                 |
| `conditionId`            | `conditionId`              | child `conditionId`               |

`marketResult` — serialize a small JSON object (mirroring `PolymarketMapper.produceMarketResult`)
with: `yesLabel`, `noLabel`, `volume`, `resultTokenId`. The opinion list endpoint does
not return live outcome prices, so prices are omitted.

No schema/migration change is required: all target columns already exist on
`fetching_prediction_markets` (V10 added `yes_token_id`/`no_token_id`,
later migrations added `condition_id`).

## 5. New / changed files

### 5.1 New files
```
dto/opinion/OpinionResponseDto.java     // { int code; String msg; OpinionResultDto result; }
dto/opinion/OpinionResultDto.java       // { long total; List<OpinionMarketDto> list; }
dto/opinion/OpinionMarketDto.java       // list item + List<OpinionChildMarketDto> childMarkets
dto/opinion/OpinionChildMarketDto.java  // child market fields
mapper/OpinionMapper.java               // mapToPredictionMarketList(String rawListJson)
service/fetchers/OpinionFetcher.java    // extends BaseFetcher
```
All DTOs annotated `@Data @JsonIgnoreProperties(ignoreUnknown = true)` like the
Polymarket DTOs.

### 5.2 Changed files
- `entity/enums/DataSource.java` — add `OPINION`.
- `lib/BaseFetcher.java` — add header support to `RequestParameters` + `executeRequest`
  (see §6).
- `temporal/activity/FetchActivity.java` — add `void fetchOpinion();`
- `temporal/activity/FetchActivityImpl.java` — inject `OpinionFetcher`, implement
  `fetchOpinion()` → `opinionFetcher.fetchAll()`.
- `temporal/workflow/MarketsRefreshWorkflowImpl.java` — add a third async promise
  `Async.procedure(fetchActivity::fetchOpinion)` and `.get()` it alongside the Kalshi
  and Polymarket promises (before `publishFetchedMarkets()`).
- `service/FetcherControllerService.java` — inject `OpinionFetcher`; add
  `fetchOpinionData()` (manual trigger via `FetchWorkflow`, provider `"OPINION"`); include
  it in `stopTasks()` / `pauseTasks()` / `resumeTasks()`.
- `temporal/workflow/FetchWorkflowImpl.java` — handle the `"OPINION"` provider string in
  the same switch/dispatch that handles `"KALSHI"` / `"POLYMARKET"`.
- `controller/MarketsFetcherController.java` — inject `OpinionFetcher`; add manual-trigger
  and status endpoints mirroring the Polymarket/Kalshi ones.
- `application.properties` — add config (see §7).

## 6. BaseFetcher header support (required change)

`OpinionFetcher` needs the `apikey` header on every request. Extend the shared base
minimally and backwards-compatibly:

- Add `Map<String,String> headers` to `BaseFetcher.RequestParameters` (builder).
- In `executeRequest`, after building `HttpGet`, apply headers when present:
  ```java
  if (params.getHeaders() != null) {
      params.getHeaders().forEach(httpGet::addHeader);
  }
  ```
- Kalshi/Polymarket continue to pass no headers (null) → unchanged behaviour.

`OpinionFetcher.prepareRequest` sets `headers = Map.of("apikey", apiKey)`.

## 7. OpinionFetcher behaviour

Constructor injects `CloseableHttpClient`, `PredictionMarketRepository`,
`OpinionMapper`, `ObjectMapper`, plus `@Value`-injected `fetcher.opinion.base-url` and
`fetcher.opinion.api-key`.

- `prepareRequest(state)`:
  - query params: `status=activated`, `marketType=2`, `limit=20`, `page=<n>`.
  - derive `page` from state. Since `FetcherState` has no page field, track it via the
    request number: `page = state.getRequestNumber() + 1` (1-based). Alternatively store
    the current page in `responseText` like the cursor pattern — prefer the
    `requestNumber`-derived page for simplicity.
  - set `apikey` header.
  - URL = `<base-url>/market`.
- `parseResponse(response)`: deserialize `OpinionResponseDto`, hand `result.list`
  (re-serialized to JSON, mirroring Polymarket) to `OpinionMapper`. Guard on
  `code != 0` / null result → log and return `List.of()`.
- `hasMoreData(response, itemCount)`: parse `result.total` and stop when
  `page * limit >= total` **or** `itemCount == 0`. (Robust against the cursor-style
  base classes.)
- `initialState()`: `FetcherState.initial(baseUrl + "/market")` with an empty
  `responseText` seed.

Respect the 15 req/s limit. The catalogue is small relative to Polymarket; the existing
per-page `@Retryable` backoff plus a modest inter-page pause (mirror Polymarket's
`RATE_LIMIT_PAUSE_MS`/`REQUESTS_PER_PAUSE` only if needed) is sufficient. A `429` will be
retried by `@Retryable`.

## 8. Configuration (`application.properties`)

```properties
fetcher.opinion.base-url=${OPINION_BASE_URL:https://openapi.opinion.trade/openapi}
fetcher.opinion.api-key=${OPINION_API_KEY:}
```
Document `OPINION_API_KEY` in `.env` / deployment config. The fetcher should fail fast
with a clear log message if the API key is blank.

## 9. Acceptance criteria

1. `./gradlew build` passes (compile + existing tests).
2. `DataSource.OPINION` persists and round-trips via the entity enum mapping.
3. A manual trigger (new controller endpoint) fetches all `activated` opinion.trade
   markets and writes rows into `fetching_prediction_markets` with `datasource='OPINION'`,
   non-null `event_id`, `market_ticker`, `market_title`, and parsed open/close datetimes.
4. Categorical markets expand into one row per `childMarket`; binary markets produce one row.
5. `yes_token_id` / `no_token_id` / `condition_id` populated where present in the API.
6. `MarketsRefreshWorkflow` runs Opinion fetch concurrently with Kalshi & Polymarket, then
   publishes into `prediction_markets` and triggers similar-markets — no regressions to the
   existing two sources.
7. Pagination terminates correctly (no infinite loop) when `page*limit >= total`.
8. Missing/blank `OPINION_API_KEY` is logged clearly and does not crash the other fetchers.

## 10. Open questions / verify during implementation

- Confirm the live base URL/host (`openapi` vs `proxy:8443`).
- Confirm epoch unit (seconds vs millis) of `createdAt`/`cutoffAt`.
- Confirm the exact JSON for `code` on success (`0` vs `200`) and the `list`/`result`
  envelope field names against a live response.
- Decide whether to also ingest `resolved` markets or only `activated` (spec assumes
  `activated` to match Kalshi `status=open` / Polymarket `active=true,closed=false`).
