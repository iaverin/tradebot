# Spec: Portfolio Dashboard

**Issue:** [#97 — Portfolio dashboard](https://github.com/hzprotocol/gb-trading-desk/issues/97)
**Status:** Draft
**Author:** Claude (from issue by @iaverin)
**Date:** 2026-07-09

---

## Overview

Add a **Portfolio Section** to the existing Dashboard page that displays six key portfolio indicators, broken down by trading venue (Kalshi and Polymarket):

| # | Indicator | Description |
|---|---|---|
| 1 | **Total Portfolio (USD)** | In Cash + In Assets + In Orders |
| 2 | **In Cash (USD)** | Available (uncommitted) cash balance |
| 3 | **In Orders (USD)** | Value tied up in open/resting orders |
| 4 | **In Assets (USD)** | Current market value of open positions |
| 5 | **Resolved Profit** | Net profit from settled/resolved markets |
| 6 | **Resolved Loss** | Net loss from settled/resolved markets |

**Guiding rule:** If an indicator cannot be fetched for a venue (API error, unimplemented endpoint, missing credentials), return `null`/absent for that indicator — never fail the entire dashboard.

---

## API Research: Indicator → Endpoint Mapping

### Kalshi

Base: `https://api.elections.kalshi.com/trade-api/v2` (or `external-api.kalshi.com` for production)
Auth: RSA-PSS headers (`KALSHI-ACCESS-KEY`, `KALSHI-ACCESS-SIGNATURE`, `KALSHI-ACCESS-TIMESTAMP`) — already implemented in `KalshiAuthorizedClient`.

| # | Indicator | Endpoint | Response Field(s) | Calculation |
|---|---|---|---|---|
| 1 | **Total Portfolio** | _(computed)_ | In Cash + In Assets + In Orders | Sum of indicators 2 + 3 + 4 |
| 2 | In Cash USD | `GET /portfolio/balance` | `balance` (available cash, cents) | `balance / 100` |
| 3 | In Orders USD | `GET /portfolio/orders?status=resting` | `orders[]` → `remaining_count_fp` × `yes_price_dollars` (or `no_price_dollars`) | Sum over all resting orders |
| 4 | In Assets USD | `GET /portfolio/balance` | `portfolio_value` (cents) | `portfolio_value / 100` |
| 5 | Resolved Profit | `GET /portfolio/settlements` | `revenue`, `yes_total_cost_dollars`, `no_total_cost_dollars`, `fee_cost` | Sum of positive net P&L: `revenue/100 - yes_total_cost - no_total_cost - fee_cost > 0` |
| 6 | Resolved Loss | `GET /portfolio/settlements` | (same as above) | Sum of negative net P&L (absolute value): where `revenue/100 - yes_total_cost - no_total_cost - fee_cost < 0` |

**Notes:**
- `GET /portfolio/summary/total_resting_order_value` exists but is documented as "FCM-only" — **not used**. We compute In-Orders value manually from the orders list.
- Settlements require pagination to aggregate all history. Use cursor-based pagination (max 200 per page per the docs, though the spec says limit up to 1000).
- `portfolio_value` from `/balance` already represents the mark-to-market value of all open positions, so we use it directly for "In Assets."
- `balance` from `/balance` is available (uncommitted) cash — used for "In Cash."

### Polymarket

Polymarket uses **three** APIs. Two are relevant here:

| API | Base URL | Auth | Used For |
|---|---|---|---|
| **Data API** | `https://data-api.polymarket.com` | None (public) | Positions, closed positions, portfolio value |
| **CLOB API** | `https://clob.polymarket.com` | L2 HMAC (already in `PolymarketAuthorizedClient`) | Balance, open orders |

Wallet address: available from `arbitrage.polymarket-deposit-wallet-address` config property.

| # | Indicator | API | Endpoint | Response Field(s) | Calculation |
|---|---|---|---|---|---|
| 1 | **Total Portfolio** | _(computed)_ | — | In Cash + In Assets + In Orders | Sum of indicators 2 + 3 + 4 |
| 2 | In Cash USD | CLOB | `GET /balance-allowance?asset_type=COLLATERAL&signature_type=N` | `balance` (6-decimal string) | `balance / 1_000_000` |
| 3 | In Orders USD | CLOB | `GET /data/orders` | `data[]` where `status == "ORDER_STATUS_LIVE"` → `(original_size - size_matched) × price` | Sum over all LIVE orders. Size and price are decimal strings; size is in micro-units (6 decimals). |
| 4 | In Assets USD | Data | `GET /value?user={addr}` | `value` (USDC) | Direct |
| 5 | Resolved Profit | Data | `GET /closed-positions?user={addr}` | `realizedPnl` (USDC) | Sum of positive `realizedPnl` |
| 6 | Resolved Loss | Data | `GET /closed-positions?user={addr}` | `realizedPnl` (USDC) | Sum of negative `realizedPnl` (absolute value) |

**Notes:**
- The **Data API is public** — no auth headers needed. We need a new lightweight HTTP client for it (or can use `RestTemplate` since there's no signing). This is a **new integration**.
- CLOB order sizes and prices are returned as **decimal strings**. Internally sizes use 6 decimal places and prices are in basis points (0–10000). Parse carefully.
- Data API pagination: `limit` (0–500 for positions, 0–50 for closed-positions), `offset` for pagination.
- `realizedPnl` on closed positions is already computed by Polymarket — usable directly.

---

## Backend Implementation Plan

### 1. New REST Client: `PolymarketDataApiClient`

A simple client for the public Polymarket Data API.

**File:** `backend/src/main/java/hzpro/com/tradingdesk/client/PolymarketDataApiClient.java`

- Uses `RestTemplate` (or the shared `CloseableHttpClient` bean)
- Base URL configurable via `arbitrage.polymarket-data-api-url` (default: `https://data-api.polymarket.com`)
- Methods:
  - `getPositions(String walletAddress)` → `GET /positions?user={addr}`
  - `getClosedPositions(String walletAddress)` → `GET /closed-positions?user={addr}` (paginated)
  - `getPortfolioValue(String walletAddress)` → `GET /value?user={addr}`
- Returns parsed DTOs (see below). Handles pagination internally for `closed-positions` (accumulate all pages).
- Null-on-failure semantics matching existing client patterns.

### 2. New DTOs

**Package:** `backend/src/main/java/hzpro/com/tradingdesk/controller/dto/`

#### `PortfolioDashboardDto` — top-level response

```java
public record PortfolioDashboardDto(
    PortfolioVenueDto kalshi,
    PortfolioVenueDto polymarket
) {}
```

#### `PortfolioVenueDto` — per-venue indicators

```java
public record PortfolioVenueDto(
    BigDecimal totalPortfolioUsd,   // computed: inCash + inAssets + inOrders (null if any component is null)
    BigDecimal inCashUsd,           // null if unavailable
    BigDecimal inOrdersUsd,         // null if unavailable
    BigDecimal inAssetsUsd,         // null if unavailable
    BigDecimal resolvedProfit,      // null if unavailable
    BigDecimal resolvedLoss         // null if unavailable
) {}
```

**Package:** `backend/src/main/java/hzpro/com/tradingdesk/client/dto/`

#### Data API response DTOs (Polymarket)

```java
// GET /value response
public record PolymarketPortfolioValueDto(String user, BigDecimal value) {}

// GET /positions item
public record PolymarketPositionDto(
    String conditionId, String asset, String title,
    BigDecimal size, BigDecimal avgPrice, BigDecimal curPrice,
    BigDecimal initialValue, BigDecimal currentValue,
    BigDecimal cashPnl, BigDecimal realizedPnl,
    // ... other fields parsed on demand
) {}

// GET /closed-positions item
public record PolymarketClosedPositionDto(
    String conditionId, String asset, String title,
    BigDecimal avgPrice, BigDecimal totalBought,
    BigDecimal realizedPnl, BigDecimal curPrice,
    Long timestamp, String outcome
    // ... other fields parsed on demand
) {}

// GET /balance-allowance response (already handled by PolymarketBalanceService.Balance)
```

### 3. New Services

**Package:** `backend/src/main/java/hzpro/com/tradingdesk/service/account/`

#### `KalshiPortfolioService`

Fetches and aggregates all 6 Kalshi indicators.

- Injects `KalshiAuthorizedClient`, `ObjectMapper`
- Methods:
  - `PortfolioVenueDto getPortfolio()` — main entry point, calls individual methods, catches exceptions per-indicator
  - `BigDecimal getInCashUsd()` — calls `/portfolio/balance`, returns `balance / 100`
  - `BigDecimal getInOrdersUsd()` — calls `/portfolio/orders?status=resting`, sums `remaining_count * price`
  - `BigDecimal getInAssetsUsd()` — calls `/portfolio/balance`, returns `portfolio_value / 100`
  - `BigDecimal getTotalPortfolioUsd()` — `inCash + inAssets + inOrders` (null if any component is null)
  - `BigDecimal getResolvedProfit()` — calls `/portfolio/settlements` (paginated), sums positive net P&L
  - `BigDecimal getResolvedLoss()` — calls `/portfolio/settlements` (paginated), sums negative net P&L (absolute)

Each indicator fetch is wrapped in try-catch; failures log a warning and return `null`.

**Pagination for settlements:** Use a loop with cursor-based pagination (limit=200). Continue until cursor is empty or no more results.

**In-Orders calculation:** For each resting order, `value = parseBigDecimal(remaining_count_fp) * parseBigDecimal(yes_price_dollars)` (or `no_price_dollars` if YES price is 0). Sum all values.

#### `PolymarketPortfolioService`

Fetches and aggregates all 6 Polymarket indicators.

- Injects `PolymarketAuthorizedClient`, `PolymarketDataApiClient`, `ArbitrageConfig` (for wallet address)
- Methods:
  - `PortfolioVenueDto getPortfolio()` — main entry point
  - `BigDecimal getInCashUsd()` — CLOB `GET /balance-allowance`, returns `balance / 1_000_000`
  - `BigDecimal getInOrdersUsd()` — CLOB `GET /data/orders`, filter LIVE, sum remaining value
  - `BigDecimal getInAssetsUsd()` — Data API `GET /value`, direct
  - `BigDecimal getTotalPortfolioUsd()` — `inCash + inAssets + inOrders` (null if any component is null)
  - `BigDecimal getResolvedProfit()` — Data API `GET /closed-positions`, sum positive `realizedPnl`
  - `BigDecimal getResolvedLoss()` — Data API `GET /closed-positions`, sum negative `realizedPnl` (abs)

**In-Orders calculation (Polymarket CLOB):**
- `remaining = parseBigDecimal(original_size) - parseBigDecimal(size_matched)` (both in micro-units, 6 decimals)
- `price_in_usdc = parseBigDecimal(price)` (CLOB price is a string like `"0.50"` meaning $0.50)
- `value = remaining / 1_000_000 * price`

**Note on Data API `realizedPnl`:** The Data API returns `realizedPnl` as a signed number in USDC. Positive = profit, negative = loss.

### 4. New Controller

**File:** `backend/src/main/java/hzpro/com/tradingdesk/controller/PortfolioController.java`

```java
@RestController
@RequestMapping("/portfolio")
@RequiredArgsConstructor
public class PortfolioController {

    private final KalshiPortfolioService kalshiPortfolioService;
    private final PolymarketPortfolioService polymarketPortfolioService;

    @GetMapping("/dashboard")
    public PortfolioDashboardDto dashboard() {
        PortfolioVenueDto kalshi = fetchSafely(kalshiPortfolioService::getPortfolio);
        PortfolioVenueDto polymarket = fetchSafely(polymarketPortfolioService::getPortfolio);
        return new PortfolioDashboardDto(kalshi, polymarket);
    }

    private PortfolioVenueDto fetchSafely(Supplier<PortfolioVenueDto> supplier) {
        try {
            return supplier.get();
        } catch (Exception e) {
            log.warn("Failed to fetch portfolio for venue", e);
            return new PortfolioVenueDto(null, null, null, null, null, null);
        }
    }
}
```

Route: `GET /portfolio/dashboard` — requires JWT auth (under `/api/**` in SecurityConfig... _verify path matches_).

> **Note:** The existing `BalanceController` is mapped to `/balance` which is **not** under `/api/**`. The new controller should be mapped to `/api/portfolio` or the SecurityConfig should be updated. **Check `SecurityConfig`** — if `/balance/**` is explicitly permitted, follow the same pattern. Otherwise map to `/api/portfolio/**`.

### 5. Security Config

Verify in `SecurityConfig.java` that `/portfolio/dashboard` (or `/api/portfolio/**`) is accessible to authenticated users. Follow the same pattern as the existing `/balance/**` endpoints.

### 6. Config Properties

New optional property in `application.properties`:

```properties
# Polymarket Data API (public, no auth)
polymarket.data-api-url=https://data-api.polymarket.com
```

(Or add to `ArbitrageConfig` if that's where Polymarket config lives.)

---

## Frontend Implementation Plan

### 1. New API Module

**File:** `frontend/src/api/portfolio.js`

```js
import api from './axios'

export const portfolioApi = {
  getDashboard: () => api.get('/portfolio/dashboard'),
}
```

### 2. Update Dashboard.vue

Add a **Portfolio Section** to the existing Dashboard page. Placement: between the balance row and the monitor-status row.

**Layout:**

```
┌──────────────────────────────────────────────────────────────┐
│  Portfolio Section                                           │
│                                                              │
│  ┌───────── Kalshi ─────────┐  ┌────── Polymarket ─────────┐│
│  │ Total Portfolio     $X   │  │ Total Portfolio       $X  ││
│  │   In Cash           $X   │  │   In Cash             $X  ││
│  │   In Orders         $X   │  │   In Orders           $X  ││
│  │   In Assets         $X   │  │   In Assets           $X  ││
│  │ Resolved Profit     $X   │  │ Resolved Profit       $X  ││
│  │ Resolved Loss       $X   │  │ Resolved Loss         $X  ││
│  └──────────────────────────┘  └────────────────────────────┘│
└──────────────────────────────────────────────────────────────┘
```

**Implementation details:**
- Two `el-card` components side by side (`el-row` → `el-col :span="12"` each)
- Each card shows 6 indicator rows with label + formatted USD value
- Total Portfolio is the primary/featured number at the top of each card (larger font, bold)
- Sub-indicators (In Cash, In Orders, In Assets) indented slightly below Total Portfolio
- Resolved Profit / Resolved Loss as separate rows
- Use `v-loading` directive while fetching
- Missing indicators (null) display as "—" (em dash)
- Values formatted as USD: `$1,234.56`
- Profit in green text, Loss in red text
- Fetch on mount via `onMounted`
- Add a small "Refresh" button in the card header

**Data flow:**
1. `onMounted` → call `portfolioApi.getDashboard()`
2. Response: `{ kalshi: {...}, polymarket: {...} }`
3. Store in reactive refs: `const kalshiPortfolio = ref(null)`, `const polymarketPortfolio = ref(null)`
4. Template renders each card with null-safe formatting

### 3. Styling

- Follow existing dashboard card patterns (`.status-grid`, `.status-row`, `.status-label`, `.status-value`)
- Total Portfolio: large font (`font-size: 28px; font-weight: 700` — matches existing `.balance-amount`)
- Sub-indicators: smaller font with slight indent (`padding-left: 12px` or nested grid)
- Profit: `color: #67C23A` (green, matches `.stat-green`)
- Loss: `color: #F56C6C` (red, matches `.stat-red`)
- Missing: `color: #909399` (gray, matches `.stat-gray`)

---

## Data Flow Diagram

```
Frontend (Dashboard.vue)
  │
  │  GET /api/portfolio/dashboard
  ▼
PortfolioController
  │
  ├──▶ KalshiPortfolioService
  │     │
  │     ├──▶ KalshiAuthorizedClient ──▶ Kalshi API
  │     │    GET /portfolio/balance        ──▶ inCash, inAssets
  │     │    GET /portfolio/orders          ──▶ inOrders
  │     │    GET /portfolio/settlements     ──▶ resolvedProfit, resolvedLoss
  │     │
  │     └── Aggregate → PortfolioVenueDto
  │           totalPortfolio = inCash + inAssets + inOrders
  │
  └──▶ PolymarketPortfolioService
        │
        ├──▶ PolymarketAuthorizedClient ──▶ Polymarket CLOB API
        │    GET /balance-allowance          ──▶ inCash
        │    GET /data/orders                ──▶ inOrders
        │
        ├──▶ PolymarketDataApiClient ──▶ Polymarket Data API (public)
        │    GET /value                      ──▶ inAssets
        │    GET /closed-positions           ──▶ resolvedProfit, resolvedLoss
        │
        └── Aggregate → PortfolioVenueDto
              totalPortfolio = inCash + inAssets + inOrders
```

---

## Error Handling Strategy

| Scenario | Behavior |
|---|---|
| One indicator fails for a venue | Return `null` for that indicator; others still populated. Total Portfolio also becomes `null`. |
| All indicators fail for a venue | Return `PortfolioVenueDto` with all nulls |
| Entire venue fetch throws | Catch in `PortfolioController.fetchSafely()`, return all-null DTO |
| Auth failure (401/403) | Let it propagate — frontend interceptor handles redirect to login |
| Network timeout | Log warning, return null for affected indicator(s) |
| API key not configured | Service logs warning, returns null |
| Empty response (no positions/orders) | Return `BigDecimal.ZERO`, not null |

---

## Files to Create

| # | File | Purpose |
|---|---|---|
| 1 | `backend/.../client/PolymarketDataApiClient.java` | Public REST client for Polymarket Data API |
| 2 | `backend/.../client/dto/PolymarketPortfolioValueDto.java` | DTO for `/value` response |
| 3 | `backend/.../client/dto/PolymarketPositionDto.java` | DTO for `/positions` response |
| 4 | `backend/.../client/dto/PolymarketClosedPositionDto.java` | DTO for `/closed-positions` response |
| 5 | `backend/.../controller/dto/PortfolioDashboardDto.java` | Top-level API response |
| 6 | `backend/.../controller/dto/PortfolioVenueDto.java` | Per-venue indicators (6 fields) |
| 7 | `backend/.../service/account/KalshiPortfolioService.java` | Kalshi portfolio aggregation |
| 8 | `backend/.../service/account/PolymarketPortfolioService.java` | Polymarket portfolio aggregation |
| 9 | `backend/.../controller/PortfolioController.java` | REST endpoint |
| 10 | `frontend/src/api/portfolio.js` | Frontend API module |

## Files to Modify

| # | File | Change |
|---|---|---|
| 1 | `backend/.../config/ArbitrageConfig.java` | Add `polymarket-data-api-url` property |
| 2 | `backend/.../security/SecurityConfig.java` | Ensure `/portfolio/**` is accessible (verify) |
| 3 | `frontend/src/views/Dashboard.vue` | Add Portfolio Section component/logic |

---

## Testing

### Backend (Automated)

- `KalshiPortfolioServiceTest`: Mock `KalshiAuthorizedClient`, verify aggregation logic with sample JSON responses
- `PolymarketPortfolioServiceTest`: Mock both `PolymarketAuthorizedClient` and `PolymarketDataApiClient`, verify aggregation
- `PortfolioControllerTest`: Use `AuthenticatedClient` test utility, mock services, verify 200 response structure
- Test partial-failure scenarios: one indicator null, all null, Total Portfolio becomes null when any component is null

### Backend (Manual)

- `@Tag("manual")` integration test that hits real APIs with real credentials
- Verify all 6 indicators return sensible values for both venues

### Frontend

- Verify the Portfolio Section renders correctly in the Dashboard
- Verify loading states, null handling, and formatting
- Manual E2E: login → Dashboard → verify portfolio cards show data

---

## Open Questions

1. ~~**Should "Total Portfolio" include "In Orders" value?**~~ **RESOLVED:** Yes — Total Portfolio = In Cash + In Assets + In Orders.

2. **Polymarket Data API pagination for closed-positions:** The limit is 50 per page. For active traders this could mean many pages. Should we:
   - (a) Fetch all pages (slow but complete)
   - (b) Fetch first N pages with a configurable limit
   - (c) Add a date filter (e.g., last 90 days)?
   _Recommendation: (b) with a default of fetching up to 500 records (10 pages)._

3. **Refresh cadence:** Should the portfolio data auto-refresh on an interval, or only on manual refresh?
   _Recommendation: Manual refresh + refresh on page mount. Add auto-refresh later if needed._

4. **SecurityConfig mapping:** Is `/portfolio` already covered by a security filter, or does it need to be added explicitly? _Needs verification._

---

## References

- [Kalshi API Docs](https://docs.kalshi.com) — `/portfolio/balance`, `/portfolio/orders`, `/portfolio/settlements`, `/portfolio/positions`
- [Polymarket API Docs](https://docs.polymarket.com/api-reference/introduction) — Data API (`/value`, `/positions`, `/closed-positions`) and CLOB API (`/balance-allowance`, `/data/orders`)
- Existing balance services: `KalshiBalanceService.java`, `PolymarketBalanceService.java`
- Existing auth clients: `KalshiAuthorizedClient.java`, `PolymarketAuthorizedClient.java`
- Existing Dashboard: `frontend/src/views/Dashboard.vue`
