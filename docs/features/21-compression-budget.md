# Adaptive Compression Budget

**Status:** shipped · **Ranked feature:** #15 · **Migration:** `V38__compression_policy.sql`
**Default: OFF** for every tenant.

> One ratio for the whole prompt is the wrong shape. Instructions, examples and
> the question do not carry information at the same density.

---

## What was already there, and what was missing

Continuum's compressor (`PromptCompressor`) has existed since V8: coarse-to-fine
ranking, a self-information proxy, and protected spans. What it did not have is
the other half of LLMLingua — the **budget controller**.

The paper (Jiang et al., EMNLP 2023) allocates different ratios to different
regions of a prompt, and reports these:

| Region | Drop |
|---|---|
| Instructions | 10–20% |
| Demonstrations | 60–80% |
| The question | 0–10% |

The reasoning is that few-shot examples are largely redundant with each other —
that is what makes them examples — while an instruction is a list of
requirements where every clause matters, and the question must survive intact.

Implemented as:

| Region | Keep | Detected by |
|---|---|---|
| `QUESTION` | 1.00 | the latest user turn |
| `INSTRUCTION` | 0.85 | a system message |
| `HISTORY` | 0.55 | anything else — the pre-existing default |
| `EXAMPLE` | 0.30 | two or more demonstration markers |

---

## Unsure means gentler, never harsher

Region detection is a heuristic over roles and text markers, and the two
mistakes are not equally costly. **Calling an instruction a demonstration
throws away 70% of it and silently changes what the model was asked to do.**

So:

- a message is only called `EXAMPLE` on **two or more** marker lines (`Example:`,
  `Input:`/`Output:`, `Q:`/`A:`, `### …`). One `Output:` inside a sentence of
  prose is not a demonstration block;
- everything unrecognised falls to `HISTORY`, which is the ratio the system used
  before this class existed.

The same asymmetry rule as `Criticality` (unknown → `NORMAL`, never `BACKGROUND`)
and `Priority` (unknown → `NORMAL`, never `BATCH`).

---

## The gate: not compressing at all

Compression costs fidelity, so it should only run when it buys something.

- **Prompt under 400 tokens** → skipped. There is little to remove and the
  fidelity cost outweighs the saving.
- **Model too cheap for the saving to matter** → skipped. Implemented, but
  **not reachable from the gateway today**, and the code says why: the gateway
  selects a model *after* compression runs, so at that moment there is genuinely
  nothing to price. `gate()` is passed `null` and the reason string reports
  "the model is chosen later in the pipeline, so price could not be considered".
  Naming the gap beats inventing a figure.

---

## A bug this feature exposed

Setting a *gentle* budget revealed that the compressor could not honour one.
Measured live, asked to keep 85% of an instruction block, it kept **75%**.

The cause: the fine pass (`pruneTokens`) dropped stopwords unconditionally,
regardless of how much room was left. The target ratio was therefore a *floor*,
not a target — harmless when you want aggressive compression, and wrong when you
deliberately asked for a gentle one.

**Fixed** by making the fine pass budget-aware: it prunes only while the budget
is still exceeded, and appends a sentence verbatim once the target is met.

```
before fix   instructions  target 0.85   achieved 0.75   ← over-compressed
after fix    instructions  target 0.85   achieved 0.87   ← within budget
```

The five pre-existing `PromptCompressorTest` cases still pass, so aggressive
compression is unchanged.

---

## Honest limitation: an aggressive budget is a target, not a guarantee

Also measured live, on a demonstration block dense with clause numbers:

```
examples      target 0.30   achieved 0.81
earlier turns target 0.55   achieved 0.79
```

The coarse pass force-keeps any sentence containing a protected span, and the
protection pattern matches numbers and identifiers — so a block of
`clause 4.2`, `clause 7.1`, `clause 9.3` is almost entirely protected and cannot
be cut to 30%.

**That is the correct outcome, not a defect.** Dropping a sentence to hit a ratio
would lose a clause number the answer depends on. Protection is a guarantee; the
budget is a target; protection wins.

The console therefore shows **target against achieved** for every region, and
says which direction is the fault: above target is protection doing its job,
**below target is over-compression and is the real problem.**

---

## API

```
GET  /api/portal/developer/compression-policy/status
PUT  /api/portal/developer/compression-policy/settings   { "enabled": true }
POST /api/portal/developer/compression-policy/reset
```

The base compressor is gated separately by `developer_auth.v8_compression_enabled`.
Both must be on for the budget to have anything to allocate — the page says so
when only one is.

---

## Verified live

```
region        target      in     out  achieved
question        1.00      19      19      1.00   ← never touched
instruction     0.85     252     220      0.87   ← within budget
history         0.55     186     147      0.79   ← protection-bound
example         0.30     204     165      0.81   ← protection-bound

skip: "prompt is only 5 tokens; below 400 there is little to remove and
       the fidelity cost outweighs the saving"
```

10 unit tests: each region's classification, the single-marker false positive,
the unknown fallback, budget ordering, and all four gate outcomes.

---

## Console

`Prompt → Compression Budget`. A bar per region showing target against achieved,
the list of prompts the gate declined with the reason for each, and the tallies.

The per-region tallies are in memory and reset on restart; cumulative token
savings remain durable in `compression_metrics` and appear under Prompt Guard.
