# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project Overview

This is the frontend portion of "Got Balls Trading Desk" - a full-stack SPA application with JWT-based authentication. The frontend is built with Vue 3, Element Plus UI framework, and Vite.

## Development Commands

### Running the Application

```bash
npm run dev          # Start dev server on http://localhost:5173
npm run build        # Build for production
npm run preview      # Preview production build
```

### Backend Integration

The backend is a Spring Boot application located in `../backend/`:

```bash
cd ../backend
./gradlew bootRun    # Start backend on http://localhost:8080
./gradlew build      # Build backend
```

Or use Docker from project root:
```bash
cd ..
docker-compose up --build    # Runs both frontend and backend
```

## Architecture

### Authentication Flow

1. **Login Process** (`src/views/Login.vue`):
   - Form validation handled by Element Plus
   - Credentials sent to `POST /api/auth/login`
   - On success, JWT token and username stored via Pinia auth store
   - User redirected to `/dashboard`

2. **Auth Store** (`src/stores/auth.js`):
   - Manages authentication state with Pinia
   - Persists token and username to localStorage with keys `'token'` and `'username'`
   - Provides `setAuth()`, `clearAuth()`, and `isAuthenticated()` methods

3. **Axios Interceptor** (`src/api/axios.js`):
   - Automatically attaches JWT token to all API requests
   - Token retrieved from localStorage key `'token'` (NOT `'token_9607'`)
   - Sets `Authorization: Bearer ${token}` header
   - Base URL: `VITE_API_URL` env var or `/api` default

4. **Route Guards** (`src/router/index.js`):
   - Protected routes have `meta: { requiresAuth: true }`
   - Guest-only routes (login) have `meta: { requiresGuest: true }`
   - Unauthenticated users redirected to `/login`
   - Authenticated users trying to access `/login` redirected to `/dashboard`

### State Management

- **Pinia stores** in `src/stores/`:
  - `auth.js` - Authentication state (token, username)
  - State persisted to localStorage

### API Layer

- **Axios instance** (`src/api/axios.js`):
  - Centralized HTTP client configuration
  - Request interceptor adds auth token

- **API modules** (`src/api/`):
  - `auth.js` - Authentication endpoints
    - `login(username, password)` - POST to `/auth/login`
    - `getHelloWorld()` - GET to `/dashboard/hello` (protected)
  - `requests.js` - Venue API proxy
    - `proxyRequest(venue, method, path, body)` - POST to `/api/requests/proxy` (supports GET/POST/DELETE proxying to Polymarket, Kalshi, Polymarket Data API)
    - `getRequestsConfig()` - GET to `/api/requests/config` (returns wallet address)

### UI Components

- Uses **Element Plus** component library
- Main layout in Dashboard has:
  - Left sidebar (`el-aside`) with navigation menu
  - Top header (`el-header`) with user dropdown
  - Main content area (`el-main`)

### Pages

- **Requests** (`src/views/Requests.vue`):
  - Venue selector: Kalshi / Polymarket / Polymarket Data
  - HTTP method selector: GET / POST / DELETE
  - Collapsible request body textarea (JSON, min 80ch wide) — available for all methods
  - Collapsible help section with request templates for both venues:
    - Polymarket: cancel order (`DELETE /order`), sell/place order (`POST /order` with EIP-712 struct)
    - Kalshi: cancel order (`DELETE /portfolio/events/orders/{id}`), sell/place order (`POST /portfolio/events/orders`)
  - JSON response viewer with status code tag
  - Proxies through `POST /api/requests/proxy` backend endpoint

## Key Technical Details

### localStorage Keys
- `'token'` - JWT authentication token (used by axios interceptor)
- `'username'` - Current user's username

### Environment Variables
- `VITE_API_URL` - Backend API base URL (default: `/api`)
  - Development: typically `http://localhost:8080/api`
  - Production/Docker: `/api` (proxied through Nginx)

### Common Pitfalls

1. **Axios Interceptor Token Key**: The interceptor MUST use `localStorage.getItem('token')` not array destructuring or different key names. The code previously had a bug with `const [token_9607] = localStorage.getItem('[token_9607]')` which caused iteration errors.

2. **Form Validation**: Use promise-based `await formRef.value.validate()` instead of callback-based validation for cleaner async/await flow.

3. **Route Meta**: When adding new routes, remember to set `meta: { requiresAuth: true }` for protected routes or `meta: { requiresGuest: true }` for public-only routes.

## Default Test Credentials

| Username | Password | Role  |
|----------|----------|-------|
| admin    | admin123 | ADMIN |
| user     | user123  | USER  |

## Backend Details

- Spring Boot 3.4.2 with Java 23
- Build output directory: `.build/` (not standard `build/`)
- Uses Gradle Kotlin DSL (`.gradle.kts` files)
- H2 in-memory database
- JWT-based security with Spring Security
