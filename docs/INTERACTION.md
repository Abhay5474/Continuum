# Interaction and information design

How the console shows a running system. It extends the existing interface; it
does not replace it. The colours, cards, typography and charts stay as they are.

## What was wrong

An audit of the screens that explain execution:

- **Time was missing.** A verification run was drawn only as a graph, and a
  workflow's timeline drew a retried step as one solid bar. A two-second
  backoff read as two seconds of work, and the step that delayed the run could
  not be found.
- **Results were not where the question was.** A claim's prior → posterior,
  and whether it made it into the answer, was not shown anywhere. Only the
  aggregate was.
- **Exceptions needed a hunt.** A contradiction was a "!" node with no reason
  attached. A failed check was one red bar among many. Nothing said what to
  look at first.
- **Empty space waited for a click.** The inspector beside the graph was blank
  until something was selected.
- **Views did not talk to each other.** Selecting a node in the graph changed
  nothing in the cards beside it.
- **Decorative motion.** A finished run's contradiction pulsed forever. The
  experiments with 3D scenes added depth that carried no data. They were removed,
  along with three.js.

## Direction

Overview first, details on demand, every view linked.

- **Say it first.** Each run opens with *findings*: one sentence per thing
  worth knowing, worst first, computed from the run's own record. Each finding
  is a way in; choosing it selects what it is about.
- **One clock.** Execution is a waterfall, the trace-viewer form engineers
  already read. Bar length is time; a thin line before a bar is time queued for
  a worker; a dashed line is a retry's backoff; a hatched bar is an attempt that
  failed. The critical path (the chain that decided how long the run took) is
  drawn darker, and the time along it is split into work, queue, backoff and the
  engine deciding.
- **One selection, everywhere.** Choosing a claim, step or contradiction in any
  view highlights it in all of them. Details appear in place: beside the run on a
  wide screen, above it on a phone.
- **Colour is status.** Work is neutral. Green, amber and red mean what they
  mean everywhere else in the console. The accent marks only what is selected.

## Shared parts

| Part | Where | What it is |
|---|---|---|
| `system/execution/model.ts` | all run views | the run as lanes, attempts, waits, relations, critical path and time budget; pure and tested |
| `system/execution/findings.ts` | run views | findings computed from the model, each with a target |
| `components/Waterfall.tsx` | verification run, workflow run | the waterfall: HTML rows, keyboard-walkable, live-updating |
| `components/RunDetail.tsx` | run views | time budget, a step's execution facts, the findings list |
| `ExecutionTimeline` (backend) | `/api/dag/trace`, `/api/workflows/{id}` | every step's attempts and retries, read from the event log |

## Rules for motion

- Motion only shows a change of state: a selection, a run progressing, a value
  arriving. Nothing moves to decorate, and nothing reacts to hovering beyond the
  usual row highlight.
- Transitions run 150–200 ms in CSS. There is no animation library.
- Anything that pulses means something is happening now (for example, a
  contradiction in a run that is still live). Once the run has finished, it stays still.
- With reduced motion set, transitions and smooth scrolling are off. Nothing is
  lost, because no information lives in motion alone.

## Rollout

Done: verification runs, workflow runs, the context memory profiler's request
history, the autopilot's optimization cycle, the verification run list.

Next, in order of how much a person gains:

1. **Saga runs.** The same waterfall, showing each compensation next to the step it undid.
2. **Gateway request detail.** One request as a waterfall of its failover chain:
   each provider tried, how long, why it failed.
3. **Pipelines.** Each run's trace steps on the shared waterfall.
4. **Command Centre.** Findings across the account ("3 runs retried in the last
   hour, all on email.send"), each linking to the run.
