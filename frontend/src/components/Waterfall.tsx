import { useEffect, useMemo, useState, type KeyboardEvent, type ReactNode } from "react";
import { duration, type Attempt } from "../system/execution/model";

/**
 * A run as a waterfall: one row per step on one clock, the way a trace viewer
 * draws spans. Length is how long something ran; a thin line before a bar is
 * the time it waited for a worker; a dashed line is a retry's backoff; a
 * hatched bar is an attempt that failed. Steps on the critical path — the chain
 * that decided how long the run took — are drawn darker.
 *
 * HTML rather than a canvas or SVG, so every label is real text: crisp at any
 * zoom, selectable, and read by a screen reader. Rows are a list a keyboard
 * can walk with the arrow keys.
 */
export type WaterfallRow = {
  id: string;
  /** What choosing the row selects in the page; null if it selects nothing. */
  select?: string | null;
  level: 0 | 1;
  code?: string;
  label: string;
  /** The full name, when the label is cut short. */
  title?: string;
  scheduled: number | null;
  attempts: Attempt[];
  /** A moment rather than a span, such as when the evidence was weighed. */
  marker?: number | null;
  critical?: boolean;
  running?: boolean;
  /** An attempt failed and the next is not due yet. */
  retrying?: boolean;
  /** Shown at the right: a result, a value. */
  value?: ReactNode;
  /** Said to a screen reader for the whole row. */
  summary?: string;
};

/** A relation between two rows, drawn as a bracket at the right edge. */
export type WaterfallLink = { id: string; from: string; to: string; title: string; select?: string };

const ROW_H = { 0: 32, 1: 24 } as const;
const STEPS = [10, 20, 50, 100, 200, 250, 500, 1000, 2000, 2500, 5000, 10_000, 15_000, 30_000, 60_000, 120_000, 300_000, 600_000];

/** Re-renders once a second while a run is live, so what is running grows. */
function useClock(live: boolean) {
  const [, tick] = useState(0);
  useEffect(() => {
    if (!live) return;
    const id = setInterval(() => tick((n) => n + 1), 1000);
    return () => clearInterval(id);
  }, [live]);
}

export function Waterfall({
  rows,
  span,
  live = false,
  madeAt = Date.now(),
  selected,
  onSelect,
  links = [],
  ariaLabel,
}: {
  rows: WaterfallRow[];
  /** Milliseconds the run took (or has taken, at madeAt). */
  span: number;
  live?: boolean;
  madeAt?: number;
  /** Ids whose rows show as selected. */
  selected: Set<string>;
  onSelect: (id: string | null) => void;
  links?: WaterfallLink[];
  ariaLabel: string;
}) {
  useClock(live);
  const now = live ? span + Math.max(0, Date.now() - madeAt) : span;
  // A live run keeps a little room ahead of now, so bars grow into it.
  const scale = live ? Math.max(now * 1.15, 1) : Math.max(span, 1);
  const pct = (t: number) => `${Math.max(0, Math.min(100, (t / scale) * 100))}%`;
  const step = STEPS.find((s) => scale / s <= 8) ?? 1_200_000;
  const ticks = useMemo(() => {
    const out: number[] = [];
    for (let t = 0; t <= scale + 0.5; t += step) out.push(t);
    return out;
  }, [scale, step]);
  // The grid lines, as one background shared by every row's bar cell.
  const grid = ticks
    .slice(1)
    .map((t) => {
      const x = (t / scale) * 100;
      return `linear-gradient(to right, transparent calc(${x}% - 0.5px), rgb(var(--card-rule)) calc(${x}% - 0.5px), rgb(var(--card-rule)) calc(${x}% + 0.5px), transparent calc(${x}% + 0.5px))`;
    })
    .join(", ");

  // Where each row sits, for the brackets between rows.
  const tops = useMemo(() => {
    const m = new Map<string, number>();
    let y = 0;
    for (const r of rows) {
      m.set(r.id, y + ROW_H[r.level] / 2);
      y += ROW_H[r.level];
    }
    return { m, height: y };
  }, [rows]);

  const onKey = (e: KeyboardEvent<HTMLDivElement>) => {
    if (e.key !== "ArrowDown" && e.key !== "ArrowUp") return;
    const items = [...e.currentTarget.querySelectorAll<HTMLElement>("[data-row]")];
    const at = items.indexOf(document.activeElement as HTMLElement);
    const next = items[Math.max(0, Math.min(items.length - 1, at + (e.key === "ArrowDown" ? 1 : -1)))];
    if (next) {
      e.preventDefault();
      next.focus();
    }
  };

  const cols = links.length ? "wf-cols wf-cols-links" : "wf-cols";
  return (
    <div className="wf" role="group" aria-label={ariaLabel}>
      {/* the clock */}
      <div className={`${cols} wf-axis`} aria-hidden>
        <span />
        <span className="relative h-4">
          {ticks
            // A tick right under "now" would print over it.
            .filter((t) => !live || Math.abs(t - now) / scale > 0.06)
            .map((t) => (
              <span key={t} className="readout absolute top-0 -translate-x-1/2 text-[10px] text-slate-500" style={{ left: pct(t) }}>
                {t === 0 ? "0" : duration(t)}
              </span>
            ))}
          {live && (
            <span className="readout absolute top-0 -translate-x-1/2 text-[10px] font-semibold" style={{ left: pct(now), color: "var(--state-active-ink)" }}>
              now
            </span>
          )}
        </span>
        <span />
        {links.length > 0 && <span />}
      </div>

      <div className="relative" role="list" onKeyDown={onKey}>
        {rows.map((r) => {
          const on = selected.has(r.id) || (r.select != null && selected.has(r.select));
          const first = r.attempts[0];
          const sched = r.scheduled ?? first?.start ?? null;
          const selectable = r.select !== null;
          return (
            <div
              key={r.id}
              role="listitem"
              data-row
              tabIndex={0}
              aria-current={on || undefined}
              aria-label={r.summary ?? r.title ?? r.label}
              onClick={() => selectable && onSelect(on ? null : r.select ?? r.id)}
              onKeyDown={(e) => {
                if ((e.key === "Enter" || e.key === " ") && selectable) {
                  e.preventDefault();
                  onSelect(on ? null : r.select ?? r.id);
                }
              }}
              className={`${cols} wf-row ${on ? "wf-on" : ""} ${selectable ? "cursor-pointer" : ""}`}
              style={{ height: ROW_H[r.level] }}
            >
              <span className={`flex min-w-0 items-baseline gap-1.5 ${r.level === 1 ? "pl-6" : "pl-2"}`} title={r.title}>
                {r.code && <span className="readout shrink-0 text-[11px] font-semibold text-slate-200">{r.code}</span>}
                <span className={`truncate ${r.level === 1 ? "text-[11px] text-slate-500" : "text-[12px] text-slate-300"}`}>{r.label}</span>
              </span>

              <span className="relative h-full" style={{ backgroundImage: grid }}>
                {/* waiting for a worker */}
                {sched != null && first && first.start - sched > 0.5 && (
                  <span className="wf-wait" style={{ left: pct(sched), width: `calc(${pct(first.start)} - ${pct(sched)})` }}
                        title={`queued ${duration(first.start - sched)}`} />
                )}
                {/* scheduled, and no worker has taken it yet */}
                {sched != null && !first && live && (
                  <span className="wf-wait" style={{ left: pct(sched), width: `calc(${pct(now)} - ${pct(sched)})` }}
                        title={`waiting ${duration(now - sched)} for a worker`} />
                )}
                {r.attempts.map((a, i) => {
                  const end = a.end ?? now;
                  const next = r.attempts[i + 1];
                  const due = next ? Math.min(a.retryAt ?? next.start, next.start) : null;
                  return (
                    <span key={a.n}>
                      <span
                        className={`wf-bar ${r.level === 1 ? "wf-bar-thin" : ""} ${a.outcome === "failed" ? "wf-failed" : a.outcome === "running" ? "wf-running" : r.critical ? "wf-hot" : ""}`}
                        style={{ left: pct(a.start), width: `max(2px, calc(${pct(end)} - ${pct(a.start)}))` }}
                        title={`attempt ${a.n}: ${a.outcome === "running" ? "running" : `${a.outcome === "ok" ? "ran" : "failed after"} ${duration(end - a.start)}`}${a.error ? `\n${a.error}` : ""}`}
                      />
                      {next && a.end != null && due != null && due > a.end && (
                        <span className="wf-backoff" style={{ left: pct(a.end), width: `calc(${pct(due)} - ${pct(a.end)})` }}
                              title={`backoff ${duration(due - a.end)} before attempt ${next.n}`} />
                      )}
                      {next && due != null && next.start - due > 0.5 && (
                        <span className="wf-wait" style={{ left: pct(due), width: `calc(${pct(next.start)} - ${pct(due)})` }}
                              title={`queued ${duration(next.start - due)}`} />
                      )}
                    </span>
                  );
                })}
                {(() => {
                  // Waiting to try again: the backoff so far, and any wait for a
                  // worker once it was due.
                  const last = r.attempts[r.attempts.length - 1];
                  if (!r.retrying || !last || last.end == null) return null;
                  const due = last.retryAt ?? now;
                  const backoffEnd = Math.min(due, now);
                  return (
                    <>
                      {backoffEnd > last.end && (
                        <span className="wf-backoff" style={{ left: pct(last.end), width: `calc(${pct(backoffEnd)} - ${pct(last.end)})` }}
                              title={`backing off until ${duration(due)}`} />
                      )}
                      {now > due && (
                        <span className="wf-wait" style={{ left: pct(due), width: `calc(${pct(now)} - ${pct(due)})` }} title="due, waiting for a worker" />
                      )}
                    </>
                  );
                })()}
                {r.marker != null && <span className="wf-marker" style={{ left: pct(r.marker) }} title={`at ${duration(r.marker)}`} />}
              </span>

              <span className="flex min-w-0 items-center justify-end gap-2 pr-2 text-right">{r.value}</span>
              {links.length > 0 && <span />}
            </div>
          );
        })}

        {/* relations between rows: a bracket at the right edge */}
        {links.length > 0 && (
          <svg className="wf-links" width="32" height={tops.height} aria-hidden>
            {links.map((l, i) => {
              const y1 = tops.m.get(l.from), y2 = tops.m.get(l.to);
              if (y1 == null || y2 == null) return null;
              const x = 8 + (i % 3) * 8;
              const on = (l.select && selected.has(l.select)) || selected.has(l.id);
              return (
                <g key={l.id} className="cursor-pointer" style={{ pointerEvents: "auto" }} onClick={() => onSelect(on ? null : l.select ?? l.id)}>
                  <title>{l.title}</title>
                  <path d={`M 0 ${y1} H ${x - 3} Q ${x} ${y1} ${x} ${y1 + 3} V ${y2 - 3} Q ${x} ${y2} ${x - 3} ${y2} H 0`}
                        fill="none" stroke="var(--state-critical-ink)" strokeWidth={on ? 2 : 1.25} strokeLinecap="round" />
                  <path d={`M 0 ${y1} H ${x} V ${y2} H 0`} fill="none" stroke="transparent" strokeWidth={10} />
                </g>
              );
            })}
          </svg>
        )}
      </div>
    </div>
  );
}

/** What the waterfall's marks mean. */
export function WaterfallLegend({ links = false }: { links?: boolean }) {
  const item = (cls: string, label: string) => (
    <span className="inline-flex items-center gap-1.5">
      <span className={`wf-swatch ${cls}`} aria-hidden />
      {label}
    </span>
  );
  return (
    <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[10.5px] text-slate-500">
      {item("", "ran")}
      {item("wf-hot", "critical path")}
      {item("wf-wait-swatch", "queued for a worker")}
      {item("wf-failed", "failed attempt")}
      {item("wf-backoff-swatch", "retry backoff")}
      {links && item("wf-link-swatch", "contradiction")}
    </div>
  );
}
