import type { ReactNode } from "react";
import { STATE, type StateKey } from "./tokens";
import { CountUp } from "./motion";

/**
 * Instrument primitives.
 *
 * These are the shared vocabulary of the interface: labels, readouts, meters and
 * state indicators. They are intentionally *not* cards — a Plane is a surface you
 * compose into a layout, not a box that isolates a metric.
 */

/** A wide-tracked uppercase system label. */
export function Micro({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <div className={`micro ${className}`}>{children}</div>;
}

/** A recessed instrument surface. */
/**
 * The heading every console page wears.
 *
 * <p>Seventeen pages had grown seventeen slightly different headers — some with
 * tracking, some without, a few with an emoji, three with no heading element at
 * all. None of that was a decision; it was drift. One component means the
 * console reads as one instrument, and means a page with no {@code h1} cannot
 * happen by omission.
 *
 * <p>{@code aside} holds whatever the page needs at the top right: a toggle, a
 * row of readouts, a control.
 */
export function PageHeader({
  title,
  subtitle,
  aside,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  aside?: ReactNode;
}) {
  return (
    <header className="flex flex-wrap items-start gap-x-6 gap-y-3">
      <div className="min-w-0 flex-1">
        <h1 className="text-lg font-semibold tracking-tight text-slate-100">{title}</h1>
        {subtitle && <p className="mt-0.5 text-sm text-slate-500">{subtitle}</p>}
      </div>
      {aside && <div className="flex flex-wrap items-end gap-x-6 gap-y-3">{aside}</div>}
    </header>
  );
}

export function Plane({
  children,
  className = "",
  inset = false,
}: {
  children: ReactNode;
  className?: string;
  inset?: boolean;
}) {
  return <div className={`${inset ? "well" : "plane"} ${className}`}>{children}</div>;
}

/**
 * A telemetry readout: a value with its unit and label. Values are tabular so a
 * changing number doesn't reflow, and re-render is signalled by a settle, not a
 * flash.
 */
export function Readout({
  label,
  value,
  unit,
  state = "idle",
  hint,
  size = "md",
}: {
  label: string;
  value: ReactNode;
  unit?: string;
  state?: StateKey;
  hint?: string;
  size?: "sm" | "md" | "lg";
}) {
  const sizes = { sm: "text-lg", md: "text-2xl", lg: "text-4xl" };
  // A plain number rolls to its new value so a change is visible; anything
  // already formatted (strings, elements) is rendered as given.
  const body = typeof value === "number" ? <CountUp value={value} /> : value;
  return (
    <div title={hint}>
      <Micro>{label}</Micro>
      <div className="mt-1 flex items-baseline gap-1">
        <span
          className={`readout font-semibold ${sizes[size]}`}
          style={{ color: state === "idle" ? undefined : STATE[state].ink }}
        >
          {body}
        </span>
        {unit && <span className="text-xs text-slate-500">{unit}</span>}
      </div>
    </div>
  );
}

/** A state indicator — the dot only animates when the state warrants attention. */
export function StateDot({ state, size = 8 }: { state: StateKey; size?: number }) {
  const s = STATE[state];
  const attention = state === "critical" || state === "degraded";
  return (
    <span
      className={`inline-block shrink-0 rounded-full ${attention ? "degrading" : ""}`}
      style={{
        width: size,
        height: size,
        background: s.color,
        boxShadow: state === "offline" ? "none" : `0 0 ${size}px ${s.glow}`,
      }}
      title={s.label}
    />
  );
}

/** A horizontal load/saturation meter. Colour is the state, not decoration. */
export function Meter({
  value,
  state = "active",
  height = 4,
  label,
}: {
  value: number; // 0..1
  state?: StateKey;
  height?: number;
  label?: string;
}) {
  const pct = Math.max(0, Math.min(1, value)) * 100;
  return (
    <div>
      {label && (
        <div className="mb-1 flex items-baseline justify-between">
          <Micro>{label}</Micro>
          <span className="readout text-[10px] text-slate-400">{pct.toFixed(0)}%</span>
        </div>
      )}
      <div className="w-full overflow-hidden rounded-full bg-ink/80" style={{ height }}>
        <div
          className="h-full rounded-full transition-[width] duration-700 ease-out"
          style={{ width: `${pct}%`, background: STATE[state].color }}
        />
      </div>
    </div>
  );
}

/**
 * A compact history trace. Renders nothing but a line — no axes, no legend — so
 * a row of them reads as instrumentation rather than a set of charts.
 */
export function Trace({
  points,
  state = "active",
  width = 120,
  height = 28,
}: {
  points: number[];
  state?: StateKey;
  width?: number;
  height?: number;
}) {
  const color = STATE[state].color;
  const max = Math.max(...points);
  const min = Math.min(...points);
  const flat = points.length < 2 || max === min;

  // Not enough history, or a value that hasn't moved, is drawn as a quiet
  // baseline. Filling the area under a flat series would render a solid block
  // and read as a bug rather than as "nothing has changed yet".
  if (flat) {
    return (
      <svg width={width} height={height} className="overflow-visible" aria-label="no change">
        <line
          x1={0}
          y1={height / 2}
          x2={width}
          y2={height / 2}
          stroke={color}
          strokeOpacity={0.35}
          strokeWidth={1}
          strokeDasharray="2 4"
        />
      </svg>
    );
  }

  const span = max - min;
  const step = width / (points.length - 1);
  const d = points
    .map((p, i) => `${i === 0 ? "M" : "L"} ${(i * step).toFixed(1)} ${(height - ((p - min) / span) * height).toFixed(1)}`)
    .join(" ");
  return (
    <svg width={width} height={height} className="overflow-visible">
      <path d={`${d} L ${width} ${height} L 0 ${height} Z`} fill={color} opacity={0.1} />
      <path d={d} fill="none" stroke={color} strokeWidth={1.25} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

/**
 * A subsystem that exists in the topology but is not present in this build.
 * Shown rather than hidden so the system's real shape stays legible.
 */
export function NotInstalled({ name, note }: { name: string; note?: string }) {
  return (
    <div className="flex items-center gap-2 rounded border border-dashed border-edge/70 px-3 py-2">
      <StateDot state="offline" />
      <span className="text-xs text-slate-500">{name}</span>
      <span className="ml-auto micro">Not installed</span>
      {note && <span className="sr-only">{note}</span>}
    </div>
  );
}

/**
 * The console's one on/off control.
 *
 * <p>Every feature that can be turned on used to draw its own switch, so they
 * drifted — and two subsystems (the prompt firewall and prompt compression)
 * shipped a working backend toggle with no control at all, which meant the only
 * way to enable them was curl.
 *
 * <p>{@code locked} is the important part. Some settings change engine-wide
 * behaviour and belong to the operator, so a developer's click would come back
 * 403. Rather than hide the control or let it fail, it renders visibly locked
 * and says what would unlock it.
 */
export function Switch({
  checked,
  onChange,
  label,
  hint,
  busy = false,
  locked,
  onUnlock,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  hint?: string;
  busy?: boolean;
  /** Why this control cannot be used right now; omit when it is usable. */
  locked?: string;
  /** Offered alongside {@code locked} as the way out. */
  onUnlock?: () => void;
}) {
  const disabled = busy || locked !== undefined;
  return (
    <div className="flex min-w-0 items-start gap-3">
      <button
        type="button"
        role="switch"
        aria-checked={checked}
        aria-label={label}
        disabled={disabled}
        onClick={() => onChange(!checked)}
        className={`mt-0.5 inline-flex h-5 w-9 shrink-0 items-center rounded-full border transition-colors ${
          checked ? "border-aurora/60 bg-aurora/70" : "border-edge bg-edge/40"
        } ${disabled ? "cursor-not-allowed opacity-50" : "hover:border-aurora/60"}`}
      >
        <span
          className={`h-3.5 w-3.5 rounded-full bg-white shadow transition-transform ${
            checked ? "translate-x-[18px]" : "translate-x-[3px]"
          }`}
        />
      </button>
      <div className="min-w-0">
        <div className="text-sm font-medium text-slate-200">{label}</div>
        {hint && <p className="mt-0.5 text-xs text-slate-500">{hint}</p>}
        {locked && (
          <p className="mt-1 text-xs text-amber-400/90">
            {locked}
            {onUnlock && (
              <>
                {" "}
                <button onClick={onUnlock} className="underline underline-offset-2 hover:text-amber-300">
                  Unlock
                </button>
              </>
            )}
          </p>
        )}
      </div>
    </div>
  );
}
