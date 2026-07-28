# The Hub — find something to plug in, and add it in one step

**Status:** Phase 2 shipped · **No migration** — the Hub adds nothing to the request path.

> Pick a task shape, supply the parts only you know, and get back either a
> working integration or the exact reason it isn't.

---

## What it replaces

Adding a specialist by hand meant knowing six things before you could press a
button: the provider, the base URL, the auth style, the model path, a sensible
confidence threshold, and a timeout — and then remembering to probe it.

From the Hub it is: search, pick, fill in the parts Continuum cannot know, and
it creates the connection, creates the specialist, and **probes it immediately**.

```
install http-detect, reusing "Injury detector endpoint"
  → connection : VERIFIED
  → specialist : READY · HTTP 200 · 52 ms
  → threshold  : 0.40  (from the template)
  → understood : open wound 0.91, bleeding 0.67
```

---

## Scope: what this is *not*

**The original plan was to search Roboflow Universe. That could not be built
here, and it would have been dishonest to pretend otherwise.**

This deployment's network policy refuses every model host at CONNECT:

```
universe.roboflow.com   403
api.roboflow.com        403
detect.roboflow.com     403
huggingface.co          403
pypi.org                200   ← only package registries are allowed
```

The Universe search API could not be called once. Writing a client against a
response shape nobody had seen would have produced a feature that looked finished
and 404'd on first contact with the real service.

So the Hub ships with the seam and one local source. Adding a live directory
later is a new `CatalogueSource`, not a rewrite.

---

## Entries are task shapes, not a model directory

**No entry names a specific third-party model.** A catalogue that hard-coded
model ids nobody had probed would hand you a one-click add that 404s — worse than
asking you to paste an id you already have. There is a test asserting every
Roboflow entry leaves `modelPath` empty and declares it as required.

What an entry *does* carry is the answers to the questions that actually block
people: which provider, how the credential is presented, what response shape to
expect, what threshold suits this kind of task, and what you must still supply.

| Entry | Input | Suggests |
|---|---|---|
| Object or defect detection (Roboflow) | image | 0.40 |
| Image classification (Roboflow) | image | 0.50 |
| Object detection (your own endpoint) | image | 0.40 |
| Text classification (your own endpoint) | text | 0.50 |
| OCR / text extraction | image | **0.60** |
| Audio transcription | audio | 0.50 |
| Structured extraction from documents | json | 0.55 |
| Content safety / moderation | text | **0.25** |
| Anything else over HTTP | json | 0.40 |

### The thresholds are the part worth reading

They point in **opposite directions**, deliberately:

- **OCR at 0.60** — OCR confidences are usually decisive, and a half-read word
  passed on as a finding becomes a confidently wrong quotation.
- **Moderation at 0.25** — for a safety check a missed flag costs more than a
  spurious one, so it errs toward surfacing.

A test asserts `moderation < ocr`. If they ever collapse to the same number the
guidance has stopped being guidance.

---

## Search

Ranked: title match (10) > exact tag (6) > provider (4) > partial tag (3) >
description (2). Someone typing `ocr` wants the OCR entry, not everything whose
description mentions text.

Tags carry the words people actually type — `invoice` finds structured
extraction, `whisper` finds transcription.

**A miss returns nothing, not everything.** Falling back to the whole shelf would
make a typo look like a successful search.

---

## Source state is reported, always

The search response carries every source and whether it answered:

```json
{ "entries": [...], "sources": [{"name": "Continuum", "available": true}],
  "allSourcesAvailable": true }
```

Because an empty result set means nothing without it. *"No match for wound"* and
*"the only directory that would have known is unreachable"* send a developer to
very different places — one checks their spelling, the other checks their
network. The console says so above the results when a source is down.

This is the same distinction the confidence policy draws between `NONE` and
`UNAVAILABLE`, applied one layer up.

---

## Guards

| Guard | Response |
|---|---|
| Unknown entry | `No catalogue entry called 'nope'.` |
| Roboflow entry, no model path | `This one needs a model path — it is the part only you know.` |
| Self-hosted entry, no base URL | `This one runs on your own infrastructure, so it needs a base URL.` |
| No credential and no reuse | `A credential is needed, or pick an existing connection to reuse.` |
| Reusing a provider-mismatched connection | `That connection is for http, but this entry needs a roboflow one — the credential would be presented in the wrong place.` |
| **Reusing another tenant's connection** | `No such connection.` |

The provider-mismatch check runs **before anything is created**. A Roboflow key
presented as a bearer token to a custom endpoint fails in a way that looks like a
broken model rather than a misconfigured credential.

Cross-tenant reuse was verified with a second developer account: the install is
refused and the reuse list comes back empty.

---

## Probing is not deferred

The install probes before returning. An unprobed specialist is `DRAFT` and cannot
enter a pipeline anyway, so deferring it only means finding out later that the
credential was wrong.

A probe failure **does not roll the install back**. The specialist exists as
`DRAFT` and carries the reason — throwing away correct configuration over a
temporarily unreachable endpoint would be the wrong trade.

---

## Why there is no on/off switch

The 18-feature rule is that anything changing runtime behaviour ships off. The
Hub changes none: it is a discovery surface that creates configuration. The
safety defaults are already downstream — a specialist it creates is `DRAFT` until
probed, and a pipeline is disabled until you enable it.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `…/specialists/catalogue?q=&limit=` | Search; returns entries **and** source state |
| `GET` | `…/specialists/catalogue/{id}/connections` | Which of your connections this entry could reuse |
| `POST` | `…/specialists/catalogue/{id}/install` | Create connection + specialist, probe |

Secrets go in on install and never come back out — verified by asserting the
plaintext key does not appear anywhere in the response.

---

## Verified live

- Search, tag synonyms, provider search, ranking, and a miss that stays a miss
- Install reusing an existing connection → `READY` in 52 ms
- Install creating a new connection → `READY`, threshold 0.60 from the template,
  **no secret in the response**
- All six guards, including cross-tenant refusal
- **48/48 UI assertions** across desktop dark, mobile dark, desktop light

---

## Next

| Phase | What |
|---|---|
| ~~5~~ | ~~**Specialist routing**~~ — **shipped**, see [10-specialist-routing.md](10-specialist-routing.md) |
| 6 | Output verification — does the advice match the findings |
| — | A live `CatalogueSource` against a provider directory, wherever it can be run against the real API |
