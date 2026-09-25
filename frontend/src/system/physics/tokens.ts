import {
  elasticSpring,
  fadeSpring,
  gentleSpring,
  gestureSpring,
  heavySpring,
  microSpring,
  snappySpring,
  standardSpring,
  toCssLinear,
  type SpringConfig,
} from "./spring";

/**
 * Motion tokens.
 *
 * <p>Components ask for a role — "a press", "a modal", "a page" — never for a
 * duration or a curve. That indirection is what keeps the interface speaking
 * one motion language: retune {@code motion.modal} and every panel, guide and
 * overlay changes together, and nothing can quietly drift to its own 300ms.
 *
 * <p>The same tokens exist twice: as spring constants here, for surfaces driven
 * from script, and as CSS custom properties generated from those constants, for
 * hover and press states driven by the browser. Both come from one table, so
 * a button's CSS release and a panel's scripted release are the same physics.
 */
export const motion = {
  /** Press, release, icon state. Tiny, quick, a hint of give. */
  micro: microSpring,
  /** Menus, tooltips, anything small that appears from an anchor. */
  fast: snappySpring,
  /** Indicators, cards, content that changes in place. */
  standard: standardSpring,
  /** Large, quiet changes that should not draw the eye. */
  slow: gentleSpring,
  /** The default when nothing more specific applies. */
  spring: standardSpring,
  /** Arrivals that should visibly land: toggles, notifications. */
  elastic: elasticSpring,
  /** Panels, the guide, anything that takes over part of the screen. */
  modal: heavySpring,
  /** A page entering from where it was opened. */
  page: heavySpring,
  /** Whatever a finger lets go of, carrying the finger's velocity. */
  gesture: gestureSpring,
  /** Colour and opacity: arrives without overshooting. */
  fade: fadeSpring,
} satisfies Record<string, SpringConfig>;

export type MotionToken = keyof typeof motion;

/** How far things move. Small on purpose: this is an instrument, not a toy. */
export const scale = {
  /** A button under a finger. */
  press: 0.965,
  /** A larger surface under a finger — less, because more area moves. */
  pressSurface: 0.988,
  /** A card being considered. */
  hover: 1.0,
  /** A surface lifting toward the cursor, in px. */
  lift: 1,
} as const;

export const blur = {
  /** The scene behind an overlay, at full focus loss. */
  modal: 6,
  /** A menu materialising out of focus. */
  menu: 6,
  /** A tooltip — barely; it is a label, not an event. */
  tooltip: 3,
} as const;

/** CSS variable names for elevation, defined per theme in index.css. */
export const shadow = {
  rest: "var(--shadow-rest)",
  hover: "var(--shadow-hover)",
  active: "var(--shadow-active)",
  float: "var(--shadow-float)",
  overlay: "var(--shadow-overlay)",
} as const;

/**
 * Writes the spring curves onto :root as custom properties.
 *
 * <p>Browsers without {@code linear()} get the nearest cubic-bézier instead of
 * an invalid declaration, which would silently fall back to {@code ease} and
 * make those browsers the only place the motion is generic.
 */
export function installMotionTokens(root: HTMLElement = document.documentElement) {
  const supportsLinear =
    typeof CSS !== "undefined" && CSS.supports?.("transition-timing-function", "linear(0, 1)");
  const fallback: Record<MotionToken, string> = {
    micro: "cubic-bezier(0.34, 1.3, 0.64, 1)",
    fast: "cubic-bezier(0.3, 1.15, 0.55, 1)",
    standard: "cubic-bezier(0.25, 1.05, 0.5, 1)",
    slow: "cubic-bezier(0.22, 1, 0.36, 1)",
    spring: "cubic-bezier(0.25, 1.05, 0.5, 1)",
    elastic: "cubic-bezier(0.34, 1.56, 0.64, 1)",
    modal: "cubic-bezier(0.22, 1, 0.36, 1)",
    page: "cubic-bezier(0.22, 1, 0.36, 1)",
    gesture: "cubic-bezier(0.22, 1, 0.36, 1)",
    fade: "cubic-bezier(0.25, 0.8, 0.4, 1)",
  };
  for (const [name, config] of Object.entries(motion) as [MotionToken, SpringConfig][]) {
    const { easing, duration } = toCssLinear(config);
    root.style.setProperty(`--ease-${name}`, supportsLinear ? easing : fallback[name]);
    root.style.setProperty(`--dur-${name}`, `${duration}ms`);
  }
  root.style.setProperty("--scale-press", String(scale.press));
  root.style.setProperty("--scale-press-surface", String(scale.pressSurface));
  root.style.setProperty("--lift", `${scale.lift}px`);
  root.style.setProperty("--blur-modal", `${blur.modal}px`);
  root.style.setProperty("--blur-menu", `${blur.menu}px`);
  root.style.setProperty("--blur-tooltip", `${blur.tooltip}px`);
}
