# Dify SSE Chat — React + Spring Boot + Dify AI

Production-ready monorepo implementing an **SSE-first streaming chat** architecture:

```
┌─────────────────┐      SSE       ┌──────────────────┐      SSE       ┌─────────────┐
│   React Client  │ ◄────────────► │  Spring Boot     │ ◄────────────► │  Dify AI    │
│ (fetch + SSE    │  (text/event-  │  (WebFlux SSE    │  (text/event-  │  (Chat API) │
│  parser)        │    stream)     │   proxy)         │    stream)     │             │
└─────────────────┘                └──────────────────┘                └─────────────┘
```

The Spring Boot backend is a **transparent, non-buffering SSE proxy**: every token Dify
emits is forwarded to the browser the moment it arrives. The React client consumes the
stream via `fetch` + `ReadableStream` (POST-based SSE, since `EventSource` only supports GET).

## Repository layout

```
├── backend/    Spring Boot 3.3 (WebFlux) SSE proxy — Java 17, Maven wrapper included
├── frontend/   React 18 + TypeScript + Vite chat UI — Vitest test suite
├── nginx/      Production reverse-proxy config (SSE-safe: buffering off)
├── docker-compose.yml
├── .env.example
└── recreate.md Full blueprint to recreate this project from scratch
```

## Prerequisites

- **Java 17+** (a recent patch release, e.g. Temurin 17.0.13+)
- **Node.js 18+** (tested with Node 22)
- **Docker** (optional, for containerized deployment)
- A **Dify** app (Chatflow/Workflow) with an API key — see [Dify config](#dify-configuration)

Maven is not required — the repo ships the Maven Wrapper (`backend/mvnw`).

## Quick start (local development)

### 1. Backend

```bash
cd backend
# Windows PowerShell:  $env:DIFY_API_KEY = "app-..."
export DIFY_API_KEY=app-your-key
export DIFY_BASE_URL=https://api.dify.ai/v1   # or your self-hosted /v1 URL
./mvnw spring-boot:run
```

Backend runs at `http://localhost:8080`. Endpoints:

| Method | Path                | Description                          |
|--------|---------------------|--------------------------------------|
| POST   | `/api/chat/stream`  | SSE streaming chat (text/event-stream) |
| GET    | `/api/chat/health`  | Simple health check                  |
| GET    | `/actuator/health`  | Actuator health (liveness/readiness) |

### 2. Frontend

```bash
cd frontend
npm install
npm run dev
```

Open `http://localhost:3000`. The API base URL comes from `VITE_API_URL`
(defaults to `http://localhost:8080/api`; see `frontend/.env.example`).

## Running tests

```bash
# Backend — 19 tests (unit + WebFlux slice + SSE integration via MockWebServer)
cd backend && ./mvnw test

# Frontend — 22 tests (SSE parser, streaming hook, component)
cd frontend && npm test
```

## Docker

```bash
cp .env.example .env      # fill in DIFY_API_KEY
docker compose up --build
```

- Frontend: `http://localhost:3000`
- Backend: `http://localhost:8080`

Production profile with SSE-safe nginx reverse proxy in front of both:

```bash
VITE_API_URL=/api docker compose --profile production up --build
```

Then browse `http://localhost` — nginx serves the frontend at `/` and proxies `/api/`
to the backend with `proxy_buffering off` (essential for SSE).

## Dify configuration

1. In Dify Studio create a **Chatflow** (or Workflow) app.
2. Go to **API Access → API Keys** and generate a key (`app-...`).
3. Base URL: `https://api.dify.ai/v1` for Dify Cloud, or `http://your-host/v1` self-hosted.
4. Conversation behavior: the first request omits `conversation_id`; Dify returns one in
   the `message` events and the frontend automatically reuses it for multi-turn chat.

### Test the stream with curl

```bash
curl -N -X POST http://localhost:8080/api/chat/stream \
  -H "Content-Type: application/json" \
  -H "X-User-Id: test-user" \
  -d '{"message": "Hello, how are you?", "inputs": {}}'
```

## Architecture highlights

| Decision | Rationale |
|----------|-----------|
| WebFlux (reactive) over WebMvc | Non-blocking, backpressure-aware, thousands of concurrent streams |
| Transparent SSE proxy | No buffering; Dify events (`message`, `message_end`, `workflow_finished`, `error`, …) forwarded natively |
| POST-based SSE in React | `EventSource` is GET-only; `fetch` + `ReadableStream` + hand-rolled SSE parser supports POST bodies |
| Per-user rate limiting | In-memory fixed window (swap for Redis when scaling horizontally) |
| API key server-side only | The Dify key never reaches the browser |

## Security notes

- CORS is restricted to `CORS_ORIGINS` (comma-separated, no wildcard).
- `X-User-Id` is trusted as-is for demo purposes — in production derive the user
  from an authenticated session/JWT (see `SecurityConfig` sketch in `recreate.md`).
- Error responses to clients are generic; details are logged server-side.
- Rate limit: 10 requests/user/minute by default (`rate-limit.*` properties).

## Troubleshooting

| Symptom | Cause | Solution |
|---------|-------|----------|
| Full response arrives at once | Proxy buffering | `proxy_buffering off; proxy_cache off;` (already in `nginx/nginx.conf`) |
| Connection drops after ~30s | Proxy timeout | Increase `proxy_read_timeout` to ≥120s |
| CORS errors | Origin not allowed | Add your origin to `CORS_ORIGINS` |
| Dify 401 | Bad API key | Check `Authorization: Bearer` value |
| `Unable to establish loopback connection` in tests (Windows) | AF_UNIX sockets blocked in the temp dir by security software | Run with `./mvnw test "-DargLine=-Djdk.net.unixdomain.tmpdir=D:\tmp"` (any local dir where AF_UNIX works) and use a recent JDK 17 patch release |
| Duplicate messages in dev | React StrictMode double-invoke | Expected in dev only; stream state lives in refs |
