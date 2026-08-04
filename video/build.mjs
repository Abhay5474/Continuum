/**
 * Renders the introduction video.
 *
 * <p>Frames come from a real browser rendering `scenes.html`, driven by an
 * explicit `render(t)` rather than by CSS animation — so a frame can be asked
 * for by time, in any order, and the capture is reproducible rather than a
 * recording of whatever the machine managed that second.
 *
 * <p>Narration is espeak-ng, which is what this environment has. It is
 * intelligible and unmistakably synthetic; the captions are burned in so the
 * video works with the sound off, which is how most of it will be watched.
 * Swapping in a better voice means replacing the WAVs and re-running this — the
 * timeline is derived from their durations, so nothing else needs touching.
 *
 *   node build.mjs
 */
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { readFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
import { fileURLToPath } from 'node:url';
import { dirname, join } from 'node:path';

const HERE = dirname(fileURLToPath(import.meta.url));
const FPS = 25;
const W = 1280;
const H = 720;
const FRAMES = join(HERE, 'frames');

const scenes = JSON.parse(readFileSync(join(HERE, 'narration.json'), 'utf8'));
const total = scenes.reduce((n, s) => n + s.duration, 0);

// The caption is the narration, verbatim. A caption that paraphrases its own
// audio is worse than none — it makes a viewer doubt which one is the content.
const captions = Object.fromEntries(scenes.map((s) => [s.id, s.text]));

if (existsSync(FRAMES)) rmSync(FRAMES, { recursive: true });
mkdirSync(FRAMES, { recursive: true });

const browser = await chromium.launch({ executablePath: '/opt/pw-browsers/chromium' });
const page = await browser.newPage({ viewport: { width: W, height: H }, deviceScaleFactor: 1 });

await page.addInitScript(
  ([s, c]) => {
    window.__SCENES__ = s;
    window.__CAPTIONS__ = c;
  },
  [scenes, captions]
);
await page.goto('file://' + join(HERE, 'scenes.html'), { waitUntil: 'load' });
// Emoji and webfont metrics settle a beat after load; a first frame captured
// before that shows fallback boxes.
await page.waitForTimeout(1200);

const count = Math.ceil(total * FPS);
console.log(`rendering ${count} frames at ${FPS}fps (${total.toFixed(1)}s)`);

for (let i = 0; i < count; i++) {
  await page.evaluate((t) => window.__render(t), i / FPS);
  await page.screenshot({ path: join(FRAMES, `f${String(i).padStart(5, '0')}.png`) });
  if (i % 200 === 0) console.log(`  ${i}/${count}`);
}
await browser.close();

// Narration: concatenate the per-scene WAVs, each padded to its scene length so
// audio and picture cannot drift.
const listPath = join(HERE, 'audio-list.txt');
const parts = [];
for (const s of scenes) {
  const src = join(HERE, `audio-${s.id}.wav`);
  const padded = join(HERE, `pad-${s.id}.wav`);
  execFileSync('ffmpeg', ['-y', '-v', 'error', '-i', src,
    '-af', `apad=whole_dur=${s.duration}`, '-ar', '44100', '-ac', '1', padded]);
  parts.push(`file '${padded}'`);
}
execFileSync('bash', ['-c', `printf "%s\\n" ${parts.map((p) => JSON.stringify(p)).join(' ')} > ${JSON.stringify(listPath)}`]);

const voice = join(HERE, 'voice.wav');
execFileSync('ffmpeg', ['-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', listPath,
  // A gentle shelf and a touch of room take the hardest edge off a synthetic
  // voice. It is still synthetic; it is no longer piercing.
  '-af', 'highpass=f=90,lowpass=f=7600,acompressor=threshold=-18dB:ratio=3:attack=8:release=180,volume=1.35',
  '-ar', '44100', '-ac', '2', voice]);

const out = join(HERE, '..', 'frontend', 'public', 'continuum-intro.mp4');
execFileSync('ffmpeg', ['-y', '-v', 'error',
  '-framerate', String(FPS), '-i', join(FRAMES, 'f%05d.png'),
  '-i', voice,
  '-c:v', 'libx264', '-preset', 'slow', '-crf', '25',
  // yuv420p or Safari and most embedded players show nothing at all.
  '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
  '-c:a', 'aac', '-b:a', '96k', '-shortest', out]);

const size = execFileSync('bash', ['-c', `du -h ${JSON.stringify(out)} | cut -f1`]).toString().trim();
console.log(`\nwrote ${out} (${size}, ${total.toFixed(1)}s)`);
