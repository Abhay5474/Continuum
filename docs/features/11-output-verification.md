# Output verification — does the advice match the findings?

**Status:** Phase 6 shipped · **Migration:** `V28__output_verification.sql`
**Default: OFF**, per pipeline, including every pipeline that already exists.

> The confidence policy asks the model to behave, and the hedge check asks
> whether it did. Both are about the *instruction*. Neither asks whether the
> advice is anchored to what was actually found.

---

## The gap this closes

An answer can hedge beautifully and still describe an injury nobody detected.
Phase 4 would call that a success: it asked for a hedge, it got a hedge.

This checks the answer against the evidence instead.

---

## Three checks

| Check | Fails the answer? |
|---|---|
| **Certainty beyond the evidence** — "this is a", "clearly", "confirmed" when the strongest finding was weak, or when nothing was found, or when nothing ran | **Yes** |
| **Invented figures** — a percentage matching no finding's confidence and absent from the question | **Yes** |
| **Coverage** — which findings the advice actually addresses | No, warns |

### Why coverage can only warn

The match is lexical. A model that writes *"laceration"* for a finding labelled
*"open wound"* reads as uncovered while having covered it perfectly. **Failing an
answer for that would punish good writing.**

So the two checks that can fail an answer are the two that do not depend on the
model having chosen the same words. This is the same reasoning that keeps `NONE`
and `UNAVAILABLE` apart: a check that cannot distinguish two situations must not
act as though it can.

### Restating a confidence is not inventing one

Models restate figures and they also make them up. The difference is checkable:
a percentage is allowed if it matches a finding's confidence (rounded any of
three ways) **or** appears in the user's own question. Everything else is an
invention.

---

## Three modes, not a boolean

| Mode | What happens |
|---|---|
| `OFF` | Nothing. The default. |
| `MONITOR` | Check and record. The answer goes out unchanged. |
| `ENFORCE` | Replace a failing answer with one that states the findings plainly. |

**`MONITOR` is where people should start**, and the console says so — it tells
you how often your pipeline would have been stopped before you let it stop
anything.

`ENFORCE` replaces the text a real user reads, so it can only ever be a decision
someone made. Never something that arrives with an upgrade.

The replacement carries the evidence rather than hiding it:

```
The automated analysis found the following, but a reliable answer could not be
produced from it:
  - fracture (31% confident)
Please try again, or seek advice from someone who can examine this directly.
```

And for an outage it says something different, because an outage is not a clean
result:

```
This could not be assessed — the automated analysis did not complete, so nothing
was examined. Please try again shortly.
```

---

## The bug this feature found in itself

Driving it live produced a `FAIL` that looked right and was completely wrong:

```
FAIL: The answer asserts (confirmed) but the strongest finding was only 31%.
```

Nothing had asserted anything. The context builder writes **"Weak signals, treat
as unconfirmed"**, the model echoed it, and a substring match on the certainty
marker `confirmed` matched inside `unconfirmed`.

**The phrase carrying the strongest available doubt was being read as an
assertion of certainty.**

Fixed with word-boundary matching in a shared `Phrases` helper, applied to
`HedgeDetector` as well — the same list has `"inconclusive"` containing
`"conclusive"` and `"unclear"` containing `"clear"` waiting for the next person
who adds a phrase. Two copies of a matching rule is one copy too many.

Two regression tests, one in each class.

Worth stating plainly: **after the fix, the same run correctly reads `OK`.** The
live `FAIL` I had was the defect, not the feature working.

---

## Verified live

```
band-strong  MONITOR → OK    covered: open wound, bleeding
band-weak    MONITOR → OK    (correctly — the model asserted nothing)
band-empty   MONITOR → OK
verify-coverage MONITOR → WARN
   "The advice does not mention swelling around the joint, matted fur with
    debris, limping gait, dehydration. This may just be wording — the check
    is lexical."
   covered:   abrasion on the left forelimb
   uncovered: swelling around the joint, matted fur with debris, limping gait,
              dehydration
   chain: … → VERIFY(WARNED) → OUTPUT(OK)
```

**An honest limitation of the live run:** the mock provider echoes only the first
160 characters of the context, so it cannot produce an unqualified assertion, and
the `FAIL` and `ENFORCE` paths could not be driven authentically end to end.
They are covered by unit tests — including certainty with no analysis, certainty
with no findings, certainty on weak evidence, invented figures, and both
replacement messages. Against a real model those paths would fire; against this
one they cannot be honestly demonstrated, and manufacturing a demonstration would
be the thing this feature exists to prevent.

A declined run produces no verification at all, correctly: the confidence policy
short-circuits before the model, so there is no answer to check.

**48/48 UI assertions** across desktop dark, mobile dark, desktop light.
**Backend suite: 398 passing** (380 → 398).

---

## What this cannot do

It cannot tell whether the advice is *correct*. It checks whether the advice is
anchored to the findings — a smaller question, but the one that catches a model
inventing a diagnosis.

The next honest step beyond a word list is an entailment check between the advice
and the findings. That needs a model, which makes it a cost and latency decision
rather than a free one, and it belongs behind its own switch.
