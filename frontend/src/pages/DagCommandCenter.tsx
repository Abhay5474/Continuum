import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api, portal } from "../api";
import { Micro, Readout, Plane, StateDot } from "../system/primitives";
import { STATE, type StateKey } from "../system/tokens";
import DataView from "../system/DataView";
import FeatureToggle from "../system/FeatureToggle";
import { elapsed, humanMs } from "../system/time";

/**
 * Verification — the agent constellation.
 *
 * A question is decomposed into claims; each claim gets a solver, each solver a
 * set of verifiers, and the evidence is aggregated in log-odds space into a
 * single confidence. That pipeline is a layered DAG, so it is drawn as one:
 * evidence flows left to right and the constellation collapses into a verdict.
 *
 * Contradictions are the interesting part, so they are drawn as tension — a
 * curved dashed edge into an explicit conflict node — rather than as another
 * grey line.
 */

type Node = {
  nodeKey: string;
  nodeType: string;
  claimId: number | null;
  label: string;
  status: string;
  validity: number | null;
  outputJson: string | null;
  startedAt: string | null;
  completedAt: string | null;
};
type Edge = { fromKey: string; toKey: string; edgeType: string; weight: number };

/** Pipeline order — the x axis of the constellation. */
const LAYER: Record<string, number> = {
  PLANNER: 0,
  SOLVER: 1,
  CONFLICT: 2,
  VERIFIER: 3,
  AGGREGATOR: 4,
  SYNTHESIS: 5,
};
const LAYER_LABEL = ["Plan", "Solve", "Conflict", "Verify", "Aggregate", "Answer"];

/** Short code shown inside a node when it has no claim number of its own. */
const TYPE_CODE: Record<string, string> = {
  PLANNER: "P",
  AGGREGATOR: "\u03A3", // sigma — evidence summed in log-odds space
  SYNTHESIS: "A",
  CONFLICT: "!",
};

/** Caption under a node: the verifier's check name, or the stage. */
function caption(n: Node): string {
  if (n.nodeType === "VERIFIER") return (n.label.split("\u00B7")[0] || "check").trim().toLowerCase().replace(/_/g, " ");
  if (n.nodeType === "SOLVER") return `claim ${n.claimId}`;
  if (n.nodeType === "PLANNER") return "decompose";
  if (n.nodeType === "AGGREGATOR") return "bayesian";
  if (n.nodeType === "SYNTHESIS") return "answer";
  if (n.nodeType === "CONFLICT") return "conflict";
  return n.nodeType.toLowerCase();
}

function statusState(status: string): StateKey {
  if (status === "PASS" || status === "DONE") return "healthy";
  if (status === "FAIL") return "critical";
  if (status === "UNCERTAIN") return "warning";
  return "idle";
}

export default function DagCommandCenter() {
  const { workflowId } = useParams();
  const [runs, setRuns] = useState<any[]>([]);

  useEffect(() => {
    const load = () => api.get<any[]>("/api/dag/runs").then(setRuns).catch(() => {});
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, []);

  if (workflowId) return <Constellation workflowId={workflowId} />;

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <h1 className="text-lg font-semibold tracking-tight">Verification</h1>
          <p className="mt-0.5 text-sm text-slate-500 max-w-2xl leading-relaxed">
            Claims solved in parallel · verified independently · resolved by Bayesian aggregation
          </p>
        </div>
        <FeatureToggle status={portal.v6.status} enable={portal.v6.enable} disable={portal.v6.disable} />
      </header>

      {runs.length === 0 ? (
        <div className="text-center">
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">No verification runs</h2>
          <p className="mx-auto mt-2 max-w-md text-sm text-slate-400">
Turn it on above, then send a gateway request.
          </p>
        </div>
      ) : (
        <section>
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Runs · newest first</h2>
          <div className="mt-2 divide-y divide-edge/50">
            {runs.map((r) => {
              const conf = r.finalConfidence ?? 0;
              const s: StateKey =
                r.status !== "COMPLETED"
                  ? "active"
                  : conf >= 0.85
                  ? "healthy"
                  : conf >= 0.6
                  ? "warning"
                  : "critical";
              return (
                <Link
                  key={r.workflowId}
                  to={`/dag/${r.workflowId}`}
                  className="flex flex-wrap items-center gap-x-4 gap-y-1 px-2 py-3 transition-colors hover:bg-edge/40"
                >
                  <StateDot state={s} />
                  <span className="min-w-0 flex-1 truncate text-sm text-slate-300">
                    {r.prompt || <span className="text-slate-600">no prompt recorded</span>}
                  </span>
                  <span className="readout text-sm font-semibold" style={{ color: STATE[s].ink }}>
                    {r.finalConfidence != null ? `${(conf * 100).toFixed(1)}%` : "—"}
                  </span>
                  <span className="micro w-20 text-right">{r.uncertainty ?? "—"}</span>
                  <span className="readout w-24 text-right text-[10px] text-slate-600">
                    {r.claimCount} claims · {r.nodeCount} nodes
                  </span>
                </Link>
              );
            })}
          </div>
        </section>
      )}
    </div>
  );
}

function Constellation({ workflowId }: { workflowId: string }) {
  const [trace, setTrace] = useState<any | null>(null);
  const [sel, setSel] = useState<string | null>(null);

  useEffect(() => {
    const load = () => api.get<any>(`/api/dag/trace/${workflowId}`).then(setTrace).catch(() => {});
    load();
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, [workflowId]);

  const run = trace?.run;
  const nodes: Node[] = trace?.nodes ?? [];
  const edges: Edge[] = trace?.edges ?? [];

  const W = 1000;
  const H = 560;

  // ---- layout: pipeline stage on x, claim grouping on y ----
  const laid = useMemo(() => {
    if (!nodes.length) return [];
    const byLayer = new Map<number, Node[]>();
    nodes.forEach((n) => {
      const l = LAYER[n.nodeType] ?? 1;
      if (!byLayer.has(l)) byLayer.set(l, []);
      byLayer.get(l)!.push(n);
    });
    // Keep a claim's nodes vertically aligned so a column reads as one claim.
    byLayer.forEach((list) =>
      list.sort((a, b) => (a.claimId ?? 99) - (b.claimId ?? 99) || a.nodeKey.localeCompare(b.nodeKey))
    );
    const maxLayer = LAYER_LABEL.length - 1;
    const out: (Node & { x: number; y: number })[] = [];
    byLayer.forEach((list, layer) => {
      const x = 70 + (layer / maxLayer) * (W - 150);
      list.forEach((n, i) => {
        const y = list.length === 1 ? H / 2 : 70 + (i / (list.length - 1)) * (H - 140);
        out.push({ ...n, x, y });
      });
    });
    return out;
  }, [nodes]);

  const posOf = useMemo(() => {
    const m = new Map<string, { x: number; y: number }>();
    laid.forEach((n) => m.set(n.nodeKey, { x: n.x, y: n.y }));
    return m;
  }, [laid]);

  const selected = laid.find((n) => n.nodeKey === sel) ?? null;
  const conf = run?.finalConfidence ?? 0;
  const confState: StateKey = conf >= 0.85 ? "healthy" : conf >= 0.6 ? "warning" : "critical";

  const contradictions = nodes.filter((n) => n.nodeType === "CONFLICT").length;
  const verifiers = nodes.filter((n) => n.nodeType === "VERIFIER");
  const passed = verifiers.filter((n) => n.status === "PASS").length;
  const risks: string[] = useMemo(() => {
    try {
      const v = run?.riskFlagsJson ? JSON.parse(run.riskFlagsJson) : [];
      return Array.isArray(v) ? v.map(String) : [];
    } catch {
      return [];
    }
  }, [run]);

  return (
    <div className="space-y-5">
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <Link to="/dag" className="micro transition-colors hover:text-slate-300">
            ← All runs
          </Link>
          <h1 className="mt-1 truncate text-lg font-semibold tracking-tight">
            {run?.prompt || "Verification run"}
          </h1>
          <span className="font-mono text-[10px] text-slate-600">{workflowId}</span>
        </div>
        {run && (
          <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
            <Readout label="Confidence" value={`${(conf * 100).toFixed(1)}%`} state={confState} />
            <Readout label="Uncertainty" value={run.uncertainty ?? "—"} size="sm" />
            <Readout label="Claims" value={run.claimCount} size="sm" />
            <Readout
              label="Verifiers passed"
              value={`${passed}/${verifiers.length}`}
              size="sm"
              state={verifiers.length && passed === verifiers.length ? "healthy" : "warning"}
            />
            <Readout label="Contradictions" value={contradictions} size="sm"
              state={contradictions > 0 ? "warning" : "idle"} />
          </div>
        )}
      </header>

      {!trace ? (
        <Plane className="p-8 text-center text-sm text-slate-500">Loading constellation…</Plane>
      ) : (
        <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_400px]">
          <section>
            <div className="grid-field rounded-lg border border-edge/60">
              <svg viewBox={`0 0 ${W} ${H}`} className="w-full" onClick={() => setSel(null)}>
                {/* pipeline stage guides */}
                {LAYER_LABEL.map((l, i) => {
                  const x = 70 + (i / (LAYER_LABEL.length - 1)) * (W - 150);
                  const occupied = laid.some((n) => (LAYER[n.nodeType] ?? 1) === i);
                  return (
                    <g key={l} opacity={occupied ? 1 : 0.45}>
                      <line x1={x} y1={34} x2={x} y2={H - 24} stroke="currentColor"
                        className="text-slate-700" strokeOpacity={0.18} strokeDasharray="2 8" />
                      <text x={x} y={22} textAnchor="middle" className="text-[9px]"
                        style={{ letterSpacing: "0.14em", fill: "rgb(var(--topo-label))" }}>
                        {l.toUpperCase()}
                      </text>
                      {!occupied && (
                        <text x={x} y={H / 2} textAnchor="middle" className="text-[9px]"
                          style={{ fill: "rgb(var(--topo-label-off))" }}>
                          none
                        </text>
                      )}
                    </g>
                  );
                })}

                {/* ---- evidence edges ---- */}
                {edges.map((e, i) => {
                  const a = posOf.get(e.fromKey);
                  const b = posOf.get(e.toKey);
                  if (!a || !b) return null;
                  const contradicts = e.edgeType === "CONTRADICTS";
                  const supports = e.edgeType === "SUPPORTS";
                  const dim = sel !== null && sel !== e.fromKey && sel !== e.toKey;
                  const color = contradicts
                    ? STATE.critical.color
                    : supports
                    ? STATE.healthy.color
                    : STATE.active.color;
                  // Contradictions bow away from the flow so tension is visible.
                  const mx = (a.x + b.x) / 2;
                  const my = (a.y + b.y) / 2 + (contradicts ? -46 : 0);
                  return (
                    <path
                      key={i}
                      d={`M ${a.x} ${a.y} Q ${mx} ${my} ${b.x} ${b.y}`}
                      fill="none"
                      stroke={color}
                      strokeOpacity={dim ? 0.07 : contradicts ? 0.65 : 0.3 + e.weight * 0.35}
                      strokeWidth={contradicts ? 1.4 : 0.8 + e.weight * 1.2}
                      strokeDasharray={contradicts ? "4 4" : undefined}
                      style={{ transition: "stroke-opacity 320ms" }}
                    />
                  );
                })}

                {/* ---- agent nodes ---- */}
                {laid.map((n) => {
                  const st = statusState(n.status);
                  const color = STATE[st].color;
                  const isSel = sel === n.nodeKey;
                  const dim = sel !== null && !isSel;
                  const isTerminal = n.nodeType === "AGGREGATOR" || n.nodeType === "SYNTHESIS";
                  const r = isTerminal ? 26 : n.nodeType === "CONFLICT" ? 14 : 18;
                  return (
                    <g
                      key={n.nodeKey}
                      onClick={(ev) => {
                        ev.stopPropagation();
                        setSel(isSel ? null : n.nodeKey);
                      }}
                      className="cursor-pointer"
                      opacity={dim ? 0.28 : 1}
                      style={{ transition: "opacity 320ms" }}
                    >
                      {n.nodeType === "CONFLICT" && (
                        <circle cx={n.x} cy={n.y} r={6} fill={color} className="incident-pulse" />
                      )}
                      {isSel && (
                        <circle cx={n.x} cy={n.y} r={r + 7} fill="none" stroke={color} strokeOpacity={0.5} />
                      )}
                      <circle cx={n.x} cy={n.y} r={r} style={{ fill: "rgb(var(--topo-node))" }} fillOpacity={0.95} />
                      <circle cx={n.x} cy={n.y} r={r} fill="none" stroke={color}
                        strokeOpacity={0.75} strokeWidth={isSel ? 1.8 : 1.1} />
                      {/* validity arc — how strong this node's evidence is */}
                      {n.validity != null && (
                        <circle
                          cx={n.x}
                          cy={n.y}
                          r={r - 4}
                          fill="none"
                          stroke={color}
                          strokeWidth={2.5}
                          strokeLinecap="round"
                          strokeDasharray={`${(2 * Math.PI * (r - 4) * Math.max(0, Math.min(1, n.validity))).toFixed(1)} 999`}
                          transform={`rotate(-90 ${n.x} ${n.y})`}
                        />
                      )}
                      {isTerminal && n.validity != null && (
                        <text x={n.x} y={n.y + 4} textAnchor="middle" className="text-[11px] font-semibold"
                          style={{ fill: "rgb(var(--topo-text))" }}>
                          {(n.validity * 100).toFixed(0)}
                        </text>
                      )}
                      {n.claimId != null && !isTerminal && (
                        <text x={n.x} y={n.y + 4} textAnchor="middle" className="text-[10px] font-semibold"
                          style={{ fill: "rgb(var(--topo-text))" }}>
                          {n.claimId}
                        </text>
                      )}
                      {n.claimId == null && !(isTerminal && n.validity != null) && (
                        <text x={n.x} y={n.y + 4} textAnchor="middle" className="text-[11px] font-semibold"
                          style={{ fill: "rgb(var(--topo-text))" }}>
                          {TYPE_CODE[n.nodeType] ?? ""}
                        </text>
                      )}
                      <text x={n.x} y={n.y + r + 13} textAnchor="middle" className="text-[9px]"
                        style={{ fill: "rgb(var(--topo-label))" }}>
                        {caption(n)}
                      </text>
                    </g>
                  );
                })}
              </svg>
            </div>

            <div className="mt-2 flex flex-wrap items-center gap-4">
              <Legend color={STATE.healthy.color} label="passed" />
              <Legend color={STATE.warning.color} label="uncertain" />
              <Legend color={STATE.critical.color} label="failed / contradiction" />
              <span className="text-[10px] text-slate-500">arc = evidence strength · claim number inside solvers and verifiers · confidence inside the aggregate</span>
            </div>

            {run?.verdict && (
              <div className="mt-4">
                <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Synthesised answer</h2>
                <Plane inset className="mt-2 p-4">
                  <p className="whitespace-pre-wrap text-[13px] leading-relaxed text-slate-300">{run.verdict}</p>
                </Plane>
              </div>
            )}
          </section>

          {/* ---- evidence inspector ---- */}
          <aside className="space-y-4">
            <Micro>Evidence</Micro>
            {selected ? (
              <div className="settle space-y-3">
                <div className="flex flex-wrap items-center gap-2">
                  <StateDot state={statusState(selected.status)} />
                  <span className="text-sm font-semibold text-slate-200">{selected.nodeType}</span>
                  {selected.claimId != null && <span className="micro">claim {selected.claimId}</span>}
                </div>
                <p className="text-[12px] leading-relaxed text-slate-300">{selected.label}</p>
                <div className="space-y-2">
                  <Row k="Status" v={selected.status} />
                  {selected.validity != null && (
                    <Row k="Evidence strength" v={`${(selected.validity * 100).toFixed(1)}%`} />
                  )}
                  {selected.startedAt && selected.completedAt && (
                    <Row
                      k="Duration"
                      v={humanMs(elapsed(selected.startedAt, selected.completedAt))}
                    />
                  )}
                </div>
                {selected.outputJson && <Output json={selected.outputJson} />}
              </div>
            ) : (
              <div className="text-[11px] leading-relaxed text-slate-500">
                Select a node to read the evidence it produced.
              </div>
            )}

            {risks.length > 0 && (
              <div>
                <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Risk flags</h2>
                <div className="mt-2 space-y-1">
                  {risks.map((r, i) => (
                    <div key={i} className="flex items-start gap-2 text-[11px] text-slate-400">
                      <span className="mt-1">
                        <StateDot state="warning" size={6} />
                      </span>
                      <span>{r}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}
          </aside>
        </div>
      )}
    </div>
  );
}

function Output({ json }: { json: string }) {
  const parsed = useMemo(() => {
    try {
      return JSON.parse(json);
    } catch {
      return null;
    }
  }, [json]);
  if (!parsed || typeof parsed !== "object") return null;
  return (
    <div>
      <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Output</h2>
      <Plane inset className="mt-1.5 max-h-[420px] overflow-y-auto p-3">
        <DataView value={parsed} />
      </Plane>
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex items-baseline justify-between border-b border-edge/40 pb-1.5">
      <span className="micro">{k}</span>
      <span className="readout text-xs text-slate-200">{v}</span>
    </div>
  );
}

function Legend({ color, label }: { color: string; label: string }) {
  return (
    <span className="flex items-center gap-1.5 text-[10px] text-slate-500">
      <span className="h-1.5 w-1.5 rounded-full" style={{ background: color }} />
      {label}
    </span>
  );
}
