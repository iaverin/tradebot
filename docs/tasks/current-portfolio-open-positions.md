# Current Portfolio and Open Positions

## Task Description

Add a page that displays the current portfolio, or open positions, for every supported venue.

For each position, display:

- Venue.
- Event title and market title.
- Outcome (`Yes` or `No`).
- Number of shares or quantity.
- Dollars spent.
- Current value.

Also display related positions from other venues. the related position is determined by similar market and outcome from  `arbitrage_orders` or `arbitrage_events` tables.

the related positions must be grouped with original one.

the ux logic:
- there must be selector of venue
- so the positions should be display based on these venue and related must by from other venues (not choosed)
- the page should display the last timestamp of update
- the page must contain a button which triggers the portfolio update

### data gathering

there must be scheduler which should run every 5 minutes and gather the position data from venues
these data must be stored in single table with the venue encoded as string (POLYMARKET or KALSHI)

For now do only for polymarket and kalshi

## Technical Specification

### 1. Scope and user-visible behavior

Add a dedicated authenticated frontend page for current open positions. The page supports only `POLYMARKET` and `KALSHI` in this iteration.

- A required venue selector chooses the primary venue. Default it to `POLYMARKET`.
- Show only open positions from the selected venue as primary rows.
- Under each primary row, show any matching open position from the other venue as a related row. Never include another position from the selected venue in the related list.
- Display these fields for both primary and related rows:
  - venue;
  - event title and market title;
  - outcome (`YES` or `NO`);
  - quantity;
  - spent USD;
  - current value USD.
- Keep positions with no known cross-venue relation in the list with an empty related-position collection.
- Paginate primary positions. Related rows do not count toward page size or total elements.
- Above the list, show the selected venue's last successful refresh timestamp. Show `Never updated` until that venue has completed its first successful refresh.
- Add an `Update portfolio` button. It starts one refresh of both supported venues, shows a loading state while the request is running, and reloads the selected page after the refresh completes.
- Show loading, empty, and request-failure states. A missing monetary value is displayed as `-`, not as zero.

The browser always reads positions from the persisted snapshot and never contacts either venue directly. Normal page loads are read-only; only the explicit update action asks the backend to refresh the venue snapshots.

### 2. External data contracts

The following contracts were checked against the official venue documentation on 2026-09-10:

- [Polymarket current positions](https://docs.polymarket.com/api-reference/core/get-current-positions-for-a-user): `GET /positions` with the configured deposit wallet as `user`. Use `limit=500`, offset pagination, `sizeThreshold=0`, and `includeArchived=true`; retain only rows whose `size` is greater than zero. The response supplies `asset`, `conditionId`, `size`, `grossInitialValue`, `initialValue`, `currentValue`, `title`, `slug`, `eventSlug`, and `outcome`.
- [Kalshi portfolio positions](https://docs.kalshi.com/api-reference/portfolio/get-positions): authenticated `GET /portfolio/positions?count_filter=position&limit=1000`, following `cursor` until it is empty. Use the fixed-point `position_fp`, `market_exposure_dollars`, and `last_updated_ts` fields; do not depend on removed legacy integer fields.
- [Kalshi markets](https://docs.kalshi.com/api-reference/market/get-markets): fetch quotes and market metadata in batches through `GET /markets?tickers=...&limit=1000`. Use `yes_bid_dollars` or `no_bid_dollars` for per-position valuation.

All quantities and monetary fields must be parsed as `BigDecimal`. Do not parse venue decimals through `double`.

### 3. Normalized position model

Add Flyway migration `V23__create_portfolio_positions.sql` and a matching JPA entity. Use one table for both venues:

```sql
CREATE TABLE portfolio_positions (
    id                  BIGSERIAL PRIMARY KEY,
    venue               VARCHAR(50) NOT NULL,
    source_position_id  TEXT NOT NULL,
    market_ticker       TEXT NOT NULL,
    condition_id        TEXT,
    event_ticker        TEXT,
    event_title         TEXT,
    market_title        TEXT,
    outcome             VARCHAR(10) NOT NULL,
    quantity            NUMERIC(30, 10) NOT NULL,
    spent_usd           NUMERIC(30, 10),
    current_value_usd   NUMERIC(30, 10),
    source_updated_at   TIMESTAMPTZ,
    refreshed_at        TIMESTAMPTZ NOT NULL,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ NOT NULL DEFAULT NOW()
    );

CREATE INDEX idx_portfolio_positions_venue
    ON portfolio_positions (venue);

CREATE UNIQUE INDEX idx_portfolio_positions_relation
    ON portfolio_positions (venue, market_ticker, outcome);
```

In the same migration, add a small control-state table so the UI can report a successful empty snapshot and can retain the timestamp across backend restarts:

```sql
CREATE TABLE portfolio_position_refresh_state (
    venue                       VARCHAR(50) PRIMARY KEY,
    last_successful_refresh_at  TIMESTAMPTZ NOT NULL,
    updated_at                  TIMESTAMPTZ NOT NULL DEFAULT NOW()
);
```

This table stores refresh metadata only; all position data remains in the single `portfolio_positions` table. Create or update the venue's state row in the same transaction as its successful snapshot replacement. Do not advance `last_successful_refresh_at` after a failed or incomplete fetch.

Mapping rules:

| Normalized field | Polymarket | Kalshi |
|---|---|---|
| `venue` | `POLYMARKET` | `KALSHI` |
| `source_position_id` | `asset` token ID | market `ticker` |
| `market_ticker` | `slug`; fall back to the locally matched market ticker | `ticker` |
| `condition_id` | `conditionId` | `NULL` |
| `event_ticker` | local `prediction_markets.event_ticker`; fall back to `eventSlug` | local `prediction_markets.event_ticker`; fall back to the market response `event_ticker` |
| titles | latest matching row in `prediction_markets`; fall back to the venue response where available | latest matching row in `prediction_markets`; fall back to Kalshi market metadata where available |
| `outcome` | normalized API `outcome`; accept only binary `YES`/`NO` | `YES` when `position_fp > 0`, `NO` when `position_fp < 0` |
| `quantity` | `size` | absolute value of `position_fp` |
| `spent_usd` | `grossInitialValue` when supplied; otherwise `initialValue` | absolute value of `market_exposure_dollars` |
| `current_value_usd` | API `currentValue` | `quantity * yes_bid_dollars` for `YES`, or `quantity * no_bid_dollars` for `NO` |
| `source_updated_at` | `NULL` because the response has no row timestamp | `last_updated_ts` |

The normalized identity and upsert key is `(venue, market_ticker, outcome)`, matching `idx_portfolio_positions_relation`. `source_position_id` retains the venue's position/token identifier for traceability and may change without creating another normalized row. Before persistence, discard exact repeated rows with the same source ID. If two distinct source IDs collide on one normalized identity, fail that venue snapshot because the chosen table cannot represent both without losing source identity; never rely on database constraint errors as the normal deduplication path.

`spent_usd` means the remaining entry basis/exposure for the currently open position, not lifetime turnover. For Polymarket, prefer `grossInitialValue` because it includes attributed entry fees; its absence must fall back to the fee-exclusive `initialValue`. Kalshi current value is a conservative executable mark using the relevant best bid. If that bid is unavailable, persist `NULL` for current value instead of substituting a last trade or zero.

Use `DataSource` and `YesOrNoResult` in Java where appropriate, persisted as strings. Because the table intentionally has no database `CHECK` constraints for these values, application validation must allow only `POLYMARKET`/`KALSHI`, allow only `YES`/`NO`, and require a positive quantity before writing. Skip an explicitly unsupported venue or non-binary outcome with a warning. Treat a missing required identity field (`source_position_id`, `market_ticker`, or `outcome`) as an incomplete venue snapshot so it cannot trigger stale-row deletion.

### 4. Venue clients and collection

#### Polymarket

Extend `PolymarketDataApiClient` with a position-fetch method and add `client/dto/PolymarketPositionDto.java`.

- The new method returns all pages only after every page succeeds.
- A successful empty response returns an empty list.
- A configuration, HTTP, status-code, parsing, or mid-pagination failure returns an explicit failure (`null` under the existing client convention, or a typed result carrying failure). It must not be converted into an empty snapshot.
- Stop when a page contains fewer than 500 rows. Respect the API's maximum offset and fail the refresh with a clear warning rather than silently truncating if another page would exceed it.
- Use the configured `arbitrage.polymarket-deposit-wallet-address` and existing `arbitrage.polymarket-data-api-url`.

#### Kalshi

Add typed client DTOs for the position page, market position, and market quote/metadata. Implement collection through the existing `KalshiAuthorizedClient` so current authentication, base URL, HTTP client, and proxy behavior are reused.

- Page `/portfolio/positions` by cursor and retain only non-zero `position_fp` rows.
- Batch returned tickers into bounded `GET /markets?tickers=...` calls and index quote results by ticker. Do not issue an unbounded request or one market request per database row.
- Treat a failed positions page as a failed venue snapshot.
- A missing quote for one ticker does not invalidate all position data; persist that row with `current_value_usd = NULL` and log the ticker.
- Use only current fixed-point fields with `_fp` and `_dollars` suffixes.

#### Metadata enrichment

Batch-load the latest metadata from the published `prediction_markets` table using typed record projections:

- match Kalshi by `(datasource = 'KALSHI', market_ticker)`;
- match Polymarket first by `(datasource = 'POLYMARKET', condition_id)`, then by market slug/ticker;
- use `DISTINCT ON` or an equivalent latest-row query ordered by `created_at DESC` so repeated market snapshots do not duplicate positions.

Repository query methods must return typed records, never `Object[]`, raw maps, or `Map<String, Object>`. External API DTOs stay in `client/dto/`; REST response records stay in `controller/dto/`.

### 5. Five-minute snapshot refresh

Add `PortfolioPositionRefreshCoordinator` and `PortfolioPositionRefreshScheduler` under a focused `portfolio` package. Scheduling is already enabled by `AuthApplication`. Both the scheduler and the manual REST action must call the same coordinator so collection, normalization, persistence, and concurrency rules have one implementation.

```java
@Scheduled(
    fixedDelayString = "${portfolio.positions.refresh-delay-ms:300000}",
    initialDelayString = "${portfolio.positions.initial-delay-ms:0}"
)
public void refreshPositions() {
    refreshCoordinator.refreshAll(RefreshTrigger.SCHEDULED);
}
```

Expose the defaults through `application.properties` and environment variables:

```properties
portfolio.positions.refresh-delay-ms=${PORTFOLIO_POSITIONS_REFRESH_DELAY_MS:300000}
portfolio.positions.initial-delay-ms=${PORTFOLIO_POSITIONS_INITIAL_DELAY_MS:0}
```

Refresh semantics:

1. Fetch every page for one venue outside a database transaction.
2. Normalize and enrich the complete result.
3. In one transaction for that venue, load its existing rows, upsert by the unique `(venue, market_ticker, outcome)` key, delete rows whose normalized keys are absent from the newly completed snapshot, and upsert `portfolio_position_refresh_state.last_successful_refresh_at`.
4. Use one `refreshed_at` value for all rows in a venue run.
5. Refresh the second venue even if the first venue fails.

Never remove or replace the last successful snapshot or advance its last-success timestamp on a failed or partial fetch. A successful empty response is authoritative: it removes all stored positions for that venue and advances the state timestamp in the same transaction. Keep remote I/O outside the write transaction so a slow venue cannot hold database locks.

The coordinator permits only one all-venue refresh at a time in the backend process. Use a non-blocking lock shared by scheduled and manual calls: a scheduled invocation logs and skips when another refresh is active, while a manual request receives `409 Conflict`. This prevents duplicate upstream calls and competing snapshot writers. A manual refresh waits for both venue attempts to finish and reports their results independently; failure of one venue must not prevent the other from being attempted.

The current deployment has one backend scheduler. If the backend becomes horizontally replicated, add a distributed scheduler lock before enabling more than one writer.

### 6. Cross-venue relation resolution

Do not add relation columns or a second relation table. Build relationships when serving the page from typed query results over existing arbitrage data.

Create a `PortfolioPositionRelationDto` projection containing:

```text
similarMarketId
polymarketMarketTicker
polymarketOutcome
kalshiMarketTicker
kalshiOutcome
```

For each distinct `OPPORTUNITY_START` row in `arbitrage_events`:

1. Join `arbitrage_orders` twice by `opportunity_uuid`, once for `POLYMARKET` and once for `KALSHI`.
2. Prefer each matching order's `contract_type`, because it records the side actually submitted.
3. Where an order side is absent, derive the expected pair from `arbitrage_events.direction`:
   - `PM_YES_KS_NO` maps Polymarket `YES` to Kalshi `NO`;
   - `PM_NO_KS_YES` maps Polymarket `NO` to Kalshi `YES`.
4. Use the event's `polymarket_market_ticker` and `kalshi_market_ticker` as the normalized market keys.
5. Deduplicate repeated opportunities into unique `(market tickers, outcomes)` relations.

For every primary position on the selected page, match `(venue, market_ticker, outcome)` against these relations, invert the relation to obtain the other-venue key, and attach only counterpart rows that currently exist in `portfolio_positions`. Events never create synthetic or "expected" position rows.

### 7. Backend API

Extend the existing authenticated `PortfolioController` with:

```http
GET /portfolio/positions?venue=POLYMARKET&page=0&size=50
```

Rules:

- `venue` defaults to `POLYMARKET` and accepts only `POLYMARKET` or `KALSHI`, case-insensitively.
- `page` defaults to `0`.
- `size` defaults to `50` and is limited to `1..200`.
- Invalid parameters return `400` through a typed error response.
- No rows returns `200` with empty `content` and zero totals.
- Order primary positions deterministically by event title, market title, outcome, then ID.
- The endpoint performs database reads only.
- `lastSuccessfulRefreshAt` is loaded from `portfolio_position_refresh_state` for the selected venue and is `null` before the first successful refresh.
- Existing `SecurityConfig.anyRequest().authenticated()` already protects this route; use `AuthenticatedClient` in integration tests.

Add response records under `controller/dto/`:

```java
public record PortfolioPositionDto(
    Long id,
    String venue,
    String eventTicker,
    String eventTitle,
    String marketTicker,
    String marketTitle,
    String outcome,
    BigDecimal quantity,
    BigDecimal spentUsd,
    BigDecimal currentValueUsd,
    Instant refreshedAt
) {}

public record PortfolioPositionGroupDto(
    Long groupId,
    PortfolioPositionDto primary,
    List<PortfolioPositionDto> related
) {}

public record PortfolioPositionsPageDto(
    String selectedVenue,
    Instant lastSuccessfulRefreshAt,
    List<PortfolioPositionGroupDto> content,
    int page,
    int size,
    long totalElements,
    int totalPages
) {}
```

`groupId` is the primary stored position ID. Keep `related` as a list so the response remains valid if another venue is supported later or more than one related market maps to a primary position.

Add a synchronous manual refresh action:

```http
POST /portfolio/positions/refresh
```

- It has no request body and refreshes both `POLYMARKET` and `KALSHI`, regardless of the venue currently selected in the UI.
- It calls the shared refresh coordinator and returns only after both venue attempts have completed.
- Return `200` when the refresh ran, including a result for each venue. `allSucceeded` communicates complete versus partial success; an individual failure includes only a safe user-facing message and leaves that venue's previous last-success timestamp unchanged.
- Return `409` with a typed error response when a scheduled or manual refresh is already running.
- Unexpected controller/orchestration failures use the existing typed error handling. Do not expose upstream response bodies, credentials, or stack traces.

Add response records under `controller/dto/`:

```java
public record PortfolioVenueRefreshResultDto(
    String venue,
    String status,
    Instant lastSuccessfulRefreshAt,
    String message
) {}

public record PortfolioRefreshResponseDto(
    Instant requestedAt,
    boolean allSucceeded,
    List<PortfolioVenueRefreshResultDto> venues
) {}
```

`status` is `SUCCESS` or `FAILED`; `message` is nullable and sanitized. Use the per-venue transaction's committed timestamp as `lastSuccessfulRefreshAt`, rather than the browser time or request start time.

### 8. Frontend page

Add `frontend/src/views/PortfolioPositions.vue` and reuse `frontend/src/api/portfolio.js`:

```javascript
getPositions: (venue, page = 0, size = 50) =>
  api.get('/portfolio/positions', { params: { venue, page, size } }),
refreshPositions: () =>
  api.post('/portfolio/positions/refresh')
```

- Register authenticated route `/portfolio/positions` in `frontend/src/router/index.js` before the catch-all route.
- Add an `Open Positions` item to `SidebarMenu.vue` with a new unique active index.
- Use an Element Plus venue selector with explicit Polymarket and Kalshi options.
- Reset to page 1 and reload when the venue changes.
- Display `Last updated: <localized date/time>` for the selected venue using `lastSuccessfulRefreshAt` from the GET response. Display `Last updated: Never` when it is `null`; do not derive freshness from row timestamps or the client clock.
- Place an `Update portfolio` button beside the freshness display. Disable it and show a spinner while its POST request is pending so the user cannot submit duplicate refreshes from the page.
- After any completed refresh response, reload the current selected-venue page so positions and its timestamp reflect committed data. Show success when both venues succeeded and a warning naming failed venues when the result is partial. On `409`, report that an update is already in progress and reload the page data without retrying automatically.
- Render each API group with the selected-venue row first and its related rows immediately below, indented or otherwise visually marked as `Related`. Keep every group visibly separated.
- Render event and market titles together in the existing `Event / Market` style; reuse `venueEventUrl` when an event ticker is present.
- Format quantities without forcing them to integers. Format `spentUsd` and `currentValueUsd` as USD from server-provided values; do not recompute them in JavaScript.
- Drive Element Plus pagination from the primary-position totals in `PortfolioPositionsPageDto`.
- On an API error, show an `ElMessage` error and clear stale page content. An empty successful response shows `No open positions`.

### 9. Failure, consistency, and security behavior

- Venue credentials and wallet addresses remain backend-only. The frontend receives normalized portfolio fields, not secrets or raw venue payloads.
- A venue refresh failure is isolated, logged without credentials or response secrets, and leaves that venue's last complete snapshot untouched.
- The displayed timestamp is the selected venue's last successfully committed snapshot time. An attempted or failed manual refresh must not make stale data appear fresh.
- Scheduled and manual refreshes share one in-process concurrency guard. The UI must not automatically retry `409` responses.
- A relationship-query failure must fail the REST request rather than return positions with silently missing relations.
- A metadata lookup failure may fall back to source titles/tickers and does not fail the snapshot.
- A missing Kalshi price produces a nullable current value and does not discard the position.
- Each venue snapshot is transactionally consistent. The two venues may have different `refreshed_at` times because they refresh independently.
- The table is global to the configured venue accounts, matching the existing portfolio dashboard. It is not scoped to the authenticated application user.

### 10. Acceptance criteria

- Within one successful scheduler run, all non-zero Polymarket and Kalshi open positions are stored in `portfolio_positions` with normalized venue and outcome values.
- A subsequent successful run updates retained positions, inserts new ones, and removes closed/disappeared ones for that venue.
- A failed or truncated venue response leaves the previous venue snapshot unchanged.
- `GET /portfolio/positions` returns only selected-venue positions as primaries and attaches only existing positions from the other venue.
- The GET response returns the selected venue's last successful refresh timestamp, including after a successful empty snapshot, and returns `null` before the first successful refresh.
- The page renders the last successful update time and provides an authenticated `Update portfolio` action.
- `POST /portfolio/positions/refresh` attempts both venues, returns per-venue results, and causes the page to reload committed snapshot data.
- A failed venue attempt keeps that venue's prior positions and prior timestamp; an overlapping manual update returns `409` and does not start another refresh.
- A PM `YES` / Kalshi `NO` pair and a PM `NO` / Kalshi `YES` pair are grouped correctly from order/event data.
- The page can switch between Polymarket and Kalshi while preserving the inverse primary/related relationship.
- Values are mapped according to the rules above, nullable values render as `-`, and fractional quantities are preserved.
- Both GET and POST endpoints require a valid JWT.

## Implementation Essentials

- Distinguish a successful empty snapshot from a failed fetch. Conflating them would delete valid positions during a venue outage.
- Finish all remote pagination before opening the persistence transaction. Kalshi terminates on an empty cursor; Polymarket terminates on a short page and has an offset ceiling.
- Use `position_fp` and `_dollars` Kalshi fields. The legacy integer count/cent fields have been removed or deprecated.
- Preserve `BigDecimal` end to end, including Jackson DTOs, JPA columns, calculations, response DTOs, and frontend string-to-display handling.
- Treat Kalshi's signed quantity as the outcome: positive is `YES`, negative is `NO`; always store positive absolute quantity.
- Do not use `total_traded_dollars` as spent USD because it is turnover rather than the basis of the current open position.
- Do not value Kalshi `NO` positions with the YES bid. Use the outcome-specific best bid and leave the value null when it is unavailable.
- Resolve Polymarket by `conditionId` before slug because condition ID is the stable market identifier and the local ticker is the slug used by arbitrage events.
- Preserve stable database IDs while `(venue, market_ticker, outcome)` remains unchanged by updating the matching entity rather than deleting and reinserting the entire venue snapshot. An outcome reversal is a new normalized position key, so the old key is removed and the new one is inserted.
- Treat `source_position_id` as traceability data, not as the repository identity or conflict target. Refresh it on every successful venue snapshot.
- Enforce venue, outcome, positive-quantity, and required-key invariants in normalization because the chosen SQL definition does not enforce them with database checks.
- Relation matching must include both market ticker and outcome. Matching only `similar_market_id` or ticker can connect the wrong arbitrage direction.
- Filter `arbitrage_events` to `OPPORTUNITY_START`; END rows reuse the opportunity UUID and would otherwise duplicate relations.
- Use actual `arbitrage_orders.contract_type` when available and event direction only as a fallback. Never infer that a related position exists solely because an opportunity occurred.
- Keep controller and repository result shapes as typed records in accordance with repository conventions; introduce no new `Object[]` or `Map<String, Object>` APIs.
- Keep the five-minute job separate from `OrderStatusChecker` and the hourly Temporal market refresh. They have different data ownership and failure semantics.
- Route both scheduled and manual refreshes through one coordinator and one in-process non-blocking lock. Do not duplicate refresh logic in the controller or invoke one REST endpoint from the scheduler.
- Define freshness as the last successfully committed venue snapshot. Persist it separately from position rows so a successful empty snapshot remains observable, and update it transactionally with that snapshot.
- Keep ordinary GET requests database-only. The authenticated POST action is the only browser-triggered mutation and always refreshes both supported venues.
- Verification must cover both relation directions, fractional quantities, pagination, a successful empty snapshot and its timestamp, partial/failing fetches without timestamp advancement, overlapping refresh rejection, missing quote data, stale-row removal, parameter validation, and JWT enforcement.

## Architectural Decisions and Modifications

### Persisted current-state projection

The new `portfolio_positions` table is a current-state read model, not an append-only history table. This matches the page's "current/open positions" purpose and avoids reconstructing state on every request. `created_at`, `updated_at`, `source_updated_at`, and `refreshed_at` provide operational timestamps without retaining closed snapshots.

### One normalized table with a venue discriminator

Polymarket and Kalshi positions share one schema with `venue` stored as `POLYMARKET` or `KALSHI`. Venue-specific wire fields remain confined to client DTOs and mapping code. This satisfies the single-table requirement while keeping the REST/frontend contract venue-neutral.

The table's unique `(venue, market_ticker, outcome)` index defines one current normalized position per venue-side-market combination and is also the natural lookup key for relationship resolution. `source_position_id` is retained as mutable source metadata rather than a unique key. Domain validation is intentionally owned by the Java normalization layer because the SQL schema does not constrain venue, outcome, or positive quantity.

### Durable refresh metadata

`portfolio_position_refresh_state` is a separate operational state table, not a second position store. Computing freshness as `MAX(portfolio_positions.refreshed_at)` would lose the timestamp whenever a successful refresh finds zero positions and would be unable to distinguish “successfully empty” from “never refreshed.” Updating the state row in the same transaction as snapshot replacement gives the UI an honest last-success timestamp after non-empty and empty snapshots alike.

### Scheduled ingestion and explicit manual updates

The existing portfolio dashboard fetches venues synchronously per request. The positions page's GET path instead reads a five-minute persisted snapshot, which removes venue latency and availability from ordinary page requests. The explicit POST action invokes the same application coordinator as the scheduler and waits for a result; it does not introduce a second ingestion path. A simple Spring scheduler and in-process concurrency guard are appropriate for the current single-backend deployment; Temporal is not needed for this short, idempotent projection refresh.

### Relationships remain derived

Cross-venue links are derived from `arbitrage_events` and `arbitrage_orders`; they are not copied into `portfolio_positions`. This avoids stale foreign keys when snapshots or similar-market data change. Complete paired orders are the preferred evidence of outcomes actually submitted, while event direction supports older or incomplete order records. A counterpart is returned only if it exists in the latest position snapshot.

### Explicit valuation semantics

Polymarket's venue-provided remaining basis and current value are authoritative. Kalshi does not expose a per-position current-value field in the positions response, so current value is calculated as quantity multiplied by the relevant best bid. This is a conservative liquidation-value definition and is intentionally not based on last trade price. `market_exposure_dollars` is used as the Kalshi position's spent/exposure amount.

### Existing boundaries reused

- Extend `PortfolioController` and `portfolio.js` instead of adding a parallel top-level API family.
- Reuse `PolymarketDataApiClient`, `KalshiAuthorizedClient`, proxy configuration, `DataSource`, `YesOrNoResult`, JWT security, and `AuthenticatedClient`.
- Add a focused backend `portfolio` package for the normalized entity, repositories, collectors, snapshot writer, refresh coordinator, relation service, and scheduler.
- Keep source API DTOs in `client/dto/` and frontend response DTO records in `controller/dto/`.

No changes are required in the Similar Markets Python service. The backend database already contains the arbitrage and published market data needed for enrichment and relation resolution.

## Implementation TODO Plan

- [x] **1. Database model** — Add `V23__create_portfolio_positions.sql` with the specified `portfolio_positions` definition, venue index, unique `(venue, market_ticker, outcome)` relation index, and the separate `portfolio_position_refresh_state` control table.
- [x] **2. Entities and repositories** — Add typed entities and repositories for positions and refresh state; support venue snapshot loading, lookup by the normalized relation key, batched persistence, stale-key deletion, selected-venue pagination, counterpart-key lookup, and last-success lookup by venue.
- [x] **3. Typed metadata and relation projections** — Add records under `controller/dto/` and a query repository that returns the latest published market metadata and deduplicated PM/KS relation keys without `Object[]` or raw maps.
- [x] **4. Polymarket wire DTO** — Add `client/dto/PolymarketPositionDto.java` with the position fields used by normalization and ignore unknown response fields.
- [x] **5. Polymarket pagination** — Extend `PolymarketDataApiClient` to fetch the full `/positions` snapshot, distinguish failure from an empty account, include archived active positions, and enforce the offset limit.
- [x] **6. Kalshi wire DTOs** — Add typed DTOs for `/portfolio/positions` pages and `/markets` quote/metadata rows using current fixed-point field names.
- [x] **7. Kalshi collection** — Implement cursor pagination, signed-outcome normalization, bounded ticker batching, and best-bid current-value calculation through `KalshiAuthorizedClient`.
- [x] **8. Metadata enrichment** — Batch-resolve event/market titles and canonical tickers from the latest `prediction_markets` rows, with the documented venue fallbacks.
- [x] **9. Snapshot writer** — Validate and deduplicate normalized keys, then implement one transactional upsert-and-prune operation per venue keyed by `(venue, market_ticker, outcome)`; preserve IDs for retained keys, refresh source metadata, and update the venue's last-success state in the same transaction. Leave both positions and timestamp untouched on incomplete fetches.
- [x] **10. Refresh coordinator and scheduler** — Add the shared coordinator, non-blocking single-refresh guard, per-venue result model, and `PortfolioPositionRefreshScheduler`; isolate venue failures, run immediately after startup and every five minutes thereafter, and add the two scheduler properties/environment overrides.
- [x] **11. Relation resolver** — Query `OPPORTUNITY_START` events plus paired orders, prefer order contract types, fall back to direction, deduplicate keys, and attach only persisted other-venue positions.
- [x] **12. REST DTOs and service** — Add the position/group/page DTOs with `lastSuccessfulRefreshAt`, plus typed manual-refresh response DTOs; implement selected-venue pagination, deterministic grouping, and refresh-state lookup in a portfolio query service.
- [x] **13. Controller endpoints** — Add authenticated `GET /portfolio/positions` and `POST /portfolio/positions/refresh` to `PortfolioController`, including venue/page/size validation, per-venue refresh results, `409` handling for an active refresh, and typed errors.
- [x] **14. Client parsing tests** — Add mocked HTTP tests for Polymarket offset pagination and Kalshi cursor/quote parsing, including fractional decimals, empty responses, intermediate-page errors, and missing bid fields.
- [x] **15. Snapshot and coordinator integration tests** — With Testcontainers PostgreSQL, verify unique relation-key enforcement, insert/update/prune behavior, stable IDs for retained normalized keys, outcome reversal, source-ID replacement, successful empty snapshots with durable timestamps, independent venue transactions, application-level invariant validation, preservation after failure, and non-overlapping scheduled/manual execution.
- [x] **16. Relation and controller integration tests** — Seed positions, refresh state, START/END events, and orders for both arbitrage directions; verify grouping, unrelated positions, inverse venue selection, pagination totals, returned freshness, manual full/partial results, concurrent-refresh `409`, invalid parameters, and JWT enforcement through `AuthenticatedClient`.
- [x] **17. Frontend API** — Extend `frontend/src/api/portfolio.js` with the paginated positions GET and manual refresh POST.
- [x] **18. Frontend page** — Add `PortfolioPositions.vue` with venue selection, selected-venue freshness display, manual update button/loading/result handling, grouped primary/related rows, pagination, currency/quantity formatting, links, and loading/error/empty states.
- [x] **19. Navigation** — Register `/portfolio/positions` before the catch-all route and add a uniquely indexed `Open Positions` sidebar item.
- [x] **20. Automated verification** — `cd backend && ./gradlew test` passed 136 tests; `cd frontend && npm run build` completed successfully on 2026-09-10.
- [x] **21. Live venue verification** — The tagged `PortfolioPositionsManualTest` exercised both the scheduled coordinator and authenticated POST against configured accounts in an isolated database on 2026-09-10. Both venues succeeded; the authenticated GET returned durable timestamps and sampled 160 Polymarket and 63 Kalshi positions with normalized quantities, basis, current values, and venue filtering.
- [ ] **22. Browser verification** — Verify the timestamp, update-button feedback, grouping, pagination, and selector inversion in an authenticated browser. This environment exposed no Computer Use browser provider, so visual interaction could not be run; the frontend production build and the corresponding API/integration behavior are verified.
