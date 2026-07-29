# File ingestion and live tool discovery

This document covers the layer added on top of the Tool architecture: how a file
gets into Continuum, how a PDF is read without an OCR vendor, and how the Hub
searches a provider's real model directory instead of only its own shelf.

It is also the honest record of what has and has not been run against a real
service. The short version is at the bottom under **Verification status**, and
the one thing to take from it is:

> **`LIVE_UNVERIFIED` — this deployment cannot reach Roboflow.** The discovery
> client is written against the documented contract and tested against
> constructed fixtures. It has never received a response from the real service,
> and nothing in the code or the console claims otherwise.

---

## 1. Getting a file in

### The problem

A pipeline's input was a JSON map that went to the adapters verbatim. Sending
Continuum a PDF meant the caller base64-encoded it themselves and routed it to an
OCR endpoint they had to run. Both halves of that were unnecessary work.

### Upload

`POST /api/gateway/pipeline/{name}/upload` and
`POST /api/portal/developer/pipelines/{name}/run/upload` accept
`multipart/form-data` with a `file` part, an optional `prompt`, and optional
extra JSON `input`. Same pipeline, same trace, same response shape — only the way
the bytes arrive is different.

`UploadedInput` decides which input key the file lands under, because that is
what decides which tools ever see it:

| Detected | Key |
| --- | --- |
| `%PDF-` | `documentBase64` |
| PNG / JPEG / GIF magic | `imageBase64` |
| ID3 / OggS / fLaC / RIFF…WAVE | `audioBase64` |
| `text/*`, JSON, CSV | `text` (as text, not base64) |
| unrecognised | `documentBase64` |

**Bytes beat the declared content type.** Browsers send
`application/octet-stream` for PDFs often enough that trusting the header would
strand perfectly readable documents. The content type and then the filename are
consulted only when the magic bytes say nothing.

Extra JSON input is merged *underneath* the file: a stale `documentBase64` in the
JSON cannot displace the file the caller actually attached.

The container refuses anything over 25MB before it is read into memory
(`spring.servlet.multipart`), and `UploadedInput` enforces its own limit again.

### Automatic PDF reading

`InputIngestor` runs **before the first tool** in `PipelineService.run`. It does
exactly two things: recognise what arrived, and extract a PDF's text layer in
process via `PdfTextTool` (Apache PDFBox 3.0.3).

Two properties matter more than the extraction itself.

**It adds; it never consumes.** The original base64 document stays in the map
untouched. Extraction succeeding is not a reason to throw the file away — a
downstream OCR tool needs the pages, not the text, and ingestion cannot know
whether one is in the chain. Text is added with `putIfAbsent`, so a caller who
supplied their own `text` keeps it.

**A scan is not a blank page.** `PdfTextTool` distinguishes:

| Outcome | Meaning |
| --- | --- |
| `OK` | text recovered |
| `NO_TEXT_LAYER` | parsed cleanly, contains no text — almost certainly a scan. **An OCR tool can rescue this.** |
| `ENCRYPTED` | password-protected; not opened |
| `UNREADABLE` | not a PDF, or corrupt |
| `TOO_LARGE` | over 20MB decoded |

Collapsing `NO_TEXT_LAYER` into an empty success would make a fixable problem
look like an empty document. When extraction fails the result still produces
`Evidence.note(detail)`, so the pipeline can say *why* rather than silently
finding nothing.

Recovered text is `Evidence.text(...)`, **unscored**. PDFBox reports what the
file says, not how sure it is; attaching a confidence would be inventing a number
that something downstream would then filter on.

Ingestion is traced as an `INPUT` step — but only when it actually did something.
An ingestion row on every JSON request would be noise in every trace.

---

## 2. Live tool discovery

### The seam

`CatalogueSource` gained four things, all defaulted so the existing
`CuratedCatalogue` needed no changes:

```java
default boolean live()                     { return false; }
default String  unavailableReason()        { return null;  }
default CatalogueEntry byId(String id)     { return null;  }
default void    invalidate(String devId)   { }
```

`live()` is what lets the console tell a **template** (a shape that ships with
Continuum, which you point at your own endpoint) from a **live** result (a model
that exists in a provider's directory right now). Showing both without saying
which is which would leave a developer unable to tell a one-click install from a
form they have to finish.

### The Roboflow source

Three classes, deliberately separated:

- **`RoboflowDiscoveryClient`** — an interface, and the only thing that touches
  the network. It exists so the mapping logic can be tested without one, which
  is the only way it can be tested here at all. It **must never throw**: a
  network failure, a refused credential and an unreadable body are three
  different things the Hub has to show differently, and an exception collapses
  them into one. Hence `Status`: `OK`, `NO_CREDENTIAL`, `UNREACHABLE`,
  `UNAUTHORISED`, `UNREADABLE`, `PROVIDER_ERROR`.
- **`HttpRoboflowDiscoveryClient`** — the HTTP implementation, over the existing
  `GuardedHttpSender` (so SSRF and DNS-rebinding protection is not re-invented).
  Base URL is configurable (`continuum.discovery.roboflow.base-url`) so a
  deployment behind a proxy, or a test against a local fixture server, needs no
  code change.
- **`RoboflowCatalogueSource`** — turns discovered models into catalogue entries.
  Discovery only: it never calls a model, parses a prediction, or stores a
  credential.

### No second execution path, no second key store

Two constraints shaped this and both are load-bearing.

**Installing a discovered model goes through the existing Hub install flow**,
which creates a connection through the existing vault and a specialist that the
existing `RoboflowProvider` invokes. There is no second Roboflow calling path.

**Discovery reuses the tenant's existing Roboflow credential.** Asking for a
second key purely to search would be irritating *and* a second place for a secret
to live. `SpecialistConnectionService.discoveryKeyFor(developerId, provider)` is
the single accessor, resolving through the same vault and the same
`credentialRef` convention as call-time secret resolution.

A tenant with no Roboflow connection gets a source marked unavailable **with the
reason**, not an empty result list. "No results for wound" and "your Roboflow key
is not connected" send a developer to completely different screens.

### The key never leaves

The API key is never returned by a GET, never in a search result, never logged,
never in trace output, never in an error message, never in model configuration
JSON, never sent to Gemini, never sent to an external developer's application.
Specifically:

- the 10-minute search cache holds **entries only, never the key** — caching a
  decrypted secret to save a vault read trades a real security property for a
  negligible one;
- `SearchResponse.detail` is written for a developer and never carries a
  credential, a host name, or anything about the deployment's network topology
  (the underlying exception can name both the host and the proxy, so it is
  logged at debug and not surfaced);
- `RoboflowCatalogueSourceTest` asserts the key appears in no rendered entry and
  no unavailability reason, and the live IT asserts it appears in neither
  `detail` nor `rawBody`.

### Cross-tenant isolation

Directory ids are global, so `byId` is keyed by `developerId + "|" + id`. Without
that, one tenant could install an entry only another tenant's credential had ever
discovered. There is a test for exactly this.

### Parsing is tolerant, but never inventive

Since the real response shape has not been observed, every value is looked for
under several plausible names and every optional field may be absent; keys are
matched with separators and case normalised, so `display_name` and `displayName`
both resolve. A body shaped slightly differently from the documentation yields a
partial entry the console can still show, rather than an exception or an empty
list. The provider's raw body is returned alongside so a developer whose search
comes back thin can see exactly what arrived.

What it will **not** do is guess. `RoboflowProvider` needs `project/version` to
build a call. A directory entry that does not carry one is still listed — it is a
real model — but it declares `modelPath` in `needs`, so the Hub asks for the path
instead of installing something that 404s on first probe.

### Caching and refresh

Searches are cached for 10 minutes per `tenant|query|limit`, capped at 200
entries. An empty query never fans out: there is no meaningful "everything" in a
directory of hundreds of thousands of projects, and the round trip would be paid
on every page load.

`POST /api/portal/developer/specialists/catalogue/refresh` clears every live
source for the tenant and searches again. A cache a developer cannot clear is a
bug report: they publish a model, search for it, and are told for ten minutes
that it does not exist.

---

## 3. What changed

### New code

| File | What |
| --- | --- |
| `tool/builtin/PdfTextTool.java` | in-process PDF text extraction (PDFBox) |
| `tool/InputIngestor.java` | pre-tool input recognition and PDF ingestion |
| `tool/UploadedInput.java` | multipart file → pipeline input map |
| `specialist/discovery/RoboflowDiscoveryClient.java` | network-isolating interface |
| `specialist/discovery/HttpRoboflowDiscoveryClient.java` | tolerant HTTP implementation |
| `specialist/discovery/RoboflowCatalogueSource.java` | discovery → catalogue entries |

### Changed, all backward compatible

- `CatalogueSource` — four **default** methods; `CuratedCatalogue` unchanged.
- `SpecialistCatalogue` — fan-out reports per-source `type`, `results` and
  `reason`; `resolve()` finds entries from any source, so install works for
  discovered entries; `refresh()` added.
- `SpecialistConnectionService` — `discoveryKeyFor(...)` added; nothing removed.
- `PipelineService` — ingestion before the step loop; the existing JSON path is
  unaffected when no document is present.
- `PipelineController`, `PipelineGatewayController` — new upload routes; the
  existing JSON routes are byte-for-byte identical in behaviour.

### Database

**No migration.** Ingestion is traced under the existing `INPUT` trace kind
rather than adding an enum value — it genuinely is input handling, and a schema
change for a label would not have earned itself.

### API

| Method | Path | New? |
| --- | --- | --- |
| POST | `/api/gateway/pipeline/{name}/upload` | new |
| POST | `/api/portal/developer/pipelines/{name}/run/upload` | new |
| POST | `/api/portal/developer/specialists/catalogue/refresh` | new |
| GET | `/api/portal/developer/specialists/catalogue` | extended — `sources[]` now carries `type`, `live`, `results`, `reason` |

Every existing endpoint keeps its shape. The catalogue change is additive.

### UI

- **Hub** — per-source chips showing `live` / `template`, result counts, and an
  off state; an explicit panel naming each unavailable source *and its reason*; a
  Refresh button when any live source is present; a `live · Roboflow` or
  `template` badge on every entry card.
- **Pipelines → Try it** — a file picker beside the base64 textarea. Choosing a
  file disables the textarea and says so.

---

## 4. Verification status

| Claim | Status |
| --- | --- |
| PDF text extraction | ✅ **Verified.** `PdfTextToolTest` builds real PDFs with PDFBox and reads them back. Runs in process — no service to be unreachable. |
| `NO_TEXT_LAYER` vs blank | ✅ Verified against a real page-without-text PDF. |
| Ingestion never consumes the original | ✅ Verified. |
| Upload key routing | ✅ Verified (`UploadedInputTest`, magic bytes and fallbacks). |
| Discovery response mapping | 🟡 **Fixture-tested only.** `RoboflowDiscoveryMappingTest` uses **constructed** bodies written from the documented contract. None is a captured response and none is presented as one. |
| Credential handling, cache, tenant isolation | ✅ Verified (`RoboflowCatalogueSourceTest`), with a fake client. |
| **Live Roboflow search** | ❌ **`LIVE_UNVERIFIED` — this environment cannot reach Roboflow.** |

Measured from this deployment:

```
universe.roboflow.com -> 000   (refused at CONNECT)
api.roboflow.com      -> 000   (refused at CONNECT)
detect.roboflow.com   -> 000   (refused at CONNECT)
repo.maven.apache.org -> 200
```

`RoboflowLiveDiscoveryIT` is the one test that talks to the real service. It is
gated on `ROBOFLOW_API_KEY` and **skips** here — a skipped test is not a passing
test. Run it somewhere with network access to move the mapping from "correct
against constructed fixtures" to "correct against the service":

```
ROBOFLOW_API_KEY=… mvn test -Dtest=RoboflowLiveDiscoveryIT
```

If it fails there, the fixtures are what to fix: they encode an assumption about
a contract, and the service is the authority. No credential appears in any source
file, fixture or properties file.
