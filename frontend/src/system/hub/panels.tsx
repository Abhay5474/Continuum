import type { ReactNode } from "react";
import { Tone, hueAt, toneInk } from "./tone";
import { Pill } from "./workspace";

/* ------------------------------------------------------------------ *
 * Panels with something in them
 * ------------------------------------------------------------------ */

export type Slice = { key: string; label: string; value: number; tone?: Tone; note?: string };

/**
 * A ring with the headline in its hole, and the parts named beside it.
 *
 * <p>The ring answers "roughly what proportion", which is all a ring is good
 * for; the bars beside it answer "which one and how much", which a ring is bad
 * at. Together they are the allocation panel from the reference — and neither
 * half is decorative, because removing either loses a real question.
 */
export function Allocation({
  slices,
  centre,
  centreLabel,
  unit,
}: {
  slices: Slice[];
  centre: string;
  centreLabel: string;
  unit?: string;
}) {
  const total = slices.reduce((n, s) => n + s.value, 0) || 1;
  const R = 46;
  const C = 2 * Math.PI * R;
  let at = 0;
  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-5">
      <div className="relative shrink-0" style={{ width: 132, height: 132 }}>
        <svg viewBox="0 0 120 120" width="132" height="132" aria-hidden>
          <circle cx="60" cy="60" r={R} fill="none" stroke="rgb(var(--edge))" strokeWidth="13" />
          {slices.map((s, i) => {
            const frac = s.value / total;
            const dash = `${Math.max(0, frac * C - 2)} ${C}`;
            const el = (
              <circle
                key={s.key}
                cx="60"
                cy="60"
                r={R}
                fill="none"
                stroke={toneInk(s.tone ?? hueAt(i))}
                strokeWidth="13"
                strokeLinecap="round"
                strokeDasharray={dash}
                strokeDashoffset={-at * C}
                transform="rotate(-90 60 60)"
                className="transition-all duration-500 ease-out"
              />
            );
            at += frac;
            return el;
          })}
        </svg>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <span className="readout text-[19px] leading-none tracking-tight text-slate-100">
            {centre}
          </span>
          <span className="mt-1 text-[10px] text-slate-500">{centreLabel}</span>
        </div>
      </div>

      <ul className="min-w-[190px] flex-1 space-y-2.5">
        {slices.map((s, i) => {
          const colour = toneInk(s.tone ?? hueAt(i));
          return (
            <li key={s.key}>
              <div className="flex items-baseline justify-between gap-3 text-[11.5px]">
                <span className="truncate text-slate-300">{s.label}</span>
                <span className="readout shrink-0 text-slate-400">
                  {s.note ?? `${s.value.toLocaleString()}${unit ? ` ${unit}` : ""}`}
                </span>
              </div>
              <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-edge">
                <div
                  className="h-full rounded-full transition-[width] duration-500 ease-out"
                  style={{
                    width: `${Math.max(2, (s.value / total) * 100)}%`,
                    background: colour,
                  }}
                />
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** Named rows with a bar each — the "distributed training nodes" panel. */
export function BarList({
  items,
}: {
  items: { key: string; label: string; note?: string; fraction: number; tone?: Tone }[];
}) {
  return (
    <ul className="space-y-3">
      {items.map((it, i) => (
        <li key={it.key}>
          <div className="flex items-baseline justify-between gap-3">
            <span className="truncate text-[12px] text-slate-200">{it.label}</span>
            {it.note && <span className="readout shrink-0 text-[11px] text-slate-500">{it.note}</span>}
          </div>
          <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-edge">
            <div
              className="grow-x h-full rounded-full transition-[width] duration-500 ease-out"
              style={{
                animationDelay: `${i * 60}ms`,
                width: `${Math.max(2, Math.min(1, it.fraction) * 100)}%`,
                background: toneInk(it.tone ?? hueAt(i)),
              }}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}

/**
 * One line in a live feed: what happened, to what, and how bad.
 *
 * <p>The severity word is a pill because it is the one part that gets scanned
 * rather than read — a column of them is triage without reading a sentence.
 */
export function Event({
  tone = "info",
  title,
  meta,
  when,
  badge,
}: {
  tone?: Tone;
  title: ReactNode;
  meta?: ReactNode;
  when?: ReactNode;
  badge?: string;
}) {
  return (
    <li
      className="flex items-start gap-3 rounded-lg px-2.5 py-2.5 transition-colors hover:bg-slate-500/[0.05]"
    >
      <span
        aria-hidden
        className="mt-[3px] h-1.5 w-1.5 shrink-0 rounded-full"
        style={{ background: toneInk(tone) }}
      />
      <div className="min-w-0 flex-1">
        <div className="truncate text-[12px] text-slate-200">{title}</div>
        {meta && <div className="mt-0.5 truncate text-[11px] text-slate-500">{meta}</div>}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {when && <span className="readout text-[10.5px] text-slate-600">{when}</span>}
        {badge && <Pill tone={tone}>{badge}</Pill>}
      </div>
    </li>
  );
}

/** The container for {@link Event}s, with the live marker in its head. */
export function Feed({ children }: { children: ReactNode }) {
  return <ul className="-mx-1 max-h-[300px] space-y-0.5 overflow-y-auto">{children}</ul>;
}

/**
 * A grid of cells shaded by value.
 *
 * <p>Sequential, one hue: the cell's job is "more or less than its neighbour",
 * and giving each row its own colour would spend the identity channel on
 * something position already encodes.
 */
export function Heat({
  rows,
  cols,
  tone = "violet",
}: {
  rows: { label: string; values: number[] }[];
  cols?: string[];
  tone?: Tone;
}) {
  const max = Math.max(1, ...rows.flatMap((r) => r.values));
  const colour = toneInk(tone);
  return (
    <div className="overflow-x-auto">
      <div className="min-w-[260px]">
        {rows.map((r) => (
          <div key={r.label} className="mb-1 flex items-center gap-2">
            <span className="w-16 shrink-0 truncate text-[10.5px] text-slate-500">{r.label}</span>
            <div className="flex flex-1 gap-1">
              {r.values.map((v, i) => (
                <span
                  key={i}
                  title={`${r.label} · ${v}`}
                  className="h-5 flex-1 rounded-[3px] transition-colors duration-300"
                  style={{
                    background: v === 0 ? "rgb(var(--edge))" : colour,
                    opacity: v === 0 ? 1 : 0.22 + (v / max) * 0.78,
                  }}
                />
              ))}
            </div>
          </div>
        ))}
        {cols && (
          <div className="mt-1 flex items-center gap-2">
            <span className="w-16 shrink-0" />
            <div className="flex flex-1 gap-1">
              {cols.map((c) => (
                <span key={c} className="flex-1 text-center text-[9.5px] text-slate-600">
                  {c}
                </span>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * A proportional bar.
 *
 * <p>Non-zero always draws at least three pixels. A bar that rounds a real value
 * down to nothing says "none" when the answer is "a little", and that is the one
 * error a bar chart must not make.
 */
export function Bar({
  fraction,
  tone = "accent",
  width = 84,
}: {
  fraction: number;
  tone?: "accent" | "ok" | "warn" | "bad" | "mute";
  width?: number;
}) {
  const pct = Math.min(1, Math.max(0, fraction)) * 100;
  const colour = {
    accent: "var(--accent)",
    ok: "var(--state-healthy-ink)",
    warn: "var(--state-warning-ink)",
    bad: "var(--state-critical-ink)",
    mute: "var(--series-mute)",
  }[tone];
  return (
    <span
      className="inline-block shrink-0 overflow-hidden rounded-full align-middle"
      style={{ width, height: 5, background: "rgb(var(--edge))" }}
      aria-hidden
    >
      <span
        className="block h-full rounded-full transition-[width] duration-500 ease-out"
        style={{ width: pct === 0 ? 0 : `max(3px, ${pct}%)`, background: colour }}
      />
    </span>
  );
}
