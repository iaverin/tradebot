# Check Balance Before Trade

## Summary

Add pre-trade balance validation to the arbitrage monitor. Before placing orders for a
detected arbitrage opportunity, verify that both Kalshi and Polymarket account balances
exceed the trade cost. Balances are cached in-memory and refreshed on monitor start, on
reset, and after every successful order placement — no per-check API calls.

## Motivation

Currently `ArbitrageMonitorService.checkArbitrage()` places orders whenever
`config.isTradingEnabled()` is true and the spread exceeds the threshold, with no regard
for whether the accounts actually hold sufficient funds. An underfunded account would
cause the venue order placement to fail silently (the trading services return
`OrderResult(false, …)` on errors), wasting the opportunity and potentially leaving the
paired order in an inconsistent state.

## Implementation Plan

### 1. Create `BalanceService` — unified balance cache

**New file:** `backend/src/main/java/hzpro/com/tradingdesk/arbitrage/service/BalanceService.java`

```java
@Service
@Slf4j
public class BalanceService {

    private final KalshiBalanceService kalshiBalanceService;
    private final PolymarketBalanceService polymarketBalanceService;

    private volatile BigDecimal kalshiBalance = BigDecimal.ZERO;
    private volatile BigDecimal polymarketBalance = BigDecimal.ZERO;

    // Constructor injection

    /**
     * Fetches current balances from both venues and caches them.
     * Called on monitor start and on reset.
     */
    public void refreshBalances() {
        // fetch Kalshi balance, store in kalshiBalance
        // fetch Polymarket balance, store in polymarketBalance
        // log the refreshed values
        // catch exceptions per-venue — failure on one should not prevent
        // refreshing the other
    }

    /**
     * Returns true when both cached balances are >= the given amounts.
     * Does NOT make API calls — reads the volatile fields only.
     */
    public boolean hasSufficientBalance(BigDecimal requiredKalshi, BigDecimal requiredPolymarket) {
        // compare cached balances to required amounts
        // log the result (balances + required amounts + decision)
    }

    // getters for cached balances if needed
}
```

**Design decisions:**
- `volatile` fields are sufficient for the single-writer (monitor thread) / multi-reader pattern here. No `synchronized` or `AtomicReference` needed.
- Balance refresh is best-effort: if one venue's API fails, log a warning and keep the stale cached value rather than blocking trading entirely. The `hasSufficientBalance` check will compare against whatever is cached.
- Zero is the safe default for uninitialized balances — it will block trading until the first successful refresh, which is the conservative behavior.

### 2. Integrate into `ArbitrageMonitorService`

**Modify:** `backend/src/main/java/hzpro/com/tradingdesk/arbitrage/service/ArbitrageMonitorService.java`

#### 2a. Inject `BalanceService`

Add `BalanceService` as a constructor dependency.

#### 2b. Refresh balances on start and reset

In `startMonitoring()`, after the REST prefetch block and before setting `running = true`,
call `balanceService.refreshBalances()`.

```java
// After line 194 (catch block for REST prefetch) and before line 198 (WS client setup):
try {
    balanceService.refreshBalances();
} catch (Exception e) {
    log.warn("Initial balance refresh failed: {}", e.getMessage());
}
```

In `restart()` (line 452), the call chain is `stop()` → `startMonitoring()`, so
balances will be refreshed automatically via the `startMonitoring()` path. No
separate call needed in `restart()`.

#### 2c. Check balances before trading

In `checkArbitrage()`, around line 324 where the trading decision is made:

```java
// Current (line 324-329):
if (config.isTradingEnabled() && !arbitrageOrderService.createOrders(...)) {
    // reset opportunity
}

// New:
if (config.isTradingEnabled()) {
    BigDecimal tradeCost = calculateTradeCost(pm, ks, dir); // see 2d below
    if (balanceService.hasSufficientBalance(tradeCost, tradeCost)) {
        if (!arbitrageOrderService.createOrders(e.getUuid(), pmTicker, ksTicker, dir)) {
            log.info("[uuid={}] Orders are not created. Resetting opportunity.", e.getUuid());
            saveEnd(e, dir, spread, pm, ks);
            activeOpps.remove(key);
        }
    } else {
        log.warn("[uuid={}] Insufficient balance for trade. Required: ${}. Skipping.", e.getUuid(), tradeCost);
    }
}
```

The balance check must happen **inside the `lock`** (it already does — this code is
inside the `lock.lock()` block) so the cached balance and the order placement are
consistent with the in-memory opportunity state.

#### 2d. Calculate trade cost from price snapshot

Add a private helper to estimate the trade cost from the current price snapshot:

```java
/**
 * Estimates the cost of placing both sides of an arbitrage trade.
 * Uses the ask prices from the price snapshot as a conservative estimate.
 */
private BigDecimal calculateTradeCost(PriceSnapshot pm, PriceSnapshot ks, ArbitrageDirection dir) {
    BigDecimal pmPrice = dir == ArbitrageDirection.PM_YES_KS_NO ? pm.getYesAsk() : pm.getNoAsk();
    BigDecimal ksPrice = dir == ArbitrageDirection.PM_YES_KS_NO ? ks.getNoAsk() : ks.getYesAsk();
    // Use maxOrderCost as the quantity cap (same as ArbitrageOrderUtils)
    return config.getMaxOrderCost();
}
```

**Rationale:** The exact share quantity is determined later inside `ArbitrageOrderService.createOrders()`
based on live order books. At the check-balance stage we only have the price snapshot. Using
`maxOrderCost` (the per-trade cap) gives a conservative, fast estimate that never
understates the cost. A more precise estimate would require fetching order books here too,
which defeats the purpose of a lightweight pre-check.

> **Alternative considered:** Fetch order books inside the balance check to get the exact
> cost. Rejected because: (a) it doubles the order-book API calls, (b) the `maxOrderCost`
> cap already bounds worst-case exposure, and (c) the cost estimate is conservative
> (overestimates, never underestimates).

### 3. Refresh balances after order placement

**Modify:** `backend/src/main/java/hzpro/com/tradingdesk/arbitrage/service/ArbitrageOrderService.java`

After both venue orders have been placed (after line 110 in `createOrders()`), refresh
balances:

```java
// After orderRepository.save(ksOrder) on line 110:
if (pmResult.success() || ksResult.success()) {
    balanceService.refreshBalances();
}
```

**Design decision:** Refresh only if at least one order was placed successfully. If both
failed, the balances haven't changed and there's no point in an API call. Also, refresh
is fire-and-forget (we don't block the return on it) — but since this is already
synchronous code, a simple try-catch around the refresh call keeps it safe.

Inject `BalanceService` into `ArbitrageOrderService` (add constructor parameter; the
class already uses `@RequiredArgsConstructor` so Lombok will handle it).

### 4. Integration test

**New file:** `backend/src/test/java/hzpro/com/tradingdesk/arbitrage/service/ArbitrageMonitorServiceBalanceCheckTest.java`

Follow the existing test pattern from `ArbitrageMonitorServiceCheckArbitrageTest`:
- `@SpringBootTest` + `@ActiveProfiles("test")` (Testcontainers PostgreSQL)
- Mock `BalanceService` with `@MockitoBean` (or mock the underlying balance services)
- Seed a similar pair in the DB
- Mock order book services and trading services
- Use `ReflectionTestUtils` to invoke `checkArbitrage`

#### Test Case 1: Sufficient balance → trade proceeds

- Stub `balanceService.hasSufficientBalance(any(), any())` → `true`
- Seed prices that create a spread above threshold
- Seed order books so `createOrders` succeeds
- Mock trading services to return success
- Invoke `checkArbitrage`
- **Assert:** `OPPORTUNITY_START` event persisted, orders placed on both venues
- **Assert:** log contains message indicating balance check passed (use a `LogbackTestAppender` or check that trading services were called)

#### Test Case 2: Insufficient balance → trade skipped

- Stub `balanceService.hasSufficientBalance(any(), any())` → `false`
- Seed prices that create a spread above threshold
- Invoke `checkArbitrage`
- **Assert:** `OPPORTUNITY_START` event persisted (opportunity is recorded)
- **Assert:** No orders placed on either venue (`verifyNoInteractions` on trading services)
- **Assert:** Log contains "Insufficient balance" warning

#### Test Case 3: Balance refresh on start

- Stub the underlying `KalshiBalanceService.getBalance()` and `PolymarketBalanceService.getBalance()`
- Call `balanceService.refreshBalances()`
- **Assert:** cached balances match the stubbed values
- **Assert:** Log contains the refreshed balance values

#### Log verification approach

Use a custom `ListAppender` added to the relevant loggers in `@BeforeEach`:

```java
private ListAppender<ILoggingEvent> listAppender;

@BeforeEach
void setUp() {
    // ... existing setup ...
    listAppender = new ListAppender<>();
    listAppender.start();
    ((Logger) LoggerFactory.getLogger(BalanceService.class)).addAppender(listAppender);
}

@AfterEach
void tearDown() {
    ((Logger) LoggerFactory.getLogger(BalanceService.class)).detachAppender(listAppender);
    // ... existing teardown ...
}
```

Then assert on `listAppender.list` for expected log messages.

## Files Changed

| File | Change |
|------|--------|
| `arbitrage/service/BalanceService.java` | **New** — unified balance cache |
| `arbitrage/service/ArbitrageMonitorService.java` | Inject `BalanceService`, refresh on start, check before trade |
| `arbitrage/service/ArbitrageOrderService.java` | Inject `BalanceService`, refresh after order placement |
| `arbitrage/service/ArbitrageMonitorServiceBalanceCheckTest.java` | **New** — integration tests |

## Edge Cases & Error Handling

1. **Initial balance fetch fails (API down):** Balances stay at `BigDecimal.ZERO`. `hasSufficientBalance()` returns `false` for any positive trade cost → trading is effectively paused until the next successful refresh. Log a warning so operators know why.

2. **One venue balance fetch fails, the other succeeds:** The failed venue keeps its stale/zero value. This is conservative — if the failed venue is the one we need funds on, trading is blocked for that direction.

3. **Balance refresh after order placement fails:** Log a warning but don't affect the trade outcome. The next opportunity check will use the stale cached value.

4. **Race between balance refresh and check:** The `checkArbitrage` path runs under `lock`, so two concurrent checks can't race. The `volatile` fields ensure visibility of the latest refresh across threads.

5. **`maxOrderCost` is 0 (disabled):** The `ArbitrageConfig` postConstruct validator rejects 0 unless explicitly set that way. If disabled, `hasSufficientBalance(0, 0)` trivially returns true — no impact.

## Dependencies

- None external. Uses existing `KalshiBalanceService` and `PolymarketBalanceService`.
- Test dependency: `ch.qos.logback:logback-classic` (already available as a transitive dependency of Spring Boot).
