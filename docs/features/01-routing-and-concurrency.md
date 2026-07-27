# Routing strategy, gateway hedging, and real worker concurrency

**Status:** shipped · **Ranked feature:** #14 (Learned Router) plus the three
audit fixtures · **Migration:** `V18__routing_strategy.sql`

This is the first step of the roadmap, and it is entirely about making things
that already existed actually run. Four mechanisms were built, tested and
displayed in the console, and none of them affected a request from an external
application.

---

## What was wrong

Continuum has two LLM execution paths, and they did not have the same
capabilities:

| | `/api/gateway/chat` (external apps) | `LlmActivity` (durable workflows) |
|---|---|---|
| Routing switch honoured | **No** | Yes |
| Contextual bandit consulted | **No** | No |
| Tail-latency hedging | **No** | Yes |

Concretely:

1. **The router on/off switch did nothing on the gateway.** `GatewayService`
   never read `ModelRoutingState.isEnabled()`; it called the heuristic scorer
   unconditionally. Turning routing off changed nothing an application could
   observe.

2. **The contextual bandit learned and the learning was discarded.** A complete
   non-stationary Thompson sampler with discounted Beta posteriors, unit-tested,
   with a console panel drawing its real belief intervals. `observe()` ran on
   every gateway request. `rank()` was called only by a display endpoint. It had
   never influenced a single answer.

3. **Hedging was unreachable from the gateway.** `HedgedProviderExecutor` was
   referenced only by `HedgingService`, which was injected only into
   `LlmActivity`. The switch, the adaptive governor and the p50/p95/p99 rail on
   the Routing page all described workflow traffic.

4. **The engine's real concurrency was one.** Spring's default scheduler is
   single-threaded, and `ActivityWorker.poll()` executed each claimed activity
   inline. Seven `@Scheduled` methods shared that one thread — both queue
   pollers, the outbox dispatcher, the recovery sweeper, autopilot, god mode and
   model discovery. A single slow HTTP step (timeout up to 300s) blocked all of
   them, including the sweeper whose job was to recover from exactly that. It
   also made `executeActivitiesParallel` sequential in practice.

---

## What changed

### Routing strategy is now an explicit choice

`ModelRoutingState.Strategy` names the three behaviours:

| Strategy | Provider order comes from |
|---|---|
| `STATIC` | Availability order — the original V1 behaviour |
| `HEURISTIC` | Cost/latency/quality scorer over aggregate provider statistics |
| `LEARNED` | Contextual bandit: Thompson sampling over per-context posteriors |

`getStrategy()` returns `STATIC` whenever routing is switched off, so the switch
now means something on both paths. `getConfiguredStrategy()` returns what is
configured regardless — the console shows both, because conflating them is how a
setting that did nothing looked active.

`RoutingStrategyService.decide(...)` chooses the order and never throws: any
failure inside learned routing falls back to the scorer, because routing must not
be able to fail a request.

### Learned routing is guarded

A bandit that explores freely is correct in the limit and unpleasant in
production. Two guards:

- **`MIN_OBSERVATIONS = 12`.** Below this an arm is mostly prior, and "learned"
  routing would be a coin toss wearing a lab coat. Thin arms defer to the scorer.
- **Bounded exploration.** When the top arm is thin, the sampler is followed
  anyway 15% of the time — the only way a thin arm ever gets thicker — and the
  decision is flagged `explored` so it can be separated in analysis.

### Every decision records its counterfactual

`routing_strategy_decision` stores what the active strategy chose **and what the
heuristic would have chosen** for the same request, plus the outcome.

This is the point of the whole exercise. Aggregate success rate is dominated by
requests where both strategies agreed and tells you nothing. `GET
/api/routing/comparison` reports the two populations separately:

```json
{
  "strategy": "LEARNED",
  "decisions": 412,
  "diverged": 96,
  "divergenceRate": 0.23,
  "whenDiverged": { "count": 96,  "successRate": 0.97, "avgLatencyMs": 780, "avgCost": 0.0021 },
  "whenAgreed":   { "count": 316, "successRate": 0.93, "avgLatencyMs": 910, "avgCost": 0.0026 }
}
```

Read that as: *on the 96 requests where learning changed the answer, success was
4 points higher at 19% lower cost.* If the numbers come out the other way, the
honest response is to switch the strategy back — which is why they are measured
rather than asserted.

### Hedging works on the gateway

`GatewayService.tryHedged(...)` races the distinct providers in the chain when
hedging is enabled and more than one provider is reachable. The first good answer
wins and its siblings are cancelled.

Two deliberate decisions:

- **A failed race is not a failed request.** `tryHedged` returns `null` rather
  than throwing, and the caller falls through to the ordinary sequential chain.
- **A fired hedge is billed for what it spent.** Cost is multiplied by the number
  of requests launched. Reporting one call would make hedging look free, and the
  cost is exactly the tradeoff being made.

The gateway needs `HedgingService.execute(request, chain, caller)` because a
developer's own provider credentials are resolved per request, so the
`ProviderCaller` cannot be a singleton built at startup the way the workflow
path's is.

### Activities run on a bounded pool

- `spring.task.scheduling.pool.size` defaults to 8, so the seven `@Scheduled`
  methods stop serialising behind each other.
- `ActivityWorker` owns a fixed pool of `continuum.engine.activity-concurrency`
  threads (default 16) and a matching semaphore.
- **Claim to capacity, never beyond it.** The poller takes `min(batchSize,
  availablePermits)` tasks. Claiming work it cannot start would hide that work
  from other workers for the length of the visibility timeout.

Measured on a local run: 25 concurrent `DurableDemo` workflows reached a peak of
**10 concurrent activities**, bounded by the batch size. Before the change the
peak was structurally 1.

---

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` | `/api/routing/state` | session | Adds `strategy`, `configuredStrategy`, `hedgingOnGateway` |
| `POST` | `/api/routing/strategy?strategy=` | **operator** | `STATIC` / `HEURISTIC` / `LEARNED` |
| `GET` | `/api/routing/comparison?limit=` | session | Learned routing vs its baseline; operator sees engine-wide |
| `GET` | `/api/engine/capacity` | session | `inFlight`, `capacity`, `saturation` |

Strategy is engine-wide, so it is operator-gated like the other routing
controls. Use **Account → Operator access** in the console to unlock it.

---

## What you see

**Routing → Learning & hedging** gains a decision ledger:

- Counters for decisions, how many overrode the scorer, and how many were
  deliberate exploration.
- Success rate when overriding against success rate when agreeing, with a plain
  sentence stating the delta and the cost difference.
- A live tape of recent decisions — *scorer wanted X, actually ran Y* — with
  overrides marked, so you can watch the bandit disagree in real time.

**Command Centre** runtime tile shows `Workers n/16` — live occupancy of the
activity pool.

**Gateway** responses carry the routing reason, which now names the mechanism:

```
"routingReason": "bandit chose gemini — MODERATE context, posterior mean 0.94 over 38 observations"
"routingReason": "hedged across [gemini, groq] — groq answered first in 412ms (hedge fired)"
"routingReason": "routing off — static availability order"
```

---

## Trying it

```bash
# Unlock operator access, then:
curl -X POST "$API/api/routing/enable?enabled=true"   -H "Authorization: Bearer $OPERATOR"
curl -X POST "$API/api/routing/strategy?strategy=LEARNED" -H "Authorization: Bearer $OPERATOR"
curl -X POST "$API/api/hedging/enable?enabled=true"   -H "Authorization: Bearer $OPERATOR"

# Send traffic, then read the ledger:
curl "$API/api/routing/comparison" -H "Authorization: Bearer $SESSION"
```

With a single provider configured, divergence stays 0 — the bandit has nothing to
override. That is correct behaviour, not a failure; configure a second provider
credential to see learned routing actually disagree.

---

## Tests

`RoutingStrategyServiceTest` (7 tests) covers: the switch producing static order,
heuristic pass-through, thin arms deferring, a well-observed arm overriding and
explaining itself, divergence being recorded, the comparison separating the two
populations, and recording surviving a broken repository.

Full suite: **222 passing.**
