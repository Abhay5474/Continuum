import { useMemo } from "react";
import { STATE, type StateKey, MOTION } from "./tokens";

/**
 * The Continuum Core — the central spatial metaphor of the interface.
 *
 * Subsystems are arranged around a core and connected by signal paths. Nothing
 * here is decorative: a node's colour is its measured state, a node breathes only
 * while it is carrying traffic, and a path only emits signal packets when the
 * telemetry for that subsystem shows real activity. An idle system renders as a
 * still diagram; a working one comes alive on its own.
 */

export type Subsystem = {
  id: string;
  name: string;
  /** Two-letter instrument code shown inside the node. */
  code: string;
  state: StateKey;
  installed: boolean;
  /** 0..1 — how much traffic this path is carrying right now. */
  flow: number;
  /** Direction of travel relative to the core. */
  direction: "in" | "out" | "both";
  /** One-line summary shown on hover/selection. */
  summary: string;
  /** Key readings, rendered in the inspector. */
  metrics: { label: string; value: string }[];
  /** Route to the full subsystem experience. */
  route?: string;
};

const W = 1000;
const H = 620;
const CX = W / 2;
const CY = H / 2;
const R = 218; // orbital radius
const NODE_R = 34;

/** Fixed angular slots, so a subsystem never moves between renders. */
const ANGLES: Record<string, number> = {
  runtime: -90,
  router: -45,
  providers: 0,
  consensus: 45,
  cache: 90,
  memory: 135,
  mmu: 180,
  firewall: -135,
};

function pos(id: string, radius = R) {
  const a = ((ANGLES[id] ?? 0) * Math.PI) / 180;
  return { x: CX + Math.cos(a) * radius, y: CY + Math.sin(a) * radius };
}

export default function ContinuumCore({
  subsystems,
  coreState,
  load,
  selected,
  onSelect,
}: {
  subsystems: Subsystem[];
  coreState: StateKey;
  /** 0..1 — overall system load; drives the core's rhythm. */
  load: number;
  selected: string | null;
  onSelect: (id: string | null) => void;
}) {
  // Core rhythm: busier system breathes faster and rotates faster. Bounded so it
  // never becomes agitated.
  const breath = `${(5.5 - Math.min(1, load) * 3).toFixed(2)}s`;
  const spin = `${(150 - Math.min(1, load) * 90).toFixed(0)}s`;
  const coreColor = STATE[coreState].color;

  const edges = useMemo(
    () =>
      subsystems.map((s) => {
        const p = pos(s.id);
        // Stop the path at the node/core boundary so signals don't run under them.
        const dx = p.x - CX;
        const dy = p.y - CY;
        const len = Math.hypot(dx, dy);
        const ux = dx / len;
        const uy = dy / len;
        const innerR = 92;
        const from = { x: CX + ux * innerR, y: CY + uy * innerR };
        const to = { x: p.x - ux * NODE_R, y: p.y - uy * NODE_R };
        // Quantised so small telemetry jitter doesn't restart the animations.
        const packets = s.installed ? Math.min(3, Math.round(s.flow * 3)) : 0;
        return { s, p, from, to, packets };
      }),
    [subsystems]
  );

  return (
    <svg
      viewBox={`0 0 ${W} ${H}`}
      className="w-full select-none"
      style={{ maxHeight: "70vh", minHeight: 260 }}
      role="img"
      aria-label="Continuum system topology"
      onClick={() => onSelect(null)}
    >
      <defs>
        <radialGradient id="coreHalo">
          <stop offset="0%" stopColor={coreColor} stopOpacity="0.22" />
          <stop offset="55%" stopColor={coreColor} stopOpacity="0.06" />
          <stop offset="100%" stopColor={coreColor} stopOpacity="0" />
        </radialGradient>
        <filter id="soften" x="-50%" y="-50%" width="200%" height="200%">
          <feGaussianBlur stdDeviation="3" />
        </filter>
      </defs>

      {/* Ambient light behind the core establishes depth without a shadow. */}
      <circle cx={CX} cy={CY} r={300} fill="url(#coreHalo)" />

      {/* Orbital guide — the spatial field the subsystems sit on. */}
      <circle
        cx={CX}
        cy={CY}
        r={R}
        fill="none"
        stroke="currentColor"
        className="text-slate-700"
        strokeOpacity={0.25}
        strokeDasharray="2 6"
      />

      {/* ---- signal paths ---- */}
      {edges.map(({ s, from, to, packets }) => {
        const dim = selected !== null && selected !== s.id;
        const color = s.installed ? STATE[s.state].color : STATE.offline.color;
        const path = `M ${from.x} ${from.y} L ${to.x} ${to.y}`;
        // Packets travel toward whichever end the data is actually going.
        const motionPath =
          s.direction === "out" ? path : `M ${to.x} ${to.y} L ${from.x} ${from.y}`;
        return (
          <g key={s.id} opacity={dim ? 0.18 : 1} style={{ transition: `opacity ${MOTION.base}ms` }}>
            <path
              d={path}
              stroke={color}
              strokeOpacity={s.installed ? 0.28 : 0.16}
              strokeWidth={1}
              strokeDasharray={s.installed ? undefined : "3 5"}
              fill="none"
            />
            {Array.from({ length: packets }).map((_, i) => (
              <circle key={`${s.id}-p${i}`} r={2.6} fill={color}>
                <animateMotion
                  dur={`${MOTION.packetSeconds}s`}
                  begin={`${(i * MOTION.packetSeconds) / packets}s`}
                  repeatCount="indefinite"
                  path={motionPath}
                />
              </circle>
            ))}
            {/* A bidirectional path also returns a fainter acknowledgement. */}
            {s.direction === "both" && packets > 0 && (
              <circle r={1.8} fill={color} opacity={0.5}>
                <animateMotion
                  dur={`${MOTION.packetSeconds * 1.35}s`}
                  repeatCount="indefinite"
                  path={path}
                />
              </circle>
            )}
          </g>
        );
      })}

      {/* ---- the core ---- */}
      <g
        onClick={(e) => {
          e.stopPropagation();
          onSelect("core");
        }}
        className="cursor-pointer"
        opacity={selected && selected !== "core" ? 0.35 : 1}
        style={{ transition: `opacity ${MOTION.base}ms` }}
      >
        <g style={{ ["--spin" as string]: spin }}>
          <circle
            className="core-spin"
            cx={CX}
            cy={CY}
            r={84}
            fill="none"
            stroke={coreColor}
            strokeOpacity={0.35}
            strokeWidth={1}
            strokeDasharray="1 9"
          />
          <circle
            className="core-spin-rev"
            cx={CX}
            cy={CY}
            r={70}
            fill="none"
            stroke={coreColor}
            strokeOpacity={0.22}
            strokeWidth={1}
            strokeDasharray="34 12"
          />
        </g>
        <g style={{ ["--breath" as string]: breath }} className="core-breath" transform-origin={`${CX} ${CY}`}>
          <polygon
            points={hexagon(CX, CY, 52)}
            fill={coreColor}
            fillOpacity={0.07}
            stroke={coreColor}
            strokeOpacity={0.55}
            strokeWidth={1.25}
          />
          <polygon points={hexagon(CX, CY, 34)} fill={coreColor} fillOpacity={0.12} />
        </g>
        <text
          x={CX}
          y={CY - 2}
          textAnchor="middle"
          className="text-[13px] font-semibold"
          style={{ letterSpacing: "0.16em", fill: "rgb(var(--topo-text))" }}
        >
          CORE
        </text>
        <text x={CX} y={CY + 14} textAnchor="middle" className="text-[9px]" style={{ letterSpacing: "0.1em", fill: "rgb(var(--topo-label))" }}>
          {STATE[coreState].label.toUpperCase()}
        </text>
      </g>

      {/* ---- subsystem nodes ---- */}
      {edges.map(({ s, p }) => {
        const isSel = selected === s.id;
        const dim = selected !== null && !isSel;
        const color = s.installed ? STATE[s.state].color : STATE.offline.color;
        const attention = s.state === "degraded" || s.state === "critical";
        return (
          <g
            key={s.id}
            onClick={(e) => {
              e.stopPropagation();
              onSelect(isSel ? null : s.id);
            }}
            className="cursor-pointer"
            opacity={dim ? 0.3 : 1}
            style={{ transition: `opacity ${MOTION.base}ms` }}
          >
            {/* An incident radiates from the node it originates at. */}
            {attention && (
              <circle cx={p.x} cy={p.y} r={6} fill={color} className="incident-pulse" />
            )}
            {isSel && (
              <circle cx={p.x} cy={p.y} r={NODE_R + 9} fill="none" stroke={color} strokeOpacity={0.45} strokeWidth={1} />
            )}
            <circle cx={p.x} cy={p.y} r={NODE_R} style={{ fill: "rgb(var(--topo-node))" }} fillOpacity={0.95} />
            <circle
              cx={p.x}
              cy={p.y}
              r={NODE_R}
              fill="none"
              stroke={color}
              strokeOpacity={s.installed ? 0.7 : 0.35}
              strokeWidth={isSel ? 1.75 : 1.1}
              strokeDasharray={s.installed ? undefined : "4 4"}
              className={attention ? "degrading" : ""}
            />
            {/* Flow arc: how much of this subsystem's capacity is in use. */}
            {s.installed && s.flow > 0.02 && (
              <circle
                cx={p.x}
                cy={p.y}
                r={NODE_R - 5}
                fill="none"
                stroke={color}
                strokeWidth={2}
                strokeOpacity={0.8}
                strokeLinecap="round"
                strokeDasharray={`${(2 * Math.PI * (NODE_R - 5) * Math.min(1, s.flow)).toFixed(1)} 999`}
                transform={`rotate(-90 ${p.x} ${p.y})`}
                style={{ transition: `stroke-dasharray ${MOTION.slow}ms ${MOTION.ease}` }}
              />
            )}
            <text
              x={p.x}
              y={p.y + 4}
              textAnchor="middle"
              className="text-[12px] font-semibold"
              style={{
                letterSpacing: "0.08em",
                fill: s.installed ? "rgb(var(--topo-text))" : "rgb(var(--topo-label-off))",
              }}
            >
              {s.code}
            </text>
            <text
              x={p.x}
              y={p.y + NODE_R + 15}
              textAnchor="middle"
              className="text-[10px]"
              style={{
                letterSpacing: "0.08em",
                fill: s.installed ? "rgb(var(--topo-label))" : "rgb(var(--topo-label-off))",
              }}
            >
              {s.name}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

function hexagon(cx: number, cy: number, r: number) {
  return Array.from({ length: 6 })
    .map((_, i) => {
      const a = (Math.PI / 3) * i - Math.PI / 2;
      return `${(cx + Math.cos(a) * r).toFixed(2)},${(cy + Math.sin(a) * r).toFixed(2)}`;
    })
    .join(" ");
}
