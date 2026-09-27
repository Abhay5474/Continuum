import { Children, cloneElement, isValidElement, useRef } from "react";
import type { ReactNode } from "react";
import { useLiquidIndicator } from "../physics";
import { HUES, Hue, Tone, hueAt, toneInk, toneWash } from "./tone";
import { Chip, GLYPHS, GlyphName } from "./glyphs";
import { Card, Grid } from "./structure";

/* ------------------------------------------------------------------ *
 * Workspace layout
 * ------------------------------------------------------------------ */

/**
 * List on the left, the thing you picked on the right.
 *
 * <p>Replaces the accordion. An accordion answers "show me this one" by pushing
 * everything below it off the screen, so comparing two pipelines means opening,
 * scrolling, closing, scrolling, opening. Here the list never moves and the
 * detail is always in the same place — which is also why the eye can go straight
 * to it rather than hunting for where the page expanded.
 *
 * <p>Below the breakpoint it stacks: on a phone there is only ever room for one
 * of the two, and a 240px column beside a detail pane is neither.
 */
export function Split({ list, detail }: { list: ReactNode; detail: ReactNode }) {
  return (
    <div className="grid items-start gap-x-10 gap-y-8 lg:grid-cols-[minmax(210px,254px)_minmax(0,1fr)]">
      <div className="min-w-0">{list}</div>
      <div className="min-w-0">{detail}</div>
    </div>
  );
}

/**
 * One control for a set of exclusive views.
 *
 * <p>The pages this replaces stacked every configuration area vertically inside
 * a bordered box inside a card, so the shape of the page said "five equally
 * important things" when in practice you are doing exactly one of them. A
 * segmented control says that: one at a time, and you can see the others exist.
 */
export function Segmented<T extends string>({
  value,
  onChange,
  options,
}: {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: string; badge?: ReactNode }[];
}) {
  const wrap = useRef<HTMLDivElement>(null);
  const bar = useRef<HTMLSpanElement>(null);
  // One rule that travels, rather than one per option that fades: the eye
  // follows the move from the old view to the new, which a cross-fade cannot
  // show.
  useLiquidIndicator(wrap, value, (l, r) => {
    const el = bar.current;
    if (el) el.style.transform = `translate3d(${l}px,0,0) scaleX(${Math.max(0, r - l)})`;
  });
  return (
    <div
      ref={wrap}
      role="tablist"
      className="relative flex flex-wrap items-center gap-x-5 gap-y-1 border-b border-edge/60"
    >
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            role="tab"
            aria-selected={on}
            data-indicator-key={o.value}
            onClick={() => onChange(o.value)}
            className={`relative -mb-px flex items-center gap-1.5 py-2 text-[12.5px] ${
              on ? "text-slate-100" : "text-slate-500 hover:text-slate-300"
            }`}
          >
            {o.label}
            {o.badge}
          </button>
        );
      })}
      {/* The indicator is a rule under the live tab, not a filled pill.
          A pill is the same shape as a button and invites a second click. */}
      <span
        ref={bar}
        aria-hidden
        className="pointer-events-none absolute -bottom-px left-0 h-[1.5px] w-px origin-left"
        style={{ background: "var(--accent)", willChange: "transform" }}
      />
    </div>
  );
}

/**
 * A word about a state, in its own colour, on its own tint.
 *
 * <p>The rule that keeps pills from becoming badge soup: a pill is for a
 * <em>state</em> — live, failed, degraded, cached — never for a category. A
 * category is what the mark and the words are for.
 */
export function Pill({
  tone = "mute",
  dot = false,
  children,
}: {
  tone?: Tone;
  dot?: boolean;
  children: ReactNode;
}) {
  return (
    <span
      className="inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-[3px] text-[10.5px] font-medium"
      style={{ background: toneWash(tone), color: toneInk(tone) }}
    >
      {dot && (
        <span
          className="h-1.5 w-1.5 rounded-full"
          style={{ background: "currentColor" }}
          aria-hidden
        />
      )}
      {children}
    </span>
  );
}

/**
 * Steps as a chain of beads: what ran, in order, and how each ended.
 *
 * <p>Replaces the sentence the console used to print for this — "1 rolled back;
 * 1 could not be and their effects remain" — with the steps themselves. The
 * eye reads a row of green and red faster than it reads a count in prose, and
 * the names are right there.
 */
export type ChainStep = { label: string; state?: "done" | "undone" | "failed" | "stranded" | "pending" | "plain"; note?: string };

export function StepChain({ steps, arrow = "→", label }: { steps: ChainStep[]; arrow?: string; label?: ReactNode }) {
  const look: Record<string, { tone: Tone; icon?: string }> = {
    done: { tone: "ok", icon: "✓" },
    undone: { tone: "ok", icon: "↺" },
    failed: { tone: "bad", icon: "✕" },
    stranded: { tone: "bad", icon: "!" },
    pending: { tone: "mute" },
    plain: { tone: "info" },
  };
  return (
    <div className="flex flex-wrap items-center gap-1.5">
      {label && <span className="micro mr-1">{label}</span>}
      {steps.map((st, i) => {
        const l = look[st.state ?? "plain"];
        return (
          <span key={i} className="inline-flex items-center gap-1.5">
            {i > 0 && <span aria-hidden className="text-[11px] text-slate-500">{arrow}</span>}
            <span
              data-tip={st.note}
              className="inline-flex items-center gap-1 rounded-full px-2.5 py-[3px] font-mono text-[11px] font-medium"
              style={{ background: toneWash(l.tone), color: toneInk(l.tone) }}
            >
              {l.icon && <span aria-hidden>{l.icon}</span>}
              {st.label}
            </span>
          </span>
        );
      })}
    </div>
  );
}

/**
 * The shape of a number's recent history, drawn small.
 *
 * <p>A figure on its own answers "what is it"; the same figure with its last
 * dozen readings under it answers "and is that normal", which is the question
 * anyone opening a console actually has. No axes: at this size a scale would be
 * unreadable, and the sparkline is deliberately making a claim about shape
 * rather than about value.
 */
export function Spark({
  points,
  tone = "info",
  height = 34,
}: {
  points: number[];
  tone?: Tone;
  height?: number;
}) {
  const lo = Math.min(...points);
  const hi = Math.max(...points);
  // A flat series has no shape to show. Drawing it anyway puts a hard rule
  // across the bottom of the card that reads as a broken chart rather than as
  // "nothing has happened", so it draws nothing instead.
  if (points.length < 2 || hi === lo) return null;
  const w = 100;
  const span = hi - lo || 1;
  const y = (v: number) => 2 + (1 - (v - lo) / span) * (height - 4);
  const x = (i: number) => (i / (points.length - 1)) * w;
  const line = points.map((v, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(2)} ${y(v).toFixed(2)}`).join(" ");
  const id = `sp${tone}${points.length}${Math.round(lo)}${Math.round(hi)}`;
  const colour = toneInk(tone);
  return (
    <svg
      viewBox={`0 0 ${w} ${height}`}
      preserveAspectRatio="none"
      width="100%"
      height={height}
      aria-hidden
      className="block"
    >
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={colour} stopOpacity="0.22" />
          <stop offset="100%" stopColor={colour} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${line} L${w} ${height} L0 ${height} Z`} fill={`url(#${id})`} />
      <path d={line} fill="none" stroke={colour} strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

/**
 * A number, its mark, its history and its direction — one card.
 *
 * <p>This is the console's headline unit. The earlier objection to stat cards
 * stands and is answered here: the reason a band of four bordered figures gets
 * skipped is that it carries a label and a number and nothing else, so there is
 * nothing in it to look at twice. A tinted mark makes it findable, the sparkline
 * gives the figure a shape, and the delta says whether to care.
 */
export function Stat({
  label,
  value,
  unit,
  tone,
  hint,
  glyph,
  series,
  delta,
  deltaNote,
}: {
  label: string;
  value: ReactNode;
  unit?: string;
  tone?: Tone;
  hint?: string;
  glyph?: GlyphName;
  /** Recent readings, oldest first. Drawn as a sparkline across the card foot. */
  series?: number[];
  /** Signed change. Positive draws up, negative down; the colour comes from
      {@code tone} rather than the sign, because up is not always good. */
  delta?: number;
  deltaNote?: string;
}) {
  // Only a *state* colours the figure. A hue is identity — it belongs on the
  // mark and the line, and eight differently-coloured numbers across a band
  // would read as eight different severities.
  const stateTone = tone && !HUES.includes(tone as Hue) ? tone : undefined;
  const colour = stateTone && stateTone !== "mute" ? toneInk(stateTone) : undefined;
  return (
    <Card className="flex min-w-0 flex-col" pad={false}>
      <div className="flex items-start gap-3 p-4 pb-3">
        <div className="min-w-0 flex-1">
          <div className="micro truncate" title={hint}>
            {label}
          </div>
          <div
            className="readout mt-1.5 text-[24px] leading-none tracking-tight text-slate-100"
            style={colour ? { color: colour } : undefined}
          >
            {value}
            {unit && <span className="ml-1 text-[12px] text-slate-500">{unit}</span>}
          </div>
        </div>
        {glyph ? (
          <Chip glyph={glyph} tone={tone ?? "info"} />
        ) : (
          // No glyph chosen: a plain mark still gives the card something to be
          // found by, without inventing an icon that means the wrong thing. A
          // filled bead, not an outlined square: the square read as an empty
          // checkbox, which invited a click that did nothing.
          <span
            aria-hidden
            className="mt-0.5 grid h-[18px] w-[18px] shrink-0 place-items-center rounded-full"
            style={{ background: toneWash(tone ?? "mute") }}
          >
            <span className="h-[7px] w-[7px] rounded-full" style={{ background: toneInk(tone ?? "mute") }} />
          </span>
        )}
      </div>
      {series && series.length > 1 && (
        <div className="-mt-1">
          <Spark points={series} tone={tone ?? "info"} />
        </div>
      )}
      {delta !== undefined && (
        <div className="flex items-baseline gap-1.5 px-4 pb-3 pt-2 text-[11px]">
          <span style={{ color: toneInk(delta >= 0 ? "ok" : "bad") }}>
            {delta >= 0 ? "↗" : "↘"} {delta >= 0 ? "+" : ""}
            {delta}%
          </span>
          {deltaNote && <span className="truncate text-slate-500">{deltaNote}</span>}
        </div>
      )}
    </Card>
  );
}

/**
 * A run of {@link Stat} cards.
 *
 * <p>Any child that has not chosen a tone is given one from the hue order, by
 * position. That is what stops a page of figures coming out monochrome without
 * making every caller think about colour: a card that means something specific
 * says so, and the rest are simply told apart.
 */
export function Stats({ children, cols }: { children: ReactNode; cols?: 2 | 3 | 4 | 5 }) {
  // Five figures on a four-wide grid left one alone on a second row; the row
  // is as wide as its figures, up to five.
  const n = Children.toArray(children).filter(isValidElement).length;
  const width = cols ?? (n === 5 ? 5 : n === 3 ? 3 : 4);
  let i = 0;
  const painted = Children.map(children, (child) => {
    if (!isValidElement(child)) return child;
    const props = child.props as { tone?: Tone };
    const hue = hueAt(i++);
    return props.tone === undefined ? cloneElement(child, { tone: hue } as never) : child;
  });
  return <Grid cols={width}>{painted}</Grid>;
}

/**
 * A labelled bar with its own caption — the rollout form.
 *
 * <p>Name on the left, state on the right, the bar under both, and the figure
 * at the end of the bar rather than in a column somewhere else.
 */
export function Progress({
  label,
  sub,
  fraction,
  tone = "info",
  status,
  caption,
}: {
  label: ReactNode;
  sub?: ReactNode;
  fraction: number;
  tone?: Tone;
  status?: ReactNode;
  caption?: ReactNode;
}) {
  const pct = Math.min(1, Math.max(0, fraction)) * 100;
  return (
    <Card>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-[12.5px] font-medium text-slate-100">{label}</div>
          {sub && <div className="mt-0.5 truncate text-[11px] text-slate-500">{sub}</div>}
        </div>
        {status}
      </div>
      <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-edge">
        <div
          className="h-full rounded-full transition-[width] duration-500 ease-out"
          style={{ width: pct === 0 ? 0 : `max(3px, ${pct}%)`, background: toneInk(tone) }}
        />
      </div>
      <div className="mt-1.5 text-right text-[10.5px] text-slate-500">
        {caption ?? `${Math.round(pct)}% complete`}
      </div>
    </Card>
  );
}

/**
 * Something that happened and may need attention.
 *
 * <p>A rail down the leading edge in the tone's colour, and the faintest wash
 * behind it. The rail is what lets a column of these be triaged without reading
 * any of them — the shape of the column tells you how bad the day is.
 */
export function Notice({
  tone = "info",
  title,
  meta,
  body,
  right,
}: {
  tone?: Tone;
  title: ReactNode;
  meta?: ReactNode;
  body?: ReactNode;
  right?: ReactNode;
}) {
  return (
    <div
      className="rounded-xl border border-l-[3px] p-3.5 shadow-card"
      style={{
        background: toneWash(tone),
        borderColor: "rgb(var(--card-edge))",
        borderLeftColor: toneInk(tone),
      }}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span style={{ color: toneInk(tone) }} aria-hidden className="grid place-items-center">
              <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
                {GLYPHS[tone === "ok" ? "check" : tone === "bad" || tone === "warn" ? "alert" : "activity"]}
              </svg>
            </span>
            <span className="truncate text-[12.5px] font-semibold text-slate-100">{title}</span>
          </div>
          {meta && <div className="mt-1 text-[11px] leading-relaxed text-slate-500">{meta}</div>}
        </div>
        {right && <div className="shrink-0 text-[11px]">{right}</div>}
      </div>
      {body && <p className="mt-2 text-[12px] leading-relaxed text-slate-400">{body}</p>}
    </div>
  );
}
