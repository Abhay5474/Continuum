# Continuum V2 — AI Reliability Research Extensions

V2 turns Continuum from a *durable execution engine for AI workflows* into an
**AI Reliability Research Platform**. It adds five capabilities that target
AI-native failure modes traditional workflow engines don't address.

Everything here is **additive and backward compatible**:

- No V1 table is altered (new migration `V2__v2_extensions.sql` only adds tables).
- Every new behavior is **opt-in and defaults OFF** — with nothing enabled, the
  LLM activity executes exactly as in V1.
- All V1 tests still pass; each new capability ships with its own tests.

```
                         ┌──────────────────────────────────────────┐
                         │              LLM Activity                  │
                         │  (V1 path is default; V2 hooks are gated)   │
                         └───┬───────────┬───────────┬────────────┬──┘
            request hooks    │           │           │            │  response hooks
        ┌────────────────────▼──┐   ┌────▼─────┐  ┌──▼────────┐  ┌▼──────────────────┐
        │ Ext2 ModelRouter      │   │ Ext4     │  │ Ext3      │  │ Ext1 SemanticReplay│
        │ select provider chain │   │ Hedging  │  │ AI Chaos  │  │ (offline analysis) │
        └───────────┬───────────┘   └────┬─────┘  └───────────┘  └────────────────────┘
                    │ ordered chain        │ race + arbitrate
                    └──────────► ProviderRouter (V1 failover, unchanged) ──► adapters
        Ext5 Memory: separate activities/APIs feeding context in/out of workflows
```

---

## Extension 1 — Semantic Replay Verification

**Problem.** Deterministic replay guarantees a workflow re-runs to the same
recorded outputs. It cannot tell you whether the workflow would still behave
*correctly* against today's models/prompts/providers.

**What it does.** For each historical LLM activity, it regenerates the output
with the current provider and scores semantic equivalence across five
dimensions, then aggregates a verdict.

**Scoring (deterministic, offline-capable).** `SemanticComparator` computes:

| Dimension | Method |
| --- | --- |
| Lexical similarity | cosine over term-frequency vectors (`TextVectors`) |
| Decision intent | opposing-pole detection (`DecisionPolarity`) — catches reversals |
| Tool consistency | Jaccard over tool-call names |
| Structured compatibility | JSON field/type overlap |
| Constraint preservation | numeric/id token retention |

A reversed decision ("approve" → "reject") is an automatic fail regardless of
lexical similarity. An optional **LLM-as-judge** blends a model's equivalence
rating when a real provider is configured (`useLlmJudge=true`).

**Aggregate metrics:** `replayConfidence` (mean overall), `semanticDrift`
(1 − mean similarity), `decisionConsistency` (mean intent agreement).

**Schema:** `replay_verification_reports`.

**API**
```
POST /api/replay/verify/{workflowId}?passThreshold=&useLlmJudge=
GET  /api/replay/reports/{workflowId}
GET  /api/replay/trends?limit=
```

**Demo (also proves Extension 3):**
```bash
# corrupt an LLM output with a hallucinated reversal, then detect it
curl -s -X POST "localhost:8080/api/ai-chaos/rate?type=HALLUCINATION&rate=1.0"
WID=$(curl -s -X POST localhost:8080/api/workflows \
      -d '{"workflowType":"CustomerAnalysis","input":{"customerId":"C1"}}' | jq -r .workflowId)
# wait for completion …
curl -s -X POST localhost:8080/api/ai-chaos/reset
curl -s -X POST "localhost:8080/api/replay/verify/$WID" | jq '.passed,.failed,.decisionConsistency'
# → failed activities with "INTENT REVERSAL", decisionConsistency 0.0
```

**Research question.** Do workflows remain semantically stable across provider /
prompt / model upgrades? The drift trend answers it empirically.

---

## Extension 2 — AI-Aware Model Router

**Problem.** V1 routing is reactive (static failover order). The runtime should
pick the *optimal* provider before execution.

**What it does.** A cost/latency/quality-aware scheduler selects an ordered
provider chain per task, then hands it to the unchanged `ProviderRouter`.
Operates strictly **above** the adapter layer — adapters are untouched.

**Inputs are all measured, none hardcoded.**

- `TaskComplexityEstimator` — complexity from prompt size + reasoning-signal words.
- `ProviderMetrics` (`provider_stats` table) — real latency, tokens, cost,
  error rate recorded on **every** LLM call.
- `CostPredictor` — predicts cost using pricing + measured avg completion length.
- `QualityPredictor` — quality from measured reliability (1 − error rate) blended
  with semantic-verification fidelity; neutral 0.5 + low confidence when no data.
- `ProviderScorer` — min-max normalizes cost/latency across candidates; complexity
  tilts weight toward quality. Pure & unit-tested.

**Routing modes:** `LOW_COST`, `LOW_LATENCY`, `HIGH_QUALITY`, `BALANCED`, `CUSTOM`.

**Schema:** `provider_stats`, `routing_decisions`.

**API**
```
GET  /api/routing/state
POST /api/routing/enable?enabled=&mode=
POST /api/routing/mode?mode=
GET  /api/routing/providers          # live measured stats
POST /api/routing/select             # preview scoring for a sample task
GET  /api/routing/decisions?limit=
```

> With only the mock provider available the chain has one entry; configure real
> Gemini/Groq keys to see differentiated scoring and selection.

---

## Extension 3 — AI Chaos Engineering

**Problem.** Real AI systems fail differently than infrastructure. V1 chaos
covers crashes/latency/outages; V2 covers AI-native failures.

**Injectors** (each `AiFailureType`, applied around the real LLM call):

| Type | Effect |
| --- | --- |
| `HALLUCINATION` | replace output with plausible-but-wrong, reversing decisions |
| `SCHEMA_CORRUPTION` | drop fields / wrong types / malformed JSON |
| `TOOL_CORRUPTION` | force wrong/dangerous tool (e.g. `payment.refund`) |
| `PROMPT_INJECTION` | append a real injection payload to context |
| `CONTEXT_TRUNCATION` | drop critical middle context |
| `MEMORY_CORRUPTION` | corrupt retrieved memory (Extension 5 hook) |
| `PROVIDER_DRIFT` | reword output to simulate a model revision (drift, no reversal) |

**Metrics are real**, derived by joining injection events to actual workflow
outcomes: total injections, by type, affected workflows, survived/failed, and
**workflow survival rate**.

**Schema:** `ai_chaos_events`.

**API**
```
GET  /api/ai-chaos
POST /api/ai-chaos/rate?type=&rate=
POST /api/ai-chaos/reset
GET  /api/ai-chaos/events?limit=
GET  /api/ai-chaos/metrics
```

Dashboard: **AI Chaos Lab** (sliders per failure type, live survival metrics,
recent injections).

---

## Extension 4 — Tail Latency Hedging

**Problem.** A single slow provider request blows up p99 latency.

**What it does.** `HedgedProviderExecutor` launches the primary; if no response
within `thresholdMs`, it launches a parallel hedge to the next provider (bounded
by `maxHedges` and a `CostBudgetGuard`), takes the first success
(`ResponseArbitrator`) and cancels the rest. Failures trigger immediate
failover. Cost-aware: it never blindly duplicates every request.

**Tested** deterministically with controllable latencies and budgets
(`HedgedProviderExecutorTest`): fast-primary-no-hedge, slow-primary-hedge-wins,
failover-on-error, budget-blocks-hedge.

**API**
```
GET  /api/hedging
POST /api/hedging/enable?enabled=
POST /api/hedging/policy?thresholdMs=&maxHedges=&budgetUsd=
```

---

## Extension 5 — Long Context Memory System

**Problem.** Long-running agents overflow context windows and suffer
lost-in-the-middle degradation.

**What it does.** Hierarchical memory (`WORKING → EPISODIC → LONG_TERM →
ARCHIVED`) stored in PostgreSQL, outside the context window:

- **Retrieve** — `RelevanceRanker` (pure, tested) blends cosine relevance,
  recency decay and salience; only the top-k are returned.
- **buildContext** — packs the most relevant memories within a char budget.
- **Compress** — summarizes cold EPISODIC memories into a LONG_TERM summary
  (offline extractive by default; LLM summary optional) and archives originals.
- **maintain** — promotes frequently-accessed and demotes stale entries.

Workflows use it via the `memory.store` / `memory.retrieve` durable activities;
operators/agents use the REST API.

**Schema:** `memory_entries`.

**API**
```
POST /api/memory/store      {scope,tier,content,salience}
POST /api/memory/retrieve   {scope,query,topK}
POST /api/memory/context    {scope,query,topK,maxChars}
POST /api/memory/compress?scope=&useLlm=
POST /api/memory/maintain?scope=
GET  /api/memory/{scope}
```

---

## Java package structure (new)

```
io.continuum.semantic    TextVectors, DecisionPolarity, SemanticComparator,
                         SemanticReplayVerifier, ReplayVerificationPolicy/Report
io.continuum.routing     ProviderMetrics, TaskComplexityEstimator, CostPredictor,
                         QualityPredictor, ProviderScorer, ProviderSelectionEngine,
                         RoutingMode/Policy, ModelRoutingState
io.continuum.aichaos     AiChaosEngine, AiFailureType, injectors/*
io.continuum.hedging     HedgingPolicy, ProviderCaller, CostBudgetGuard,
                         ResponseArbitrator, HedgedProviderExecutor, HedgingService
io.continuum.memory      MemoryTier, RelevanceRanker, MemoryService
io.continuum.api         ReplayVerification/Routing/AiChaos/Hedging/Memory controllers
```

New entities: `ReplayVerificationReportEntity`, `ProviderStatsEntity`,
`RoutingDecisionEntity`, `AiChaosEventEntity`, `MemoryEntryEntity`.

---

## Backward compatibility checklist

- ✅ V1 endpoints unchanged; V2 endpoints are new paths.
- ✅ `LlmActivity` default path identical to V1 when routing/hedging/ai-chaos are off.
- ✅ `ProviderRouter.complete(request)` behaves exactly as before; the new
  `complete(request, chain)` overload powers routing/hedging.
- ✅ Provider adapters (Gemini/Groq/Mock) untouched.
- ✅ Only additive DB migrations.
- ✅ `mvn test` green (V1 + V2).

---

## Tests

```bash
cd backend && mvn test
```

| Test | Proves |
| --- | --- |
| `WorkflowExecutorTest` (V1) | deterministic replay contract |
| `SemanticComparatorTest` | reword passes, reversal fails, structured/identical scoring |
| `HallucinationInjectorTest` | single-pass decision reversal |
| `HedgedProviderExecutorTest` | hedging race, failover, budget guard |
| `RelevanceRankerTest` | relevance/recency/salience ranking |
