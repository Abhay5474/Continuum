import type { ReactNode } from "react";
import { LazyMotion, MotionConfig, domMax } from "motion/react";
import { transitions } from "./physics/transitions";

/**
 * Motion for the whole app.
 *
 * <p>Strict, so every animated element is an {@code m.*} component and the
 * animation features are loaded once, here. {@code reducedMotion="user"}
 * turns every transform and layout animation into an instant change for
 * anyone who has asked their system for less motion; opacity still fades,
 * because a fade is not motion.
 */
export function MotionRoot({ children }: { children: ReactNode }) {
  return (
    <LazyMotion features={domMax} strict>
      <MotionConfig reducedMotion="user" transition={transitions.standard}>
        {children}
      </MotionConfig>
    </LazyMotion>
  );
}
