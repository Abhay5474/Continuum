import type { ReactNode } from "react";
import { STATE, type StateKey } from "./tokens";

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
  return (
    <div title={hint}>
      <Micro>{label}</Micro>
      <div className="mt-1 flex items-baseline gap-1">
        <span
          className={`readout font-semibold ${sizes[size]}`}
          style={{ color: state === "idle" ? undefined : STATE[state].color }}
        >
          {value}
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
