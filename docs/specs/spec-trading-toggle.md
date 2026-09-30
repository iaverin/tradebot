# Spec: Trading On/Off Toggle (Issue #90)

## 1. Goal

Add a **trading on/off toggle** on the Settings page that controls the existing
`ArbitrageConfig.tradingEnabled` flag. The setting must be:

- **Persisted** in a new `settings` database table (key-value)
- **Read/write** via an authorized REST API
- **Loaded** on arbitrage monitor start and restart, falling back to env vars when
  no DB value exists
- **Verified** by an automated test with seeded DB data and mocked WebSocket / order
  book feeds

Work in the `backend/` and `frontend/` directories.

## 2. Current state

### 2.1 `tradingEnabled` flag

`ArbitrageConfig.java` (`backend/src/main/java/hzpro/com/tradingdesk/arbitrage/config/ArbitrageConfig.java`)
already declares:
```java
private boolean tradingEnabled = false;
```

This is mapped to `arbitrage.trading-enabled=${TRADING_ENABLED:false}` in
`application.properties`. The flag is consulted in `ArbitrageMonitorService.checkArbitrage()`
(line 334): when `true`, it checks balances and places orders; when `false`, opportunities
are only detected/logged, never acted on.

The field has a Lombok `@Setter`, so it is **mutable at runtime** — `setTradingEnabled(true|false)`
works on the singleton `ArbitrageConfig` bean. This is the mechanism the API endpoint
will use to toggle trading.

### 2.2 Settings page

The frontend sidebar (`frontend/src/components/SidebarMenu.vue`, lines 30-33, 46-49)
has two duplicate "Settings" entries pointing to `/settings`, but:
- **No `Settings.vue` view file exists**
- **No `/settings` route is registered** in the router (`frontend/src/router/index.js`)
- Clicking "Settings" hits the wildcard catch-all and redirects to `/dashboard`

### 2.3 No settings table in DB

All configuration lives in `.env` + `application.properties`. No `settings` table
or runtime-configuration infrastructure exists. The existing Flyway migrations go
up to V19.

## 3. Design

### 3.1 Database: `settings` table (new migration V20)

```sql
CREATE TABLE settings (
    key VARCHAR(255) NOT NULL,
    value TEXT NOT NULL,
    created_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,
    updated_at TIMESTAMP WITH TIME ZONE DEFAULT CURRENT_TIMESTAMP,

    CONSTRAINT pk_settings_key PRIMARY KEY (key)
);
```

- **key** — primary key and unique setting identifier (e.g., `"TRADING_ENABLED"`).
  No surrogate `id` column — `key` itself is the PK; the PK constraint provides
  the unique index.
- **value** — string-encoded value. The entity/service layer will parse to/from
  the target type (boolean for `TRADING_ENABLED`).
- **`updated_at` with timezone** (`TIMESTAMP WITH TIME ZONE`) as requested.

Migration file: `V20__create_settings_table.sql`.

### 3.2 Backend: new files

```
config/SettingsService.java          // CRUD operations on the settings table
entity/Setting.java                  // JPA entity for the settings table
repository/SettingsRepository.java   // JPA repository (key lookup)
controller/SettingsController.java   // REST endpoints: GET + PUT /api/settings/trading-enabled
controller/dto/TradingEnabledDto.java // { boolean tradingEnabled }
```

### 3.3 Backend: changes to existing files

- **`ArbitrageMonitorService.java`** — inject `SettingsService`; before
  `startMonitoring()`, call `settingsService.loadSettings()` which syncs the DB
  value → `ArbitrageConfig.tradingEnabled`. Also call it in `restart()`.
- **`ArbitrageConfig.java`** — no changes needed (already has `@Setter` and default).

### 3.4 Frontend: new files

```
src/views/Settings.vue               // Settings page with trading toggle
```

### 3.5 Frontend: changes to existing files

- **`src/router/index.js`** — add `/settings` route (requires auth)
- **`src/components/SidebarMenu.vue`** — remove the duplicate Settings entry (keep one)
- **`src/api/settings.js`** — API module for settings endpoints

## 4. `SettingsService`

```java
@Service
public class SettingsService {

    public static final String TRADING_ENABLED_KEY = "TRADING_ENABLED";

    private final SettingsRepository repo;
    private final ArbitrageConfig arbitrageConfig;  // for direct setter calls

    // Inject via constructor
    // ...

    /**
     * Called at monitor start/restart. For each known key:
     *   - if a DB row exists, use its value
     *   - otherwise use the env-var default (already on ArbitrageConfig)
     *   - if the DB row exists but the env default is already correct,
     *     no change needed
     */
    public void loadSettings() {
        repo.findByKey(TRADING_ENABLED_KEY).ifPresentOrElse(
            s -> arbitrageConfig.setTradingEnabled(Boolean.parseBoolean(s.getValue())),
            () -> { /* keep env-var default already on the bean */ }
        );
    }

    public boolean getTradingEnabled() {
        return repo.findByKey(TRADING_ENABLED_KEY)
            .map(s -> Boolean.parseBoolean(s.getValue()))
            .orElseGet(arbitrageConfig::isTradingEnabled);
    }

    @Transactional
    public boolean setTradingEnabled(boolean enabled) {
        Setting s = repo.findByKey(TRADING_ENABLED_KEY)
            .orElseGet(() -> { Setting ns = new Setting(); ns.setKey(TRADING_ENABLED_KEY); return ns; });
        s.setValue(String.valueOf(enabled));
        repo.save(s);
        arbitrageConfig.setTradingEnabled(enabled);
        return enabled;
    }
}
```

Key design decisions:
- `loadSettings()` is called during monitor start/restart, syncing DB state into
  the in-memory config bean.
- `setTradingEnabled()` writes to DB **and** updates the bean, so the change takes
  effect immediately (the next `checkArbitrage()` call will see it) without
  requiring a restart.
- The constant `TRADING_ENABLED_KEY = "TRADING_ENABLED"` is the initial setting key.
  The infrastructure is extensible to other keys in the future.

## 5. REST API

### `SettingsController` — `@RestController` + `@RequestMapping("/api/settings")`

Follows the existing controller pattern (`@RequiredArgsConstructor`, constructor
injection, `ResponseEntity<T>` returns). All endpoints require JWT auth (via the
global `.anyRequest().authenticated()` rule in `SecurityConfig`).

| Method | Path | Auth | Request | Response |
|--------|------|------|---------|----------|
| `GET` | `/api/settings/trading-enabled` | JWT | — | `200 { "tradingEnabled": true|false }` |
| `PUT` | `/api/settings/trading-enabled` | JWT (ADMIN only) | `{ "tradingEnabled": true|false }` | `200 { "tradingEnabled": true|false }` |

- `PUT` should be **admin-only**. Add `@PreAuthorize("hasAuthority('ADMIN')")` on
  the PUT method (following the role-based `SimpleGrantedAuthority` pattern from
  `CustomUserDetailsService` — roles are plain strings with no `ROLE_` prefix).
- `GET` is available to any authenticated user.
- DTO: `TradingEnabledDto` — a simple record or class with `boolean tradingEnabled`.

### `TradingEnabledDto`

```java
// In controller/dto/TradingEnabledDto.java
public record TradingEnabledDto(boolean tradingEnabled) {}
```

## 6. `Setting` entity

```java
@Entity
@Table(name = "settings")
@Getter @Setter @NoArgsConstructor
public class Setting {
    @Id
    @Column(name = "key", nullable = false, unique = true, length = 255)
    private String key;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String value;

    @CreationTimestamp
    @Column(updatable = false)
    private ZonedDateTime createdAt;

    @UpdateTimestamp
    private ZonedDateTime updatedAt;
}
```

Follows the existing entity pattern (see `PredictionMarket.java`, `User.java`):
`@Getter @Setter @NoArgsConstructor`, `@CreationTimestamp`, `@UpdateTimestamp`,
`ZonedDateTime` for timestamps. Note: `key` is the `@Id` — no surrogate PK.

### `SettingsRepository`

```java
@Repository
public interface SettingsRepository extends JpaRepository<Setting, String> {
    Optional<Setting> findByKey(String key);
}
```

## 7. `ArbitrageMonitorService` integration

Two injection points in the monitor lifecycle:

### `start()` method (line 96)
```java
@EventListener(ApplicationReadyEvent.class)
public void start() {
    settingsService.loadSettings();  // <-- ADD: load DB settings before starting
    loadActiveOpportunitiesFromDb();
    startMonitoring();
}
```

### `restart()` method (line 478)
```java
public void restart() {
    stop();
    settingsService.loadSettings();  // <-- ADD: re-load on restart
    startMonitoring();
}
```

No changes to `checkArbitrage()` — it already reads `config.isTradingEnabled()`
at line 334; the `setTradingEnabled()` call in `SettingsService` updates the same
bean instance, so the new value is picked up on the next opportunity check.

## 8. Frontend: `Settings.vue`

Create `frontend/src/views/Settings.vue`:

- Displays a card/panel with a **switch/toggle** (Element Plus `el-switch`) for
  "Trading Enabled"
- On page mount, `GET /api/settings/trading-enabled` to load current state
- On toggle change, `PUT /api/settings/trading-enabled` to persist
- Show appropriate loading/success/error states
- Style consistent with the existing dashboard pages (use the same card layout
  pattern from `Arbitrage.vue` or `Dashboard.vue`)

Follows existing frontend patterns:
- `<script setup>` with Composition API
- Element Plus components
- API calls via dedicated `src/api/settings.js` module
- JWT token attached automatically by the axios interceptor

### Router addition (`src/router/index.js`)
```js
{
  path: '/settings',
  name: 'Settings',
  component: () => import('../views/Settings.vue'),
  meta: { requiresAuth: true }
}
```

### Sidebar fix (`src/components/SidebarMenu.vue`)
- Remove the duplicate Settings menu item (keep one, e.g., at index 5).
- The existing `route="/settings"` attribute on the menu item will navigate
  correctly once the route is registered.

## 9. Test

Create `ArbitrageMonitorServiceTradingToggleTest.java` in
`backend/src/test/java/hzpro/com/tradingdesk/arbitrage/service/`.

### Test approach

Use the existing test pattern from `ArbitrageMonitorServiceCheckArbitrageTest`:
- `@SpringBootTest` + `@ActiveProfiles("test")` with Testcontainers PostgreSQL
- `@MockitoBean` on `PolymarketFetchCommonOrderBookService`,
  `KalshiFetchCommonOrderBookService`, `PolymarketTradingService`,
  `KalshiTradingService`, `BalanceService`
- `@Autowired` on `SettingsService`, `SettingsRepository`, `ArbitrageConfig`

### Seeded data

In `@BeforeEach`, seed the `settings` table via `SettingsRepository.save()`:
```java
Setting s = new Setting();
s.setKey(SettingsService.TRADING_ENABLED_KEY);
s.setValue("false");  // or "true" depending on test case
settingsRepository.save(s);
```

### Mocked WebSocket / order book data

Use the same approach as existing tests:
- Call `checkArbitrage(pairId)` via reflection (`ReflectionTestUtils.invokeMethod()`)
- Mock `PairPriceCache` to return synthetic `PriceSnapshot` data that creates a spread
  above threshold
- Mock `BalanceService.hasSufficientBalance()` to return `true`

### Test cases

1. **`tradingEnabled=true` → orders are placed**
   - Seed DB with `TRADING_ENABLED = true`
   - Call `settingsService.loadSettings()`
   - Inject price data that triggers an arbitrage opportunity
   - Assert that `arbitrageOrderService.createOrders()` was called (verify on
     the mocked `ArbitrageOrderService`)

2. **`tradingEnabled=false` → orders are NOT placed**
   - Seed DB with `TRADING_ENABLED = false`
   - Call `settingsService.loadSettings()`
   - Inject price data that triggers an arbitrage opportunity
   - Assert that `arbitrageOrderService.createOrders()` was **never** called

3. **No DB row → fallback to `ArbitrageConfig` default**
   - Don't seed any row
   - Set `arbitrageConfig.setTradingEnabled(false)` (mimics env default)
   - Call `settingsService.loadSettings()`
   - Verify the config value is unchanged (`false`)

4. **DB row overrides env default**
   - Seed DB with `TRADING_ENABLED = true`
   - Set `arbitrageConfig.setTradingEnabled(false)` (mimics env default)
   - Call `settingsService.loadSettings()`
   - Assert `arbitrageConfig.isTradingEnabled()` returns `true` (DB wins)

5. **Runtime toggle via `setTradingEnabled()`**
   - Seed DB with `TRADING_ENABLED = false`
   - Call `settingsService.loadSettings()`
   - Then call `settingsService.setTradingEnabled(true)`
   - Assert `arbitrageConfig.isTradingEnabled()` returns `true`
   - Assert the DB row was updated

## 10. Configuration

No new `application.properties` entries are needed. The existing
`arbitrage.trading-enabled=${TRADING_ENABLED:false}` serves as the env-var
fallback documented in the `.env.sample` / `.env` files.

## 11. Files summary

### New files (backend)
```
src/main/java/hzpro/com/tradingdesk/entity/Setting.java
src/main/java/hzpro/com/tradingdesk/repository/SettingsRepository.java
src/main/java/hzpro/com/tradingdesk/config/SettingsService.java
src/main/java/hzpro/com/tradingdesk/controller/SettingsController.java
src/main/java/hzpro/com/tradingdesk/controller/dto/TradingEnabledDto.java
src/main/resources/db/migration/V20__create_settings_table.sql
src/test/java/hzpro/com/tradingdesk/arbitrage/service/ArbitrageMonitorServiceTradingToggleTest.java
```

### Changed files (backend)
```
src/main/java/hzpro/com/tradingdesk/arbitrage/service/ArbitrageMonitorService.java
  - Inject SettingsService
  - Call settingsService.loadSettings() in start() and restart()
```

### New files (frontend)
```
src/views/Settings.vue
src/api/settings.js
```

### Changed files (frontend)
```
src/router/index.js         - Add /settings route
src/components/SidebarMenu.vue  - Remove duplicate Settings entry
```

## 12. Acceptance criteria

1. `./gradlew build` passes (compile + all tests, including the new trading toggle test).
2. `GET /api/settings/trading-enabled` returns the current state (requires valid JWT).
3. `PUT /api/settings/trading-enabled` updates the setting in both DB and the live
   `ArbitrageConfig` bean, taking effect on the next `checkArbitrage()` call without
   a monitor restart. Requires ADMIN role; returns 403 for USER role.
4. `POST /api/arbitrage/restart` reloads the trading setting from DB before restarting.
5. When `TRADING_ENABLED` row exists in DB, it overrides the `TRADING_ENABLED` env var.
6. When no row exists, the env var default is used as-is.
7. The `settings` table uses `key` as the primary key (unique index, no surrogate `id`)
   and has a `TIMESTAMP WITH TIME ZONE` `updated_at` column.
8. The Settings page in the frontend shows a toggle that loads the current state on
   mount and persists changes via the API.
9. The duplicate Settings entry in the sidebar is removed.
10. The automated test verifies all five test cases from §9 using seeded DB data and
    mocked price WebSocket / order book feeds.
