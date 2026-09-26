import type { CSSProperties, ReactNode } from "react";
import { Pill, toneInk, toneWash, type Tone } from "../system/hub";

/** Pieces the demos share. Each is a picture of data, never of plumbing. */

export const wait = (ms: number) => new Promise((r) => setTimeout(r, ms));

/** A labelled value, big. */
export function Big({ label, value, tone, sub }: { label: string; value: ReactNode; tone?: Tone; sub?: ReactNode }) {
  return (
    <div className="min-w-0">
      <div className="micro">{label}</div>
      <div className="readout mt-1 text-2xl font-semibold tracking-tight" style={tone ? { color: toneInk(tone) } : undefined}>
        {value}
      </div>
      {sub && <div className="mt-0.5 text-xs text-slate-500">{sub}</div>}
    </div>
  );
}

/** A text sample in a quiet box, the way a prompt looks in a request. */
export function Quote({ children, mono = false }: { children: ReactNode; mono?: boolean }) {
  return (
    <div
      className={`whitespace-pre-wrap break-words rounded-xl border border-edge/70 bg-slate-500/[0.06] px-3 py-2.5 text-[12.5px] leading-relaxed text-slate-300 ${
        mono ? "font-mono text-[11.5px]" : ""
      }`}
    >
      {children}
    </div>
  );
}

/** A horizontal bar with its value beside it; grows in when it mounts. */
export function HBar({
  label,
  value,
  max,
  tone = "info",
  note,
  marker,
  delay = 0,
}: {
  label: ReactNode;
  value: number;
  max: number;
  tone?: Tone;
  note?: ReactNode;
  /** A threshold drawn across the track, as a fraction of max. */
  marker?: number;
  delay?: number;
}) {
  const f = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
  return (
    <div className="min-w-0">
      <div className="flex items-baseline justify-between gap-3">
        <span className="min-w-0 truncate text-[12px] text-slate-200">{label}</span>
        {note !== undefined && <span className="readout shrink-0 text-[11px] text-slate-400">{note}</span>}
      </div>
      <div className="relative mt-1.5 h-2 rounded-full bg-edge">
        <div
          className="demo-grow h-full rounded-full"
          style={{ width: `${Math.max(f > 0 ? 2 : 0, f * 100)}%`, background: toneInk(tone), animationDelay: `${delay}ms` }}
        />
        {marker !== undefined && (
          <span
            aria-hidden
            className="absolute -top-1 h-4 w-0.5 rounded-full bg-slate-200"
            style={{ left: `calc(${Math.max(0, Math.min(1, marker)) * 100}% - 1px)` }}
          />
        )}
      </div>
    </div>
  );
}

/** A row of squares, one per item, coloured by outcome. Pops in one by one. */
export function Squares({
  items,
  size = 18,
  step = 35,
}: {
  items: { tone: Tone; label: string; mark?: string }[];
  size?: number;
  step?: number;
}) {
  return (
    <ul className="flex flex-wrap gap-1.5" aria-label="Outcomes">
      {items.map((it, i) => (
        <li
          key={i}
          title={it.label}
          aria-label={it.label}
          className="demo-pop grid place-items-center rounded-[5px] text-[9px] font-bold"
          style={{ width: size, height: size, background: toneInk(it.tone), color: "#fff", animationDelay: `${i * step}ms` }}
        >
          {it.mark}
        </li>
      ))}
    </ul>
  );
}

/** A legend for Squares. */
export function Key({ items }: { items: { tone: Tone; label: string }[] }) {
  return (
    <div className="mt-2.5 flex flex-wrap gap-x-3 gap-y-1 text-[11px] text-slate-400">
      {items.map((k) => (
        <span key={k.label} className="inline-flex items-center gap-1.5">
          <span className="h-2.5 w-2.5 rounded-[3px]" style={{ background: toneInk(k.tone) }} aria-hidden />
          {k.label}
        </span>
      ))}
    </div>
  );
}

/** Steps as beads on a line, each with a state. */
export function Beads({
  steps,
  highlight,
}: {
  steps: { label: string; tone: Tone; note?: string }[];
  highlight?: number;
}) {
  return (
    <ol className="flex flex-wrap items-start gap-y-3">
      {steps.map((s, i) => (
        <li key={i} className="flex items-start">
          <div className="demo-pop flex w-[74px] flex-col items-center text-center" style={{ animationDelay: `${i * 80}ms` }}>
            <span
              className="grid h-7 w-7 place-items-center rounded-full text-[11px] font-bold"
              style={{
                background: toneInk(s.tone),
                color: "#fff",
                boxShadow: highlight === i ? `0 0 0 3px ${toneWash(s.tone)}, 0 0 0 5px ${toneInk(s.tone)}` : undefined,
              }}
            >
              {i + 1}
            </span>
            <span className="mt-1 text-[11px] leading-tight text-slate-200">{s.label}</span>
            {s.note && <span className="mt-0.5 text-[10px] leading-tight text-slate-500">{s.note}</span>}
          </div>
          {i < steps.length - 1 && <span aria-hidden className="mt-3.5 h-px w-3 bg-edge" />}
        </li>
      ))}
    </ol>
  );
}

/** One verdict, prominent. */
export function Verdict({ tone, children, sub }: { tone: Tone; children: ReactNode; sub?: ReactNode }) {
  return (
    <div className="demo-pop flex flex-wrap items-center gap-2">
      <span
        className="rounded-full px-3 py-1 text-[13px] font-semibold"
        style={{ background: toneWash(tone), color: toneInk(tone) }}
      >
        {children}
      </span>
      {sub && <span className="text-xs text-slate-400">{sub}</span>}
    </div>
  );
}

/** A value on a 0–1 scale with labelled ends and a marker for a threshold. */
export function Scale({
  value,
  threshold,
  left,
  right,
  tone,
}: {
  value: number;
  threshold?: number;
  left: string;
  right: string;
  tone: Tone;
}) {
  const pos = (v: number) => `${Math.max(0, Math.min(1, v)) * 100}%`;
  return (
    <div>
      <div className="relative h-2.5 rounded-full" style={{ background: "linear-gradient(90deg, var(--wash-ok), var(--wash-warn), var(--wash-bad))" }}>
        {threshold !== undefined && (
          <span aria-hidden className="absolute -top-1 h-[18px] w-0.5 rounded-full bg-slate-300" style={{ left: pos(threshold) }} />
        )}
        <span
          aria-hidden
          className="demo-slide absolute top-1/2 h-4 w-4 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white shadow"
          style={{ left: pos(value), background: toneInk(tone), "--from": "0%" } as CSSProperties}
        />
      </div>
      <div className="mt-1.5 flex justify-between text-[10.5px] text-slate-500">
        <span>{left}</span>
        <span>{right}</span>
      </div>
    </div>
  );
}

export { Pill };
export const pct = (f: number, d = 0) => `${(f * 100).toFixed(d)}%`;
export const usd = (v: number) => (v < 0.01 ? `$${v.toFixed(4)}` : `$${v.toFixed(2)}`);
