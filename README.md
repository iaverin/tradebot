# GB Trading Desk

GB Trading Desk is a cross-venue prediction-market trading system for Polymarket and Kalshi. It discovers equivalent binary markets, monitors both venues in real time, detects complementary-outcome arbitrage, submits paired limit orders, and tracks orders and positions through a Vue dashboard.

> [!CAUTION]
> Enabling trading submits real orders. The two venue orders are independent and are not atomic: one leg can fail, remain open, or fill without the other. The system currently records and monitors that condition, but it does not automatically cancel, hedge, or unwind an unmatched leg. Start with `TRADING_ENABLED=false`, small limits, and accounts you are prepared to supervise.

## Trading workflow

```text
Kalshi + Polymarket market data
              │
              ▼
      normalized market catalog
              │
              ▼
   ML-generated similar-market pairs
              │
       operator allow-list
              │
              ▼
 REST price prefetch + WebSocket updates
              │
              ▼
      two-direction spread check
              │
              ▼
  fresh order-book validation and sizing
              │
              ▼
 concurrent GTC limit orders on both venues
              │
              ▼
 status polling, order history, positions,
 opportunity reports, and profit estimates
```

The normal operator flow is:

1. Refresh the market catalog and recalculate similar markets.
2. Review matches in **Similar Markets** and enable only pairs whose contracts and resolution rules are genuinely equivalent.
3. Restart the arbitrage monitor so it reloads the enabled pair universe.
4. Observe opportunities with trading disabled.
5. Configure the order-cost and active-pair limits, verify credentials and balances, then enable live trading from **Settings**.
6. Supervise **Orders**, **Open Positions**, and the opportunity reports for asymmetric fills or stale orders.

Changing the allow-list does not update existing WebSocket subscriptions; restart the monitor after enabling or disabling market pairs. Disabling trading prevents new order pairs but does not cancel orders already sent to a venue.

## Trading strategy

### Market universe

The data pipeline fetches prediction markets and the Python similarity service produces candidate Polymarket/Kalshi matches. Candidates do not become tradable automatically. The monitor loads only rows present in `allowed_market_pairs`, capped by `ARBITRAGE_PAIR_LIMIT`, and requires Polymarket YES and NO token IDs.

Similarity is a discovery aid, not proof that two contracts settle identically. Before allowing a pair, compare at least:

- the event and outcome definitions;
- resolution source and edge-case rules;
- close and settlement times;
- cancellation and invalid-market behavior.

### Spread calculation

Prices are normalized to dollars per share in the `[0, 1]` range. For every enabled pair the monitor evaluates both complementary positions:

```text
PM_YES_KS_NO spread = 1 - (Polymarket YES ask + Kalshi NO ask)
PM_NO_KS_YES spread = 1 - (Polymarket NO ask  + Kalshi YES ask)
```

An opportunity starts when a spread is strictly greater than `ARBITRAGE_THRESHOLD`. For example, a Polymarket YES ask of `0.42` and a Kalshi NO ask of `0.54` produce a gross spread of `0.04` per matched share.

The calculated spread is gross. Trading fees, funding costs, withdrawal costs, latency, slippage, and settlement differences are not deducted. Set the threshold high enough to cover those costs and the execution risk.

### Price acquisition

At monitor startup, the backend:

- loads the enabled pairs;
- prefetches Polymarket and Kalshi prices over REST;
- refreshes cached venue balances;
- opens WebSocket subscriptions for both venues; and
- reconnects dropped streams automatically.

A spread check runs only when the cache contains complete YES/NO prices for both venues. Once a candidate appears, the execution path fetches fresh order books and checks the selected best asks again. If the order-book spread no longer meets the threshold, no orders are created.

### Execution gates

Live placement requires all of the following:

| Gate | Behavior |
|---|---|
| Pair is allowed | Only operator-approved Polymarket/Kalshi ticker pairs are monitored. |
| Complete prices | Both YES and NO prices must be available for both venues. |
| Pair serialization | A per-market-pair lock prevents overlapping evaluations inside one backend instance. |
| Active-pair capacity | Unfinished order-pair attempts for the exact pair must be below `ACTIVE_PAIRS_LIMIT`. |
| Trading enabled | The persisted runtime switch must be on; the environment value is only the initial fallback. |
| Balance reserve | Each cached venue balance must be greater than `5 × MAX_ORDER_COST`. With the default `$6` cap, each venue must have more than `$30`. |
| Fresh executable spread | The selected best asks from both REST order books must still meet the configured threshold. |
| Minimum size and notional | Both legs must support at least 5 whole shares and at least `$1` notional per leg. |

If balance is unavailable, its safe cached value is zero and placement is blocked. Balances are refreshed at monitor startup and after a successful placement request.

## Order sizing and placement

Both legs use the same whole-share quantity. Given the best selected levels:

```text
depth = min(polymarket best-level shares, kalshi best-level shares)
cost_cap_quantity = floor(MAX_ORDER_COST / max(polymarket ask, kalshi ask))
quantity = min(depth, cost_cap_quantity)
```

This makes `MAX_ORDER_COST` a per-leg cap, not a cap for the combined pair. The order is skipped when the resulting quantity is below 5 shares or when either leg would be below the `$1` minimum notional. Fractional order-book sizes are truncated to whole shares.

The backend then schedules both venue requests concurrently:

- **Kalshi:** good-till-canceled limit order with `taker_at_cross` self-trade prevention.
- **Polymarket:** signed EIP-712, non-post-only, good-till-canceled BUY limit order.

Each response is persisted as a separate `arbitrage_orders` row linked by the opportunity UUID. After both requests complete and their rows are stored, the opportunity closes as `ORDERS_CREATED` even if either row has an `ERROR` state. Always inspect the two linked rows together instead of treating that close reason as proof that both legs were accepted.

### Order lifecycle

Provider statuses are normalized into these internal states:

| State | Meaning | Active-pair slot |
|---|---|---|
| `CREATED` | Local row created before a conclusive provider state. | Held |
| `PENDING` | Accepted but not yet resting or complete. | Held |
| `PLACED` | Live/resting at the venue. | Held |
| `UNKNOWN` | Provider state is not conclusive; treated conservatively. | Held |
| `EXECUTED` | Terminal execution state; filled quantity, amount, and execution time are recorded when available. | Released |
| `CANCELED` | Terminal canceled/expired state. | Released |
| `ERROR` | Placement or provider error. | Released |

`OrderStatusChecker` polls unfinished orders every 10 seconds. At startup or monitor restart it first refreshes all unfinished orders and reconstructs the active-pair registry; the monitor refuses to start if that initialization cannot complete. When a pair has reached its active limit, the checker refreshes that pair once more before rejecting a new attempt.

The placement error counter is accumulated from failed venue requests since trading was last enabled. When it reaches `CONSECUTIVE_ERRORS_COUNT_TO_STOP_TRADING`, the backend persists `TRADING_ENABLED=false`. Re-enabling trading from Settings resets the counter.

### Opportunity records

Every detected window is stored as linked `OPPORTUNITY_START` and `OPPORTUNITY_END` events with price snapshots, order books, direction, threshold, market metadata, and a close reason. Common close reasons include:

- `SPREAD_BELOW_THRESHOLD`
- `ORDERBOOK_PRICE_BELOW_THRESHOLD`
- `ORDERBOOK_EMPTY`
- `ORDER_BOOK_NOT_ENOUGH_AMOUT`
- `MINIMIM_ORDER_COST_NOT_MET`
- `ACTIVE_PAIR_LIMIT_REACHED`
- `ORDER_CREATION_FAILED`
- `ORDERS_CREATED`
- `PRICE_DATA_UNAVAILABLE`

The misspelled enum values are retained because they are persisted database values.

## Dashboard and operations

The frontend provides:

- **Dashboard** — venue balances, portfolio summary, recent orders, and execution counts.
- **Similar Markets** — review matches and allow or disallow individual pairs or event groups.
- **Arbitrage** — monitor health, active opportunities, linked orders, and manual restart.
- **Orders** — filterable order history grouped by opportunity UUID, with links to raw provider-status requests.
- **Open Positions** — current venue position snapshots and manual refresh.
- **Order Volume** and **Opportunity Activity** — UTC activity charts.
- **Report** — opportunity outcomes and close-reason breakdowns.
- **Profit** — gross report derived from stored order/fill values; it is not an accounting ledger and does not include all fees or unmatched-leg risk.
- **Settings** — live-trading switch, placement error count, order cap, minimum size, and active-pair limit.

## Configuration

Copy `backend/.env.sample` to `backend/.env`. Keep private keys and wallet credentials out of source control.

### Strategy and risk controls

| Variable | Default | Description |
|---|---:|---|
| `ARBITRAGE_THRESHOLD` | required | Minimum gross spread per share, expressed as a decimal such as `0.03`. |
| `TRADING_ENABLED` | `false` | Initial live-trading state when no persisted database setting exists. |
| `ARBITRAGE_PAIR_LIMIT` | `100` | Maximum allowed pairs loaded when the monitor starts. |
| `MAX_ORDER_COST` | `6` | Maximum USD cost of each venue leg. Must be `0` (which prevents a valid order size) or at least `6`. |
| `ACTIVE_PAIRS_LIMIT` | `1` | Maximum unfinished opportunity UUIDs for one exact market pair. Must be at least `1`; a persisted Settings value overrides it. |
| `CONSECUTIVE_ERRORS_COUNT_TO_STOP_TRADING` | `2` | Failed placement-request count that automatically turns trading off. |
| `ARBITRAGE_PREFETCH_TIMEOUT` | `300000` | REST startup-prefetch timeout in milliseconds. |

### Venue credentials

| Variable | Default | Description |
|---|---|---|
| `KALSHI_API_KEY` | — | Kalshi API key UUID. |
| `KALSHI_PRIVATE_KEY` | — | Base64-encoded Kalshi RSA private key, without PEM headers. |
| `POLY_PRIVATE_KEY` | — | EOA private key used for Polymarket authentication and order signing. |
| `POLYMARKET_CLOB_URL` | `https://clob.polymarket.com` | Polymarket CLOB base URL. |
| `DEPOSIT_WALLET_ADDRESS` | — | Optional Polymarket funding/maker wallet. |
| `SIGNATURE_TYPE` | `3` | Polymarket signature type: `0` EOA, `1` proxy, `2` Gnosis Safe, `3` ERC-1271. |
| `POLY_SESSION_SIGNER` | `false` | Wrap ERC-1271 signatures for a DepositWallet session signer. |
| `POLYMARKET_DATA_API_URL` | `https://data-api.polymarket.com` | Polymarket portfolio-data API. |

The backend derives Polymarket L2 API credentials from `POLY_PRIVATE_KEY` on the first authorized request. Verify the signature type, maker wallet, permissions, allowances, and funding model for the account before enabling trading.

### Platform configuration

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/trading_desk` | Shared PostgreSQL database. |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | Database user. |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | Database password. |
| `JWT_SECRET` | development placeholder | HS256 secret; use at least 256 bits outside local development. |
| `JWT_EXPIRATION` | `86400000` | JWT lifetime in milliseconds. |
| `TEMPORAL_TARGET_ENDPOINT` | `localhost:7233` | Temporal gRPC endpoint. |
| `TEMPORAL_NAMESPACE` | `default` | Temporal namespace. |
| `FETCHER_CRON_STRING_UTC` | `0 0 * * *` | Five-field market-refresh cron in UTC. |
| `PROXY_HOST`, `PROXY_PORT` | — | Optional fetcher proxy. |
| `PROXY_USERNAME`, `PROXY_PASSWORD` | — | Optional proxy credentials. |
| `PROXY_USE_SSL` | `false` | Connect to the proxy over HTTPS. |

## Getting started

### Prerequisites

- Docker and Docker Compose v2.20+
- Java 23
- Node.js 18+
- Python 3.13 and [`uv`](https://docs.astral.sh/uv/)

### Local development

Start PostgreSQL and Temporal:

```bash
docker compose up postgres temporal temporal-ui-proxy temporal-ui
```

Start the backend:

```bash
cd backend
cp .env.sample .env
# Set ARBITRAGE_THRESHOLD, JWT_SECRET, and venue credentials as needed.
./gradlew bootRun
```

Start the similarity service:

```bash
cd similar-markets
uv run alembic upgrade head
uv run python -m app
```

Start the frontend:

```bash
cd frontend
npm install
npm run dev
```

Local endpoints:

| Service | URL |
|---|---|
| Frontend | `http://localhost:5173` |
| Backend | `http://localhost:8080` |
| Similar Markets | `http://localhost:8000` |
| Temporal UI | `http://localhost:8088` (`admin` / `admin`) |

For containerized development, `docker compose up --build` starts the default services. The backend has the `manual` Compose profile and can be started explicitly with `docker compose up backend`.

## Trading API

Except for `/auth/login` and `/health`, frontend-facing endpoints require `Authorization: Bearer <JWT>`. Settings mutations additionally require the `ADMIN` authority.

### Monitor and orders

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/arbitrage/status` | Monitor state, loaded pair count, and active-opportunity count. |
| `POST` | `/api/arbitrage/restart` | Reload settings/pairs and rebuild monitoring connections. |
| `GET` | `/api/arbitrage/events/active` | Currently open opportunities. |
| `GET` | `/api/arbitrage/events/recent` | Recently unclosed opportunity records. |
| `GET` | `/api/arbitrage/orders/{uuid}` | Both venue orders for an opportunity. |
| `GET` | `/api/arbitrage/orders` | Paginated order history; supports `status` and `platform`. |
| `GET` | `/api/arbitrage/orders/stats` | Counts and executed-order notional. |
| `GET` | `/api/arbitrage/orders/recent` | Most recent orders. |
| `GET` | `/api/arbitrage/orders/daily-totals` | Executed notional grouped by UTC date. |
| `GET` | `/api/arbitrage/opportunities/daily-stats` | Daily opportunity and linked-order activity. |
| `GET` | `/api/arbitrage/opportunities/report` | Pair-level close reasons and execution results. |
| `GET` | `/api/arbitrage/opportunities/profit-report` | Gross order/fill-based profit estimate. |

### Controls and portfolio

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/settings/info` | Current order cap, minimum shares, errors, and active-pair limit. |
| `GET` | `/api/settings/trading-enabled` | Current persisted trading state. |
| `PUT` | `/api/settings/trading-enabled` | Enable or disable new live placement. |
| `PUT` | `/api/settings/active-pairs-limit` | Update the per-market active-pair limit. |
| `GET` | `/similar-markets/list` | Review matched markets and allow-list state. |
| `PUT` | `/similar-markets/{id}/toggle` | Toggle one pair in the allow-list. |
| `PUT` | `/similar-markets/event/toggle` | Toggle all pairs in a matched event group. |
| `GET` | `/balance/{kalshi|polymarket}` | Fetch a live venue balance. |
| `GET` | `/portfolio/dashboard` | Portfolio summary for both venues. |
| `GET` | `/portfolio/positions` | Paginated position snapshots by venue. |
| `POST` | `/portfolio/positions/refresh` | Refresh both venue position snapshots. |

There is no public API endpoint for canceling an arbitrage order. The venue services contain cancellation clients, but cancellation is not wired into the current controller or automatic execution flow.

## Architecture

| Service | Responsibilities | Stack |
|---|---|---|
| `backend` | Market ingestion, Temporal workflows, pair monitoring, venue authentication, order placement/status, portfolios, reports, JWT API | Java 23, Spring Boot 3.5, JPA, Flyway |
| `similar-markets` | Embedding-based cross-venue event and market matching | Python 3.13, FastAPI, SQLAlchemy, sentence-transformers |
| `frontend` | Operator controls, monitoring, order/position views, reports | Vue 3, Vite, Element Plus, Pinia |
| Infrastructure | Shared persistence and workflow orchestration | PostgreSQL 16, Temporal, Docker Compose, Nginx |

All services use the `trading_desk` PostgreSQL database. Flyway owns the backend schema, including market data, allowed pairs, arbitrage events, settings, orders, and position snapshots. The similarity service reads the published market catalog and writes match candidates.

## Development commands

Backend:

```bash
cd backend
./gradlew build -x test
./gradlew test
./gradlew manualTest
```

Backend build output is written to `.build/`. Default tests exclude `@Tag("manual")`; integration tests use Testcontainers and require Docker.

Similar Markets:

```bash
cd similar-markets
uv run pytest
uv run ruff check .
```

Frontend:

```bash
cd frontend
npm run build
```

## Default local users

| Username | Password | Role |
|---|---|---|
| `admin` | `admin123` | `ADMIN` |
| `user` | `user123` | `USER` |

Change or remove the seeded credentials outside local development.
