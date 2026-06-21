# Architecture & Design Rationale

This document explains *why* Continuum is built the way it is: the execution
model, the data design, the failure modes it defends against, and the tradeoffs
taken. It is written for a reader evaluating the distributed-systems thinking,
not just the feature list.

---

## 1. The central idea: replay-based durable execution

A workflow is **not** stored as "current state = step 3". It is stored as an
append-only log of events. To make progress, the engine:

1. Loads the workflow's event history.
2. **Replays the workflow code from the top.** Each call to
   `ctx.executeActivity(...)` checks the history:
   - result present → return it (no execution),
   - permanently failed → throw into the workflow,
   - scheduled but pending → suspend,
   - brand new → emit a "schedule this activity" command and suspend.
3. Persists whatever the run decided (record side effects / schedule the next
   activity / complete / fail) in a single transaction.

When an activity completes, a `workflow_task` is enqueued and the loop runs
again — replaying further this time, because one more result is in the log.

This is the same model as Temporal/Cadence, reduced to its essence. The key
property: **a decision is a pure function of (input, history)**, so re-running it
is always safe. Crash recovery is therefore not a special code path — it is just
"run the decision again".

### Why replay instead of saving a continuation?

Saving a thread/continuation (call stack, locals) is fragile across deploys and
JVM versions and hard to make portable. Replaying deterministic code needs only
the event log. The cost is that workflow code must be deterministic and is
re-executed on each step; for orchestration logic (cheap, side-effect-free) this
is a good trade.

### Suspension mechanism

There are no threads or coroutines. The workflow function runs to the first
unresolved activity and throws an internal `WorkflowBlockedException` to unwind
the stack. The engine catches it and persists the batch of commands the run
produced. Non-deterministic values (`now()`, `randomUuid()`, `sideEffect`) are
captured once and replayed, so the function stays pure.

---

## 2. Component model

| Component | Responsibility |
| --- | --- |
| `EventStore` | Append events under a per-workflow lock; load history |
| `WorkflowExecutor` | Pure replay → produce a `Decision` (no I/O — unit-tested) |
| `WorkflowEngine` | Start workflows; turn decisions into durable state changes |
| `TaskClaimer` | `FOR UPDATE SKIP LOCKED` claims for all queues |
| `WorkflowWorker` | Poll decisions, run them |
| `ActivityWorker` / `ActivityExecutor` | Run activities; record completion/failure; retries |
| `RecoverySweeper` | Reclaim tasks abandoned by crashed workers |
| `OutboxDispatcher` + `OutboxSink`s | Deliver external messages exactly once |
| `ProviderRouter` + `LlmProvider`s | Provider-agnostic LLM calls with failover |

The separation between `WorkflowExecutor` (pure) and `WorkflowEngine`
(persistence) is deliberate: the determinism guarantee — the riskiest part — is
testable with no database and no Spring.

---

## 3. Data design

### `workflow_events` — the source of truth
Append-only. `UNIQUE(workflow_id, sequence_number)` gives a gap-free total order
per workflow. Everything else is a projection that could be rebuilt from here.

### `workflow_instances` — projection + serialization point
Holds status/result and the `current_sequence` cursor. A **pessimistic write
lock** on this row is taken on every event append. That single lock is the
per-workflow serialization point: it guarantees sequence numbers are monotonic
and that a decision and an activity completion for the *same* workflow cannot
interleave. Different workflows never contend, so throughput scales with the
number of distinct workflows.

### `activity_tasks` / `workflow_tasks` — the durable queues
Claimed with `SELECT … FOR UPDATE SKIP LOCKED LIMIT n`: concurrent workers each
pull disjoint work without blocking. A claim flips the row to `RUNNING` and sets
`locked_until` (a visibility timeout). `UNIQUE(workflow_id, sequence_number)` on
`activity_tasks` makes scheduling idempotent — the same activity can never be
enqueued twice.

### `outbox` — transactional outbox
Written in the same transaction as the completion event. `UNIQUE(idempotency_key)`
guarantees a logical message exists at most once. The dispatcher delivers and
marks `SENT`.

### `llm_cost_records` — accounting
Deduplicated by idempotency key so retries/replays never double-count cost.

---

## 4. Correctness guarantees & how they hold

| Guarantee | Mechanism |
| --- | --- |
| No lost workflow progress | Every step is a committed event before the next is scheduled |
| No divergence on recovery | Deterministic replay; recorded activity results + side effects |
| No duplicate activity *records* | `UNIQUE(workflow_id, sequence_number)` + instance lock |
| No duplicate external side effects | Outbox + `UNIQUE(idempotency_key)`, deterministic keys |
| Crashed-worker progress | Visibility timeout + recovery sweeper re-queues |
| Exactly-once delivery | Outbox written atomically with event; unique key on delivery |
| Cost integrity | Cost rows keyed by idempotency key |

---

## 5. Failure modes considered

- **Worker dies mid-activity.** Task stays `RUNNING` until `locked_until`; the
  sweeper resets it to `PENDING`; another worker re-runs it. Side effects are
  idempotent, so re-execution is safe. *(Demonstrated by the kill-`-9` demo.)*
- **Worker dies mid-decision.** Decisions are pure replay; the `workflow_task`
  is reclaimed and re-run with no effect duplication.
- **Activity succeeds but the commit fails (crash between work and persist).**
  The completion transaction never committed, so on recovery the activity runs
  again. This is why side effects must be idempotent (outbox) — the system is
  *at-least-once execution, exactly-once effect*.
- **Two workers claim two `workflow_task`s for the same workflow.** The instance
  write lock serializes them; the second sees no new work (BLOCKED) or an
  already-scheduled activity (idempotent).
- **Provider timeout/outage.** `ProviderRouter` fails over to the next provider
  within the same activity attempt; if all fail, normal activity retry/backoff
  applies; if still failing, the workflow observes a permanent failure.
- **Outbox dispatcher crashes mid-send.** Message stays `PENDING` (or becomes
  visible again) and is retried; the unique key prevents a double-enqueue, and
  sinks are expected to be idempotent on the key.
- **Non-deterministic workflow code.** A bug that branches on wall-clock or RNG
  would diverge on replay. Mitigated by forcing all non-determinism through
  `ctx` (recorded), and by failing the workflow (rather than looping) if its
  code throws.

---

## 6. Tradeoffs and deliberate non-goals

- **Sequential activities per workflow.** The model schedules one activity at a
  time per workflow. This keeps replay simple and correct. Parallel fan-out
  (scheduling multiple activities from one decision) is a natural extension: emit
  several `ScheduleActivity` commands and treat the workflow as blocked until all
  resolve. Not implemented to keep the core small.
- **Polling, not `LISTEN/NOTIFY`.** Workers poll every 500 ms. Simple and
  robust; adds a little latency. `LISTEN/NOTIFY` or long-polling is a drop-in
  optimization if latency matters.
- **Postgres for the queue.** Deliberately *not* Kafka/Redis. A single Postgres
  gives transactions spanning the event log, queue and outbox — which is exactly
  what the exactly-once guarantees need. Introducing a broker would add a
  second source of truth and dual-write problems. Scale Postgres first; reach for
  a broker only at a proven bottleneck.
- **Per-workflow lock.** Serializes a single workflow's events. Fine because
  workflows are inherently sequential here; cross-workflow concurrency is
  unbounded.
- **Replay cost grows with history length.** For very long histories, the
  standard remedy is *continue-as-new* (snapshot + fresh history). Not needed at
  this scale; called out as the known scaling edge.

---

## 7. Extension points

- **New LLM provider:** implement `LlmProvider`, add to `LLM_FAILOVER_ORDER`.
- **New activity:** implement `Activity` (`@Component`); auto-registered.
- **New workflow:** implement `Workflow` (`@Component`); auto-registered.
- **New side-effect channel:** implement `OutboxSink` for a new `destination`.
- **Parallel activities / timers / signals:** extend the command set produced by
  a decision and the corresponding event types.
