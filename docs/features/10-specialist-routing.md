# Specialist routing — which specialists run, not all of them, always

**Status:** Phase 5 shipped · **Migration:** `V27__specialist_routing.sql`
**Default: OFF**, per pipeline, including every pipeline that already exists.

> A pipeline that OCRs, then transcribes, then detects objects pays for all
> three on an input that was only ever going to need one.

---

## The two conditions that matter

Both are cascades, pointing in opposite directions:

**`IF_PREVIOUS_EMPTY` — escalation.** Screen with something cheap, and only reach
for the expensive model when the cheap one saw nothing.

```
SPECIALIST  detector-strong                     ·
SPECIALIST  detector-strong — skipped        SKIPPED
            "An earlier specialist already found 2 findings,
             so escalating would only cost money."
```

**`IF_PREVIOUS_FOUND` — drill-down.** A general detector first, and a specific
classifier only once there is something to classify.

Between them they cover most of why anyone wants more than one specialist. The
other two are narrower: `IF_PROMPT_MATCHES` gates on the user's own question,
`IF_INPUT_IS` gates on input kind for a pipeline that accepts several.

---

## A skipped step is recorded, never silent

This is the whole reason routing needs a trace entry rather than just an absence.

**A step that did not run and a step that ran and found nothing produce the same
answer.** They need completely different fixes. So every skip writes a
`SPECIALIST … SKIPPED` step carrying the reason in words, and the console renders
it dashed and dimmed — neither a failure nor an intervention, but work that was
correctly not done.

Every decision carries a reason, including the ones that run. `StepRouter` is
pure, so the reasons are asserted in tests rather than hoped for.

---

## Backward compatibility, in the data

`steps` already held JSON. It now holds **either** shape:

```json
[3, 7]                                              ← before
[{"specialistId": 3, "when": "IF_PREVIOUS_EMPTY"}]  ← after
```

A bare id means `ALWAYS`, which is what it always meant. No pipeline needed
rewriting, and a rollback does not strand rows the previous build cannot parse.
An unknown condition from a newer build also degrades to `ALWAYS` rather than
making a step vanish.

Verified on the untouched Phase-3 pipeline:

```
animal-triage  steps=[3]  routing=[{specialistId: 3, when: ALWAYS}]  routingEnabled=false
run → INPUT → SPECIALIST → ENRICHMENT → MODEL → OUTPUT
```

The API returns both `steps` (bare ids) and `routing` (with conditions), so
anything reading the old field keeps working.

---

## Conditions are editable while routing is off

Deliberate. The alternative is making someone turn on a behaviour change before
they are allowed to configure it. With routing off the conditions are saved and
inert, and the console says so:

> These conditions are saved but not in force — routing is off, so every step
> still runs.

---

## Guards

| Guard | Response |
|---|---|
| A previous-step condition in first position | `'X' is first, so there is no previous step for its condition to look at. Move it down, or set it to run always.` |
| A pattern that will not compile | `/[bad/ is not a valid regular expression.` |
| `IF_INPUT_IS` with no kind | `'X' runs for one input kind, but none was given.` |

The first is caught at configuration time and also disabled in the dropdown, so
it cannot be selected. It is not a condition that can be false — it is a
condition with nothing to refer to.

### Two failure modes, chosen deliberately

- **A broken pattern at request time skips the step.** Running anyway would turn
  a typo in a regular expression into an unexplained bill. The skip is visible in
  the trace with the reason attached, which is where someone will look.
- **A previous-step condition with nothing before it runs anyway.** Configuration
  refuses this, so reaching it means a specialist was deleted out from under the
  pipeline. Skipping would leave a pipeline that quietly does nothing at all.

---

## Verified live

```
escalation, cheap found nothing  → both ran, 2 findings
escalation, cheap found 2        → second SKIPPED, "would only cost money"
prompt "she is bleeding"         → both ran
prompt "what breed is she?"      → second SKIPPED, pattern did not match
routing off                      → both ran, conditions inert
all three guards                 → 400 with the specific reason
pre-routing pipeline             → parsed, ran, unchanged
```

**45/45 UI assertions** across desktop dark, mobile dark, desktop light.
**Backend suite: 380 passing** (368 → 380).

---

## Next

| Phase | What |
|---|---|
| ~~6~~ | ~~**Output verification**~~ — **shipped**, see [11-output-verification.md](11-output-verification.md) |
