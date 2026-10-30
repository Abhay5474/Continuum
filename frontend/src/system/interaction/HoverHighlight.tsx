import { m, useSpring } from "motion/react";
import { useCallback, useRef, useState, type PointerEvent, type RefObject } from "react";
import { prefersReducedMotion, snappySpring } from "../physics";

/**
 * A highlight that follows the pointer from item to item.
 *
 * <p>One surface for the whole list, not one per item: it slides from the row
 * you left to the row you reached, so moving down a menu reads as one gesture
 * rather than a series of rows lighting up. It appears in place when the
 * pointer arrives from outside — sliding in from wherever it last was would be
 * motion that means nothing — and fades when the pointer leaves.
 *
 * <p>Items are any descendants matching {@code selector} ({@code [data-hl]} by
 * default). Touch never shows it: there is no hover on glass you tap.
 */
export function useHoverHighlight(container: RefObject<HTMLElement>, selector = "[data-hl]") {
  const spring = { stiffness: snappySpring.stiffness, damping: snappySpring.damping, mass: snappySpring.mass };
  const x = useSpring(0, spring);
  const y = useSpring(0, spring);
  const w = useSpring(0, spring);
  const h = useSpring(0, spring);
  const [shown, setShown] = useState(false);
  const at = useRef<Element | null>(null);

  const moveTo = useCallback(
    (el: Element | null) => {
      const c = container.current;
      at.current = el;
      if (!c || !el) {
        setShown(false);
        return;
      }
      const cr = c.getBoundingClientRect();
      const r = el.getBoundingClientRect();
      const nx = r.left - cr.left + c.scrollLeft;
      const ny = r.top - cr.top + c.scrollTop;
      if (!shown || prefersReducedMotion()) {
        x.jump(nx);
        y.jump(ny);
        w.jump(r.width);
        h.jump(r.height);
      } else {
        x.set(nx);
        y.set(ny);
        w.set(r.width);
        h.set(r.height);
      }
      setShown(true);
    },
    [container, shown, x, y, w, h],
  );

  const bind = {
    onPointerMove: (e: PointerEvent) => {
      if (e.pointerType === "touch") return;
      const el = (e.target as Element).closest(selector);
      if (el && el !== at.current && container.current?.contains(el) && !el.matches("[aria-disabled='true'], :disabled")) moveTo(el);
    },
    onPointerLeave: () => moveTo(null),
  };

  const highlight = (
    <m.span
      aria-hidden
      className="hover-hl"
      style={{ x, y, width: w, height: h, opacity: shown ? 1 : 0 }}
    />
  );
  return { bind, highlight, moveTo };
}
