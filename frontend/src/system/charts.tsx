import { useId, useLayoutEffect, useMemo, useRef, useState } from "react";
import type { ReactNode } from "react";

/**
 * Chart primitives.
 *
 * <p>Built in SVG against the console's own tokens rather than pulled from a
 * library, for two reasons. The palette is validated — the categorical slots
 * clear colour-vision-deficiency separation and contrast against both surfaces,
 * and a library's defaults would not — and a chart here has to read as
 * instrumentation beside a readout, not as a chart pasted next to one.
 *
 * <p>The rules these components enforce, so a caller cannot get them wrong:
 *
 * <ul>
 *   <li><b>Identity colour never depends on rank.</b> A series keeps its hue when
 *       a filter removes its neighbours. A reader who learned "Gemini is blue"
 *       stays right.</li>
 *   <li><b>Eight slots, never cycled.</b> A ninth series folds into "Other"
 *       rather than getting a generated hue that is indistinguishable from an
 *       existing one under CVD.</li>
 *   <li><b>Magnitude uses one hue, identity uses many.</b> Colouring nominal bars
 *       by their own value spends the identity channel on what bar length
 *       already shows.</li>
 *   <li><b>Every chart carries a table view.</b> It is the accessibility relief
 *       for the light-surface contrast exception, and it is also just the
 *       fastest way to read exact numbers.</li>
 * </ul>
 */

/** Fixed categorical order. Index, never hash — the order is the safety. */
const SERIES = [
  "var(--series-1)",
  "var(--series-2)",
  "var(--series-3)",
  "var(--series-4)",
  "var(--series-5)",
  "var(--series-6)",
  "var(--series-7)",
  "var(--series-8)",
] as const;

const SEQ = ["var(--seq-1)", "var(--seq-2)", "var(--seq-3)", "var(--seq-4)", "var(--seq-5)"];

export const MUTED = "var(--series-mute)";
export const GRID = "var(--chart-grid)";
export const SURFACE = "var(--chart-surface)";

/** The colour for slot i. Past the eighth the caller should have folded to "Other". */
export function seriesColor(index: number): string {
  return SERIES[index % SERIES.length];
}

/** A magnitude step, low → high. For ordered bins, never for nominal categories. */
export function seqColor(fraction: number): string {
  const i = Math.max(0, Math.min(SEQ.length - 1, Math.round(fraction * (SEQ.length - 1))));
  return SEQ[i];
}

export type Datum = {
  /** Stable identity. The colour follows this, not the row position. */
  key: string;
  label: string;
  value: number;
  /** Overrides the slot colour — for status data, where the colour has meaning. */
  color?: string;
  hint?: string;
};

const fmt = (n: number) =>
  Math.abs(n) >= 10000
    ? n.toLocaleString(undefined, { maximumFractionDigits: 0 })
    : Number.isInteger(n)
      ? String(n)
      : n.toFixed(Math.abs(n) < 1 ? 2 : 1);

/**
 * Folds a long tail into "Other".
 *
 * <p>Past about six classes adjacent colours blur, and generating a ninth hue
 * produces one indistinguishable from an existing slot. Folding is the honest
 * answer; the table view still carries every row.
 */
export function foldTail(data: Datum[], keep = 6): Datum[] {
  if (data.length <= keep) return data;
  const sorted = [...data].sort((a, b) => b.value - a.value);
  const head = sorted.slice(0, keep - 1);
  const tail = sorted.slice(keep - 1);
  return [
    ...head,
    {
      key: "__other",
      label: `Other (${tail.length})`,
      value: tail.reduce((n, d) => n + d.value, 0),
      color: MUTED,
      hint: tail.map((d) => `${d.label} ${fmt(d.value)}`).join(", "),
    },
  ];
}

/** Identity, always present for two or more series. Never colour alone. */
export function Legend({ data, unit }: { data: Datum[]; unit?: string }) {
  if (data.length < 2) return null;
  return (
    <ul className="flex flex-wrap gap-x-4 gap-y-1.5" role="list">
      {data.map((d, i) => (
        <li key={d.key} className="flex items-center gap-1.5 text-xs text-slate-400">
          <span
            aria-hidden
            className="inline-block h-2.5 w-2.5 shrink-0 rounded-[2px]"
            style={{ background: d.color ?? seriesColor(i) }}
          />
          <span>{d.label}</span>
          <span className="readout text-slate-500">
            {fmt(d.value)}
            {unit ? ` ${unit}` : ""}
          </span>
        </li>
      ))}
    </ul>
  );
}

/**
 * The exact numbers, behind a toggle.
 *
 * <p>Not an afterthought: on the light surface three categorical slots sit below
 * 3:1 against paper, and the rule for that is relief — visible labels or this.
 * It is also what a developer copying a figure into a ticket actually wants.
 */
export function ChartTable({
  data,
  unit,
  valueLabel = "Value",
}: {
  data: Datum[];
  unit?: string;
  valueLabel?: string;
}) {
  const total = data.reduce((n, d) => n + d.value, 0);
  return (
    <div className="overflow-x-auto">
      <table className="w-full text-left text-xs">
        <thead>
          <tr className="text-slate-600">
            <th className="pb-1.5 font-normal">Series</th>
            <th className="pb-1.5 text-right font-normal">
              {valueLabel}
              {unit ? ` (${unit})` : ""}
            </th>
            <th className="pb-1.5 text-right font-normal">Share</th>
          </tr>
        </thead>
        <tbody className="text-slate-400">
          {data.map((d, i) => (
            <tr key={d.key} className="border-t border-edge/40">
              <td className="py-1 text-slate-300">
                <span
                  aria-hidden
                  className="mr-2 inline-block h-2 w-2 rounded-[2px] align-middle"
                  style={{ background: d.color ?? seriesColor(i) }}
                />
                {d.label}
              </td>
              <td className="readout py-1 text-right">{fmt(d.value)}</td>
              <td className="readout py-1 text-right text-slate-500">
                {total > 0 ? `${((d.value / total) * 100).toFixed(1)}%` : "—"}
              </td>
            </tr>
          ))}
        </tbody>
      </table>
    </div>
  );
}

/** The frame every chart shares: title, the chart, a table toggle. */
export function ChartFrame({
  title,
  caption,
  data,
  unit,
  valueLabel,
  aside,
  children,
}: {
  title: string;
  caption?: string;
  data: Datum[];
  unit?: string;
  valueLabel?: string;
  aside?: ReactNode;
  children: ReactNode;
}) {
  const [table, setTable] = useState(false);
  return (
    <div
      className="card space-y-3 border border-card-edge bg-card p-4 shadow-card"
      style={{ borderRadius: "var(--r-lg)" }}
    >
      <div className="flex flex-wrap items-center justify-between gap-2">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-100">{title}</h2>
        <div className="flex items-center gap-2">
          {aside}
          {/* Two states, both visible. A single button that reads "table" and
              silently becomes "chart" makes you click it to find out what it
              does; a pair shows which view you are in. */}
          <div
            className="flex items-center gap-0.5 rounded-[var(--r-md)] p-0.5"
            style={{ background: "var(--wash-mute)" }}
            role="group"
            aria-label="View"
          >
            {([false, true] as const).map((wantTable) => (
              <button
                key={String(wantTable)}
                onClick={() => setTable(wantTable)}
                aria-pressed={table === wantTable}
                className="rounded-[5px] px-1.5 py-[3px] text-[10px] font-medium uppercase tracking-wide transition-colors duration-150"
                style={
                  table === wantTable
                    ? { background: "rgb(var(--card))", color: "rgb(var(--topo-text))" }
                    : { color: "var(--text-2)" }
                }
              >
                {wantTable ? "Table" : "Chart"}
              </button>
            ))}
          </div>
        </div>
      </div>
      {caption && <p className="text-xs text-slate-600">{caption}</p>}
      {table ? (
        <ChartTable data={data} unit={unit} valueLabel={valueLabel} />
      ) : (
        children
      )}
    </div>
  );
}

/**
 * Horizontal bars — the default for comparing magnitude.
 *
 * <p>Horizontal rather than vertical because the categories here are names —
 * providers, callers, exception types — and a name under a vertical column
 * either wraps, truncates or tilts. Every bar is one hue by default: the length
 * already encodes the value, so colouring by value would spend the identity
 * channel on information the chart shows twice.
 *
 * @param emphasis a key to highlight; every other bar goes grey. The most
 *                 underused form, and usually the honest answer to "make this
 *                 clearer" when one row is the point
 */
export function BarChart({
  data,
  unit,
  categorical = false,
  emphasis,
  max,
  height = 22,
}: {
  data: Datum[];
  unit?: string;
  categorical?: boolean;
  emphasis?: string;
  max?: number;
  height?: number;
}) {
  const top = max ?? Math.max(1, ...data.map((d) => d.value));
  const labelWidth = 128;

  if (data.length === 0) {
    return <p className="text-sm text-slate-600">Nothing to show yet.</p>;
  }

  return (
    <div className="space-y-1.5">
      {data.map((d, i) => {
        const frac = Math.max(0, Math.min(1, d.value / top));
        const dim = emphasis !== undefined && d.key !== emphasis;
        const fill = dim ? MUTED : d.color ?? (categorical ? seriesColor(i) : "var(--series-1)");
        return (
          <div key={d.key} className="flex items-center gap-2" title={d.hint}>
            <span
              className="shrink-0 truncate text-right text-xs text-slate-400"
              style={{ width: labelWidth }}
              title={d.label}
            >
              {d.label}
            </span>
            <div className="relative min-w-0 flex-1" style={{ height }}>
              {/* The track is the plot area, not a second value. Hairline only. */}
              <div
                className="absolute inset-y-0 left-0 right-0 rounded-[3px]"
                style={{ background: GRID }}
                aria-hidden
              />
              <div
                className="absolute inset-y-0 left-0 transition-[width] duration-700 ease-out"
                style={{
                  // A non-zero value never renders as nothing. A count of 1
                  // beside a count of 500 is a sliver, and a sliver rounded to
                  // zero pixels reads as "none" — which is a different fact.
                  width: d.value > 0 ? `max(3px, ${frac * 100}%)` : 0,
                  background: fill,
                  // Square at the baseline, rounded at the data end: the shape
                  // says which end is the measurement.
                  borderRadius: "0 4px 4px 0",
                }}
              />
            </div>
            {/* Direct label, always visible — this is the relief the light
                surface's contrast exception requires, and it saves a hover.
                Beside the bar, not on it: printed over the fill it lost its
                contrast on exactly the longest bar. */}
            <span className="readout w-16 shrink-0 text-[11px] text-slate-300">
              {fmt(d.value)}
              {unit ? <span className="ml-0.5 text-slate-500">{unit}</span> : null}
            </span>
          </div>
        );
      })}
    </div>
  );
}

/**
 * One horizontal bar split into its parts.
 *
 * <p>The right form for part-to-whole at a glance. A pie would be worse at every
 * size this console uses, and a two-slice pie would be worse than the number.
 * Segments are separated by a 2px gap in the surface colour rather than a
 * stroke — the gap does the separating, and a stroke would add ink that is not
 * data.
 */
export function StackedBar({
  data,
  height = 28,
  unit,
}: {
  data: Datum[];
  height?: number;
  unit?: string;
}) {
  const total = data.reduce((n, d) => n + Math.max(0, d.value), 0);
  if (total <= 0) {
    return (
      <div
        className="w-full rounded-[4px]"
        style={{ height, background: GRID }}
        aria-label="no data yet"
      />
    );
  }
  return (
    <div className="space-y-2">
      <div className="flex w-full overflow-hidden rounded-[4px]" style={{ height }}>
        {data.map((d, i) => {
          const pct = (Math.max(0, d.value) / total) * 100;
          if (pct <= 0) return null;
          return (
            <div
              key={d.key}
              className="relative flex items-center justify-center transition-[width] duration-700 ease-out"
              style={{
                width: `${pct}%`,
                background: d.color ?? seriesColor(i),
                // The 2px spacer, in surface colour.
                marginRight: i < data.length - 1 ? 2 : 0,
              }}
              title={`${d.label}: ${fmt(d.value)}${unit ? " " + unit : ""} (${pct.toFixed(1)}%)`}
            >
              {/* Only where it genuinely fits — a clipped label is worse than
                  none, and the legend and table carry the rest. */}
              {pct > 12 && (
                <span className="readout rounded px-1 text-[10px] font-medium" style={{ color: "#fff", background: "rgb(0 0 0 / .5)" }}>
                  {pct.toFixed(0)}%
                </span>
              )}
            </div>
          );
        })}
      </div>
      <Legend data={data} unit={unit} />
    </div>
  );
}

/**
 * A ring for part-to-whole where a single share is the headline.
 *
 * <p>Used sparingly and never for comparing close values — that is a bar. Its
 * one real advantage is the hole, which holds the number the reader came for.
 */
export function Donut({
  data,
  size = 132,
  thickness = 14,
  centerValue,
  centerLabel,
  unit,
}: {
  data: Datum[];
  size?: number;
  thickness?: number;
  centerValue?: string;
  centerLabel?: string;
  unit?: string;
}) {
  const total = data.reduce((n, d) => n + Math.max(0, d.value), 0);
  const r = (size - thickness) / 2;
  const c = 2 * Math.PI * r;
  let offset = 0;

  return (
    <div className="flex flex-wrap items-center gap-x-5 gap-y-3">
      <svg width={size} height={size} role="img" aria-label={`${data.length} segments`}>
        <g transform={`rotate(-90 ${size / 2} ${size / 2})`}>
          <circle
            cx={size / 2}
            cy={size / 2}
            r={r}
            fill="none"
            stroke={GRID}
            strokeWidth={thickness}
          />
          {total > 0 &&
            data.map((d, i) => {
              const frac = Math.max(0, d.value) / total;
              // A 2px surface gap between segments, expressed as dash spacing.
              const len = Math.max(0, frac * c - 2);
              const seg = (
                <circle
                  key={d.key}
                  cx={size / 2}
                  cy={size / 2}
                  r={r}
                  fill="none"
                  stroke={d.color ?? seriesColor(i)}
                  strokeWidth={thickness}
                  strokeDasharray={`${len} ${c - len}`}
                  strokeDashoffset={-offset}
                  className="transition-[stroke-dasharray] duration-700 ease-out"
                >
                  <title>{`${d.label}: ${fmt(d.value)} (${(frac * 100).toFixed(1)}%)`}</title>
                </circle>
              );
              offset += frac * c;
              return seg;
            })}
        </g>
        {centerValue && (
          <>
            <text
              x={size / 2}
              y={size / 2 - 2}
              textAnchor="middle"
              className="readout"
              style={{ fill: "rgb(var(--topo-text))", fontSize: 20, fontWeight: 600 }}
            >
              {centerValue}
            </text>
            {centerLabel && (
              <text
                x={size / 2}
                y={size / 2 + 15}
                textAnchor="middle"
                style={{ fill: "var(--text-3)", fontSize: 10, letterSpacing: "0.08em" }}
              >
                {centerLabel.toUpperCase()}
              </text>
            )}
          </>
        )}
      </svg>
      <div className="min-w-[10rem] flex-1">
        <Legend data={data} unit={unit} />
      </div>
    </div>
  );
}

/**
 * Ordered bins as columns — a real histogram.
 *
 * <p>Vertical because the categories are an ordered scale, so the x-axis carries
 * meaning that a horizontal layout would throw away. One hue stepped by
 * magnitude: the bins have a natural order, which is what makes a ramp correct
 * here and wrong on nominal categories.
 */
export function Histogram({
  bins,
  height = 120,
  xLabel,
  endLabel,
}: {
  /** {@code color} overrides the magnitude ramp — for bins whose position has a meaning (below a threshold, say). */
  bins: { label: string; value: number; hint?: string; color?: string }[];
  height?: number;
  xLabel?: string;
  /** The upper edge of the last bin; the labels are lower edges, so the axis would otherwise stop one bin short. */
  endLabel?: string;
}) {
  const top = Math.max(1, ...bins.map((b) => b.value));
  const total = bins.reduce((n, b) => n + b.value, 0);

  if (total === 0) {
    return (
      <p className="text-sm text-slate-600">
        No measurements yet — the histogram fills in as requests are scored.
      </p>
    );
  }

  return (
    <div>
      {/* Columns stretch to the chart's height: a bar's percentage height needs
          a definite column to be a percentage of. With the columns sized to
          their content, every bar resolved to zero and the chart drew empty. */}
      <div className="flex items-stretch gap-[3px]" style={{ height }}>
        {bins.map((b, i) => {
          const frac = b.value / top;
          return (
            <div key={i} className="group relative flex min-w-0 flex-1 flex-col justify-end">
              <span
                className="readout mb-1 text-center text-[10px] text-slate-500 opacity-0 transition-opacity group-hover:opacity-100"
                aria-hidden
              >
                {fmt(b.value)}
              </span>
              <div
                className="w-full transition-[height] duration-700 ease-out"
                style={{
                  height: `${Math.max(frac * 100, b.value > 0 ? 3 : 0)}%`,
                  background: b.color ?? seqColor(frac),
                  borderRadius: "3px 3px 0 0",
                  maxWidth: 24,
                  marginInline: "auto",
                }}
                title={b.hint ?? `${b.label}: ${fmt(b.value)}`}
              />
            </div>
          );
        })}
      </div>
      <div
        className="mt-1.5 border-t pt-1"
        style={{ borderColor: GRID }}
        aria-hidden
      />
      <div className="flex justify-between text-[10px] text-slate-600">
        <span>{bins[0]?.label}</span>
        {xLabel && <span className="text-slate-700">{xLabel}</span>}
        <span>{endLabel ?? bins[bins.length - 1]?.label}</span>
      </div>
    </div>
  );
}

/**
 * Asked-for against achieved, per row.
 *
 * <p>A dumbbell rather than two bars side by side: the reader's question is the
 * <em>gap</em>, and paired bars make them compare two lengths instead of looking
 * at one distance. Two shades of one hue, because these are two states of the
 * same measure and not two identities.
 */
export function TargetVsActual({
  rows,
  format = (n: number) => n.toFixed(2),
}: {
  rows: { key: string; label: string; target: number; actual: number | null; hint?: string }[];
  format?: (n: number) => string;
}) {
  const all = rows.flatMap((r) => [r.target, r.actual ?? r.target]);
  const lo = Math.min(...all, 0);
  const hi = Math.max(...all, 1);
  const span = hi - lo || 1;
  const pos = (v: number) => ((v - lo) / span) * 100;

  return (
    <div className="space-y-2">
      {rows.map((r) => {
        const missing = r.actual === null;
        const short = !missing && r.actual! < r.target;
        return (
          <div key={r.key} className="flex items-center gap-2" title={r.hint}>
            <span className="w-28 shrink-0 truncate text-right text-xs text-slate-400">
              {r.label}
            </span>
            <div className="relative h-5 min-w-0 flex-1">
              <div
                className="absolute inset-x-0 top-1/2 h-px -translate-y-1/2"
                style={{ background: GRID }}
                aria-hidden
              />
              {!missing && (
                <div
                  className="absolute top-1/2 h-[3px] -translate-y-1/2 rounded-full transition-all duration-700"
                  style={{
                    left: `${Math.min(pos(r.target), pos(r.actual!))}%`,
                    width: `${Math.abs(pos(r.actual!) - pos(r.target))}%`,
                    background: short ? "var(--div-neg)" : "var(--div-pos)",
                    opacity: 0.5,
                  }}
                  aria-hidden
                />
              )}
              {/* Target: hollow, because it is the intention, not the reading. */}
              <span
                className="absolute top-1/2 h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full"
                style={{
                  left: `${pos(r.target)}%`,
                  border: "2px solid var(--series-1)",
                  background: SURFACE,
                }}
                title={`target ${format(r.target)}`}
              />
              {!missing && (
                <span
                  className="absolute top-1/2 h-2.5 w-2.5 -translate-x-1/2 -translate-y-1/2 rounded-full transition-all duration-700"
                  style={{
                    left: `${pos(r.actual!)}%`,
                    background: short ? "var(--div-neg)" : "var(--div-pos)",
                    // The 2px surface ring, so the two dots stay legible where
                    // they overlap — which is exactly when the gap is zero.
                    boxShadow: `0 0 0 2px ${SURFACE}`,
                  }}
                  title={`achieved ${format(r.actual!)}`}
                />
              )}
            </div>
            <span className="readout w-24 shrink-0 text-right text-[11px]">
              {missing ? (
                <span className="text-slate-600">not measured</span>
              ) : (
                <>
                  <span className="text-slate-500">{format(r.target)}</span>
                  <span className="mx-1 text-slate-700">→</span>
                  <span className={short ? "text-rose-400" : "text-slate-200"}>
                    {format(r.actual!)}
                  </span>
                </>
              )}
            </span>
          </div>
        );
      })}
      <div className="flex flex-wrap gap-x-4 gap-y-1 pt-1 text-[10px] text-slate-600">
        <span className="flex items-center gap-1.5">
          <span
            className="inline-block h-2 w-2 rounded-full"
            style={{ border: "2px solid var(--series-1)" }}
            aria-hidden
          />
          asked for
        </span>
        <span className="flex items-center gap-1.5">
          <span
            className="inline-block h-2 w-2 rounded-full"
            style={{ background: "var(--div-pos)" }}
            aria-hidden
          />
          achieved
        </span>
        <span className="flex items-center gap-1.5">
          <span
            className="inline-block h-2 w-2 rounded-full"
            style={{ background: "var(--div-neg)" }}
            aria-hidden
          />
          fell short
        </span>
      </div>
    </div>
  );
}

/**
 * A time series with a crosshair.
 *
 * <p>The hover layer is not optional decoration: an SVG chart <em>is</em>
 * interactive, and without it the only way to read a value is to guess against
 * the axis. One series needs no legend — the title names it.
 */
export function SeriesChart({
  series,
  height = 140,
  unit,
  format = fmt,
}: {
  series: { key: string; label: string; points: number[]; color?: string }[];
  height?: number;
  unit?: string;
  format?: (n: number) => string;
}) {
  const id = useId();
  const [hover, setHover] = useState<number | null>(null);
  const width = 600;

  const { max, min, len } = useMemo(() => {
    const all = series.flatMap((s) => s.points);
    const len = Math.max(...series.map((s) => s.points.length), 0);
    return {
      max: all.length ? Math.max(...all) : 1,
      min: 0,
      len,
    };
  }, [series]);

  if (len < 2) {
    return (
      <p className="text-sm text-slate-600">
        Not enough history yet — a line needs at least two readings.
      </p>
    );
  }

  const span = max - min || 1;
  const x = (i: number) => (i / (len - 1)) * width;
  const y = (v: number) => height - ((v - min) / span) * height;

  // Four ticks including both ends. A gridline with no number on it says "there
  // is a scale" without saying what it is, which is the most common way a chart
  // in a console manages to be decorative.
  const ticks = [1, 0.75, 0.5, 0.25, 0].map((f) => ({ f, v: min + span * f }));

  return (
    <div className="space-y-2">
      {/* The axis gutter is HTML, not SVG text: the plot is drawn with
          preserveAspectRatio="none" so anything inside it is stretched, and
          stretched type is the tell that a chart was scaled rather than laid
          out. */}
      <div className="relative pl-10">
        <div className="pointer-events-none absolute inset-y-0 left-0 w-9" aria-hidden>
          {ticks.map(({ f, v }) => (
            <span
              key={f}
              className="readout absolute right-0 -translate-y-1/2 text-[10px] text-slate-500"
              style={{ top: `${(1 - f) * 100}%` }}
            >
              {format(v)}
            </span>
          ))}
        </div>
        <svg
          viewBox={`0 0 ${width} ${height}`}
          className="w-full"
          style={{ height }}
          preserveAspectRatio="none"
          onMouseLeave={() => setHover(null)}
          onMouseMove={(e) => {
            const box = e.currentTarget.getBoundingClientRect();
            const frac = (e.clientX - box.left) / box.width;
            setHover(Math.max(0, Math.min(len - 1, Math.round(frac * (len - 1)))));
          }}
        >
          {/* Recessive hairline grid — solid, never dashed, and on the same
              rows as the axis numbers so a value can be read off the line. */}
          {ticks.map(({ f }) => (
            <line
              key={f}
              x1={0}
              x2={width}
              y1={height * (1 - f)}
              y2={height * (1 - f)}
              stroke={GRID}
              strokeWidth={1}
              vectorEffect="non-scaling-stroke"
            />
          ))}

          {series.map((s, i) => {
            const color = s.color ?? seriesColor(i);
            const d = s.points
              .map((p, j) => `${j === 0 ? "M" : "L"} ${x(j).toFixed(1)} ${y(p).toFixed(1)}`)
              .join(" ");
            return (
              <g key={s.key}>
                {series.length === 1 && (
                  <path
                    d={`${d} L ${width} ${height} L 0 ${height} Z`}
                    fill={color}
                    opacity={0.1}
                  />
                )}
                <path
                  d={d}
                  fill="none"
                  stroke={color}
                  strokeWidth={2}
                  strokeLinejoin="round"
                  strokeLinecap="round"
                  vectorEffect="non-scaling-stroke"
                />
              </g>
            );
          })}

          {hover !== null && (
            <line
              x1={x(hover)}
              x2={x(hover)}
              y1={0}
              y2={height}
              stroke="currentColor"
              className="text-slate-500"
              strokeWidth={1}
              vectorEffect="non-scaling-stroke"
            />
          )}
        </svg>

        {/* Markers sit outside the stretched viewBox so they stay circular. */}
        {hover !== null && (
          <div className="pointer-events-none absolute inset-0">
            {series.map((s, i) => {
              const v = s.points[hover];
              if (v === undefined) return null;
              return (
                <span
                  key={s.key}
                  className="absolute h-2 w-2 -translate-x-1/2 -translate-y-1/2 rounded-full"
                  style={{
                    left: `${(hover / (len - 1)) * 100}%`,
                    top: `${((height - ((v - min) / span) * height) / height) * 100}%`,
                    background: s.color ?? seriesColor(i),
                    boxShadow: `0 0 0 2px ${SURFACE}`,
                  }}
                />
              );
            })}
          </div>
        )}

        {hover !== null && (
          <div
            className="pointer-events-none absolute top-0 z-10 space-y-0.5 border px-2.5 py-2 text-[11px]"
            style={{
              left: `${(hover / (len - 1)) * 100}%`,
              transform: hover > len / 2 ? "translateX(-105%)" : "translateX(5%)",
              borderRadius: "var(--r-md)",
              borderColor: "rgb(var(--card-edge))",
              background: "rgb(var(--card))",
              boxShadow: "0 4px 6px -2px rgba(0,0,0,.14), 0 10px 22px -6px rgba(0,0,0,.3)",
            }}
            role="status"
            aria-live="polite"
          >
            <div className="text-slate-500">reading {hover + 1}</div>
            {series.map((s, i) => (
              <div key={s.key} className="flex items-center gap-1.5 whitespace-nowrap">
                <span
                  className="inline-block h-2 w-2 rounded-[2px]"
                  style={{ background: s.color ?? seriesColor(i) }}
                  aria-hidden
                />
                <span className="text-slate-400">{s.label}</span>
                <span className="readout text-slate-200">
                  {format(s.points[hover] ?? 0)}
                  {unit ? ` ${unit}` : ""}
                </span>
              </div>
            ))}
          </div>
        )}
        <span id={id} className="sr-only">
          {series.map((s) => `${s.label}: ${s.points.map(format).join(", ")}`).join(". ")}
        </span>
      </div>

      {series.length > 1 && (
        <Legend
          data={series.map((s) => ({
            key: s.key,
            label: s.label,
            value: s.points[s.points.length - 1] ?? 0,
            color: s.color,
          }))}
          unit={unit}
        />
      )}
    </div>
  );
}

/**
 * A before/after pair with the change between them.
 *
 * <p>Two bars on one scale, so the comparison is a length the reader can see
 * rather than two numbers they have to subtract. The delta is stated, because
 * the delta is the point.
 */
export function BeforeAfter({
  beforeLabel = "Before",
  afterLabel = "After",
  before,
  after,
  unit,
  goodDirection = "down",
  format = fmt,
}: {
  beforeLabel?: string;
  afterLabel?: string;
  before: number;
  after: number;
  unit?: string;
  goodDirection?: "up" | "down";
  /** How a value is written on its bar; money wants more places than a count. */
  format?: (n: number) => string;
}) {
  // Scaled to the larger of the two, not to at least 1: a pair of costs in
  // fractions of a dollar drew as two empty tracks against a floor of one.
  const top = Math.max(before, after) || 1;
  const improved = goodDirection === "down" ? after < before : after > before;
  const delta = before > 0 ? (after - before) / before : 0;
  const accent = before === after ? MUTED : improved ? "var(--div-pos)" : "var(--div-neg)";

  // The value sits beside its bar, not on it: printed over a saturated fill it
  // lost its contrast exactly when the bar was longest.
  const row = (label: string, value: number, color: string) => (
    <div className="flex items-center gap-2">
      <span className="w-16 shrink-0 text-right text-xs text-slate-500">{label}</span>
      <div className="relative h-5 min-w-0 flex-1">
        <div className="absolute inset-0 rounded-[4px]" style={{ background: GRID }} aria-hidden />
        <div
          className="absolute inset-y-0 left-0 transition-[width] duration-700 ease-out"
          style={{
            width: `${(value / top) * 100}%`,
            background: color,
            borderRadius: 4,
          }}
        />
      </div>
      <span className="readout w-20 shrink-0 text-[11.5px] text-slate-300">
        {format(value)}
        {unit ? <span className="ml-0.5 text-slate-500">{unit}</span> : null}
      </span>
    </div>
  );

  return (
    <div className="space-y-1.5">
      {row(beforeLabel, before, MUTED)}
      {row(afterLabel, after, accent)}
      <div className="flex items-center gap-2 pt-0.5">
        <span className="w-16 shrink-0" aria-hidden />
        {before === after ? (
          <span className="readout text-xs text-slate-500">no change</span>
        ) : (
          <span
            className="readout text-xs"
            style={{ color: improved ? "var(--state-healthy-ink)" : "var(--state-degraded-ink)" }}
          >
            {delta >= 0 ? "+" : "−"}
            {Math.abs(delta * 100).toFixed(1)}%{" "}
            <span className="text-slate-600">{after < before ? "smaller" : "larger"}</span>
          </span>
        )}
      </div>
    </div>
  );
}

/** The rendered width of a chart, so its text is drawn at real pixel size rather than scaled with the box. */
function useWidth(fallback = 600) {
  const ref = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(fallback);
  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(200, Math.round(e.contentRect.width))));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  return [ref, w] as const;
}

/**
 * Two measures per item, one dot each — for "does one depend on the other?".
 *
 * <p>A list shows each request's tokens and latency side by side, and the eye
 * cannot compare forty pairs. Plotted, the relationship (or its absence) and
 * the outliers are the first thing seen. Dots arrive one after another, oldest
 * first, the way the requests did.
 */
export function Scatter({
  points,
  xLabel,
  yLabel,
  xFormat = fmt,
  yFormat = fmt,
  height = 200,
}: {
  points: { x: number; y: number; color?: string; label: string }[];
  xLabel: string;
  yLabel: string;
  xFormat?: (n: number) => string;
  yFormat?: (n: number) => string;
  height?: number;
}) {
  const [hover, setHover] = useState<number | null>(null);
  const [box, W] = useWidth();
  if (points.length < 2) {
    return <p className="text-sm text-slate-600">Not enough points yet — this fills in as requests arrive.</p>;
  }
  const H = height;
  const pad = { l: 44, r: 12, t: 10, b: 28 };
  const xMax = Math.max(...points.map((p) => p.x)) || 1;
  const yMax = Math.max(...points.map((p) => p.y)) || 1;
  const X = (v: number) => pad.l + (v / xMax) * (W - pad.l - pad.r);
  const Y = (v: number) => H - pad.b - (v / yMax) * (H - pad.t - pad.b);
  const h = hover === null ? null : points[hover];
  return (
    <div className="relative" ref={box}>
      <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img" aria-label={`${points.length} points: ${yLabel} against ${xLabel}`}>
        {[0, 0.5, 1].map((f) => (
          <g key={f}>
            <line x1={pad.l} x2={W - pad.r} y1={Y(yMax * f)} y2={Y(yMax * f)} stroke={GRID} strokeDasharray={f ? "3 4" : undefined} />
            <text x={pad.l - 6} y={Y(yMax * f) + 3} textAnchor="end" fontSize="10" fill="var(--text-3)">
              {yFormat(yMax * f)}
            </text>
          </g>
        ))}
        {[0, 0.5, 1].map((f) => (
          <text key={f} x={X(xMax * f)} y={H - pad.b + 14} textAnchor={f === 1 ? "end" : f ? "middle" : "start"} fontSize="10" fill="var(--text-3)">
            {xFormat(xMax * f)}
          </text>
        ))}
        <text x={W - pad.r} y={H - 2} textAnchor="end" fontSize="10" fill="var(--text-3)">
          {xLabel} →
        </text>
        <text x={pad.l} y={pad.t - 1} fontSize="10" fill="var(--text-3)">
          ↑ {yLabel}
        </text>
        {points.map((p, i) => (
          <circle
            key={i}
            cx={X(p.x)}
            cy={Y(p.y)}
            r={hover === i ? 7 : 5}
            fill={p.color ?? seriesColor(0)}
            fillOpacity={hover === null || hover === i ? 0.85 : 0.3}
            stroke="rgb(var(--card))"
            strokeWidth="1.5"
            className="scatter-dot"
            style={{ animationDelay: `${Math.min(i * 25, 900)}ms`, transition: "r 200ms, fill-opacity 200ms" }}
            onMouseEnter={() => setHover(i)}
            onMouseLeave={() => setHover(null)}
          >
            <title>{p.label}</title>
          </circle>
        ))}
      </svg>
      {h && (
        <div className="pointer-events-none absolute right-2 top-1 rounded-lg px-2 py-1 text-[11px]" style={{ background: "var(--wash-mute)", color: "var(--text-2)" }}>
          {h.label} · {xFormat(h.x)} {xLabel} · {yFormat(h.y)} {yLabel}
        </div>
      )}
    </div>
  );
}

/**
 * A running total, where it is heading, and the line it must not cross.
 *
 * <p>For budgets and quotas the question is never "how much so far" but "will
 * I run out, and when". The solid area is what happened; the dashed line
 * carries today's pace to the end of the period; the limit is drawn across, and
 * where the projection meets it is marked.
 */
export function ProjectionChart({
  actual,
  periodLength,
  limit,
  format = fmt,
  unitLabel,
  height = 170,
}: {
  /** Cumulative values, one per elapsed step (e.g. per day), starting at step 1. */
  actual: number[];
  periodLength: number;
  limit?: number;
  format?: (n: number) => string;
  unitLabel: string;
  height?: number;
}) {
  const id = useId();
  const [box, W] = useWidth();
  const H = height;
  const pad = { l: 52, r: 14, t: 14, b: 24 };
  const n = actual.length;
  const last = n ? actual[n - 1] : 0;
  const pace = n ? last / n : 0;
  const projected = pace * periodLength;
  const top = Math.max(limit ?? 0, projected, last, 1) * 1.08;
  const X = (step: number) => pad.l + (step / periodLength) * (W - pad.l - pad.r);
  const Y = (v: number) => H - pad.b - (v / top) * (H - pad.t - pad.b);
  const pts = [[0, 0], ...actual.map((v, i) => [i + 1, v])];
  const line = pts.map(([s, v], i) => `${i ? "L" : "M"}${X(s).toFixed(1)} ${Y(v).toFixed(1)}`).join(" ");
  const crossAt = limit && pace > 0 ? limit / pace : null;
  return (
    <div ref={box}>
    <svg viewBox={`0 0 ${W} ${H}`} width="100%" role="img"
         aria-label={`${format(last)} ${unitLabel} so far; at this pace ${format(projected)} by the end of the period${limit ? ` against a limit of ${format(limit)}` : ""}`}>
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor="var(--series-1)" stopOpacity="0.35" />
          <stop offset="100%" stopColor="var(--series-1)" stopOpacity="0" />
        </linearGradient>
      </defs>
      {[0, 0.5, 1].map((f) => (
        <g key={f}>
          <line x1={pad.l} x2={W - pad.r} y1={Y(top * f)} y2={Y(top * f)} stroke={GRID} strokeDasharray={f ? "3 4" : undefined} />
          <text x={pad.l - 6} y={Y(top * f) + 3} textAnchor="end" fontSize="10" fill="var(--text-3)">{format(top * f)}</text>
        </g>
      ))}
      {limit !== undefined && (
        <g>
          <line x1={pad.l} x2={W - pad.r} y1={Y(limit)} y2={Y(limit)} stroke="var(--state-critical-ink)" strokeWidth="1.5" strokeDasharray="6 4" />
          <text x={W - pad.r} y={Y(limit) - 5} textAnchor="end" fontSize="10" fill="var(--state-critical-ink)">limit {format(limit)}</text>
        </g>
      )}
      <path d={`${line} L${X(n)} ${Y(0)} L${X(0)} ${Y(0)} Z`} fill={`url(#${id})`} className="area-rise" />
      <path d={line} fill="none" stroke="var(--series-1)" strokeWidth="2.5" strokeLinejoin="round" className="line-draw" pathLength={1} />
      {n > 0 && (
        <>
          <line x1={X(n)} y1={Y(last)} x2={X(periodLength)} y2={Y(projected)} stroke="var(--series-1)" strokeWidth="2" strokeDasharray="5 5" opacity="0.7" className="fade-late" />
          <circle cx={X(n)} cy={Y(last)} r="5" fill="var(--series-1)" stroke="rgb(var(--card))" strokeWidth="2" className="scatter-dot" style={{ animationDelay: "700ms" }} />
          <text x={X(periodLength)} y={Y(projected) - 8} textAnchor="end" fontSize="10.5" fontWeight="600" fill="var(--text-2)" className="fade-late">
            {format(projected)} projected
          </text>
        </>
      )}
      {crossAt && crossAt <= periodLength && (
        <circle cx={X(crossAt)} cy={Y(limit!)} r="6" fill="none" stroke="var(--state-critical-ink)" strokeWidth="2" className="scatter-dot" style={{ animationDelay: "900ms" }} />
      )}
      <text x={X(0)} y={H - 6} fontSize="10" fill="var(--text-3)">start</text>
      <text x={X(n)} y={H - 6} textAnchor="middle" fontSize="10" fill="var(--text-2)" fontWeight="600">today</text>
      <text x={X(periodLength)} y={H - 6} textAnchor="end" fontSize="10" fill="var(--text-3)">end</text>
    </svg>
    </div>
  );
}
