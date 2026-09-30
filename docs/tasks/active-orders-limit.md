# Active Pairs Limit

## Task

Add a safety limit that prevents the arbitrage monitor from submitting another
Polymarket/Kalshi orders pair for exact pair of markets when either venue already has the not executed order.

the limit is applied per pair.
Active pair means that at least one order from any venue is not filled.

The limit must be enforced from persisted order state,
must remain correct when several market updates attempt to trade concurrently,
and must leave an observable reason when an opportunity is skipped in opportunity close reason.

This blueprint assumes a limit of **1 active pair**, configurable
through the frontend Settings page.

Order statuses and `activeOrderPairs` must be updated immediately when orders
are created and subsequently by the shared order-status update flow, rather than
by rereading database statuses into the map. When the active-pair limit is met,
the monitor must invoke that same status-update flow for the pair's unfinished
orders before deciding whether to reject the new opportunity.

Also add a fix to polymarket order status - which assumes that MATCHED status is for executed order.

## Spec

### 1. Confirmed behavior

- The limit applies independently to each exact Polymarket/Kalshi market pair.
  The pair key is
  `(polymarket_market_ticker, kalshi_market_ticker)` from the related
  `OPPORTUNITY_START` row.
- Direction is not part of the pair key. An active `PM_YES_KS_NO` attempt blocks
  a new `PM_NO_KS_YES` attempt, and vice versa, for the same two market tickers.
- The configured value is the maximum number of distinct active order-pair
  attempts for one market pair. The default is `1`. If the operator later sets
  the value to `2`, at most two distinct opportunity UUIDs for that market pair
  may have active orders at the same time.
- An order is active when its stored `OrderState` is `CREATED`, `PENDING`,
  `PLACED`, or `UNKNOWN`. `EXECUTED`, `CANCELED`, and `ERROR` are terminal and do
  not consume capacity.
- An order pair is active while at least one order belonging to its opportunity
  UUID is active. Count a UUID once even when both its Polymarket and Kalshi rows
  are active.
- When both order rows for an opportunity are terminal, that opportunity no
  longer consumes a slot. For example, `EXECUTED + PLACED` is still active,
  while `EXECUTED + CANCELED` is not.
- `ArbitrageMonitorService` owns the runtime active-pair registry. During normal
  operation, the registry is updated directly from order-creation and
  order-status lifecycle notifications, not by rereading order statuses from
  PostgreSQL. The database is used to discover unfinished orders after startup
  or restart and by the status checker, which then refreshes their statuses and
  publishes the same lifecycle notifications.

### 2. Monitor-owned active-pair registry

Store runtime active-pair information in `ArbitrageMonitorService` with typed
keys and per-order state, not concatenated strings:

```java
private record MarketPairKey(String polymarketTicker, String kalshiTicker) {}

private final ConcurrentHashMap<MarketPairKey, ConcurrentHashMap<UUID, ActivePairInfo>> activeOrderPairs =
        new ConcurrentHashMap<>();
```

`ActivePairInfo` tracks the current active order IDs/statuses reported for one
opportunity UUID. Its nested collection must be concurrency-safe. The map
supports per-pair capacity checks. Every lifecycle event must carry both market
tickers, so UUID-based cleanup never needs to scan the registry or maintain a
second reverse index. Lifecycle updates must mutate the outer map through
`activeOrderPairs.compute(pairKey, ...)`. This makes each compound same-pair
transition atomic without a global registry lock, while allowing unrelated
market pairs to update concurrently.

The map contains only UUIDs reported by real order lifecycle events: initial
`PENDING` events from `ArbitrageOrderService` and active effective-status events
from the unfinished-order refresh during startup, restart, scheduled polling, or
an at-limit refresh. It must not contain synthetic or temporary reservations.

The existing exact-`MarketPairKey` lock closes the capacity-check/order-creation
race. The monitor holds that lock through `createOrders(...)`, and the order
service synchronously persists and publishes both initial `PENDING` orders
before either venue submission begins. Consequently, the real lifecycle events
populate the registry before a second same-pair creation can acquire the lock.
If validation creates no order, no registry cleanup is necessary. If only one
`PENDING` save succeeds before a persistence failure, that real order remains
active and conservatively consumes capacity.

For every order lifecycle notification:

- `CREATED`, `PENDING`, `PLACED`, or `UNKNOWN` adds or updates that order ID in
  `ActivePairInfo`;
- `EXECUTED`, `CANCELED`, or `ERROR` removes that order ID;
- when no active order IDs remain, remove the UUID and then remove the empty
  market-pair entry.

Do not reuse the existing `activeOpps` map for this purpose. `activeOpps` is
keyed by pair ID and direction and represents a price opportunity; the new map
is keyed by exact market tickers, ignores direction, and represents order risk.

### 3. Placement workflow in `ArbitrageMonitorService`

Move the limit decision into `ArbitrageMonitorService.checkArbitrage(...)`.
`ArbitrageOrderService` remains responsible for validating order books and
submitting/persisting orders, but it does not decide whether another active pair
is allowed.

For each above-threshold direction that would otherwise trade:

1. Resolve the `MarketPairKey` from `pmSlugs` and `ksTickers` before acquiring
   the monitor lock. Missing/blank tickers must fail closed and must not call the
   order service.
2. Change the existing `pairLocks` key from `similarMarketId` to
   `MarketPairKey`. This serializes both directions and duplicate
   `similar_market_id` rows that refer to the same exact markets.
3. Save the existing `OPPORTUNITY_START` and add it to `activeOpps` as today.
4. Preserve the existing trading-enabled and cached-balance checks. Perform the
   balance check before the capacity check so an insufficient balance does not
   read the setting or invoke the order service.
5. Explicitly call a monitor-owned `checkActivePairLimit(...)` method from
   `checkArbitrage(...)`. That method reads
   `SettingsService.getActivePairsLimit()` and compares the current
   `activeOrderPairs` count with that limit while the `MarketPairKey` lock is
   held. It returns a typed decision that distinguishes allowed creation, a
   reached limit, and a failed safety check.
6. If the in-memory count is already at the limit, synchronously invoke
   `OrderStatusChecker.checkUnfinishedOrdersForPair(...)`. That method refreshes
   unfinished orders from the venues, persists any changed status, and publishes
   lifecycle notifications that update `activeOrderPairs`. Recheck the in-memory
   count after the method completes.
7. If the refreshed in-memory count is still at the limit, do not call
   `ArbitrageOrderService`. Save `OPPORTUNITY_END` with
   `ACTIVE_PAIR_LIMIT_REACHED`, remove the opportunity from `activeOpps`, and
   release the pair lock.
8. In `checkArbitrage(...)`, branch explicitly on the limit decision. If order
   creation is allowed, call a separate monitor-owned `createOrders(...)` method
   that delegates to `ArbitrageOrderService.createOrders(...)`. Keep the pair
   lock for the duration, matching the current monitor behavior and preventing a
   second same-pair update from entering the creation window. The order service
   must synchronously save and publish both `PENDING` rows before either venue
   submission. Do not query the database or create a synthetic registry entry
   around the call.
9. Preserve the existing opportunity close reason returned by
    `createOrders(...)`. Only the monitor's pre-call rejection uses
    `ACTIVE_PAIR_LIMIT_REACHED`.

Reaching the configured limit is an expected safety decision, not an order
execution failure. It must not increment `SettingsService`'s consecutive error
counter, disable trading, or refresh venue balances. Log one warning containing
the opportunity UUID, both tickers, current active-pair count, and configured
limit. Do not log credentials or signed request content.

If the setting lookup fails, fail closed and do not call the order service. If
the on-demand status refresh fails or cannot determine a terminal provider
state, keep the existing active entries and reject the new opportunity with
`ACTIVE_PAIR_LIMIT_REACHED`. A failed status lookup must never free capacity.

### 4. Order creation and status-driven updates

Define a typed internal lifecycle event containing enough information for the
monitor to update the map without reading the database:

```java
public record ArbitrageOrderLifecycleEvent(
        UUID opportunityUuid,
        String polymarketTicker,
        String kalshiTicker,
        Long databaseOrderId,
        String platform,
        OrderState status
) {}
```

Use Spring's synchronous application-event delivery, or an equivalently typed
direct callback, so the map mutation is complete before the publisher returns.
`ArbitrageMonitorService` is the only component that mutates
`activeOrderPairs`; other services publish facts about order lifecycle.

`ArbitrageOrderService` must publish the event:

- immediately after each initial order row is synchronously saved with `PENDING`
  status, before either venue submission is scheduled or invoked; and
- after each subsequent save that changes the row to `PLACED`, `ERROR`, or
  another state.

Do not schedule the initial `PENDING` saves on the order executor. Both saves and
their synchronous lifecycle publications must return before either trading
client is called. This is the first population path for newly created orders.
The monitor must not call a repository afterward to rediscover the statuses it
just received.

Refactor `OrderStatusChecker` so both scheduled and on-demand checks use the
same implementation:

```text
checkUnfinishedOrders()                         // all pairs; called by scheduler/startup
checkUnfinishedOrdersForPair(pmTicker, ksTicker) // one exact pair; called at limit
```

Both methods return a typed refresh result indicating whether candidate loading
completed and how many orders were checked and updated. An individual provider
lookup failure may still be a completed refresh because that order safely keeps
its previous active state; failure to select or process the candidate set makes
the refresh incomplete.

The existing scheduled method delegates to `checkUnfinishedOrders()`. The
pair-specific method is invoked synchronously by `ArbitrageMonitorService` only
when the in-memory limit is met.

Add a typed repository projection for unfinished order candidates, containing
the `ArbitrageOrder` fields required by the checker plus both market tickers from
the related `OPPORTUNITY_START`. Select candidates whose stored state is
`CREATED`, `PENDING`, `PLACED`, or `UNKNOWN`; provide all-pairs and exact-pair
queries. Conceptually:

```sql
SELECT
       ao.id,
       ao.opportunity_uuid,
       ao.platform,
       ao.order_id,
       ao.status,
       ae.polymarket_market_ticker,
       ae.kalshi_market_ticker
FROM arbitrage_orders ao
JOIN arbitrage_events ae
  ON ae.uuid = ao.opportunity_uuid
 AND ae.event_type = 'OPPORTUNITY_START'
WHERE ao.status IN ('CREATED', 'PENDING', 'PLACED', 'UNKNOWN');
```

For a candidate with a provider order ID, request its current status from the
corresponding venue, normalize it, persist a changed status, and publish the
effective lifecycle event. Publish an event even when the provider state is
unchanged so startup/restart can rebuild `activeOrderPairs` from the refresh.

For `CREATED`/`PENDING`/`UNKNOWN` rows without a provider order ID, do not invent
a terminal state. Publish their stored active state so they continue to consume
capacity. A null response, HTTP failure, parsing failure, or a Polymarket
`OrderStatus.error()` must retain the previous active state and publish that
state; transport failure must not be normalized to terminal `ERROR` for the
purpose of releasing the limit.

Serialize scheduled and on-demand refreshes with a shared lock so the same order
is not queried and updated concurrently. The pair-specific refresh may wait for
an already-running scheduled refresh, then must operate on the latest unfinished
candidates.

On application start and monitor restart:

1. Clear the active-pair registry and mark it not ready.
2. Invoke `OrderStatusChecker.checkUnfinishedOrders()` before WebSocket clients
   begin delivering price updates.
3. Let the emitted lifecycle events populate `activeOrderPairs`.
4. Mark the registry ready only after candidate selection and processing
   completes. If candidate loading fails, do not submit new orders; an empty map
   is not proof that capacity is available.

PostgreSQL is therefore used to locate unfinished candidates and persist audit
state, but `activeOrderPairs` is mutated only by the initial order-creation
events and by status-check events. The monitor does not rebuild or reconcile the
map by directly interpreting database statuses during normal operation.

The current deployment assumption is one active backend instance that owns
automatic order creation. The ticker-keyed locks and concurrent maps guarantee
correctness for concurrent price updates within that process. They do not form a
distributed lock. Before enabling multiple backend order-writers, add a database
advisory lock or persisted capacity constraint around the check-and-create
sequence.

### 5. Configuration, persistence, and API

Add an environment-backed default to `ArbitrageConfig` and
`application.properties`:

```properties
arbitrage.active-pairs-limit=${ACTIVE_PAIRS_LIMIT:1}
```

The value must be a positive integer. `0` is invalid; it does not mean unlimited
or disable trading. Validate the environment value during application startup.

Persist frontend changes in the existing `settings` key/value table under the
key `ACTIVE_PAIRS_LIMIT`:

- when no row exists, use `ArbitrageConfig.activePairsLimit` as the default;
- `SettingsService.getActivePairsLimit()` reads and validates the persisted row,
  falling back only when the row is absent;
- `SettingsService.setActivePairsLimit(int)` validates and upserts the row;
- `ArbitrageMonitorService` reads the limit through `SettingsService` immediately
  before the in-memory capacity check, so a committed frontend change applies
  without restarting the monitor;
- lowering the limit below the current active count does not cancel orders. It
  blocks new pairs until the stored count falls below the new value.

Extend the existing authenticated settings contract with typed DTOs only:

- Add `activePairsLimit` to `SettingsInfoDto`, returned by
  `GET /api/settings/info`.
- Add `ActivePairsLimitDto(int activePairsLimit)` under `controller/dto/`.
- Add `PUT /api/settings/active-pairs-limit` accepting and returning that DTO.
- Require `ADMIN` authority on the `PUT`, matching the trading-enabled update.
  The existing `GET /api/settings/info` remains available to any authenticated
  user.
- Return `400` with a typed settings error response for a missing, non-integer,
  or less-than-one value. Do not silently clamp invalid input.

A check-and-create sequence already in progress may finish using the value it
read before a concurrent settings update committed. Every capacity check
starting after that commit must observe the new value.

### 6. Frontend settings behavior

Update `frontend/src/views/Settings.vue` and `frontend/src/api/settings.js`:

- Show `Active pairs limit per market pair` in the existing Trading Settings
  card.
- Use an `el-input-number` restricted to whole values with a minimum of `1`.
- Load the initial value from `GET /api/settings/info`.
- Save through `PUT /api/settings/active-pairs-limit` only after an explicit user
  action; disable the control/button while the request is running.
- On success, keep the returned canonical value and show a success message. On
  failure, restore the last server-confirmed value and show an error message.
- Do not change or restart the arbitrage monitor from the browser; the monitor
  reads the persisted setting immediately before each capacity check.

No new route or navigation item is required.

### 7. Opportunity close reason and reporting

Add `ACTIVE_PAIR_LIMIT_REACHED` to `OpportunityCloseReason`. Add a Flyway
migration after `V25` that replaces `chk_arbitrage_events_close_reason` with the
same accepted values plus the new reason.

Make the reason visible in the existing opportunity report:

- add `activePairLimitReachedCloseCount` to
  `OpportunityReportAggregateDto` and `OpportunityReportDto`;
- count distinct opportunity UUIDs with the new close reason in
  `OpportunityReportRepository`;
- map the typed field in `ArbitrageController`;
- add an `Active Pair Limit` close-reason column to
  `frontend/src/views/OpportunityReport.vue`.

A rejected opportunity has no order rows, so it is visible when the report is
requested with `onlyWithOrders=false`; the existing `onlyWithOrders=true`
behavior may continue to exclude it.

The same migration should add indexes for unfinished-order selection queries:

- a partial index on
  `arbitrage_events(polymarket_market_ticker, kalshi_market_ticker, uuid)` for
  `event_type = 'OPPORTUNITY_START'`;
- a partial index on `arbitrage_orders(opportunity_uuid)` for statuses
  `CREATED`, `PENDING`, `PLACED`, and `UNKNOWN`.

No new order columns or capacity table are required.

### 8. Polymarket `MATCHED` status correction

`OrderStatusChecker.mapToOrderStatus(...)` currently applies one status mapping
to both venues and maps `matched` to `PLACED`. Refactor the mapping to include
the order platform:

- Polymarket `MATCHED` (case-insensitive) maps to `OrderState.EXECUTED`.
- Kalshi `matched` retains its current `OrderState.PLACED` mapping.
- Existing mappings for `resting`, `live`, `delayed`, `filled`, `executed`,
  `partial`, canceled spellings, expired, error, and unknown values remain
  unchanged unless a platform-specific branch is necessary to preserve them.

When a Polymarket `MATCHED` response is processed, set `executedAt`, parse
`size_matched` through the existing filled-count path, and calculate
`filledAmount` exactly as for the other `EXECUTED` statuses. The resulting
terminal state releases the market pair if the counterpart order is also
terminal. After persisting the change, publish the typed order-status event used
by the monitor to update its active-pair maps.

This fix belongs in the shared status checker rather than
`PolymarketTradingService.getOrderStatus(...)`: the venue client should continue
to return the provider's raw status, while normalization remains in one service.

### 9. Compatibility and safety constraints

- Existing settings rows and order/event data need no backfill. The environment
  default applies until an `ACTIVE_PAIRS_LIMIT` row is created.
- Adding fields to settings and report JSON responses is backward-compatible for
  existing frontend clients.
- Existing active orders begin consuming capacity immediately after deployment,
  because the monitor invokes the unfinished-order status refresh and consumes
  its lifecycle events before accepting price updates.
- Manual venue orders that are not represented in `arbitrage_orders` are outside
  this limit. A normal below-limit check performs no upstream API calls; an
  at-limit check deliberately refreshes that exact pair from both venues before
  rejecting the new opportunity.
- Status updates can lag a venue by the existing 90-second polling delay. This
  may temporarily block a new pair after the real order became terminal, but the
  on-demand at-limit refresh reduces that delay. Unconfirmed states must never
  release capacity.
- `UNKNOWN` intentionally fails closed. It remains capacity-consuming until an
  operator or future reconciliation flow changes it to a terminal state.
- Direct status edits in the database do not update `activeOrderPairs` and are
  not a supported production path. Runtime state changes must pass through
  order creation or `OrderStatusChecker`; a restart rebuilds the map through a
  fresh unfinished-order check.
- Only one backend instance may own automatic order creation while this
  in-memory design is in use. Horizontal order-writer deployment requires a
  distributed capacity-control mechanism first.
- The change does not cancel existing orders, alter order sizing, change balance
  requirements, or make the limit independently configurable per user or venue.

### 10. Verification requirements

Backend coverage must demonstrate:

- zero active UUIDs allows one order-creation attempt at limit `1`;
- any one of `CREATED`, `PENDING`, `PLACED`, or `UNKNOWN` blocks another attempt
  for the same tickers;
- `EXECUTED`, `CANCELED`, and `ERROR` rows do not block;
- two active order rows for one opportunity UUID count as one active pair;
- one terminal leg plus one active leg still blocks;
- the opposite arbitrage direction for the same tickers is blocked;
- a different Polymarket or Kalshi ticker has independent capacity;
- duplicate `similar_market_id` values or changed IDs do not bypass a ticker
  pair's limit;
- two concurrent price updates for the same ticker pair allow at most one UUID
  with active lifecycle state at limit `1`, including when they originate from
  different `similar_market_id` values, and the rejected attempt makes no remote
  calls;
- no synthetic registry state exists before `ArbitrageOrderService` is called;
  its initial `PENDING` lifecycle events are the first population path;
- both initial `PENDING` rows and lifecycle events occur before either venue
  submission;
- initial `PENDING` and final placement statuses update the map directly from
  `ArbitrageOrderService` lifecycle events, without a post-call database read;
- startup and monitor restart invoke the unfinished-order checker and rebuild
  the map from its emitted effective-status events, while candidate-loading
  failure prevents order submission;
- the scheduled checker and at-limit checker use the same status-update method
  and cannot refresh the same orders concurrently;
- hitting the limit invokes the exact-pair unfinished-order refresh before the
  monitor rejects: newly terminal venue states release the slot, while unchanged
  or unconfirmed states continue to block;
- an order-status event removes a UUID only after its last active order becomes
  terminal, and a failed provider lookup retains the previous active status;
- a limit rejection returns `ACTIVE_PAIR_LIMIT_REACHED`, writes the matching
  `OPPORTUNITY_END`, writes no orders, and does not change the consecutive error
  count;
- changing the setting is persisted, survives service restart, takes effect for
  subsequent capacity checks, and rejects values below `1`;
- settings controller tests use `AuthenticatedClient` and cover authorization,
  the additive info field, valid updates, and invalid input;
- the opportunity report exposes the new count when
  `onlyWithOrders=false`;
- a Polymarket `MATCHED` status becomes `EXECUTED` with execution/fill fields,
  while a Kalshi `matched` status remains `PLACED`;
- null or failed provider status responses do not persist a terminal `ERROR` or
  release an active-pair slot.

Final validation is `cd backend && ./gradlew test`,
`cd frontend && npm run build`, and `git diff --check`.


## Plan

### 1. Shared contracts and configuration

- [x] Add a single shared active/terminal classification for `OrderState` under
  `backend/src/main/java/hzpro/com/tradingdesk/arbitrage/model/` so the monitor,
  order service, status checker, and repository queries consistently treat
  `CREATED`, `PENDING`, `PLACED`, and `UNKNOWN` as active and the other states as
  terminal.
- [x] Add the typed internal contracts needed by the flow: an
  `ArbitrageOrderLifecycleEvent` carrying opportunity UUID, both tickers,
  database order ID, platform, and effective status; and an
  `OrderStatusRefreshResult` carrying completion/check/update counts. Keep these
  out of controller DTO packages because they are internal service contracts.
- [x] Add `activePairsLimit` to `ArbitrageConfig`, bind
  `arbitrage.active-pairs-limit=${ACTIVE_PAIRS_LIMIT:1}` in
  `backend/src/main/resources/application.properties`, reject values below one
  during startup, and document `ACTIVE_PAIRS_LIMIT` in `backend/.env.sample`.
- [x] Add focused configuration tests for the default, a positive override, and
  startup rejection when the environment-backed value is zero or negative.

### 2. Migration, close reason, and query support

- [x] Add the next Flyway migration after the repository's current latest
  version (currently `V25`) to extend
  `chk_arbitrage_events_close_reason` with `ACTIVE_PAIR_LIMIT_REACHED` and add
  the two partial indexes specified for `OPPORTUNITY_START` pair lookup and
  unfinished `arbitrage_orders` lookup.
- [x] Add `ACTIVE_PAIR_LIMIT_REACHED` to
  `backend/src/main/java/hzpro/com/tradingdesk/arbitrage/model/OpportunityCloseReason.java`
  and verify the entity can persist/read the new enum value against the migrated
  constraint.
- [x] Introduce a typed unfinished-order candidate record containing all fields
  required for venue status checks, fill calculations, event publication, and
  exact pair identity; do not return `Object[]`, raw maps, or generic response
  containers.
- [x] Extend the order repository layer with all-pairs and exact-ticker-pair
  queries for `CREATED`/`PENDING`/`PLACED`/`UNKNOWN` candidates, joining the
  related `OPPORTUNITY_START` to obtain both market tickers.
- [x] Add Testcontainers-backed repository tests covering both query scopes,
  both directions sharing the same ticker pair, active versus terminal states,
  a UUID with one or two order rows, and exclusion of unrelated ticker pairs.

### 3. Persisted and editable active-pair setting

- [x] Add `ACTIVE_PAIRS_LIMIT_KEY`, `getActivePairsLimit()`, and
  `setActivePairsLimit(int)` to `SettingsService`: validate positive integers,
  use `ArbitrageConfig.activePairsLimit` only when the row is absent, and upsert
  changes in the existing `settings` table.
- [x] Extend `SettingsInfoDto` with `activePairsLimit`; add typed
  `ActivePairsLimitDto` and settings error response records under
  `controller/dto/`.
- [x] Add admin-only `PUT /api/settings/active-pairs-limit` to
  `SettingsController`, returning the persisted canonical value and a typed
  `400` response for missing, malformed, zero, or negative input. Keep
  `GET /api/settings/info` authenticated and include the resolved limit.
- [x] Extend `SettingsControllerIntegrationTest` using `AuthenticatedClient` to
  verify the environment fallback, database persistence, valid live updates,
  survival across service reload/restart behavior, invalid input, and endpoint
  authorization.

### 4. Lifecycle events from order creation

- [x] Inject the application event publisher (or the selected equivalent typed
  callback) into `ArbitrageOrderService` and centralize save-and-publish behavior
  so an event is emitted only after the corresponding order row and database ID
  exist.
- [x] Publish one lifecycle event immediately after each Polymarket and Kalshi
  row is saved as `PENDING`, including the opportunity UUID and both market
  tickers supplied to `createOrders(...)`.
- [x] Publish another lifecycle event after every final order-service status save
  (`PLACED`, `ERROR`, or any future effective state), including partial-success
  and exception paths. Ensure that a saved `PENDING` row still emits its event if
  later submission work fails.
- [x] Extend `ArbitrageOrderServiceTest` to capture synchronous lifecycle events
  and verify their ordering, IDs, tickers, platforms, and statuses for two
  successes, one-leg failure, two failures, pre-persistence validation failure,
  and an exception after a `PENDING` row was saved.

### 5. Reusable unfinished-order status refresh

- [x] Refactor `OrderStatusChecker` so the scheduled entry point delegates to a
  reusable `checkUnfinishedOrders()` method and add
  `checkUnfinishedOrdersForPair(polymarketTicker, kalshiTicker)` for the monitor's
  synchronous at-limit refresh; return `OrderStatusRefreshResult` from both.
- [x] Add a shared refresh lock around scheduled and on-demand runs so they
  cannot query or update the same order concurrently, and ensure the pair-specific
  call observes candidates selected after any already-running refresh finishes.
- [x] Update the checker to process every active state, not only `PLACED`: rows
  without provider order IDs retain and publish their stored active state; rows
  with IDs call the correct venue and publish their effective state even when it
  did not change.
- [x] Make status lookup failures fail closed. A null response, transport/HTTP
  error, parsing failure, or populated Polymarket `OrderStatus.error()` must keep
  the prior active state, must not persist terminal `ERROR`, and must publish the
  retained effective state for registry initialization.
- [x] Make status normalization platform-aware: Polymarket `MATCHED` becomes
  `EXECUTED`, Kalshi `matched` remains `PLACED`, and all existing mappings remain
  unchanged. Populate `executedAt`, `filledQuantity`, and `filledAmount` for the
  Polymarket terminal transition.
- [x] Publish an `ArbitrageOrderLifecycleEvent` after each effective scheduled or
  on-demand result, after any changed status is persisted, so the monitor never
  needs to reread the status to update its maps.
- [x] Add unit/service tests for unchanged active states, each terminal state,
  missing order IDs, Polymarket `MATCHED`, Kalshi `matched`, null/error provider
  responses, filled-field calculation, event contents, and refresh-result
  counts.
- [x] Add a concurrency test proving a scheduled all-orders refresh and an
  on-demand exact-pair refresh do not issue overlapping venue requests or race
  status persistence.

### 6. Monitor-owned active-pair registry

- [x] Add `MarketPairKey`, `ActivePairInfo`, the ticker-pair keyed
  `activeOrderPairs` map, and a registry-ready flag to
  `ArbitrageMonitorService`. Use concurrency-safe nested state and monitor-owned
  helper methods for all registry mutations; use per-key `ConcurrentHashMap`
  compute operations instead of a global registry lock.
- [x] Implement monitor-owned event handling: active lifecycle states add/update
  a database order ID; terminal states remove it; a UUID and empty pair entry are
  removed as soon as no active order IDs remain.
- [x] Keep the registry single-phase: do not add temporary UUID reservations;
  populate it only from real initial `PENDING` events and status-check events,
  with no reverse UUID index or post-call database read.
- [x] Change `pairLocks` from `similarMarketId` keys to exact `MarketPairKey`
  keys. Resolve and validate both tickers before locking so opposite directions
  and duplicate similarity rows for the same markets share one lock.
- [x] During monitor start and restart, clear the registry, mark it not
  ready, synchronously invoke `checkUnfinishedOrders()`, consume its events, and
  mark ready only after candidate processing completes successfully. Block order
  submission when initialization is incomplete or failed.
- [x] Update `stopMonitoringResources()` and test cleanup helpers to clear the new
  map and readiness state. Ticker-keyed locks remove themselves
  when their in-flight user count reaches zero, so stop does not unsafely delete a
  lock that still protects an order-creation call.

### 7. Enforce the limit in the monitor

- [x] Update `ArbitrageMonitorService.checkArbitrage(...)` so the trading-enabled
  and cached-balance checks occur before the capacity check, then read the
  current setting and compare the in-memory count while holding the exact-pair
  lock.
- [x] When the first capacity check is at the limit, synchronously invoke
  `checkUnfinishedOrdersForPair(...)`, let its lifecycle events update the map,
  and recheck exactly once. Keep existing entries when the refresh is incomplete
  or a provider state is unconfirmed.
- [x] If the retry is still at capacity, skip `ArbitrageOrderService`, save the
  matching `OPPORTUNITY_END` with `ACTIVE_PAIR_LIMIT_REACHED`, remove the entry
  from `activeOpps`, and log UUID, tickers, active count, and limit without
  incrementing order errors, refreshing balances, or disabling trading.
- [x] Split the capacity decision and order creation into monitor-owned
  `checkActivePairLimit(...)` and `createOrders(...)` methods. Call both
  explicitly from `checkArbitrage(...)`, branching on a typed decision while
  still holding the exact-pair lock. Persist and publish both initial `PENDING`
  orders synchronously before either venue submission, use those events as the
  first registry population, and preserve the existing close reason returned by
  order creation without synthetic cleanup.
- [x] Add focused monitor tests for below-limit placement, same-direction and
  opposite-direction blocking, independent ticker pairs, duplicate
  `similar_market_id` values, missing tickers, insufficient balance before the
  capacity check, incomplete registry initialization, and no interaction with
  trading clients on rejection.
- [x] Add a concurrent monitor test where two updates compete for the final slot
  and assert that only one active UUID reaches `createOrders(...)`, the registry
  remains consistent, and the rejected opportunity is closed with the limit
  reason.
- [x] Add at-limit refresh tests for: a newly terminal provider result releasing
  the slot and allowing placement; an unchanged active result rejecting; a
  provider failure retaining the slot; and a pair-specific refresh affecting no
  unrelated pair.
- [x] Add restart/recovery coverage that seeds unfinished database candidates,
  mocks venue status responses, starts or restarts the monitor, and verifies the
  map is populated only through emitted status events before trading is allowed.

### 8. Reporting and frontend

- [x] Extend `OpportunityReportRepository` with a distinct
  `ACTIVE_PAIR_LIMIT_REACHED` aggregate; add
  `activePairLimitReachedCloseCount` to `OpportunityReportAggregateDto` and
  `OpportunityReportDto`; and map the field in `ArbitrageController`.
- [x] Extend `ArbitrageControllerOpportunityReportIntegrationTest` to verify the
  new count, including visibility with `onlyWithOrders=false` and continued
  exclusion under `onlyWithOrders=true` when the skipped opportunity has no
  order rows.
- [x] Add `setActivePairsLimit` to `frontend/src/api/settings.js`, load the value
  through the existing settings-info request, and add the integer input,
  explicit save action, loading state, success message, and rollback-on-error
  behavior to `frontend/src/views/Settings.vue`.
- [x] Add the `Active Pair Limit` close-reason column to
  `frontend/src/views/OpportunityReport.vue` and include its value in any related
  aggregate/display logic.

### 9. Final verification and handoff

- [x] Run focused backend tests while implementing, then run the full backend
  suite with `cd backend && ./gradlew test`; record any Docker/Testcontainers
  limitation rather than marking this item complete without evidence.
- [x] Run `cd frontend && npm run build` and review the Settings load/save/revert
  wiring plus an opportunity-report response containing the new close reason
  against the authenticated backend integration coverage.
- [x] Run `git diff --check`, review the Flyway migration against both fresh and
  upgraded schemas, and verify all repository/controller response shapes are
  typed DTOs or records as required by `AGENTS.md`.
- [x] Re-read this task against the implementation, mark only verified items
  complete, and record any architectural deviation—especially any change to the
  single-backend-writer assumption or event-driven registry ownership—before
  declaring the task finished.

Verification recorded on 2026-09-27:

- `cd backend && ./gradlew test`: 201 tests passed.
- `cd frontend && npm run build`: passed; Vite reported only its existing large
  chunk-size advisory.
- The single-backend-writer assumption and monitor-owned, event-driven registry
  design are unchanged. In-flight ticker-pair locks deliberately self-remove
  after their final user instead of being forcibly cleared during stop.
