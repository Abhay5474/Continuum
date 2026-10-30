import { useRef, type ReactNode } from "react";
import { m } from "motion/react";
import { duration, type ExecutionModel, type Work } from "../system/execution/model";
import { useHoverHighlight } from "../system/interaction/HoverHighlight";
import { transitions } from "../system/physics";
import type { Finding } from "../system/execution/findings";
import { Micro } from "../system/primitives";

/*
 * The parts every view of a run shares: where its time went, how one step
 * executed, and the findings that say what to look at first.
 */

/**
 * Where the time went, along the chain of work that decided how long the run
 * took: running, waiting for a worker, backing off before a retry, and the
 * engine deciding what to do next. The four add up to the whole run.
 */
export function TimeBudget({ model }: { model: ExecutionModel }) {
  const b = model.breakdown;
  const parts = [
    { label: "work", v: b.work, color: "rgb(var(--bar-hot))" },
    { label: "queued for a worker", v: b.queued, color: "rgb(var(--bar-wait))" },
    { label: "retry backoff", v: b.backoff, color: "var(--state-warning-ink)" },
    { label: "engine deciding", v: b.orchestration, color: "rgb(var(--bar-work))" },
  ].filter((x) => x.v >= 1);
  const total = Math.max(1, model.span);
  const name = (id: string) => {
    const w = model.work.find((x) => x.id === id);
    if (!w) return id;
    return w.lane === 0 ? w.title.toLowerCase() : `${model.lanes[w.lane]?.code} ${w.title.toLowerCase()}`;
  };
  return (
    <div className="mt-2">
      <div className="flex flex-wrap items-baseline gap-x-2 gap-y-1 text-[11.5px] text-slate-400">
        <span className="readout font-semibold text-slate-200">{duration(model.span)}</span>
        <span>{model.live ? "so far," : "end to end,"} along the critical path:</span>
        <span className="text-slate-500">{model.critical.map(name).join(" → ")}</span>
      </div>
      <div className="mt-2 flex h-1.5 overflow-hidden rounded-full" style={{ background: "rgb(var(--card-rule))" }} aria-hidden>
        {parts.map((x) => (
          <span key={x.label} style={{ width: `${(x.v / total) * 100}%`, background: x.color }} />
        ))}
      </div>
      <div className="mt-1.5 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-slate-500">
        {parts.map((x) => (
          <span key={x.label} className="inline-flex items-center gap-1.5">
            <span className="h-2 w-2 rounded-[2px]" style={{ background: x.color }} />
            {x.label}
            <span className="readout text-slate-300">{duration(x.v)}</span>
          </span>
        ))}
      </div>
    </div>
  );
}

/** How a step executed: when it was scheduled, every attempt, what it waited for. */
export function ExecutionFacts({ work, model }: { work: Work; model: ExecutionModel }) {
  const lane = model.lanes[work.lane];
  return (
    <div className="space-y-2">
      <Micro>Execution</Micro>
      {lane && lane.index > 0 && <Row k="Claim" v={`${lane.code}`} />}
      <Row k="Scheduled" v={`+${duration(work.scheduled)}`} />
      {work.attempts.map((a, i) => {
        const prior = work.attempts[i - 1];
        const ready = i === 0 ? work.scheduled : Math.min(prior.retryAt ?? a.start, a.start);
        return (
          <div key={a.n} className="space-y-1 border-b border-edge/40 pb-1.5">
            <div className="flex items-baseline justify-between">
              <span className="micro">Attempt {a.n}</span>
              <span className="readout text-xs" style={{ color: a.outcome === "failed" ? "var(--state-warning-ink)" : a.outcome === "running" ? "var(--state-active-ink)" : undefined }}>
                {a.outcome === "running" ? "running" : `${a.outcome === "ok" ? "ran" : "failed after"} ${duration((a.end ?? a.start) - a.start)}`}
              </span>
            </div>
            <div className="text-[11px] text-slate-500">
              {a.start - ready > 1 ? `waited ${duration(a.start - ready)} for a worker` : "started at once"}
              {i > 0 && prior.end != null && ready > prior.end ? ` · after a ${duration(ready - prior.end)} backoff` : ""}
            </div>
            {a.error && <div className="break-words font-mono text-[11px] text-slate-400">{a.error}</div>}
          </div>
        );
      })}
      {work.critical && <div className="text-[11px] text-slate-500">On the critical path: the run could not finish sooner than this step.</div>}
    </div>
  );
}

/**
 * The findings: what to look at first, each one a way into the part of the run
 * it is about. Overview first, details on demand.
 */
export function Findings({
  findings,
  onSelect,
  hint = "Choose a finding to show it in the graph and the timeline.",
  morph = false,
}: {
  findings: Finding[];
  onSelect: (id: string) => void;
  /** Where choosing a finding shows it. */
  hint?: string;
  /**
   * Each finding with a target is a shared surface ({@code finding-<target>}):
   * the panel that shows the target grows out of the finding chosen, and
   * shrinks back into it on return.
   */
  morph?: boolean;
}) {
  const ink: Record<Finding["tone"], string> = {
    ok: "var(--state-healthy-ink)",
    warn: "var(--state-warning-ink)",
    bad: "var(--state-critical-ink)",
    info: "var(--state-active-ink)",
  };
  const list = useRef<HTMLOListElement>(null);
  const { bind, highlight } = useHoverHighlight(list, "[data-hl]");
  return (
    <div>
      <Micro>Findings</Micro>
      <ol ref={list} className="relative -mx-2 mt-2" {...bind}>
        {highlight}
        {findings.map((f) => {
          const body = (
            <>
              <span className="mt-[5px] h-2 w-2 shrink-0 rounded-[2px]" style={{ background: ink[f.tone] }} aria-hidden />
              <span className="min-w-0">
                <span className="block text-[12.5px] font-medium leading-snug text-slate-200">{f.title}</span>
                {f.detail && <span className="mt-0.5 block text-[11.5px] leading-relaxed text-slate-400">{f.detail}</span>}
              </span>
            </>
          );
          return (
            <li key={f.id} className="relative z-[1]">
              {f.target ? (
                <m.div layoutId={morph ? `finding-${f.target}` : undefined} transition={transitions.standard} style={{ borderRadius: 10 }}>
                  {/* Corrected for the scale its surface travels through, so
                      the words never stretch on the way back from the panel. */}
                  <m.button type="button" data-hl onClick={() => onSelect(f.target!)} layout={morph ? "position" : undefined}
                          transition={transitions.standard}
                          className="group flex w-full items-start gap-2.5 rounded-[10px] px-2 py-2.5 text-left">
                    {body}
                    <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.7" strokeLinecap="round" strokeLinejoin="round"
                         aria-hidden className="ml-auto mt-[3px] shrink-0 text-slate-500 opacity-0 transition-[opacity,transform] duration-300 group-hover:translate-x-0.5 group-hover:opacity-100">
                      <path d="M6 3.5 10.5 8 6 12.5" />
                    </svg>
                  </m.button>
                </m.div>
              ) : (
                <div className="flex items-start gap-2.5 px-2 py-2.5">{body}</div>
              )}
            </li>
          );
        })}
      </ol>
      {findings.some((f) => f.target) && (
        <p className="mt-1 text-[10.5px] text-slate-500">{hint}</p>
      )}
    </div>
  );
}


function Row({ k, v }: { k: string; v: ReactNode }) {
  return (
    <div className="flex items-baseline justify-between gap-3 border-b border-edge/40 pb-1.5">
      <span className="micro">{k}</span>
      <span className="readout text-right text-xs text-slate-200">{v}</span>
    </div>
  );
}
