import { useCallback, useEffect, useRef, useState } from "react";
import { useLocation, useNavigate } from "react-router-dom";
import { portal, type Advice, type AdviceReport } from "../api";
import { visibleInterval } from "../system/poll";
import { Primary, SidePanel, toneInk, toneWash, GLYPHS, type Tone } from "../system/hub";

/**
 * The recommendation box.
 *
 * <p>Every feature checks its own inputs; none can see the others. The server's
 * advisor reads the whole configuration against the account's own traffic —
 * a latency ceiling no model has met, a verification engine bypassing the
 * cache the account also turned on, a chaos experiment left armed — and this
 * is where that reaches the user: a new finding pops up once, in the corner,
 * with its fix and a link to the page it is on; everything stays one click
 * away behind the badge until it is fixed or dismissed.
 *
 * <p>It re-checks after any setting is saved (the API client announces
 * writes), on navigation, and every half minute while the tab is visible.
 */

const DISMISSED_KEY = "continuum.advice.dismissed";
const TONE: Record<Advice["severity"], Tone> = { error: "bad", warn: "warn", info: "info" };
const WORD: Record<Advice["severity"], string> = { error: "Needs fixing", warn: "Worth a look", info: "Tip" };

function readSet(storage: () => Storage, key: string): Set<string> {
  try {
    return new Set(JSON.parse(storage().getItem(key) ?? "[]"));
  } catch {
    return new Set();
  }
}

function writeSet(storage: () => Storage, key: string, v: Set<string>) {
  try {
    storage().setItem(key, JSON.stringify([...v]));
  } catch {
    /* storage unavailable: the box still works, it just forgets */
  }
}

export default function Advisor() {
  const [report, setReport] = useState<AdviceReport | null>(null);
  const [dismissed, setDismissed] = useState<Set<string>>(() => readSet(() => localStorage, DISMISSED_KEY));
  const [open, setOpen] = useState(false);
  const [popup, setPopup] = useState<Advice | null>(null);
  // What has already popped up in this tab, so a finding is announced once,
  // not on every poll.
  const announced = useRef<Set<string>>(readSet(() => sessionStorage, "continuum.advice.announced"));
  const location = useLocation();
  const navigate = useNavigate();

  const load = useCallback(async () => {
    try {
      const r = await portal.advice();
      setReport(r);
    } catch {
      /* advice is a convenience; a failed check shows nothing rather than an error */
    }
  }, []);

  useEffect(() => visibleInterval(() => void load(), 30000), [load]);
  useEffect(() => {
    void load();
  }, [load, location.pathname]);
  useEffect(() => {
    let t: number | undefined;
    const onChange = () => {
      window.clearTimeout(t);
      t = window.setTimeout(() => void load(), 500);
    };
    window.addEventListener("continuum:config-changed", onChange);
    return () => {
      window.removeEventListener("continuum:config-changed", onChange);
      window.clearTimeout(t);
    };
  }, [load]);

  const active = (report?.advice ?? []).filter((a) => !dismissed.has(a.id));
  const hidden = (report?.advice ?? []).filter((a) => dismissed.has(a.id));

  // Pop up the most serious finding not yet announced in this tab.
  useEffect(() => {
    if (open) return;
    const next = active.find((a) => !announced.current.has(a.id));
    if (!next) return;
    announced.current.add(next.id);
    writeSet(() => sessionStorage, "continuum.advice.announced", announced.current);
    setPopup(next);
    // Errors stay until acted on; the rest step aside after a while.
    if (next.severity !== "error") {
      const t = window.setTimeout(() => setPopup((p) => (p?.id === next.id ? null : p)), 14000);
      return () => window.clearTimeout(t);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [report, dismissed, open]);

  // A popped finding that has since been fixed leaves by itself.
  useEffect(() => {
    if (popup && report && !report.advice.some((a) => a.id === popup.id)) setPopup(null);
  }, [report, popup]);

  const dismiss = (id: string) => {
    const next = new Set(dismissed);
    next.add(id);
    setDismissed(next);
    writeSet(() => localStorage, DISMISSED_KEY, next);
    if (popup?.id === id) setPopup(null);
  };
  const restore = (id: string) => {
    const next = new Set(dismissed);
    next.delete(id);
    setDismissed(next);
    writeSet(() => localStorage, DISMISSED_KEY, next);
  };
  const go = (a: Advice) => {
    setPopup(null);
    setOpen(false);
    if (a.route) navigate(a.route);
  };

  const worst = active[0]?.severity;
  const tone: Tone = worst ? TONE[worst] : "ok";

  return (
    <>
      {popup && !open && (
        <div
          role="status"
          aria-live="polite"
          className="glass-strong rise-in fixed bottom-20 right-4 z-40 w-[calc(100%-2rem)] max-w-[23rem] p-4"
          style={{ borderRadius: "var(--r-glass)" }}
        >
          <Item a={popup} onGo={() => go(popup)} onDismiss={() => dismiss(popup.id)} compact />
          {active.length > 1 && (
            <button
              onClick={() => {
                setPopup(null);
                setOpen(true);
              }}
              className="mt-3 text-[12px] font-medium text-slate-400 hover:text-slate-200"
            >
              {active.length - 1} more recommendation{active.length > 2 ? "s" : ""} →
            </button>
          )}
        </div>
      )}

      <button
        onClick={() => {
          setPopup(null);
          setOpen(true);
        }}
        aria-label={active.length ? `${active.length} recommendation${active.length > 1 ? "s" : ""}` : "Recommendations: nothing to fix"}
        className="press glass-strong fixed bottom-4 right-4 z-40 flex h-11 items-center gap-2 rounded-full pl-3 pr-4 text-[13px] font-medium text-slate-200"
      >
        <span className="grid h-6 w-6 place-items-center rounded-full" style={{ background: toneWash(tone), color: toneInk(tone) }} aria-hidden>
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"
               strokeLinecap="round" strokeLinejoin="round">
            {GLYPHS[active.length ? "alert" : "check"]}
          </svg>
        </span>
        {active.length ? `${active.length} to review` : "All good"}
      </button>

      <SidePanel
        open={open}
        title="Recommendations"
        subtitle={
          report
            ? `${report.checks} checks across your settings${report.measuredRequests ? `, measured against your last ${report.measuredRequests} requests` : ""}`
            : "Checking…"
        }
        onClose={() => setOpen(false)}
      >
        {active.length === 0 ? (
          <p className="text-[13px] leading-relaxed text-slate-400">
            Nothing to fix. Your settings work together, and none asks for something your traffic cannot do.
          </p>
        ) : (
          <ul className="space-y-3">
            {active.map((a) => (
              <li key={a.id} className="rounded-xl p-3" style={{ boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }}>
                <Item a={a} onGo={() => go(a)} onDismiss={() => dismiss(a.id)} />
              </li>
            ))}
          </ul>
        )}
        {hidden.length > 0 && (
          <details className="mt-5 text-[12px] text-slate-500">
            <summary className="cursor-pointer select-none">Dismissed ({hidden.length})</summary>
            <ul className="mt-2 space-y-1.5">
              {hidden.map((a) => (
                <li key={a.id} className="flex items-center justify-between gap-3">
                  <span className="min-w-0 truncate">{a.title}</span>
                  <button onClick={() => restore(a.id)} className="shrink-0 text-slate-400 hover:text-slate-200">
                    Show again
                  </button>
                </li>
              ))}
            </ul>
          </details>
        )}
      </SidePanel>
    </>
  );
}

function Item({ a, onGo, onDismiss, compact = false }: { a: Advice; onGo: () => void; onDismiss: () => void; compact?: boolean }) {
  const tone = TONE[a.severity];
  return (
    <div>
      <div className="flex items-center gap-2 text-[11px] font-medium" style={{ color: toneInk(tone) }}>
        <span className="h-1.5 w-1.5 rounded-full" style={{ background: toneInk(tone) }} aria-hidden />
        {WORD[a.severity]} · {a.area}
      </div>
      <div className="mt-1 text-[13.5px] font-semibold leading-snug text-slate-100">{a.title}</div>
      <p className={`mt-1 text-[12.5px] leading-relaxed text-slate-400 ${compact ? "line-clamp-3" : ""}`}>{a.message}</p>
      <p className="mt-1.5 text-[12.5px] leading-relaxed text-slate-300">
        <span className="font-medium">Fix: </span>
        {a.fix}
      </p>
      <div className="mt-2.5 flex items-center gap-3">
        {a.route && (
          <Primary onClick={onGo}>Open the setting</Primary>
        )}
        <button onClick={onDismiss} className="text-[12px] text-slate-500 hover:text-slate-300">
          Dismiss
        </button>
      </div>
    </div>
  );
}
