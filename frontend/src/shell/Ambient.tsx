import { m, useMotionValue, useSpring, useTransform } from "motion/react";
import { useEffect } from "react";
import { prefersReducedMotion } from "../system/physics";

/**
 * The light the console sits in.
 *
 * <p>Two soft sources — the accent from the top left, a cool counter-light low
 * on the right — behind a fine grain, on a near-white field. They sit at
 * different depths and drift a few pixels against the pointer, the nearer one
 * more, so the glass in front of them reads as a pane at a distance from a
 * lit wall rather than a sticker on a flat colour. The drift is slow and
 * heavily damped; it is felt rather than watched.
 *
 * <p>Fixed and promoted, so the browser moves two textures and repaints
 * nothing. Off for touch, and for anyone who asked for reduced motion.
 */
export function Ambient() {
  const px = useMotionValue(0);
  const py = useMotionValue(0);
  const sx = useSpring(px, { stiffness: 38, damping: 18, mass: 1.2 });
  const sy = useSpring(py, { stiffness: 38, damping: 18, mass: 1.2 });
  const nearX = useTransform(sx, (v) => v * -22);
  const nearY = useTransform(sy, (v) => v * -16);
  const farX = useTransform(sx, (v) => v * 10);
  const farY = useTransform(sy, (v) => v * 8);

  useEffect(() => {
    if (prefersReducedMotion()) return;
    let raf = 0;
    let last: PointerEvent | null = null;
    const paint = () => {
      raf = 0;
      if (!last) return;
      px.set(last.clientX / window.innerWidth - 0.5);
      py.set(last.clientY / window.innerHeight - 0.5);
    };
    const move = (e: PointerEvent) => {
      if (e.pointerType === "touch") return;
      last = e;
      if (!raf) raf = requestAnimationFrame(paint);
    };
    window.addEventListener("pointermove", move, { passive: true });
    return () => {
      cancelAnimationFrame(raf);
      window.removeEventListener("pointermove", move);
    };
  }, [px, py]);

  return (
    <div aria-hidden className="ambient pointer-events-none fixed inset-0 -z-10 overflow-hidden">
      <m.div className="ambient-near" style={{ x: nearX, y: nearY }} />
      <m.div className="ambient-far" style={{ x: farX, y: farY }} />
      <div className="ambient-grain" />
    </div>
  );
}
