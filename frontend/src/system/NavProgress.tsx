import { useEffect, useRef } from "react";
import { useNavigation } from "react-router-dom";
import { motion, useSpring } from "./physics";

/**
 * A hairline along the bottom of the bar while a page is being fetched.
 *
 * <p>Console pages are downloaded on first visit. On a fast connection that is
 * instant and this never shows (it waits 120ms before appearing); on a slow
 * one, the click would otherwise seem to have done nothing. It creeps toward
 * the end rather than claiming a percentage it does not know, then fills and
 * fades when the page arrives.
 */
export function NavProgress() {
  const loading = useNavigation().state === "loading";
  const bar = useRef<HTMLDivElement>(null);
  const p = useSpring(0, { config: motion.slow, kind: "fade", precision: 0.001 });

  useEffect(() => {
    const paint = () => {
      const el = bar.current;
      if (!el) return;
      const v = Math.max(0, Math.min(1, p.value));
      el.style.transform = `scaleX(${v})`;
      el.style.opacity = v >= 0.999 ? "0" : v > 0.001 ? "1" : "0";
    };
    return p.subscribe(paint);
  }, [p]);

  useEffect(() => {
    if (!loading) {
      if (p.value > 0.001) {
        p.set(1);
        p.onRest = () => p.jump(0);
      }
      return;
    }
    p.onRest = undefined;
    const t = setTimeout(() => p.set(0.8, { config: motion.slow }), 120);
    return () => clearTimeout(t);
  }, [loading, p]);

  return (
    <div
      ref={bar}
      role="progressbar"
      aria-hidden={!loading}
      aria-label="Loading page"
      className="pointer-events-none absolute inset-x-0 bottom-0 h-[2px] origin-left"
      style={{ background: "var(--accent)", opacity: 0, transform: "scaleX(0)" }}
    />
  );
}
