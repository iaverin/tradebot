# Implementation Plan: Opinion.trade Markets Fetcher

Trackable task plan for [`spec-opinion-trade-fetcher.md`](../spec-opinion-trade-fetcher.md).

- **Module:** `backend/src/main/java/hzpro/com/tradingdesk/marketsfetcher`
- **Branch:** `feature/opinion-trade-fetcher`
- **Status legend:** `[ ]` todo · `[~]` in progress · `[x]` done · `[!]` blocked

---

## Progress overview

| Phase | Description | Status |
|-------|-------------|--------|
| 0 | Pre-flight / API verification | [x] |
| 1 | Domain enum + DTOs | [x] |
| 2 | BaseFetcher header support | [x] |
| 3 | Mapper | [x] |
| 4 | OpinionFetcher | [x] |
| 5 | Temporal + service wiring | [x] |
| 6 | Controller + config | [x] |
| 7 | Build, test, verify | [x] |

### Phase 0 findings (verified live against the API)
- **Base URL:** `https://openapi.opinion.trade/openapi` works (the `proxy:8443` host is not needed).
- **Envelope:** the real fields are `errno` / `errmsg` / `result` (NOT `code` / `msg`). Success = `errno == 0`.
- **`result`:** `{ total, list }` — `total` confirmed present (e.g. 207 for `marketType=2`).
- **Epoch unit:** **seconds** (`createdAt` ~`1.76e9`); `0` means "unset" (treated as null).
- **Binary (`marketType=0`):** top-level `yesTokenId`/`noTokenId` populated, `childMarkets` empty, `conditionId` often blank → ticker falls back to `marketId`.
- **Categorical (`marketType=1`):** parent token ids blank, `childMarkets` carries per-outcome token ids.
- **Scope:** `status=activated` only (mirrors Kalshi `open` / Polymarket `active`).

---

## Phase 0 — Pre-flight / API verification
Resolve the spec's open questions before writing mapping code.

- [x] 0.1 Confirm live base URL/host (`openapi.opinion.trade` vs `proxy.opinion.trade:8443`)
- [x] 0.2 Obtain an `OPINION_API_KEY` from backend/.env file; make a real `GET /market?status=activated&marketType=2&limit=20&page=1` call
- [x] 0.3 Capture a sample response JSON (binary + categorical) into the PR description / scratch notes
- [x] 0.4 Confirm success `code` value (`0` vs `200`) and `result/list/total` envelope field names
- [x] 0.5 Confirm epoch unit of `createdAt` / `cutoffAt` / `resolvedAt` (seconds vs millis)
- [x] 0.6 Decide scope: `activated` only (default) vs include `resolved`

**Exit:** sample response saved; base URL, code value, and epoch unit known.

## Phase 1 — Domain enum + DTOs
- [x] 1.1 Add `OPINION` to `entity/enums/DataSource.java`
- [x] 1.2 `dto/opinion/OpinionResponseDto.java` — `{ int code; String msg; OpinionResultDto result; }`
- [x] 1.3 `dto/opinion/OpinionResultDto.java` — `{ long total; List<OpinionMarketDto> list; }`
- [x] 1.4 `dto/opinion/OpinionMarketDto.java` — list-item fields + `List<OpinionChildMarketDto> childMarkets`
- [x] 1.5 `dto/opinion/OpinionChildMarketDto.java` — child fields
- [x] 1.6 Annotate all DTOs `@Data` + `@JsonIgnoreProperties(ignoreUnknown = true)`

**Exit:** DTOs deserialize the Phase 0 sample without error.

## Phase 2 — BaseFetcher header support
- [x] 2.1 Add `Map<String,String> headers` to `BaseFetcher.RequestParameters` (builder)
- [x] 2.2 In `executeRequest`, apply headers to `HttpGet` when non-null
- [x] 2.3 Verify Kalshi/Polymarket still pass no headers → unchanged behaviour

**Exit:** existing fetchers compile/run unchanged; header path available.

## Phase 3 — Mapper
- [x] 3.1 `mapper/OpinionMapper.java` with `mapToPredictionMarketList(String rawListJson)`
- [x] 3.2 Binary (`marketType==0`): one `PredictionMarket` row (market = event)
- [x] 3.3 Categorical (`marketType==1`): one row per `childMarkets` entry, parent as event
- [x] 3.4 Field mapping per spec §4 (eventId/ticker/title, market ticker/title, token ids, conditionId)
- [x] 3.5 Epoch → `ZonedDateTime` for open/close datetimes (unit from 0.5)
- [x] 3.6 `marketResult` JSON = `{ yesLabel, noLabel, volume, resultTokenId }`
- [x] 3.7 Null-safe fallbacks (e.g. `marketTicker` ← `conditionId` else `marketId`)

**Exit:** mapper turns the sample JSON into correct `PredictionMarket` objects (unit-checked).

## Phase 4 — OpinionFetcher
- [x] 4.1 `service/fetchers/OpinionFetcher.java extends BaseFetcher`
- [x] 4.2 Inject `CloseableHttpClient`, repo, `OpinionMapper`, `ObjectMapper`, `@Value` base-url + api-key
- [x] 4.3 `prepareRequest`: `status=activated`, `marketType=2`, `limit=20`, `page=requestNumber+1`, `apikey` header
- [x] 4.4 `parseResponse`: deserialize envelope, guard `code != 0`/null result, pass `result.list` to mapper
- [x] 4.5 `hasMoreData`: stop when `page*limit >= total` or `itemCount == 0`
- [x] 4.6 `initialState`: `FetcherState.initial(baseUrl + "/market")` + empty seed
- [x] 4.7 Fail-fast log if api-key blank; rely on `@Retryable` for `429`

**Exit:** standalone fetch populates `fetching_prediction_markets` with `datasource='OPINION'`.

## Phase 5 — Temporal + service wiring
- [x] 5.1 `FetchActivity.java`: add `void fetchOpinion();`
- [x] 5.2 `FetchActivityImpl.java`: inject `OpinionFetcher`, implement `fetchOpinion()` → `fetchAll()`
- [x] 5.3 `MarketsRefreshWorkflowImpl.java`: add `Async.procedure(fetchActivity::fetchOpinion)` promise, `.get()` before `publishFetchedMarkets()`
- [x] 5.4 `FetchWorkflowImpl.java`: handle `"OPINION"` provider in dispatch
- [x] 5.5 `FetcherControllerService.java`: inject `OpinionFetcher`; add `fetchOpinionData()`; include in stop/pause/resume

**Exit:** `MarketsRefreshWorkflow` fetches all three sources concurrently, then publishes.

## Phase 6 — Controller + config
- [x] 6.1 `MarketsFetcherController.java`: inject `OpinionFetcher`; add manual-trigger + status endpoints mirroring Polymarket/Kalshi
- [x] 6.2 `application.properties`: `fetcher.opinion.base-url`, `fetcher.opinion.api-key`
- [x] 6.3 Document `OPINION_API_KEY` (+ `OPINION_BASE_URL`) in `.env` / deployment config

**Exit:** manual REST trigger works end-to-end.

## Phase 7 — Build, test, verify
- [x] 7.1 `./gradlew build` passes (compile + existing tests)
- [x] 7.2 Manual trigger writes correct rows (event_id, market_ticker, title, datetimes, token ids, condition_id)
- [x] 7.3 Verify categorical → multiple rows, binary → single row
- [x] 7.4 Verify pagination terminates (no infinite loop) at `page*limit >= total`
- [x] 7.5 Full `MarketsRefreshWorkflow` run: no regression to Kalshi/Polymarket; similar-markets still triggers
- [x] 7.6 Blank `OPINION_API_KEY` logged clearly, other fetchers unaffected

**Exit:** all spec §9 acceptance criteria met.

---

## Acceptance criteria (from spec §9)
- [x] Build passes
- [x] `DataSource.OPINION` round-trips
- [x] Manual trigger populates `fetching_prediction_markets`
- [x] Categorical expands per child; binary single row
- [x] Token/condition ids populated where present
- [x] Concurrent refresh with no regressions
- [x] Pagination terminates correctly
- [x] Blank API key handled gracefully
