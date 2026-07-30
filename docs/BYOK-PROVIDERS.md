# Bring-your-own-key providers

## What a Specialist actually is

A language model cannot read an image, an audio file, or a scanned page. A
Specialist calls a third-party API — **with the user's own key** — that turns
format X into text or structured data the model can use. Continuum never hosts,
downloads or runs a model. It is a caller and a translator.

The unit of work is an **adapter**. An adapter knows four things about exactly
one provider:

1. how to build the URL
2. how the credential is presented
3. how to shape the request body
4. how to read the response back into `Evidence`

That is the whole job. `RoboflowProvider` was the first one; this document
covers the five added after it, prioritised by whether a developer can wire one
up end to end without paying anybody first.

---

## What was added

| Provider | Task | Free tier | Rounds | Status |
| --- | --- | --- | --- | --- |
| Roboflow | image → detections/classifications | yes | 1 | ✅ shipped earlier |
| **Deepgram** | audio → transcript | free credits | 1 | 🟡 fixture-tested |
| **AssemblyAI** | audio → transcript | yes | 3+ | 🟡 fixture-tested |
| **OCR.space** | image/scanned PDF → text | yes, generous | 1 | 🟡 fixture-tested |
| **Hugging Face** | text/image → labels, moderation, ASR, summaries | yes, serverless | 1 | 🟡 fixture-tested |
| **Google Vision** | image → OCR, labels, safe search | 1,000 units/month | 1 | 🟡 fixture-tested |

They install through the existing flow into the existing `SpecialistInvoker`. No
new execution path, no new credential store. The four free-tier ones carry a
**free tier** badge in the Hub; Google Vision does not, because its free
allowance runs out.

Ordering is deliberate. Google is more accurate than OCR.space and more work to
start using, so it sits below it — reach for the free one to get something
working, and move up when accuracy on real documents starts to matter.

---

## What had to change first

Some of these could not have been written against the invoker as it stood.

**Bodies could only be text.** `GuardedHttpSender.send` took a `String`.
Deepgram reads the request body *as the media file*, and AssemblyAI's upload
endpoint wants octets — sending a base64 text encoding would hand their decoders
something that is not audio. A `byte[]` overload was added; the string version
now delegates to it, so nothing else changed behaviour.

**A call was always one round trip.** AssemblyAI uploads, queues a job, then
polls. `SpecialistProvider` gained a `next()` seam returning the next `Call` (and
an optional delay), plus `maxRounds()`. The invoker loops on it. Every existing
adapter inherits the default — `next()` returns null, `maxRounds()` is 1 — so
Roboflow and the generic HTTP adapter were not touched.

The loop is bounded twice on purpose: by `maxRounds()` (hard ceiling of 20 in the
invoker regardless of what an adapter asks for) and by the specialist's own
timeout as a wall clock. A provider that never reports completion cannot hold a
customer's request open.

**Deepgram's auth is its own scheme.** `Authorization: Token <key>` is neither
Bearer nor a bare header value. Rather than store the word "Token" as part of the
secret — where a key rotation would silently drop the prefix and every call would
401 — `AuthStyle.TOKEN` was added. The column is `EnumType.STRING`, so this is
additive with no migration.

---

## The decision that matters most: transcripts are unscored

Deepgram, AssemblyAI and OCR.space all return a confidence. **None of them is
attached to the evidence as a confidence.** They are kept in the evidence
attributes, where the console shows them and no filter acts on them.

The reason is concrete. A pipeline's `minConfidence` is a statement about
*detection* scores — "only tell me about things you are 80% sure you saw". A
recogniser's confidence means something entirely different: how sure it is of its
wording. Treating the second as the first means a threshold of 0.8 silently
deletes an entire transcript that was 78% clean. Not a weak part of it — all of
it, because a transcript is one piece of evidence.

So the transcript is `Evidence.text(...)`, unscored, and the invoker's rule
already covers it:

```java
// A confidence threshold is a statement about scores. Applying one to unscored
// evidence would discard every OCR line and every transcript.
if (!e.scored() || e.confidence() >= specialist.getMinConfidence()) kept.add(e);
```

The same reasoning applies to OCR: recognition confidence says how clearly a
character was read, not whether what it says is true.

### The other side of the rule

**Classifier scores are not like this, and are scored normally.** A Hugging Face
moderation model saying "0.94 toxic" or Google returning "0.98 Dog" is a class
probability — exactly the number a threshold exists to compare against. Those
come back as scored `Evidence.classification` and are filtered like any
detection.

The distinction is not configuration. Hugging Face serves both from the same
endpoint with the same URL shape, so the adapter decides from the **response
shape**: labels and scores become scored classifications, a `{"text": ...}` from
an ASR model on the same provider becomes unscored text. Getting that right from
the shape alone is most of what `HuggingFaceProvider` does.

### And a third case: words instead of numbers

Google's safe-search answers `VERY_UNLIKELY` … `VERY_LIKELY`. Those are ordered
categories, and there is no defensible number for `POSSIBLE`. Converting them
would invent the precision a moderation rule is then compared against, so they
come back as unscored `Evidence.field` carrying Google's own word. A pipeline
decides on what Google actually asserted.

---

## Failure shapes are told apart

Every one of these adapters can come back with nothing, and "nothing" has several
causes that need opposite responses. Each is a distinct `Evidence.note`:

| Situation | What the developer is told |
| --- | --- |
| Deepgram / AssemblyAI, no speech | "returned no speech — the file may be silent, or too short" |
| AssemblyAI, job unfinished | "still transcribing; it did not finish inside this timeout" **plus the job id** |
| AssemblyAI, job errored | the provider's own error message |
| OCR.space, blank page | "read the file and found no text — the page may be blank, or too low-resolution" |
| OCR.space, processing failed | the provider's own error message |
| Hugging Face, bad model id | the provider's own error, which arrives in a 200 body |
| Google Vision, per-image failure | the provider's message, also inside a 200 body |
| Google Vision, nothing found | "found nothing for the requested feature — for OCR that usually means no legible text" |

The AssemblyAI unfinished case is the one worth calling out. It must never read
like silence: one means raise the timeout, the other means check the recording.

**Three of these report failure inside a 200 body**, not in the status code:
OCR.space via `IsErroredOnProcessing`, Hugging Face via a top-level `error`
(which is how a mistyped model id arrives), and Google Vision via an `error`
object nested per-image. A call that "succeeded" is still checked in all three.

### Hugging Face cold starts

A model that has not been called recently is unloaded, and Hugging Face answers
**HTTP 503, "currently loading"**. The invoker treats any 4xx/5xx as failure,
which would make a perfectly good tool look broken the first time it was used
each day. The adapter sends `x-wait-for-model: true`, so Hugging Face holds the
request until the model is ready — one slow call instead of one confusing
failure.

---

## The honest limitation: polling inside a synchronous request

A pipeline run is one HTTP request from the caller's application, and the
AssemblyAI adapter holds it open while the job finishes. AssemblyAI is roughly
real-time, so a short recording usually lands inside a normal timeout. **A long
one will not.**

When the budget runs out the adapter says the job is still processing and returns
the id. That is a truthful partial answer. Long-form audio really wants a
callback, and that is a larger change than an adapter — it needs the pipeline to
suspend and resume, which Continuum's workflow engine can do but the specialist
path currently cannot.

**Deepgram has no such problem**, which is why it is the one to reach for first.

---

## Probing now works for audio

Previously an audio tool could not be probed at all: Continuum had no sample, so
it stayed DRAFT until the developer went and found a clip. The reasoning behind
that was right — the alternative at the time was sending `{"audioBase64": ""}`,
and a probe that proves nothing is worse than no probe, because the tool ends up
READY on the strength of a request no provider could have processed.

`SampleMedia` generates **genuine files** in process:

- a well-formed 1-second WAV containing a real 440Hz tone (not silence — some
  providers reject a signal-free file as corrupt)
- a PNG with `CONTINUUM PROBE 12345` actually drawn on it
- a one-page PDF with a real text layer

The sample now follows the **tool kind**, not just the input kind: an OCR tool
gets the image with text on it, a detector keeps the 1×1 pixel every existing
detector was probed with. A detector has nothing to find either way; an OCR tool
handed a blank pixel is being asked to read nothing.

A transcriber handed a pure tone will correctly report no speech. **That is a
successful probe** — credential, URL, request shape and response parsing all
exercised. It is not a claim that the provider transcribes well, and nothing
says it is.

---

## How this fits the PDF story

`PdfTextTool` reads a born-digital PDF in process, free, with no vendor and no
recognition errors — and reports `NO_TEXT_LAYER` when it meets a scan.

OCR.space is the other half. It accepts PDFs directly, so one pipeline covers
both kinds of document without the developer knowing in advance which will
arrive: the built-in reader handles the ones that already contain text, the OCR
adapter handles the ones that do not.

---

## Catalogue ranking

A ready-to-use entry now outranks a self-hosted template for the same query.
Searching "transcription" surfaces Deepgram and AssemblyAI above "Audio
transcription (your own endpoint)" — someone searching for a transcriber wants
one, not a form for the transcriber they have not written yet. Implemented as a
+5 in `CatalogueEntry.score` for entries that do not require a `baseUrl`.

The stale note on the self-hosted transcription entry was also corrected: it
claimed transcripts arrive "as findings ... with confidence per segment", which
has not been true since the Evidence model landed.

---

## Verification status

| Claim | Status |
| --- | --- |
| Sample WAV is a valid RIFF/WAVE file | ✅ verified (asserted byte-for-byte) |
| Sample PNG has real text; sample PDF has a real text layer | ✅ verified |
| Probe sample follows tool kind | ✅ verified |
| Request shapes match each provider's documented contract | 🟡 **fixture-tested only** |
| Transcripts and OCR text are unscored | ✅ verified |
| Classifier scores ARE scored, from the same providers | ✅ verified |
| Google safe-search stays categorical | ✅ verified |
| Hugging Face maps 6 task shapes from the response alone | ✅ verified (fixtures) |
| Failure shapes distinguished | ✅ verified |
| AssemblyAI's 3-stage sequence drives correctly | 🟡 fixture-tested (the `next()` loop is exercised, the network is not) |
| **Live calls to Deepgram / AssemblyAI / OCR.space** | ❌ **`LIVE_UNVERIFIED` — this environment cannot reach any of them** |

Measured from this deployment:

```
api.assemblyai.com           -> 000   (refused at CONNECT)
api.deepgram.com             -> 000   (refused at CONNECT)
api.ocr.space                -> 000   (refused at CONNECT)
api-inference.huggingface.co -> 000   (refused at CONNECT)
vision.googleapis.com        -> 000   (refused at CONNECT)
```

`ByokProviderLiveIT` holds one test per provider, each gated on that provider's
key and **skipped** here. A skipped test is not a passing test.

```
DEEPGRAM_API_KEY=…      mvn test -Dtest=ByokProviderLiveIT
ASSEMBLYAI_API_KEY=…    mvn test -Dtest=ByokProviderLiveIT
OCRSPACE_API_KEY=…      mvn test -Dtest=ByokProviderLiveIT
HUGGINGFACE_API_KEY=…   mvn test -Dtest=ByokProviderLiveIT
GOOGLE_VISION_API_KEY=… mvn test -Dtest=ByokProviderLiveIT
```

The two OCR tests are the strongest: the probe image has known text drawn on it,
so a working recogniser **must** return "CONTINUUM PROBE". The audio tests can
only assert that a real WAV was accepted and the response understood, since a
tone has no words in it. The Hugging Face test doubles as the cold-start check —
reaching a result at all is what proves `x-wait-for-model` works.

If a live test fails, the fixtures in `ByokProviderTest` are what to fix — they
encode an assumption about a contract, and the service is the authority. No
credential appears in any source file, fixture or properties file.

---

## Not built

- **Azure Document Intelligence / Google Document AI** — structured document
  extraction. Genuinely more configuration than anything above: form models,
  training, and a region to pick.
- **Callback-based long audio** — see the polling limitation above.
- **Multipart request bodies** — no adapter needs one yet. OpenAI Whisper would;
  that is why it was not chosen first.
- **Google Vision via service account** — the API-key path is enough to use it,
  and OAuth would mean token refresh inside the invoker.
