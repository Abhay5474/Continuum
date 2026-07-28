# Decision Provenance Graph

**Status:** shipped · **Ranked feature:** #10 · **Migration:** `V33__decision_provenance.sql`
**Default: OFF** for every tenant.

> Why Continuum did what it did — as data, not as a sentence.

---

## The problem

Every response already carries an explanation:

```
mode=BALANCED, complexity=0.00, low complexity → cheaper model → mock/mock-small
 · quality 0.55 (repair) · confidence 0.82 over 4 samples in 1 meaning
```

That is fine for reading one answer and useless for everything else. **You cannot
aggregate a string, alert on it, bill against it, or ask "how often did the
cascade escalate last week and what did it cost me?"**

One row per decision, not one blob per request — because those questions are
aggregations over *decisions*.

## What is recorded

| Field | |
|---|---|
| `stage` | ADMISSION · CACHE · FIREWALL · CONTEXT · COMPLEXITY · ROUTE · PROVIDER · CASCADE · CONFIDENCE · QUALITY · REPAIR · BREAKER · OUTPUT |
| `choice` | what was chosen |
| `reason` | why, in words |
| `alternatives` | **what was not chosen** |
| `costDelta`, `latencyMs` | what the decision cost |

Stages are named after what they decide, not after the class that decides it. A
provenance record outlives the code that produced it, and `ROUTE` still means
something after the routing engine is rewritten.

Decisions are buffered and written once at the end. A request makes a dozen of
them, and a dozen synchronous inserts on the hot path would make the
observability cost more than the thing observed.

---

## Exported as OpenTelemetry

Using the GenAI semantic conventions where they exist — `gen_ai.request.model`,
`gen_ai.usage.cost` — so it groups correctly in a dashboard that already exists.

**An observability feature that can only be read inside the product it observes
has solved the easy half of the problem.**

A zero cost is omitted rather than exported as `0`: a cost attribute on every
span is noise that hides the real ones.

---

## Verified live

```
0 COMPLEXITY  0.00              estimated before any model was chosen
1 ROUTE       mock-small        mode=BALANCED, 8 candidates in the fallback chain
              not chosen: mock-large, gemini-3.1-flash-lite, gemini-2.0-flash, …
2 PROVIDER    mock/mock-small   first choice answered
3 OUTPUT      25 tokens         mode=BALANCED, complexity=0.00, …
```

and the same request as spans:

```json
{ "name": "continuum.route",
  "attributes": { "gen_ai.request.model": "mock-small",
                  "continuum.alternatives": ["mock-large", …] } }
```

`DecisionTest` (5) pins the export shape, since that is the part other tools
depend on.

---

## A mobile bug, found by the sweep

The OTel JSON block overflowed the viewport by **472px** on a 390px screen. The
`<pre>` had `overflow-auto`, but its grid parent defaults to `min-width: auto`,
so the row was forced wider than the screen however hard the `pre` tried to
scroll. Fixed with `min-w-0` on the grid children.
