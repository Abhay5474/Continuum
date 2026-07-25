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

## V3 — Developer AI Infrastructure Gateway

A developer-facing control plane on top of the engine: external apps integrate
once and get reliability, routing, model lifecycle and observability — without
provider SDKs. Additive and backward compatible (auth only on the gateway chat
endpoint). Full details in **[docs/V3_GATEWAY.md](docs/V3_GATEWAY.md)**.

- **Developer gateway** — `POST /api/gateway/chat` (+ OpenAI-shaped `/v1/chat/completions`).
- **Continuum API keys** — `cnt_live_…`, stored hashed; invalid → 401.
- **Credential vault** — developers' own provider keys encrypted AES-256-GCM,
  decrypted only at call time, never logged or returned.
- **Intelligent routing + model failover** — deterministic complexity scoring
  (reuses V2 stats) and a capability-aware `(provider, model)` fallback chain
  with per-model health.
- **Model registry & lifecycle** — discovery seeds a vetted catalog; lifecycle
  `DISCOVERED→TESTING→ACTIVE→DEPRECATED→REMOVED`; disappeared models auto-deprecated.
- **Observability** — `GET /api/gateway/stats` shows requests, success rate,
  and **failures prevented** (silent failovers) vs developer-visible failures;
  new **Gateway** dashboard tab.

Demo: a developer with an invalid Gemini key sees their request silently fail
over across 4 Gemini models to a working one — `failuresPrevented: 4`,
`developerVisibleFailures: 0`.

## V4 — Autopilot (opt-in autonomous control plane)

An **optional, off-by-default** intelligence layer that safely tunes a
developer's runtime (routing, fallback, hedging, budgets, timeouts) from real
telemetry, like an AI SRE. Full details in **[docs/V4_AUTOPILOT.md](docs/V4_AUTOPILOT.md)**.

- **Opt-in & reversible** — per-developer toggle, OFF by default. When off the
  gateway runs its exact pre-Autopilot path (guaranteed by `PolicyResolver`).
- **Closed loop** — Observe → Infer → Propose → Verify → Canary → Promote/Rollback → Learn.
- **Real decision engine** — Thompson-sampling contextual bandit + Bayesian
  failure estimation + constrained optimization (not if/else).
- **Bounded & safe** — only versioned, immutable policy bundles; verified before
  traffic, canaried on a small %, auto-rolled-back on regression.
- **Beginner-friendly UI** — onboarding wizard, explainer cards, one-click
  ON/OFF, recommendations with confidence, canary meters, policy history,
  rollback events. `/api/portal/developer/autopilot/*`

## V5.1.1 — Paradox Resolution Engine (determinism divergence auto-healing)

A **compulsory, always-on** structural auto-healing layer inside the replay
loop that eliminates the classic event-sourcing failure mode: deploying changed
workflow code over in-flight instances. Full details in
**[docs/V5_PARADOX_HEALING.md](docs/V5_PARADOX_HEALING.md)**.

- **Divergence interception** — code↔history mismatches are caught inside the
  decision replay before they can crash a worker.
- **Stack-to-history diffing** — inserted / deleted / re-ordered activities are
  classified against the recorded `ACTIVITY_SCHEDULED` stream.
- **History virtualization** — insertions get fresh virtualized slots (fresh
  idempotency keys ⇒ outbox/cost exactly-once preserved); deletions shift the
  sequence cursor past obsolete records; reorders reuse recorded results.
- **Durable resolution ledger** — micro-patch mappings committed to
  `workflow_healing_logs` atomically with the decision, so healed instances
  replay deterministically forever.
- **Observability** — `GET /api/gateway/healing/status`, per-instance ledgers,
  a dry-run verify scan, a "Paradox Resolution Ledger" dashboard panel and
  `PARADOX RESOLVED` badges on healed workflows.

## V5 — Adaptive Policy (autonomous memory & policy engine)

An **opt-in, off-by-default** autonomous layer: 4-tier learned memory
(Working → Episodic → Semantic experience graph → Archive), Memory-as-Action
policies on the V4 Thompson-sampling machinery, and a **digital twin** that
replays every policy candidate against real historical traffic — vetoing
confident regressions before they receive live traffic. Ships with a full UI
overhaul and the **Adaptive Policy** console page (enable toggle, context
weight gauge, memory-tier flow, counterfactual timeline, experience graph). Full details in **[docs/V5_GOD_MODE.md](docs/V5_GOD_MODE.md)**.

## V6 — The Consensus DAG Engine (verifiable AI reliability layer)

An **opt-in, off-by-default** compliance-grade verification layer: gateway
requests are compiled into a parallel DAG of solver and verifier nodes (every
node a durable Postgres-queued activity — no threads), contradictions are
resolved by pure Bayesian evidence aggregation (no LLM judge), and the verified
answer returns in the identical response shape. Includes the **Execution
Command Center** trace UI (living decision graph, causal inspector, risk
engine, time-travel scrubber, "Collapse to Truth") and a portal toggle with a
when-to-use guide. Full details in **[docs/V6_CONSENSUS_DAG.md](docs/V6_CONSENSUS_DAG.md)**.

## V7 — Context Virtualization (the Paging MMU for AI)

An **opt-in, off-by-default** OS-style MMU for LLM context: Continuum owns the
Virtual Context Space and the model only sees a bounded L1 slice. Long
histories page out into L2 semantic stubs (`[MEMORY_REF: …]`) backed by L3
per-stub event streams; relevant pages are **predictively prefetched**, true
page faults are intercepted mid-generation and lazily materialized
(base ⊕ mutation deltas — never stale), and dirty pages flush write-behind.
Live result: a 9k-token conversation sent as 2.7k tokens (70% reduction),
fully transparent to the client. Includes the **Context Memory Profiler** UI.
Full details in **[docs/V7_CONTEXT_MMU.md](docs/V7_CONTEXT_MMU.md)**.

## V8 — Research improvements & new paper-based features

Two research improvements to existing paper-based features + two new
paper-grounded features, all additive and (for the new features) opt-in.
Full details in **[docs/V8_RESEARCH.md](docs/V8_RESEARCH.md)**.

- **Adaptive hedging** — the tail-latency hedge now fires at the live p95 and is
  capped at a 5% hedge rate, faithful to *The Tail at Scale* (was a fixed delay).
- **Contextual + non-stationary bandit** — provider posteriors are now kept
  per-complexity-context and discounted over time (LinUCB / discounted Thompson
  sampling), fixing the context-free, stationary gap in the V4 bandit.
- **Prompt Compression (LLMLingua)** — drops low-information tokens to cut input
  cost while protecting numbers/IDs/code; opt-in.
- **Prompt Firewall (OWASP LLM01)** — inbound PII redaction + prompt-injection
  blocking and outbound secret scanning; opt-in.
- **Novel contribution harness** — `ParadoxHealingBenchmarkTest` proves the
  self-healing replay engine over **300 mid-flight code mutations: 0 crashes, 0
  double side-effects, 266 divergences healed.**

## Security & multi-tenancy

Every console API is authenticated and tenant-scoped. Two rules hold throughout:

1. **The tenant comes from the session, never from the request.** Endpoints do
   not accept a `developerId` parameter — it is resolved from the signed-in
   session, so one account cannot name another and read its data. Reaching for a
   record owned by someone else returns `403`.
2. **Everything fails closed.** With no `CONTINUUM_ADMIN_TOKEN` configured the
   admin surface and operator login are unavailable rather than open. Developers
   onboard through `POST /api/portal/developer/signup`.

What each role sees:

| Role | Scope |
|------|-------|
| Anonymous | Landing page, docs, `/api/meta` only |
| `DEVELOPER` | Their own workflows, traces, stats, memory, usage and keys |
| `OPERATOR` | Engine-wide state (requires `CONTINUUM_ADMIN_TOKEN`) |

Workflows carry an owning `developer_id` (nullable — rows created before this and
internal system workflows have none and are operator-visible only). Memory scopes
are namespaced per tenant server-side, so identical scope names never collide
across accounts. `RequestScopeTest` locks these rules in.

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
