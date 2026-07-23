import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api";

/**
 * V6 — Execution Command Center: the mission-control trace view for
 * ConsensusDag workflow runs ONLY. Center canvas = the live probabilistic
 * decision graph; left = Causal Inspector; right = Risk + Confidence engine;
 * top = run strip with the time-travel scrubber and "Collapse to Truth".
 */
export default function DagCommandCenter() {
  const { workflowId } = useParams();
  return workflowId ? <TraceView workflowId={workflowId} /> : <RunList />;
}

/* ---------------- run list (entry point) ---------------- */

function RunList() {
  const [runs, setRuns] = useState<any[]>([]);
  useEffect(() => {
    const load = () => api.get<any[]>("/api/dag/runs").then(setRuns).catch(() => {});
    load();
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, []);
  return (
    <div className="space-y-4">
      <div>
        <h1 className="text-lg font-semibold text-gradient">V6 — Execution Command Center</h1>
        <p className="text-sm text-slate-400">
          Every request verified by the Consensus DAG Engine leaves a full forensic trace: solver and
          verifier nodes, the contradiction graph, and the Bayesian resolution. Select a run to open
          its command center. (Enable the engine per developer in the Developer Portal.)
        </p>
      </div>
      <div className="glass divide-y divide-edge/60">
        {runs.map((r) => (
          <Link key={r.id} to={`/dag/${r.workflowId}`}
            className="flex flex-wrap items-center gap-3 px-4 py-3 transition-colors hover:bg-aurora/5">
            <span className={`rounded px-2 py-0.5 text-[10px] font-bold ${
              r.status === "COMPLETED" ? "bg-emerald-500/20 text-emerald-300"
                : r.status === "RUNNING" ? "bg-sky-500/20 text-sky-300 animate-pulse"
                : "bg-rose-500/20 text-rose-300"}`}>{r.status}</span>
            <span className="max-w-md truncate text-sm text-slate-300">{r.prompt}</span>
            {r.finalConfidence != null && (
              <span className="text-xs text-neon">{(r.finalConfidence * 100).toFixed(1)}%</span>
            )}
            <span className="text-xs text-slate-500">{r.claimCount} claims · {r.nodeCount} nodes</span>
            <span className="ml-auto font-mono text-[10px] text-slate-600">{r.workflowId}</span>
          </Link>
        ))}
        {runs.length === 0 && (
          <div className="px-4 py-8 text-center text-xs text-slate-500">
            No V6 runs yet. Enable the V6 Verification Engine in the Developer Portal, then send a
            gateway request — its verification trace will appear here.
          </div>
        )}
      </div>
    </div>
  );
}

/* ---------------- the command center ---------------- */

const TYPE_STYLE: Record<string, { fill: string; ring: string; icon: string }> = {
  PLANNER: { fill: "#1e293b", ring: "#94a3b8", icon: "◈" },
  SOLVER: { fill: "#172554", ring: "#3b82f6", icon: "🧠" },
  VERIFIER: { fill: "#1c1917", ring: "#eab308", icon: "⚖" },
  CONFLICT: { fill: "#450a0a", ring: "#ef4444", icon: "⚡" },
  AGGREGATOR: { fill: "#2e1065", ring: "#a855f7", icon: "Σ" },
  SYNTHESIS: { fill: "#022c22", ring: "#34d399", icon: "✍" },
};

function statusRing(node: any): string {
  if (node.nodeType === "VERIFIER") {
    return node.status === "PASS" ? "#34d399" : node.status === "FAIL" ? "#ef4444" : "#eab308";
  }
  if (node.status === "FAIL") return "#ef4444";
  return TYPE_STYLE[node.nodeType]?.ring ?? "#64748b";
}

function TraceView({ workflowId }: { workflowId: string }) {
  const [trace, setTrace] = useState<any | null>(null);
  const [selected, setSelected] = useState<any | null>(null);
  const [tab, setTab] = useState<"evidence" | "computation" | "dependencies">("evidence");
  const [collapsed, setCollapsed] = useState(false);
  const [cursor, setCursor] = useState(100); // time-travel percentage

  useEffect(() => {
    const load = () => api.get<any>(`/api/dag/trace/${workflowId}`).then(setTrace).catch(() => {});
    load();
    const t = setInterval(load, 3000);
    return () => clearInterval(t);
  }, [workflowId]);

  const nodes: any[] = trace?.nodes ?? [];
  const edges: any[] = trace?.edges ?? [];
  const run = trace?.run;

  // ---- layered layout ----
  const layout = useMemo(() => {
    const cols: Record<string, number> = {
      PLANNER: 70, SOLVER: 250, VERIFIER: 470, CONFLICT: 470, AGGREGATOR: 690, SYNTHESIS: 850,
    };
    const byCol: Record<number, any[]> = {};
    nodes.forEach((n) => {
      const x = cols[n.nodeType] ?? 470;
      (byCol[x] = byCol[x] ?? []).push(n);
    });
    const pos = new Map<string, { x: number; y: number }>();
    Object.entries(byCol).forEach(([x, list]) => {
      list.forEach((n, i) => {
        pos.set(n.nodeKey, { x: Number(x), y: 60 + ((520 - 120) * (i + 0.5)) / list.length });
      });
    });
    return pos;
  }, [nodes]);

  // ---- time travel: order nodes by start time ----
  const timeline = useMemo(() => {
    const stamps = nodes
      .map((n) => (n.startedAt ? new Date(n.startedAt).getTime() : null))
      .filter((t): t is number => t !== null)
      .sort((a, b) => a - b);
    return stamps;
  }, [nodes]);
  const cutoff = useMemo(() => {
    if (timeline.length === 0 || cursor >= 100) return Infinity;
    const idx = Math.floor((cursor / 100) * timeline.length);
    return idx <= 0 ? -Infinity : timeline[Math.min(idx, timeline.length) - 1];
  }, [cursor, timeline]);
  const visible = (n: any) => {
    if (cursor >= 100) return true;
    if (!n.startedAt) return cursor >= 99; // derived nodes appear at the end
    return new Date(n.startedAt).getTime() <= cutoff;
  };

  // ---- collapse to truth: the surviving reasoning spine ----
  const spine: Set<string> = useMemo(() => {
    const agg = nodes.find((n) => n.nodeKey === "aggregator");
    const keys = new Set<string>(["planner", "aggregator", "synthesis"]);
    try {
      const parsed = JSON.parse(agg?.outputJson ?? "{}");
      (parsed.spine ?? []).forEach((id: number) => keys.add(`solver-${id}`));
    } catch { /* no spine yet */ }
    return keys;
  }, [nodes]);
  const dimmed = (n: any) => collapsed && !spine.has(n.nodeKey);

  const riskFlags: string[] = useMemo(() => {
    try { return JSON.parse(run?.riskFlagsJson ?? "[]"); } catch { return []; }
  }, [run]);

  if (!trace || !run) {
    return <div className="py-16 text-center text-sm text-slate-500">Loading trace…</div>;
  }

  return (
    <div className="space-y-3">
      {/* ---- TOP BAR: mission control strip ---- */}
      <div className="glass flex flex-wrap items-center gap-3 px-4 py-2.5">
        <Link to="/dag" className="text-xs text-slate-400 hover:text-slate-200">← Runs</Link>
        <span className="font-mono text-xs text-slate-400">{run.workflowId}</span>
        <span className={`rounded px-2 py-0.5 text-[10px] font-bold ${
          run.status === "COMPLETED" ? "bg-emerald-500/20 text-emerald-300"
            : run.status === "RUNNING" ? "bg-sky-500/20 text-sky-300 animate-pulse"
            : "bg-rose-500/20 text-rose-300"}`}>{run.status}</span>
        <div className="mx-2 flex min-w-[220px] flex-1 items-center gap-2">
          <span className="text-[10px] uppercase tracking-wider text-slate-500">Time travel</span>
          <input type="range" min={0} max={100} value={cursor}
            onChange={(e) => setCursor(Number(e.target.value))}
            className="flex-1 accent-indigo-500" />
          <span className="w-9 text-right text-[10px] text-slate-500">{cursor}%</span>
        </div>
        <button onClick={() => setCollapsed(!collapsed)}
          className={`rounded-md px-3 py-1.5 text-xs font-semibold transition-all duration-300 ${
            collapsed
              ? "bg-gradient-to-r from-aurora to-neon text-ink shadow-glow"
              : "border border-aurora/50 text-indigo-300 hover:shadow-glow-sm"}`}>
          {collapsed ? "⟲ Expand multiverse" : "🔥 Collapse to Truth"}
        </button>
      </div>

      <div className="grid gap-3 lg:grid-cols-[280px_1fr_260px]">
        {/* ---- LEFT: causal inspector ---- */}
        <div className="glass max-h-[560px] overflow-y-auto p-3">
          <div className="text-xs font-semibold uppercase tracking-wider text-slate-400">Causal Inspector</div>
          {!selected ? (
            <div className="mt-6 text-center text-xs text-slate-500">
              Click any node on the canvas to inspect its inputs, structured outputs and evidence.
            </div>
          ) : (
            <div className="mt-2 space-y-2 animate-fade-up" key={selected.nodeKey}>
              <div className="flex items-center gap-2">
                <span className="text-lg">{TYPE_STYLE[selected.nodeType]?.icon}</span>
                <div>
                  <div className="text-sm font-medium">{selected.nodeKey}</div>
                  <div className="text-[10px] text-slate-500">{selected.nodeType}</div>
                </div>
              </div>
              <div className="flex items-center gap-2 text-xs">
                <span className={`rounded px-2 py-0.5 font-bold ${
                  selected.status === "PASS" || selected.status === "DONE"
                    ? "bg-emerald-500/20 text-emerald-300"
                    : selected.status === "FAIL" ? "bg-rose-500/20 text-rose-300"
                    : "bg-amber-500/20 text-amber-300"}`}>{selected.status}</span>
                {selected.validity != null && (
                  <span className="text-slate-400">validity {(selected.validity * 100).toFixed(0)}%</span>
                )}
              </div>
              <div className="text-xs text-slate-400">{selected.label}</div>
              <div className="flex gap-1">
                {(["evidence", "computation", "dependencies"] as const).map((t) => (
                  <button key={t} onClick={() => setTab(t)}
                    className={`rounded px-2 py-1 text-[10px] capitalize transition-colors ${
                      tab === t ? "bg-aurora/20 text-indigo-300" : "text-slate-500 hover:text-slate-300"}`}>
                    {t}
                  </button>
                ))}
              </div>
              <InspectorTab node={selected} tab={tab} edges={edges} />
            </div>
          )}
        </div>

        {/* ---- CENTER: the living decision graph ---- */}
        <div className="glass relative overflow-hidden p-1">
          <svg viewBox="0 0 920 520" className="h-[556px] w-full">
            <defs>
              <radialGradient id="nodeGlow">
                <stop offset="0%" stopColor="rgba(99,102,241,0.35)" />
                <stop offset="100%" stopColor="transparent" />
              </radialGradient>
            </defs>
            {/* edges: weighted probability flows with animated particles */}
            {edges.map((e: any, i: number) => {
              const a = layout.get(e.fromKey);
              const b = layout.get(e.toKey);
              const fromN = nodes.find((n) => n.nodeKey === e.fromKey);
              const toN = nodes.find((n) => n.nodeKey === e.toKey);
              if (!a || !b || !fromN || !toN || !visible(fromN) || !visible(toN)) return null;
              const isDim = collapsed && (!spine.has(e.fromKey) || !spine.has(e.toKey));
              const color = e.edgeType === "CONTRADICTS" ? "#f87171"
                : e.edgeType === "SUPPORTS" ? "#34d399"
                : e.edgeType === "DEPENDS" ? "#94a3b8" : "#6366f1";
              const mx = (a.x + b.x) / 2;
              const d = `M ${a.x} ${a.y} C ${mx} ${a.y}, ${mx} ${b.y}, ${b.x} ${b.y}`;
              return (
                <g key={i} style={{ opacity: isDim ? 0.06 : 1, transition: "opacity 600ms ease" }}>
                  <path d={d} fill="none" stroke={color} strokeOpacity={0.35}
                    strokeWidth={0.8 + e.weight * 2.4} />
                  {!isDim && (
                    <circle r={2.2} fill={color} style={{ filter: `drop-shadow(0 0 3px ${color})` }}>
                      <animateMotion dur={`${2.2 + (i % 5) * 0.4}s`} repeatCount="indefinite" path={d} />
                    </circle>
                  )}
                </g>
              );
            })}
            {/* nodes */}
            {nodes.map((n: any) => {
              const p = layout.get(n.nodeKey);
              if (!p || !visible(n)) return null;
              const style = TYPE_STYLE[n.nodeType] ?? TYPE_STYLE.PLANNER;
              const ring = statusRing(n);
              const justFailed = n.status === "FAIL" && cursor < 100;
              return (
                <g key={n.nodeKey} transform={`translate(${p.x},${p.y})`}
                  onClick={() => setSelected(n)} className="cursor-pointer"
                  style={{ opacity: dimmed(n) ? 0.1 : 1, transition: "opacity 600ms ease" }}>
                  {run.status === "RUNNING" && <circle r={26} fill="url(#nodeGlow)" className="animate-pulse" />}
                  <circle r={16} fill={style.fill} stroke={ring} strokeWidth={selected?.nodeKey === n.nodeKey ? 3 : 1.8}
                    className={justFailed ? "animate-pulse" : ""}
                    style={{ filter: `drop-shadow(0 0 ${collapsed && spine.has(n.nodeKey) ? 10 : 5}px ${ring}66)` }} />
                  <text textAnchor="middle" dy={5} fontSize={13}>{style.icon}</text>
                  {n.nodeType === "AGGREGATOR" && n.validity != null && (
                    <g transform="translate(-24, 22)">
                      <rect width={48} height={5} rx={2.5} fill="#1e2739" />
                      <rect width={48 * n.validity} height={5} rx={2.5} fill="#a855f7"
                        style={{ transition: "width 800ms ease" }} />
                    </g>
                  )}
                  <text textAnchor="middle" dy={n.nodeType === "AGGREGATOR" ? 40 : 30}
                    fontSize={7.5} fill="#64748b">
                    {n.nodeKey.length > 22 ? n.nodeKey.slice(0, 22) + "…" : n.nodeKey}
                  </text>
                </g>
              );
            })}
            {collapsed && (
              <text x={460} y={30} textAnchor="middle" fontSize={13} fill="#c4b5fd"
                className="animate-fade-up" style={{ filter: "drop-shadow(0 0 8px rgba(99,102,241,0.8))" }}>
                REASONING SPINE — final confidence {((run.finalConfidence ?? 0) * 100).toFixed(1)}%
              </text>
            )}
          </svg>
        </div>

        {/* ---- RIGHT: risk + confidence engine ---- */}
        <div className="glass space-y-3 p-3">
          <div className="text-xs font-semibold uppercase tracking-wider text-slate-400">Risk + Confidence</div>
          <div className="rounded-lg border border-edge bg-ink p-3 text-center">
            <div className="text-3xl font-bold"
              style={{ color: `hsl(${(run.finalConfidence ?? 0) * 140} 80% 60%)` }}>
              {run.finalConfidence != null ? `${(run.finalConfidence * 100).toFixed(1)}%` : "—"}
            </div>
            <div className="text-[10px] uppercase tracking-wider text-slate-500">Final confidence</div>
            <div className={`mt-1 inline-block rounded px-2 py-0.5 text-[10px] font-bold ${
              run.uncertainty === "LOW" ? "bg-emerald-500/20 text-emerald-300"
                : run.uncertainty === "MEDIUM" ? "bg-amber-500/20 text-amber-300"
                : "bg-rose-500/20 text-rose-300"}`}>
              Uncertainty: {run.uncertainty ?? "?"}
            </div>
          </div>
          <div className="space-y-1.5">
            {riskFlags.map((f, i) => (
              <div key={i} className={`rounded-md border px-2.5 py-1.5 text-xs ${
                f.startsWith("✔") ? "border-emerald-500/30 text-emerald-300"
                  : f.startsWith("✘") ? "border-rose-500/40 text-rose-300"
                  : "border-amber-500/40 text-amber-300"}`}>{f}</div>
            ))}
          </div>
          <div>
            <div className="text-[10px] uppercase tracking-wider text-slate-500">Verified answer</div>
            <pre className="mt-1 max-h-48 overflow-y-auto whitespace-pre-wrap rounded-md bg-ink p-2 text-[10px] leading-relaxed text-slate-300">
              {run.verdict ?? "(pending)"}
            </pre>
          </div>
        </div>
      </div>
    </div>
  );
}

function InspectorTab({ node, tab, edges }: { node: any; tab: string; edges: any[] }) {
  const output = useMemo(() => {
    try { return JSON.parse(node.outputJson ?? "null"); } catch { return node.outputJson; }
  }, [node]);

  if (tab === "dependencies") {
    const incoming = edges.filter((e) => e.toKey === node.nodeKey);
    const outgoing = edges.filter((e) => e.fromKey === node.nodeKey);
    return (
      <div className="space-y-1 text-[11px]">
        {incoming.map((e, i) => (
          <div key={`i${i}`} className="text-slate-400">
            ⬅ <span className="font-mono">{e.fromKey}</span>
            <span className={e.edgeType === "CONTRADICTS" ? "text-rose-400" : "text-slate-500"}> ({e.edgeType}, w={e.weight.toFixed(2)})</span>
          </div>
        ))}
        {outgoing.map((e, i) => (
          <div key={`o${i}`} className="text-slate-400">
            ➡ <span className="font-mono">{e.toKey}</span>
            <span className={e.edgeType === "CONTRADICTS" ? "text-rose-400" : "text-slate-500"}> ({e.edgeType}, w={e.weight.toFixed(2)})</span>
          </div>
        ))}
        {incoming.length + outgoing.length === 0 && <div className="text-slate-600">no edges</div>}
      </div>
    );
  }
  if (tab === "computation") {
    return (
      <div className="space-y-1 text-[11px] text-slate-400">
        {node.startedAt && <div>scheduled: {new Date(node.startedAt).toLocaleTimeString()}</div>}
        {node.completedAt && <div>completed: {new Date(node.completedAt).toLocaleTimeString()}</div>}
        {output?.provider && <div>provider: <span className="font-mono">{output.provider}/{output.model}</span></div>}
        {output?.tokens != null && <div>tokens: {output.tokens} · cost ${Number(output.costUsd ?? 0).toFixed(6)}</div>}
        {output?.confidence != null && <div>self-confidence: {(output.confidence * 100).toFixed(0)}%</div>}
        <pre className="mt-1 max-h-56 overflow-auto rounded bg-ink p-2 text-[10px] text-slate-300">
          {typeof output === "string" ? output : JSON.stringify(output, null, 2)}
        </pre>
      </div>
    );
  }
  // evidence tab
  const failureModes: string[] = output?.failureModes ?? [];
  const evidence: string[] = output?.evidence ?? output?.notes ?? [];
  return (
    <div className="space-y-1 text-[11px]">
      {failureModes.map((f, i) => (
        <div key={`f${i}`} className="rounded border border-rose-500/40 px-2 py-1 text-rose-300">✘ {f}</div>
      ))}
      {evidence.map((e, i) => (
        <div key={`e${i}`} className="rounded border border-edge px-2 py-1 text-slate-400">· {e}</div>
      ))}
      {output?.reasoning && (
        <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap rounded bg-ink p-2 text-[10px] text-slate-300">
          {output.reasoning}
        </pre>
      )}
      {failureModes.length + evidence.length === 0 && !output?.reasoning && (
        <div className="text-slate-600">no structured evidence recorded</div>
      )}
    </div>
  );
}
