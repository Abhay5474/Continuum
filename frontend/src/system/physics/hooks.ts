import { useEffect, useLayoutEffect, useRef, useState, type RefObject } from "react";
import {
  prefersReducedMotion,
  rubberband,
  Spring,
  VelocityTracker,
  type SpringConfig,
  type SpringOptions,
} from "./spring";
import { motion } from "./tokens";

/**
 * React bindings for the spring.
 *
 * <p>The rule every hook here keeps: <b>React renders structure, springs paint
 * frames.</b> A spring never calls setState per frame; it writes transforms and
 * opacity straight onto elements it was given. A panel opening costs two
 * renders — mount and unmount — whatever its frame count, so motion stays
 * smooth while SSE events and polls are re-rendering the page around it.
 */

/** A spring that lives as long as the component. */
export function useSpring(initial: number, opts: SpringOptions = {}): Spring {
  const ref = useRef<Spring | null>(null);
  if (!ref.current) ref.current = new Spring(initial, opts);
  useEffect(() => () => ref.current?.stop(), []);
  return ref.current;
}

/**
 * Paints an element from a spring on every frame, without re-rendering.
 * {@code paint} runs immediately on attach, so the first frame is right.
 */
export function useSpringPaint<T extends HTMLElement | SVGElement>(
  spring: Spring,
  ref: RefObject<T>,
  paint: (el: T, value: number, velocity: number) => void,
  deps: unknown[] = []
) {
  const paintRef = useRef(paint);
  paintRef.current = paint;
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    return spring.subscribe((v, vel) => paintRef.current(el, v, vel));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [spring, ref, ...deps]);
}

/**
 * Presence: keeps something mounted while it animates out.
 *
 * <p>Progress runs 0 → 1 on open and back to 0 on close, and the element is
 * unmounted only when the spring actually comes to rest at 0 — not after a
 * guessed timeout that drifts from the real animation. Toggle it mid-flight and
 * the same spring reverses from where it is, with the velocity it has.
 */
export function usePresence(
  open: boolean,
  config: SpringConfig = motion.modal,
  kind: "move" | "fade" = "move"
) {
  const [mounted, setMounted] = useState(open);
  const openRef = useRef(open);
  openRef.current = open;
  const spring = useSpring(open ? 1 : 0, {
    config,
    kind,
    precision: 0.0005,
    onRest: (p) => {
      if (p === 0 && !openRef.current) setMounted(false);
    },
  });

  useLayoutEffect(() => {
    if (open) setMounted(true);
    spring.set(open ? 1 : 0, { config });
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  return { mounted, progress: spring };
}

/** Whether the person has asked for less motion, as React state. */
export function useReducedMotion() {
  const [reduced, setReduced] = useState(prefersReducedMotion);
  useEffect(() => {
    const m = window.matchMedia?.("(prefers-reduced-motion: reduce)");
    if (!m) return;
    const on = (e: MediaQueryListEvent) => setReduced(e.matches);
    m.addEventListener?.("change", on);
    return () => m.removeEventListener?.("change", on);
  }, []);
  return reduced;
}

/* ------------------------------------------------------------------ *
 * Drag
 * ------------------------------------------------------------------ */

export type DragState = {
  /** Offset from where the drag began, along the axis. */
  offset: number;
  /** Units per second, from the last ~80ms of movement. */
  velocity: number;
  event: PointerEvent;
};

/**
 * A one-axis drag with real release velocity.
 *
 * <p>Nothing happens until the pointer has moved a few pixels along the axis,
 * so a click is still a click and a vertical scroll in a panel is not mistaken
 * for a horizontal dismiss. Pointer capture keeps the gesture alive when the
 * finger outruns the element.
 */
export function useDrag<T extends HTMLElement>(
  ref: RefObject<T>,
  handlers: {
    axis: "x" | "y";
    onStart?: () => void;
    onMove: (s: DragState) => void;
    onEnd: (s: DragState) => void;
    /** Drags that begin on these are left to the element (buttons, links, fields). */
    ignore?: string;
    enabled?: boolean;
  }
) {
  const h = useRef(handlers);
  h.current = handlers;

  useEffect(() => {
    const el = ref.current;
    if (!el || handlers.enabled === false) return;
    const tracker = new VelocityTracker();
    let start = 0;
    let cross = 0;
    let id: number | null = null;
    let live = false;

    const pos = (e: PointerEvent) => (h.current.axis === "x" ? e.clientX : e.clientY);
    const other = (e: PointerEvent) => (h.current.axis === "x" ? e.clientY : e.clientX);

    const down = (e: PointerEvent) => {
      if (e.button !== 0) return;
      const skip = h.current.ignore ?? "button, a, input, select, textarea, [role=switch], [data-no-drag]";
      if ((e.target as Element).closest(skip)) return;
      id = e.pointerId;
      start = pos(e);
      cross = other(e);
      live = false;
      tracker.reset();
      tracker.add(start, e.timeStamp);
    };
    const move = (e: PointerEvent) => {
      if (e.pointerId !== id) return;
      const d = pos(e) - start;
      if (!live) {
        // Decide the axis on the first few pixels, then commit.
        if (Math.abs(other(e) - cross) > 8 && Math.abs(other(e) - cross) > Math.abs(d)) {
          id = null;
          return;
        }
        if (Math.abs(d) < 5) return;
        live = true;
        el.setPointerCapture(e.pointerId);
        h.current.onStart?.();
      }
      tracker.add(pos(e), e.timeStamp);
      h.current.onMove({ offset: d, velocity: tracker.velocity(e.timeStamp), event: e });
    };
    const up = (e: PointerEvent) => {
      if (e.pointerId !== id) return;
      id = null;
      if (!live) return;
      live = false;
      h.current.onEnd({
        offset: pos(e) - start,
        velocity: tracker.velocity(e.timeStamp),
        event: e,
      });
    };

    el.addEventListener("pointerdown", down);
    el.addEventListener("pointermove", move);
    el.addEventListener("pointerup", up);
    el.addEventListener("pointercancel", up);
    return () => {
      el.removeEventListener("pointerdown", down);
      el.removeEventListener("pointermove", move);
      el.removeEventListener("pointerup", up);
      el.removeEventListener("pointercancel", up);
    };
  }, [ref, handlers.enabled]);
}

export { rubberband };
