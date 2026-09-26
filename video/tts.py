"""
Narration for the introduction video, with Kokoro — a small neural voice.

Called by regen-audio.mjs. Writes audio-<scene>.wav for every scene in
narration.json and records each scene's duration, measured from the audio.

Why this voice. The previous narration was Festival's HTS voice: legible, but
its vocoder has a buzzy, doubled quality that listeners heard as two voices
talking over each other, and its prosody is flat. Kokoro (82M parameters,
Apache-2.0) is a neural voice; it runs offline on a CPU and its model files
come from GitHub releases, which this environment can reach.

How a scene is spoken. Each sentence is synthesised on its own, trimmed of the
model's own leading and trailing silence, and joined with a fixed, short pause.
Letting the model read a whole paragraph gives it nowhere to breathe, and the
pauses come out uneven; per-sentence synthesis gives the steady rhythm of a
person reading a script. Every scene then gets a short lead-in and a tail, so
no scene starts on a syllable or cuts one off, and since a scene's duration is
measured from its finished audio, the picture can never run shorter than the
voice: one scene's narration cannot overlap the next.

Model files (not committed; ~120 MB):
  https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/kokoro-v1.0.int8.onnx
  https://github.com/thewh1teagle/kokoro-onnx/releases/download/model-files-v1.0/voices-v1.0.bin
into video/models/, or point KOKORO_DIR at wherever they are.
"""
import json
import math
import os
import re
import sys

import numpy as np
import soundfile as sf
from kokoro_onnx import Kokoro

HERE = os.path.dirname(os.path.abspath(__file__))
MODELS = os.environ.get("KOKORO_DIR", os.path.join(HERE, "models"))

# af_heart is the voice Kokoro's own evaluation rates highest: warm, even, and
# unhurried. Speed a touch under 1 suits explanatory narration.
VOICE = os.environ.get("KOKORO_VOICE", "af_heart")
SPEED = float(os.environ.get("KOKORO_SPEED", "0.96"))

LEAD_IN = 0.30      # silence before the first word of a scene
BETWEEN = 0.34      # between sentences — a breath, not a stop
TAIL = 0.75         # after the last word, before the picture moves on


def sentences(text):
    parts = re.split(r"(?<=[.!?])\s+", text.strip())
    return [p for p in parts if p]


def trim(audio, sr, floor=0.012):
    """Drops the model's own leading/trailing silence, keeping a few ms of air."""
    loud = np.where(np.abs(audio) > floor)[0]
    if loud.size == 0:
        return audio[:0]
    pad = int(0.03 * sr)
    return audio[max(0, loud[0] - pad): min(len(audio), loud[-1] + pad)]


def fade(audio, sr, ms=12):
    """A few milliseconds of fade at each join, so a cut never clicks."""
    n = min(len(audio) // 2, int(sr * ms / 1000))
    if n > 0:
        ramp = np.linspace(0.0, 1.0, n, dtype=audio.dtype)
        audio[:n] *= ramp
        audio[-n:] *= ramp[::-1]
    return audio


def main():
    kokoro = Kokoro(os.path.join(MODELS, "kokoro-v1.0.int8.onnx"), os.path.join(MODELS, "voices-v1.0.bin"))
    path = os.path.join(HERE, "narration.json")
    scenes = json.load(open(path))
    only = set(sys.argv[1:])
    total = 0.0
    for s in scenes:
        wav = os.path.join(HERE, f"audio-{s['id']}.wav")
        if only and s["id"] not in only and os.path.exists(wav):
            s["duration"] = math.ceil(sf.info(wav).duration * 100) / 100
            total += s["duration"]
            continue
        # `say` is for words a synthesiser gets wrong; `text` is what the
        # captions show, so a respelling never leaks into them.
        spoken = s.get("say") or s["text"]
        sr = 24000
        pieces = [np.zeros(int(LEAD_IN * sr), dtype=np.float32)]
        for i, line in enumerate(sentences(spoken)):
            audio, sr = kokoro.create(line, voice=VOICE, speed=SPEED, lang="en-us")
            if i:
                pieces.append(np.zeros(int(BETWEEN * sr), dtype=np.float32))
            pieces.append(fade(trim(audio.astype(np.float32), sr), sr))
        pieces.append(np.zeros(int(TAIL * sr), dtype=np.float32))
        audio = np.concatenate(pieces)
        sf.write(wav, audio, sr, subtype="PCM_16")
        # Rounded up: a scene may be a few ms longer than its audio, never shorter.
        s["duration"] = math.ceil(len(audio) / sr * 100) / 100
        total += s["duration"]
        print(f"{s['id']:<12} {s['duration']:6.2f}s", flush=True)
    json.dump(scenes, open(path, "w"), indent=2)
    open(path, "a").write("\n")
    print(f"{'TOTAL':<12} {total:6.2f}s")


if __name__ == "__main__":
    main()
