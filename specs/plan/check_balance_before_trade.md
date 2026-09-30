# Implementation Plan: Check Balance Before Trade

## Summary

Add pre-trade balance validation to the arbitrage monitor. Before placing orders for a
detected arbitrage opportunity, verify that both Kalshi and Polymarket account balances
exceed the trade cost. Balances are cached in-memory and refreshed on monitor start, on
reset, and after every successful order placement — no per-check API calls.

## Files Changed

| # | File | Change |
|---|------|--------|
| 1 | `arbitrage/service/BalanceService.java` | **New** — unified balance cache |
| 2 | `arbitrage/service/ArbitrageMonitorService.java` | Inject `BalanceService`, refresh on start, check before trade, `calculateTradeCost()` helper |
| 3 | `arbitrage/service/ArbitrageOrderService.java` | Inject `BalanceService`, refresh after order placement |
| 4 | `arbitrage/service/ArbitrageMonitorServiceBalanceCheckTest.java` | **New** — integration tests |

## Step 1: Create `BalanceService`

- [x] **1.1** Create `BalanceService` in `arbitrage/service/` (alongside other arbitrage services, not in `service/account/`)
- [x] **1.2** Inject `KalshiBalanceService` and `PolymarketBalanceService`
- [x] **1.3** `volatile BigDecimal kalshiBalance` and `volatile BigDecimal polymarketBalance` fields (default `BigDecimal.ZERO`)
- [x] **1.4** `refreshBalances()` — fetch from both venues, catch exceptions per-venue, log refreshed values
- [x] **1.5** `hasSufficientBalance(BigDecimal requiredKalshi, BigDecimal requiredPolymarket)` — compare cached balances, log result

## Step 2: Integrate into `ArbitrageMonitorService`

- [x] **2.1** Add `BalanceService` as constructor parameter
- [x] **2.2** In `startMonitoring()`, call `balanceService.refreshBalances()` after REST prefetch block (after line 196 catch) and before setting `running = true` (before line 207)
- [x] **2.3** Add `calculateTradeCost(PriceSnapshot pm, PriceSnapshot ks, ArbitrageDirection dir)` private helper — returns `config.getMaxOrderCost()` as a conservative estimate
- [x] **2.4** In `checkArbitrage()`, wrap the trading block (lines 325-329) with a `hasSufficientBalance()` check; log warning if insufficient

## Step 3: Refresh balances after order placement

- [x] **3.1** Add `BalanceService` as constructor parameter in `ArbitrageOrderService` (class uses `@RequiredArgsConstructor`, Lombok handles it automatically)
- [x] **3.2** After `orderRepository.save(ksOrder)` on line 110, add balance refresh if at least one order succeeded

## Step 4: Integration tests

- [x] **4.1** Create `ArbitrageMonitorServiceBalanceCheckTest` following `ArbitrageMonitorServiceCheckArbitrageTest` pattern
- [x] **4.2** Test: Sufficient balance → trade proceeds (mock `BalanceService.hasSufficientBalance` → true, verify orders placed)
- [x] **4.3** Test: Insufficient balance → trade skipped (mock `BalanceService.hasSufficientBalance` → false, verify no trading service interactions)
- [x] **4.4** Test: Balance refresh on start (verify `refreshBalances()` is called during `startMonitoring()`)

## Design Decisions

- `volatile` fields sufficient for single-writer (monitor thread) / multi-reader pattern
- Balance refresh is best-effort: per-venue failure keeps stale cached value
- Zero default for uninitialized balances — blocks trading until first successful refresh (conservative)
- `maxOrderCost` as trade cost estimate — conservative, fast, no extra API calls
- Balance check inside `lock` — consistent with in-memory opportunity state
- Refresh after order only if at least one venue succeeded — avoids pointless API calls
