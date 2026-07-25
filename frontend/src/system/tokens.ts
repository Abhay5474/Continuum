/**
 * Continuum design system — tokens.
 *
 * The interface is an instrument, not a dashboard. Colour is used semantically:
 * a single system accent carries "signal", and every other hue means a specific
 * system state. Nothing is coloured for decoration.
 */

/** System states. Order is severity-ascending; `rank` drives the worst-state roll-up. */
export const STATE = {
  idle: { label: "Idle", color: "#5A6478", glow: "rgba(90,100,120,0.35)", rank: 0 },
  healthy: { label: "Healthy", color: "#34D399", glow: "rgba(52,211,153,0.45)", rank: 1 },
  active: { label: "Active", color: "#4C8BF5", glow: "rgba(76,139,245,0.55)", rank: 2 },
  warning: { label: "Warning", color: "#F5B544", glow: "rgba(245,181,68,0.5)", rank: 3 },
  degraded: { label: "Degraded", color: "#F97C4A", glow: "rgba(249,124,74,0.5)", rank: 4 },
  critical: { label: "Critical", color: "#F4566E", glow: "rgba(244,86,110,0.55)", rank: 5 },
  offline: { label: "Not installed", color: "#333A49", glow: "rgba(51,58,73,0.25)", rank: -1 },
} as const;

export type StateKey = keyof typeof STATE;

/** Worst state wins — used to roll subsystem states up into the Core. */
export function worst(states: StateKey[]): StateKey {
  return states.reduce<StateKey>((acc, s) => (STATE[s].rank > STATE[acc].rank ? s : acc), "idle");
}

/** Motion timing. Every duration is tied to how a real system event should read. */
export const MOTION = {
  /** Entrances and layout shifts. */
  ease: "cubic-bezier(0.22, 1, 0.36, 1)",
  fast: 220,
  base: 420,
  slow: 700,
  /** A signal packet crossing one topology edge, in seconds. */
  packetSeconds: 1.6,
} as const;

/** Instrument surface elevations — recessed planes defined by hairlines, not shadows. */
export const SURFACE = {
  base: "rgb(var(--ink))",
  plane: "rgb(var(--panel))",
  hairline: "rgb(var(--edge))",
} as const;

export const ACCENT = "#4C8BF5";
export const ACCENT_SOFT = "#7DA9FF";
