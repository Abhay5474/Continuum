import { useLayoutEffect, useRef, type RefObject } from "react";
import { Spring } from "./spring";
import { motion } from "./tokens";

/**
 * Where each indicator last was, by key.
 *
 * <p>Needed because some tab strips are remounted by the navigation they
 * perform — the feature tabs live inside the route's keyed container — so
 * without memory every move would start from nowhere and the indicator would
 * appear rather than travel.
 */
const memory = new Map<string, { l: number; r: number }>();

/**
 * A liquid indicator: two springs, one per edge.
 *
 * <p>Moving to a tab on the right, the right edge leads on the snappy spring
 * and the left edge follows on the standard one, so the indicator stretches
 * toward its destination and draws itself in as it arrives — it reads as
 * something travelling, not a box being teleported and resized. Reversed
 * mid-flight, each edge turns around with its own momentum.
 *
 * @param paint receives the two edges in px relative to the container
 */
export function useLiquidIndicator(
  container: RefObject<HTMLElement>,
  activeKey: string | null | undefined,
  paint: (left: number, right: number) => void,
  opts: { memoryKey?: string; selector?: (key: string) => string } = {}
) {
  const paintRef = useRef(paint);
  paintRef.current = paint;
  const springs = useRef<{ l: Spring; r: Spring } | null>(null);

  if (!springs.current) {
    const remembered = opts.memoryKey ? memory.get(opts.memoryKey) : undefined;
    const draw = () => {
      const s = springs.current;
      if (!s) return;
      paintRef.current(s.l.value, s.r.value);
      if (opts.memoryKey) memory.set(opts.memoryKey, { l: s.l.goal, r: s.r.goal });
    };
    springs.current = {
      l: new Spring(remembered?.l ?? 0, { precision: 0.05, onUpdate: draw }),
      r: new Spring(remembered?.r ?? 0, { precision: 0.05, onUpdate: draw }),
    };
  }

  const measure = () => {
    const root = container.current;
    if (!root || activeKey == null) return null;
    const sel = opts.selector ? opts.selector(activeKey) : `[data-indicator-key="${CSS.escape(activeKey)}"]`;
    const el = root.querySelector<HTMLElement>(sel);
    if (!el) return null;
    const a = root.getBoundingClientRect();
    const b = el.getBoundingClientRect();
    return { l: b.left - a.left + root.scrollLeft, r: b.right - a.left + root.scrollLeft };
  };

  useLayoutEffect(() => {
    const s = springs.current!;
    const target = measure();
    if (!target) return;
    const remembered = opts.memoryKey ? memory.get(opts.memoryKey) : undefined;
    const fresh = s.l.goal === 0 && s.r.goal === 0 && !remembered;
    if (fresh) {
      // First sight of this strip: no travel to show, so just be there.
      s.l.jump(target.l);
      s.r.jump(target.r);
      paintRef.current(target.l, target.r);
      return;
    }
    const goingRight = target.l > s.l.value;
    s.l.set(target.l, { config: goingRight ? motion.standard : motion.fast });
    s.r.set(target.r, { config: goingRight ? motion.fast : motion.standard });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeKey]);

  // A resize is not an event the user caused, so it re-measures without motion.
  useLayoutEffect(() => {
    const root = container.current;
    if (!root || typeof ResizeObserver === "undefined") return;
    const ro = new ResizeObserver(() => {
      const t = measure();
      const s = springs.current!;
      if (!t || !s.l.isResting || !s.r.isResting) return;
      s.l.jump(t.l);
      s.r.jump(t.r);
      paintRef.current(t.l, t.r);
    });
    ro.observe(root);
    return () => ro.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [activeKey]);

  useLayoutEffect(() => () => {
    springs.current?.l.stop();
    springs.current?.r.stop();
  }, []);
}
