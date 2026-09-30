# Quick Start Guide - Prediction Markets Fetchers

This guide will help you get the prediction markets data fetchers up and running in 5 minutes.

## Prerequisites

- Java 23
- PostgreSQL 12+
- Gradle (included via wrapper)

## Step 1: Database Setup

Create the PostgreSQL database:

```bash
# Using psql
createdb trading_desk

# Or via psql command
psql -U postgres -c "CREATE DATABASE trading_desk;"
```

## Step 2: Environment Configuration

Copy the sample environment file:

```bash
cp .env.sample .env
```

Edit `.env` with your settings:

```bash
# Minimum required settings
SPRING_DATASOURCE_URL=jdbc:postgresql://localhost:5432/trading_desk
SPRING_DATASOURCE_USERNAME=postgres
SPRING_DATASOURCE_PASSWORD=your_postgres_password
```

**Optional - Configure Proxy:**

If you need to use a proxy, uncomment and set in `.env`:

```bash
PROXY_HOST=proxy.example.com
PROXY_PORT=8080
PROXY_USERNAME=your_username
PROXY_PASSWORD=your_password
```

## Step 3: Build the Application

```bash
./gradlew clean build
```

## Step 4: Run the Application

```bash
./gradlew bootRun
```

The application will:
1. Start on port 8080 (configurable via `SERVER_PORT` in `.env`)
2. Run Flyway migrations to create database tables
3. Initialize db-scheduler
4. Start scheduled tasks (Kalshi and Polymarket fetchers run hourly)

## Step 5: Verify It's Working

### Check Application Started

Look for this in the logs:

```
Started AuthApplication in X.XXX seconds
```

### Manually Trigger a Fetch

Test Kalshi data fetch:

```bash
curl -X POST http://localhost:8080/api/prediction-markets/fetch/kalshi
```

Expected response:
```json
{
  "status": "success",
  "message": "Kalshi fetch initiated"
}
```

Test Polymarket data fetch:

```bash
curl -X POST http://localhost:8080/api/prediction-markets/fetch/polymarket
```

### Query the Data

Get all Kalshi markets:

```bash
curl http://localhost:8080/api/prediction-markets/datasource/KALSHI | jq .
```

Get all Polymarket markets:

```bash
curl http://localhost:8080/api/prediction-markets/datasource/POLYMARKET | jq .
```

## Step 6: Monitor Scheduled Tasks

The fetchers run automatically every hour. Check logs for:

```
INFO  c.e.a.p.scheduler.PredictionMarketScheduler - Starting Kalshi fetch task
INFO  c.e.a.p.fetcher.KalshiFetcher - Starting fetch from https://api.elections.kalshi.com/trade-api/v2/events
INFO  c.e.a.p.fetcher.BaseFetcher - Fetch completed. Retrieved 200 markets. Has more data: true
INFO  c.e.a.p.scheduler.PredictionMarketScheduler - Kalshi fetch task completed successfully
```

## Common Issues

### Database Connection Failed

**Error:**
```
org.postgresql.util.PSQLException: Connection refused
```

**Solution:**
- Ensure PostgreSQL is running: `sudo systemctl status postgresql`
- Check database credentials in `.env`
- Verify database exists: `psql -l | grep trading_desk`

### Port Already in Use

**Error:**
```
Web server failed to start. Port 8080 was already in use.
```

**Solution:**
- Change port in `.env`: `SERVER_PORT=8081`
- Or stop the process using port 8080

### Proxy Authentication Failed

**Error:**
```
Connection prematurely closed BEFORE response
```

**Solution:**
- Verify proxy credentials in `.env`
- Test proxy manually: `curl -x http://user:pass@proxy.example.com:8080 https://google.com`
- Check proxy logs for authentication errors

### No Data Retrieved

**Issue:** Fetches complete but no markets in database

**Solution:**
- Check API responses in logs (set `logging.level.com.example.auth.predictionmarkets=DEBUG`)
- Verify network connectivity to Kalshi/Polymarket APIs
- Check if proxy is blocking the API endpoints

## API Endpoints Reference

| Method | Endpoint | Description |
|--------|----------|-------------|
| POST | `/api/prediction-markets/fetch/kalshi` | Manually trigger Kalshi fetch |
| POST | `/api/prediction-markets/fetch/polymarket` | Manually trigger Polymarket fetch |
| GET | `/api/prediction-markets` | Get all markets |
| GET | `/api/prediction-markets/datasource/{KALSHI\|POLYMARKET}` | Get markets by source |
| GET | `/api/prediction-markets/event/{eventId}` | Get markets by event ID |
| GET | `/api/prediction-markets/opening?start={date}&end={date}` | Markets opening in date range |
| GET | `/api/prediction-markets/closing?start={date}&end={date}` | Markets closing in date range |

## Next Steps

- **Configure Scheduling**: Edit `PredictionMarketScheduler.java` to change fetch intervals
- **Add Monitoring**: Set up metrics/alerts for failed fetches
- **Production Deployment**: See [PROXY_CONFIGURATION.md](PROXY_CONFIGURATION.md) for Docker/Kubernetes setup
- **Customize Queries**: Add more repository methods in `PredictionMarketRepository.java`

## Development Tips

### Enable Debug Logging

Add to `application.properties`:

```properties
logging.level.com.example.auth.predictionmarkets=DEBUG
logging.level.org.springframework.web.reactive.function.client=DEBUG
```

### Test Without Scheduler

Disable automatic scheduling in `application.properties`:

```properties
db-scheduler.enabled=false
```

Then use manual trigger endpoints for testing.

### Database Inspection

View fetched data:

```bash
psql -U postgres trading_desk
```

```sql
-- Count markets by source
SELECT datasource, COUNT(*) FROM prediction_markets GROUP BY datasource;

-- Recent markets
SELECT event_title, market_title, market_open_datetime
FROM prediction_markets
ORDER BY created_at DESC
LIMIT 10;

-- Markets closing soon
SELECT event_title, market_title, market_close_datetime
FROM prediction_markets
WHERE market_close_datetime > NOW()
ORDER BY market_close_datetime
LIMIT 10;
```

## Getting Help

- **Configuration Issues**: See [PROXY_CONFIGURATION.md](PROXY_CONFIGURATION.md)
- **API Documentation**: See [PREDICTION_MARKETS_README.md](PREDICTION_MARKETS_README.md)
- **Implementation Details**: See [.claude/IMPLEMENTATION_SUMMARY.md](.claude/IMPLEMENTATION_SUMMARY.md)

## Production Checklist

Before deploying to production:

- [ ] Change `JWT_SECRET` to a strong random value
- [ ] Update database credentials
- [ ] Configure proxy if needed
- [ ] Set up monitoring and alerting
- [ ] Enable HTTPS (reverse proxy/load balancer)
- [ ] Review and adjust scheduler intervals
- [ ] Set up log aggregation
- [ ] Configure backups for PostgreSQL
- [ ] Test failover scenarios
- [ ] Document your deployment procedure
