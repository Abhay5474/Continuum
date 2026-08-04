/**
 * Scores every installed voice for intelligibility, so the choice of narrator
 * is evidence rather than an opinion.
 *
 * <p>The method is deliberately blunt: synthesise a known sentence, run an ASR
 * over the result, and measure the word error rate of the transcript against
 * the script. It does not measure how pleasant a voice is — nothing offline
 * does — but it measures the thing that actually went wrong the first time. A
 * narration a speech recogniser cannot decode is one a person will describe as
 * slurred, overlapping or robotic, and that is precisely what happened.
 *
 *   node score-voice.mjs
 */
import { execFileSync } from 'node:child_process';
import { mkdtempSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { join } from 'node:path';

const SENTENCE = 'Continuum is a reliability layer between your application and a language model.';
const REF = normalise(SENTENCE);

const CANDIDATES = [
  { id: 'festival hts slt', gen: (out) => execFileSync('text2wave',
      ['-eval', '(voice_cmu_us_slt_arctic_hts)', '-o', out], { input: SENTENCE }) },
  { id: 'espeak en-us', gen: (out) => execFileSync('espeak-ng',
      ['-v', 'en-us', '-s', '172', '-p', '40', '-g', '5', '-w', out, SENTENCE]) },
  ...['mb-us1', 'mb-us2', 'mb-us3', 'mb-en1'].map((v) => ({
    id: `mbrola ${v}`,
    gen: (out) => execFileSync('espeak-ng', ['-v', v, '-s', '160', '-w', out, SENTENCE]),
  })),
];

function normalise(s) {
  return s.toLowerCase().replace(/[^a-z ]/g, ' ').split(/\s+/).filter(Boolean);
}

/** Levenshtein over words, divided by the reference length. */
function wer(ref, hyp) {
  const d = Array.from({ length: ref.length + 1 }, (_, i) =>
    Array.from({ length: hyp.length + 1 }, (_, j) => (i === 0 ? j : j === 0 ? i : 0)));
  for (let i = 1; i <= ref.length; i++) {
    for (let j = 1; j <= hyp.length; j++) {
      d[i][j] = Math.min(d[i - 1][j] + 1, d[i][j - 1] + 1,
        d[i - 1][j - 1] + (ref[i - 1] === hyp[j - 1] ? 0 : 1));
    }
  }
  return d[ref.length][hyp.length] / ref.length;
}

const dir = mkdtempSync(join(tmpdir(), 'voicescore-'));
const rows = [];

for (const c of CANDIDATES) {
  const raw = join(dir, 'raw.wav');
  const mono = join(dir, 'mono.wav');
  try {
    c.gen(raw);
  } catch {
    rows.push({ id: c.id, wer: null, heard: 'voice not installed' });
    continue;
  }
  // pocketsphinx wants 16kHz mono; every candidate produces something else.
  execFileSync('ffmpeg', ['-y', '-v', 'error', '-i', raw, '-ar', '16000', '-ac', '1', mono]);
  const heard = execFileSync('pocketsphinx_continuous',
    ['-infile', mono, '-logfn', '/dev/null']).toString().trim().replace(/\s+/g, ' ');
  rows.push({ id: c.id, wer: wer(REF, normalise(heard)), heard });
}

rows.sort((a, b) => (a.wer ?? 9) - (b.wer ?? 9));
console.log(`\nreference: "${SENTENCE}"\n`);
for (const r of rows) {
  const score = r.wer === null ? '   —  ' : `${(r.wer * 100).toFixed(1)}%`.padStart(6);
  console.log(`${r.id.padEnd(18)} WER ${score}   "${r.heard.slice(0, 62)}"`);
}
console.log('\nLower is better. The narration uses the top row.');
