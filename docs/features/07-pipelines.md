# Pipelines — input in, answer out

**Status:** Phase 3 shipped · **Migration:** `V25__pipelines.sql`
**Default: OFF.** A new pipeline is created disabled and stays that way until you turn it on.

> Your application sends an image and gets advice back. It never learns that a
> detector ran, what it was called, or who hosts it.

---

## What this is

Phase 1 gave Continuum specialists — a way to call something that isn't the LLM.
It stopped at the specialist. Somebody still had to take `wound 0.91,
bleeding 0.67`, write it into a prompt, call the gateway, and hope.

A pipeline is that whole path, named and callable:

```
image  →  specialist  →  structured context  →  model  →  answer
```

One endpoint. One name. Your application posts to it and gets prose back.

```bash
curl -X POST https://your-continuum/api/gateway/pipeline/animal-triage \
  -H "Authorization: Bearer cnt_live_..." \
  -H "content-type: application/json" \
  -d '{"input":{"image":"<base64>"},"prompt":"My dog is bleeding, what do I do?"}'
```

```json
{
  "response": "Apply firm pressure with a clean cloth…",
  "evidenceConfidence": 0.91,
  "findings": 2,
  "analysisRan": true,
  "traceId": "58b1cc18-4858-4a6b-95fd-7cb652bd0ec8",
  "trace": [ … ]
}
```

**Nothing in that response names Roboflow, the endpoint, or the credential.**
Verified by assertion, not by inspection — swapping a hosted detector for your
own FastAPI is invisible to every application you have shipped.

---

## The context builder is the feature

Everything else here is plumbing. This is the part that decides what the model
is allowed to believe.

### Confidence is stated, not implied

A finding at 0.91 and one at 0.42 are different kinds of fact, and a model given
a bare list treats them identically. So the numbers are written down and the
findings are grouped by strength:

```
Observed:
  - open wound (91% confident, from Animal injury detector)

Possible, less certain:
  - bleeding (67% confident, from Animal injury detector)
```

The model's hedging then has something to track.

### The model is told it cannot see

Every prompt, every branch, says so:

> You have NOT been shown the raw input, so do not claim to have examined it or
> introduce details the analysis did not report.

Without this a model handed two findings about an image will happily describe the
image — the exact failure the specialist was added to prevent.

### Absence is stated

A pipeline that found nothing says so. Sending only the user's question and
letting the specialist's silence be a gap invites the model to answer from its
priors.

### Withheld signals are counted

A detection below threshold never reaches the prompt — passing it with a caveat
invites the model to reason about it anyway, which is how a 0.14 detection
becomes a paragraph of advice. But *how many* were withheld is reported, because
**"nothing found" and "nothing confident enough" are different situations.**

---

## One bug, found by unplugging the detector

With the specialist endpoint killed mid-flight, the context told the model:

> No findings. The specialist analysis did not identify anything above the
> configured confidence threshold.

Which is false. Nothing was analysed. The threshold had nothing to do with it.

An application reading that as "all clear" reports a healthy animal during an
outage. So total failure now reads:

> No analysis is available. The specialist checks did not complete, so nothing
> has been examined.

and the run carries `analysisRan: false` alongside `anythingFound: false`.
**A clean image and a dead endpoint both produce zero findings, and only one of
them is evidence.** Phase 4's confidence policy needs to tell them apart; so does
any application deciding whether to show a result.

Three tests pin the distinction, including that one specialist surviving out of
two is *incomplete*, not *unavailable*.

---

## A failed specialist does not fail the pipeline

Its absence is named in the context so the model knows its evidence is partial,
and the answer is still produced. **A partial answer with a caveat beats an error
page.** The trace marks the step `FAILED` and the enrichment step `DEGRADED`, so
nothing is hidden — it is just not fatal.

---

## Why execution is synchronous

Compiling a pipeline to the durable workflow engine would make it replayable and
crash-proof. That is the right shape for a long back-office job and the wrong one
for a chat turn, where the caller is holding a connection open waiting for an
answer.

Durable execution of the same definition belongs behind a separate entry point,
not a different pipeline model.

---

## Seeing it work

The console page is not a form with a status field. It runs the pipeline through
**the same `PipelineService.run` the gateway route uses** — a test path that
skipped a step would be worse than no test path — and replays the chain:

```
● INPUT        Image received
● SPECIALIST   Animal injury detector              0.91   8ms
● ENRICHMENT   Findings structured for the model   0.91
● MODEL        mock-small                                41ms
● OUTPUT       Answer returned
```

Every step opens. The enrichment step opens onto **the exact prose the model was
handed**, kept verbatim rather than rebuilt from the structured form — when an
answer is wrong that is the first thing worth reading, and a reconstruction is
not the same artefact.

---

## Guards

| Guard | Why |
|---|---|
| A pipeline is created **disabled** | Half-configured pipelines cannot be reached by accident |
| Enabling with no steps is refused | A pipeline with no specialists is just the gateway |
| A `DRAFT` specialist cannot be added | Moves the failure from configuration time to a customer's request |
| Name is `[a-zA-Z0-9_-]` | It appears in the URL |
| Every lookup is tenant-scoped | Including by id, not just by name |
| A deleted specialist is skipped, not fatal | One missing step should not break a pipeline that still has others |

---

## API

| Method | Path | Auth | Purpose |
|---|---|---|---|
| `GET` `POST` | `/api/portal/developer/pipelines` | session | List, create |
| `PUT` `DELETE` | `…/pipelines/{id}` | session | Configure, enable, remove |
| `POST` | `…/pipelines/{name}/run` | session | Run it from the console |
| `POST` | `/api/gateway/pipeline/{name}` | **API key** | What your application calls |

Two routes to the same pipeline because the person wiring it up and the program
using it are not the same principal. `/api/gateway/pipeline/*` is registered with
the API-key filter, not the console filter.

---

## Verified live

```
pipeline    → animal-triage                    enabled: false   ← default
run         → 400  "Pipeline 'animal-triage' is not enabled."
enable      → live
run         → 200  2 findings · evidence 0.91 · mock-small · 61ms
              INPUT → SPECIALIST → ENRICHMENT → MODEL → OUTPUT
gateway     → 200  no key leak, no provider leak, no endpoint leak
  no API key → 401
  unknown    → 400  "No pipeline called 'nope'."
detector down → 200  analysisRan: false, answer still returned,
                     SPECIALIST FAILED, ENRICHMENT DEGRADED
```

**60/60 UI assertions** across desktop dark, mobile dark, desktop light.

**Backend suite: 330 passing** (316 → 330, +14 from `ContextBuilderTest`).

---

## Two UI defects, found by driving it

- **The finding label vanished on mobile.** At 390px a single flex row truncated
  `open wound` to zero width while `ANIMAL INJURY DETECTOR` — the least important
  thing on the row — survived at full width. The label now takes its own line
  until there is room to share one. Pinned with a pixel assertion, because the
  text reads as "present" in the DOM either way.
- **No favicon anywhere in the console.** The browser fell back to
  `/favicon.ico` and logged a 404 on every page, burying real errors in the one
  place you go to read them. Declared inline; site-wide, not specific to this
  page.

---

## Next

| Phase | What |
|---|---|
| ~~4~~ | ~~**Confidence policy**~~ — **shipped**, see [08-confidence-policy.md](08-confidence-policy.md) |
| ~~2~~ | ~~**The Hub**~~ — **shipped**, see [09-hub.md](09-hub.md) |
| 5 | Specialist routing — which specialist, decided by type, rule, or the model |
| ~~6~~ | ~~**Output verification**~~ — **shipped**, see [11-output-verification.md](11-output-verification.md) |

Phase 4 is what stops a 0.31 detection becoming confident first-aid advice.
`analysisRan` was added here because Phase 4 cannot be correct without it.
