# Continuum V6 — The Consensus DAG Engine (Verifiable AI Reliability Layer)

V6 turns Continuum into a **compliance-grade verification and audit layer** for
high-stakes LLM actions. When a developer enables it, each gateway request is
compiled into a dynamically generated, parallel computation graph of
micro-solvers and verifiers; contradictions are resolved **mathematically**
(no LLM judge); and the full execution is replayable and visualized in the
Execution Command Center.

> **Compatibility contract:** the toggle (`developer_auth.v6_dag_enabled`) is
> **OFF by default**. While off — or if a DAG run fails for any reason — the
> gateway executes its exact legacy path (`V6OffPathTest` + live verification:
> zero DAG runs created, identical responses). When on, the verified answer is
> packaged into the **identical `ChatResponse` shape**, so external clients
> never know a DAG ran.

## No threads — pure event sourcing

The one core extension V6 required is an **additive fan-out** in the V1 command
layer: `WorkflowContext.executeActivitiesParallel(...)` lets a single decision
emit N `ACTIVITY_SCHEDULED` events atomically (`Commands.Decision.scheduleMany`).
The existing worker pool claims those tasks concurrently via the
`FOR UPDATE SKIP LOCKED` Postgres queues, and the barrier is replay semantics:
the workflow stays BLOCKED until every branch has a recorded
`ACTIVITY_COMPLETED`. No `CompletableFuture`, no executors — the DAG inherits
crash recovery, exactly-once side effects and deterministic replay for free.
The single-activity path is bit-for-bit unchanged (`WorkflowExecutorTest`
untouched and green; `ParallelFanOutTest` proves the new contract).

## The pipeline (`ConsensusDagWorkflow`, a standard V1 workflow)

```
 prompt ─▶ 1. dag.plan        LLM compiler → typed plan {claims, dependencies, checks}
                              (deterministic fallback decomposition if unparseable)
        ─▶ 2a. dag.solve ×N   parallel solver nodes (one per claim, DB fan-out)
        ─▶ 2b. dag.verify ×M  parallel verifier nodes (one per claim×check):
                              LOGIC_CONSISTENCY (V2 DecisionPolarity),
                              SCHEMA_ALIGNMENT (structured block must parse),
                              EVIDENCE_GROUNDING (V2 TextVectors),
                              CONSTRAINT_CHECK (salient-token preservation)
                              → { claim_id, validity, failure_modes, evidence }
        ─▶ 3. ConflictGraph   PURE function of history: DEPENDS / SUPPORTS /
                              CONTRADICTS edges (V2 polarity reversal detection)
        ─▶ 4. BayesianAggregator  PURE math, no LLM judge:
                              P(correct) = prior × verifier agreement ×
                              evidence strength × contradiction penalty
                              (log-odds likelihood ratios; validity 0.5 = exactly
                              neutral; damped relaxation over contradiction edges)
        ─▶ 5. dag.synthesize  deterministic post-hoc narration of the math
                              (why each claim survived or failed)
```

Steps 3–4 run inside the workflow as pure functions of recorded history, so
the **entire DAG replays deterministically** (proved by `ConsensusDagWorkflowTest`).

## Database (Flyway `V8`, strictly additive)

- `developer_auth.v6_dag_enabled` — additive column, DEFAULT FALSE.
- `dag_runs` / `dag_nodes` / `dag_edges` — a *projection* for the trace UI,
  derived after completion; `workflow_events` remains the source of truth.

## APIs

```
POST /api/portal/developer/v6/enable|disable, GET /status   (session-scoped toggle)
GET  /api/dag/runs                                          recent DAG runs
GET  /api/dag/trace/{workflowId}                            full node/edge trace
```

External clients keep calling `POST /api/gateway/chat` — nothing changes for them.

## UI (scoped strictly to V6)

- **Developer Portal**: the "Enable V6 Verification Engine" card (off by
  default) with a **feature-journey guide** — turn it ON for generated
  SQL/configs/payment logic, compliance audit trails, correctness-over-latency
  flows; keep it OFF for latency-sensitive chat, creative generation and
  high-volume low-risk traffic.
- **Execution Command Center** (`/dag/:workflowId`, renders V6 runs only):
  - **Center canvas** — the living decision graph: 🟦 solvers, 🟨 verifiers
    (green/yellow/red by verdict), 🟥 conflict nodes, 🟪 aggregator with a
    confidence bar; weighted bezier edges with animated particles (thickness =
    influence, color = agreement vs contradiction).
  - **🔥 Collapse to Truth** — dims everything but the surviving reasoning
    spine and reveals the final confidence.
  - **Left: Causal Inspector** — click any node: status, failure reasons,
    structured JSON outputs, with Evidence / Computation / Dependencies tabs.
  - **Right: Risk + Confidence engine** — global confidence meter, uncertainty
    band, risk flags (hallucination risk, schema validated, contradiction resolved).
  - **Top bar** — run id, status, and the time-travel scrubber replaying the
    graph's chronological evolution (failed branches pulse red).
  - No other page was touched beyond the portal card and one nav link.

## Tests (13 new; full suite 93/93 green)

| Test | Proves |
| --- | --- |
| `ParallelFanOutTest` | one decision schedules N activities atomically; barrier holds until all branches recorded; terminal failure propagates; single-activity semantics unchanged; deterministic replay |
| `BayesianAggregatorTest` | agreement raises / disagreement sinks posteriors; validity 0.5 is exactly neutral evidence; contradiction resolves toward the confidently-verified claim; bounds + determinism |
| `ConflictGraphTest` | approve↔reject on the same topic contradicts (V2 polarity); consistent claims support; unrelated claims stay disconnected; planner dependencies map to edges |
| `ConsensusDagWorkflowTest` | the full pipeline on pure V1 replay mechanics with REAL verifiers: 2-claim plan → 2-solver fan-out → 4-verifier fan-out → resolution → narration; replay-identical |
| `V6OffPathTest` | **OFF by default** for fresh/unknown/null developers; explicit opt-in flips the gate; opt-out restores it |

Runtime-verified against PostgreSQL: V8 migrates; OFF ⇒ legacy responses and
zero `dag_runs`; ON ⇒ the same gateway endpoint returned a verified answer in
2.5s — 12 activities (1 plan + **3 parallel solvers** + **7 parallel
verifiers** + 1 synthesis) all as `ACTIVITY_SCHEDULED`/`ACTIVITY_COMPLETED`
events, confidence 94.4% (LOW uncertainty), 13-node/20-edge trace projected;
disable ⇒ legacy path restored with the run count unchanged.
