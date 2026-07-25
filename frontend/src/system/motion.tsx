import {
  useEffect,
  useRef,
  useState,
  type CSSProperties,
  type ReactNode,
} from "react";

/**
 * Motion system.
 *
 * Everything here is CSS-transform driven, uses passive listeners and
 * requestAnimationFrame, and adds no dependencies — so it cannot stall the
 * console or the polling loops that feed it. Every effect also has a
 * reduced-motion path.
 *
 * The console is an instrument, so motion is used where it aids reading:
 * revealing a section as it arrives, settling a changing number, giving depth
 * under the cursor, and morphing between states. Effects that would fight
 * legibility (heavy parallax on data, full-screen cinematic wipes between
 * panels) are deliberately not here.
 */

const reduced = () =>
  typeof window !== "undefined" &&
  window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

/* ------------------------------------------------------------------ *
 * Scroll-triggered reveal
 * ------------------------------------------------------------------ */

/** Fires once when the element first enters the viewport. */
export function useInView<T extends HTMLElement>(rootMargin = "-10% 0px") {
  const ref = useRef<T>(null);
  const [seen, setSeen] = useState(false);
  useEffect(() => {
    if (reduced()) {
      setSeen(true);
      return;
    }
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      ([e]) => {
        if (e.isIntersecting) {
          setSeen(true);
          io.disconnect();
        }
      },
      { rootMargin }
    );
    io.observe(el);
    return () => io.disconnect();
  }, [rootMargin]);
  return { ref, seen };
}

/** A section that rises into place as it enters the viewport. */
export function Reveal({
  children,
  delay = 0,
  className = "",
}: {
  children: ReactNode;
  delay?: number;
  className?: string;
}) {
  const { ref, seen } = useInView<HTMLDivElement>();
  return (
    <div
      ref={ref}
      className={className}
      style={{
        opacity: seen ? 1 : 0,
        transform: seen ? "none" : "translateY(14px)",
        transition: `opacity 520ms cubic-bezier(0.22,1,0.36,1) ${delay}ms, transform 520ms cubic-bezier(0.22,1,0.36,1) ${delay}ms`,
      }}
    >
      {children}
    </div>
  );
}

/* ------------------------------------------------------------------ *
 * Cursor-reactive depth
 * ------------------------------------------------------------------ */

/**
 * A soft light that follows the cursor across a surface, giving the panel
 * depth without moving any data. Pointer position is written to CSS variables
 * so the paint stays on the compositor.
 */
export function Spotlight({
  children,
  className = "",
  strength = 0.09,
}: {
  children: ReactNode;
  className?: string;
  strength?: number;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const raf = useRef(0);

  useEffect(() => {
    const el = ref.current;
    if (!el || reduced()) return;
    const onMove = (e: PointerEvent) => {
      cancelAnimationFrame(raf.current);
      raf.current = requestAnimationFrame(() => {
        const r = el.getBoundingClientRect();
        el.style.setProperty("--mx", `${e.clientX - r.left}px`);
        el.style.setProperty("--my", `${e.clientY - r.top}px`);
        el.style.setProperty("--ma", String(strength));
      });
    };
    const onLeave = () => el.style.setProperty("--ma", "0");
    el.addEventListener("pointermove", onMove, { passive: true });
    el.addEventListener("pointerleave", onLeave, { passive: true });
    return () => {
      cancelAnimationFrame(raf.current);
      el.removeEventListener("pointermove", onMove);
      el.removeEventListener("pointerleave", onLeave);
    };
  }, [strength]);

  return (
    <div ref={ref} className={`spotlight relative ${className}`}>
      {children}
    </div>
  );
}

/**
 * Subtle magnetism: the element leans toward the cursor and springs back.
 * Capped at a few pixels so a control never drifts away from where you clicked.
 */
export function Magnetic({
  children,
  radius = 70,
  pull = 5,
  className = "",
}: {
  children: ReactNode;
  radius?: number;
  pull?: number;
  className?: string;
}) {
  const ref = useRef<HTMLSpanElement>(null);
  const raf = useRef(0);

  useEffect(() => {
    const el = ref.current;
    if (!el || reduced()) return;
    const onMove = (e: PointerEvent) => {
      cancelAnimationFrame(raf.current);
      raf.current = requestAnimationFrame(() => {
        const r = el.getBoundingClientRect();
        const cx = r.left + r.width / 2;
        const cy = r.top + r.height / 2;
        const dx = e.clientX - cx;
        const dy = e.clientY - cy;
        const d = Math.hypot(dx, dy);
        if (d > radius + Math.max(r.width, r.height) / 2) {
          el.style.transform = "translate3d(0,0,0)";
          return;
        }
        const k = Math.max(0, 1 - d / (radius * 2));
        el.style.transform = `translate3d(${(dx * k * pull) / radius}px, ${(dy * k * pull) / radius}px, 0)`;
      });
    };
    const reset = () => {
      el.style.transform = "translate3d(0,0,0)";
    };
    window.addEventListener("pointermove", onMove, { passive: true });
    window.addEventListener("pointerleave", reset, { passive: true });
    return () => {
      cancelAnimationFrame(raf.current);
      window.removeEventListener("pointermove", onMove);
      window.removeEventListener("pointerleave", reset);
    };
  }, [radius, pull]);

  return (
    <span
      ref={ref}
      className={`inline-block will-change-transform ${className}`}
      style={{ transition: "transform 380ms cubic-bezier(0.22,1,0.36,1)" }}
    >
      {children}
    </span>
  );
}

/* ------------------------------------------------------------------ *
 * Kinetic numerals
 * ------------------------------------------------------------------ */

/**
 * Counts to a new value instead of snapping. On an instrument this is not
 * decoration: the roll shows that a figure moved and in which direction, which
 * a hard swap hides.
 */
export function CountUp({
  value,
  decimals = 0,
  duration = 600,
  format,
}: {
  value: number;
  decimals?: number;
  duration?: number;
  format?: (n: number) => string;
}) {
  const [shown, setShown] = useState(value);
  const from = useRef(value);
  const raf = useRef(0);

  useEffect(() => {
    if (reduced() || from.current === value) {
      from.current = value;
      setShown(value);
      return;
    }
    const start = performance.now();
    const a = from.current;
    const b = value;
    const tick = (t: number) => {
      const p = Math.min(1, (t - start) / duration);
      // ease-out cubic: fast settle, no overshoot on a readout
      const e = 1 - Math.pow(1 - p, 3);
      setShown(a + (b - a) * e);
      if (p < 1) raf.current = requestAnimationFrame(tick);
      else from.current = b;
    };
    raf.current = requestAnimationFrame(tick);
    return () => cancelAnimationFrame(raf.current);
  }, [value, duration]);

  const n = decimals ? Number(shown.toFixed(decimals)) : Math.round(shown);
  return <>{format ? format(n) : n.toLocaleString()}</>;
}

/* ------------------------------------------------------------------ *
 * State morphing
 * ------------------------------------------------------------------ */

/**
 * Cross-fades and lifts between states — used when a tab or selection swaps a
 * whole panel, so the change reads as one surface transforming rather than a
 * hard cut.
 */
export function Morph({
  k,
  children,
  className = "",
}: {
  /** Changing this key re-runs the transition. */
  k: string | number;
  children: ReactNode;
  className?: string;
}) {
  const [state, setState] = useState({ k, children });
  const [entering, setEntering] = useState(false);

  useEffect(() => {
    if (state.k === k) {
      setState({ k, children });
      return;
    }
    setEntering(true);
    setState({ k, children });
    const t = setTimeout(() => setEntering(false), 30);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [k, children]);

  return (
    <div
      className={className}
      style={{
        opacity: entering ? 0 : 1,
        transform: entering ? "translateY(6px) scale(0.995)" : "none",
        transition: reduced()
          ? undefined
          : "opacity 300ms cubic-bezier(0.22,1,0.36,1), transform 300ms cubic-bezier(0.22,1,0.36,1)",
      }}
    >
      {state.children}
    </div>
  );
}

/* ------------------------------------------------------------------ *
 * Scroll-driven depth
 * ------------------------------------------------------------------ */

/**
 * Moves a decorative layer at a fraction of scroll speed. Applied only to
 * background fields — never to a chart or a table, where displacement would
 * make values harder to compare.
 */
export function ParallaxLayer({
  speed = 0.15,
  className = "",
  style,
}: {
  speed?: number;
  className?: string;
  style?: CSSProperties;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const raf = useRef(0);

  useEffect(() => {
    const el = ref.current;
    if (!el || reduced()) return;
    const onScroll = () => {
      cancelAnimationFrame(raf.current);
      raf.current = requestAnimationFrame(() => {
        el.style.transform = `translate3d(0, ${window.scrollY * speed}px, 0)`;
      });
    };
    window.addEventListener("scroll", onScroll, { passive: true });
    return () => {
      cancelAnimationFrame(raf.current);
      window.removeEventListener("scroll", onScroll);
    };
  }, [speed]);

  return <div ref={ref} className={`pointer-events-none ${className}`} style={style} />;
}
