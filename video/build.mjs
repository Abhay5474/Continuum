/**
 * Renders the introduction video.
 *
 * <p>Frames come from a real browser rendering `scenes.html`, driven by an
 * explicit `render(t)` rather than by CSS animation — so a frame can be asked
 * for by time, in any order, and the capture is reproducible rather than a
 * recording of whatever the machine managed that second.
 *
 * <p>Narration comes from `regen-audio.mjs`, which uses Festival's CMU SLT
 * arctic HTS voice. That was picked by measurement rather than by ear — see
 * `score-voice.mjs`. The captions are burned in so the video also works with
 * the sound off, which is how a good deal of it will be watched.
 *
 *   node build.mjs
 */
import { chromium } from 'playwright';
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync, mkdirSync, rmSync, existsSync } from 'node:fs';
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
// Mastering, not colouring. The previous chain lowpassed at 7.6kHz — which
// throws away most of the consonant energy that carries intelligibility — and
// then made up the loss with a flat gain that left the mix at -27 dB mean.
// loudnorm targets the -16 LUFS that web video is normally mixed to, so the
// narration sits at a sane level next to everything else in a browser tab.
execFileSync('ffmpeg', ['-y', '-v', 'error', '-f', 'concat', '-safe', '0', '-i', listPath,
  '-af', [
    'highpass=f=70',            // rumble only; the voice starts well above this
    'deesser=i=0.4',            // HTS sibilance is a little hot at 32kHz
    'loudnorm=I=-16:TP=-1.5:LRA=11',
  ].join(','),
  '-ar', '44100', '-ac', '2', voice]);

const out = join(HERE, '..', 'frontend', 'public', 'continuum-intro.mp4');
execFileSync('ffmpeg', ['-y', '-v', 'error',
  '-framerate', String(FPS), '-i', join(FRAMES, 'f%05d.png'),
  '-i', voice,
  '-c:v', 'libx264', '-preset', 'slow', '-crf', '25',
  // yuv420p or Safari and most embedded players show nothing at all.
  '-pix_fmt', 'yuv420p', '-movflags', '+faststart',
  '-c:a', 'aac', '-b:a', '96k', '-shortest', out]);

// The player's chapters and transcript are emitted here rather than typed into
// the component. They were hand-kept before, and a rewritten line silently left
// every chapter marker pointing at the wrong second. One source of truth.
let at = 0;
const chapters = scenes.map((s) => {
  const row = { id: s.id, label: s.label ?? s.id, at: Math.round(at * 100) / 100, text: s.text };
  at += s.duration;
  return row;
});
const meta = join(HERE, '..', 'frontend', 'src', 'generated', 'intro-chapters.json');
mkdirSync(dirname(meta), { recursive: true });
writeFileSync(meta, JSON.stringify({ total: Math.round(total * 100) / 100, chapters }, null, 2));

const size = execFileSync('bash', ['-c', `du -h ${JSON.stringify(out)} | cut -f1`]).toString().trim();
console.log(`\nwrote ${out} (${size}, ${total.toFixed(1)}s)`);
console.log(`wrote ${meta}`);
