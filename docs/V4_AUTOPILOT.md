# Continuum V4 — Autopilot (Autonomous Control Plane)

Autopilot is an **optional, opt-in, per-developer** intelligence layer that
safely tunes a developer's AI runtime — routing, fallback, hedging, budgets and
timeouts — by learning from real telemetry. It behaves like an AI SRE.

> **Non-negotiable compatibility guarantee:** Autopilot is **OFF by default**.
> When off (or before any policy is activated), the gateway runs its exact
> pre-Autopilot path. Verified by `PolicyResolverTest` and at runtime (labels
> table stays empty, request behaviour unchanged). Additive only — no existing
> table, API, workflow, replay path or dashboard view is modified.

## Closed loop

```
Observe ── TelemetryAggregator (real gateway/replay data)
  → Infer ── Bayesian success estimates + drift
  → Propose ── DecisionEngine (Thompson-sampling contextual bandit + constrained optimization)
  → Verify ── PolicyVerifier (bounded values, capability/golden, allow-list)
  → Canary ── PolicyResolver splits a % of traffic; CanaryEvaluator (Bayesian) compares
  → Promote / Rollback ── automatic on confident improvement / regression
  → Learn ── next tick uses updated arm posteriors
```
Driven by `AutopilotLoop` (`@Scheduled`, gated: only enabled developers incur work).

## What it may change (bounded policy surfaces only)

A **versioned, content-immutable `PolicyBundle`**: routing mode, provider order,
max retries, hedge threshold + fan-out, semantic-drift threshold, canary %, cost
cap, latency cap, memory-compression threshold, verification threshold, timeout.
It **never** touches workflow code, provider internals, secrets, security rules,
schema (except via migrations) or application code.

## Decision engine (real, not if/else)

- **Contextual bandit / Thompson sampling** — each provider is an arm with a
  `Beta(successes+1, failures+1)` posterior; utilities sampled and blended with
  normalized cost/latency under the mode's weights; providers ranked by expected
  utility over 400 samples (stable for separated arms).
- **Bayesian failure estimation** — `BetaDistribution` gives posterior means and
  95% credible bounds, used for canary promote/rollback decisions.
- **Constrained optimization** — providers breaching the per-request cost/latency
  budget are excluded (never all; a fallback is always kept).
- **Latency-aware hedge tuning** — hedge threshold moves toward the observed p95
  vs the latency budget.

## Verification, canary & rollback

- **Verify** before any candidate touches traffic: bounded values, provider
  allow-list, and every provider must have an ACTIVE registered model (golden
  check). A failing candidate is never promoted.
- **Canary** via `PolicyResolver`: a bounded random fraction of requests are
  served by the candidate; `autopilot_request_labels` attributes each request to
  its bundle (without altering `gateway_requests`).
- **Promote** only when the candidate is *confidently* ≥ baseline (its 95% lower
  bound clears the baseline mean). **Rollback** automatically on a confident
  regression in success, latency (>1.5×) or cost (>1.5×), reverting to the
  known-good ACTIVE bundle. Manual rollback is also available.

## Modes

`BALANCED`, `LOW_COST`, `LOW_LATENCY`, `HIGH_QUALITY`, `SAFETY_FIRST` — each maps
to cost/latency/quality weights the engine optimizes under.

## Database (migration `V5`, additive)

`autopilot_config` (opt-in state, profile, active/canary pointers),
`autopilot_policy_bundles` (immutable versions = history + rollback targets),
`autopilot_telemetry`, `autopilot_decisions`, `autopilot_recommendations`,
`autopilot_canary_runs`, `autopilot_rollbacks`, `autopilot_feedback`,
`autopilot_request_labels`.

## API (session-scoped under `/api/portal/developer/autopilot`)

```
GET  /status                         current state, profile, active policy, live telemetry
POST /enable    {profile}            opt in (creates initial ACTIVE bundle; suggest-only by default)
POST /disable                        opt out (bundles preserved)
PUT  /auto-apply?value=              autonomous canary+promote vs suggest-only
PUT  /profile   {profile}            update goal/budgets/providers/mode
POST /propose                        run one loop iteration now
GET  /bundles                        version history
GET  /active-bundle
GET  /recommendations                pending proposals
POST /recommendations/{id}/accept    start a canary for the proposal
POST /recommendations/{id}/reject
POST /canary/start/{bundleId}
GET  /canary                         canary runs + metrics
POST /rollback                       manual rollback to previous policy
GET  /rollbacks
GET  /decisions?limit=               audit log
POST /feedback  {requestRef,score,comment}
```

## UI (Developer → **Autopilot** tab)

Beginner-friendly: prominent ON/OFF toggle, a 3-step onboarding wizard (app +
objective → budgets → providers), "What does Autopilot do?" explainer cards,
current-policy card, recommendations panel with Accept/Dismiss + confidence
badges, canary progress meters, policy-history timeline, decision log, rollback
events, and safety indicators. Tasteful CSS transitions only.

Config: `continuum.autopilot.loop-enabled` (default true),
`continuum.autopilot.loop-interval-ms` (default 30000).

## Tests (part of the 53-test suite, all green)

| Test | Proves |
| --- | --- |
| `BetaDistributionTest` | posterior mean/bounds, interval shrinks with data, valid sampling |
| `DecisionEngineTest` | reliable provider ranked first; LOW_LATENCY prefers faster; hedge tightens over budget |
| `CanaryEvaluatorTest` | continue < min-samples; promote when confidently better; rollback on success/latency/cost regression |
| `PolicyVerifierTest` | rejects unknown/allow-list-violating/no-active-model bundles |
| `PolicyResolverTest` | **OFF ⇒ empty ⇒ unchanged gateway path** (the compatibility guarantee) |

Runtime-verified end to end against PostgreSQL: OFF adds zero labels/overhead;
enable creates v1 ACTIVE bundle; traffic is attributed per bundle; the loop
proposes a real hedge-threshold optimization as a recommendation; accepting
starts a 10% canary that splits traffic; the Bayesian evaluator gathers data and
records a verdict; disable preserves bundles and restores exact prior behaviour.
