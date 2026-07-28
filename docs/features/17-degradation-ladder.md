# Graceful Degradation Ladder

**Status:** shipped · **Ranked feature:** #11 · **Migration:** `V34__degradation_ladder.sql`
**Default: OFF** for every tenant.

> When every model fails, step down explicitly instead of returning nothing.

---

## The rungs

| Rung | |
|---|---|
| `FULL` | What was asked for. |
| `CACHED` | A previous answer to an equivalent question — stale, but true when it was written. |
| `STATIC` | An honest message saying no answer could be produced. |

**There is no "cheaper model" rung, and the omission is deliberate.** Falling
back to a smaller model is already what the fallback chain does, several times
over, before this ladder is ever reached. Adding it here would present the
chain's ordinary work as a degradation event.

---

## Degradation is never silent

Every degraded response says which rung it came from — the model reads
`degraded/cached` or `degraded/static`, and the reason begins `DEGRADED`.

**A degraded answer presented as a normal one is worse than an error**, because
the caller cannot tell it should retry, warn its user, or decline to act on it.
Silently succeeding is the failure mode this feature could most easily become,
and there is a test asserting every outcome is marked.

The bottom rung is not an apology-shaped nothing:

> No answer could be produced for this request — every available model failed.
> This is a temporary service problem, not a judgement about the question.

Upstream causes are shortened before they reach the caller. Provider errors carry
stack detail and sometimes credential-adjacent text; the caller gets a cause, not
an upstream's internals. A test pins that.

---

## Verified live

Forcing genuine chain exhaustion needed a way to take the safety-net provider
down — the mock is the last entry in every fallback chain, so nothing that
handles total provider failure could be exercised end to end while it answered.
`CONTINUUM_MOCK_UNAVAILABLE` now does that, alongside the latency and concurrency
knobs added for admission control.

```
ladder OFF   HTTP 502   "All upstream AI providers are currently unavailable."
ladder ON    HTTP 200   model degraded/static
                        DEGRADED (STATIC): Every model failed … and no equivalent
                        answer was cached, so nothing could be served.
```

**Honest limitation:** the `CACHED` rung was not driven live — populating the
cache requires a successful request, which requires the provider that had to be
down. It is covered by `DegradationLadderTest`, which asserts a cached answer is
preferred, is returned verbatim, and is disclosed as possibly out of date.

**48/48 UI assertions** (shared sweep with #10) across desktop dark, mobile dark,
desktop light. **Backend suite: 460 passing.**
