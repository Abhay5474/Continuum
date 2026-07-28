# Priority & Deadline Scheduling

**Status:** shipped · **Ranked feature:** #14 · **Migration:** `V37__priority_scheduling.sql`
**Default: OFF** for every tenant.

> Admission control answers *is there room*. This answers *for whom*.

---

## The problem with FIFO

Admission control already holds concurrency at an inferred limit and lets a
request wait briefly for a slot. Which waiter gets the slot is currently whoever
polled at the right moment — so a bulk backfill submitted a second earlier beats
an interactive request, and the person watching a spinner has no idea why.

That is fair, and it is the wrong kind of fair.

---

## The ordering

**Earliest-deadline-first within the highest priority band.** EDF is optimal for
meeting deadlines on a single resource (Liu & Layland, 1973); priority bands sit
above it because not all deadlines are equally worth meeting, and strict EDF lets
a batch job with a tight deadline outrank an interactive request with a loose
one.

| Band | |
|---|---|
| `BATCH` | Bulk work nobody is waiting for |
| `NORMAL` | The default |
| `INTERACTIVE` | Someone is watching a cursor blink |

An unrecognised value reads as `NORMAL`, never as `BATCH` — a typo in a client
must not silently send that caller's traffic to the back of every queue. **No
deadline sorts last, not first:** an unset deadline means "whenever", and
treating it as zero would make every unannotated request the most urgent thing in
the queue.

Priority is derived from the existing `criticality` field rather than being a
second knob. They are the same judgement seen twice — one decides who is refused
when there is no room, the other who goes first when there is nearly none — and
asking for both invites a caller to disagree with itself.

---

## Two behaviours that matter more than the ordering

**A request that cannot meet its deadline is not started.** Running it spends a
slot producing a result nobody can use *and* delays the requests that could still
make theirs. It comes back `422 deadline_unreachable` immediately. The estimate
is the latency actually observed for that provider; when nothing has been
measured the estimate is zero, and a request is never refused on the strength of
a guess.

**Starvation is bounded.** A pure priority queue lets background work wait
forever under sustained interactive load. Waiting time is aged into the effective
band — one band per `AGING_STEP` (120s), capped at two — so anything queued long
enough eventually outranks new arrivals. Without that, "low priority" quietly
means "never". The cap matters too: a day-old batch job is still a batch job, and
an uncapped boost would let a stale backlog take over the queue the moment load
eased.

The order is recomputed on every poll rather than cached, because aging changes
it while requests are waiting. That is what stops the queue from starving its own
tail.

---

## What this deliberately does not do

**Nothing is persisted, and there is no queue table.** A waiter exists only while
its request is blocked, so there is nothing to recover after a restart — every
waiter died with the request that created it.

**Each instance orders its own waiters.** Across several instances there is no
global order, and there deliberately is not one: a shared queue would need a
round trip to reach, and at a quarter-second bounded wait that trip costs more
than the ordering saves. Stated rather than hidden.

---

## API

```
GET  /api/portal/developer/scheduling/status
PUT  /api/portal/developer/scheduling/settings   { "enabled": true }
POST /api/portal/developer/scheduling/order      { "tasks": [...] }
POST /api/portal/developer/scheduling/reset
```

On the gateway, per request:

```json
{ "criticality": "INTERACTIVE", "deadlineMs": 30000 }
```

`/order` orders a hypothetical queue without running anything. The ordering is
the part worth checking before it is trusted with real traffic — a scheduler that
silently disagrees with what an operator expected is worse than no scheduler —
and it is also the only way to see the deadline-miss and aging rules fire on
demand rather than by waiting for a real overload.

---

## Verified live

`/order` against the running backend:

```
—    expired-lookup    cannot meet its deadline — needs 9s and only 2s remain
#1   chat-reply        INTERACTIVE, due in 30s
#2   nightly-backfill  BATCH, no deadline (+2 bands for waiting 5m)
#3   report-refresh    NORMAL, due in 600s
```

Every rule visible at once: the impossible deadline refused, EDF inside the band,
and aging lifting a five-minute-old batch job above a fresh `NORMAL` request.

Through the gateway, with admission control on and 9ms of measured provider
latency:

```
deadlineMs: 1       HTTP 422  deadline_unreachable
                              "…cannot meet its 1ms deadline — a call to mock is
                               taking about 9ms."
deadlineMs: 60000   HTTP 200  answered normally
```

9 unit tests covering priority over FIFO, EDF within a band, unset deadlines,
deadline refusal, refusal not disturbing other tasks, aging, the aging cap, and
unrecognised priorities.

---

## Console

`Traffic → Priority & Deadlines`. Live queue per provider with each waiter's rank
and the reason for it, plus counters for jumped-the-queue, lifted-by-waiting and
refused-on-deadline.

The live queue is usually empty — the wait it orders is bounded at a quarter
second, so by the time you look it has drained. That is why the planner is on the
page rather than beside it.
