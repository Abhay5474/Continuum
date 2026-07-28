# Working-Set Context Assembly

**Status:** shipped · **Ranked feature:** #8 · **Migration:** `V32__working_set.sql`
**Default: OFF** for every tenant. With it off, eviction is positional exactly as before.

> Keep what the current request needs — not whatever happens to be newest.

---

## The failure it fixes

Eviction was positional: oldest paged out first, newest `RECENT_KEEP` pinned.
That is LRU, and LRU is the wrong policy here for the reason Denning identified
in 1968 — what matters is not what was touched most recently but what belongs to
the **working set** of the task in front of you.

In a conversation the two come apart constantly. A customer states their order
number in message three and asks about it in message forty; a recency policy
pages out the order number to keep small talk that happened to be newer.

## The scoring

```
score = 0.7 × relevance(message, current request) + 0.3 × recency
```

Highest-scoring messages stay resident until the budget is spent, then the
original conversation order is restored — a conversation reordered by score is
not a conversation.

**Relevance outweighs recency deliberately.** Recency is only a *proxy* for
relevance, and when a direct measurement is available the proxy should not
outvote it. Recency survives as a tiebreak because with two equally relevant
messages the later one is usually the one that superseded the earlier.

Nothing here decides what is *pinned*. The system prompt and the newest turns are
held resident regardless of score — the immediate conversational turn is not a
cache entry to be reasoned about.

---

## The bug this found

First live run on a 37,000-token conversation:

```
workingSet OFF   37235 → 3233 tokens
workingSet ON    37235 → 8275 tokens   ← against a 6000 budget
```

The resident set spent the whole remaining budget, and then the `[MEMORY_REF]`
stubs were added *on top* of it. **L1 came in 38% over its ceiling** — and the
bound is the entire point of having one.

Fixed by reserving what the stubs will cost before selecting anything: worst-case
stub count from the evictable token total, at a measured 70 tokens per stub plus
45 for the paging instruction. Re-measured:

```
workingSet OFF   37235 → 3233 tokens   within 6000 ✓
workingSet ON    37235 → 5293 tokens   within 6000 ✓
```

Note the second number is *higher* and that is correct: positional eviction
over-evicts, throwing away budget it was entitled to spend. The working set uses
the room it has.

---

## The limitation

Relevance is **lexical cosine over bag-of-words** — the same measure the rest of
Continuum uses without an embedding model. It will miss a paraphrase that shares
no vocabulary with the question.

**Better than position, worse than understanding.** That is the honest
description, and it is on the page rather than in a footnote.

---

## Verified

- Live: eviction fires on a 37k-token conversation, both modes stay within the
  L1 ceiling, and the working set holds ~64% more context resident.
- `WorkingSetTest` (9): an old message the question is about survives while newer
  chatter does not; with nothing relevant, recency decides; the budget is spent
  on the best fit first; conversation order is preserved; zero and generous
  budgets; every candidate is reported whether resident or not — a page-out
  nobody can account for is indistinguishable from a bug.

**30/30 UI assertions** across desktop dark, mobile dark, desktop light.
**Backend suite: 449 passing** (440 → 449).

---

## API

| Method | Path | Purpose |
|---|---|---|
| `PUT` | `/api/mmu/settings` | `{ workingSet }` |
| `GET` | `/api/mmu/profile` | now reports `workingSet` alongside residency |
