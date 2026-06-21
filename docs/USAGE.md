# Usage Guide — Every Feature, End to End

This guide walks through every capability of Continuum: defining and running
workflows, the REST API, the dashboard, and copy-paste chaos demos that prove
the durability guarantees.

Assumes the backend is on `http://localhost:8080`. Examples use `curl` + `jq`.

---

## 1. Concepts in 60 seconds

- **Workflow** — deterministic orchestration code. Decides *what* happens next.
  Re-run (replayed) from its event history on every step; must be a pure
  function of input + history.
- **Activity** — a unit of non-deterministic, possibly-failing work (LLM call,
  email, payment, DB read). The *only* place side effects and randomness live.
- **Event log** — the append-only source of truth. State is always rebuilt by
  replaying it.
- **Task queues** — durable Postgres-backed queues for workflow decisions and
  activities, claimed with `FOR UPDATE SKIP LOCKED`.
- **Outbox** — external messages written atomically with events and delivered
  exactly once.

---

## 2. Built-in workflows

| Type | Steps | Purpose |
| --- | --- | --- |
| `DurableDemo` | echo ×3 + captured timestamp | Phase-0 durability demo (no AI) |
| `CustomerAnalysis` | fetch → AI analysis → AI report → email → DB update | Full agentic workflow |

### Start a workflow

```bash
curl -s -X POST localhost:8080/api/workflows \
  -H 'Content-Type: application/json' \
  -d '{"workflowType":"CustomerAnalysis","input":{"customerId":"C-1001"}}' | jq
# → { "workflowId": "…", "status": "RUNNING" }
```

Provide your own `workflowId` to make **starts idempotent** (re-sending the same
id is a no-op, not a duplicate run):

```bash
curl -s -X POST localhost:8080/api/workflows \
  -d '{"workflowType":"DurableDemo","workflowId":"order-42","input":{"name":"x"}}'
```

### Inspect a workflow

```bash
curl -s localhost:8080/api/workflows/<id> | jq
```

Returns the summary, input, result, the full **event timeline**, the
**activities**, the **outbox** messages, and **token/cost** totals.

---

## 3. Writing your own workflow

Implement `Workflow` (deterministic) and any `Activity` you need. Spring
auto-registers both via component scanning.

```java
@Component
public class GreetingWorkflow implements Workflow {
    public String type() { return "Greeting"; }

    public Object execute(WorkflowContext ctx) {
        var in = ctx.input(Input.class);

        // Activity result is recorded; on replay it is returned, not re-run.
        var greeting = ctx.executeActivity(
                LlmActivity.TYPE,
                new LlmActivity.Input("You are friendly.", "Greet " + in.name(), null, 128, 0.7),
                ActivityOptions.defaults().maxAttempts(3).timeoutSeconds(30),
                LlmActivity.Output.class);

        // Idempotent side effect via the outbox.
        ctx.executeActivity(EmailActivity.TYPE,
                new EmailActivity.Input(in.email(), "Hello", greeting.content()),
                EmailActivity.Output.class);

        return Map.of("said", greeting.content());
    }
    public record Input(String name, String email) {}
}
```

Determinism rules:

- Never call `Instant.now()`, `UUID.randomUUID()`, RNGs, or do I/O directly in
  workflow code. Use `ctx.now()`, `ctx.randomUuid()`, or `ctx.sideEffect(...)`,
  which record the value once and replay it.
- All real work goes through `ctx.executeActivity(...)`.

### Writing an activity

```java
@Component
public class SlackActivity implements Activity {
    public String type() { return "slack.post"; }

    public Object execute(String inputJson, ActivityContext ctx) {
        var in = ctx.input(inputJson, Input.class);
        // For side effects, enqueue to the outbox (exactly-once delivery):
        ctx.enqueueOutbox("notification", "SlackPost", in);
        return Map.of("queued", true);
    }
    public record Input(String channel, String text) {}
}
```

`ctx.idempotencyKey()` is `workflowId:commandSeq` — deterministic and stable
across retries/replays, which is what makes outbox delivery exactly-once.

---

## 4. Event sourcing & the timeline

Every fact is an event. A completed `CustomerAnalysis` looks like:

```
WORKFLOW_STARTED
ACTIVITY_SCHEDULED  → customer.fetch
ACTIVITY_STARTED
ACTIVITY_COMPLETED
ACTIVITY_SCHEDULED  → llm.complete   (analysis)
ACTIVITY_STARTED
ACTIVITY_COMPLETED
ACTIVITY_SCHEDULED  → llm.complete   (report)
...
ACTIVITY_SCHEDULED  → email.send
ACTIVITY_COMPLETED
ACTIVITY_SCHEDULED  → customer.update
ACTIVITY_COMPLETED
WORKFLOW_COMPLETED
```

```bash
curl -s localhost:8080/api/workflows/<id>/events | jq -r '.[] | "\(.sequenceNumber) \(.eventType)"'
```

The dashboard renders this as an icon timeline with payload previews.

---

## 5. Deterministic replay (AI is never re-called)

When a workflow resumes, the engine replays its code against the event history.
Activities already in the log (including LLM outputs) are returned from the
record — the model is **not** called again, so:

- the workflow can't diverge,
- you don't pay twice,
- recovery is free of duplicate effects.

This is verified by `WorkflowExecutorTest` (`mvn test`), which asserts a side
effect runs exactly once across an entire execution and that replaying the same
history yields an identical decision.

---

## 6. Crash recovery (the flagship demo)

```bash
# 1. Slow activities so we can kill mid-step
curl -s -X POST "localhost:8080/api/chaos/activity-latency?ms=2500"

# 2. Start a workflow
WID=$(curl -s -X POST localhost:8080/api/workflows \
      -d '{"workflowType":"DurableDemo","input":{"name":"crashtest"}}' | jq -r .workflowId)

# 3. Let it get partway, then HARD-KILL the backend (kill -9)
sleep 4
kill -9 $(pgrep -f continuum.jar)

# 4. Restart the backend, then disable latency
curl -s -X POST "localhost:8080/api/chaos/activity-latency?ms=0"

# 5. Watch it resume and finish — no repeated steps, same captured timestamp
curl -s localhost:8080/api/workflows/$WID | jq '.summary.status, .result'
```

What happened: the dead worker's in-flight activity was left locked; the
**recovery sweeper** reclaimed it after its visibility timeout; another worker
re-ran the decision (pure replay), returned the already-completed steps from the
log, and continued. The captured `startedAtMillis` is byte-for-byte identical
because it was recorded as a side effect.

---

## 7. Idempotency & the transactional outbox

Email/payment activities don't call external systems inline. They write an
outbox row in the **same transaction** as the `ACTIVITY_COMPLETED` event (no
lost messages), keyed by a unique idempotency key (no duplicates). A dispatcher
delivers each message exactly once.

Prove it under a flaky sink:

```bash
curl -s -X POST "localhost:8080/api/chaos/sink-failure-rate?rate=0.8"
for c in A B C; do
  curl -s -X POST localhost:8080/api/workflows \
    -d "{\"workflowType\":\"CustomerAnalysis\",\"input\":{\"customerId\":\"$c\"}}" >/dev/null
done
# wait, then check integrity — duplicateDeliveries MUST be empty:
curl -s localhost:8080/api/stats | jq
curl -s localhost:8080/api/deliveries | jq
curl -s -X POST localhost:8080/api/chaos/reset
```

Each delivery carries a `deliveryCount` (always 1) and the global stats expose
`duplicateDeliveries` (always `[]`).

---

## 8. Retries & retry policies

Per activity, the scheduling workflow sets `maxAttempts` and `timeoutSeconds`:

```java
ctx.executeActivity(type, input,
    ActivityOptions.defaults().maxAttempts(5).timeoutSeconds(30), Out.class);
```

Failed attempts are retried with **exponential backoff** (2s, 4s, 8s, … capped
at 5 min) and recorded as `ACTIVITY_FAILED` / `RETRY_SCHEDULED` events. After
the last attempt the activity fails permanently and the workflow observes it
(and may branch or fail).

Demo:

```bash
curl -s -X POST "localhost:8080/api/chaos/activity-failure-rate?rate=0.5"
# start workflows; watch retry_count climb in the activities panel, then succeed
curl -s -X POST "localhost:8080/api/chaos/activity-failure-rate?rate=0"
```

---

## 9. Provider abstraction & AI failover

LLM calls go through a provider-agnostic model (`Message`/`LlmRequest`/
`LlmResponse`) and an ordered failover chain. With real keys configured:

```bash
curl -s localhost:8080/api/meta | jq .providerFailoverChain
# e.g. ["gemini","groq","mock"]

# Force the primary down → the next LLM call transparently fails over to Groq
curl -s -X POST "localhost:8080/api/chaos/provider-down?down=true"
curl -s -X POST localhost:8080/api/workflows \
  -d '{"workflowType":"CustomerAnalysis","input":{"customerId":"FAILOVER-1"}}'
# the ACTIVITY_COMPLETED / cost record shows provider="groq"
curl -s -X POST "localhost:8080/api/chaos/provider-down?down=false"
```

The workflow above never knows a provider failed — failover happens inside the
activity. New providers (OpenAI, Anthropic, DeepSeek) are added by implementing
`LlmProvider`; no workflow changes required.

---

## 10. Token & cost tracking

Each LLM call records provider, model, prompt/completion tokens and estimated
USD cost (deduplicated by idempotency key, so retries never double-count).

```bash
curl -s localhost:8080/api/costs | jq
# { "totalCostUsd": …, "totalTokens": …, "byProvider": [ … ] }
```

Per-workflow cost/tokens are in the workflow detail (`costUsd`, `tokens`) and on
the dashboard.

---

## 11. Chaos Lab (API + dashboard)

| Endpoint | Effect |
| --- | --- |
| `POST /api/chaos/provider-down?down=true\|false` | Force primary LLM provider down |
| `POST /api/chaos/crash-after?activities=N` | Simulate a worker crash during the Nth activity |
| `POST /api/chaos/activity-failure-rate?rate=0..1` | Randomly fail activities |
| `POST /api/chaos/sink-failure-rate?rate=0..1` | Randomly fail outbox deliveries |
| `POST /api/chaos/activity-latency?ms=N` | Inject latency into activities |
| `POST /api/chaos/reset` | Clear all fault injection |
| `GET  /api/chaos` | Current chaos state |

The **Chaos Lab** tab in the dashboard exposes all of these as buttons.

---

## 12. The dashboard

`http://localhost:5173` (dev) or your Vercel URL.

- **Dashboard:** live stats (running/completed/failed, deliveries, duplicate
  count), a start-workflow form, the workflow list, and a cost panel.
- **Workflow detail:** the event timeline, activities (with retry counts), the
  outbox (with delivery status/attempts), cost, and the final result.
- **Chaos Lab:** one-click fault injection.

---

## 13. REST API reference

| Method | Path | Description |
| --- | --- | --- |
| `POST` | `/api/workflows` | Start a workflow `{workflowType, input, workflowId?}` |
| `GET` | `/api/workflows?limit=N` | List recent workflows |
| `GET` | `/api/workflows/{id}` | Full workflow detail |
| `GET` | `/api/workflows/{id}/events` | Event timeline |
| `GET` | `/api/meta` | Registered workflow types + provider chain |
| `GET` | `/api/stats` | Counts + delivery integrity |
| `GET` | `/api/costs` | Cost/token report |
| `GET` | `/api/deliveries` | Outbox delivery log (with `deliveryCount`) |
| `GET`/`POST` | `/api/chaos/...` | Fault injection (see §11) |
| `GET` | `/actuator/health` | Health probe |

---

## 14. Running the tests

```bash
cd backend && mvn test
```

`WorkflowExecutorTest` is the core contract test: it proves step-by-step
checkpointing, that side effects run exactly once, that replay is deterministic,
and that permanent activity failure surfaces as workflow failure — all without a
database.
