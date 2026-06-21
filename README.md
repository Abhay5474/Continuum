# ⟳ Continuum — A Durable Execution Engine for AI Agents

Continuum is a miniature, self-contained **durable workflow runtime** (in the
spirit of Temporal/Cadence) specialized for AI workloads. It makes
multi-step AI agents behave like reliable infrastructure: they **survive
crashes**, **never repeat side effects**, **checkpoint progress through event
sourcing**, **replay deterministically**, and **fail over across model
providers** — all without losing state.

> The AI model is treated as just another unreliable dependency. The real
> system is the engine that keeps making forward progress despite crashes,
> timeouts, provider outages and non-deterministic model output.

```
            ┌────────────┐      ┌──────────────────────────────┐
            │  React UI  │────▶ │        Spring Boot API        │
            └────────────┘      └───────────────┬──────────────┘
                                                 │
                                  ┌──────────────▼───────────────┐
                                  │      Workflow Runtime Engine  │
                                  │  (deterministic replay loop)  │
                                  └───┬───────────┬───────────┬───┘
                            ┌─────────▼──┐  ┌─────▼──────┐  ┌─▼──────────┐
                            │ Event Store│  │ Task Queue │  │  Outbox    │
                            │ PostgreSQL │  │ PostgreSQL │  │ PostgreSQL │
                            └────────────┘  └─────┬──────┘  └─────┬──────┘
                                                  │ Worker Pool   │ Dispatcher
                                       ┌──────────▼─────────┐  ┌──▼─────────────┐
                                       │ LLM / Email / Pay  │  │ Email/Payment  │
                                       │     Activities     │  │     Sinks      │
                                       └─────────┬──────────┘  └────────────────┘
                                       ┌─────────▼──────────┐
                                       │  Provider Adapters │  Gemini · Groq · Mock
                                       └────────────────────┘
```

## Why it's interesting

| Most AI projects | Continuum |
| --- | --- |
| `prompt → LLM → response` | `workflow → event sourcing → replay → recovery → idempotency → failover → LLM` |
| Crash = start over | Crash = resume from the last recorded step |
| Retry = duplicate emails/charges | Idempotent, exactly-once side effects |
| Provider down = workflow dies | Automatic failover, state preserved |

## Core guarantees (and how they're proven)

1. **Durable execution** — every workflow step is an immutable event. State is
   reconstructed by replaying the log; nothing depends on in-memory state.
2. **Deterministic replay** — workflow code is a pure function of its input and
   history. Activities and even clock/UUID reads are recorded and replayed, so
   recovery never diverges and the LLM is never called twice for the same step.
3. **Crash recovery** — workers claim work with `FOR UPDATE SKIP LOCKED` and a
   visibility timeout; a crashed worker's task is automatically reclaimed.
4. **Idempotent, exactly-once side effects** — emails/payments go through a
   transactional outbox keyed by a deterministic idempotency key.
5. **Provider failover** — LLM calls route through an ordered provider chain
   (Gemini → Groq → Mock); a failure transparently falls over to the next.

See **[docs/USAGE.md](docs/USAGE.md)** for a guided tour of every feature,
including copy-paste chaos demos that kill workers and flood sinks with
failures while the system stays correct.

## V2 — AI Reliability Research Platform

On top of the durable runtime, Continuum V2 adds five AI-native reliability
capabilities (all additive, opt-in, default-off — V1 behavior is unchanged).
Full details in **[docs/V2_EXTENSIONS.md](docs/V2_EXTENSIONS.md)**.

1. **Semantic Replay Verification** — re-runs historical LLM steps against
   today's provider and scores semantic equivalence (similarity, decision
   intent, tools, structure, constraints). Catches drift and decision reversals
   that deterministic replay alone cannot. `POST /api/replay/verify/{id}`
2. **AI-Aware Model Router** — cost/latency/quality scheduler that picks the
   provider chain *before* execution from **measured** runtime stats (no
   hardcoded scores). `POST /api/routing/...`
3. **AI Chaos Engineering** — first-class AI failure injection (hallucination,
   schema/tool corruption, prompt injection, context truncation, memory
   corruption, provider drift) with real survival metrics. `POST /api/ai-chaos/...`
4. **Tail Latency Hedging** — cost-aware request hedging with first-success
   arbitration to cut p99 latency. `POST /api/hedging/...`
5. **Long-Context Memory** — hierarchical memory (working/episodic/long-term/
   archived) with relevance ranking, compression and tier promotion. `POST /api/memory/...`

Flagship cross-extension demo: inject a hallucinated decision reversal
(Extension 3) and watch Semantic Replay Verification (Extension 1) detect it —
`decisionConsistency` drops to 0 and the affected activities fail verification.

## Quick start (one command)

```bash
docker compose up --build
# Dashboard: http://localhost:8081
# API:       http://localhost:8080
```

No API keys needed — Continuum ships with an always-available mock LLM provider.
Add `GEMINI_API_KEY` / `GROQ_API_KEY` to use real models and demo failover.

## Run locally without Docker

```bash
# 1. Postgres
createdb continuum   # or use any Postgres; set SPRING_DATASOURCE_URL

# 2. Backend
cd backend && mvn spring-boot:run

# 3. Frontend
cd frontend && npm install && npm run dev   # http://localhost:5173
```

## The flagship demo (crash recovery)

```bash
# slow activities so we can kill mid-flight
curl -X POST "localhost:8080/api/chaos/activity-latency?ms=2500"
WID=$(curl -s -X POST localhost:8080/api/workflows -H 'Content-Type: application/json' \
      -d '{"workflowType":"DurableDemo","input":{"name":"demo"}}' | jq -r .workflowId)

sleep 4 && kill -9 <backend-pid>     # crash the worker mid-step
# ...restart the backend...

curl -s localhost:8080/api/workflows/$WID | jq '.result, .events[].eventType'
# → workflow COMPLETED, no step repeated, captured timestamp unchanged.
```

## Layout

```
backend/    Spring Boot engine (event store, replay, queue, outbox, providers)
frontend/   React + TypeScript + Tailwind dashboard
docs/        DEPLOYMENT.md, USAGE.md, ARCHITECTURE.md
docker-compose.yml, render.yaml   deployment blueprints
```

## Tech stack

- **Backend:** Java 21, Spring Boot 3, Spring Data JPA, Flyway, PostgreSQL
- **Frontend:** React 18, TypeScript, Tailwind, Vite
- **Deploy:** Render (API + Neon/Render Postgres), Vercel (dashboard)

## Tests

```bash
cd backend && mvn test     # includes the deterministic-replay contract test
```

---
Built as a serious distributed-systems project, not an AI demo. The interesting
part is the engine.
