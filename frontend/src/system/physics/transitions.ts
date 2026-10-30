import type { Transition } from "motion/react";
import {
  elasticSpring,
  fadeSpring,
  gentleSpring,
  gestureSpring,
  heavySpring,
  microSpring,
  snappySpring,
  standardSpring,
  type SpringConfig,
} from "./spring";

/**
 * The spring presets as Motion transitions.
 *
 * <p>Components animated with Motion (layout changes, presence, gestures) move
 * on exactly the constants that drive the hand-painted springs and the CSS
 * linear() curves, so a sidebar pill gliding, a panel opening and a button
 * releasing are one physics, not three.
 */
export const springOf = (c: SpringConfig): Transition => ({
  type: "spring",
  stiffness: c.stiffness,
  damping: c.damping,
  mass: c.mass,
});

export const transitions = {
  /** Press, release, a toggle's thumb. */
  micro: springOf(microSpring),
  /** Menus, popovers, a hover highlight following the pointer. */
  fast: springOf(snappySpring),
  /** Indicators, cards, content changing in place. */
  standard: springOf(standardSpring),
  /** Large, quiet changes. */
  slow: springOf(gentleSpring),
  /** Arrivals that should visibly land. */
  elastic: springOf(elasticSpring),
  /** Panels, drawers, the command palette. */
  modal: springOf(heavySpring),
  /** Whatever a finger lets go of. */
  gesture: springOf(gestureSpring),
  /** Opacity and colour: never overshoots. */
  fade: springOf(fadeSpring),
} as const;
