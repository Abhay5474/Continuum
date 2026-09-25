/**
 * The spring.
 *
 * <p>Every moving thing in Continuum is a damped harmonic oscillator: a mass on a
 * spring, pulled toward a target, slowed by friction. That one model gives the
 * whole interface a single physical vocabulary — a panel, a toggle thumb and a
 * tab indicator obey the same law with different constants, so they feel like
 * objects in one world rather than a collection of unrelated animations.
 *
 * <h3>Why a closed-form solver</h3>
 * The position at time t is computed exactly from the equation of motion, not
 * accumulated frame by frame. A dropped frame therefore cannot make a spring
 * overshoot further or settle somewhere else, and a busy main thread — an SSE
 * burst, a graph re-layout — changes how often the motion is sampled, never the
 * motion itself.
 *
 * <h3>Why interruption is free</h3>
 * Retargeting reads the current position and velocity and starts a new segment
 * from exactly there. Nothing is queued and nothing restarts from rest: a panel
 * reversed mid-flight turns around with the momentum it had, the way a thrown
 * object would.
 */

export type SpringConfig = {
  /** Pull toward the target. Higher is faster. */
  stiffness: number;
  /** Friction. Relative to stiffness and mass this decides the overshoot. */
  damping: number;
  /** Inertia. A heavier object takes longer to start and to stop. */
  mass: number;
  /** Initial velocity, in units per second, used when a spring starts from rest. */
  velocity?: number;
};

/*
 * The presets. Raw physical constants, because those are what the rest of the
 * system reasons in; describe() turns any of them into the numbers a designer
 * thinks in (how long, how much it overshoots).
 *
 *   preset     ζ      overshoot  settles*  used for
 *   micro      0.73   3.4%       384ms     press, release, icon states
 *   snappy     0.80   1.5%       408ms     menus, tooltips, small surfaces
 *   standard   0.84   0.8%       520ms     indicators, cards, content swaps
 *   gentle     1.00   none       720ms     large quiet changes, fades
 *   heavy      0.93   <0.1%      600ms     panels, pages — things with mass
 *   elastic    0.56   11.9%      676ms     toggles, arrivals that should land
 *   gesture    0.87   0.4%       488ms     releasing something you dragged
 *
 *   * to within 0.1% — measured, not estimated. Almost all of the travel
 *     happens in the first third; the rest is the settle you feel rather than
 *     see, which is the part a fixed-duration ease cuts off.
 */
export const microSpring: SpringConfig = { stiffness: 900, damping: 44, mass: 1 };
export const snappySpring: SpringConfig = { stiffness: 560, damping: 38, mass: 1 };
export const standardSpring: SpringConfig = { stiffness: 340, damping: 31, mass: 1 };
export const gentleSpring: SpringConfig = { stiffness: 170, damping: 26, mass: 1 };
export const heavySpring: SpringConfig = { stiffness: 260, damping: 38, mass: 1.6 };
export const elasticSpring: SpringConfig = { stiffness: 420, damping: 23, mass: 1 };
export const gestureSpring: SpringConfig = { stiffness: 380, damping: 34, mass: 1 };
/**
 * Colour and opacity. Critically damped: a colour that overshoots its target
 * over-saturates for a frame, which reads as a flicker, not as physics.
 */
export const fadeSpring: SpringConfig = { stiffness: 520, damping: 46, mass: 1 };

/** Opacity and colour under reduced motion: quick, no overshoot, never bouncy. */
export const reducedFade: SpringConfig = { stiffness: 900, damping: 60, mass: 1 };

/* ------------------------------------------------------------------ *
 * The solver
 * ------------------------------------------------------------------ */

/**
 * Displacement and velocity at time t for a spring released at displacement
 * x0 (position minus target) with velocity v0. Exact for all three regimes.
 */
export function solve(c: SpringConfig, x0: number, v0: number, t: number): [number, number] {
  const w0 = Math.sqrt(c.stiffness / c.mass);
  const zeta = c.damping / (2 * Math.sqrt(c.stiffness * c.mass));

  if (zeta < 1) {
    // Underdamped: a decaying oscillation. Everything with overshoot.
    const a = zeta * w0;
    const wd = w0 * Math.sqrt(1 - zeta * zeta);
    const e = Math.exp(-a * t);
    const cos = Math.cos(wd * t);
    const sin = Math.sin(wd * t);
    const B = (v0 + a * x0) / wd;
    return [e * (x0 * cos + B * sin), e * (v0 * cos - ((a * v0 + w0 * w0 * x0) / wd) * sin)];
  }
  if (zeta === 1) {
    // Critically damped: the fastest approach that never crosses the target.
    const e = Math.exp(-w0 * t);
    const B = v0 + w0 * x0;
    return [e * (x0 + B * t), e * (v0 - w0 * B * t)];
  }
  // Overdamped: two decaying exponentials, no crossing.
  const s = Math.sqrt(zeta * zeta - 1);
  const r1 = -w0 * (zeta - s);
  const r2 = -w0 * (zeta + s);
  const C2 = (v0 - r1 * x0) / (r2 - r1);
  const C1 = x0 - C2;
  const e1 = Math.exp(r1 * t);
  const e2 = Math.exp(r2 * t);
  return [C1 * e1 + C2 * e2, r1 * C1 * e1 + r2 * C2 * e2];
}

/** How a preset feels, in the terms a designer uses. */
export function describe(c: SpringConfig) {
  const zeta = c.damping / (2 * Math.sqrt(c.stiffness * c.mass));
  const overshoot = zeta < 1 ? Math.exp((-zeta * Math.PI) / Math.sqrt(1 - zeta * zeta)) : 0;
  return { zeta, overshoot, settleMs: settleTime(c) };
}

/** Time for a unit step to come within 0.1% and stay there. */
export function settleTime(c: SpringConfig, precision = 0.001): number {
  let lastOutside = 0;
  for (let ms = 0; ms <= 4000; ms += 4) {
    const [x, v] = solve(c, -1, c.velocity ?? 0, ms / 1000);
    if (Math.abs(x) > precision || Math.abs(v) > precision * 10) lastOutside = ms;
  }
  return lastOutside + 4;
}

/**
 * The spring as a CSS easing curve.
 *
 * <p>{@code linear()} takes a list of output values, so a sampled spring —
 * overshoot included — becomes a timing function any CSS transition can use.
 * This is how hover and press states follow the same physics as the JS-driven
 * surfaces without a single line of script per element.
 */
export function toCssLinear(c: SpringConfig, points = 48): { easing: string; duration: number } {
  const duration = settleTime(c);
  const out: string[] = [];
  for (let i = 0; i <= points; i++) {
    const t = (duration / 1000) * (i / points);
    const [x] = solve(c, -1, c.velocity ?? 0, t);
    out.push((1 + x).toFixed(4).replace(/\.?0+$/, "") || "0");
  }
  out[out.length - 1] = "1";
  return { easing: `linear(${out.join(", ")})`, duration };
}

/* ------------------------------------------------------------------ *
 * Reduced motion
 * ------------------------------------------------------------------ */

const media =
  typeof window !== "undefined" && window.matchMedia
    ? window.matchMedia("(prefers-reduced-motion: reduce)")
    : null;
let reduced = !!media?.matches;
media?.addEventListener?.("change", (e) => {
  reduced = e.matches;
});

/** Whether the person has asked for less motion. Live: it follows the OS setting. */
export function prefersReducedMotion() {
  return reduced;
}

/* ------------------------------------------------------------------ *
 * The ticker
 * ------------------------------------------------------------------ */

/*
 * One requestAnimationFrame loop for every spring on the page. Forty springs
 * settling at once cost one callback per frame, not forty, and when nothing is
 * moving there is no loop at all.
 */
const active = new Set<Spring>();
let frame = 0;

function loop(now: number) {
  frame = 0;
  for (const s of active) {
    if (s.step(now)) active.delete(s);
  }
  if (active.size) frame = requestAnimationFrame(loop);
}

function wake(s: Spring) {
  active.add(s);
  if (!frame) frame = requestAnimationFrame(loop);
}

/* ------------------------------------------------------------------ *
 * The spring object
 * ------------------------------------------------------------------ */

export type SpringOptions = {
  config?: SpringConfig;
  /**
   * What this spring moves. Under reduced motion a "move" spring arrives
   * instantly, while a "fade" spring still eases — opacity and colour carry
   * state, and removing them removes information, not just motion.
   */
  kind?: "move" | "fade";
  /** At rest when within this of the target, in the spring's own units. */
  precision?: number;
  onUpdate?: (value: number, velocity: number) => void;
  onRest?: (value: number) => void;
};

export class Spring {
  config: SpringConfig;
  kind: "move" | "fade";
  precision: number;
  onUpdate?: (value: number, velocity: number) => void;
  onRest?: (value: number) => void;

  private listeners = new Set<(value: number, velocity: number) => void>();
  private target: number;
  private x0 = 0;
  private v0 = 0;
  private t0 = 0;
  private resting = true;
  private last: number;
  private lastV = 0;

  constructor(initial: number, opts: SpringOptions = {}) {
    this.config = opts.config ?? standardSpring;
    this.kind = opts.kind ?? "move";
    this.precision = opts.precision ?? 0.001;
    this.onUpdate = opts.onUpdate;
    this.onRest = opts.onRest;
    this.target = initial;
    this.last = initial;
  }

  /** Where it is right now — exact, even between frames. */
  get value() {
    return this.resting ? this.target : this.sample(performance.now())[0];
  }

  /** How fast it is moving right now, in units per second. */
  get velocity() {
    return this.resting ? 0 : this.sample(performance.now())[1];
  }

  get goal() {
    return this.target;
  }

  get isResting() {
    return this.resting;
  }

  /**
   * Send it somewhere. Called mid-flight, it turns around from where it is with
   * the velocity it has — the whole of interruptibility is this method.
   */
  set(target: number, opts: { config?: SpringConfig; velocity?: number } = {}) {
    const now = performance.now();
    const [pos, vel] = this.resting ? [this.target, 0] : this.sample(now);
    if (opts.config) this.config = opts.config;
    this.target = target;

    if (reduced && this.kind === "move") {
      this.arrive(target);
      return this;
    }
    this.x0 = pos - target;
    this.v0 = opts.velocity ?? (this.resting ? (this.config.velocity ?? 0) : vel);
    this.t0 = now;
    if (Math.abs(this.x0) < this.precision && Math.abs(this.v0) < this.precision * 10) {
      this.arrive(target);
      return this;
    }
    this.resting = false;
    wake(this);
    return this;
  }

  /**
   * Listens to every frame. Returns the unsubscribe. The value is delivered
   * immediately, so a listener attached after mount paints the right state on
   * its first frame rather than flashing the resting one.
   */
  subscribe(fn: (value: number, velocity: number) => void) {
    this.listeners.add(fn);
    fn(this.value, this.velocity);
    return () => {
      this.listeners.delete(fn);
    };
  }

  /** An immediate arrival still counts as arriving: onRest fires. */
  private arrive(target: number) {
    this.jump(target);
    this.onRest?.(target);
  }

  /** Put it somewhere with no motion — for following a finger. */
  jump(value: number) {
    this.target = value;
    this.x0 = 0;
    this.v0 = 0;
    this.resting = true;
    active.delete(this);
    this.emit(value, 0);
    return this;
  }

  /** Advances to a frame time. Returns true once at rest. */
  step(now: number): boolean {
    if (this.resting) return true;
    const [pos, vel] = this.sample(now);
    if (Math.abs(pos - this.target) < this.precision && Math.abs(vel) < this.precision * 10) {
      this.resting = true;
      this.emit(this.target, 0);
      this.onRest?.(this.target);
      return true;
    }
    this.emit(pos, vel);
    return false;
  }

  /**
   * Freezes it where it is. Not at its target: an unmount that snapped every
   * spring to its destination would make React StrictMode's rehearsal unmount
   * finish each animation before it started, and in production a component
   * that stops a spring mid-flight means "hold here", not "skip to the end".
   */
  stop() {
    if (!this.resting) {
      const [pos] = this.sample(performance.now());
      this.target = pos;
      this.x0 = 0;
      this.v0 = 0;
    }
    active.delete(this);
    this.resting = true;
  }

  private sample(now: number): [number, number] {
    const cfg = reduced && this.kind === "fade" ? reducedFade : this.config;
    const [x, v] = solve(cfg, this.x0, this.v0, Math.max(0, now - this.t0) / 1000);
    return [this.target + x, v];
  }

  private emit(v: number, vel: number) {
    if (v === this.last && vel === this.lastV) return;
    this.last = v;
    this.lastV = vel;
    this.onUpdate?.(v, vel);
    for (const fn of this.listeners) fn(v, vel);
  }
}

/* ------------------------------------------------------------------ *
 * Velocity from a gesture
 * ------------------------------------------------------------------ */

/**
 * Release velocity from pointer samples.
 *
 * <p>Taking only the last two samples makes a flick read as whatever the
 * finger did in its final 4ms, which is mostly noise. This fits a line through
 * the last ~80ms instead — the same window Android's VelocityTracker uses — so a
 * fast swipe and a slow drag that happen to end at the same point release with
 * the momentum each actually had.
 */
export class VelocityTracker {
  private samples: { t: number; x: number }[] = [];

  add(x: number, t = performance.now()) {
    this.samples.push({ t, x });
    while (this.samples.length > 2 && t - this.samples[0].t > 100) this.samples.shift();
  }

  reset() {
    this.samples = [];
  }

  /** Units per second. */
  velocity(now = performance.now()): number {
    const s = this.samples.filter((p) => now - p.t <= 80);
    if (s.length < 2) return 0;
    const n = s.length;
    const mt = s.reduce((a, p) => a + p.t, 0) / n;
    const mx = s.reduce((a, p) => a + p.x, 0) / n;
    let num = 0;
    let den = 0;
    for (const p of s) {
      num += (p.t - mt) * (p.x - mx);
      den += (p.t - mt) ** 2;
    }
    return den === 0 ? 0 : (num / den) * 1000;
  }
}

/**
 * Where a released object would come to rest if it only had friction.
 *
 * <p>Used to decide the outcome of a gesture from its momentum, not its final
 * position: a short fast flick carries a panel past the dismiss line even
 * though the finger lifted before reaching it.
 */
export function projectedRest(position: number, velocity: number, deceleration = 0.998) {
  return position + ((velocity / 1000) * deceleration) / (1 - deceleration);
}

/** Resistance past an edge: follows the finger, but less and less. */
export function rubberband(overshoot: number, dimension: number, constant = 0.55) {
  const sign = Math.sign(overshoot);
  const x = Math.abs(overshoot);
  return sign * (1 - 1 / ((x * constant) / dimension + 1)) * dimension;
}
