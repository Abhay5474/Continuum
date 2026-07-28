# Agent Loop Detection

**Status:** shipped · **Ranked feature:** #12 · **Migration:** `V35__loop_detector.sql`
**Default: OFF** for every tenant.

> An agent that has lost the thread does not crash. It keeps working, and every
> step of it is a billable model call.

---

## The failure this catches

The failure is silent, unbounded and expensive. An agent re-reads the same file,
re-asks the same question, or alternates between two plans forever; nothing
errors, nothing times out, and the only thing that currently stops it is somebody
noticing the invoice.

Three shapes, because they need different evidence:

| Kind | What it is | Why it needs its own check |
|---|---|---|
| `REPETITION` | The same step, over and over | Exact match — cheap and certain |
| `PARAPHRASE` | The same step in different words | The string changed, which is the whole point; equality cannot see it |
| `OSCILLATION` | A → B → A → B | Neither step repeats *consecutively*, so a "same as last time" check never fires |

Oscillation is the one that runs longest before anyone notices.

---

## What it deliberately does not do

**Repetition alone never trips it.** Legitimate work repeats: a loop over twenty
files issues twenty similar steps and is not stuck. The signal is repetition
*without progress*, and progress is judged by whether anything new entered the
conversation. An agent that reads the same file twice but learns something in
between is working.

Unknown progress is treated as none — an agent that cannot say whether it
advanced is exactly the one worth watching.

**Arguments are part of the step.** Measured, not assumed: `read file src/a.java`
and `read file src/b.java` score **1.000** on this codebase's bag-of-words
similarity, because the vectoriser drops the filenames. Iterating over files is
the most common legitimate repetition there is, so a paraphrase check on prose
alone would fire on exactly the case it must not. `sameArguments()` vetoes
similarity when the paths, identifiers, numbers or quoted strings differ.

---

## The honest limit on paraphrase detection

Also measured on this codebase's vectoriser:

| Pair | Same intent? | Cosine |
|---|---|---|
| "I will retry the failed request" / "Let me try the request again" | yes | **0.258** |
| "check the database connection" / "check the network connection" | no | **0.667** |

The two populations overlap. **No threshold separates them.** The threshold is
therefore set high (`SAME = 0.90`): it catches near-identical rewording and
misses the rest. High precision, low recall, by choice — a false loop stops an
agent that was working, and that is the more expensive mistake.

This is a limitation of the lexical vectoriser, not a tuning oversight. It is
stated in the class javadoc, on the console page, and here, rather than papered
over by lowering the number until the test fixtures passed.

---

## Modes

| Mode | Behaviour |
|---|---|
| `MONITOR` (default) | Records what it would have stopped. The run continues. |
| `HALT` | Also stops the run, raising `LoopHaltedException`. |

Start in `MONITOR`. It is the only safe way to find out whether the detector
agrees with you about your own agents before it is allowed to stop one.

---

## API

```
GET    /api/portal/developer/loops/status
PUT    /api/portal/developer/loops/settings   { "enabled": true, "mode": "MONITOR" }
POST   /api/portal/developer/loops/inspect    { "steps": [...], "progress": [...] }
DELETE /api/portal/developer/loops
```

`/inspect` is exposed so an agent framework Continuum does not run can still use
the detector — the loop it needs to catch is in the caller's control flow, not in
Continuum's. In `HALT` mode it answers **409** carrying the verdict: the caller
asked whether to continue and the answer is no, which is a result rather than a
server fault.

---

## Verified live

Against the running backend, with detection on:

```
["read config.yaml" ×3]                       REPETITION,  confidence 1.00
["read file src/a.java", ".../b", ".../c"]    NONE          ← iterating, not stuck
[logs, restart, logs, restart, logs, restart] OSCILLATION, 3 cycles
["read config.yaml" ×3] + progress[1]=true    NONE          ← learned something
HALT mode, looping input                      HTTP 409
```

13 unit tests, all passing. The paraphrase fixture was rewritten to match what
the detector can actually do, rather than the threshold being lowered to match
the fixture.

---

## Console

`Reliability → Loop Detection`. Live tallies, the mode switch, the caught-loop
log with the specific steps each verdict is accusing, and an inspector that runs
the detector over a sequence you paste in — nothing is executed, so the rules can
be watched making a decision before they are trusted with an agent.
