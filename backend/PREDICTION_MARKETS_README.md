# Prediction Markets Data Fetchers

This module implements data fetchers for Kalshi and Polymarket prediction markets, with automatic scheduling via db-scheduler and data persistence to PostgreSQL.

## Architecture

The implementation follows the Template Method pattern with a clean separation of concerns:

### Core Components

1. **BaseFetcher** - Abstract base class with retry logic (3 attempts, exponential backoff)
2. **FetcherState** - Immutable state tracking for pagination
3. **KalshiFetcher** - Cursor-based pagination fetcher for Kalshi API
4. **PolymarketFetcher** - Offset-based pagination fetcher for Polymarket API
5. **PredictionMarket** - JPA entity with proper indexes
6. **Mappers** - Platform-specific DTOs to entity conversion

## Database Schema

Table: `prediction_markets`

Key fields:
- `datasource` (POLYMARKET | KALSHI) - indexed
- `event_id` - indexed
- `market_open_datetime` - indexed (TIMESTAMP WITH TIME ZONE)
- `market_close_datetime` - indexed (TIMESTAMP WITH TIME ZONE)
- `market_raw_data` - stores full JSON response

## API Endpoints

### Kalshi

**Base URL:** `https://api.elections.kalshi.com/trade-api/v2/events`

**Pagination:** Cursor-based
- Limit: 200 records per request
- Query param: `with_nested_markets=true`

### Polymarket

**Base URL:** `https://gamma-api.polymarket.com/events`

**Pagination:** Offset-based
- Limit: 20 records per request
- Increments offset by 20 each request

## Scheduling

Both fetchers run automatically every hour via db-scheduler:

- **Kalshi Task:** `kalshi-fetch-task` - runs hourly
- **Polymarket Task:** `polymarket-fetch-task` - runs hourly

Configuration in `application.properties`:
```properties
db-scheduler.enabled=true
db-scheduler.polling-interval=10s
db-scheduler.threads=10
```

## Manual Triggering

### Via REST API

```bash
# Fetch Kalshi data
curl -X POST http://localhost:8080/api/prediction-markets/fetch/kalshi

# Fetch Polymarket data
curl -X POST http://localhost:8080/api/prediction-markets/fetch/polymarket
```

### Via Service

```java
@Autowired
private PredictionMarketService service;

// Trigger Kalshi fetch
service.fetchKalshiData();

// Trigger Polymarket fetch
service.fetchPolymarketData();
```

## Query APIs

```bash
# Get all markets
GET /api/prediction-markets

# Get markets by data source
GET /api/prediction-markets/datasource/KALSHI
GET /api/prediction-markets/datasource/POLYMARKET

# Get markets by event ID
GET /api/prediction-markets/event/{eventId}

# Get markets opening between dates
GET /api/prediction-markets/opening?start=2026-03-01T00:00:00Z&end=2026-03-31T23:59:59Z

# Get markets closing between dates
GET /api/prediction-markets/closing?start=2026-03-01T00:00:00Z&end=2026-03-31T23:59:59Z

# Get markets by data source and status
GET /api/prediction-markets/datasource/KALSHI/status/active
```

## Database Setup

### Prerequisites

1. PostgreSQL database running
2. Update credentials in `application.properties`:

```properties
spring.datasource.url=jdbc:postgresql://localhost:5432/trading_desk
spring.datasource.username=postgres
spring.datasource.password=postgres
```

### Migration

The schema is automatically created via Flyway migration on application startup:
- Migration file: `src/main/resources/db/migration/V1__create_prediction_markets.sql`

## Proxy Support

The fetchers support HTTPS proxy with username/password authentication. See [PROXY_CONFIGURATION.md](PROXY_CONFIGURATION.md) for detailed setup.

**Quick setup:**

```bash
# Copy sample env file
cp .env.sample .env

# Edit .env and set proxy settings
PROXY_HOST=proxy.example.com
PROXY_PORT=8080
PROXY_USERNAME=your_username
PROXY_PASSWORD=your_password
```

## Dependencies

Added to `build.gradle.kts`:

```kotlin
// PostgreSQL
runtimeOnly("org.postgresql:postgresql")

// db-scheduler
implementation("com.github.kagkarlsson:db-scheduler:14.0.3")
implementation("com.github.kagkarlsson:db-scheduler-spring-boot-starter:14.0.3")

// Spring Retry
implementation("org.springframework.retry:spring-retry")
implementation("org.springframework:spring-aspects")

// WebClient
implementation("org.springframework.boot:spring-boot-starter-webflux")

// Dotenv for .env file support
implementation("me.paulschwarz:spring-dotenv:4.0.0")

// Flyway
implementation("org.flywaydb:flyway-core")
implementation("org.flywaydb:flyway-database-postgresql")
```

## Error Handling

- **Retry Logic:** 3 attempts with exponential backoff (4s, 10s max)
- **Validation Errors:** Logged and skipped, processing continues
- **Network Errors:** Retried automatically
- **Parsing Errors:** Logged, empty list returned

## Logging

All fetchers use SLF4J logging:
- Info: Start/completion of fetch cycles
- Error: Failed requests, parsing errors, validation failures

## Package Structure

```
com.example.auth.predictionmarkets/
├── config/
│   ├── RetryConfig.java
│   └── WebClientConfig.java
├── controller/
│   └── PredictionMarketController.java
├── dto/
│   ├── kalshi/
│   │   ├── KalshiEventDto.java
│   │   ├── KalshiMarketDto.java
│   │   └── KalshiResponseDto.java
│   └── polymarket/
│       ├── PolymarketEventDto.java
│       └── PolymarketMarketDto.java
├── entity/
│   ├── DataSource.java
│   └── PredictionMarket.java
├── fetcher/
│   ├── BaseFetcher.java
│   ├── FetcherState.java
│   ├── KalshiFetcher.java
│   └── PolymarketFetcher.java
├── mapper/
│   ├── KalshiMapper.java
│   └── PolymarketMapper.java
├── repository/
│   └── PredictionMarketRepository.java
├── scheduler/
│   └── PredictionMarketScheduler.java
└── service/
    └── PredictionMarketService.java
```

## Testing

To test the implementation:

1. Start PostgreSQL database
2. Update database credentials if needed
3. Run the application
4. Manually trigger a fetch:
   ```bash
   curl -X POST http://localhost:8080/api/prediction-markets/fetch/kalshi
   ```
5. Query the results:
   ```bash
   curl http://localhost:8080/api/prediction-markets/datasource/KALSHI
   ```

## Future Enhancements

- Add metrics and monitoring
- Implement incremental updates (only fetch new/updated markets)
- Add data deduplication logic
- Implement market result validation
- Add more granular error handling and recovery
