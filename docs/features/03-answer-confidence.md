# Answer Confidence — semantic uncertainty

**Status:** shipped · **Ranked feature:** #2 · **Migration:** `V20__semantic_uncertainty.sql`
**Research:** [Farquhar, Kossen, Kuhn & Gal, *Detecting hallucinations in large language models using semantic entropy*](https://www.nature.com/articles/s41586-024-07421-0) (Nature, 2024)

Ask the same question several times. Cluster the answers by **meaning**. Take
entropy over the meaning classes. A model that says the same thing five
different ways is certain; one that says five different things is confabulating.

---

## The problem

Continuum returned an answer with no indication of whether the model was
confident or making it up, so every calling application had to treat all answers
as equally reliable. That is why chat interfaces render hallucinations with the
same typographic confidence as facts.

Token-level probability cannot fix this, because it measures **surface form**.
"Thirty days" and "30 days from renewal" are low-probability continuations of
each other and identical in meaning. That is the gap semantic entropy closes.

---

## How it works

```
request ──► answer (already produced by the normal path)
              │
              ▼
       resample k−1 times at temperature > 0
              │
              ▼
       AnswerClusterer — group by meaning
              │
              ▼
       H = −Σ p·log p  over clusters,  p = |cluster| / k
       normalised = H / log(k)
       confidence = 1 − normalised
              │
              ▼
   response + { confidence, lowConfidence, agreementClusters }
```

`log(k)` is the entropy of maximal disagreement — every sample its own meaning —
so dividing by it makes three samples and seven samples comparable. Without that,
sampling harder would look like rising uncertainty.

### Clustering is the whole trick

Proper entailment needs an NLI model, and Continuum cannot assume one is
reachable — the system is designed to run with no API keys. `AnswerClusterer` is
a deterministic approximation with a deliberate ordering:

1. **Claims first.** Numbers, money, percentages, dates and proper nouns are what
   factual answers assert. Number words fold to digits, so "thirty days" and
   "30 days" are one claim.
2. **Contradiction beats similarity.** If both answers make claims and the claims
   disagree, they are separate meanings *however* similar the prose. *"The window
   is 30 days"* and *"The window is 14 days"* are lexically near-identical and
   semantically opposite — a cosine-only comparator merges them and reports total
   confidence in a coin flip.
3. **Prose falls back to lexical similarity**, for answers asserting nothing
   countable.

### This also repaired the cascade

The cascade's tier-agreement test used raw cosine, so a terse answer and a
verbose answer stating the same fact registered as *disagreement* — biasing its
audit toward over-reporting missed escalations. That was the known limitation
documented when the cascade shipped. It now uses `AnswerClusterer.sameMeaning`,
and the two features share one definition of "the same answer".

---

## Cost, stated plainly

Measuring costs **k times the tokens**. `Mode` is the dial:

| Mode | When |
|---|---|
| `OFF` | Never. Answers carry no confidence field. |
| `ON_DEMAND` | Only when the request sets `measureUncertainty: true`. |
| `ADAPTIVE` | Only where the cascade's cheap judge was already unsure — **recommended**, the multiplier hits a slice rather than everything. |
| `ALWAYS` | Every request. Honest, and k× the bill. |

`ADAPTIVE` is why building the cascade first was the right order: its judge is a
free signal for where a second opinion is worth buying.

### Two settings that cannot be set to useless values

- **Temperature is floored at 0.2.** At 0 every sample is identical, entropy is
  always 0, and the feature would confidently declare certainty about
  everything — the worst possible failure for a confidence signal.
- **Samples are clamped to 2–7.** One sample cannot disagree with itself.

And when only one sample exists, `confidence` is `null`, not `1.0`. A caller must
be able to tell "confident" from "not measured"; defaulting to 1.0 would quietly
assert the first.

---

## API

Response gains three fields, all null unless measured:

```json
{
  "response": "…",
  "confidence": 0.58,
  "lowConfidence": false,
  "agreementClusters": 2,
  "routingReason": "… · confidence 0.58 over 5 samples in 2 meanings"
}
```

Request gains `"measureUncertainty": true` for per-call opt-in.

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/portal/developer/uncertainty/status` | Mode, counters, confidence histogram |
| `PUT` | `/api/portal/developer/uncertainty/settings` | `mode`, `samples`, `temperature`, `lowConfidence` |
| `GET` | `/api/portal/developer/uncertainty/measurements?limit=` | Measurements with their meaning clusters |
| `DELETE` | `/api/portal/developer/uncertainty` | Clear history |

---

## Also fixed: AI chaos never reached the gateway

`AiChaosEngine` was injected only into `LlmActivity` — the same gap hedging had.
A tenant arming a hallucination drill saw **nothing happen** to gateway traffic,
which is the path an external application actually uses.

It now applies to every provider response on the gateway, scoped to the tenant
that armed it. That makes this feature demonstrable with no API keys at all,
which was otherwise impossible: the mock provider is deterministic by design (for
replay assertions), so every sample agrees and confidence is correctly 1.00.

---

## What you see

**Console → Reliability → Answer Confidence:**

- **Mode selector** — four cards, each stating its cost consequence.
- **Counters** — measured, mean confidence, flagged low, **extra spend**, added
  latency. The cost of measuring sits next to what it bought.
- **Confidence distribution** — ten buckets. A single average would hide a
  bimodal workload, which is exactly the shape that matters.
- **Measurements** — each row a radial confidence dial, the prompt, and the
  meaning count. **Click one and it expands into the actual answers**, grouped
  into clusters and sized by how many samples agreed. The number is what a
  program branches on; the disagreement is what a human needs to judge whether
  the number is right.

---

## Demonstrated locally

```
chaos off  → conf=1.00  meanings=1     (deterministic provider, full self-agreement)
chaos 50%  → conf=0.58  meanings=2
             conf=0.58  meanings=2
             conf=0.69  meanings=2
chaos off  → conf=1.00  meanings=1
```

---

## Known limitation, and it is the important one

**Semantic entropy detects confabulation, not systematic error.** In the run
above, one request came back `conf=1.00, meanings=1` *while every sample was
hallucinated* — the injector corrupted all five identically, so they agreed.

A model that is consistently, confidently wrong measures as certain. This is
inherent to self-consistency methods, not a defect in the implementation, and it
is why this signal belongs next to the consensus DAG (multiple *providers*) and
grounding checks rather than replacing them.

High confidence means "the model is not guessing". It does not mean "the model is
right".

---

## Tests

- `AnswerClustererTest` (10) — rewordings collapse to one meaning; number words
  normalise; **contradicting numbers never merge however similar the sentence**;
  majority sorts first; prose falls back to similarity; a terse answer agrees
  with a verbose one (the cascade's old blind spot); proper nouns, money and
  percentages count as claims.
- `SemanticUncertaintyServiceTest` (10) — agreement is zero entropy; total
  disagreement is `log(k)`; a 2:1 split lands between; **normalisation makes
  sample counts comparable**; one sample reports `NaN` rather than 1.0; modes
  gate correctly; temperature floored; samples clamped; unknown mode refused.

Full suite: **260 passing.**
