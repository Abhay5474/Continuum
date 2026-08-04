# The introduction video

`frontend/public/continuum-intro.mp4` — 79 seconds, narrated, captions burned
into the picture. Played from the landing page by `IntroVideo.tsx`.

## Rebuilding

```sh
cd video
npm i playwright                 # only the renderer is needed
node regen-audio.mjs             # narration + timings, from narration.json
node build.mjs                   # frames, mux, writes the MP4 and poster
```

`narration.json` is the source of truth. Edit the text, re-run both scripts, and
the scene timings follow — the timeline is derived from the measured audio
durations rather than hand-set, so a rewritten line cannot silently desync the
picture from the voice.

## About the voice

The narration is **espeak-ng**, which is what this environment had available.
It is intelligible and unmistakably synthetic. A better voice — Piper, a cloud
TTS, or a person — drops straight in: replace the `audio-*.wav` files, re-run
`build.mjs`, and nothing else changes.

The captions are burned into the frame rather than shipped as a WebVTT track,
because most viewers watch a landing-page video with the sound off, and a track
a player might not render is not a caption. The same words are in the
transcript under the player.

## Why frames rather than a screen recording

`scenes.html` exposes `render(t)` and uses no CSS animation or
`requestAnimationFrame`. A frame is a pure function of its timestamp, so the
renderer can ask for any frame in any order and get identical pixels. A screen
recording would capture whatever the machine managed that second.
