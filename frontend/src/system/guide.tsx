import { useCallback, useEffect, useLayoutEffect, useRef, useState } from "react";
import type { ReactNode } from "react";
import { createPortal } from "react-dom";
import { toneInk, toneWash, type Tone } from "./hub";
import {
  motion,
  prefersReducedMotion,
  useLiquidIndicator,
  usePresence,
  useSpring,
  type Spring,
} from "./physics";

/**
 * The feature guide.
 *
 * <p>Every screen in this console configures something with a real cost — money,
 * latency, or an answer being rewritten before a customer reads it. The pages
 * explain themselves in prose, but prose is read least by the people who most
 * need it: someone who has just arrived and does not yet know which of the four
 * controls is the one that matters.
 *
 * <p>So this is a walkthrough, not a help article. Each step can name a real
 * element on the page by selector; when it does, the guide scrolls that element
 * into view and cuts a hole in the dimming overlay around it. You are looking at
 * the actual control while being told what it does, which is the only reliable
 * way to connect the two.
 *
 * <p><b>Why an overlay rather than a docs link.</b> A link takes you off the
 * screen you are trying to understand, and you come back having to find the
 * thing again. The guide keeps the page underneath and the step beside it.
 *
 * <p>Steps with no {@code target} are just prose — used for the opening "what
 * this is" and the closing "what it costs", where there is nothing to point at.
 */

export type GuideStep = {
  title: string;
  body: ReactNode;
  /**
   * A CSS selector for the element this step is about. Prefer a
   * {@code [data-guide="…"]} attribute over a class: class names change when
   * someone restyles a page, and a guide that silently stops highlighting is
   * worse than one that was never written.
   */
  target?: string;
  /** Shown when the step describes a cost, a risk, or an irreversible action. */
  caution?: string;
};

export type Guide = {
  title: string;
  /** One sentence. What the feature is, in the words a newcomer would use. */
  summary: string;
  /** What you should be able to do after reading it. */
  steps: GuideStep[];
};

const SEEN_KEY = "continuum.guides.seen";

function seenSet(): Set<string> {
  try {
    return new Set(JSON.parse(localStorage.getItem(SEEN_KEY) ?? "[]"));
  } catch {
    return new Set();
  }
}

function markSeen(key: string) {
  const s = seenSet();
  s.add(key);
  try {
    localStorage.setItem(SEEN_KEY, JSON.stringify([...s]));
  } catch {
    /* private mode — the guide still works, it just re-offers itself */
  }
}

export function hasSeenGuide(key: string) {
  return seenSet().has(key);
}

/* ------------------------------------------------------------------ *
 * The spotlight
 * ------------------------------------------------------------------ */

type Box = { top: number; left: number; width: number; height: number };

/**
 * Tracks a target element's position through scrolling and resizing.
 *
 * <p>Measured from the viewport rather than the document, because the overlay is
 * fixed: converting to document coordinates and back is one more place for the
 * hole and the element to drift apart.
 */
function useTargetBox(
  selector: string | undefined,
  step: number
): { box: Box | null; follow: boolean } {
  const [box, setBox] = useState<Box | null>(null);
  // Whether the latest change came from the page moving under the target
  // (track it exactly) or from the walkthrough moving to a new one (travel).
  const [follow, setFollow] = useState(false);

  const measure = useCallback((isFollow = false) => {
    setFollow(isFollow);
    if (!selector) {
      setBox(null);
      return;
    }
    const el = document.querySelector(selector);
    if (!el) {
      setBox(null);
      return;
    }
    const r = el.getBoundingClientRect();
    // A generous pad: a ring drawn tight against a control reads as a focus
    // state, which is a different thing and already means something here.
    const pad = 8;
    setBox({
      top: r.top - pad,
      left: r.left - pad,
      width: r.width + pad * 2,
      height: r.height + pad * 2,
    });
  }, [selector]);

  useLayoutEffect(() => {
    if (!selector) {
      setBox(null);
      return;
    }
    const el = document.querySelector(selector);
    el?.scrollIntoView({ behavior: "smooth", block: "center" });
    // Measured at once, so the spotlight sets off toward the new target while
    // the page is still scrolling to it; the scroll then re-targets it.
    measure(false);
    const onMove = () => measure(true);
    window.addEventListener("resize", onMove);
    window.addEventListener("scroll", onMove, true);
    return () => {
      window.removeEventListener("resize", onMove);
      window.removeEventListener("scroll", onMove, true);
    };
  }, [selector, step, measure]);

  return { box, follow };
}

/* ------------------------------------------------------------------ *
 * The launcher
 * ------------------------------------------------------------------ */

/** The button. Carries a dot until the guide has been read once. */
export function GuideButton({
  guide,
  guideKey,
  tone = "accent",
}: {
  guide: Guide;
  guideKey: string;
  tone?: Tone;
}) {
  const [open, setOpen] = useState(false);
  const [seen, setSeen] = useState(true);

  // Read on mount rather than during render: localStorage is not available
  // during server rendering and this must not be the reason a page cannot boot.
  useEffect(() => setSeen(hasSeenGuide(guideKey)), [guideKey]);

  return (
    <>
      <button
        onClick={() => setOpen(true)}
        data-tip={`How ${guide.title} works`}
        style={{ height: "var(--h-md)", borderRadius: "var(--r-md)" }}
        className="relative inline-flex shrink-0 items-center gap-1.5 border border-card-edge bg-card px-2.5 text-[12.5px] font-medium text-slate-300 hover:border-slate-500/60 hover:text-slate-100"
      >
        <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor"
             strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <circle cx="8" cy="8" r="6.3" />
          <path d="M6.2 6.2a1.9 1.9 0 1 1 2.6 1.8c-.5.2-.8.6-.8 1.1v.3" />
          <path d="M8 11.9v.1" />
        </svg>
        Guide
        {!seen && (
          <span
            aria-hidden
            className="absolute -right-1 -top-1 h-2 w-2 rounded-full"
            style={{ background: toneInk(tone) }}
          />
        )}
      </button>
      <GuidePanel
        open={open}
        guide={guide}
        tone={tone}
        onClose={() => {
          markSeen(guideKey);
          setSeen(true);
          setOpen(false);
        }}
      />
    </>
  );
}

/* ------------------------------------------------------------------ *
 * The panel
 * ------------------------------------------------------------------ */

function GuidePanel({
  open,
  guide,
  tone,
  onClose,
}: {
  open: boolean;
  guide: Guide;
  tone: Tone;
  onClose: () => void;
}) {
  const { mounted, progress } = usePresence(open, motion.modal, "fade");
  if (!mounted) return null;
  return <GuideScene open={open} progress={progress} guide={guide} tone={tone} onClose={onClose} />;
}

/**
 * The walkthrough, as one physical scene.
 *
 * <ul>
 *   <li><b>The spotlight is an object.</b> Four springs hold the hole's
 *       position and size; the dimming around it is painted from the same
 *       springs, so the shade and the ring can never disagree. Stepping to a
 *       new target, the hole travels there. A step with no target closes the
 *       hole to a point rather than removing it.</li>
 *   <li><b>The rest of the page loses focus.</b> The shade dims and blurs what
 *       is not being explained; the target stays sharp. Both rise with the
 *       scene's own progress, so they arrive together.</li>
 *   <li><b>Steps have direction.</b> Next brings the new step in from the
 *       right, Back from the left — the sequence is spatial, not a cut.</li>
 * </ul>
 */
function GuideScene({
  open,
  progress,
  guide,
  tone,
  onClose,
}: {
  open: boolean;
  progress: Spring;
  guide: Guide;
  tone: Tone;
  onClose: () => void;
}) {
  const [i, setI] = useState(0);
  const [dir, setDir] = useState(0);
  const step = guide.steps[i];
  const { box, follow } = useTargetBox(open ? step?.target : undefined, i);
  const last = i === guide.steps.length - 1;
  const go = (n: number) => {
    setDir(n > i ? 1 : -1);
    setI(n);
  };

  // Escape closes, and the arrows step — a walkthrough you cannot drive from
  // the keyboard is a walkthrough you have to keep reaching for the mouse in.
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      if (e.key === "ArrowRight" && !last) go(i + 1);
      if (e.key === "ArrowLeft" && i > 0) go(i - 1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, i, last, onClose]);

  const sx = useSpring(0, { config: motion.standard, precision: 0.2 });
  const sy = useSpring(0, { config: motion.standard, precision: 0.2 });
  const sw = useSpring(0, { config: motion.standard, precision: 0.2 });
  const sh = useSpring(0, { config: motion.standard, precision: 0.2 });
  const hole = useSpring(0, { config: motion.standard, precision: 0.002 });
  const seeded = useRef(false);

  useLayoutEffect(() => {
    if (!box) {
      hole.set(0);
      return;
    }
    const all = [sx, sy, sw, sh];
    const to = [box.left, box.top, box.width, box.height];
    const moving = all.some((s) => !s.isResting);
    if (!seeded.current || hole.value < 0.02) {
      // No hole on screen yet: open one where the target is.
      all.forEach((s, k) => s.jump(to[k]));
      seeded.current = true;
    } else if (follow && !moving) {
      // The page scrolled under a settled spotlight: track it exactly.
      all.forEach((s, k) => s.jump(to[k]));
    } else {
      all.forEach((s, k) => s.set(to[k]));
    }
    hole.set(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [box]);

  const shades = useRef<(HTMLDivElement | null)[]>([]);
  const ring = useRef<HTMLSpanElement>(null);
  const card = useRef<HTMLDivElement>(null);
  const scene = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const paint = () => {
      const p = Math.max(0, Math.min(1, progress.value));
      const k = Math.max(0, Math.min(1.2, hole.value));
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      const w = Math.max(0, sw.value * k);
      const h = Math.max(0, sh.value * k);
      const left = sx.value + (sw.value - w) / 2;
      const top = sy.value + (sh.value - h) / 2;
      const rects: [number, number, number, number][] = [
        [0, 0, vw, Math.max(0, top)],
        [0, top + h, vw, Math.max(0, vh - top - h)],
        [0, top, Math.max(0, left), h],
        [left + w, top, Math.max(0, vw - left - w), h],
      ];
      shades.current.forEach((el, n) => {
        if (!el) return;
        const [x, y, ww, hh] = rects[n];
        el.style.transform = `translate3d(${x}px,${y}px,0)`;
        el.style.width = `${ww}px`;
        el.style.height = `${hh}px`;
      });
      if (scene.current) scene.current.style.setProperty("--shade", p.toFixed(3));
      if (ring.current) {
        ring.current.style.transform = `translate3d(${left}px,${top}px,0)`;
        ring.current.style.width = `${w}px`;
        ring.current.style.height = `${h}px`;
        ring.current.style.opacity = String(Math.min(1, k) * p);
      }
      if (card.current) {
        const reduced = prefersReducedMotion();
        card.current.style.opacity = String(p);
        card.current.style.transform = reduced
          ? ""
          : `translate3d(0,${((1 - p) * 28).toFixed(2)}px,0) scale(${(0.965 + 0.035 * p).toFixed(4)})`;
      }
    };
    const offs = [progress, hole, sx, sy, sw, sh].map((s) => s.subscribe(paint));
    window.addEventListener("resize", paint);
    return () => {
      offs.forEach((f) => f());
      window.removeEventListener("resize", paint);
    };
  }, [progress, hole, sx, sy, sw, sh]);

  // The active dot is one pill that travels, like every other indicator.
  const dots = useRef<HTMLDivElement>(null);
  const pill = useRef<HTMLSpanElement>(null);
  // Every dot keeps its size; only the pill moves. If the active dot grew
  // instead, the layout would shift under the pill as it measured its target.
  useLiquidIndicator(dots, String(i), (l, r) => {
    if (!pill.current) return;
    pill.current.style.transform = `translate3d(${((l + r) / 2 - 9).toFixed(2)}px,0,0)`;
  });

  return createPortal(
    <div
      ref={scene}
      className={`fixed inset-0 z-[60] ${open ? "" : "pointer-events-none"}`}
      role="dialog"
      aria-modal="true"
      aria-label={`${guide.title} guide`}
    >
      {/* Four shades around the hole rather than one box with a cut-out: a real
          hole needs an SVG mask or a blend mode, and both fight the theme's own
          backgrounds. Blurred as well as dimmed, so what is not being explained
          recedes and the target is the one sharp thing on the page. */}
      {[0, 1, 2, 3].map((n) => (
        <div
          key={n}
          ref={(el) => (shades.current[n] = el)}
          onClick={onClose}
          aria-hidden
          className="fixed left-0 top-0"
          style={{
            background: "rgb(0 0 0 / calc(0.55 * var(--shade, 0)))",
            backdropFilter: "blur(calc(var(--blur-modal) * var(--shade, 0) * 0.5))",
            WebkitBackdropFilter: "blur(calc(var(--blur-modal) * var(--shade, 0) * 0.5))",
            willChange: "transform",
          }}
        />
      ))}
      <span
        ref={ring}
        aria-hidden
        className="pointer-events-none fixed left-0 top-0"
        style={{
          borderRadius: "var(--r-lg)",
          boxShadow: `0 0 0 2px ${toneInk(tone)}, 0 0 0 6px ${toneWash(tone)}, 0 0 28px 2px ${toneWash(tone)}`,
          opacity: 0,
        }}
      />

      {/* Bottom-centre, so it never covers the thing it is pointing at — a
          panel pinned to one side hides half the targets on a wide page. It
          rises from the bottom edge it is docked to. */}
      <div className="pointer-events-none fixed inset-x-0 bottom-0 flex justify-center p-4">
        <div
          ref={card}
          className="pointer-events-auto w-full max-w-xl overflow-hidden border p-4"
          style={{
            borderRadius: "var(--r-xl)",
            borderColor: "rgb(var(--card-edge))",
            background: "rgb(var(--card))",
            boxShadow: "var(--shadow-float)",
            opacity: 0,
            transformOrigin: "50% 100%",
          }}
        >
          <StepBody key={i} dir={dir}>
            <div className="flex items-start gap-3">
              <span
                aria-hidden
                className="grid h-7 w-7 shrink-0 place-items-center rounded-[8px] text-[11px] font-semibold"
                style={{ background: toneWash(tone), color: toneInk(tone) }}
              >
                {i + 1}
              </span>
              <div className="min-w-0 flex-1">
                <div className="flex items-baseline gap-2">
                  <h2 className="text-[13.5px] font-semibold tracking-tight text-slate-100">
                    {step.title}
                  </h2>
                  <span className="readout shrink-0 text-[11px] text-slate-500">
                    {i + 1}/{guide.steps.length}
                  </span>
                </div>
                <div className="mt-1.5 text-[12.5px] leading-relaxed text-slate-400">{step.body}</div>
                {step.caution && (
                  <p
                    className="mt-2.5 border-l-2 pl-2.5 text-[12px] leading-relaxed"
                    style={{ borderColor: "var(--state-warning-ink)", color: "var(--state-warning-ink)" }}
                  >
                    {step.caution}
                  </p>
                )}
              </div>
              <button
                onClick={onClose}
                aria-label="Close the guide"
                className="shrink-0 rounded-[var(--r-md)] p-1 text-slate-500 hover:bg-slate-500/10 hover:text-slate-200"
              >
                <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                  <path d="M4 4l8 8M12 4l-8 8" />
                </svg>
              </button>
            </div>
          </StepBody>

          <div className="mt-4 flex items-center gap-3">
            {/* Dots, not a bar: the count is small enough to show exactly, and
                a clickable dot lets someone jump back to the step they half
                read rather than clicking Back four times. */}
            <div ref={dots} className="relative flex items-center gap-1">
              {guide.steps.map((s, n) => (
                <button
                  key={n}
                  data-indicator-key={String(n)}
                  onClick={() => go(n)}
                  aria-label={`Step ${n + 1}: ${s.title}`}
                  aria-current={n === i}
                  className="grid h-3 w-3 place-items-center"
                >
                  <span className="h-1.5 w-1.5 rounded-full" style={{ background: "rgb(var(--card-edge))" }} />
                </button>
              ))}
              <span
                ref={pill}
                aria-hidden
                className="pointer-events-none absolute left-0 top-1/2 -mt-[3px] h-1.5 w-[18px] rounded-full"
                style={{ background: toneInk(tone) }}
              />
            </div>
            <div className="ml-auto flex items-center gap-2">
              {i > 0 && (
                <button
                  onClick={() => go(i - 1)}
                  style={{ height: "var(--h-md)", borderRadius: "var(--r-md)" }}
                  className="inline-flex items-center px-3 text-[12.5px] font-medium text-slate-400 hover:bg-slate-500/10 hover:text-slate-100"
                >
                  Back
                </button>
              )}
              <button
                onClick={() => (last ? onClose() : go(i + 1))}
                style={{
                  height: "var(--h-md)",
                  borderRadius: "var(--r-md)",
                  background: "var(--accent-strong)",
                  color: "var(--accent-on)",
                }}
                className="press-on-accent inline-flex items-center px-3.5 text-[12.5px] font-medium hover:brightness-110"
              >
                {last ? "Done" : "Next"}
              </button>
            </div>
          </div>
        </div>
      </div>
    </div>,
    document.body
  );
}

/** One step's content, arriving from the side the walkthrough is moving toward. */
function StepBody({ dir, children }: { dir: number; children: ReactNode }) {
  const ref = useRef<HTMLDivElement>(null);
  const p = useSpring(dir === 0 ? 1 : 0, { config: motion.standard, kind: "fade", precision: 0.002 });
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const off = p.subscribe((v) => {
      el.style.opacity = String(Math.max(0, Math.min(1, v)));
      el.style.transform = prefersReducedMotion() ? "" : `translate3d(${(dir * 22 * (1 - v)).toFixed(2)}px,0,0)`;
    });
    p.set(1);
    return off;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);
  return <div ref={ref}>{children}</div>;
}
