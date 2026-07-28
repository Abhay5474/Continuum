# Adaptive Consensus

**Status:** shipped · **Ranked feature:** #7 · **Migration:** `V31__adaptive_consensus.sql`
**Default: OFF** for every tenant. With it off, confidence measurement draws
exactly the configured k, as it always has.

> Ask once for an easy question and five times for a contested one — stop as soon
> as the answer is statistically decided.

---

## The problem

Measuring confidence by resampling costs k times the tokens, and k is configured
once for every request a tenant makes. That is the wrong shape. *"What time do
you close?"* and *"Does this indemnity clause survive termination?"* do not need
the same evidence: the first is settled after a few samples that agree, the
second is not settled after five.

## The rule

**Adaptive-Consistency** (Aggarwal, Yang, Mausam & Roy, EMNLP 2023): keep a Beta
posterior over the leading answer cluster's share and stop when the probability
that further sampling would overturn it falls below a threshold. Underneath it is
Wald's sequential probability ratio test (1945) — stop when the evidence is
decisive, not when a counter runs out.

With `a` answers in the leading cluster and `b` elsewhere, the belief about the
true majority rate is `Beta(a+1, b+1)`, and the quantity that matters is
`P(rate < 0.5)`.

| Agreement | P(overturned) | At a 5% bar |
|---|---|---|
| 2 of 2 | 12.5% | keep sampling |
| 3 of 3 | 6.25% | keep sampling |
| **4 of 4** | **3.1%** | **stop** |
| 7 of 8 | 3.5% | stop |
| 4 of 7 | 50% | keep sampling |

**The rule is not "everyone agreed" — it is "agreement this consistent is
unlikely to reverse."** Two coin flips landing the same way is not evidence the
coin is biased, and an adaptive rule that treated it as such would just be a
cheaper way to be wrong.

Clustering is by *meaning*, reusing the same `AnswerClusterer` the final
measurement uses. Deciding to stop on a different notion of agreement than the
one that produces the answer would be measuring one thing and acting on another.

---

## What it cannot do

**It cannot shorten a genuinely contested question, and should not.** Disagreement
is exactly the case the extra samples exist for. A split vote never stops early
however many samples are drawn — there are three tests asserting that. The saving
comes from the easy majority of traffic, which is where it should come from.

Two hard guards: it never stops below **2** samples whatever the posterior says,
and it never exceeds the configured budget. **Adaptive means fewer samples, never
more** — a rule that could exceed the budget would turn an opt-in cost into an
unbounded one.

---

## Verified live

Budget 7, six easy questions:

```
adaptive OFF   samples drawn [7, 7, 7, 7, 7, 7]   total 42   cost 4.03e-05
adaptive ON    samples drawn [4, 4, 4, 4, 4, 4]   total 24   cost 2.02e-05

43% fewer samples, cost halved
```

Stopping at exactly 4 is the arithmetic, not a coincidence: the mock is
deterministic, so four samples agree and `0.5^5 = 0.031` clears the 5% bar while
three (`0.0625`) does not.

**An honest caveat on that number:** a deterministic provider is the best case for
this rule — every sample agrees, so it stops as early as it ever could. Against a
real model with genuine variation the saving will be smaller, and on contested
questions it is zero by design. The 7.9× in the paper is over a reasoning
benchmark, not over this workload.

**36/36 UI assertions** across desktop dark, mobile dark, desktop light.
**Backend suite: 440 passing** (429 → 440).

---

## API

Added to `PUT …/uncertainty/settings`: `adaptiveEnabled`, `overturnThreshold`
(clamped to 0.001–0.5). The pre-adaptive `configure` signature is kept as a
delegating overload so callers that do not use the feature are not churned by it.

The response reason gains `· stopped early, 4 of 7 drawn` when it fires.

---

## Tests

`AdaptiveStoppingTest` (11). The Beta CDF is checked against closed forms
(`Beta(2,2)` at 0.5 = 0.5, `Beta(4,1)` = 0.0625, `Beta(1,4)` = 0.9375), and the
rest are about refusing to stop: near-ties, split votes, below the minimum, and
the budget as a hard ceiling.
