# Continuum V5 — God Mode (Autonomous Memory & Policy Engine)

God Mode is an **opt-in, per-developer, OFF-by-default** autonomous intelligence
layer on top of the Continuum gateway: a 4-tier learned memory hierarchy, a
Memory-as-Action (MemAct) policy, and a **digital twin** that proves every
policy change offline — against the developer's real historical traffic —
before it can touch production.

> **Compatibility contract (mirrors V4's PolicyResolver):**
> `GodModeService.configIfEnabled()` returns empty unless the developer
> explicitly opted in, and every hook treats empty as "do exactly what you did
> before God Mode existed." Verified by `GodModeOffPathTest` and live
> (disabled ⇒ gateway responses unchanged, zero memory rows written).

## Architecture

```
                       ┌────────────────────────── God Mode (opt-in) ──────────────────────────┐
 Gateway request ──────┤ observeExchange (no-op when off, can never throw)                     │
        │              │        │                                                              │
        ▼              │        ▼                                                              │
   V3 routing          │  ┌───────────┐  MemAct(Thompson) ┌───────────┐  distill  ┌─────────┐ │
   V4 policy           │  │  WORKING  │──── summarize ───▶│ EPISODIC  │──────────▶│SEMANTIC │ │
   V5.1.1 healing      │  │ (minutes) │   (deterministic  │  (days)   │  (dedup + │  GRAPH  │ │
   (all unchanged)     │  └───────────┘    extractive)    └───────────┘   edges)  │ (months)│ │
        │              │                                                          └────┬────┘ │
        ▼              │                                                        decay  ▼      │
    response           │                                                          ┌─────────┐ │
                       │                                                          │ ARCHIVE │ │
 V4 startCanary ───────┤ TwinCanaryPreflight: offline replay must not             └─────────┘ │
   (gated hook)        │ confidently regress, or the canary is vetoed                         │
                       └──────────────────────────────────────────────────────────────────────┘
```

## Architectural conflict resolutions (per directive)

1. **Bandit/canary NOT rebuilt.** `MemActEngine` (in `io.continuum.autopilot.engine`)
   extends the V4 engine family: memory actions are arms with the same
   `BetaDistribution` Beta(s+1, f+1) posteriors, selected by Thompson sampling.
   The digital twin's verdicts come from the **unmodified V4 `CanaryEvaluator`**
   — identical Bayesian promote/rollback rules offline and live.
2. **Chaos NOT rebuilt.** The twin's `HALLUCINATION_STORM` scenario corrupts
   realistic decision outputs with the **V2 `HallucinationInjector`** and scores
   detection with the **V2 `SemanticComparator`** — real corruption, real
   detection scoring, entirely offline (no chaos rates mutated, no live traffic).
3. **V2 Extension 5 upgraded, not replaced.** The original in-memory
   `MemoryService` continues working untouched; God Mode adds the durable
   4-tier system with the experience graph (`memory_experience_nodes/edges`)
   via Flyway **V7**.

## The memory engine (Memory-as-Action)

- **Working** (TTL minutes): raw gateway exchanges, ingested transparently by a
  single gated hook in `GatewayService` (no-op when off, never throws).
- **Episodic** (TTL days): produced by a deterministic TextRank-style extractive
  `Summarizer` — no LLM call, no replay non-determinism, real compression
  (measured `tokens saved` is the MemAct reward).
- **Semantic experience graph** (TTL months): dense episodes distilled into
  `EXPERIENCE` nodes with hashing-trick embeddings; near-duplicates reinforce
  existing nodes instead of duplicating (interference-based forgetting);
  similarity edges form the graph; retrieval bumps node utility.
- **Archive**: expired/decayed experiences compressed into cold spans.

**MemAct**: `SUMMARIZE_NOW` vs `DEFER` (weighted by live context pressure),
`PRUNE_DUPLICATES`, `PROMOTE_EXPERIENCE`, `ARCHIVE_COLD` — each a Thompson arm
updated from realized outcomes. Live-verified: at 1.4% context fill the bandit
deferred; a later tick summarized 6 items → 1 episode and dropped the
off-topic sentence.

**Bounds**: every row carries `expires_at`; per-tenant quotas
(`max_working_items`, `max_episodic_items`, `max_semantic_nodes`); every repo
query is developer-scoped; developers can wipe their own memory
(`DELETE /memory`). No cross-tenant sharing, ever.

## The digital twin (counterfactual simulator)

Off-policy evaluation before live traffic: fits per-provider Beta posteriors +
latency/cost models from the developer's real `gateway_requests`, then
bootstrap-replays candidate vs baseline policy (provider order, retries,
budgets) with seeded, reproducible RNG. Scenarios:

- `HISTORICAL_REPLAY` — pure off-policy A/B on measured behavior.
- `PROVIDER_OUTAGE` — the most-used provider is degraded to stress fallbacks.
- `HALLUCINATION_STORM` — V2-injected corruptions scored by V2 semantics:
  would this bundle's verification threshold have caught them?

**Autopilot integration** (the one gated V4 hook): `AutopilotService.startCanary`
consults an optional `CanaryPreflight`. God Mode's `TwinCanaryPreflight` runs a
`HISTORICAL_REPLAY`; a **confident offline regression vetoes the canary**
(bundle archived, `TWIN_VETO` decision recorded). God Mode off / twin failure /
non-veto ⇒ the exact V4 canary path.

## Database (Flyway V7, strictly additive)

`god_mode_config` (opt-in toggle + budgets; a dedicated table FK'd to
`developers` rather than a column on the security-critical `developer_auth`),
`memory_working`, `memory_episodic`, `memory_experience_nodes`,
`memory_experience_edges`, `memory_archives`, `god_mode_simulations`,
`god_mode_actions` (the full MemAct audit trail).

## API (session-scoped under `/api/portal/developer/godmode`)

```
GET    /status                 toggle state, budgets, live tier snapshot, MemAct posteriors
POST   /enable | /disable      opt in / out (off restores the exact prior path)
PUT    /settings               memact / twin-gate toggles, context budget
POST   /memory/ingest          add to working memory
POST   /memory/consolidate     run one consolidation tick now
POST   /memory/retrieve        relevance-ranked recall across episodic+semantic
GET    /memory/graph           the experience graph (nodes + weighted edges)
DELETE /memory                 wipe all tiers (privacy control)
GET    /actions                autonomous action audit feed
POST   /twin/simulate          {scenario, candidateBundleId?, baselineBundleId?}
GET    /twin/simulations       recent runs with metrics + verdicts
```

Autonomous loop: `continuum.godmode.loop-enabled` (default true),
`continuum.godmode.loop-interval-ms` (default 60000) — iterates only enabled
developers; with none enabled a tick is a single indexed SELECT.

## UI overhaul (V5)

Theme-wide glow-up: aurora/neon palette, ambient gradient field, glass panels,
gradient branding, animated nav, slim scrollbars, micro-interactions. New
**⚡ Agentic Autopilot** tab:

- A **God Mode toggle** that looks the part (gradient ring, glow pulse when on)
  with a 3-step explainer wizard when off.
- **Context Weight gauge** — SVG arc shifting green→red with fill, "pinching"
  when episodic summarization compresses the working tier.
- **Memory hierarchy flow** — four live tier cards joined by shimmering flow
  arrows, plus ingest/retrieve/wipe controls.
- **Counterfactual Multiverse** — branching timeline that grows candidate
  universes while a simulation runs and collapses to the surviving branch on
  the verdict, with PROMOTE/ROLLBACK/CONTINUE badges.
- **Experience graph** — radial constellation with utility-scaled glowing nodes.
- **MemAct panel** — Thompson posterior bars and the autonomous action feed.
- Glowing **token-streaming indicator** (LIVE/IDLE).

## Tests (15 new; full suite 80/80 green)

| Test | Proves |
| --- | --- |
| `GodModeOffPathTest` | **OFF ⇒ every hook is a strict no-op** (no config/disabled/exception paths); twin preflight allows everything when off; memory failures never propagate to requests |
| `MemActEngineTest` | full context ⇒ summarize; repeated zero-reward ⇒ learns to defer; posterior snapshot |
| `SummarizerAndEmbedderTest` | deterministic real compression preserving the central topic; embeddings separate topics and flag near-duplicates |
| `DigitalTwinSimulatorTest` | flaky-provider candidate ROLLBACK'd offline; identical policies never vetoed; seeded determinism; strict verification threshold detects more V2-injected hallucinations |

Runtime-verified against PostgreSQL: V7 migrates; off-by-default; enable →
ingest → MemAct defer-then-summarize → episodic retrieval; gateway observe hook
fed 12 chats → 24 working items; all three twin scenarios produced Bayesian
verdicts over 48 replayed requests; accepting an Autopilot recommendation
auto-ran the twin preflight (recorded simulation, canary allowed → RUNNING);
disabling God Mode restored the exact gateway path with zero memory writes.
