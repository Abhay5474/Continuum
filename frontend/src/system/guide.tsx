import { useCallback, useEffect, useLayoutEffect, useState } from "react";
import type { ReactNode } from "react";
import { createPortal } from "react-dom";
import { toneInk, toneWash, type Tone } from "./hub";

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
function useTargetBox(selector: string | undefined, step: number): Box | null {
  const [box, setBox] = useState<Box | null>(null);

  const measure = useCallback(() => {
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
    // Measure after the smooth scroll has had time to land, then keep up with
    // anything that moves it afterwards.
    const t = setTimeout(measure, 380);
    window.addEventListener("resize", measure);
    window.addEventListener("scroll", measure, true);
    return () => {
      clearTimeout(t);
      window.removeEventListener("resize", measure);
      window.removeEventListener("scroll", measure, true);
    };
  }, [selector, step, measure]);

  return box;
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
        title={`How ${guide.title} works`}
        style={{ height: "var(--h-md)", borderRadius: "var(--r-md)" }}
        className="relative inline-flex shrink-0 items-center gap-1.5 border border-card-edge bg-card px-2.5 text-[12.5px] font-medium text-slate-300 transition-colors duration-150 hover:border-slate-500/60 hover:text-slate-100"
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
      {open && (
        <GuidePanel
          guide={guide}
          tone={tone}
          onClose={() => {
            markSeen(guideKey);
            setSeen(true);
            setOpen(false);
          }}
        />
      )}
    </>
  );
}

/* ------------------------------------------------------------------ *
 * The panel
 * ------------------------------------------------------------------ */

function GuidePanel({
  guide,
  tone,
  onClose,
}: {
  guide: Guide;
  tone: Tone;
  onClose: () => void;
}) {
  const [i, setI] = useState(0);
  const step = guide.steps[i];
  const box = useTargetBox(step?.target, i);
  const last = i === guide.steps.length - 1;

  // Escape closes, and the arrows step — a walkthrough you cannot drive from
  // the keyboard is a walkthrough you have to keep reaching for the mouse in.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") onClose();
      if (e.key === "ArrowRight" && !last) setI((n) => n + 1);
      if (e.key === "ArrowLeft" && i > 0) setI((n) => n - 1);
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [i, last, onClose]);

  return createPortal(
    <div className="fixed inset-0 z-[60]" role="dialog" aria-modal="true" aria-label={`${guide.title} guide`}>
      {/* The dimming is four rectangles around the target rather than one box
          with a cut-out: a real hole needs either an SVG mask or mix-blend, and
          both of those pick fights with the theme's own backgrounds. */}
      {box ? (
        <>
          <Shade style={{ top: 0, left: 0, right: 0, height: Math.max(0, box.top) }} onClose={onClose} />
          <Shade style={{ top: box.top + box.height, left: 0, right: 0, bottom: 0 }} onClose={onClose} />
          <Shade style={{ top: box.top, left: 0, width: Math.max(0, box.left), height: box.height }} onClose={onClose} />
          <Shade style={{ top: box.top, left: box.left + box.width, right: 0, height: box.height }} onClose={onClose} />
          <span
            aria-hidden
            className="pointer-events-none fixed transition-all duration-300 ease-out"
            style={{
              top: box.top,
              left: box.left,
              width: box.width,
              height: box.height,
              borderRadius: "var(--r-lg)",
              boxShadow: `0 0 0 2px ${toneInk(tone)}, 0 0 0 6px ${toneWash(tone)}`,
            }}
          />
        </>
      ) : (
        <Shade style={{ inset: 0 }} onClose={onClose} />
      )}

      {/* Bottom-centre, so it never covers the thing it is pointing at — a
          panel pinned to one side hides half the targets on a wide page. */}
      <div className="pointer-events-none fixed inset-x-0 bottom-0 flex justify-center p-4">
        <div
          className="pointer-events-auto w-full max-w-xl border p-4"
          style={{
            borderRadius: "var(--r-xl)",
            borderColor: "rgb(var(--card-edge))",
            background: "rgb(var(--card))",
            boxShadow: "0 10px 15px -3px rgba(0,0,0,.2), 0 24px 48px -12px rgba(0,0,0,.4)",
          }}
        >
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
              className="shrink-0 rounded-[var(--r-md)] p-1 text-slate-500 transition-colors hover:bg-slate-500/10 hover:text-slate-200"
            >
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round">
                <path d="M4 4l8 8M12 4l-8 8" />
              </svg>
            </button>
          </div>

          <div className="mt-4 flex items-center gap-3">
            {/* Dots, not a bar: the count is small enough to show exactly, and
                a clickable dot lets someone jump back to the step they half
                read rather than clicking Back four times. */}
            <div className="flex items-center gap-1.5">
              {guide.steps.map((s, n) => (
                <button
                  key={n}
                  onClick={() => setI(n)}
                  aria-label={`Step ${n + 1}: ${s.title}`}
                  aria-current={n === i}
                  className="h-1.5 rounded-full transition-all duration-200"
                  style={{
                    width: n === i ? 18 : 6,
                    background: n === i ? toneInk(tone) : "rgb(var(--card-edge))",
                  }}
                />
              ))}
            </div>
            <div className="ml-auto flex items-center gap-2">
              {i > 0 && (
                <button
                  onClick={() => setI(i - 1)}
                  style={{ height: "var(--h-md)", borderRadius: "var(--r-md)" }}
                  className="inline-flex items-center px-3 text-[12.5px] font-medium text-slate-400 transition-colors hover:bg-slate-500/10 hover:text-slate-100"
                >
                  Back
                </button>
              )}
              <button
                onClick={() => (last ? onClose() : setI(i + 1))}
                style={{
                  height: "var(--h-md)",
                  borderRadius: "var(--r-md)",
                  background: "var(--accent-strong)",
                  color: "var(--accent-on)",
                }}
                className="inline-flex items-center px-3.5 text-[12.5px] font-medium transition-[filter] duration-150 hover:brightness-110"
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

function Shade({ style, onClose }: { style: React.CSSProperties; onClose: () => void }) {
  return (
    <div
      onClick={onClose}
      className="fixed bg-black/55 transition-opacity duration-200"
      style={style}
      aria-hidden
    />
  );
}
