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
