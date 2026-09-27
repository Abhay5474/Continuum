/* ------------------------------------------------------------------ *
 * Tone
 * ------------------------------------------------------------------ */

/**
 * The six meanings a colour is allowed to carry in this console.
 *
 * <p>Everything below takes a tone rather than a colour, so "this is a failure"
 * is written once and rendered consistently — as ink on a label, as a wash
 * behind a pill, as the rail down the side of a notice. The pairs are defined
 * per theme in {@code index.css}: the ink is chosen to be read against the
 * surface, and the wash is the same ink at low alpha so the two stay legible
 * together on both instrument black and paper.
 */
export type State = "ok" | "warn" | "bad" | "info" | "accent" | "mute";

/**
 * The eight identity hues.
 *
 * <p>Separate from state on purpose. A console that colours everything by state
 * ends up monochrome, because most things are fine most of the time — and one
 * that colours state by identity can't tell you anything is wrong. So a card's
 * mark and its chart wear a hue, which says *which* thing this is, and its
 * badges wear a state, which says whether to care.
 */
export type Hue =
  | "violet"
  | "blue"
  | "cyan"
  | "green"
  | "amber"
  | "orange"
  | "red"
  | "pink";

export type Tone = State | Hue;

/** Fixed order, never hashed — a reader who learned "spend is amber" stays right. */
export const HUES: Hue[] = ["violet", "blue", "cyan", "green", "amber", "orange", "red", "pink"];

/** The hue for slot i, cycling. For a run of cards with no meaning to encode. */
export function hueAt(i: number): Hue {
  return HUES[i % HUES.length];
}

const TONE_INK: Record<Tone, string> = {
  ok: "var(--state-healthy-ink)",
  warn: "var(--state-warning-ink)",
  bad: "var(--state-critical-ink)",
  info: "var(--state-active-ink)",
  accent: "var(--accent-ink)",
  mute: "var(--text-3)",
  violet: "var(--hue-violet-ink)",
  blue: "var(--hue-blue-ink)",
  cyan: "var(--hue-cyan-ink)",
  green: "var(--hue-green-ink)",
  amber: "var(--hue-amber-ink)",
  orange: "var(--hue-orange-ink)",
  red: "var(--hue-red-ink)",
  pink: "var(--hue-pink-ink)",
};

const TONE_WASH: Record<Tone, string> = {
  ok: "var(--wash-ok)",
  warn: "var(--wash-warn)",
  bad: "var(--wash-bad)",
  info: "var(--wash-info)",
  accent: "var(--accent-wash)",
  mute: "var(--wash-mute)",
  violet: "var(--hue-violet-wash)",
  blue: "var(--hue-blue-wash)",
  cyan: "var(--hue-cyan-wash)",
  green: "var(--hue-green-wash)",
  amber: "var(--hue-amber-wash)",
  orange: "var(--hue-orange-wash)",
  red: "var(--hue-red-wash)",
  pink: "var(--hue-pink-wash)",
};

export function toneInk(t: Tone = "mute") {
  return TONE_INK[t];
}
export function toneWash(t: Tone = "mute") {
  return TONE_WASH[t];
}
