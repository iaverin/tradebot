# AGENTS.md — GB Trading Desk

Three-service monorepo: Java/Spring Boot backend (Temporal workflows), Python/FastAPI similarity service, Vue 3 frontend.

## Quick commands

### Backend (Java 23, Spring Boot 3.5)
```bash
cd backend
./gradlew bootRun            # dev server on :8080
./gradlew build -x test      # fast build (skip tests)
./gradlew test               # all unit tests
./gradlew manualTest         # tests tagged @Tag("manual")
```
- Build output goes to `.build/` (not default `build/`).
- Env from `backend/.env` (copy of `.env.sample`).
- Requires **PostgreSQL** + **Temporal** running (use `docker compose up postgres temporal temporal-ui-proxy temporal-ui`).
- Tests use **Testcontainers** (PostgreSQL) — Docker required.
- Flyway migrations run on startup, `ddl-auto=validate`.
- `exclude` Tags("manual") on the default test task.

### Similar Markets (Python 3.13, FastAPI)
```bash
cd similar-markets
uv run alembic upgrade head   # run migrations
uv run python -m app          # Granian ASGI server on :8000
uv run pytest                 # tests with coverage
uv run ruff check .           # lint (ALL rules, aggressive config)
```
- Uses `uv` (Astral), not pip/poetry. PyTorch CPU via `pytorch-cpu` index in pyproject.toml.
- `all-MiniLM-L6-v2` model downloaded at Docker build time to `/code/model_cache`.
- Settings from env vars (see `app/settings.py`).

### Frontend (Vue 3, Vite, Element Plus)
```bash
cd frontend
npm run dev      # Vite dev on :5173
npm run build    # production build
```
- No tests or lint configured.

### Docker (full stack)
```bash
docker compose up --build     # all services
docker compose up backend     # backend only (manual profile)
```
- Frontend on :80, Backend on :8080, Similar Markets on :8081, Temporal UI on :8088 (admin/admin).

## Architecture notes

- **Backend entrypoint**: `hzpro.com.tradingdesk.AuthApplication` (excludes `HttpClientAutoConfiguration` + `RestClientAutoConfiguration`)
- **Cron**: 5-field standard format (NOT Quartz 6-field), default `0 0 * * *` UTC
- **Auth**: JWT via `jjwt` 0.12.6, stored in localStorage key `token` (not `token_9607`)
- **Similar Markets entrypoint**: `app.__main__` → `app.application:build_app` (DI via `modern-di` + `lite-bootstrap`)
- **Services share the same PostgreSQL database** (`trading_desk`). Similar Markets reads `prediction_markets` table directly.
- **Proxy support**: Optional HTTP proxy for fetchers via env vars `PROXY_HOST`/`PROXY_PORT`/`PROXY_USERNAME`/`PROXY_PASSWORD`/`PROXY_USE_SSL`
- **DTOs over generics**: Controller and repository methods must return typed DTO records, never `Object[]`, `Map<String, Object>`, or other generic containers. Define a `record` in `controller/dto/` for every response shape.

## Testing quirks

- Backend: Tests with `@Tag("manual")` excluded from default `./gradlew test`; run via `./gradlew manualTest`
- Similar Markets: `pytest.ini_options` sets `asyncio_mode = auto`, coverage on by default
- No CI workflows found in repo
- **Authenticated controller tests**: Use `AuthenticatedClient` (`src/test/java/.../testutil/AuthenticatedClient.java`) for any controller integration test that needs JWT auth. It creates a random user, logs in, and wraps `MockMvc` with automatic `Authorization` header injection — no manual login or token management in tests.