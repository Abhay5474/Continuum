# Answer Repair Engine

**Status:** shipped · **Ranked feature:** #6 · **Migration:** `V30__answer_repair.sql`
**Default: OFF** for every tenant. With it off the quality gate's existing
single-shot repair is unchanged.

> When an answer is deficient, diagnose *why* and fix that specific defect —
> rather than re-rolling the whole request and hoping.

---

## What was already there, and what was missing

The Quality Gate (#3) already repairs: it collects every defect, concatenates
them into one complaint — *"your previous answer had these problems: …; fix
them"* — and keeps whatever comes back if it scores better.

That works, and it throws away the most useful thing the gate knows: **which
kind of failure it is**. An answer that stopped mid-sentence and an answer that
invented a figure are not the same problem and do not have the same fix. A model
asked to fix "these problems" rewrites everything, including the parts that were
already right.

## Three differences

1. **One defect kind per attempt.** Each classified defect gets an instruction
   naming the single thing to change — and, just as importantly, saying what to
   leave alone: *"Keep what you already wrote and add the missing part. Do not
   rewrite the parts that were already answered."*
2. **Re-checked after every attempt.** The loop stops the moment the answer
   passes, so an easy fix costs one call rather than the whole budget.
3. **The answer never gets worse.** Every attempt is re-scored by the same
   external gate, and an attempt that scores no better than what it replaced is
   **discarded**.

### The defect taxonomy

| Strategy | Trigger | Repairable |
|---|---|---|
| `EMPTY` | nothing came back | yes |
| `TRUNCATION` | stopped mid-sentence | yes |
| `FORMAT` | requested shape not delivered | yes |
| `COUNT` | wrong number of items | yes |
| `INCOMPLETE` | part of the question unanswered | yes |
| `UNGROUNDED` | figures absent from the supplied context | yes |
| `IRRELEVANT` | answered something else | yes |
| `REFUSAL` | the model declined | **no** |
| `UNCLASSIFIED` | a defect nobody anticipated | falls back to naming it |

A refusal is never repaired — asking again is how you get the same refusal twice
and pay for both. An unclassified defect is **kept**, not dropped: silently
ignoring it would make this quietly weaker than the single-shot repair it
replaces.

---

## Why this is safe to ship

Huang et al., *Large Language Models Cannot Self-Correct Reasoning Yet*
(ICLR 2024), found that unaided self-correction usually makes answers worse — a
model asked to reconsider will find fault with correct work.

Two things keep this on the right side of that finding:

- **Every trigger is external.** A failed format check, a missing sub-question, a
  figure that is not in the supplied material. Never the model's opinion of
  itself.
- **An independent score decides what survives.** Continuum does not have to
  trust the repair; it can measure whether it helped and throw it away when it
  did not. **Repair without that check is a coin flip that costs money.**

The discarded attempts are recorded and shown, because they are the evidence the
guard is doing something. An engine that never discards anything is not being
checked; one that never keeps anything is money going out with nothing back.

---

## Two bugs, both found by tests

### The gate's own wording defeated the classifier

The gate emits *"the answer does not appear to address the question"* for an
off-topic answer. That contains `"not appear"`, which the classifier matched for
`UNGROUNDED` — so an irrelevant answer was sent the instruction for *removing
invented figures*. Completely the wrong fix. `IRRELEVANT` is now checked first
and the `UNGROUNDED` match tightened to `"not appear in"`.

### A correct bullet list was being read as truncated

Writing a test for the keep path exposed something in the shared
`DeferralJudge`: `- Liquidity cover is thin` has no terminal punctuation, so
`endsCleanly` called it truncated. A well-formed three-bullet answer therefore
scored **0.55** — exactly the same as the prose it was meant to replace — and the
repair engine discarded it.

**The repair engine could never have improved a list-shaped answer.** Fixed: a
line that is a bullet or numbered item is not a truncated sentence. The same
answer now scores **0.92** and passes.

This lives in `DeferralJudge`, which the cascade also uses, so it improves #1 and
#3 too. Full suite re-run to confirm nothing else depended on the old behaviour.

---

## Verified live

```
"exactly 3 bullet points"     → COUNT    0.55 → 0.55  discarded
"valid JSON with keys …"      → FORMAT   0.55 → 0.55  discarded
a refusal                     → quality 1.00, no repair attempted
```

**An honest limitation.** Every live attempt was discarded — correctly, because
the mock provider cannot produce bullets or JSON however it is asked, so no
repair could help. That demonstrates the guard, not a successful repair. The
*keep* path could not be driven authentically against this provider and is
covered by tests instead:

- `improvementIsKept` — a real 3-bullet answer scoring 0.92 against 0.55 replaces
  it.
- `stopsOnSuccess` — one call, not the whole budget.
- `regressionIsDiscarded` — a worse answer never ships.
- `refusalIsNotRepaired` — **zero model calls**.
- `modelFailureIsNotFatal`, `budgetIsRespected`, `zeroAttemptsDoesNothing`.

The loop also stops after an attempt that did not help: trying the same defect
again with the same instruction produces the same result and bills for it.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `PUT` | `…/quality/settings` | `{ repairEngineEnabled }` alongside mode, threshold, attempts, budget |
| `GET` | `…/quality/repairs` | The attempt ledger, discards included |
| `GET` | `…/quality/repairs/summary` | Attempts, kept, discarded, score gained, cost, by strategy |
| `DELETE` | `…/quality/repairs` | Clear the ledger |

Attempts are hard-capped at 3 regardless of configuration, and the whole loop
shares one wall-clock budget.

**39/39 UI assertions** across desktop dark, mobile dark, desktop light.
**Backend suite: 429 passing** (414 → 429).

---

## What this cannot do

It cannot tell whether an answer is *correct* — only whether it satisfies
checkable properties of the request. A confidently wrong answer in perfect JSON
with exactly three bullets passes every dimension the gate has. Repair makes
answers meet their contract; it does not make them true.
