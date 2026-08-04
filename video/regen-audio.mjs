/**
 * Regenerates the narration and re-measures the timeline.
 *
 * <p><b>The voice is Festival's CMU SLT arctic HTS voice</b>, not espeak. That
 * was measured, not chosen by ear. Running an ASR over each candidate and
 * scoring the transcript against the script gives espeak a <b>75% word error
 * rate</b> and HTS <b>8.3%</b>: three quarters of the espeak narration was not
 * recoverable even by a machine, which is what "it sounds like it is
 * overlapping" actually was. The MBROLA diphone voices sat in between and are
 * 16kHz besides.
 *
 * <pre>
 *   espeak en-us     75.0%   "you is we you really do and which model"
 *   HTS slt arctic    8.3%   "continuum is a reliability layer between your…"
 *   MBROLA us3       25.0%
 *   MBROLA us2/en1   41.7%
 *   MBROLA us1       50.0%
 * </pre>
 *
 * <p>{@code node score-voice.mjs} reproduces that table.
 *
 * <p>The durations in narration.json are measured from the rendered audio, not
 * estimated from word count — so the picture follows the voice rather than the
 * two being kept in sync by hand.
 */
import { execFileSync } from 'node:child_process';
import { readFileSync, writeFileSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { fileURLToPath } from 'node:url';

const HERE = dirname(fileURLToPath(import.meta.url));
const scenes = JSON.parse(readFileSync(join(HERE, 'narration.json'), 'utf8'));

/** The Festival voice, under the name the installed package registers. */
const VOICE = 'voice_cmu_us_slt_arctic_hts';

let total = 0;
for (const s of scenes) {
  const wav = join(HERE, `audio-${s.id}.wav`);

  // `say` exists for words a synthesiser mispronounces. It is deliberately
  // separate from `text`, which is what the captions and the transcript show —
  // a respelling that leaked into a caption would be a visible typo.
  const spoken = s.say ?? s.text;

  execFileSync('text2wave', ['-eval', `(${VOICE})`, '-o', wav], { input: spoken });

  const d = Number(execFileSync('ffprobe',
    ['-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', wav])
    .toString().trim());

  // A tail pause so a scene does not cut on the last syllable.
  s.duration = Math.round((d + 0.6) * 100) / 100;
  total += s.duration;
  console.log(`${s.id.padEnd(12)} ${s.duration.toFixed(2)}s`);
}

writeFileSync(join(HERE, 'narration.json'), JSON.stringify(scenes, null, 2));
console.log(`${'TOTAL'.padEnd(12)} ${total.toFixed(2)}s`);
