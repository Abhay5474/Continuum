import type { ReactNode } from "react";
import { LazyMotion, MotionConfig } from "motion/react";
import { transitions } from "./physics/transitions";

/**
 * Motion for the whole app.
 *
 * <p>Strict, so every animated element is an {@code m.*} component. The
 * animation features load as their own chunk just after the first paint. {@code reducedMotion="user"}
 * turns every transform and layout animation into an instant change for
 * anyone who has asked their system for less motion; opacity still fades,
 * because a fade is not motion.
 */
const loadFeatures = () => import("./motionFeatures").then((r) => r.default);

export function MotionRoot({ children }: { children: ReactNode }) {
  return (
    <LazyMotion features={loadFeatures} strict>
      <MotionConfig reducedMotion="user" transition={transitions.standard}>
        {children}
      </MotionConfig>
    </LazyMotion>
  );
}
