# The introduction video

`frontend/public/continuum-intro.mp4` — 75 seconds, narrated, captions burned
into the picture. Played from the landing page by `IntroVideo.tsx`.

## Rebuilding

```sh
cd video
npm i playwright                 # only the renderer is needed
node regen-audio.mjs             # narration + timings (Kokoro; see "The voice")
node build.mjs                   # frames, mux, MP4 + chapter metadata
```

`narration.json` is the single source of truth. Edit the text, re-run both
scripts, and everything else follows: the scene timings come from the measured
audio durations, and the player's chapters and transcript are emitted into
`frontend/src/generated/intro-chapters.json`.

That last part was learned the hard way. The chapter markers were originally
typed into the component; the narration was then rewritten, every marker
silently pointed at the wrong second, and nothing failed. Generated now.

## The voice

The narration is **Kokoro** (`af_heart`), an 82M-parameter neural voice that
runs offline on a CPU. It replaced Festival's HTS voice, which was legible but
whose buzzy vocoder people heard as two voices overlapping, with flat delivery.

How a scene is spoken (`tts.py`): each sentence is synthesised separately,
trimmed of the model's own silence, and joined with a fixed 0.34 s breath, with
a short lead-in and tail per scene. A scene's length is measured from its
finished audio and rounded *up*, so the picture always outlasts the voice and
one scene's narration can never run into the next.

Legibility was checked the same way as before — an offline recogniser
(pocketsphinx) against the script: 25% word error rate for Kokoro against 30%
for the HTS narration it replaced. Most of what remains is names the 2010-era
recogniser has never heard (Continuum, Roboflow, Deepgram), not the voice.

Setup, once:

```sh
pip install kokoro-onnx soundfile imageio-ffmpeg
mkdir -p models && cd models
curl -LO https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.int8.onnx
curl -LO https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin
```

Without a system ffmpeg, point the build at the one imageio-ffmpeg ships:
`FFMPEG=$(python3 -c "import imageio_ffmpeg as i; print(i.get_ffmpeg_exe())") node build.mjs`.
`KOKORO_VOICE` and `KOKORO_SPEED` override the voice.

The script is written to be *spoken*: short declarative sentences, no
inversions, no long noun phrases.

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
