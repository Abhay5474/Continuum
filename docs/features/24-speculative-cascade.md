# Speculative Cascade

**Status:** shipped as an **advisor** · **Ranked feature:** #18 · **No migration**
**Default:** the cascade runs sequentially; speculation is never on by default.

> Fire the cheap and strong models at once and return whichever the verifier
> accepts — converting the cascade's latency penalty into a cost penalty.

---

## Why this shipped as an advisor and not as a switch

This was ranked **last of eighteen** (6.2), and the dossier's assessment was
specific:

> *Elegant… It converts the cascade's latency penalty into a cost penalty. Worth
> revisiting **after** 01 ships and you can measure how often escalation actually
> happens — if it's 15% of traffic, speculation is wasteful; if it's 60%, it's
> the right answer. **Do not build it first.***

The cascade (#1) has shipped, and it records every escalation. So the escalation
rate *can* now be measured — and the honest response to "build it only when the
measurement supports it" is to **make the measurement the feature**.

`SpeculationEconomics` computes, from this account's own traffic, what
speculation would cost and what it would save, and says plainly whether it is
worth turning on. Shipping a switch without that would be shipping the thing the
research explicitly warned against.

---

## The arithmetic

Where `p` is the measured escalation rate:

| | Cost | Latency |
|---|---|---|
| Sequential (today) | `C_cheap + p·C_strong` | `L_cheap + p·L_strong` |
| Speculative | `C_cheap + C_strong` | `≈ L_cheap` |

Both differences are driven by the same number, in opposite directions:

- **extra spend** = `(1 − p) · C_strong` — you paid for the strong model on every
  request that turned out not to need it;
- **latency saved** = `p · L_strong` — on requests that did escalate, the strong
  call was already running instead of starting afterwards.

Break-even is at **p = 0.5**, where more requests benefit from the parallel call
than pay for it needlessly. Not a law of nature — a stated reference point.

The strong tier's latency is derived as `total − cheap` on escalated rows, which
is exactly the wait speculation removes.

---

## What it deliberately does not decide

**Whether a millisecond is worth a cent is a product decision, not an arithmetic
one.** Both numbers are reported; they are not collapsed into a single score,
because there is no universal exchange rate between latency and money and
inventing one would make the advice look more objective than it is.

---

## It refuses to advise on thin data

Below **30** cascade decisions the escalation rate is noise. Four escalations out
of five looks decisive and means nothing, so the advisor returns no verdict and
says why:

> *Only 5 cascade decisions recorded. Below 30 the escalation rate is noise, and
> turning speculation on or off from it would be guessing. Send more traffic
> through the cascade first.*

Refusing to answer is the correct answer there. The same rule as the semantic
breaker's warm-up and adaptive consensus's minimum sample.

---

## Verified live

With the cascade on and 34 real requests through it:

```
requests 34 · escalated 0 · rate 0.0%

worthwhile     : false
extraSpendUsd  : $0.00670
latencySavedMs : 0
summary: "Only 0% of requests escalate, so speculation would pay for the strong
          model on the 100% that never needed it — about $0.00670 more (2587%
          more) to save roughly 0ms per request. At this rate it is mostly waste;
          it becomes a reasonable trade above 50%."
```

And with no data at all, the thin-sample refusal fires as designed.

The 2587% is real, not a formatting error: this cascade currently spends almost
nothing because it answers everything on the cheap tier, so adding a strong call
to every request multiplies the bill many times over. That is precisely the case
the dossier said to check for before building the feature.

5 unit tests: low escalation is called waste, high escalation is called
worthwhile, thin data refuses to advise, spend and latency move in opposite
directions as the rate climbs, and an unknown baseline reports an absent
percentage rather than a flattering zero.

---

## Console

`Traffic → Model Cascade`, in a panel below the calibration curve — the advice
belongs beside the escalation rate it is computed from, not on a page of its own.

A meter shows the measured rate against the 50% break-even, with the extra spend
and the latency saved side by side and a plain-language verdict.
