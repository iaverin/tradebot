# Implementation Plan: Portfolio Dashboard

## Summary

Add a Portfolio Section to the Dashboard page displaying 6 indicators per venue (Kalshi & Polymarket):
Total Portfolio, In Cash, In Orders, In Assets, Resolved Profit, Resolved Loss.
Requires a new `PolymarketDataApiClient` for the public Polymarket Data API, two new
portfolio aggregation services, a new REST endpoint, and frontend updates to Dashboard.vue.

**Spec:** [`specs/portfolio-dashboard.md`](../portfolio-dashboard.md)
**Issue:** [#97](https://github.com/hzprotocol/gb-trading-desk/issues/97)

## Files Changed

| # | File | Change |
|---|------|--------|
| 1 | `backend/.../client/PolymarketDataApiClient.java` | **New** — public REST client for Polymarket Data API |
| 2 | `backend/.../client/dto/PolymarketPortfolioValueDto.java` | **New** — DTO for Data API `/value` response |
| 3 | `backend/.../client/dto/PolymarketClosedPositionDto.java` | **New** — DTO for Data API `/closed-positions` response |
| 4 | `backend/.../controller/dto/PortfolioDashboardDto.java` | **New** — top-level API response |
| 5 | `backend/.../controller/dto/PortfolioVenueDto.java` | **New** — per-venue indicators (6 fields) |
| 6 | `backend/.../service/account/KalshiPortfolioService.java` | **New** — Kalshi portfolio aggregation |
| 7 | `backend/.../service/account/PolymarketPortfolioService.java` | **New** — Polymarket portfolio aggregation |
| 8 | `backend/.../controller/PortfolioController.java` | **New** — `GET /portfolio/dashboard` endpoint |
| 9 | `backend/.../config/ArbitrageConfig.java` | **Modify** — add `polymarket-data-api-url` property |
| 10 | `frontend/src/api/portfolio.js` | **New** — frontend API module |
| 11 | `frontend/src/views/Dashboard.vue` | **Modify** — add Portfolio Section |

## Step 1: Polymarket Data API Client

- [x] **1.1** Create `PolymarketDataApiClient` in `client/` package (alongside `KalshiAuthorizedClient`, `PolymarketAuthorizedClient`)
- [x] **1.2** Use `CloseableHttpClient` (follows existing codebase pattern from `PolymarketPriceRestClient`; no auth needed for public API)
- [x] **1.3** Inject `ArbitrageConfig` for wallet address (`getPolymarketDepositWalletAddress()`) and base URL
- [x] **1.4** `getPortfolioValue()` → `GET /value?user={addr}`, returns `BigDecimal`
- [x] **1.5** `getClosedPositions()` → `GET /closed-positions?user={addr}`, paginated (limit=50, loop with offset until empty), returns `List<PolymarketClosedPositionDto>`
- [x] **1.6** Add config property `polymarketDataApiUrl` with default `https://data-api.polymarket.com` to `ArbitrageConfig`
- [x] **1.7** Null-on-failure semantics: catch exceptions, log warning, return `null` or empty list

## Step 2: DTOs

- [x] **2.1** Create `PolymarketPortfolioValueDto` in `client/dto/`: `String user, BigDecimal value`
- [x] **2.2** Create `PolymarketClosedPositionDto` in `client/dto/`: `String conditionId, String title, BigDecimal realizedPnl, Long timestamp` (+ other fields as needed for JSON deserialization)
- [x] **2.3** Create `PortfolioVenueDto` in `controller/dto/`: `BigDecimal totalPortfolioUsd, BigDecimal inCashUsd, BigDecimal inOrdersUsd, BigDecimal inAssetsUsd, BigDecimal resolvedProfit, BigDecimal resolvedLoss`
- [x] **2.4** Create `PortfolioDashboardDto` in `controller/dto/`: `PortfolioVenueDto kalshi, PortfolioVenueDto polymarket`

## Step 3: Kalshi Portfolio Service

- [x] **3.1** Create `KalshiPortfolioService` in `service/account/` (alongside existing `KalshiBalanceService`)
- [x] **3.2** Inject `KalshiAuthorizedClient` and `ObjectMapper`
- [x] **3.3** `getPortfolio()` → main method, calls each indicator method, catches exceptions per-indicator, returns `PortfolioVenueDto`
- [x] **3.4** `getInCashUsd()` → `GET /portfolio/balance`, parse `balance` (cents), return `balance / 100`
- [x] **3.5** `getInOrdersUsd()` → `GET /portfolio/orders?status=resting`, iterate orders, sum `remaining_count_fp * yes_price_dollars` (or `no_price_dollars`). Handle pagination with cursor (limit=200)
- [x] **3.6** `getInAssetsUsd()` → `GET /portfolio/balance`, parse `portfolio_value` (cents), return `portfolio_value / 100`
- [x] **3.7** `getResolvedProfit()` → `GET /portfolio/settlements`, paginate with cursor (limit=200), compute `revenue/100 - yes_total_cost - no_total_cost - fee_cost`, sum positive values
- [x] **3.8** `getResolvedLoss()` → same settlements loop, sum negative values (absolute)
- [x] **3.9** `getTotalPortfolioUsd()` → sum `inCash + inAssets + inOrders`, return `null` if any component is `null`
- [x] **3.10** Each fetch method wrapped in try-catch: log warning, return `null` on failure

## Step 4: Polymarket Portfolio Service

- [x] **4.1** Create `PolymarketPortfolioService` in `service/account/`
- [x] **4.2** Inject `PolymarketAuthorizedClient`, `PolymarketDataApiClient`, `ArbitrageConfig`
- [x] **4.3** `getPortfolio()` → main method, same per-indicator catch pattern
- [x] **4.4** `getInCashUsd()` → CLOB `GET /balance-allowance?asset_type=COLLATERAL&signature_type=N`, parse `balance` (6-decimal string), return `balance / 1000000`. Reuse parsing logic from `PolymarketBalanceService`
- [x] **4.5** `getInOrdersUsd()` → CLOB `GET /data/orders`, filter `status == "ORDER_STATUS_LIVE"`, for each: `remaining = (original_size - size_matched) / 1000000`, `value = remaining * price`, sum all. Use cursor pagination
- [x] **4.6** `getInAssetsUsd()` → Data API `GET /value?user={addr}`, return `value` directly
- [x] **4.7** `getResolvedProfit()` → Data API `GET /closed-positions?user={addr}`, sum positive `realizedPnl`
- [x] **4.8** `getResolvedLoss()` → Data API `GET /closed-positions?user={addr}`, sum negative `realizedPnl` (abs)
- [x] **4.9** `getTotalPortfolioUsd()` → sum `inCash + inAssets + inOrders`, return `null` if any component is `null`

## Step 5: Portfolio Controller

- [x] **5.1** Create `PortfolioController` in `controller/` package
- [x] **5.2** Map to `/portfolio` (follow same pattern as `BalanceController` which uses `/balance` — NOT under `/api/**`)
- [x] **5.3** `GET /portfolio/dashboard` → calls both services, wraps each in `fetchSafely()`, returns `PortfolioDashboardDto`
- [x] **5.4** `fetchSafely(Supplier<PortfolioVenueDto>)` → catch exceptions, return all-null `PortfolioVenueDto` on failure
- [x] **5.5** Verify in `SecurityConfig` that `/portfolio/**` is accessible to authenticated users — confirmed: `.anyRequest().authenticated()` covers it

## Step 6: Frontend — API Module

- [x] **6.1** Create `frontend/src/api/portfolio.js`
- [x] **6.2** Export `portfolioApi.getDashboard()` → `api.get('/portfolio/dashboard')`. Verified: `VITE_API_URL=http://localhost:8080`, controller at `/portfolio`, so call goes to `http://localhost:8080/portfolio/dashboard`
- [x] **6.3** Confirmed: same pattern as `BalanceController` (`/balance`) — no `/api` prefix needed.

## Step 7: Frontend — Dashboard Update

- [x] **7.1** Add Portfolio Section between the existing balance row and the monitor-status row in `Dashboard.vue`
- [x] **7.2** Add reactive refs: `portfolioLoading`, `kalshiPortfolio`, `polymarketPortfolio`
- [x] **7.3** Add `fetchPortfolio()` method — calls `portfolioApi.getDashboard()`, stores response
- [x] **7.4** Call `fetchPortfolio()` in `onMounted`
- [x] **7.5** Template: two `el-card` components in an `el-row` (`:span="12"` each)
- [x] **7.6** Each card layout:
  - Card header: venue name + Refresh button
  - Total Portfolio: large bold number at top (`font-size: 28px; font-weight: 700`)
  - Sub-indicator rows (In Cash, In Orders, In Assets): smaller, indented
  - Separator, then Resolved Profit (green) and Resolved Loss (red)
- [x] **7.7** Null-safe formatting: `null` → "—", values → `$1,234.56`
- [x] **7.8** Add Refresh button handler (Kalshi card only; Polymarket shares `portfolioLoading` state)

## Step 8: Tests

- [x] **8.1** `KalshiPortfolioServiceTest` — mock `KalshiAuthorizedClient`, provide sample JSON for balance/orders/settlements, verify each indicator computation
- [x] **8.2** `PolymarketPortfolioServiceTest` — mock `PolymarketAuthorizedClient` + `PolymarketDataApiClient`, verify each indicator
- [x] **8.3** `PortfolioControllerTest` — use `AuthenticatedClient`, mock both services, verify 200 + JSON structure
- [x] **8.4** Partial failure tests: mock one indicator throwing, verify others still populated, Total Portfolio becomes `null`
- [ ] **8.5** `@Tag("manual")` integration test — real API calls, verify sensible values returned _(deferred — requires real API credentials)_

## Design Decisions

- **Two separate services** (`KalshiPortfolioService`, `PolymarketPortfolioService`) rather than one unified service — venues have different auth schemes, API shapes, and parsing logic. Cleaner separation, easier to test independently.
- **`PortfolioVenueDto.totalPortfolioUsd` becomes null if any component is null** — can't compute a meaningful sum with missing data. Frontend displays "—".
- **Cursor-based pagination for Kalshi settlements** (limit=200) and **offset-based for Polymarket closed-positions** (limit=50, up to 10 pages) — matches each API's pagination model.
- **`PolymarketDataApiClient` uses `CloseableHttpClient`** — follows existing codebase pattern (`PolymarketPriceRestClient`). Public API with no auth, no semaphore needed.
- **Controller mapped to `/portfolio`** (not `/api/portfolio`) — follows existing `BalanceController` convention (`/balance`). Frontend axios base is `/api` so calls will be `/api/portfolio/dashboard`.
- **Per-indicator try-catch in services** — failures are isolated. If settlements fail, balance/orders still return. If one venue entirely fails, the other still returns data.
- **No caching in services** — each `GET /portfolio/dashboard` call fetches fresh data. Simple, no staleness bugs. Add caching later if performance becomes an issue.
- **`BigDecimal.ZERO` for empty data, `null` for failures** — distinguishes "you have no open orders" from "we couldn't fetch your orders."
