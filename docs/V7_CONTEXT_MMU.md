# Continuum V7 — Context Virtualization (The Paging MMU for AI)

V7 inverts the ownership of the context window: **Continuum owns the Virtual
Context Space**; the LLM only ever sees a carefully constructed, bounded L1
slice. Long histories are transparently paged out into semantic references and
paged back in on demand — infinite-context workflows without bigger token
limits, invisible to the client.

> **Compatibility contract:** gated by `developer_auth.v7_mmu_enabled`
> (**OFF by default**). `ContextMMU.open()` returns `null` unless the developer
> opted in — null means the full prompt array reaches the model exactly as
> before (verified live: off-path requests record zero MMU state). Any MMU
> failure also falls back to the legacy path. The client request/response
> shape never changes.

## The memory hierarchy

```
 L1  ACTIVE WINDOW    the literal bounded payload sent to the model
                      (continuum.mmu.l1-budget-tokens, default 6000)
 L2  SEMANTIC STUBS   evicted segments live on in-window as
                      [MEMORY_REF: id="seg_…", summary="…"] references
                      (mmu_semantic_stubs; content-hash ids ⇒ resent
                      histories dedup to the same pages)
 L3  THE DISK         per-stub append-only event streams in Postgres
                      (mmu_stub_events: CREATED base + UPDATED deltas)
```

## The pipeline (per request, inside `MmuSession`)

1. **Eviction (L1 → L2).** System prompts and the recent window are pinned;
   older messages fold into deterministic segments (granularity adapts to the
   budget so a promoted page always fits back — swap semantics, never
   refusal). Each segment becomes a stub summarized by the deterministic local
   summarizer — no tokens spent. A one-line system note teaches the model the
   `[PAGE_REQUEST: id="…"]` control token.
2. **Predictive prefetching.** The newest user prompt is scanned against stub
   summaries (V2 `TextVectors` relevance + explicit id mentions); matching
   pages are promoted from L3 into L1 *before* dispatch — only unexpected
   accesses become true faults.
3. **Page-fault resolution (provider-agnostic).** If the response contains
   `[PAGE_REQUEST: id="…"]`, generation is suspended, the page is materialized
   from L3, injected as `[PAGE_IN …]`, and the call re-dispatched to the same
   provider/model (bounded to 2 rounds). Fault latency is measured per request.
4. **Context coherence (lazy materialization).** Materializing a page folds
   ONLY that stub's event stream — the CREATED base plus every subsequent
   UPDATED delta — one indexed query, O(events-for-this-stub), never a replay
   of the whole conversation. The model always sees base ⊕ mutations: never
   stale state.
5. **Dirty pages (write-behind).** If a response *reverses* a paged decision
   (V2 `DecisionPolarity`), the stub is marked DIRTY in memory only. The
   UPDATED event is appended — and the L2 summary refreshed, version bumped —
   at the next natural checkpoint (the next MMU request), keeping heavy writes
   off the hot path. No threads are spawned anywhere.

## Database (Flyway `V9`, strictly additive)

`developer_auth.v7_mmu_enabled` (DEFAULT FALSE), `mmu_semantic_stubs`,
`mmu_stub_events` (append-only, unique `(stub_id, seq)`), `mmu_request_metrics`.

## APIs

```
POST /api/portal/developer/v7/enable|disable, GET /status   session-scoped toggle
GET  /api/mmu/profile?developerId=                          aggregated telemetry
GET  /api/mmu/stubs?developerId=                            the L2 stub ledger
```

## UI — the Context Memory Profiler (`/mmu`, scoped to V7)

- **Telemetry dashboard**: token reduction %, tokens without/with MMU,
  estimated cost saved, page-fault latency P50/P95, materialization time,
  compression ratio.
- **Hierarchy visualizer**: the three tiers with blocks migrating along
  shimmering eviction/page-out flows while requests stream.
- **Request timeline**: per-request rows with reduction badges; page faults
  render as ⚡ system interrupts with their latency cost; ↑ prefetches and
  ✎ dirty flushes are marked.
- **L2 stub ledger**: every stub with its version (`MUTATED` badge once a
  dirty flush bumped it) and source→stub token compression.
- Portal gains the "Enable V7 Context Virtualization" card. Nothing else in
  the UI was touched beyond one nav link.

## Tests (7 new; full suite 100/100 green)

| Test | Proves |
| --- | --- |
| `ContextMmuTest.offPathIsNullAndUntouched` | **OFF by default**; unknown/null developers ⇒ legacy path, zero L2/L3 interaction |
| `evictionBoundsL1AndCreatesDeterministicSemanticStubs` | L1 bounded near budget; system + recent window pinned; stubs + CREATED events persisted; content-hash dedup on resent history |
| `predictivePrefetchPromotesReferencedPagesBeforeTheModelRuns` | relevant pages promoted before dispatch |
| `pageFaultSuspendsMaterializesInjectsAndResumes` | the control token suspends generation, injects the materialized page, resumes with one bounded re-dispatch |
| `coherenceMaterializesBasePlusSubsequentMutations` | materialization = base ⊕ UPDATED deltas — the model never sees stale state |
| `dirtyReversalIsFlushedWriteBehindAtTheNextCheckpoint` | no write on the hot path; the delta lands at the next checkpoint, version bumps, summary refreshes |
| `shortConversationsPassThroughUnmodified` | under-budget requests have zero paging overhead |

Runtime-verified against PostgreSQL: OFF ⇒ a 2k-token conversation passed
through with zero MMU rows; ON ⇒ a 142-message, ~9,000-token conversation was
virtualized to **2,704 tokens (70% reduction)** — 11 stubs paged to L2/L3 and
**2 pages prefetched** because the newest prompt referenced the paged topic;
resending the identical history created **0 new stubs** (dedup). The client
received the identical response shape throughout.
