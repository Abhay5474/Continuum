/**
 * Regenerates the narration and re-measures the timeline.
 *
 * <p><b>The voice is Kokoro</b> (af_heart), a small neural text-to-speech model
 * that runs offline — see tts.py for the model files and how a scene is spoken.
 * It replaced Festival's HTS voice, whose buzzy vocoder people heard as two
 * voices overlapping and whose delivery was flat. Scored with the same offline
 * recogniser against the script, the new narration is also the more legible of
 * the two (25% word error rate against 30%, most of what remains being names
 * like Roboflow and Deepgram that the recogniser has never heard of).
 *
 * <p>The durations in narration.json are measured from the rendered audio, not
 * estimated from word count — so the picture follows the voice rather than the
 * two being kept in sync by hand, and a scene can never end before its narration.
 *
 *   pip install kokoro-onnx soundfile
 *   node regen-audio.mjs            # every scene
 *   node regen-audio.mjs intro      # just one; the rest keep their audio
 */
import { execFileSync } from 'node:child_process';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
execFileSync(process.env.PYTHON ?? 'python3', [join(HERE, 'tts.py'), ...process.argv.slice(2)], { stdio: 'inherit' });
