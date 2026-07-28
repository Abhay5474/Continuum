# Congestion-Controlled Admission

**Status:** shipped · **Ranked feature:** #5 · **Migration:** `V29__admission_control.sql`
**Default: OFF** for every tenant, existing and new.

> Treat a provider like a congested network link: infer its capacity from
> latency, hold concurrency there, and refuse deliberately rather than letting
> the provider do it at random.

---

## The problem

Without admission control, overload is the provider's problem — and it solves it
with 429s. Every one of those is retried, which produces a larger burst. **The
retry storm is the failure, not the original load.**

The usual defence is a maximum-concurrency number somebody typed. That number is
wrong on the first day and wronger after: providers change their limits, other
traffic shares the same key, and a quota that was generous at 3am is not generous
at peak.

## The approach

**TCP Vegas** (Brakmo & Peterson, 1995) applied to RPC, the way Netflix's
adaptive concurrency limits do it. Congestion shows up as latency rising above
the best latency ever seen — *before* anything is dropped:

```
gradient  = minRtt / currentRtt      (1.0 uncongested, → 0 when queueing)
headroom  = sqrt(limit)              (Little's Law: queue grows with √capacity)
newLimit  = limit × gradient + headroom
```

Three details matter more than the formula:

- **It only grows while it is being used.** A limit raised while eight requests
  use a limit of eighty is evidence of an idle system, not of capacity. Without
  this the limit ratchets up during quiet periods and the first burst discovers
  it was fiction.
- **Down fast, up slow.** No single sample may more than halve the limit, but a
  drop cuts it multiplicatively at once. Overshooting costs a retry storm;
  undershooting costs a little throughput.
- **The baseline decays.** Held forever, one lucky fast early response makes
  everything after it look congested and the limit never recovers.

## Criticality-aware shedding

From Google SRE's *Handling Overload*: past capacity something is refused, and
the only question is whether it is chosen or random. Random means a batch job and
a waiting customer have equal odds.

| Level | Sheds at |
|---|---|
| `BACKGROUND` | 70% of the inferred limit |
| `NORMAL` | 100% |
| `CRITICAL` | 130% — may overshoot, because the limit is an estimate and being wrong about a waiting user costs more than one queued call |

An unrecognised value reads as `NORMAL`, **never** as `BACKGROUND`. A typo in a
client must not silently make that caller's traffic the first thing dropped.

A shed request returns **429 with `Retry-After`** immediately — not a failover.
Falling over to another provider because this one is busy is how a local overload
becomes a global one. A fast honest refusal is worth more than a slow one: the
caller can retry, degrade, or tell its user, none of which it can do while
blocked.

---

## Two things found by driving it

### The gradient is meaningless on a fast provider

120 concurrent requests at a provider answering in **3ms**: queueing added 3ms,
the ratio read 0.48, and the limit collapsed to **4** on a provider that was not
remotely congested. At that timescale the difference is thread scheduling and
garbage collection, not a queue.

Both sides of the ratio are now floored at 20ms. Hosted models sit at 200ms–2s,
well above it, so the real signal is untouched — but a local model, a cached
endpoint or a stub can no longer throttle itself with noise.

### I nearly credited the rate limiter's work to this feature

The first load run reported "92 shed, background first". Only **one** of those
was admission control. The rest were the per-caller token bucket, drained by the
previous burst. The measurement script now distinguishes `error: capacity` from
every other 429, and the phases are spaced 20s apart so the bucket refills and
the comparison means something.

---

## Verified live

Against a provider with a **real concurrency ceiling of 20** and 250ms service
time (`CONTINUUM_MOCK_CONCURRENCY`, `CONTINUUM_MOCK_LATENCY_MS` — a sleep-based
mock has no capacity ceiling at all, so it cannot exercise a feature that defends
against a saturated one):

```
100 concurrent, admission OFF
  ok 100   shed  0     p95 2306ms   p99 2365ms   worst 2365ms

100 concurrent, admission ON
  ok  46   shed 54     p95 1158ms   p99 1195ms   worst 1195ms
  shed by importance: BACKGROUND 31, NORMAL 23, CRITICAL 0
  inferred limit 13   minRtt 255ms   gradient 0.69   queued 31
```

**p95 and p99 both roughly halved, and not one `CRITICAL` request was refused.**

### What that costs, stated plainly

- **46 completed instead of 100.** That is the trade, not a side effect:
  admission control converts "everyone waits" into "some are refused quickly and
  the rest are served fast". Whether that is better depends on whether the caller
  can do something useful with a fast refusal.
- **The inferred limit settled at 13 against a true capacity of 20** — about 35%
  under-utilised. The gradient is deliberately cautious and the noise floor makes
  it more so. Conservative is the right direction to be wrong in here, but it is
  a real cost.
- **Against a provider that does not degrade under concurrency, this feature only
  costs.** An earlier run without the capacity ceiling showed p95 *rising*
  (932ms → 994ms) with 26 requests shed for no benefit. It is off by default for
  this reason: it is worth turning on when a provider actually has a ceiling you
  are hitting.

---

## Scope

Limits are held per **tenant and provider**. With bring-your-own-key each account
has its own quota at the provider, so one account's burst says nothing about
another's capacity. On a shared platform key that assumption breaks and the
limits are independently optimistic — noted rather than hidden.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `…/admission/status` | Per-provider inferred limit, latency baseline, tallies, limit history |
| `PUT` | `…/admission/settings` | `{ enabled }` |
| `DELETE` | `…/admission` | Forget learned limits, so a changed quota is re-inferred |

Requests carry `criticality` (`BACKGROUND` / `NORMAL` / `CRITICAL`).

There is exactly **one setting**. The whole point is that the limit is inferred,
so a configurable maximum would reintroduce the typed guess this replaces.

---

## Seeing it

The console draws the limit as a live trace, because a limit somebody typed would
not move — the movement *is* the feature. Alongside it: utilisation against the
inferred limit, the best latency seen versus the latest, the gradient the
decision was actually made on, and the shed tally broken down by importance.

**42/42 UI assertions** across desktop dark, mobile dark, desktop light.
**Backend suite: 414 passing** (398 → 414).

---

## Tests

`ConcurrencyLimiterTest` (13). The harder half is refusing to invent a number:

- The limit never grows while the system is idle.
- No single sample can more than halve it; sustained congestion does collapse it,
  and it recovers afterwards.
- The baseline drifts up rather than freezing on one lucky early sample.
- A fast provider is not throttled by measurement noise; a slow one still shows
  real congestion.

`CriticalityTest` (3) — ordering, and that an unrecognised value is never read as
background.
