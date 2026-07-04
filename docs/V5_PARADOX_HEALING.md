# Continuum V5.1.1 — Determinism Divergence Auto-Healing (Paradox Resolution Engine)

Event-sourced runtimes have one dominant operational failure mode: **deploying
changed workflow code over in-flight instances**. The replay of an active
instance is keyed by command sequence; if the new code inserts, removes or
reorders an activity, the code path diverges from the append-only
`workflow_events` record — classically a fatal mismatch (or worse, a *silent*
misassignment of one activity's recorded result to another).

The Paradox Resolution Engine makes this a non-event. It is **compulsory,
always on, with no toggle**: an omnipresent infrastructure guarantee inside the
core decision loop. Code-graph updates become self-correcting instead of
system-breaking.

## The closed healing pipeline

```
            WorkflowEngine.processDecision (single @Transactional, instance row lock held)
            ────────────────────────────────────────────────────────────────────────────
   history ──► ParadoxResolutionService.openSession
                  │  (recorded ACTIVITY_SCHEDULED types + occupied command seqs
                  │   + durable ledger from workflow_healing_logs)
                  ▼
             SequenceAlignmentSession  ⇐ injected into WorkflowExecutor as ReplayAligner
                  │
     replay ──►  each executeActivity/sideEffect call is translated:
                  ├─ ledger mapping exists ............ reuse it verbatim (deterministic)
                  ├─ recorded type matches ............ identity (pristine path, 0 writes)
                  └─ DIVERGENCE INTERCEPTED
                       ├─ type found later in history .. DELETION_SKIPPED (cursor shifts past
                       │                                  the obsolete record; result orphaned)
                       ├─ type found earlier unconsumed . REORDER_ALIGNED (recorded result reused)
                       └─ type nowhere in history ....... INSERTION_MAPPED (fresh virtualized
                                                          slot ⇒ brand-new idempotency key)
                  ▼
             commitResolutions ── ledger rows written in the SAME transaction as the
                                  decision's own events (atomic, race-free)
```

## Guarantees

- **No crash, no lock-up** — structural mismatches are translated before they
  can throw; the worker keeps the instance write lock and progresses normally.
- **Exactly-once preserved** — a virtualized insertion always receives a fresh,
  never-used command sequence, hence a fresh `workflowId:seq` idempotency key:
  the transactional outbox and `llm_cost_records` can never be double-triggered
  by healing. Skipped records are orphaned, never re-executed.
- **Deterministic forever** — every resolution is committed to
  `workflow_healing_logs` atomically with the decision; all future replays load
  the ledger first, so a healed instance replays identically every time
  (verified: re-verification of a healed instance reports zero divergences).
- **Monotonic sequences untouched** — the engine never edits `workflow_events`
  or the `current_sequence` cursor; the existing `FOR UPDATE` instance lock
  remains the single serialization point, and ledger writes happen under it.
- **Zero pristine overhead** — for unchanged code the aligner is a map lookup +
  string compare per command; no rows are written, behavior is bit-identical
  (the `ReplayAligner.IDENTITY` default keeps every pre-existing code path and
  test untouched).
- **Bounded state** — the ledger holds at most one compact row per diverged
  command sequence per instance, indexed by `workflow_id`; nothing unbounded is
  kept in memory.

## Database (Flyway `V6`, strictly additive)

- `workflow_healing_logs` — the per-instance resolution ledger:
  `id`, `workflow_id` (FK → `workflow_instances`, indexed),
  `divergence_sequence_number`, `resolution_type`
  (`INSERTION_MAPPED` / `DELETION_SKIPPED` / `REORDER_ALIGNED`),
  `virtualized_payload_json` (the structural bridge: codeSeq → historySeq +
  cursor delta), `created_at`. Unique per (workflow, divergence seq).
- `autopilot_divergence_resolutions` — denormalized observability feed for the
  control plane and dashboard.

## APIs (V3 control plane, `/api/gateway/healing`)

```
GET  /api/gateway/healing/status                  engine metrics: totals, by type, healed workflows, timeline
GET  /api/gateway/healing/workflow/{workflowId}   the detailed structural patch ledger for one instance
POST /api/gateway/healing/verify                  dry-run code-to-history validation scan
                                                  body {"workflowId": "..."} or {} to scan running instances
```

## UI

- **Gateway tab** — a prominent **"Paradox Resolution Ledger"** panel: live
  counters (paradoxes resolved, healed workflows, per-resolution-type), a
  real-time auto-healing timeline with `PARADOX RESOLVED` / `HISTORY ALIGNED`
  badges, and a one-click verification scan.
- **Workflow detail** — healed instances carry `PARADOX RESOLVED` and
  `HISTORY ALIGNED ×N` badges plus their full per-instance ledger.

## Tests (12 new, all green; full suite 65/65)

| Test | Proves |
| --- | --- |
| `SequenceAlignmentSessionTest` | identity = zero mappings; insertion virtualized past all used seqs; deletion skips without re-execution; reorder reuses recorded results; persisted ledger short-circuits deterministically; shifted side effects never collide with activity slots |
| `ParadoxHealingReplayTest` | an instance started on old code **completes** after the code graph is altered (insert + delete cases); side effects captured exactly once across the deploy; idempotency keys never re-enqueued (outbox/cost safety); healed replays are byte-identical and add no ledger rows; unchanged workflows produce zero healing state |
| `ParadoxResolutionServiceTest` | resolutions written to both ledger tables inside the caller's decision transaction; pristine decisions write nothing |

Runtime-verified against PostgreSQL: a mid-flight `DurableDemo` instance whose
recorded history was made to look like an old deploy (its first step renamed to
a type the current code no longer contains) **auto-healed with
`DELETION_SKIPPED` and completed** — 4 scheduled activities, 4 distinct
idempotency keys, and a follow-up verification scan reporting
`diverged: false`. Pristine workflows run with `healed: false` and zero ledger
rows.
