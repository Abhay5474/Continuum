# Continuum Tool / Plugin Architecture

**Status:** Phase 1 implemented and verified live (this document marks exactly what
is built and what is not). · **Migration:** `V41__tool_kind.sql`
**Constraint honoured:** the Specialist implementation is *preserved and
generalised*, never replaced.

---

# 1. Executive architecture summary

Continuum already has a working tool runtime. It is called the Specialist layer,
it has an adapter abstraction, a credential vault, probe-before-use, conditional
routing, a confidence policy, output verification and a visible trace — and it
was **shaped entirely around one return type**: `Finding(label, confidence, region)`.

That single type was the whole problem. Measured against the real parser before
this work, an OCR endpoint answering `{"text": "INVOICE 4471"}` produced **zero
findings**, as did a transcript and a document extractor, because `text` is not a
label and a paragraph is not a class name. Three Hub entries were installable,
probeable, markable READY — and incapable of contributing one word to a prompt.

**The architecture is therefore not a new system. It is one new type and the
consequences of admitting it.**

```
                    CONTINUUM TOOL SYSTEM
                             │
                    Tool (ToolKind + Evidence)
                             │
        ┌────────────────────┴────────────────────┐
        │                                         │
  SPECIALIST FAMILY                        CONTENT FAMILY
  (scored, already shipped)                (unscored, newly possible)
        │                                         │
  ┌─────┴─────┬──────────┐          ┌─────┬───────┴────┬───────────┐
DETECTION  CLASSIFY  MODERATION    OCR  TRANSCRIPTION  EXTRACTION  TRANSFORMATION
  │            │         │          │        │            │            │
RoboflowProvider ────────┴──────────┴────────┴────────────┴────────────┘
GenericHttpProvider              (all through the SAME adapters,
                                  connections, vault, probe, routing,
                                  policy, verification and trace)
```

Two decisions carry the design:

1. **`Evidence` supplements `Finding`; it does not replace it.** `Finding` is
   retained, `RoboflowProvider` is unmodified, and `SpecialistProvider.parse()`
   still exists. A `default parseEvidence()` lifts findings into detection
   evidence, so **every adapter written before this change keeps working with no
   edit at all**.

2. **Confidence is nullable, and that is the entire point.** A detector says
   "wound, 0.87". An OCR engine says "INVOICE 4471" and has no opinion about how
   sure it is. Storing `1.0` asserts certainty nobody claimed; storing `0.0`
   asserts the opposite and gets the evidence discarded by the first threshold it
   meets. `null` means **unscored**, a third state — the same distinction the
   layer already draws between "found nothing" and "never looked".

Everything else in this document follows from those two.

---

# 2. Conceptual model

## 2.1 Naming decision

**Recommendation: keep "Specialist" as a user-facing term for scored analysis
tools, and introduce "Tool" as the umbrella.** Do not rename the existing tables,
APIs or UI.

Reasoning: renaming `specialist` → `tool` across five tables, nine endpoints and
two console pages buys a word and costs a migration, a compatibility shim on
every route, and a period where the documentation and the database disagree. The
`ToolKind` column achieves the entire conceptual shift — it says *what kind of
tool this row is* — for the price of one additive column.

## 2.2 Hierarchy

| Concept | What it is | Where it lives now |
|---|---|---|
| **Tool** | A configured, credentialled, probed unit of work a pipeline can call | `specialist` table + `ToolKind` |
| **ToolKind** | What shape of work it does, and therefore what evidence it returns | `io.continuum.tool.ToolKind` ✅ built |
| **Plugin** | *Rejected as a term.* Implies code loaded into the process. Every Continuum tool is a remote HTTP call, and calling it a plugin would invite someone to propose in-process execution, which is a sandboxing problem we do not have and must not acquire | — |
| **Provider** | A vendor or endpoint family: Roboflow, a custom host | `specialist_connection.provider` |
| **Adapter** | The code that shapes a call and reads a response for one provider | `SpecialistProvider` impls |
| **Connection** | Credential + base URL + auth style for one provider, per tenant | `specialist_connection` |
| **Evidence** | One thing a tool observed, in any shape | `io.continuum.tool.Evidence` ✅ built |
| **Finding** | Scored, labelled evidence. A *subtype by projection*, not a parallel type | `SpecialistProvider.Finding` |
| **Pipeline step** | A tool plus the condition under which it runs | `PipelineStep` |
| **Context** | The prose + structured facts handed to the model | `ContextBuilder.Context` |
| **Verification** | Checking the answer against the evidence it was built from | `AnswerVerifier`, `HedgeDetector` |

**Finding is a projection of Evidence, not a sibling.** `Evidence.isFindingShaped()`
is true only for scored, labelled detection/classification evidence. Everything
else would have to invent a label or a confidence to fit — and inventing a
confidence is precisely what this design exists to prevent.

---

# 3. Database schema

## 3.1 What changed (built)

```sql
-- V41__tool_kind.sql   ✅ APPLIED AND VERIFIED
ALTER TABLE specialist
    ADD COLUMN IF NOT EXISTS tool_kind VARCHAR(24) NOT NULL DEFAULT 'DETECTION';

UPDATE specialist SET tool_kind = 'CLASSIFICATION' WHERE input_kind = 'text';
UPDATE specialist SET tool_kind = 'DETECTION'      WHERE input_kind = 'image';

CREATE INDEX IF NOT EXISTS idx_specialist_tool_kind
    ON specialist (developer_id, tool_kind);
```

**Additive and reversible.** `DROP COLUMN tool_kind` returns the table to its
previous shape and every existing row keeps working, because `DETECTION` is what
they all were. The backfill infers only what the row already stated; guessing
further would relabel a developer's tool behind their back.

## 3.2 Tables reused unchanged

| Table | Role | Change |
|---|---|---|
| `specialist_connection` | provider, base URL, auth style, encrypted secret, health | **none** |
| `pipeline` | name, input kind, enabled, policy, thresholds, verification mode | **none** |
| `pipeline_step` / routing rules | step + condition + pattern | **none** |
| `trace` / `trace_step` | the visible chain | **none** — evidence rides inside the existing JSON `detail` |
| `specialist` | the tool row | **one added column** |

The trace decision is worth stating: `trace_step.detail` is already a JSON blob,
so per-step evidence needed **no schema change at all**. A new `tool_evidence`
table was considered and rejected — it would duplicate what the trace already
stores, and create a second place for the two to disagree.

## 3.3 Schema NOT built (future phases, designed here)

```sql
-- Phase 3: real provider/model discovery. NOT BUILT.
CREATE TABLE provider_directory_entry (
    id             BIGSERIAL PRIMARY KEY,
    provider       VARCHAR(40)  NOT NULL,      -- 'roboflow'
    external_id    VARCHAR(300) NOT NULL,      -- 'animal-injury/3'
    title          VARCHAR(300) NOT NULL,
    task_type      VARCHAR(24)  NOT NULL,      -- maps to ToolKind
    input_kind     VARCHAR(16)  NOT NULL,
    fetched_at     TIMESTAMPTZ  NOT NULL,
    UNIQUE (provider, external_id)
);
-- Cached, not live-queried per keystroke: a directory that hits a vendor API on
-- every search is a rate-limit incident waiting to happen.

-- Phase 4: tool versioning. NOT BUILT.
ALTER TABLE specialist ADD COLUMN version INTEGER NOT NULL DEFAULT 1;
ALTER TABLE specialist ADD COLUMN superseded_by BIGINT REFERENCES specialist(id);
-- A pipeline pins the version it was built against, so re-probing a tool against
-- a changed endpoint cannot silently alter a running pipeline's behaviour.

-- Phase 5: tool permissions. NOT BUILT.
CREATE TABLE tool_permission (
    developer_id VARCHAR(64) NOT NULL,
    specialist_id BIGINT     NOT NULL REFERENCES specialist(id) ON DELETE CASCADE,
    scope        VARCHAR(24) NOT NULL,   -- 'PII_PERMITTED' | 'NO_EXTERNAL_EGRESS'
    PRIMARY KEY (developer_id, specialist_id, scope)
);
```

## 3.4 Does `Finding` need generalising?

**Yes — supplemented, not replaced.** The implemented model:

```java
record Evidence(Kind kind, String label, Double confidence, String text,
                Map<String, Object> attributes)

enum Kind { DETECTION, CLASSIFICATION, TEXT, FIELD, ROW, NOTE }
```

| Kind | Carries | Serves |
|---|---|---|
| `DETECTION` | label + confidence + region | Roboflow, custom detectors |
| `CLASSIFICATION` | label + confidence | classifiers, moderation |
| `TEXT` | text (+ language, page, duration) | **OCR, transcription** |
| `FIELD` | name + value | **document extraction** |
| `ROW` | a record map | **CSV/JSON transformation** |
| `NOTE` | text | tool remarks, validation messages |

Not persisted as its own table. Evidence is per-execution, and the trace already
stores it.

---

# 4. Backend interfaces

## 4.1 Built

```java
// io.continuum.tool
public enum ToolKind { DETECTION, CLASSIFICATION, MODERATION,
                       OCR, TRANSCRIPTION, EXTRACTION, TRANSFORMATION,
                       VALIDATION, CUSTOM;
    boolean isScored();          // the property everything downstream branches on
    static ToolKind of(String);  // unknown → CUSTOM, the kind that assumes least
}

public record Evidence(Kind kind, String label, Double confidence,
                       String text, Map<String,Object> attributes) {
    boolean scored();
    boolean isFindingShaped();
    static List<Evidence> normalise(List<Evidence>);   // scale-fixes scored, keeps unscored
}
```

```java
// io.continuum.specialist — GENERALISED, backwards compatible
public interface SpecialistProvider {
    List<Finding> parse(Object body);                  // unchanged, still abstract

    default List<Evidence> parseEvidence(Object body) { // NEW, defaulted
        return parse(body).stream()
            .map(f -> Evidence.detection(f.label(), f.confidence(), f.region()))
            .toList();
    }
}
```

**What the default buys:** `RoboflowProvider` required **zero edits**. Any
adapter a developer wrote against the old interface still compiles and still
works. An adapter only overrides `parseEvidence` when its provider can return
something that is not a labelled score.

**What each interface must not do:**

- an adapter **must not throw** on an unrecognised shape — zero evidence is an
  outcome the policy handles; an exception mid-request is not;
- an adapter **must not** invent a confidence to make text fit `Finding`;
- `normalise` **must not clamp** an out-of-range confidence — a clamped 12500
  becomes a confident 1.0 and defeats every threshold downstream. It drops.

## 4.2 Not built (designed)

```java
public interface ProviderDirectory {          // Phase 3
    List<DirectoryEntry> search(String query, ToolKind kind, int limit);
    boolean available();                       // false when the vendor is unreachable
}

public interface ToolPermissionPolicy {        // Phase 5
    Decision mayReceive(SpecialistEntity tool, InputClass inputClass);
}
```

---

# 5. Runtime flow

## 5.1 Implemented, per family

```
External app → POST /api/gateway/pipeline/{name}   { input: {...}, prompt }
  │
  ├─ pipeline resolved, tenant-scoped, enabled check
  ├─ trace opened (INPUT step)
  │
  ├─ FOR EACH STEP:
  │    ├─ StepRouter.decide(condition, findingsSoFar, ranSoFar, prompt, inputKind)
  │    │     ALWAYS | IF_PREVIOUS_FOUND | IF_PREVIOUS_EMPTY
  │    │     | IF_PROMPT_MATCHES | IF_INPUT_IS
  │    │     skipped steps are RECORDED, never silent
  │    ├─ connection resolved → vault decrypts secret
  │    ├─ adapter.buildCall(connection, modelPath, input)
  │    ├─ auth applied by style: BEARER | HEADER | QUERY | NONE
  │    ├─ GuardedHttpSender.send(...)   SSRF + DNS-rebind + bounded body
  │    ├─ adapter.parseEvidence(body)          ← THE GENERALISED STEP
  │    ├─ Evidence.normalise(...)              scale-fix scored, keep unscored
  │    ├─ threshold filter: applied ONLY to scored evidence
  │    └─ trace step written with full evidence + withheld count
  │
  ├─ ContextBuilder.build(...)   → prose + structured, per evidence kind
  ├─ ConfidencePolicy.decide(...) → STRONG|MEDIUM|WEAK|NONE|UNAVAILABLE|UNSCORED
  ├─ model called (or DECLINE without calling)
  ├─ HedgeDetector  — did the model do what it was told?
  ├─ AnswerVerifier — is the answer anchored to the evidence?
  └─ response + full visible chain
```

**The one behaviour that changed for existing pipelines:** none. A detector
produces `DETECTION` evidence, `isScored()` is true, the threshold applies
exactly as before, and the bands are computed from the same numbers.

## 5.2 The threshold rule (critical)

```java
if (!e.scored() || e.confidence() >= specialist.getMinConfidence()) keep(e);
else                                                                 dropped++;
```

**A confidence threshold is a statement about scores.** Applying one to unscored
evidence would discard every OCR line and every transcript, because none has a
number to compare — the filter would silently delete whole classes of tool. This
is the single most important line in the change.

## 5.3 Per-family walkthroughs

| Family | Input key | Adapter | Returns | Evidence | Threshold applies? |
|---|---|---|---|---|---|
| Image detection | `imageBase64` | `RoboflowProvider` | `predictions[]` | `DETECTION` | yes |
| Custom detector | any JSON | `GenericHttpProvider` | `predictions[]`/`{label,score}` | `DETECTION`/`CLASSIFICATION` | yes |
| OCR | caller-supplied | `GenericHttpProvider` | `{text}` or `text/plain` | `TEXT` | **no** |
| Transcription | caller-supplied | `GenericHttpProvider` | `{text, language, duration}` | `TEXT` + attrs | **no** |
| Doc extraction | caller-supplied | `GenericHttpProvider` | `{fields:{…}}` or flat map | `FIELD` × n | **no** |
| Transformation | any JSON | `GenericHttpProvider` | `{rows:[…]}` | `ROW` × n | **no** |
| Moderation | text | `GenericHttpProvider` | score map | `CLASSIFICATION` | yes |

### The parser's precedence order (and why it stops at the first match)

1. recognised results list (`predictions`, `detections`, `results`, …)
2. text keys (`text`, `full_text`, `transcript`, `content`, …)
3. field maps (`fields`, `entities`, `data`, …)
4. row lists (`rows`, `records`, `items`, …)
5. a single `{label, score}`
6. a bare score map — **only when every value is a number in [0,100]**
7. a flat map → fields

Step 6's guard is a real bug fix. Without it, `{"invoiceNumber":"4471","total":12500}`
was claimed as a score map, `total` was read as a confidence of 12500, and
normalisation then correctly dropped it — **losing the entire response**. Verified
before and after.

Falling through after a match is how an earlier version reported an envelope's
own `bytes_received` field as a finding at 128.

---

# 6. Hub / marketplace

## 6.1 The five states — exact definitions

| State | Meaning | Where |
|---|---|---|
| **Catalogue entry** | A *template*. Provider, auth style, expected response shape, sensible threshold, and what you must still supply. **Not a claim any model exists** | `CuratedCatalogue` ✅ |
| **Connection** | Credential + base URL + auth style, encrypted, per tenant | `specialist_connection` ✅ |
| **Installed tool** | A `specialist` row: connection + model path + kind + threshold. Status `DRAFT` | ✅ |
| **Probed tool** | One real request made; raw body and parsed evidence both stored. Status `READY` / `UNPARSEABLE` / `FAILED` | ✅ |
| **Pipeline-ready** | `READY` **and** referenced by an enabled pipeline | ✅ |

A tool cannot enter a pipeline before `READY`. Deferring the probe only moves the
discovery of a wrong credential from configuration time, where a developer is
looking at it, to request time, where a customer is.

## 6.2 Search — built

Entries score against title (10), exact tag (6), partial tag (3), description (2),
provider (4), and now **tool kind (8)** and **input kind (4)**. Typing `ocr`,
`transcription` or `extraction` names a kind of work, which is the strongest
possible signal about intent.

## 6.3 Roboflow model discovery — NOT BUILT, and why

The requested flow — search "animal injury", real Roboflow models appear, pick
one — **is not implemented and cannot be implemented in this environment.** The
network policy refuses every model host at CONNECT:

```
universe.roboflow.com   403
api.roboflow.com        403
huggingface.co          403
pypi.org                200   ← only package registries permitted
```

The Universe API has never been called once from here. Writing a client against a
response shape nobody has seen would produce a feature that looks finished and
404s on first contact.

**What exists instead is the seam.** `CatalogueSource` is an interface;
`CuratedCatalogue` is one implementation. Adding live discovery is a second
implementation plus the `provider_directory_entry` cache in §3.3 — **not a
rewrite**. `RoboflowProvider` is untouched by it either way: discovery supplies a
model id, and the adapter already knows what to do with one.

This is an environment setting on your side, not a code limitation.

---

# 7. Credentials

Unchanged and reused wholesale — the existing design is sound.

| Concern | Implementation | Status |
|---|---|---|
| BYOK | developer supplies their own key per connection | ✅ |
| Encryption at rest | `CredentialVaultService`, per-tenant | ✅ |
| Auth styles | `BEARER` / `HEADER` / `QUERY` / `NONE` | ✅ |
| Roboflow specifics | `QUERY` + `api_key`, because Roboflow puts it in the URL | ✅ |
| Rotation | update the connection; tools re-probe against the new secret | ✅ |
| Never logged | secret injected at send time, never in `Call`, trace or probe body | ✅ |
| Reuse | install can reuse an existing connection of the **same provider** | ✅ |
| Provider mismatch | refused with a specific message — a Roboflow key presented as a bearer token to a custom endpoint fails in a way that looks like a broken model | ✅ |
| UI display | connection shows *that* a secret is stored, never its value | ✅ |
| OAuth | **not built.** No current provider requires it | ❌ |

---

# 8. UI screens

## 8.1 Built

**Hub** (`/pipelines` → Hub) — search box, per-entry card showing
`{input} in · {Tool kind}` and either `suggests 0.40` or **`no confidence`** with
a tooltip explaining that this kind returns content rather than scored
detections. Expanding gives the honest note, the fields you must supply, base URL
/ model path / secret, and a reuse-existing-connection picker.

**Specialists** — each tool now reads
`{modelPath} · {input} in · {Tool kind} · ignores below 0.40`, or for unscored
kinds `· returns content, so no confidence threshold applies`.

**Probe panel** — two panes, unchanged in principle, generalised in content:

| Left: what your model returned | Right: what Continuum understood |
|---|---|
| raw body, verbatim, HTTP status, latency | evidence rendered **by kind** |

The right pane now renders:
- `TEXT` — a scrollable monospace block, labelled `text`, marked
  *"no confidence — extracted content"*
- `FIELD` — `name: value` rows, labelled `field`
- `ROW` — the record, labelled `row`
- `DETECTION`/`CLASSIFICATION` — the original confidence bar, **unchanged**
- unscored detections — the word `unscored` instead of a bar

**A confidence bar is drawn only for evidence that has a confidence.** An empty
bar would read as "zero percent sure", which is the opposite of "no measurement
was taken".

## 8.2 Not built

Filter chips by kind/provider/input; sample-file upload widget; version history;
a dedicated "why this tool?" panel. All designed above; none implemented.

---

# 9. Trace visibility

## 9.1 Built

Every step already writes a `TraceStepEntity` with a JSON `detail`. That detail
now carries:

```json
{ "specialist": "ocr-tool", "model": "ocr",
  "evidence": [ { "kind": "TEXT", "confidence": null, "scored": false,
                  "text": "INVOICE 4471…", "attributes": {"language":"en"} } ],
  "findings": [],
  "unscored": 1,
  "belowThreshold": 0,
  "minConfidence": 0.6 }
```

`findings` is retained for consumers written against the Specialist layer; it is
now the scored, labelled subset. `evidence` is the full picture — **a trace that
showed only findings would show nothing at all for an OCR step.**

Skipped steps are recorded with their condition and the reason. Withheld evidence
is counted in `belowThreshold`. The assembled prompt is stored verbatim in the
`ENRICHMENT` step, because when an answer is wrong the question is always "what
was the model actually told?" and a reconstruction is not an answer.

## 9.2 Not built

Per-step input previews with redaction rules; a "why was this tool chosen?"
panel; a diff view between what a tool returned and what survived the threshold.

---

# 10. Failure semantics

| Case | Pipeline behaviour | Tool status | What the developer sees | Built |
|---|---|---|---|---|
| Not configured | step contributes an error; others continue | `DRAFT` | "Specialist is not configured" | ✅ |
| Unverified | cannot be added to a pipeline | `DRAFT` | probe required | ✅ |
| **Sample required** | probe does not run; **no request made** | `DRAFT` | "Continuum has no sample recording a real transcription service would accept — supply a short clip under `audioBase64`" | ✅ **new** |
| Probe failed | as above | `FAILED` | provider's own message, verbatim | ✅ |
| Unparseable | endpoint answered, nothing read | `UNPARSEABLE` | *distinct from FAILED* — credential and path are right, the **adapter** needs attention | ✅ |
| Provider unavailable | step errors, others continue; `analysisRan` false if all fail | — | named in the prompt as incomplete evidence | ✅ |
| Empty response | zero evidence → `NONE` band | — | "ran and found nothing" | ✅ |
| Low confidence | filtered, counted as withheld | — | "n signals below threshold, withheld" | ✅ |
| **No confidence at all** | **`UNSCORED` band, action PASS** | — | "evidence recovered, no threshold applies" | ✅ **new** |
| Timeout | per-tool `timeoutSeconds`, step errors | — | error on the step | ✅ |
| Partial success | surviving steps still build context | — | failed tools named in the prompt | ✅ |
| Verification failure | MONITOR records; ENFORCE replaces the answer | — | issue list with severity | ✅ |

### `NONE` vs `UNAVAILABLE` vs `FAILED` vs `UNSCORED`

- **`UNAVAILABLE`** — nothing ran. Not evidence of anything.
- **`NONE`** — ran, found nothing. That *is* a result.
- **`FAILED`** — the tool itself is broken.
- **`UNSCORED`** — content recovered, no confidence attached. **New, and
  necessary**: without it, OCR text lands on `topConfidence == 0`, is graded
  `WEAK`, and the model is instructed to refuse an answer it has the text to give.

---

# 11. Migration plan

## Required mapping table

| Component | Current role | New role | Action | Done |
|---|---|---|---|---|
| `SpecialistProvider` | provider abstraction | provider/adapter foundation | **Generalised** — `parseEvidence` added as a *default* | ✅ |
| `RoboflowProvider` | Roboflow integration | Roboflow Specialist Tool adapter | **Keep — zero edits** | ✅ |
| `GenericHttpProvider` | detection/classification | universal Tool adapter | **Generalised** — text/field/row parsing | ✅ |
| Specialist catalogue | discovery | Tool Marketplace source | **Extended** — `toolKind` per entry | ✅ |
| `CatalogueSource` | catalogue seam | provider/Tool catalogue abstraction | **Keep** — unchanged interface | ✅ |
| Specialist connection | credential/config | Tool provider connection | **Reused unchanged** | ✅ |
| Credential vault | secret storage | shared Tool vault | **Keep** | ✅ |
| Probe | verify specialist | universal Tool probe | **Generalised** — evidence + sample-required | ✅ |
| `Finding` | detection result | scored projection of Evidence | **Supplemented** | ✅ |
| `ContextBuilder` | findings → context | evidence → context | **Generalised** | ✅ |
| Pipeline | specialist flow | generic Tool pipeline | **Reused unchanged** | ✅ |
| Routing | specialist conditions | generic Tool routing | **Reused unchanged** | ✅ |
| Confidence Policy | specialist confidence | evidence confidence policy | **Extended** — `UNSCORED` band | ✅ |
| Trace | specialist execution | Tool execution trace | **Extended** — evidence in `detail` | ✅ |
| `HedgeDetector` | model compliance | model policy verification | **Keep — zero edits** | ✅ |
| `AnswerVerifier` | answer vs evidence | evidence-grounded verification | **Keep — zero edits** | ✅ |
| Existing Roboflow pipelines | production behaviour | backwards-compatible Tool pipelines | **Preserved** | ✅ |

## Backwards compatibility — verified

The required scenario:

```
Roboflow connection → Roboflow Specialist → Animal Injury Pipeline
→ Confidence Policy → model → Output Verification
```

survives because:

- `specialist` gained one column with a default; **no row was recreated**;
- the backfill set every existing image tool to `DETECTION`, which is what it was;
- `RoboflowProvider` was not edited, so its `parse()` still runs and the default
  `parseEvidence()` lifts the result;
- `Evidence.isFindingShaped()` projects it straight back to `Finding`, so
  `Result.findings()`, `ContextBuilder`, the policy bands and the verifier all
  receive exactly what they received before;
- **all 104 pre-existing Specialist tests pass unmodified in behaviour** (three
  test helpers switched to `StepResult.ofFindings`, a compatibility factory added
  for exactly this purpose).

**Reversibility:** `DROP COLUMN tool_kind` restores the previous schema. The Java
change is additive; reverting it restores the previous jar with no data loss.

No new API version was needed. Every endpoint kept its contract; responses gained
fields (`toolKind`, `scored`, `evidence`) and lost none.

---

# 12. Concrete examples — verified live

All of the following were run against the running backend on 29 July 2026.

### A. Image → detector → findings → model → verification ✅ *(pre-existing, unaffected)*
```
input     {"imageBase64": "…"}
tool      detect-tool (DETECTION, threshold 0.40)
evidence  DETECTION laceration 0.88  (region x=120 y=80 w=40 h=30)
band      STRONG → PASS
```

### B. Document → OCR → text → model ✅ *(newly possible)*
```
input     {"imageBase64": "…"}
tool      ocr-tool (OCR, unscored)
raw       {"text":"INVOICE 4471\nAcme Ltd\nTotal due: $12,500.00\n…"}
evidence  TEXT ×1, confidence null
```

### C. Document → OCR + extraction → model ✅ *verified end to end*
Pipeline `invoice-review`, two steps, external app call:

```
POST /api/gateway/pipeline/invoice-review
{"input":{"imageBase64":"…"},"prompt":"What is the total due and when?"}

→ findings 5 · anythingFound true · analysisRan true
→ policy {"band":"UNSCORED","action":"PASS",
          "reason":"Evidence was recovered but none of it carries a
                    confidence figure, so no threshold applies to it."}
```

The exact prompt built:

```
Automated analysis of the supplied invoice-review input:

Text recovered from the input:

[from ocr-tool]
INVOICE 4471
Acme Ltd
Total due: $12,500.00
Payment terms: net 30
Due 2026-08-01

Fields extracted from the input:
  - invoiceNumber: 4471  (from extract-tool)
  - supplier: Acme Ltd  (from extract-tool)
  - total: 12500.00  (from extract-tool)
  - dueDate: 2026-08-01  (from extract-tool)

The text above was recovered from the input automatically and may contain
recognition errors. Answer from it, and do not add details it does not contain.
You have not seen the original file, so do not describe its appearance, layout
or anything not present in the text. No confidence figures were produced for
this evidence, so do not state or imply a probability of your own.

The user asks: What is the total due and when?
```

**Note the corrected instruction.** The original wording — *"you have NOT been
shown the raw input"* — is true of a detector and **false** of an OCR tool, which
hands over the input's actual words. Repeating it would have told the model to
disregard the very text it was given.

### D. Audio → transcription → transcript ✅
```
probe with no sample  → DRAFT, "Continuum has no sample recording that a real
                                transcription service would accept…"
probe with a sample   → READY, TEXT ×1 + {language: en, duration: 14.2}
```

### E. JSON → transformation → rows ✅
```
raw       {"rows":[{"sku":"A-1","qty":3,…},{"sku":"B-2",…}]}
evidence  ROW ×2
context   "Records extracted from the input (2 in total): …"
```

### F. Fallback chain ⚠️ *partially built*
`IF_PREVIOUS_EMPTY` already expresses "escalate when the cheap tool saw nothing",
and works today. A true fallback with retry-on-error is **not built**.

### G. Hub install ✅
Install → connection created → tool created with the entry's kind → probed
automatically → `READY` with evidence shown. Verified for OCR, extraction,
transformation and detection in one session.

---

# 13. Test strategy

## Built — 540 backend tests pass, 20 new

| Suite | Tests | Guards against |
|---|---|---|
| `EvidenceTest` | 8 | unscored being turned into a number; clamping instead of dropping; ordering |
| `GenericHttpProviderEvidenceTest` | 12 | **every shape that returned zero before** — kept as regressions |
| `SpecialistProviderTest` | 19 | pre-existing normalisation, unchanged |
| `ContextBuilderTest` | 14 | prompt honesty, unchanged |
| `ConfidencePolicyTest` | 12 | band selection, unchanged |
| `AnswerVerifierTest` | 17 | grounding, unchanged |
| `StepRouterTest` | 12 | conditional routing, unchanged |
| `CuratedCatalogueTest` | 11 | no invented model ids, unchanged |

## The bugs this architecture must prevent

1. **Silent zero-evidence** — a tool that installs, probes green, and contributes
   nothing. *Caught by the 12 shape regressions.*
2. **Invented confidence** — text arriving at the policy as 1.0 or 0.0.
   *Caught by `textIsUnscored`, `normaliseKeepsUnscored`.*
3. **Threshold deleting content** — the filter eating every OCR line.
   *Guarded by the `!e.scored() ||` clause; asserted live.*
4. **Clamping** — 12500 becoming a confident 1.0. *Caught by
   `impossibleConfidenceIsDropped`.*
5. **Data read as scores** — `{invoiceNumber, total}` losing a whole response.
   *Caught by `flatFieldsAreNotScores`.*
6. **Incoherent prompts** — "you have not been shown the input" above the input's
   own text. *Verified in the live prompt.*
7. **A probe that proves nothing** — blank audio marking a tool READY. *Fixed;
   the tool stays DRAFT.*

## Not built
Migration tests against a pre-V41 dump; UI tests for the evidence renderer;
security tests for the new parser paths.

---

# 14. Reusable from the current Specialist implementation

**Everything.** Nothing was deleted, and only two files changed behaviour for
existing users (`GenericHttpProvider` gained paths; `ContextBuilder` gained
sections). Reused untouched: `RoboflowProvider`, `SpecialistConnectionService`,
the vault, `GuardedHttpSender`, `StepRouter`, `PipelineService`'s routing loop,
`HedgeDetector`, `AnswerVerifier`, `TraceRecorder`, every table but one, every
endpoint, both console pages.

---

# 15. What must be built next

**Ranked.**

1. **File upload** (`multipart/form-data`). Today a PDF must be base64'd into
   JSON by the caller. `grep MultipartFile` returns zero hits. This is the
   largest remaining gap between "works" and "pleasant".
2. **Real provider discovery** — blocked here by network policy; unblock the
   environment and it is a second `CatalogueSource`.
3. **Sample upload in the probe UI** — the backend now *asks* for a sample; the
   console cannot yet supply one except via the API.
4. **Tool versioning** — pin the probed shape so a changed endpoint cannot
   silently alter a live pipeline.
5. **Fallback-on-error routing**.
6. **Tool permissions** — which tools may receive PII.

---

# 16. Risks and trade-offs

| Risk | Assessment |
|---|---|
| Text evidence blows the context window | Mitigated: clipped at 6000 chars **and the clip is stated in the prompt**. A model told it has the whole document when it has the first half will answer confidently about the part it never saw |
| Heuristic parsing misreads a body | Precedence is fixed and first-match-wins; the score-map guard requires *every* value numeric and in range. Ambiguity resolves toward "data", the safe direction |
| `Finding` and `Evidence` drift | `Finding` is now *derived* from evidence, never stored in parallel |
| Keeping the name "Specialist" confuses | Accepted. A rename costs a migration and a compatibility shim on every route, and buys a word |
| Unscored evidence bypasses the threshold | **Intended and load-bearing.** Documented in code, tests and UI |

---

# 17. Recommended first release

**Ship Phase 1 exactly as built**, plus item 3 (sample upload) from §15.

It is coherent on its own: four tool families that could not work now work, no
existing behaviour changed, one reversible column, 540 tests green. It converts
three Hub entries from *installable and useless* into *installable and working* —
which was the single most dishonest thing in the product.

Do **not** ship a marketplace with live discovery until the network policy allows
a real vendor call. A directory that cannot be reached is worse than a curated
list that admits it is a template.

---

# What the external developer will actually see

1. Opens **Hub**, types `ocr`. The OCR entry ranks first — kind matches score
   highest. The card reads `image in · Text extraction (OCR) · no confidence`,
   and hovering explains that this kind returns content rather than scored
   detections.
2. Expands it. An honest note, the fields they must supply, and a base URL /
   model path / secret form — or a picker to reuse a connection they already have.
3. Presses **Add**. Continuum creates the connection, encrypts the key, creates
   the tool with kind `OCR`, and **probes it immediately**.
4. The probe panel shows two panes: their endpoint's raw body on the left, and on
   the right `text — no confidence — extracted content` above the actual recovered
   text. Not an empty findings list. Not a zero-percent bar.
5. Tool turns **READY**. If it had been an audio tool with no sample, it would
   have stayed **DRAFT** with *"Continuum has no sample recording a real
   transcription service would accept — supply a short clip"*, and nothing would
   have been sent anywhere.
6. Adds it to a pipeline beside a field extractor, sets routing, enables it.
7. Their app calls `POST /api/gateway/pipeline/invoice-review`. The response
   carries the answer, the trace id, and the chain.
8. In the trace they see: input received → `ocr-tool` with the text it recovered
   → `extract-tool` with four named fields → **the exact prompt the model was
   given**, including the instruction not to invent a confidence → the answer →
   verification.

At no point are they shown a confidence that nobody measured, and at no point is
a tool marked working on the strength of a request that was never made.
