# Deployment & Configuration Guide

This guide covers everything you must configure **before** deploying Continuum,
plus three deployment paths: local Docker, Render + Vercel (recommended), and
fully manual.

---

## 1. Prerequisites

| Tool | Version | Needed for |
| --- | --- | --- |
| JDK | 21+ | building/running the backend |
| Maven | 3.9+ | building the backend |
| Node | 20+ | building the dashboard |
| PostgreSQL | 14+ | the only datastore (events, queues, outbox) |
| Docker | optional | one-command local stack |

PostgreSQL is **required** — Continuum relies on `FOR UPDATE SKIP LOCKED`,
transactions, and unique constraints for its correctness guarantees. Other
databases are not supported.

---

## 2. Configuration reference

All configuration is via environment variables (12-factor). Defaults make local
development zero-config.

### Database (required in production)

| Variable | Default | Notes |
| --- | --- | --- |
| `SPRING_DATASOURCE_URL` | `jdbc:postgresql://localhost:5432/continuum` | JDBC URL |
| `SPRING_DATASOURCE_USERNAME` | `continuum` | |
| `SPRING_DATASOURCE_PASSWORD` | `continuum` | |
| `DB_POOL_SIZE` | `15` | Hikari max pool size |
| `DATABASE_URL` | — | If set (`postgres://user:pass@host/db`), it is auto-translated to the three values above. Use this on Render/Heroku/Neon/Railway. |

> **Schema:** Flyway creates and migrates the schema automatically on startup
> (`backend/src/main/resources/db/migration`). Hibernate `ddl-auto` is `none` —
> Flyway is the single owner of the schema. You do **not** run any SQL by hand.

### LLM providers (optional)

| Variable | Default | Notes |
| --- | --- | --- |
| `GEMINI_API_KEY` | _(empty)_ | Enables the Gemini adapter |
| `GEMINI_MODEL` | `gemini-3.5-flash` | See Gemini model options below |
| `GROQ_API_KEY` | _(empty)_ | Enables the Groq adapter |
| `GROQ_MODEL` | `llama-3.3-70b-versatile` | See Groq model options below |
| `LLM_FAILOVER_ORDER` | `gemini,groq,mock` | Comma-separated failover chain |

**Gemini model options** (`GEMINI_MODEL`):

| Model id | Notes |
| --- | --- |
| `gemini-3.5-flash` | **Default.** Latest, most intelligent — strong agentic/coding performance |
| `gemini-3.1-flash-lite` | Fast, budget-friendly, multimodal — high-volume tasks |
| `gemini-2.5-flash` | Balanced workhorse with strong reasoning |
| `gemini-2.0-flash` | Optimized earlier multimodal model |

**Groq model options** (`GROQ_MODEL`):

| Model id | Notes |
| --- | --- |
| `llama-3.3-70b-versatile` | **Default.** Latest high-quality general-purpose model |
| `llama-3.1-8b-instant` | Fastest, lowest cost for high-volume tasks |

If no API keys are set, the always-available **mock provider** is used, so the
system is fully functional and demoable with zero keys. To demonstrate real
failover, configure both Gemini and Groq.

### Engine tuning (optional)

| Variable | Default | Notes |
| --- | --- | --- |
| `ENGINE_POLL_INTERVAL_MS` | `500` | Worker poll cadence |
| `ENGINE_BATCH_SIZE` | `10` | Tasks claimed per poll |
| `ENGINE_RECOVERY_INTERVAL_MS` | `5000` | Crashed-task sweeper cadence |
| `ENGINE_WORKFLOW_TASK_TIMEOUT` | `60` | Visibility timeout (s) for decisions |
| `ENGINE_WORKERS_ENABLED` | `true` | Set `false` to run an API-only node (see scaling) |

### Developer Gateway (V3)

| Variable | Default | Notes |
| --- | --- | --- |
| `CONTINUUM_MASTER_KEY` | _(empty)_ | AES-256-GCM key for the provider-credential vault and API-key pepper. **Required in production.** If unset, the vault uses an ephemeral key (logs a loud warning) and stored secrets won't survive a restart. |
| `CONTINUUM_ADMIN_TOKEN` | _(empty)_ | Required `X-Admin-Token` for `/api/admin/**` onboarding endpoints. If unset, admin routes are open (dev only) with a warning. |
| `continuum.registry.discovery-cron` | `0 0 3 1 * *` | Model discovery schedule (monthly). |

See **[docs/V3_GATEWAY.md](V3_GATEWAY.md)** for the full gateway feature set.

### Server

| Variable | Default |
| --- | --- |
| `PORT` | `8080` |

### Frontend

| Variable | Default | Notes |
| --- | --- | --- |
| `VITE_API_BASE` | _(empty)_ | Backend base URL. Empty = same origin / Vite dev proxy. Set to your API URL for production (e.g. `https://continuum-api.onrender.com`). |

---

## 3. Path A — Local Docker (one command)

```bash
docker compose up --build
```

Brings up Postgres, the backend (`:8080`) and the dashboard (`:8081`). Optional
keys:

```bash
GEMINI_API_KEY=xxx GROQ_API_KEY=yyy docker compose up --build
```

---

## 4. Path B — Render (API + DB) + Vercel (dashboard)  ✅ recommended

### 4.1 Backend + database on Render

1. Push this repo to GitHub.
2. In Render: **New → Blueprint**, select the repo. `render.yaml` provisions:
   - a free PostgreSQL instance (`continuum-db`)
   - the Dockerized API (`continuum-api`) wired to it via `DATABASE_URL`
3. (Optional) In the service's **Environment**, set `GEMINI_API_KEY` and
   `GROQ_API_KEY`.
4. Deploy. Health check: `GET /actuator/health`.

> Using **Neon** instead of Render Postgres? Create a Neon database, copy its
> connection string into the `DATABASE_URL` env var on the Render service, and
> remove the `databases:` block from `render.yaml`.

### 4.2 Dashboard on Vercel

1. In Vercel: **New Project → import the repo**, set **Root Directory** to
   `frontend`.
2. Framework preset: **Vite** (auto-detected; `vercel.json` is included with the
   SPA rewrite).
3. Add an environment variable:
   `VITE_API_BASE = https://<your-render-api>.onrender.com`
4. Deploy.

CORS is already enabled for `/api/**` on the backend, so the Vercel origin can
call the Render API directly.

---

## 5. Path C — Manual / VM

```bash
# Backend
cd backend
mvn -DskipTests package
SPRING_DATASOURCE_URL=jdbc:postgresql://DBHOST:5432/continuum \
SPRING_DATASOURCE_USERNAME=continuum \
SPRING_DATASOURCE_PASSWORD=secret \
java -jar target/continuum.jar

# Frontend (static hosting)
cd frontend
VITE_API_BASE=https://api.example.com npm run build
# serve ./dist with any static server / CDN (SPA fallback to index.html)
```

---

## 6. Scaling & operations

- **Horizontal scaling:** run multiple backend instances against the same
  Postgres. Workers coordinate purely through the database
  (`FOR UPDATE SKIP LOCKED` + visibility timeouts), so adding instances adds
  throughput with no extra infrastructure.
- **Separating API and workers:** run API-only nodes with
  `ENGINE_WORKERS_ENABLED=false` and dedicated worker nodes with it `true`.
- **Recovery:** if a node dies, its in-flight tasks are reclaimed automatically
  after their visibility timeout — no manual intervention.
- **Health:** `GET /actuator/health` (liveness/readiness probes enabled).
- **Backups:** the event log (`workflow_events`) is the source of truth — back
  up Postgres and you can reconstruct all workflow state.

---

## 7. Pre-deploy checklist

- [ ] PostgreSQL reachable; credentials set (`DATABASE_URL` or the three vars).
- [ ] Outbound network egress allowed to provider APIs (if using real LLMs).
- [ ] `GEMINI_API_KEY` / `GROQ_API_KEY` set (optional; mock works without).
- [ ] `VITE_API_BASE` points at the deployed API for the dashboard.
- [ ] `GET /actuator/health` returns `UP` after first boot (Flyway migration ran).
