# Quality Gate — check the answer against the request

**Status:** shipped · **Ranked feature:** #3 · **Migration:** `V21__quality_gate.sql`
**Default: OFF.** Like every feature on this roadmap.
**Research:** [Zheng et al., *Judging LLM-as-a-Judge*](https://arxiv.org/abs/2306.05685) (NeurIPS 2023),
[Huang et al., *LLMs Cannot Self-Correct Reasoning Yet*](https://arxiv.org/abs/2310.01798) (ICLR 2024),
[Constitutional AI](https://arxiv.org/abs/2212.08073) (Bai et al., 2022)

Everything else on the response path asks *did the call succeed?* This asks *did
the answer do what was asked?*

---

## Why the scope is narrow on purpose

I flagged this as the feature most likely to disappoint, and the research says
why. Judges are documented as biased — position, verbosity, self-preference — and
Huang et al. found that unaided self-correction often makes reasoning **worse**.

So this gate does not attempt to judge whether an answer is *good*. It checks
four things that are objectively checkable from the request and the text:

| Dimension | Catches | Weight |
|---|---|---|
| **Adherence** | The explicit contract: JSON when JSON was demanded, N items when N were asked for, not truncated, not a refusal | 0.45 |
| **Completeness** | A three-part question answered in one part | 0.20 |
| **Grounding** | Figures asserted in the answer that appear nowhere in the supplied context | 0.20 |
| **Relevance** | The answer is about the question at all | 0.15 |

Adherence dominates because its failures are unambiguous. Completeness and
grounding are heuristics and are weighted so they cannot fail an answer alone.

Adherence reuses the cascade's `DeferralJudge` rather than re-deriving format and
truncation rules — one definition of "broke the contract", used by both features.

---

## Monitor mode is the point

A gate that can rewrite an answer before a customer sees it has to earn that
right. Shipping one that silently edits production traffic on day one would be
indefensible.

| Mode | Behaviour |
|---|---|
| `OFF` | **Default.** Never runs. |
| `MONITOR` | Checks every answer, records exactly what it *would* have done, changes nothing. |
| `ENFORCE` | Checks and repairs. |

In MONITOR the `action` column records the intent and `applied` records `NONE`.
The gap between those two columns is the evidence for whether enforcing would be
an improvement or a liability — drawn from your own traffic, not from a promise.

---

## Four hard limits when enforcing

**One repair attempt** (capped at two). A gate that keeps re-asking can spend
without bound on an answer it will never be satisfied with.

**A latency budget** (default 4s). Past it the original answer is returned
unchanged. A slow correct answer is worse than a fast flawed one for anything
interactive, and the miss is recorded as `BUDGET_EXCEEDED` rather than hidden.

**A repair that does not score better is discarded.** A "correction" that scores
worse is a regression the gate caused itself. Recorded as `REPAIR_REJECTED`, so a
failed repair costs a call but **never a worse answer**.

**A refusal is never repaired.** `BLOCK`, not `REPAIR`. Asking again is how you
get the same refusal twice and pay for both.

The repair instruction names the actual defects — *"you returned prose when the
request required JSON"* — because a vague complaint produces a vague correction.

---

## Ordering on the request path

The gate runs **before** the confidence measurement. There is no point measuring
the confidence of an answer that is about to be replaced.

```
answer → firewall.guardOutbound → QualityGate → [repair?] → uncertainty → response
```

---

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `/api/portal/developer/quality/status` | Mode, counters, per-dimension failure rates, repair success rate |
| `PUT` | `/api/portal/developer/quality/settings` | `mode`, `threshold`, `maxRepairs`, `budgetMs` |
| `GET` | `/api/portal/developer/quality/checks?limit=` | Checks with before/after where a repair ran |
| `DELETE` | `/api/portal/developer/quality` | Clear history |

Responses carry the verdict in `routingReason`:

```
"… · quality 0.55 (repair): JSON was requested and the answer is not JSON"
"… · repaired (0.55 → 0.92): JSON was requested and the answer is not JSON"
"… · quality 0.94"
```

---

## What you see

**Console → Reliability → Quality Gate:**

- **Mode cards**, with Monitor described as where to start.
- **Counters** — checked, *would have acted on*, **repairs that helped**,
  blocked, added latency and spend. The success rate is prominent because a gate
  that fires often and fixes nothing is worse than no gate.
- **A sentence that argues against the feature when the data does.** Below 50%
  repair success it says so in red and tells you to go back to monitoring.
- **Dimension breakdown** — which check is doing the work and which is only
  producing noise, as failure rate per dimension.
- **Check tape** — score, a four-segment dimension strip, the defect, and a
  status badge. **Expanding a repair shows the before and after side by side.**

---

## Verified live

```
gate OFF      → no quality note in the response at all
MONITOR       → "quality 0.55 (repair): JSON was requested and the answer is not JSON"
                answer returned unchanged
ENFORCE       → repair attempted, scored no better, original returned
                recorded as REPAIR_REJECTED, before/after both stored
```

The mock provider cannot produce JSON, so its repair legitimately fails — which
exercised the discard path and confirmed the safety property: **a failed repair
costs a call but never a worse answer.**

---

## Tests

`QualityGateTest` (13). The false-positive cases matter as much as the true ones,
because a gate that cries wolf gets turned off:

- Good answers pass with no defects; single-question requests never trip the
  completeness check; grounding does not run without substantial context.
- Broken JSON, three-part questions answered in one part, invented figures,
  irrelevant answers and empty answers each produce a repair.
- A refusal blocks rather than repairs.
- The repair instruction names the actual defect.
- The threshold governs the action; every dimension is reported either way.

Full suite: **273 passing.**
