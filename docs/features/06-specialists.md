# Specialists — call a smaller model before the big one

**Status:** Phase 0 + 1 shipped · **Migrations:** `V23__specialist_connections.sql`, `V24__specialists.sql`
**Default: nothing runs until you register and probe a specialist.**

> Continuum learns to call a second, smaller model *before* the big one — and
> knows what to do when that smaller model isn't sure.

---

## The problem

An app has data in a shape the language model cannot use well. An injured-animal
photo is the motivating case: a general model cannot reliably identify the injury,
but a purpose-trained detector can — and once it has, the general model is
excellent at turning `wound 0.91, bleeding 0.67` into first-aid advice.

The same shape recurs everywhere: OCR before document reasoning, transcription
before meeting summary, a defect detector before an inspection report. **Let a
specialist do the sensing; let the general model do the reasoning.**

---

## Why this is one feature, not eight

Format adapters, specialist pre-processors, enrichment and schema shaping are all
the same mechanism: **call something that isn't the LLM, and put the result into
the prompt.** Same plumbing, different endpoint.

Two of the other families were already built — context compression is Prompt
Guard and the Context Optimizer; output verification is the Quality Gate.

---

## Phase 0 — hardening, because this is what makes it load-bearing

Pointing Continuum at third-party endpoints with stored customer credentials
turned two audit findings from theoretical into urgent.

### DNS rebinding

The guard resolved a customer-supplied hostname, verified every address was
public, then handed the **hostname** to `HttpClient` — which resolved it again.
An attacker controlling DNS answers public for the check and
`169.254.169.254` for the connection.

`HttpClient` has no per-request address override, so Continuum installs an
`InetAddressResolver` through the JDK's resolver SPI (JEP 418). While a pin is
held, that host resolves to exactly the vetted address.

Pins are **thread-local**: activities run on a shared pool, and a pin visible to
a sibling would misdirect an unrelated request to somewhere it was never checked
against — worse than the hole it closes. A test asserts the JDK actually loaded
the provider, because without that registration the guard degrades silently.

### Unbounded responses

`BodyHandlers.ofString()` materialised the whole body before truncating, so one
endpoint returning gigabytes exhausted the heap every activity shares. Reads are
now bounded at 4 MB.

Both live in `GuardedHttpSender`, shared by the workflow activity and the
specialist invoker. **Two copies of a security control is one copy too many.**

---

## Phase 1 — one specialist, end to end

### Connections: bring your own key

Continuum holding one Roboflow account for every customer would be a cost
liability, a rate limit shared between strangers, and a data-handling promise
Continuum cannot make on a provider's behalf.

Secrets go into the existing AES-GCM vault under a namespaced key and are
decrypted only at call time. **No endpoint returns one** — not to the console,
not to a log, not in an error.

A connection is `UNVERIFIED` until something has actually worked through it. The
distinction between `UNVERIFIED` and `FAILING` is deliberate: never worked is a
setup mistake, stopped working is an incident.

### The probe: honest automatic configuration

You cannot reliably know a model's response shape from a catalogue entry.
Roboflow is uniform, Hugging Face varies by task, a custom endpoint is whatever
its author chose.

So Continuum **sends one real request and keeps the answer.** The console shows
two panes side by side:

| What your model returned | What Continuum understood |
|---|---|
| The raw response body, verbatim | The normalised findings, with confidence bars |

A developer who can see both knows whether the integration works. A developer
reading a description of it is guessing.

A specialist stays `DRAFT` until a probe succeeds. Letting an unproven one into a
pipeline moves the failure from configuration time, where a developer is looking
at it, to request time, where a customer is.

`UNPARSEABLE` is a distinct outcome from `FAILED`: the endpoint answered, so the
credential and path are right and the *adapter* is what needs attention.

### Adapters: Roboflow is the first, not the design

A `SpecialistProvider` answers four questions — where it lives, how the
credential is presented, how a call is shaped, how results map to one normalised
`Finding`.

- **Roboflow** — credential in a query parameter (unusual, hence the `QUERY` auth
  style), image as a base64 body, confidence threshold pushed *down* to the
  provider so it filters rather than shipping detections back to be discarded.
- **Custom HTTP** — what stops this being a Roboflow feature. A developer's own
  FastAPI, SageMaker or internal service gets the same pipeline and the same
  output shape. Parsing is forgiving because nobody agreed a schema: it
  recognises prediction lists, single label/score pairs and bare score maps.

**Neither adapter throws on a surprising response.** "No signal" is something the
confidence policy can act on; an exception mid-request is not.

### Confidence filtering happens here, not in the prompt

A finding below the threshold is **withheld entirely** rather than passed along
with a caveat. Passing it invites the model to reason about it anyway, which is
how a 0.14 detection becomes a paragraph of advice.

But the count of what was dropped is reported, because *"nothing found"* and
*"nothing confident enough"* are different situations.

### Continuum Trace, from the start

Recorded from the first specialist rather than retrofitted, because a trace
assembled afterwards is a reconstruction — it can only show what was separately
logged, in whatever order the logs allow.

Every invocation writes a step: which specialist, what it found, what fell below
threshold, how long it took. `GET /specialists/traces/{id}` returns the chain,
scoped by developer as well as trace id — a trace id is a UUID, but relying on
unguessability for access control is how tenant data leaks.

This is the substrate for the visible pipeline an external app shows its user.

---

## API

| Method | Path | Purpose |
|---|---|---|
| `GET` | `…/specialists/providers` | What can be connected to, and what each needs |
| `GET` `POST` `DELETE` | `…/specialists/connections` | Credentials, BYOK |
| `POST` | `…/specialists/connections/{id}/secret` | Rotate |
| `GET` `POST` `PUT` `DELETE` | `…/specialists` | Register and configure |
| `POST` | `…/specialists/{id}/probe` | Send one real request, keep the answer |
| `GET` | `…/specialists/traces/{traceId}` | The visible chain |

---

## Verified live

Against a stand-in detector endpoint:

```
connection  → Injury detector endpoint          UNVERIFIED
specialist  → Animal injury detector            DRAFT
probe       → READY · HTTP 200 · 72 ms
              understood:  open wound  0.91
                           bleeding    0.67
              withheld (below 0.30):   1        ← fracture 0.14
              credential_seen: true             ← endpoint echoed it back
connection  → VERIFIED                          ← self-verified by the probe
```

23 routes clean across desktop dark, mobile dark, desktop light.

---

## One bug, found by driving it

Connection creation validated the auth parameter **before** falling back to the
adapter's own default — so creating a Roboflow connection the obvious way was
rejected for omitting `api_key`, a value the adapter already knew. Fixed, with a
regression test.

---

## Tests

- `PinnedDnsResolverTest` (7) — the JDK loaded the provider; a pinned host
  resolves to exactly the vetted address; **a pin never leaks to another thread**;
  unrelated hosts fall through.
- `GuardedHttpSenderTest` (9) — private ranges, loopback, cloud metadata and
  non-HTTP schemes refused; the approved address is carried forward, not just
  validated; self-hosted deployments can opt in.
- `SpecialistProviderTest` (14) — normalisation, ordering, surprising responses,
  the credential styles, URL joining.
- `SpecialistInvokerTest` (8, against a real HTTP server) — credential presented
  in the right place and never in the URL when it should be a header; **a
  low-confidence finding never reaches the caller**; a provider error is reported
  rather than thrown; a refused target fails without throwing.

Full suite: **316 passing.**

---

## Next: Phases 2–6

| Phase | What |
|---|---|
| 2 | The Hub — search provider catalogues, one-click add |
| 3 | **Pipelines** — input → specialist → context builder → model, compiled to the durable workflow engine |
| 4 | **Confidence policy** — high passes through, medium makes the model hedge, low asks for better input or declines |
| 5 | Specialist routing — which specialist, decided by type, rule, or the model itself |
| 6 | Output verification — does the advice match the findings |

Phase 3 is where an external app sends an image and gets advice without knowing
Roboflow exists. Phase 4 is what stops a 0.31 detection becoming confident
first-aid advice.
