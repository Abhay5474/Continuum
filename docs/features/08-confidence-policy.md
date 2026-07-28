# Confidence policy — what the model may do with the evidence

**Status:** Phase 4 shipped · **Migration:** `V26__confidence_policy.sql`
**Default: OFF**, per pipeline, including every pipeline that already exists.

> What stops a 0.31 detection becoming confident first-aid advice.

---

## The problem Phase 3 left open

Phase 3 wrote the confidence into the prompt:

```
Weak signals, treat as unconfirmed:
  - fracture (31% confident, from Animal injury detector)
```

And then the model wrote a paragraph about the fracture.

Telling a model the number is necessary and nowhere near sufficient. It also has
to be told **what to do**. So the policy sits between the evidence and the model
and constrains the answer.

---

## Five bands

| Band | Evidence | What the model is told |
|---|---|---|
| `STRONG` | ≥ strong threshold | Nothing. Answer normally. |
| `MEDIUM` | between | Say out loud how sure you are; name what is uncertain. |
| `WEAK` | < weak threshold | Do not name a condition. Say the input was not good enough. |
| `NONE` | looked, found nothing | Do not speculate. Answer the parts that need no findings. |
| `UNAVAILABLE` | never looked | **Do not present this as an all-clear.** |

`STRONG` appends nothing at all. An instruction on every answer would make even
well-evidenced ones read as hedged, and a hedge that is always present carries no
information.

### NONE and UNAVAILABLE are the point

They are the same zero findings and completely different facts. A clean photo is
evidence; a dead endpoint is not. Collapsing them is how a system reports
"nothing wrong" during an outage — and `analysisRan`, added in Phase 3
specifically for this, is what keeps them apart.

Verified live by killing the detector mid-request:

```
band    : UNAVAILABLE
reason  : No specialist answered, so nothing was examined.
answer  : "...No analysis is available. The specialist checks did not complete..."
```

---

## Instructions are constraints, not moods

"Be careful" is not actionable. Every instruction names something the answer must
not contain:

> The evidence above is weak — too weak to advise on. Do not name a specific
> condition, diagnosis or cause, and do not give instructions that would only
> make sense if the weak findings were correct. […] General safety advice that
> holds regardless of the findings is fine.

That last sentence matters: a user asked a question, and refusing to engage at
all would be worse than the problem.

---

## The policy can only ask — so compliance is measured

This is the honest core of the feature.

Appending an instruction does not make it binding. A page that reported
*"hedged: yes"* because it **requested** a hedge would be reporting its own
intent dressed up as an observation — believable, and wrong.

So after the model answers, the answer is checked, and the console says which it
is:

```
POLICY   Evidence too weak to advise on — model told to ask for better input   CONSTRAINED
MODEL    mock-small                                                       30ms
VERIFY   Model did NOT follow the policy                                    IGNORED
```

**`STRONG` produces no compliance claim at all.** Nothing was asked of it, so
counting it as a success would inflate the rate with untested runs.

### What the check cannot do

It is **lexical**, and the console says so on screen. It looks for the surface
marks of uncertainty and of declining to advise.

It cannot tell a genuine hedge from a decorative one — *"this may possibly be an
open wound; apply a tourniquet immediately"* contains every marker and hedges
nothing. There is a test asserting exactly that limitation rather than papering
over it.

What it reliably catches is the common failure: an instruction to hedge that
produced flat, unqualified prose. That is worth catching, and claiming more
precision than a word list can deliver would repeat the mistake the feature
exists to prevent.

---

## Declining without calling a model

Optional, off by default. When there is no evidence *at all*, spend nothing:

```
chain   : INPUT -> SPECIALIST -> ENRICHMENT -> POLICY -> OUTPUT
model   : ''      cost: 0.0
response: "Nothing could be identified from what was provided with enough
           confidence to advise on. A clearer or closer input would help."
```

No `MODEL` step. Paying for a call to be told to say something already known
also leaves room for the model to answer anyway.

Off by default because it is not right for everyone — leaving it off keeps the
model able to answer the parts of a question that need no findings. It never
fires on weak evidence, only on *absent* evidence: a 0.05 finding is still
something to be careful about, not nothing.

---

## Two bugs, found by driving it

### Confidence was never range-checked

The `empty` band came back reading **`evidence 128.0` — "12800% confident"** and
selected `STRONG`.

The generic adapter had scraped `bytes_received: 128` out of the response
envelope, and **nothing anywhere clamped confidence to 0–1**. The reporting
threshold, the context builder's prose bands and the policy all compare against
numbers between 0 and 1; one value outside that range sails past all three. Any
endpoint scoring out of 100 would have been read as maximally confident by
everything.

Normalisation now happens **in the invoker**, the one choke point every adapter
passes through, so no adapter can bypass it:

- 0–1 taken as given
- above 1 up to 100 treated as a percentage and divided
- above 100, negative, or `NaN` → the finding is **dropped, not clamped**

Clamping 128 to 1.0 would turn garbage into maximum confidence, which is the
failure being fixed.

Wrong-in-the-safe-direction is deliberate: a logit or a vote count divided by 100
becomes a small number and gets filtered. The other direction produces confident
advice from noise.

### An empty results list invited a scrape

`predictions: []` fell through the recognised-key branch into the score-map
fallback, which then read the envelope's own bookkeeping fields as findings. A
recognised results key **is** the answer now, even when empty.

---

## Guards

| Guard | Why |
|---|---|
| Off per pipeline, and off after migration | Turning it on changes what a live endpoint says to real users |
| Thresholds set together, weak ≤ strong enforced | weak > strong empties the middle band and silently disables hedging — it looks like a working policy |
| Slider mirrors the server rule | The control cannot be dragged into a state the save will reject |
| `policy` is `null` when off, not a hollow `action: NONE` | "Not configured" and "configured and it passed" are different facts |

---

## API

Added to `PUT /api/portal/developer/pipelines/{id}`:

| Field | Default |
|---|---|
| `policyEnabled` | `false` |
| `strongThreshold` | `0.70` |
| `weakThreshold` | `0.40` |
| `declineOnNoEvidence` | `false` |

Added to every run response: `policy` and `compliance`, both `null` when the
policy is off.

The defaults are **taken from** `ContextBuilder`, not restated. Two private
copies of `0.70` would let the prompt say `Observed:` directly above an
instruction to hedge, and contradict itself. A test asserts they still match.

---

## Verified live

```
strong   band=STRONG      action=PASS                  evidence=0.91
         INPUT → SPECIALIST → ENRICHMENT → POLICY → MODEL → OUTPUT
medium   band=MEDIUM      action=HEDGE                 evidence=0.55
         … → POLICY → MODEL → VERIFY → OUTPUT     complied=True  ['possible']
weak     band=WEAK        action=ASK_FOR_BETTER_INPUT  evidence=0.31
         … → POLICY → MODEL → VERIFY → OUTPUT     complied=False []
empty    band=NONE        action=DECLINE
         INPUT → SPECIALIST → ENRICHMENT → POLICY → OUTPUT      cost=0.0
down     band=UNAVAILABLE action=ASK_FOR_BETTER_INPUT
         complied=True  ['no analysis', 'did not complete']

policy off  → policy=None compliance=None, no POLICY step
guard       → 400 "The weak threshold must not be above the strong threshold"
```

`complied=False` on the weak band is the mock model genuinely ignoring the
instruction, and the system reporting it rather than assuming success. That is
the feature working.

**84/84 UI assertions** across desktop dark, mobile dark, desktop light.
**Backend suite: 357 passing** (330 → 357).

---

## Next

| Phase | What |
|---|---|
| ~~2~~ | ~~**The Hub**~~ — **shipped**, see [09-hub.md](09-hub.md) |
| 5 | Specialist routing — which specialist, decided by type, rule, or the model |
| 6 | Output verification — does the advice match the findings |

Phase 6 is where the lexical check gets replaced by something that reads the
advice against the findings rather than against a word list.
