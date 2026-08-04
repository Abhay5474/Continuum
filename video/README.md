# The introduction video

`frontend/public/continuum-intro.mp4` — 77 seconds, narrated, captions burned
into the picture. Played from the landing page by `IntroVideo.tsx`.

## Rebuilding

```sh
cd video
npm i playwright                 # only the renderer is needed
node regen-audio.mjs             # narration + timings, from narration.json
node build.mjs                   # frames, mux, MP4 + chapter metadata
```

`narration.json` is the single source of truth. Edit the text, re-run both
scripts, and everything else follows: the scene timings come from the measured
audio durations, and the player's chapters and transcript are emitted into
`frontend/src/generated/intro-chapters.json`.

That last part was learned the hard way. The chapter markers were originally
typed into the component; the narration was then rewritten, every marker
silently pointed at the wrong second, and nothing failed. Generated now.

## The voice, and how it was chosen

The narration is **Festival's CMU SLT arctic HTS voice**. It was picked by
measurement, not by ear — `node score-voice.mjs` reproduces the table:

| Voice | Word error rate | What an ASR heard |
| --- | ---: | --- |
| **festival hts slt** | **8.3%** | "continuum is a reliability layer between your…" |
| mbrola us3 | 25.0% | "continuum is a liability where between…" |
| mbrola us2 / en1 | 41.7% | |
| mbrola us1 | 50.0% | |
| espeak-ng en-us | 75.0% | "you is we you really do and which model" |

The method: synthesise a known sentence, run pocketsphinx over the result, and
score the transcript against the script. It does not measure how *pleasant* a
voice is — nothing available offline does — but it measures the thing that
actually went wrong. The first cut of this video used espeak, and three
quarters of it could not be decoded even by a machine. That is what "it sounds
like it is overlapping" was.

**On the absolute numbers.** Across the full script the shipped narration scores
about 30%, not 8%. Most of that gap is the recogniser, not the voice:
pocketsphinx has a fixed 2010-era vocabulary that does not contain *Roboflow*,
*Deepgram* or *Continuum*, so those words are guaranteed errors. The one scene
with no proper nouns scores **3.3%**. The comparison in the table is the honest
one, because it holds everything except the voice constant.

**It is still synthetic.** A human read, or a neural voice like Piper, would be
better. Neither is reachable from this environment: `huggingface.co`,
`hf-mirror.com` and `translate.google.com` are all denied at the network gateway
(`connect_rejected`, policy denial — not a transient failure), so every neural
model and every cloud TTS is out of reach. Swapping one in later is a drop-in:
replace the `audio-*.wav` files, re-run `build.mjs`, and nothing else changes.

The script itself is also written to be *spoken*: short declarative sentences,
no inversions, no long noun phrases. That was worth doing for listeners
regardless of the synthesiser.

## Audio mastering

`highpass 70 → deesser → loudnorm I=-16 TP=-1.5`. The first version lowpassed at
7.6 kHz, which threw away the consonant energy that carries intelligibility, and
then made up the loss with a flat gain that still left the mix at −27 dB mean.
It now sits at −19 dB mean, −4.5 dB peak: the level web video is normally mixed
to, with no clipping.

## Why frames rather than a screen recording

`scenes.html` exposes `render(t)` and uses no CSS animation or
`requestAnimationFrame`. A frame is a pure function of its timestamp, so the
renderer can ask for any frame in any order and get identical pixels. A screen
recording would capture whatever the machine managed that second.
