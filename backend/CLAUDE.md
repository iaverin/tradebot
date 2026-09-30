# GB Trading Desk — Backend

Spring Boot backend for prediction-market data fetching and **live arbitrage trading**
across **Kalshi** and **Polymarket**. **Opinion** (opinion.trade) is a third *fetch-only*
data source — its markets are ingested but it is **not** part of the arbitrage/trading engine.

- **Stack:** Java 23, Spring Boot 3.5, Gradle (Kotlin DSL), PostgreSQL, Flyway, Temporal SDK 1.25.2
- **Build dir:** `.build` (overridden in `build.gradle.kts`, not `build/`)
- **Base package:** `hzpro.com.tradingdesk`
- **Entry point:** `AuthApplication.java`
- **Config:** `src/main/resources/application.properties` + `.env` (via `spring-dotenv`)

## Build & run

```bash
./gradlew bootRun        # run the app
./gradlew build          # compile + test (artifacts in .build/)
./gradlew test           # automated tests (excludes @Tag("manual"))
./gradlew manualTest     # manual integration tests (real API calls)
docker compose -f docker-compose.temporal.yml up -d   # Temporal (server :7233, UI :8088)
```

## Module map (`hzpro/com/tradingdesk/...`)

- `client/` — shared HTTP clients for authorised venue API calls.
  - `KalshiAuthorizedClient.java` — RSA-PSS auth header attachment + generic execute methods for Kalshi.
  - `PolymarketAuthorizedClient.java` — L2 HMAC auth + L1 credential derivation + EIP-712 order signing + execute methods for Polymarket CLOB.
  - `HttpMethod.java` — enum (`GET`, `POST`, `DELETE`) with `createRequest(URI)` factory.
  - `client/config/` — `HttpClientWithHttpsProxyConfig.java` (shared `CloseableHttpClient` bean), `RetryConfig.java`.
- `service/account/` — balance services extracted from trading services.
  - `KalshiBalanceService.java` — `GET /portfolio/balance`.
  - `PolymarketBalanceService.java` — `GET /balance-allowance`.
- `marketsfetcher/` — fetches markets from Kalshi, Polymarket & Opinion into Postgres.
  - `service/fetchers/` — `KalshiFetcher`, `PolymarketFetcher`, `OpinionFetcher` extend `BaseFetcher`
    (`@Retryable` per page, `isPaused` / `forceStop` flags).
  - `BaseFetcher` supports per-request **custom headers** via `RequestParameters.headers`
    (added for Opinion's required `apikey` header).
  - **Opinion** (`OpinionFetcher` / `OpinionMapper` / `dto/opinion/`): page-number pagination
    (LIMIT 20) — *not* cursor-based like the others; sends an `apikey` header on every request
    and skips the fetch (logging a warning) if `OPINION_API_KEY` is blank. **Manual-trigger only**
    (`POST /fetch-opinion`); no Temporal schedule is registered for it.
  - `service/FetcherControllerService.java` — starts workflows via `WorkflowClient`.
  - `temporal/` — Temporal orchestration (see below). `FetchActivity.fetchOpinion()` routes the
    `"OPINION"` provider to `OpinionFetcher`.
- `arbitrage/` — the live-trading engine (most active area).
  - `config/ArbitrageConfig.java` — `@ConfigurationProperties(prefix = "arbitrage")`.
  - `service/ArbitrageMonitorService.java` — watches prices, detects opportunities.
  - `service/ArbitrageOrderService.java` — places/manages arbitrage orders; checks the
    arb is still valid **at order-create time** before committing (see PR #66).
  - `service/KalshiTradingService.java` — order placement/cancel/status on Kalshi.
  - `service/PolymarketTradingService.java` — order placement/cancel/status on Polymarket CLOB.
  - `service/KalshiFetchCommonOrderBookService.java` — fetches Kalshi orderbook via `KalshiAuthorizedClient`, converts to platform-agnostic `OrderBook`.
  - `service/PolymarketFetchCommonOrderBookService.java` — fetches Polymarket orderbook via `PolymarketPriceRestClient`, converts to `OrderBook`.
  - `service/PolymarketAuthL1Service.java` — L1 EIP-712 credential derivation (one-time, consumed by `PolymarketAuthorizedClient`).
  - `service/PolymarketEip712Signer.java` — EIP-712 order struct signing (injected into `PolymarketAuthorizedClient`).
  - `service/OrderStatusChecker.java`, `model/OrderStatus.java` — order lifecycle.
  - `rest/KalshiPriceRestClient.java` — public Kalshi price endpoint (no auth).
  - `rest/PolymarketPriceRestClient.java` — public Polymarket orderbook endpoint (no auth).
  - `websocket/` — Kalshi/Polymarket WS price clients, `WebSocketReconnectHelper`.
  - `util/` — `ArbitrageOrderUtils`, `WebSocketReconnectHelper`.
- `similarmarkets/` — matches equivalent markets across venues (own Temporal workflow).
- `common/orderbook/` — shared order-book domain types.
- `controller/` — REST API: `AuthController`, `MarketsFetcherController`,
  `ArbitrageController`, `SimilarMarketsController`, `DashboardController`, `ExternalApiController`,
  `BalanceController` (uses `KalshiBalanceService` / `PolymarketBalanceService`),
  `RequestsController` (venue API proxy supporting GET/POST/DELETE, returns `ProxyResponse(int statusCode, Object body)`).
- `security/` — JWT auth.

## Venue auth architecture

| Venue | Auth type | Client class | Signature |
|-------|-----------|-------------|-----------|
| Kalshi | RSA-PSS headers | `KalshiAuthorizedClient` | `timestamp + method + path` |
| Polymarket L1 | EIP-712 | `PolymarketAuthL1Service` | ECDSA over `ClobAuth` struct |
| Polymarket L2 | HMAC headers | `PolymarketAuthorizedClient` | `timestamp + method + path + body` |
| Polymarket orders | EIP-712 | `PolymarketAuthorizedClient.signOrder()` | EIP-712 over `Order` struct |

The pattern: `*AuthorizedClient` encapsulates auth header attachment + HTTP execution with null-on-failure semantics. Callers use `execute(HttpMethod, path, handler)` for simple requests or `execute(HttpMethod, path, body, handler)` for JSON-bodied requests. Services that need exception propagation (e.g. balance checks) handle the null response explicitly.

`KalshiRestOrderBookClient` **no longer exists** — its auth logic was extracted into `KalshiAuthorizedClient` and its orderbook-specific parsing moved into `KalshiFetchCommonOrderBookService`.

## Temporal (scheduling / orchestration)

- Task queue: `prediction-markets-task-queue`
- Schedules: `kalshi-fetch-schedule`, `polymarket-fetch-schedule`
- **Cron is 5-field standard format**, NOT Quartz 6-field.
- Beans wired in `marketsfetcher/temporal/config/TemporalConfig.java`.
- `temporal/scheduler/TemporalSchedulerService.java` — `@PostConstruct` schedule init + `getNextExecutionTime()`.
- Properties: `temporal.target-endpoint`, `temporal.namespace`.

## Polymarket trading gotchas (hard-won — don't relearn)

- **L1 auth (ClobAuth):** EIP-712 signing to create/derive the API key; implemented in `PolymarketAuthL1Service`, consumed lazily by `PolymarketAuthorizedClient`. Credentials are cached (volatile + double-checked locking).
- **L2 (HMAC):** per-request signing over `timestamp + method + path + body` with the L1-derived secret; implemented in `PolymarketAuthorizedClient`.
- **EIP-712 order signing:** `PolymarketAuthorizedClient.signOrder()` delegates to `PolymarketEip712Signer`.
- **Order version:** `PolymarketAuthorizedClient.orderVersion()` caches the CLOB's `/version` response. Exchange contract selection (v2/v3, neg-risk) happens in `PolymarketTradingService.placeOrder()`.
- **CLOB v3 order struct:** watch `signatureType` / EIP-1271 handling in `PolymarketEip712Signer`.
- **Deposit wallet / session signer:** configured via `arbitrage.polymarket-deposit-wallet-address` and `arbitrage.polymarket-session-signer`; both consumed by `PolymarketAuthorizedClient.signOrder()`.
- A **semaphore** guards concurrent Polymarket calls (`1e80dad`) — keep it; the API misbehaves under parallel signed requests.
- Arbitrage opportunities are **re-validated at order-create time** (`4ccde09`, PR #66); don't place on stale snapshots.

## Testing

- **Automated tests** (`./gradlew test`): `@SpringBootTest` + `@MockitoBean` on `CloseableHttpClient` and repositories. The mock pattern uses `thenAnswer` on `HttpClientResponseHandler` to intercept `httpClient.execute()`.
- **Manual tests** (`./gradlew manualTest`): `@Tag("manual")`, hit real venue APIs with real credentials. Balance manual tests in `service/account/` package.
- **No WireMock or MockWebServer** — all HTTP mocking is via Mockito on Apache HC5 `CloseableHttpClient`.
- Test profile (`application-test.yml`) uses Testcontainers PostgreSQL, disables Temporal.
- **Authenticated controller tests**: Use `AuthenticatedClient` (`src/test/java/.../testutil/AuthenticatedClient.java`) for any `@SpringBootTest` + `@AutoConfigureMockMvc` test hitting endpoints behind `@PreAuthorize("isAuthenticated()")`. It creates a random user, logs in, and wraps `MockMvc` — `client.perform(put(...))` automatically injects the `Authorization` header. Never manage JWT tokens or do manual login in tests.

## Flyway migrations

`src/main/resources/db/migration`, currently V1–V18.
- V2 created db-scheduler tables; **V5 drops them** (migrated to Temporal).
- Note the out-of-order `V7.1` (`create_similar_markets`) alongside `V7`.
- Always add a new `V{n}__description.sql`; never edit an applied migration.

## Conventions

- Keep CLAUDE.md for **durable, non-obvious** context (architecture, venue gotchas, decisions) —
  not a changelog. Git history records *what* changed; record *why* here.

---
_Related: see `.claude/*.md` notes and the auto-memory `polymarket-*` entries for deeper signing details._
