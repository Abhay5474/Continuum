import { useCallback, useEffect, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import { createPortal } from "react-dom";
import { Micro } from "./primitives";

/**
 * Live demos.
 *
 * <p>Every feature page has a "Demo" button beside its guide. It opens a sheet
 * that runs the feature on sample input and shows the input and the output as
 * pictures, with the stages in between lighting up as they happen. A reader who
 * has never sent the feature a request can see what it takes and what it gives
 * back, in about three seconds.
 *
 * <p>Two kinds, and the sheet always says which:
 * <ul>
 *   <li><b>Live</b> — the sample goes to this server and the answer is the
 *       engine's own (the firewall, compressor, loop detector, scheduler, router,
 *       cache similarity and context transformers). Nothing is stored.</li>
 *   <li><b>Sample</b> — features whose real input is live traffic (a provider
 *       failing, a saga compensating) run the feature's documented rules over
 *       sample data in the browser, so the demo cannot disturb real state.</li>
 * </ul>
 */

export type DemoSample<I> = { label: string; input: I };

export type DemoSpec<I = any, O = any> = {
  title: string;
  live: boolean;
  samples: DemoSample<I>[];
  /** What happens between input and output, one short label per stage. */
  steps: string[];
  run: (input: I) => Promise<O>;
  Input: (p: { input: I }) => ReactNode;
  Output: (p: { input: I; output: O }) => ReactNode;
};

/** Routes that have a demo. Kept here so the button can render before the demos load. */
export const DEMO_PATHS = new Set([
  "/gateway", "/router", "/autopilot", "/counterfactual", "/admission", "/scheduling", "/cost-limits",
  "/cascade", "/workflows", "/workflows/console", "/saga", "/replay", "/dag", "/pipelines", "/specialists",
  "/guard", "/compression", "/context", "/mmu", "/memory", "/cache", "/quality", "/confidence", "/breaker",
  "/loops", "/provenance", "/chaos", "/ai-chaos", "/godmode", "/models",
]);

export const hasDemo = (path: string) => DEMO_PATHS.has(path.replace(/\/+$/, "") || "/");

/**
 * One-shot continuity: the dialog grows out of the control that opened it.
 * Its transform-origin is put on that control's centre before the first
 * paint, and the .grow-from animation scales it up from there.
 */
export function useGrowFrom(ref: RefObject<HTMLElement>, from: Element | null) {
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const o = from?.getBoundingClientRect();
    if (!o || (o.width === 0 && o.height === 0)) {
      el.classList.add("pop-in");
      return;
    }
    const r = el.getBoundingClientRect();
    el.style.transformOrigin = `${o.left + o.width / 2 - r.left}px ${o.top + o.height / 2 - r.top}px`;
    el.classList.add("grow-from");
  }, []);
}

const reducedMotion = () =>
  typeof window !== "undefined" && window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

export function DemoButton({ path }: { path: string }) {
  const [spec, setSpec] = useState<DemoSpec | null>(null);
  const [loading, setLoading] = useState(false);
  const opener = useRef<HTMLButtonElement>(null);

  const open = async () => {
    setLoading(true);
    try {
      const { DEMOS } = await import("../demos");
      setSpec(DEMOS[path.replace(/\/+$/, "")] ?? null);
    } finally {
      setLoading(false);
    }
  };

  return (
    <>
      <button
        ref={opener}
        onClick={open}
        data-tip="Run this feature on sample data"
        aria-haspopup="dialog"
        style={{ height: "var(--h-md)" }}
        className="demo-button relative inline-flex shrink-0 items-center gap-1.5 rounded-full px-3 text-[12.5px] font-semibold text-white"
      >
        <svg width="11" height="11" viewBox="0 0 12 12" aria-hidden className={loading ? "animate-pulse" : ""}>
          <path d="M3 1.8v8.4a.6.6 0 0 0 .9.5l6.8-4.2a.6.6 0 0 0 0-1L3.9 1.3a.6.6 0 0 0-.9.5Z" fill="currentColor" />
        </svg>
        Demo
      </button>
      {spec && (
        <DemoSheet
          spec={spec}
          from={opener.current}
          onClose={() => {
            setSpec(null);
            opener.current?.focus();
          }}
        />
      )}
    </>
  );
}

type Phase = "input" | "running" | "done" | "error";

export function DemoSheet({ spec, onClose, from = null }: { spec: DemoSpec; onClose: () => void; from?: Element | null }) {
  const [sample, setSample] = useState(0);
  const [phase, setPhase] = useState<Phase>("input");
  const [step, setStep] = useState(-1);
  const [output, setOutput] = useState<unknown>(null);
  const [error, setError] = useState<string | null>(null);
  const [runId, setRunId] = useState(0);
  const close = useRef<HTMLButtonElement>(null);
  const sheet = useRef<HTMLElement>(null);
  const input = spec.samples[sample].input;
  useGrowFrom(sheet, from);

  useEffect(() => {
    close.current?.focus();
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    const overflow = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    return () => {
      document.removeEventListener("keydown", onKey);
      document.body.style.overflow = overflow;
    };
  }, [onClose]);

  // One run: show the input, light each stage in turn while the work happens,
  // then reveal the output. The stages are paced so they can be read; the work
  // itself is not delayed, and a slow answer simply holds the last stage lit.
  useEffect(() => {
    let alive = true;
    const pace = reducedMotion() ? 0 : 420;
    const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));
    setPhase("input");
    setStep(-1);
    setOutput(null);
    setError(null);
    (async () => {
      await wait(pace ? 450 : 0);
      if (!alive) return;
      setPhase("running");
      const work = spec.run(input).then(
        (o) => ({ ok: true as const, o }),
        (e) => ({ ok: false as const, e }),
      );
      for (let i = 0; i < spec.steps.length; i++) {
        if (!alive) return;
        setStep(i);
        await wait(pace);
      }
      const r = await work;
      if (!alive) return;
      setStep(spec.steps.length);
      if (r.ok) {
        setOutput(r.o);
        setPhase("done");
      } else {
        setError((r.e as Error)?.message ?? "The demo could not run.");
        setPhase("error");
      }
    })();
    return () => {
      alive = false;
    };
  }, [spec, sample, runId]);

  const again = useCallback(() => setRunId((n) => n + 1), []);

  return createPortal(
    <div className="fixed inset-0 z-[70] flex items-end justify-center sm:items-center sm:p-4">
      <div
        aria-hidden
        onClick={onClose}
        className="demo-scrim absolute inset-0"
        style={{ background: "rgb(var(--scrim) / 0.55)", backdropFilter: "blur(var(--blur-modal, 10px))" }}
      />
      <section
        ref={sheet}
        role="dialog"
        aria-modal="true"
        aria-labelledby="demo-title"
        data-glass
        className="glass-strong relative flex max-h-[92vh] w-full max-w-4xl flex-col overflow-hidden"
        style={{ borderRadius: "var(--r-glass, 22px)" }}
      >
        <header className="flex items-start gap-3 border-b border-edge/70 px-5 py-4">
          <div className="min-w-0 flex-1">
            <Micro>Live demo</Micro>
            <h2 id="demo-title" className="mt-1 text-lg font-semibold tracking-tight text-slate-100">
              {spec.title}
            </h2>
            <div className="mt-1.5">
              {spec.live ? (
                <span className="demo-badge demo-badge-live">
                  <span className="demo-live-dot" aria-hidden /> Live · computed by your server
                </span>
              ) : (
                <span className="demo-badge">Sample data · the feature's rules, run in your browser</span>
              )}
            </div>
          </div>
          <button
            ref={close}
            onClick={onClose}
            aria-label="Close demo"
            className="grid h-9 w-9 shrink-0 place-items-center rounded-full text-slate-400 hover:bg-slate-500/10 hover:text-slate-100"
          >
            <svg width="14" height="14" viewBox="0 0 14 14" aria-hidden stroke="currentColor" strokeWidth="1.6" strokeLinecap="round">
              <path d="M3 3l8 8M11 3l-8 8" />
            </svg>
          </button>
        </header>

        {spec.samples.length > 1 && (
          <div role="radiogroup" aria-label="Sample" className="flex flex-wrap gap-1.5 px-5 pt-4">
            {spec.samples.map((s, i) => (
              <button
                key={s.label}
                role="radio"
                aria-checked={i === sample}
                onClick={() => (i === sample ? again() : setSample(i))}
                className={`rounded-full border px-3 py-1 text-[12px] font-medium transition-colors ${
                  i === sample
                    ? "border-transparent bg-[color:var(--accent-strong)] text-white"
                    : "border-edge text-slate-300 hover:border-slate-500/60 hover:text-slate-100"
                }`}
              >
                {s.label}
              </button>
            ))}
          </div>
        )}

        <div className="grid min-h-0 flex-1 gap-4 overflow-y-auto p-5 lg:grid-cols-[minmax(0,1fr)_auto_minmax(0,1.25fr)]">
          <Pane label="Input" key={`in-${sample}-${runId}`}>
            {spec.Input({ input })}
          </Pane>

          <Stages steps={spec.steps} step={step} phase={phase} />

          <Pane label="Output" aria-live="polite">
            {phase === "done" ? (
              <div className="rise-in" key={`out-${sample}-${runId}`}>
                {spec.Output({ input, output })}
              </div>
            ) : phase === "error" ? (
              <p role="alert" className="text-sm text-rose-400">
                {error}
              </p>
            ) : (
              <div className="space-y-2.5" aria-busy="true">
                {[0.9, 0.7, 0.8, 0.5].map((w, i) => (
                  <div key={i} className="demo-shimmer h-3 rounded-full" style={{ width: `${w * 100}%` }} />
                ))}
              </div>
            )}
          </Pane>
        </div>

        <footer className="flex items-center justify-between gap-3 border-t border-edge/70 px-5 py-3">
          <span className="text-xs text-slate-500">
            {spec.live ? "Nothing from the demo is stored." : "Nothing in your account is changed."}
          </span>
          <button
            onClick={again}
            disabled={phase === "running" || phase === "input"}
            className="rounded-full border border-edge px-4 py-1.5 text-sm font-medium text-slate-200 hover:border-slate-500/60 disabled:opacity-50"
          >
            Run again
          </button>
        </footer>
      </section>
    </div>,
    document.body,
  );
}

function Pane({ label, children, ...rest }: { label: string; children: ReactNode; "aria-live"?: "polite" }) {
  return (
    <div className="rise-in min-w-0 rounded-2xl border border-edge/70 bg-slate-500/[0.04] p-4" {...rest}>
      <div className="micro mb-3">{label}</div>
      {children}
    </div>
  );
}

/**
 * The stages, as beads on a line. Vertical beside the panes on a wide screen,
 * horizontal between them on a narrow one; each bead fills as its stage runs.
 */
function Stages({ steps, step, phase }: { steps: string[]; step: number; phase: Phase }) {
  return (
    <ol className="flex flex-row flex-wrap items-center justify-center gap-x-2 gap-y-2 lg:w-40 lg:flex-col lg:flex-nowrap lg:items-stretch lg:justify-center">
      {steps.map((s, i) => {
        const state = phase === "error" && i >= step ? "fail" : i < step || phase === "done" ? "done" : i === step ? "now" : "todo";
        return (
          <li key={s} className="demo-stage flex items-center gap-2" data-state={state}>
            <span className="demo-bead" aria-hidden>
              {state === "done" && (
                <svg width="9" height="9" viewBox="0 0 10 10" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round">
                  <path className="draw-in" d="M2 5.2l2 2 4-4.4" />
                </svg>
              )}
            </span>
            <span className="text-[11.5px] leading-tight">{s}</span>
            <span className="sr-only">{state === "done" ? "done" : state === "now" ? "running" : "waiting"}</span>
          </li>
        );
      })}
    </ol>
  );
}
