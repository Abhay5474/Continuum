/**
 * Regenerates the narration and re-measures the timeline.
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

let total = 0;
for (const s of scenes) {
  const wav = join(HERE, `audio-${s.id}.wav`);
  execFileSync('espeak-ng', ['-v', 'en-us', '-s', '172', '-p', '40', '-g', '5', '-w', wav, s.text]);
  const d = Number(execFileSync('ffprobe',
    ['-v', 'error', '-show_entries', 'format=duration', '-of', 'default=nw=1:nk=1', wav]).toString().trim());
  // A tail pause so a scene does not cut on the last syllable.
  s.duration = Math.round((d + 0.55) * 100) / 100;
  total += s.duration;
  console.log(`${s.id.padEnd(12)} ${s.duration.toFixed(2)}s`);
}
writeFileSync(join(HERE, 'narration.json'), JSON.stringify(scenes, null, 2));
console.log(`${'TOTAL'.padEnd(12)} ${total.toFixed(2)}s`);
