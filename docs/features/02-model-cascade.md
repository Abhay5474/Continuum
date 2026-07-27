# Model Cascade — verify then escalate

**Status:** shipped · **Ranked feature:** #1 · **Migration:** `V19__response_cascade.sql`
**Research:** [FrugalGPT](https://arxiv.org/abs/2305.05176) (Chen, Zaharia & Zou, Stanford 2023),
[UCCI](https://arxiv.org/abs/2605.18796) (2026), [Semantic Agreement](https://arxiv.org/pdf/2509.21837) (2025)

Answer with the cheapest capable model. Judge the answer. Pay for the expensive
model only when the judge says the cheap one did not manage it.

---

## Why the failover chain was not already this

Continuum's `ModelFallbackPolicy` builds a chain and moves down it when a call
**errors**. A cheap model that returns a confident, fluent, wrong answer is a
success as far as that chain is concerned — so every request paid for whichever
model the scorer guessed it needed up front, and the guess came from a keyword
count.

Cascading needs a judgement about the *answer*, which nothing in Continuum made.

---

## How it works

```
request
  │
  ▼
tier 0 — cheapest reachable model
  │
  ▼
DeferralJudge ──► raw score
  │
  ▼
CalibrationStore ──► calibrated confidence
  │
  ├── confidence ≥ threshold ──► return the cheap answer
  │        └── (sampled: also run tier 1 to measure what was missed)
  │
  └── confidence < threshold ──► tier 1, return the strong answer
                                   │
                                   ▼
                       compare the two answers
                       agreement ⇒ the escalation was wasted
                       disagreement ⇒ it was earned
                                   │
                                   ▼
                            calibration learns
```

### The judge is deterministic and free

It could ask a model to score the answer. That is the obvious design and it
would add a call and a latency budget to the path whose entire purpose is being
cheap and fast. Instead it reads six signals that are objectively checkable, and
names every one in the output so an escalation can be explained:

| Signal | Catches |
|---|---|
| **Hedging language** | "I don't know", "I cannot determine", "as an AI" — the model reporting its own failure |
| **Truncation** | An answer that stops mid-sentence |
| **Format compliance** | JSON requested, prose returned |
| **Explicit constraints** | "in exactly 3 bullets", counted |
| **Grounding** | Cosine against the prompt. Near-zero = answered a different question; near-total = restated the question |
| **Substance** | Length against what the question's complexity warrants |

Hard signals gate, soft signals scale: an answer that hedges or breaks a format
contract cannot be rescued by being long and on-topic.

### Calibration, because a raw score is not a probability

This is UCCI's central point, transposed: the number is roughly monotone with
correctness but badly scaled, so a fixed threshold means something different on
every workload.

`CalibrationStore` bins scores, records the rate at which the cheap answer turned
out to be sufficient in each bin, and then **pools adjacent violators** — the
step from isotonic regression that guarantees a higher score never maps to a
lower probability. Without it a sparse bin can invert the curve and the threshold
control stops behaving monotonically.

Bins below eight samples fall back to the raw score. Claiming a calibrated
probability from four observations would be worse than not calibrating.

### The label is free

Whenever both tiers run, their answers are compared. **Agreement means the cheap
answer was fine and the escalation was wasted. Disagreement means it was earned.**

No human, no benchmark, no ground truth — and it is exactly the signal needed to
tune the threshold from real traffic.

### The audit slice, because that label is one-sided

The comparison above only ever arrives for requests that *escalated*. It measures
false positives and is completely blind to false negatives — the answers the
judge waved through that the strong model would have disagreed with.

So a sampled fraction of traffic (default 5%) runs both tiers **regardless of the
verdict**. Those rows are the only way to see what the judge is missing.

Without this a cascade looks like a triumph while quietly degrading. The savings
figure in the console therefore never appears alone: beside it sit wasted
escalations and missed escalations, the two numbers that would expose it.

### Escalating costs more, not less

A miscalibrated judge that escalates everything makes the bill go **up** — you
pay for two calls instead of one. The escalation rate is compared to a configured
cap (default 60%) and the console says so loudly when it is exceeded.

---

## Two related fixes that shipped with it

**Tiers are filtered to reachable providers.** The registry lists every model it
knows about, including ones whose provider has no credential configured.
Offering one as a tier would build a cascade that escalates into a guaranteed
failure.

**The mock provider now has two tiers.** It registered exactly one model at zero
cost, which meant a keyless deployment could not demonstrate anything requiring a
*choice* — the cascade declined outright, routing had one arm, hedging had
nothing to race. That undercut the point of shipping a mock provider whose stated
purpose is making the system demonstrable with no API keys.

`mock-small` and `mock-large` now exist at different prices. The small one
answers briefly and the large one at length — the difference small models
actually exhibit. Neither is rigged to fail.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/portal/developer/cascade/status` | Config, savings, waste, misses, calibration curve |
| `POST` | `/api/portal/developer/cascade/{enable\|disable}` | Toggle |
| `PUT` | `/api/portal/developer/cascade/settings` | `threshold`, `auditRate`, `escalationCap` |
| `GET` | `/api/portal/developer/cascade/tiers` | Tiers derived from the registry, cheapest first |
| `GET` | `/api/portal/developer/cascade/decisions?limit=` | Decision tape |
| `DELETE` | `/api/portal/developer/cascade` | Clear history and the calibration curve |

Tenant-scoped throughout — the threshold governs one account's own quality and
spend, so unlike routing there is nothing operator-gated here.

---

## What you see

**Console → Traffic → Model Cascade:**

- **Escalation ladder** — a live stacked bar of where requests actually left. A
  cascade escalating most of its traffic is costing money, and that reads at a
  glance without arithmetic.
- **Did it work** — saved, reduction %, *wasted escalations*, *missed
  escalations*, with a plain sentence: either "across 14 audited requests the
  strong model agreed with every answer the judge accepted", or "the judge
  accepted 2 answers the strong model disagreed with — raise the threshold".
- **Tiers** — which models were derived, at what price.
- **Threshold** — Frugal / Balanced / Careful, each stating its consequence.
- **Calibration curve** — bars showing how often a cheap answer at that judge
  score actually matched the strong model. Amber bars fall below your threshold
  and would escalate. Bins with too few samples draw hollow.
- **Decision tape** — every request with a confidence bar marked against the
  threshold that decided it, and the judge's reasoning.

**Gateway responses** carry the reasoning:

```
"routingReason": "cascade: answered by mock-small — confidence 1.00 — no concerns"
"routingReason": "cascade: mock-small escalated to mock-large — confidence 0.00 below threshold 0.75 — JSON was requested and the answer is not JSON"
"routingReason": "cascade: answered by mock-small — confidence 0.92 — no concerns (audit sample)"
```

---

## Measured on a local run

12 requests of mixed difficulty against the two mock tiers:

```
requests 12 | escalated 3 (25%)
spend $0.000965 vs strong-only $0.001120  →  saved 14%
wasted escalations 0 | audited 2 | missed 2
```

Both escalations were correct and explained: *"3 items requested, 0 produced"*
and *"JSON was requested and the answer is not JSON"*.

---

## Known limitation

**Agreement is measured lexically.** `TextVectors.cosine` compares the two
answers as bags of words, so a terse answer and a verbose answer saying the same
thing register as disagreement. In the run above that is why 2 audit samples came
back "missed" — `mock-small` and `mock-large` differ in length by design.

With real models the effect is smaller but still present, and it biases the audit
toward over-reporting misses. The fix is ranked feature #2: entailment-based
clustering, which compares meaning rather than vocabulary. When that lands the
cascade should consume it in place of cosine.

---

## Tests

- `DeferralJudgeTest` (11) — good answers pass; hedging, truncation, broken JSON,
  wrong item counts, off-topic drift and question-parroting each escalate;
  `"exactly N"` is stricter than a soft count; brevity is only suspicious when
  the question was involved.
- `CalibrationStoreTest` (7) — identity without evidence, thin bins ignored,
  correction toward the observed rate, **monotonicity under deliberately
  inverted evidence**, per-tenant independence, reset.

Full suite: **240 passing.**
