/**
 * How many interactive demos are currently running.
 *
 * <p>The world and the demos each hold 60fps alone and drop to 40 together —
 * both are CPU-bound on canvas calls, and two full-rate loops simply do not fit
 * in a frame. Rather than degrade the thing the visitor is actually looking at,
 * the world halves its rate whenever a demo is live: at that moment it is
 * atmosphere behind an instrument, and nobody is studying the parallax while
 * they are dragging a slider.
 *
 * <p>A counter rather than a boolean, because scenes overlap during a scroll and
 * two demos can be on screen at once.
 */
let active = 0;
const listeners = new Set<(active: number) => void>();

export function demoStarted() {
  active += 1;
  listeners.forEach((l) => l(active));
}

export function demoStopped() {
  active = Math.max(0, active - 1);
  listeners.forEach((l) => l(active));
}

export function activeDemos() {
  return active;
}

export function onActivityChange(l: (active: number) => void) {
  listeners.add(l);
  return () => listeners.delete(l);
}

/* ------------------------------------------------------------------ *
 * Events
 * ------------------------------------------------------------------ */

/**
 * Something notable happened inside an instrument.
 *
 * <p>This is what stops the demos from being panels bolted onto a backdrop. A
 * page fault, a settled consensus, a provider failing over — each is real work
 * happening in the fabric, so the fabric answers: the field surges briefly and
 * pushes more traffic through itself. The instrument and the world stop being
 * two things on the same screen and become one system seen at two scales.
 *
 * <p>Intensity is deliberately small for routine events and large for rare ones,
 * so a busy demo does not leave the world permanently lit — which would make the
 * response meaningless.
 */
let surge = 0;

export function pulse(intensity: number) {
  // Saturating rather than accumulating: a hundred cache hits should not add up
  // to an event a hundred times louder than a crash.
  surge = Math.min(1.4, surge + intensity);
}

/** Reads the current surge and decays it. Called once per world frame. */
export function drainSurge(decay = 0.965): number {
  const v = surge;
  surge *= decay;
  if (surge < 0.002) surge = 0;
  return v;
}
