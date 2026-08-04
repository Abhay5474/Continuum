import { useId, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { Micro } from "./primitives";

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
    <div className="space-y-2">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <Micro>{title}</Micro>
        <div className="flex items-center gap-2">
          {aside}
          <button
            onClick={() => setTable(!table)}
            className="text-[10px] uppercase tracking-wide text-slate-600 transition-colors hover:text-slate-300"
            aria-pressed={table}
          >
            {table ? "chart" : "table"}
          </button>
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
              {/* Direct label, always visible — this is the relief the light
                  surface's contrast exception requires, and it saves a hover. */}
              <span
                className="readout pointer-events-none absolute inset-y-0 right-2 flex items-center text-[11px] text-slate-300"
                style={{ textShadow: "0 0 3px var(--chart-surface)" }}
              >
                {fmt(d.value)}
                {unit ? <span className="ml-0.5 text-slate-500">{unit}</span> : null}
              </span>
            </div>
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
                <span className="readout px-1 text-[10px] font-medium text-white/95">
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
              style={{ fill: "rgb(226 232 240)", fontSize: 20, fontWeight: 600 }}
            >
              {centerValue}
            </text>
            {centerLabel && (
              <text
                x={size / 2}
                y={size / 2 + 15}
                textAnchor="middle"
                style={{ fill: "rgb(100 116 139)", fontSize: 10, letterSpacing: "0.08em" }}
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
}: {
  bins: { label: string; value: number; hint?: string }[];
  height?: number;
  xLabel?: string;
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
      <div className="flex items-end gap-[3px]" style={{ height }}>
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
                  background: seqColor(frac),
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
        <span>{bins[bins.length - 1]?.label}</span>
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

  return (
    <div className="space-y-2">
      <div className="relative">
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
          {/* Recessive hairline grid — solid, never dashed. */}
          {[0.25, 0.5, 0.75].map((g) => (
            <line
              key={g}
              x1={0}
              x2={width}
              y1={height * g}
              y2={height * g}
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
            className="pointer-events-none absolute top-0 z-10 rounded-md border border-edge bg-panel/95 px-2 py-1.5 text-[11px] shadow-lg backdrop-blur"
            style={{
              left: `${(hover / (len - 1)) * 100}%`,
              transform: hover > len / 2 ? "translateX(-105%)" : "translateX(5%)",
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
}: {
  beforeLabel?: string;
  afterLabel?: string;
  before: number;
  after: number;
  unit?: string;
  goodDirection?: "up" | "down";
}) {
  const top = Math.max(before, after, 1);
  const improved = goodDirection === "down" ? after < before : after > before;
  const delta = before > 0 ? (after - before) / before : 0;
  const accent = improved ? "var(--div-pos)" : "var(--div-neg)";

  const row = (label: string, value: number, color: string) => (
    <div className="flex items-center gap-2">
      <span className="w-16 shrink-0 text-right text-xs text-slate-500">{label}</span>
      <div className="relative h-6 min-w-0 flex-1">
        <div className="absolute inset-0 rounded-[3px]" style={{ background: GRID }} aria-hidden />
        <div
          className="absolute inset-y-0 left-0 transition-[width] duration-700 ease-out"
          style={{
            width: `${(value / top) * 100}%`,
            background: color,
            borderRadius: "0 4px 4px 0",
          }}
        />
        <span
          className="readout pointer-events-none absolute inset-y-0 right-2 flex items-center text-[11px] text-slate-200"
          style={{ textShadow: "0 0 3px var(--chart-surface)" }}
        >
          {fmt(value)}
          {unit ? <span className="ml-0.5 text-slate-500">{unit}</span> : null}
        </span>
      </div>
    </div>
  );

  return (
    <div className="space-y-1.5">
      {row(beforeLabel, before, MUTED)}
      {row(afterLabel, after, accent)}
      <div className="flex items-center gap-2 pt-0.5">
        <span className="w-16 shrink-0" aria-hidden />
        <span
          className="readout text-xs"
          style={{ color: improved ? "var(--state-healthy-ink)" : "var(--state-degraded-ink)" }}
        >
          {delta >= 0 ? "+" : "−"}
          {Math.abs(delta * 100).toFixed(1)}%{" "}
          <span className="text-slate-600">
            {improved ? "smaller" : goodDirection === "down" ? "larger" : "smaller"}
          </span>
        </span>
      </div>
    </div>
  );
}
