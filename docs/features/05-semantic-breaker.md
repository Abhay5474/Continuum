# Semantic Breaker — trip on quality, not on errors

**Status:** shipped · **Ranked feature:** #4 · **Migration:** `V22__semantic_breaker.sql`
**Default: OFF.**
**Research:** [Chen, Zaharia & Zou, *How is ChatGPT's behavior changing over time?*](https://arxiv.org/abs/2307.09009) (HDSR 2024),
Page (1954) CUSUM, [Fowler, *Circuit Breaker*](https://martinfowler.com/bliki/CircuitBreaker.html)

Continuum already had a health tracker watching what a classical circuit breaker
watches: errors, timeouts, latency. The failure that actually damages a
production AI application is the one none of those see — **the provider is up,
fast, returning 200s, and quietly worse.**

Chen, Zaharia and Zou measured GPT-4's accuracy on one task falling from 84% to
51% between two dates on an unchanged API. A latency dashboard stays green
through all of that.

---

## Why CUSUM rather than a threshold

A provider that degrades rarely falls off a cliff. It gets slightly worse and
stays slightly worse.

- A **fixed threshold** misses that until the damage is large.
- A **moving average** absorbs it, because the average is exactly what moved.
- **CUSUM** accumulates the shortfall against a baseline, so a small persistent
  deficit adds up while a brief dip decays back to zero.

Three details make it usable rather than merely correct:

**The baseline is learned and then frozen.** "Good" for a code-generation account
is not "good" for a summarisation account. And a monitor that keeps updating its
baseline from degraded data learns to accept the degradation and goes quiet.

**Per-observation contribution is capped at 0.25.** This came out of a failing
test. Without the cap a single catastrophic answer contributes 0.85 against a
threshold of 0.75 — enough to trip on its own, which makes it a threshold alarm
wearing a CUSUM costume. Capping it means one terrible answer is noise and
several in a row are a pattern.

**It is one-sided.** A provider that gets *better* is not an incident.

---

## Two drift channels, and what each can see

| Channel | Source | Detects | Blind to |
|---|---|---|---|
| **Contract compliance** | The quality gate's score, computed free — no model call | Format breakage, refusals, truncation, off-topic drift | A fluent, well-formatted fabrication |
| **Self-agreement** | The confidence measurement, when enabled | Confabulation — the model no longer agreeing with itself | Systematic error the model is consistent about |

This distinction is worth stating plainly, because it was found by testing rather
than assumed. Arming a hallucination injector and watching the contract channel
produced **no movement at all** — correctly. The quality gate checks whether an
answer honours its contract, which is deliberately not a check on whether it is
true, so `"Assessment: HIGH RISK"` scores exactly as well as `"Assessment: LOW
RISK"`.

Agreement is the channel that moves on factual degradation, which is why the
confidence measurement now feeds the breaker as a second input.

---

## Scoped per tenant, deliberately

Drift is a property of the provider, so pooling observations across accounts
would detect it sooner. It would also mean **one account's traffic could trip a
breaker that reroutes everybody else's** — a denial-of-service primitive dressed
as a feature. Each account observes its own traffic and trips its own breakers.

---

## Recovery is probed, not timed

After the cooldown the breaker goes `HALF_OPEN` and lets exactly **one** request
through. The model earns its way back by answering well and re-opens immediately
if it does not. Closing on a timer alone would send full traffic back to a model
nothing has re-checked.

If *every* model in the chain is tripped, the filter is ignored and the request
still goes somewhere. A degraded answer beats no answer.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/portal/developer/breaker/status` | Per-model state, baseline, pressure, quality trace |
| `PUT` | `/api/portal/developer/breaker/settings` | `enabled`, `warmup`, `slack`, `threshold`, `cooldownSeconds` |
| `GET` | `/api/portal/developer/breaker/events?limit=` | Transitions with the evidence that caused them |
| `POST` | `/api/portal/developer/breaker/reset?provider=&model=` | Close one breaker and relearn its baseline |
| `DELETE` | `/api/portal/developer/breaker` | Clear all |

---

## What you see

**Console → Reliability → Semantic Breaker.** The page deliberately shows what a
latency dashboard cannot:

- **A quality trace per model with the learned baseline drawn through it.** A
  trace on its own is a wiggle; what matters is whether it has moved below where
  it used to sit.
- **Pressure** — accumulated shortfall as a fraction of the threshold. This is
  the number worth watching because **it moves before the trip does.** A breaker
  at 70% is a provider degrading right now.
- **State** in the card border and dot: closed, probing, diverted.
- **Transitions** with their evidence: *"quality fell from a baseline of 1.00 to
  a recent mean of 0.95; accumulated shortfall 0.45 crossed the 0.40 threshold"*.

---

## Demonstrated end to end

Using the AI chaos engine to induce drift — a demo only Continuum can give,
because the fault injector already exists:

```
warm-up (7 requests, healthy)
  mock-small   CLOSED   baseline=1.00 recent=1.00 pressure=0.00

hallucination armed at 50% — still 200s, still fast
  mock-small   OPEN     baseline=1.00 recent=0.95 pressure=1.00 trips=1
  mock-large   CLOSED                             ← traffic moved here

chaos disarmed, cooldown elapsed
  PROBING      cooldown elapsed — letting one request through to test recovery
  RECOVERED    probe scored 1.00 against a baseline of 1.00
  mock-small   CLOSED
```

Note the recent mean at the moment of tripping: **0.95**. Nothing about that
number looks alarming, and no threshold anyone would set catches it. The
accumulated shortfall is what crossed the line.

---

## Limitations

**Systematic error is invisible to both channels.** A model that is consistently,
confidently wrong honours its contract and agrees with itself. Semantic entropy
has the same blind spot for the same reason. Detecting that needs an external
reference — the consensus DAG across *providers*, or grounding against retrieved
sources.

**Warm-up is a real cost.** A model needs 30 answers (default) before it can trip,
so the first drift after a deployment change is missed by construction. Lowering
the warm-up trades that against false positives.

---

## Tests

`CusumDetectorTest` (12). The harder half is staying quiet:

- Nothing fires during warm-up, even on catastrophic input.
- 500 samples of realistic Gaussian noise around a stable mean do not trip it.
- A brief dip decays back to zero rather than leaving a debt a later blip inherits.
- **One catastrophic answer cannot trip it; three consecutive can.**
- A small persistent deficit (0.10 below baseline) does trip — the property a
  fixed threshold cannot give you.
- Improvement is never an incident; the baseline freezes after warm-up.

Full suite: **285 passing.**
