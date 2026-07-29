# Counterfactual Replay

**Status:** shipped · **Ranked feature:** #16 · **Migration:** `V40__counterfactual_replay.sql`
**Default: OFF** for every tenant.

> "Replay last week's traffic against this candidate routing policy and tell me
> the cost delta — before I switch."

---

## Built as a batch evaluator, deliberately

The research dossier that ranked this feature was blunt about it:

> *As a UI feature it is a gimmick; as an offline evaluation service it is
> valuable. Build it as a batch evaluator, not a time-travel button.*

That guidance was followed. There is no "re-run this one request on a different
model and compare the answers" button, because that is a demo. What there is:
replay every logged request against a candidate policy and report the cost delta,
which is how you tune a cascade threshold without experimenting on live traffic.

Two candidate shapes:

| Policy | Question it answers |
|---|---|
| `always <arm>` | "What if everything had gone to the cheap one?" |
| `complexity < t → A, else B` | "What if I moved the cascade threshold?" |

**Nothing is re-sent to any provider.** It reads the request log and touches
nothing on the request path.

---

## The statistics, and why the honest answer is uncomfortable

Estimating how a policy you did *not* run would have performed, from logs of the
one you *did*, is **off-policy evaluation**. The standard tool is the doubly
robust estimator (Dudík, Langford & Li, ICML 2011):

- a **direct method** — model each arm's reward and predict. Low variance, biased
  by however wrong the model is.
- an **inverse propensity score** correction — reweight logged outcomes by how
  likely the logging policy was to take that action. Unbiased, but only where the
  logging policy *could* have taken it.

**That second condition fails here, and not marginally.** Continuum's routing is
deterministic. For any logged request the chosen arm has propensity 1 and every
other arm has 0. IPS divides by the propensity, so for a candidate that would
have chosen differently the correction is **undefined**. There is no overlap, and
no arithmetic recovers information the logs do not contain.

---

## So the answer is split in two and never blended

| | |
|---|---|
| **Agreed** | The candidate would have made the same choice. The logged outcome *is* the counterfactual outcome. **Measured.** |
| **Diverged** | The candidate would have chosen differently. Estimated from that arm's own history on comparable traffic — the direct method alone, no correction available. |
| **Unmodellable** | The candidate routes to an arm with *no* history. Counted separately; nothing is invented for it. |

The report carries the measured cost, the modelled cost, and the fraction that
had to be estimated. **A single blended figure would hide the one thing worth
knowing before changing your routing: how much of it is a guess.**

Estimates are per arm and complexity-banded (±0.25), so a cheap model is never
credited with the cost profile of hard questions it never saw.

---

## Details that keep it honest

- **Cache hits are excluded.** A cached response cost nothing and went to no
  provider; replaying it against a routing policy would credit the candidate with
  a saving that had nothing to do with routing.
- **Per-arm rows report counts, not a verdict.** An earlier draft marked a whole
  row "modelled" if *any* of its requests were — which called a row modelled when
  nine in ten of its requests had been directly observed. Rows now read
  `89/97 measured`.
- **Nothing is stored.** An evaluation is a pure function of the log and the
  candidate policy, so persisting its output would only let it go stale.

---

## API

```
GET  /api/portal/developer/counterfactual/status
GET  /api/portal/developer/counterfactual/arms
PUT  /api/portal/developer/counterfactual/settings   { "enabled": true }
POST /api/portal/developer/counterfactual/evaluate
     { "alwaysArm": "mock/mock-small" }
     { "at": 0.5, "below": "mock/mock-small", "above": "mock/mock-large" }
```

---

## Verified live

Over 97 logged requests spread across two models:

```
candidate: always mock/mock-small
  actually spent      $0.002329
  candidate would cost $0.000569      −75.6%
  agreed 92%  →  measured $0.000461, modelled $0.000108
  caveat: "…routing here is deterministic, so there is no propensity correction
           available for the diverging traffic — that part is a direct-method
           estimate and its bias is not bounded."
```

And the case that matters most — a candidate routing to a model that has never
run:

```
candidate: always openai/gpt-9
  agreed 0%   unmodellable 29/29
  caveat: "…29 of them route to an arm with no comparable history at all — for
           those the logged cost was carried through unchanged because there is
           nothing to estimate from. Treat this as a direction, not a number."
```

It refuses to produce a saving it cannot justify. That is the whole point.

6 unit tests: full agreement is measured, divergence is flagged as modelled,
unknown arms are counted rather than invented, estimates are per-arm, measured
and modelled totals reconcile to the estimate, and an empty log yields no
estimate.

---

## Console

`Reliability → Counterfactual Replay`. Pick a candidate, replay, and read three
things: the cost delta, a bar showing what fraction is measured, and per-arm rows
saying how many of each arm's requests were observed rather than modelled.
