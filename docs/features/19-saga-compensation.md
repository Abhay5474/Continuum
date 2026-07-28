# Saga Compensation

**Status:** shipped · **Ranked feature:** #13 · **Migration:** `V36__saga_compensation.sql`
**Default: OFF** for every tenant.

> The engine guarantees each step runs exactly once. It cannot guarantee the
> *set* of steps is all-or-nothing.

---

## The gap durability does not close

Continuum already survives a crash mid-workflow: a step's result is recorded, and
a restart resumes from the last completed one. What it cannot do is make the
whole workflow atomic, because the calls go to systems that have never heard of
this transaction.

A workflow that reserves stock, charges a card and then fails to ship has taken
someone's money and is holding inventory. No amount of durability fixes that —
the engine faithfully preserves a half-finished transaction.

You cannot roll back a charge at a payment provider. You can only issue a refund.
That is the saga pattern (Garcia-Molina & Salem, 1987): forward steps paired with
compensating ones, run in reverse order of completion.

---

## Declaring a compensation

```json
{
  "id": "charge",
  "dependsOn": ["reserve"],
  "call":       { "method": "POST", "url": "https://payments/charge" },
  "compensate": { "method": "POST", "url": "https://payments/refund" }
}
```

The undo is a call you write, not something the engine can infer — nothing about
`POST /charge` tells it that `POST /refund` is the inverse.

---

## Reverse order is not a detail

If reserving stock precedes charging, then refunding must precede releasing the
reservation. Otherwise the stock is free for someone else to buy in the window
before the money is returned. Compensations undo a dependency graph, so they
follow it backwards.

---

## Gaps are named, not hidden

A step with no compensation is listed as **uncompensated**. Reporting "rolled
back" when a confirmation email has already gone out is worse than reporting the
truth: the operator needs to know what is still out there, and the
`uncompensated` column is the only place they will find it.

Three kinds of gap are all reported the same way, rather than being silently
skipped:

- a completed step with no `compensate` block (a read, a notification);
- a step that has vanished from the definition, because a version changed under a
  running workflow;
- a compensation that itself failed — its effect is still out there and now
  nothing else will remove it.

A compensation failing does not stop the others. The remaining ones still need to
run.

---

## How it runs

Every compensation goes through `executeActivity`, so it is durable, retried and
replayed exactly like a forward step: **a crash halfway through a rollback
resumes the rollback rather than restarting it.** The rollback report is written
by an activity (`saga.record`) for the same reason — a plain database write from
workflow code would repeat on every replay and show the same rollback several
times in the console.

The setting is read **once, when a run starts**, and pinned into the run's input.
Toggling it cannot change how a workflow already in flight replays; a run must
finish the way it began. Runs started before this field existed deserialize it as
`false`, which is what they actually ran with.

---

## API

```
GET    /api/portal/developer/saga/status
PUT    /api/portal/developer/saga/settings   { "enabled": true }
DELETE /api/portal/developer/saga
```

---

## Verified live

A four-step `order-fulfilment` definition was published against a local stand-in
where `ship` always fails. `reserve` and `charge` have compensations; `notify`
deliberately does not.

Calls the target actually received, in order:

```
/reserve  /charge  /email  /ship(500)   ← forward path
/refund   /release                      ← rollback, reverse order
```

`/unship` was never called: `ship` did not complete, so there is nothing of it to
undo. The recorded report:

```json
{
  "failedStep": "ship",
  "compensated": ["charge", "reserve"],
  "uncompensated": ["notify"],
  "complete": false,
  "summary": "2 rolled back; 1 could not be and their effects remain."
}
```

7 unit tests covering ordering, the excluded failed step, blank compensation
URLs, steps missing from the definition, and the empty case.

---

## Console

`Reliability → Compensation`. The headline number is **steps stranded**, not
steps undone — a rollback that undid four things and could not undo a fifth is
the interesting case, and a console reporting only the four would be actively
misleading.
