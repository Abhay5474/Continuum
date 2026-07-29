# Cost-Aware Admission

**Status:** shipped · **Ranked feature:** #17 · **Migration:** `V39__cost_aware_admission.sql`
**Default: OFF** for every tenant.

> A fifty-step agent carrying twenty thousand tokens is not one request in the
> way that `"hello"` is one request.

---

## The problem with counting requests

Under a request-count limit, the caller sending a twenty-thousand-token agent
conversation is charged the same as the caller sending `"hello"` — and is
therefore being subsidised by everyone sending small ones. It is three orders of
magnitude more expensive to serve.

Weighted fair queuing (Demers, Keshav & Shenker, SIGCOMM 1989) solved this for
networks by charging per **bit** rather than per packet. Tokens are the bits
here, and the generalisation is direct.

---

## Two resources, and the tighter one binds

Requests and tokens are limited separately; a caller is admitted only when both
have room. This is the two-resource case of **Dominant Resource Fairness**
(Ghodsi et al., NSDI 2011): whichever resource a caller consumes most of,
relative to their allowance, is the one that limits them.

- many tiny calls → bounded by the **request** limit
- one enormous call → bounded by the **token** limit

Neither caller can starve the other by choosing a shape, which is the property
DRF exists to provide.

Both buckets refill continuously rather than resetting on a boundary, so nobody
can save up a minute's allowance and spend it in one burst.

---

## Reserve, then settle

A request's real token cost is not known until the response comes back. So:

1. **Reserve** an estimate at admission — the prompt as submitted, plus the
   caller's `maxTokens`, or `800` when they did not set one.
2. **Settle** against the provider's reported usage, returning the difference.

Both halves matter. Without settlement the limiter drifts from reality in
whichever direction the estimate is biased, and **an under-estimate that is never
corrected is a hole in the limit**.

The estimate is deliberately generous: under-reserving lets a caller through and
discovers the cost too late, which is the failure this exists to prevent, while
over-reserving only makes them wait slightly longer and the excess comes straight
back.

**Reserve on submitted size, settle on actual.** The prompt as submitted is the
only size known before any work happens; the settled figure reflects compression,
context paging and cache hits. The reserve is a conservative gate; settlement is
what makes the accounting true.

---

## An unsettled reservation is a leak

A reservation whose request died before settling would hold allowance nobody is
using, forever. Three defences:

- every reservation is returned in a `finally` block;
- `settle()` and `close()` are both idempotent, so the finally-block `close()`
  after a `settle()` cannot double-refund;
- anything still missed expires after `RESERVATION_TTL` (5 minutes).

The console shows **in flight** — the count currently held. If it climbs and
never falls, reservations are leaking, and that is the number that says so.

---

## Design details

- **A refusal carries no ticket.** `Outcome.ticket()` is null exactly when the
  request was refused, so nothing can be settled against a reservation that was
  never granted. (An earlier draft passed the ticket back through a
  `ThreadLocal`; returning it with the decision removes the possibility of
  holding a reservation without knowing it.)
- **The refusal says which resource ran out.** A caller told only "too many
  requests" when they are in fact over their *token* allowance will retry with
  the same enormous prompt. The 429 body carries `boundBy`.

---

## API

```
GET  /api/portal/developer/cost-admission/status
PUT  /api/portal/developer/cost-admission/settings
     { "enabled": true, "requestsPerMin": 1000, "tokensPerMin": 6000 }
POST /api/portal/developer/cost-admission/reset
```

A refused request returns **429** with `Retry-After`:

```json
{ "error": "cost_limited", "boundBy": "TOKENS", "retryAfterSeconds": 42,
  "message": "this request needs about 5100 tokens and the token allowance of
              6000 per minute is spent — a large request costs more of it than a
              small one, which is the point" }
```

---

## Verified live

With `requestsPerMin: 1000` and `tokensPerMin: 6000` — so only token consumption
can bind:

```
5 small requests   →  200 200 200 200 200     (nowhere near 1000/min)
1 large request    →  200   5,057 tokens
another large one  →  429   boundBy: TOKENS
```

The second large request was refused while the caller had used **0%** of their
request allowance. That is precisely the case a request-counting limiter cannot
see.

Accounting afterwards:

```
admitted 6 · refused 1 · refusedByTokens 1
tokensCharged 5142 · outstanding 0
requestShare 0.0 · tokenShare 0.847
```

`outstanding 0` confirms settlement returned every reservation; `tokensCharged
5142` is the settled figure, not the reserved one.

10 unit tests: large costs more than small, token allowance binds inside the
request limit, refunds on settle, no hole without settlement, `close()` releases,
double-settle is a no-op, request-bound callers, refusals carry no ticket,
callers are independent, and an uncapped completion reserves the assumed length.

---

## Honest limitations

**In memory, per instance** — the same limitation the existing `RateLimiter`
has and documents. Three instances behind a load balancer allow three times the
traffic. Stated on the page rather than implied away.

**Low demo value, and that is expected.** The research dossier scored this 6.4
and called its impact "mostly negative-space: things that don't go wrong". It is
hardening, not a headline, and it was built as such.

---

## Console

`Traffic → Cost-Aware Limits`. Two bars per caller — request allowance and token
allowance — with the binding one highlighted. A caller whose token bar is full
while their request bar is empty is the whole argument for the feature, visible
at a glance.
