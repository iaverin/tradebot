# GB Trading Desk

A full-stack application for fetching and managing prediction market data from [Kalshi](https://kalshi.com) and [Polymarket](https://polymarket.com). Features scheduled and on-demand data fetching, a staging/persist workflow, ML-based cross-platform market similarity detection, and a Vue.js dashboard for monitoring and control.

## Features

- Automated prediction market data fetching (Kalshi + Polymarket)
- Temporal-based workflow scheduling with cron expressions
- Staging table workflow: fetch → review → persist
- Manual fetch triggers, pause/resume/stop controls
- ML-based similar market detection across platforms (sentence-transformers)
- JWT-based authentication
- Optional HTTPS proxy support for fetchers
- Temporal UI with basic auth protection

## Tech Stack

### Backend
- Java 23, Spring Boot 3.5, Gradle Kotlin DSL
- Spring Security (JWT, BCrypt)
- Spring Data JPA + PostgreSQL + Flyway
- [Temporal SDK 1.25.2](https://temporal.io) — workflow orchestration
- Apache HttpComponents 5 — HTTP client with proxy support
- Spring Retry — per-page retries with exponential backoff

### Similar Markets Service
- Python 3.13, FastAPI, SQLAlchemy 2 (async), asyncpg
- Granian (ASGI server), Alembic migrations
- sentence-transformers (`all-MiniLM-L6-v2`) + PyTorch CPU — cosine similarity matching

### Frontend
- Vue 3, Vite, Element Plus
- Pinia (state), Vue Router, Axios

### Infrastructure
- Docker Compose (Postgres, Temporal, Temporal UI, backend, frontend, similar-markets)
- Nginx — frontend serving + Temporal UI auth proxy

## Project Structure

```
gb-trading-desk/
├── backend/
│   ├── src/main/java/hzpro/com/tradingdesk/
│   │   ├── config/                  # Security, CORS, data loader
│   │   ├── controller/              # Auth, Dashboard, MarketsFetcher REST API
│   │   ├── entity/                  # User, PredictionMarket JPA entities
│   │   ├── repository/              # JPA repositories
│   │   ├── security/                # JWT filter
│   │   ├── service/                 # Auth, fetcher orchestration
│   │   └── marketsfetcher/
│   │       ├── lib/                 # BaseFetcher, FetcherState
│   │       ├── service/fetchers/    # KalshiFetcher, PolymarketFetcher
│   │       ├── temporal/
│   │       │   ├── activity/        # FetchActivity (clearFetchingData, fetchKalshi, fetchPolymarket, publishFetchedMarkets)
│   │       │   ├── config/          # TemporalConfig
│   │       │   ├── scheduler/       # TemporalSchedulerService
│   │       │   └── workflow/        # FetchWorkflow, MarketsRefreshWorkflow
│   │       ├── mapper/              # DTO → entity mappers
│   │       └── dto/                 # Kalshi + Polymarket response DTOs
│   └── src/main/resources/
│       ├── application.properties
│       └── db/migration/            # Flyway V1–V7
├── similar-markets/                 # Python FastAPI similarity service
│   ├── app/
│   │   ├── api/                     # POST /api/similar-markets/produce-similar-{events,markets}
│   │   ├── models.py                # PredictionMarket, SimilarEvent, SimilarMarket ORM models
│   │   ├── services.py              # FindSimilarEventsService, FindSimilarMarketsService
│   │   ├── repositories.py          # Async SQLAlchemy repos
│   │   └── settings.py              # Pydantic settings
│   ├── migrations/                  # Alembic migrations
│   └── pyproject.toml
├── frontend/
│   └── src/
│       ├── api/                     # Axios client, auth + fetcher API calls
│       ├── views/                   # Login, Dashboard, DataFetcher pages
│       ├── stores/                  # Pinia auth store
│       └── components/              # SidebarMenu
├── docker-compose.yml               # Main compose (includes temporal)
└── docker-compose.temporal.yml      # Temporal + UI services
```

## Getting Started

### Prerequisites

- Docker + Docker Compose v2.20+
- Java 23 (for local backend dev)
- Node.js 18+ (for local frontend dev)
- Python 3.13 + [uv](https://docs.astral.sh/uv/) (for local similar-markets dev)

### Running with Docker

1. Copy and configure environment:
   ```bash
   cp backend/.env.sample backend/.env
   # Edit backend/.env — set JWT_SECRET at minimum
   ```

2. Start all services:
   ```bash
   docker compose up --build
   ```

3. Access:
   - Frontend: http://localhost
   - Backend API: http://localhost:8080
   - Similar Markets API: http://localhost:8081
   - Temporal UI: http://localhost:8088 (login: `admin` / `admin`)

### Running Locally (without Docker)

Start Temporal and Postgres via Docker:
```bash
docker compose up postgres temporal temporal-ui-proxy temporal-ui
```

Backend:
```bash
cd backend
cp .env.sample .env   # configure as needed
./gradlew bootRun
```

Similar Markets service:
```bash
cd similar-markets
uv run alembic upgrade head
uv run python -m app
# http://localhost:8000
```

Frontend:
```bash
cd frontend
npm install
npm run dev           # http://localhost:5173
```

## Configuration

### Backend

All configurable values are set via environment variables. Copy `.env.sample` to `.env` in the `backend/` directory:

| Variable | Default | Description |
|---|---|---|
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/trading_desk` | PostgreSQL JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `postgres` | DB username |
| `SPRING_DATASOURCE_PASSWORD` | `postgres` | DB password |
| `JWT_SECRET` | — | **Required.** HS256 secret, min 256 bits |
| `JWT_EXPIRATION` | `86400000` | Token lifetime in ms (24h) |
| `SERVER_PORT` | `8080` | Backend HTTP port |
| `TEMPORAL_TARGET_ENDPOINT` | `localhost:7233` | Temporal gRPC address |
| `TEMPORAL_NAMESPACE` | `default` | Temporal namespace |
| `FETCHER_CRON_STRING_UTC` | `0 0 * * *` | Fetch schedule (5-field cron, UTC) |
| `PROXY_HOST` | — | Optional HTTP proxy host |
| `PROXY_PORT` | — | Optional HTTP proxy port |
| `PROXY_USERNAME` | — | Proxy basic auth username |
| `PROXY_PASSWORD` | — | Proxy basic auth password |
| `PROXY_USE_SSL` | `false` | Use HTTPS for proxy connection |

### Similar Markets Service

| Variable | Default | Description |
|---|---|---|
| `DB_DSN` | `postgresql+asyncpg://postgres:password@db/postgres` | Async PostgreSQL DSN |
| `SIMILARITY_THRESHOLD` | `0.8` | Cosine similarity cutoff (0–1) |
| `SERVICE_ENVIRONMENT` | `local` | Environment name |
| `SERVICE_DEBUG` | `false` | Enable debug mode |
| `APP_HOST` | `0.0.0.0` | Listen address |
| `APP_PORT` | `8000` | Listen port |

### Docker Compose Additional Variables

| Variable | Default | Description |
|---|---|---|
| `TEMPORAL_PORT` | `7233` | Temporal gRPC host port |
| `TEMPORAL_UI_PORT` | `8088` | Temporal UI host port |
| `TEMPORAL_UI_USER` | `admin` | Temporal UI basic auth username |
| `TEMPORAL_UI_PASSWORD` | `admin` | Temporal UI basic auth password |
| `TEMPORAL_DB` | `postgres12` | Temporal DB driver |

## API Endpoints

All backend endpoints are prefixed with `/api`.

### Backend — Public

| Method | Path | Description |
|---|---|---|
| `POST` | `/auth/login` | Returns JWT token |

### Backend — Protected (Bearer token required)

| Method | Path | Description |
|---|---|---|
| `GET` | `/markets-fetcher/status` | Fetcher running state |
| `GET` | `/markets-fetcher/next-cron-time` | Next scheduled run time |
| `POST` | `/markets-fetcher/fetch-kalshi` | Manually trigger Kalshi fetch |
| `POST` | `/markets-fetcher/fetch-polymarket` | Manually trigger Polymarket fetch |
| `POST` | `/markets-fetcher/pause` | Pause active fetch |
| `POST` | `/markets-fetcher/resume` | Resume paused fetch |
| `POST` | `/markets-fetcher/stop` | Force-stop active fetch |
| `POST` | `/markets-fetcher/clear-fetching-data` | Truncate staging table |
| `POST` | `/markets-fetcher/persist-fetching-data` | Copy staging → prediction_markets |

### Similar Markets Service

| Method | Path | Description |
|---|---|---|
| `POST` | `/api/similar-markets/produce-similar-events` | Match similar events across platforms and store results |
| `POST` | `/api/similar-markets/produce-similar-markets` | Match similar individual markets within similar event pairs |

## Data Fetching Architecture

### Scheduled refresh (MarketsRefreshWorkflow)

```
Temporal Schedule (cron: 0 0 * * *)
        │
        ▼
  MarketsRefreshWorkflow
        │
        ├── clearFetchingData()
        │
        ├─[parallel]─ FetchActivity::fetchKalshi  → KalshiFetcher  (cursor-based, 200/page)
        └─[parallel]─ FetchActivity::fetchPolymarket → PolymarketFetcher (offset-based, 20/page)
                                              │
                                              ▼
                               fetching_prediction_markets (staging)
                                              │
                                   publishFetchedMarkets()
                                              │
                                              ▼
                                    prediction_markets (production)
```

### Manual fetch (FetchWorkflow)

Used when triggering individual provider fetches via the REST API. Runs the activity for a single provider without clearing staging or auto-publishing.

Each page fetch is wrapped with `@Retryable` (3 attempts, exponential backoff up to 10s). Fetches can be paused or force-stopped between pages.

### Similar market detection (two-step)

```
Step 1 — POST /api/similar-markets/produce-similar-events
        │
        ▼
  FindSimilarEventsService
        │
        ├── Load distinct events from prediction_markets
        ├── Encode event titles via all-MiniLM-L6-v2
        ├── Compute cosine similarity matrix (Polymarket × Kalshi)
        ├── Filter pairs above SIMILARITY_THRESHOLD (default 0.8)
        └── Store matches → similar_events table

Step 2 — POST /api/similar-markets/produce-similar-markets
        │
        ▼
  FindSimilarMarketsService
        │
        ├── Load similar event pairs from similar_events
        ├── For each pair: load individual markets for both event tickers
        ├── Encode market titles via all-MiniLM-L6-v2
        ├── Compute cosine similarity matrix per event pair
        ├── Filter pairs above SIMILARITY_THRESHOLD
        └── Store matches → similar_markets table
```

## Database Migrations

### Backend (Flyway)

| Version | Description |
|---|---|
| V1 | Create `prediction_markets` table |
| V2 | Create `scheduled_tasks` table (legacy db-scheduler) |
| V3 | Create `users` table |
| V4 | Rename `prediction_markets` → `fetching_prediction_markets` (staging) |
| V5 | Drop `scheduled_tasks` (migrated to Temporal) |
| V6 | Change `market_result` column to TEXT in `fetching_prediction_markets` |
| V7 | Recreate `prediction_markets` table (production copy of staging schema) |

### Similar Markets Service (Alembic)

| Revision | Description |
|---|---|
| a1b2c3d4e5f6 | Create `similar_events` table |
| 8298ccf90586 | Create `prediction_markets` table (read model for similarity service) |

## Default Users

| Username | Password | Role |
|---|---|---|
| `admin` | `admin123` | ADMIN |
| `user` | `user123` | USER |

## Ports Summary

| Service | Port |
|---|---|
| Frontend (Nginx) | 80 |
| Backend (Spring Boot) | 8080 |
| Similar Markets (FastAPI) | 8081 |
| PostgreSQL | 5432 |
| Temporal gRPC | 7233 |
| Temporal UI | 8088 |
