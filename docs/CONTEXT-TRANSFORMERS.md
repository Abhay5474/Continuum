# Context Transformers

The application has data in representation X. The model reasons best over
representation Y. This layer is X → Y, deterministically, in process.

## The product boundary

Continuum now has two fundamentally different capabilities, and keeping them
apart is the point.

| | **Specialists** | **Context Transformers** |
| --- | --- | --- |
| Who does the work | Roboflow, Deepgram, OCR.space, … | Continuum itself |
| Needs an API key | Yes — the developer's own | **No** |
| Needs a network | Yes | **No** |
| Deterministic | No — a model's output | **Yes** — same bytes, same output, forever |
| Produces | `Evidence` | `CanonicalContext` |

A Specialist calls somebody else's model. A transformer *is* the work. That
matters practically: a transformer's output can go into a cached prompt, a
deterministic replay and an audit trail, because re-running it cannot produce a
different answer.

---

## What was built

Three transformers, chosen by research and ranked against the criteria in the
brief. **The ranking deviates from the initial hypothesis on one slot, with a
reason given below.**

| # | Transformer | Input | Canonical form |
| --- | --- | --- | --- |
| 1 | `SpreadsheetTransformer` | XLSX, XLS, CSV, TSV | `CanonicalTable` |
| 2 | `LogTransformer` | application/server logs | `CanonicalIncident` |
| 3 | `EmailThreadTransformer` | `.eml` / MIME | `CanonicalConversation` |

### Why SQL/API → SemanticDataset was demoted to #4

The initial hypothesis put it second. It is genuinely valuable and it is
genuinely **not** a transformation problem yet.

Turning `status = 1` into `active` requires an enum dictionary that only the
developer possesses. Continuum cannot derive it, and deriving it would violate
the failure-safety rule outright. So the feature is really *store a schema, then
bind it*: a registry, an authoring UI, and a schema-versioning story. Until the
developer authors that schema, the output is no better than the JSON they
already had — which fails criterion 3 (*significant measurable benefit*) and
criterion 4 (*deterministic implementation possible* — the mapping is, the
schema is not).

Email replaces it because it needs **zero configuration**, delivers a measured
token win on day one, and its ambiguities are self-evident from the source. The
canonical model below is designed so the semantic dataset slots in as a fourth
`CanonicalContext` implementation without changing anything.

Rejected outright as ordinary file conversion: GeoJSON, ICS, YAML→JSON,
Markdown→text. They move bytes between formats without recovering meaning.

---

## Research

**Spreadsheets.** A spreadsheet is a *rendering*, not a data model — meaning
lives in layout. Chen & Cafarella, *Automatic Web Spreadsheet Data Extraction*
(2013) established blank-region segmentation, which is the deterministic
baseline used here; Koci et al. (CAiSE 2016, KDIR 2016) and **TableSense** (Dong
et al., AAAI 2019) improve on it with models this deliberately does not need.
On the consumption side, **TAPAS** (Herzig et al., ACL 2020) and **TaBERT** (Yin
et al., ACL 2020) both show models need header-resolved, linearised tables, and
**Chain-of-Table** (Wang et al., ICLR 2024) shows reasoning quality tracks
structural clarity.

**Logs.** **Drain** (He et al., ICWS 2017) — fixed-depth-tree online log
parsing; **Spell** (Du & Li, ICDM 2016); **IPLoM** (Makanju et al., KDD 2009);
benchmarks in Zhu et al. (ICSE-SEIP 2019). For grouping crashes, **ReBucket**
(Dang et al., ICSE 2012) clusters by call-stack similarity — directly applicable
to exception fingerprinting.

**Email.** Carvalho & Cohen, *Learning to Extract Signature and Reply Lines from
Email* (CEAS 2004), and its production embodiment, Mailgun's **Talon**. RFC 3676
(`-- ` delimiter, format=flowed) and RFC 5322 supply exact anchors.

**Canonical modelling & provenance.** Rahm & Bernstein, *A survey of approaches
to automatic schema matching* (VLDB Journal 2001); **Clio** (Miller et al., VLDB
2001); the *Canonical Data Model* pattern (Hohpe & Woolf, 2003); W3C PROV-DM and
Cheney/Chiticariu/Tan, *Provenance in Databases* (2009).

**Context engineering.** **Lost in the Middle** (Liu et al., TACL 2024) — position
and repetition change what a model attends to, so *ordering* is part of the
transformation, not cosmetics. **LLMLingua** (Jiang et al., EMNLP 2023) — already
the basis of Continuum's compression budget; this layer is complementary,
performing *structural* reduction before any *lexical* compression.

---

## The canonical model

```
CanonicalContext (interface)
├─ type() · sourceName() · render(RenderBudget)
├─ describe()      → machine-readable structure
├─ ambiguities()   → what could not be determined
├─ provenance()    → where values came from
└─ structure()     → what was recovered, as counts

    CanonicalTable        ← XLSX, CSV        (later: SQL results)
    CanonicalIncident     ← logs             (later: traces, k8s events)
    CanonicalConversation ← .eml             (later: chat, ticket threads)
```

Typed, not one universal tree. A spreadsheet, an incident and an email thread
have nothing in common below the surface, and forcing them into a generic
`nodes`/`children` structure would describe all three badly.

**`RenderBudget`** separates *what was recovered* from *how much is spoken*. The
same workbook is 40,000 tokens if the model must answer about any cell, 6,000 if
it needs the shape and the totals, 900 if it only needs to know what the file
contains. `summary` / `standard` / `full` render the same canonical form at
three lengths — verified by test to produce identical `structure()`.

---

## Failure safety: never invent semantics

The failure mode of a context layer is not crashing. It is quietly deciding a
column of numbers is US dollars. A model told `unit = USD` reasons in dollars,
states conclusions in dollars, and nothing downstream ever questions it.

So every transformer records what it could not determine, as an `Ambiguity` with
a kind (`UNIT`, `HEADER`, `TYPE`, `AGGREGATION`, `TRUNCATION`, `STRUCTURE`,
`EXCLUDED`) — and **the ambiguities are rendered into the prompt**, not just
returned by the API:

```
UNRESOLVED — the following could not be determined from the file, and have not
been guessed:
  - Q4 · amount: This column is numeric but carries no unit in its header or
    number format, so no unit has been assigned.
```

Concrete instances:

- an unlabelled numeric column stays unitless (`neverInventsAUnit`)
- hidden rows are excluded **and reported**, never silently dropped
- subtotal rows are **marked, never deleted** — deleting them stops
  double-counting *and* deletes the answer to "what was the total"
- a message ending in prose keeps its ending; the signature heuristic requires
  two independent contact lines in a short trailing block
- a log with no timestamps says so rather than inventing an ordering

---

## Provenance

Every canonical value can be traced back:

```
financials.xlsx → Q4!B1 (composed from 2 header rows)
financials.xlsx → Q4!C7 (=SUM(C2:C6))
outage.log      → line 4182 (java.net.SocketTimeoutException)
```

Formulas are kept as provenance rather than rendered: the model needs the
number, the developer checking it needs to see how it was derived. Provenance is
rendered inline only under `RenderBudget.full()` — printing a source for every
value can cost more than the values, and the machine-readable form keeps them
regardless.

---

## Measured results

Run as tests (`ContextTransformServiceTest`) so they cannot drift unnoticed.
Token counts use `PromptCompressor.estimateTokens` — the same estimator the
compression budget uses, so the two features' numbers are comparable.

| Input | Before | After | Change |
| --- | ---: | ---: | ---: |
| 10,500-line outage log | 187,500 | 2,968 | **−98.4%** |
| 4-deep email thread | 193 | 156 | −19.2% |
| 800-row CSV, flat headers | 3,797 | 5,088 | **+34%** |

**The spreadsheet number is a real result and it is reported as one.** For a
plain CSV with single-row headers, the canonical rendering is *larger* than the
input: the header block (dimensions, measures, units, aggregate warnings) costs
more than the structure it recovers saves. The brief said explicitly not to
assume more structure means better, and this is the case where it does not.

The spreadsheet transformer's value is **structural fidelity, not token
reduction**. On the input it exists for — merged multi-row headers, units in
headers, hidden rows, subtotals — a CSV export *destroys* information that no
prompting recovers, so there is no token comparison to make. The console shows
an explicit amber note when a transformation grew the prompt, rather than
displaying only the wins.

The baseline is always a **plain-text rendering of the same input** — the CSV a
workbook flattens to, the log lines themselves. Comparing canonical output
against the base64 of a binary file would report an enormous saving that means
nothing.

---

## What each transformer actually does

### Spreadsheet → `CanonicalTable`

1. **Table detection** — blank-row segmentation. A sheet is not a table; a data
   block and the summary beneath it are two.
2. **Header composition** — merged regions are read from the workbook and
   forward-filled, then multi-row headers join with `·`. `Revenue` merged above
   `Q3 Q4` becomes `Revenue · Q3`, `Revenue · Q4`. **This is the association CSV
   destroys**: export gives `Revenue,,,`.
3. **Units** — from header text `(₹ crore)` or the cell's own number format
   (`"₹"#,##0`, `0.00%`). Extracted **per header segment**, since composition
   puts the unit in the middle. Disagreement between the two sources is recorded,
   not averaged.
4. **Roles** — numeric → MEASURE, text/date → DIMENSION, mixed → UNKNOWN.
5. **Aggregates** — detected by label, marked not deleted, and the rendering
   tells the model *"do not add them to the rows they summarise."*
6. **Hidden rows/columns/sheets** — excluded and counted.

### Logs → `CanonicalIncident`

1. **Template masking** — ids, durations, IPs, paths, quoted strings and bare
   numbers are masked; identical shapes collapse to one pattern with a count.
   6,000 access lines become one line with `6000x`.
2. **Exception fingerprinting** — grouped by type + top 4 frames, **with line
   numbers stripped** so the same bug does not become a second fingerprint after
   a rebuild.
3. **Correlation ids** — UUIDs appearing on more than one line, with the services
   they crossed.
4. **Timeline** — errors and first occurrences, in order. Tail-truncation, the
   usual alternative, destroys the first occurrence of the failure — the single
   most useful line in the file.

Errors sort above equally frequent noise: sorting on count alone buries a 400×
error under a 6,000× access log.

The rendering states what it is: *"This is a structured reduction of the log, not
a summary written by a model. Counts are exact."*

### Email → `CanonicalConversation`

Subtractive, with a deterministic anchor for every subtraction: `>` prefixes,
`-----Original Message-----`, `On … wrote:`, the RFC 3676 `--` delimiter
(accepted with or without the trailing space, since editors strip it),
disclaimer phrases in the trailing portion only, and residual paragraph-level
deduplication for quoting styles no marker identifies.

**Nothing is summarised or reworded** — every sentence in the output was written
by the person it is attributed to. Attachments are named, not embedded.

---

## Integration

**Pipelines.** `InputIngestor` calls the layer before the first tool, after PDF
handling. It adds the rendering under `text` with `putIfAbsent` and **never
removes the original payload** — the same contract PDF ingestion already had.
An input nothing recognises falls through exactly as before, so no existing
pipeline changes behaviour.

**API** — all new paths, nothing existing touched:

| Method | Path |
| --- | --- |
| GET | `/api/portal/developer/context/capabilities` |
| POST | `/api/portal/developer/context/transform` (multipart or `text/plain`) |
| GET | `/api/portal/developer/context/transforms` |
| POST | `/api/gateway/context/transform` (API key) |

**Database** — one additive table, `V42__context_transform.sql`. The input and
the rendering are **deliberately not stored**: a transformed spreadsheet is the
developer's data and often commercially sensitive, and keeping it would turn an
observability table into a second copy of everything they ever sent. Counts and
shape answer the question the table exists for.

**UI** — new page under *Prompt* → **Context Transformers**: the pipeline as a
four-stage strip (input → transformation → canonical → what the model receives),
before/after token counts with a saturation meter, recovered structure as counts,
ambiguities at full visual weight in their own panel, and four output tabs
(model-facing rendering, machine-readable JSON, provenance, baseline note).

---

## Testing

47 tests across four classes, all genuinely verified — nothing here needs a
network, so unlike the provider adapters there is no `LIVE_UNVERIFIED` caveat.

- **`SpreadsheetTransformerTest`** builds real workbooks with POI — genuine
  merged regions, hidden rows, currency number formats, subtotals — and reads
  them back.
- **`LogTransformerTest`** uses a synthetic 6,400-line outage with a recurring
  stack trace.
- **`EmailThreadTransformerTest`** uses a real four-deep MIME thread with
  signatures and a disclaimer.
- **`ContextTransformServiceTest`** covers dispatch, passthrough safety, a
  deliberately defective transformer, and the benchmarks.

Full suite: **673 passing**, up from 625, zero regressions.

### Bugs found by these tests, not by inspection

1. **Unit extraction missed composed headers.** The pattern was anchored to the
   end of the whole name, but composition puts the unit in the middle
   (`Revenue (₹ crore) · Q3`). Fixed to run per segment.
2. **The signature heuristic could return offset 0**, which would have deleted
   an entire message rather than its signature. Now it can never cut from the
   first line.
3. **`--` without a trailing space was not recognised** as a signature delimiter,
   though most clients and every trailing-whitespace-stripping editor produce it.
4. **Three arbitrary bytes parsed as a valid email.** MIME parsing is lenient
   enough to accept anything; no headers *and* no body is now reported as
   unparseable.

---

## Not built

- **SQL/API → SemanticDataset** — ranked #4, reasoning above. Needs a schema
  registry before it is a transformation rather than a config format.
- **Repository → CodebaseContext** — needs per-language static analysis.
- **PDF → structured layout** — would mean changing working PDF extraction;
  deliberately left alone.
- **OpenAPI → capability map, Jira → dependency graph** — plausible, unproven
  demand.
