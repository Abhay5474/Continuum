import { useEffect, useMemo, useRef, useState } from "react";
import { AnimatePresence, LayoutGroup, m } from "motion/react";
import { transitions } from "../system/physics";
import { visibleInterval } from "../system/poll";
import { Link, useLocation, useParams } from "react-router-dom";
import { api, portal } from "../api";
import { Chip, Explain, Pill } from "../system/hub";
import { Micro, Readout, Plane, StateDot } from "../system/primitives";
import { useHoverHighlight } from "../system/interaction/HoverHighlight";
import { STATE, type StateKey } from "../system/tokens";
import DataView from "../system/DataView";
import FeatureToggle from "../system/FeatureToggle";
import { elapsed, humanMs, timeOf } from "../system/time";
import { buildExecution, checkName, duration, parseJson, type ExecutionModel, type Trace, type Work } from "../system/execution/model";
import { findingsOf } from "../system/execution/findings";
import { Waterfall, WaterfallLegend, type WaterfallLink, type WaterfallRow } from "../components/Waterfall";
import { ExecutionFacts, Findings, TimeBudget } from "../components/RunDetail";

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
  // Short names: a column of eleven checks has room for "grounding", not
  // "evidence grounding", before one caption runs into the next node.
  if (n.nodeType === "VERIFIER") return checkName((n.label.split("\u00B7")[0] || "check").trim()).toLowerCase();
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

/**
 * The last list seen, so coming back to it draws it at once — and the run you
 * opened is there for its header to shrink back into — while it refreshes.
 */
let lastRuns: any[] = [];

export default function DagCommandCenter() {
  const { workflowId } = useParams();
  const [runs, setRuns] = useState<any[]>(lastRuns);
  const list = useRef<HTMLDivElement>(null);
  const { bind, highlight } = useHoverHighlight(list, "[data-row]");

  useEffect(() => {
    const load = () =>
      api.get<any[]>("/api/dag/runs").then((r) => {
        lastRuns = r;
        setRuns(r);
      }).catch(() => {});
    load();
    return visibleInterval(load, 5000);
  }, []);

  if (workflowId) return <Constellation workflowId={workflowId} />;

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-3">
            <Chip glyph="check" tone="accent" size={32} />
            <h1 className="text-[27px] font-semibold tracking-[-0.028em]">Verification Engine</h1>
          </div>
          <p className="mt-1.5 max-w-2xl text-[13.5px] leading-relaxed text-slate-500">
            Claims solved in parallel · verified independently · resolved by Bayesian aggregation
          </p>
        </div>
        <FeatureToggle
          label="Verification Engine"
          onText="Every gateway request is solved and verified as a graph"
          offText="Gateway answers go out unverified"
          status={portal.v6.status}
          enable={portal.v6.enable}
          disable={portal.v6.disable}
          guide="dag-toggle"
          useFor={["High-stakes outputs: SQL, infra config, payments", "Audit trails: finance, legal, healthcare", "Correctness over latency (seconds, not ms)", "Catching contradictions before execution"]}
          skipFor={["Latency-sensitive chat", "Creative, open-ended generation", "High-volume, low-risk traffic", "Already validated downstream"]}
        />
      </header>

      {runs.length === 0 ? (
        <div className="card p-10 text-center">
          <h2 className="text-[14px] font-semibold tracking-tight text-slate-200">No verification runs</h2>
          <p className="mx-auto mt-2 max-w-md text-sm text-slate-400">
            Turn it on above, then send a gateway request.
          </p>
        </div>
      ) : (
        <section>
          <div className="flex items-baseline justify-between px-1">
            <h2 className="text-[15px] font-semibold tracking-[-0.015em] text-slate-100">Runs</h2>
            <span className="text-[12px] text-slate-500">{runs.length} · newest first</span>
          </div>
          <div ref={list} className="card relative mt-3 p-1.5" {...bind}>
            {highlight}
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
                  state={{ title: r.prompt }}
                  data-row
                  data-morph
                  className="run-row press group relative z-[1] grid grid-cols-[auto_minmax(0,1fr)_auto] items-center gap-x-4 gap-y-1 rounded-[12px] px-3.5 py-3.5 md:grid-cols-[auto_minmax(0,1fr)_auto_auto_auto_auto]"
                >
                  <StateDot state={s} />
                  <span className="min-w-0">
                    <span data-morph-title className="block w-fit max-w-full truncate text-[14px] font-medium tracking-[-0.01em] text-slate-100">
                      {r.prompt || <span className="text-slate-500">no prompt recorded</span>}
                    </span>
                    <span className="readout mt-0.5 block text-[11.5px] text-slate-500">
                      {r.claimCount} claims · {r.nodeCount} nodes
                      <span className="md:hidden"> · {timeOf(r.createdAt)}</span>
                    </span>
                  </span>
                  {/* Confidence as a length, so a run to be wary of is found by
                      its short bar before its number is read. */}
                  <span className="flex items-center gap-2.5">
                    <span className="relative hidden h-1.5 w-24 overflow-hidden rounded-full sm:inline-block" style={{ background: "rgb(var(--card-rule))" }} aria-hidden>
                      <span className="absolute inset-y-0 left-0 rounded-full" style={{ width: `${conf * 100}%`, background: STATE[s].ink }} />
                    </span>
                    <span className="readout w-[52px] text-right text-[14px] font-semibold" style={{ color: STATE[s].ink }}>
                      {r.finalConfidence != null ? `${(conf * 100).toFixed(1)}%` : "—"}
                    </span>
                  </span>
                  <span className="hidden w-[112px] text-right md:block">
                    {r.uncertainty ? (
                      <Pill tone={r.uncertainty === "LOW" ? "ok" : r.uncertainty === "MEDIUM" ? "warn" : "bad"}>
                        {String(r.uncertainty).toLowerCase()} uncertainty
                      </Pill>
                    ) : "—"}
                  </span>
                  {/* How long it took, and when: a slow run is found by its time
                      before its graph is opened. */}
                  <span className="readout hidden w-[128px] text-right text-[11.5px] text-slate-500 md:block">
                    {r.status === "RUNNING" ? (
                      <span style={{ color: "var(--state-active-ink)" }}>running</span>
                    ) : r.completedAt ? (
                      duration(elapsed(r.createdAt, r.completedAt) ?? 0)
                    ) : (
                      String(r.status ?? "").toLowerCase()
                    )}
                    <span className="text-slate-500"> · {timeOf(r.createdAt)}</span>
                  </span>
                  <span aria-hidden className="hidden text-slate-500 transition-[transform,opacity] duration-300 group-hover:translate-x-0.5 group-hover:text-slate-200 md:block">
                    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round">
                      <path d="M6 3.5 10.5 8 6 12.5" />
                    </svg>
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
  // The question, as the list that opened this run knew it: the header has its
  // title from the first frame, so the row it grew out of has somewhere to land.
  const title = (useLocation().state as { title?: string } | null)?.title;
  const [trace, setTrace] = useState<(Trace & { run?: any }) | null>(null);
  const [sel, setSel] = useState<string | null>(null);

  // Polled while the run can still change — every two seconds, so a run can be
  // followed as it works — and not at all once its trace is written. It used
  // to poll every four seconds for as long as the page was open.
  const settled = trace != null && trace.execution?.status !== "RUNNING"
    && ((trace.nodes?.length ?? 0) > 0 || trace.run?.status !== "RUNNING");
  useEffect(() => {
    setTrace(null);
    setSel(null);
  }, [workflowId]);
  useEffect(() => {
    if (settled) return;
    let alive = true;
    const load = () => api.get<Trace>(`/api/dag/trace/${workflowId}`).then((t) => alive && setTrace(t)).catch(() => {});
    load();
    const stop = visibleInterval(load, 2000);
    return () => {
      alive = false;
      stop();
    };
  }, [workflowId, settled]);

  // Esc lets go of whatever is selected, wherever focus is.
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setSel(null);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  const run = trace?.run;
  // Stable between polls that bring nothing new, so the layout below is not redone every render.
  const nodes = useMemo(() => (trace?.nodes ?? []) as Node[], [trace]);
  const edges = useMemo(() => (trace?.edges ?? []) as Edge[], [trace]);
  const model = useMemo(() => buildExecution(trace), [trace]);
  const findings = useMemo(() => (model && trace ? findingsOf(model, trace) : []), [model, trace]);

  const W = 1000;
  // Tall enough that the busiest stage's captions never run into each other.
  const busiest = Math.max(1, ...Object.values(nodes.reduce<Record<number, number>>((m, n) => {
    const l = LAYER[n.nodeType] ?? 1;
    m[l] = (m[l] ?? 0) + 1;
    return m;
  }, {})));
  const H = Math.max(560, 150 + busiest * 46);

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
  }, [nodes, H]);

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

  // One selection, shown in every view: the graph, the waterfall, the claims.
  const related = useMemo(() => relatedTo(sel), [sel]);
  // On a narrow screen the details sit above the run, out of sight of a row
  // chosen further down; bring them into view rather than leave the person
  // wondering whether the tap did anything.
  const asideRef = useRef<HTMLElement>(null);
  useEffect(() => {
    const el = asideRef.current;
    if (!sel || !el || window.matchMedia("(min-width: 1024px)").matches) return;
    if (el.getBoundingClientRect().top < 0) {
      el.scrollIntoView({ behavior: window.matchMedia("(prefers-reduced-motion: reduce)").matches ? "auto" : "smooth", block: "start" });
    }
  }, [sel]);
  const work = model?.work.find((w) => w.id === sel) ?? null;
  const conflict = sel?.startsWith("conflict-") ? model?.relations.find((r) => r.node === sel) ?? null : null;

  return (
    <div className="space-y-5">
      <Link to="/dag" data-nav="back" className="micro inline-flex items-center gap-1.5 transition-colors hover:text-slate-300">
        <svg width="11" height="11" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" strokeLinejoin="round" aria-hidden>
          <path d="M10 3.5 5.5 8l4.5 4.5" />
        </svg>
        All runs
      </Link>
      <header className="card p-6" data-morph-target>
        <h1 data-morph-title className="w-fit max-w-full text-[24px] font-semibold leading-[1.2] tracking-[-0.026em] text-slate-100 sm:text-[28px]">
          {run?.prompt || title || "Verification run"}
        </h1>
        <div className="mt-2 flex flex-wrap items-center gap-x-2 text-[12px] text-slate-500">
          {model ? <RunState model={model} /> : <span>Loading the run…</span>}
          <span className="font-mono text-[10.5px] text-slate-500">{workflowId}</span>
        </div>
        {run && run.status !== "RUNNING" && (
          <div className="mt-5 flex flex-wrap items-end gap-x-10 gap-y-3 border-t pt-4" style={{ borderColor: "rgb(var(--card-rule))" }}>
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
        <Plane className="p-8 text-center text-sm text-slate-500">Loading the run…</Plane>
      ) : (
        <div className="grid gap-5 lg:grid-cols-[minmax(0,1fr)_380px]">
          <section className="min-w-0 space-y-8">
            {laid.length > 0 && (
              <div>
                <div className="grid-field rounded-lg border border-edge/60">
                  <svg viewBox={`0 0 ${W} ${H}`} className="w-full" onClick={() => setSel(null)}
                       role="img" aria-label={`Reasoning graph: ${nodes.length} agents, ${contradictions} contradiction${contradictions === 1 ? "" : "s"}`}>
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
                      const dim = sel !== null && !related.has(e.fromKey) && !related.has(e.toKey);
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
                          style={{ transition: "stroke-opacity 200ms" }}
                        />
                      );
                    })}

                    {/* ---- agent nodes ---- */}
                    {laid.map((n) => {
                      const st = statusState(n.status);
                      const color = STATE[st].color;
                      const isSel = related.has(n.nodeKey);
                      const dim = sel !== null && !isSel;
                      const isTerminal = n.nodeType === "AGGREGATOR" || n.nodeType === "SYNTHESIS";
                      const r = isTerminal ? 26 : n.nodeType === "CONFLICT" ? 14 : 18;
                      return (
                        <g
                          key={n.nodeKey}
                          onClick={(ev) => {
                            ev.stopPropagation();
                            setSel(sel === n.nodeKey ? null : n.nodeKey);
                          }}
                          className="cursor-pointer"
                          opacity={dim ? 0.28 : 1}
                          style={{ transition: "opacity 200ms" }}
                        >
                          <title>{`${n.label}${n.validity != null ? ` · ${(n.validity * 100).toFixed(0)}%` : ""}`}</title>
                          {/* A conflict pulses while the run that found it is live; on a
                              finished run it is a fact, and a fact does not need to move. */}
                          {n.nodeType === "CONFLICT" && (
                            <circle cx={n.x} cy={n.y} r={6} fill={color} className={model?.live ? "incident-pulse" : undefined}
                                    opacity={model?.live ? undefined : 0.35} />
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
              </div>
            )}

            {model && (
              <HowItRan model={model} related={related} onSelect={setSel} />
            )}

            <ClaimCard nodes={nodes} model={model} related={related} onSelect={setSel} />

            {/* The same figures as the card above, written out — kept one click
                away for anyone who wants the narration verbatim. */}
            {run?.verdict && (
              <div>
                <Explain title="Synthesised answer, as written">
                  <Plane inset className="mt-1 p-4">
                    <p className="whitespace-pre-wrap text-[13px] leading-relaxed text-slate-300">{run.verdict}</p>
                  </Plane>
                </Explain>
              </div>
            )}
          </section>

          {/* ---- findings, or the evidence for what is selected ---- */}
          {/* Beside the run on a wide screen and always in view; on a narrow one,
              first — the overview comes before the detail. */}
          <aside ref={asideRef} className="order-first scroll-mt-20 space-y-4 lg:sticky lg:top-20 lg:order-none lg:max-h-[calc(100vh-6rem)] lg:self-start lg:overflow-y-auto">
            <LayoutGroup id="run-aside">
              <AnimatePresence mode="popLayout" initial={false}>
                {sel === null ? (
                  <m.div key="findings" className="space-y-4"
                         initial={{ opacity: 0 }}
                         animate={{ opacity: 1, transition: { delay: 0.05, duration: 0.22 } }}
                         exit={{ opacity: 0, transition: { duration: 0.12 } }}>
                <Findings findings={findings} onSelect={setSel} morph />
                {risks.length > 0 && (
                  <div>
                    <Micro>Risk flags</Micro>
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
                  </m.div>
                ) : (
                  <m.div
                    key={`detail-${sel}`}
                    layoutId={`finding-${sel}`}
                    className="card p-4"
                    style={{ borderRadius: 18 }}
                    initial={{ opacity: 0, scale: 0.98 }}
                    animate={{ opacity: 1, scale: 1 }}
                    exit={{ opacity: 0, transition: { duration: 0.12 } }}
                    transition={transitions.standard}
                  >
                    <m.div className="space-y-3" initial={{ opacity: 0 }} animate={{ opacity: 1, transition: { delay: 0.1, duration: 0.2 } }}>
                <button type="button" onClick={() => setSel(null)} className="micro transition-colors hover:text-slate-300">
                  ← Findings
                </button>
                {conflict && model ? (
                  <ConflictDetail model={model} trace={trace} lanes={[conflict.from, conflict.to]} weight={conflict.weight} />
                ) : selected ? (
                  <>
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
                        <Row k="Duration" v={humanMs(elapsed(selected.startedAt, selected.completedAt))} />
                      )}
                    </div>
                    {work && model && <ExecutionFacts work={work} model={model} />}
                    {selected.outputJson && <Output json={selected.outputJson} />}
                  </>
                ) : work && model ? (
                  <>
                    <span className="text-sm font-semibold text-slate-200">{work.title}</span>
                    <ExecutionFacts work={work} model={model} />
                  </>
                ) : (
                  <p className="text-[11px] leading-relaxed text-slate-500">Nothing is recorded for this yet.</p>
                )}
                    </m.div>
                  </m.div>
                )}
              </AnimatePresence>
            </LayoutGroup>
          </aside>
        </div>
      )}
    </div>
  );
}

/**
 * Everything a selection touches. Choosing a claim lights up its solver and its
 * checks; a contradiction lights up both claims and the conflict between them.
 */
function relatedTo(sel: string | null): Set<string> {
  const out = new Set<string>();
  if (!sel) return out;
  out.add(sel);
  const conflict = /^conflict-(\d+)-(\d+)$/.exec(sel);
  if (conflict) {
    out.add(`solver-${conflict[1]}`);
    out.add(`solver-${conflict[2]}`);
  }
  return out;
}

/** Running, or how it ended, in a phrase. */
function RunState({ model }: { model: ExecutionModel }) {
  const retries = model.work.reduce((n, w) => n + Math.max(0, w.attempts.length - 1), 0);
  if (model.live) {
    return (
      <span className="font-medium" style={{ color: "var(--state-active-ink)" }}>
        Running · {duration(model.span)} so far · updating every 2 s
      </span>
    );
  }
  const verb = model.status === "COMPLETED" ? "Completed" : model.status === "CANCELLED" ? "Cancelled" : model.status === "FAILED" ? "Failed" : "Finished";
  return (
    <span>
      {verb} in {duration(model.span)} · {model.work.length} steps{retries ? ` · ${retries} retr${retries === 1 ? "y" : "ies"}` : ""}
    </span>
  );
}

/** Two claims that contradict, side by side: what each concluded, and what it cost them. */
function ConflictDetail({ model, trace, lanes, weight }: { model: ExecutionModel; trace: Trace; lanes: number[]; weight: number }) {
  const nodes = new Map((trace.nodes ?? []).map((n) => [n.nodeKey, n]));
  return (
    <div className="space-y-3">
      <div className="flex items-center gap-2">
        <span className="h-2 w-2 rounded-[2px]" style={{ background: "var(--state-critical-ink)" }} aria-hidden />
        <span className="text-sm font-semibold text-slate-200">Contradiction</span>
        <span className="micro">weight {weight.toFixed(2)}</span>
      </div>
      <p className="text-[11.5px] leading-relaxed text-slate-400">
        Their conclusions point opposite ways on the same subject. Each claim's belief was lowered in proportion to how strongly the other was believed.
      </p>
      {lanes.map((i) => {
        const l = model.lanes[i];
        const out = parseJson(nodes.get(`solver-${l.claimId}`)?.outputJson);
        return (
          <div key={i} className="space-y-1.5 border-t border-edge/50 pt-2.5">
            <div className="flex items-baseline gap-2">
              <span className="readout text-[11px] font-semibold text-slate-200">{l.code}</span>
              <span className="text-[12px] leading-snug text-slate-300">{l.title}</span>
            </div>
            {out?.conclusion && (
              <p className="border-l-2 pl-2.5 text-[12px] leading-relaxed text-slate-300" style={{ borderColor: "var(--state-critical-ink)" }}>
                “{out.conclusion}”
              </p>
            )}
            {l.prior != null && l.posterior != null && (
              <Row k="Belief" v={`${l.prior.toFixed(2)} → ${l.posterior.toFixed(2)} · ${l.survived === false ? "rejected" : "survived"}`} />
            )}
          </div>
        );
      })}
    </div>
  );
}

/**
 * How the run executed, on one clock: where its time went, then every step as
 * a waterfall. Claims are grouped with their checks; the result of each claim
 * is at the right of its row.
 */
function HowItRan({ model, related, onSelect }: {
  model: ExecutionModel;
  related: Set<string>;
  onSelect: (id: string | null) => void;
}) {
  const rows = useMemo(() => rowsOf(model), [model]);
  const links: WaterfallLink[] = model.relations
    .filter((r) => r.kind === "contradicts")
    .map((r) => ({
      id: r.id,
      from: `solver-${model.lanes[r.from].claimId}`,
      to: `solver-${model.lanes[r.to].claimId}`,
      title: `${model.lanes[r.from].code} and ${model.lanes[r.to].code} contradict (weight ${r.weight.toFixed(2)})`,
      select: r.node ?? undefined,
    }));
  return (
    <div>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">How it ran</h2>
        <WaterfallLegend links={links.length > 0} />
      </div>
      <TimeBudget model={model} />
      <div className="plane mt-3 overflow-hidden px-0 pb-1 pt-3">
        <Waterfall
          rows={rows}
          span={model.span}
          live={model.live}
          madeAt={model.madeAt}
          selected={related}
          onSelect={onSelect}
          links={links}
          ariaLabel={`How the run executed: ${model.work.length} steps over ${duration(model.span)}`}
        />
      </div>
    </div>
  );
}

/** The run's steps as waterfall rows: the plan, each claim with its checks, the resolution, the answer. */
function rowsOf(model: ExecutionModel): WaterfallRow[] {
  const rows: WaterfallRow[] = [];
  const row = (w: Work, extra: Partial<WaterfallRow> = {}): WaterfallRow => ({
    id: w.id,
    level: 0,
    label: w.title,
    scheduled: w.scheduled,
    attempts: w.attempts,
    critical: w.critical,
    running: w.status === "running",
    retrying: w.status === "retrying",
    summary: `${w.title}, ${w.attempts.length} attempt${w.attempts.length === 1 ? "" : "s"}${w.end != null ? `, done at ${duration(w.end)}` : ", running"}${w.critical ? ", on the critical path" : ""}`,
    ...extra,
  });
  const meter = (v: number | null, status: Work["status"]) => {
    const ink = status === "fail" ? "var(--state-critical-ink)" : status === "uncertain" ? "var(--state-warning-ink)" : "var(--state-healthy-ink)";
    return v == null ? null : (
      <>
        <span className="relative hidden h-1 w-12 overflow-hidden rounded-full sm:inline-block" style={{ background: "rgb(var(--card-rule))" }} aria-hidden>
          <span className="absolute inset-y-0 left-0 rounded-full" style={{ width: `${Math.max(0, Math.min(1, v)) * 100}%`, background: ink }} />
        </span>
        <span className="readout w-8 text-[11px]" style={{ color: status === "fail" ? ink : "var(--text-3)" }}>{v.toFixed(2)}</span>
      </>
    );
  };
  for (const w of model.work.filter((x) => x.lane === 0 && x.phase === "plan")) rows.push(row(w, { value: <span className="readout text-[11px] text-slate-500">{model.lanes.length - 1} claims</span> }));
  for (const lane of model.lanes.slice(1)) {
    const solver = model.work.find((w) => w.lane === lane.index && w.phase === "solve");
    const result = lane.posterior != null ? (
      <span className="readout text-[11px]" title={`prior ${lane.prior?.toFixed(2) ?? "—"} → posterior ${lane.posterior.toFixed(2)}`}
            style={{ color: lane.survived === false ? "var(--state-critical-ink)" : "var(--text-2)" }}>
        {lane.prior != null && <span className="hidden text-slate-500 sm:inline">{lane.prior.toFixed(2)} → </span>}
        <b className="font-semibold">{lane.posterior.toFixed(2)}</b>
        {lane.survived === false && " ✕"}
      </span>
    ) : null;
    if (solver) rows.push(row(solver, { code: lane.code, label: lane.title, title: lane.title, value: result, select: solver.id }));
    else rows.push({ id: `solver-${lane.claimId}`, level: 0, code: lane.code, label: lane.title, title: lane.title, scheduled: null, attempts: [], value: result });
    for (const c of model.work.filter((w) => w.lane === lane.index && w.phase === "verify").sort((a, b) => a.sub - b.sub)) {
      rows.push(row(c, { level: 1, label: c.title, value: meter(c.value, c.status) }));
    }
  }
  if (model.gate?.resolved) {
    rows.push({
      id: "aggregator",
      level: 0,
      label: "Resolution",
      scheduled: null,
      attempts: [],
      marker: model.gate.at,
      value: model.gate.confidence != null ? <span className="readout text-[12px] font-semibold text-slate-200">{(model.gate.confidence * 100).toFixed(1)}%</span> : null,
      summary: `Resolution at ${duration(model.gate.at)}${model.gate.confidence != null ? `, ${(model.gate.confidence * 100).toFixed(1)}% confidence` : ""}`,
    });
  }
  for (const w of model.work.filter((x) => x.lane === 0 && x.phase !== "plan")) rows.push(row(w));
  return rows;
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
    <div className="flex items-baseline justify-between gap-3 border-b border-edge/40 pb-1.5">
      <span className="micro">{k}</span>
      <span className="readout text-right text-xs text-slate-200">{v}</span>
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

/**
 * Every claim with what its checks said, as bars, and where the evidence left
 * it: the solver's own confidence, each check's validity, and the posterior
 * that decided whether the claim made it into the answer.
 *
 * <p>The synthesised text lists the same figures as indented lines — "validity
 * 0.10 (weakens)" three lines under the claim it weakens. Here each claim is a
 * row, and the claim a check undermined is found by colour, not by reading.
 */
function ClaimCard({ nodes, model, related, onSelect }: {
  nodes: Node[];
  model: ExecutionModel | null;
  related: Set<string>;
  onSelect: (id: string | null) => void;
}) {
  const solvers = nodes.filter((n) => n.nodeType === "SOLVER" && n.claimId != null);
  if (solvers.length === 0) return null;
  const bar = (v: number | null | undefined, ink: string) => (
    <span className="relative h-1.5 w-full overflow-hidden rounded-full" style={{ background: "rgb(var(--card-rule))" }}>
      <span className="absolute inset-y-0 left-0 rounded-full" style={{ width: `${Math.max(0, Math.min(1, v ?? 0)) * 100}%`, background: ink }} />
    </span>
  );
  const healthy = "var(--state-healthy-ink)", critical = "var(--state-critical-ink)", warning = "var(--state-warning-ink)";
  return (
    <div>
      <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Claims and their checks</h2>
      <div className="plane mt-2 divide-y divide-edge/50 px-4 py-1">
        {solvers.map((sv) => {
          const checks = nodes.filter((n) => n.nodeType === "VERIFIER" && n.claimId === sv.claimId);
          const failed = checks.filter((c) => c.status === "FAIL").length;
          const lane = model?.lanes.find((l) => l.claimId === sv.claimId);
          const against = (model?.relations ?? [])
            .filter((r) => r.kind === "contradicts" && lane && (r.from === lane.index || r.to === lane.index))
            .map((r) => ({ code: model!.lanes[r.from === lane!.index ? r.to : r.from].code, node: r.node }));
          const on = related.has(sv.nodeKey) || checks.some((c) => related.has(c.nodeKey));
          return (
            <div key={sv.nodeKey}
                 className={`-mx-4 grid cursor-pointer gap-x-5 gap-y-2 px-4 py-3.5 transition-colors md:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)] ${on ? "" : "hover:bg-edge/20"}`}
                 style={on ? { background: "var(--accent-wash)", boxShadow: "inset 2px 0 0 var(--accent)" } : undefined}
                 onClick={() => onSelect(on && related.has(sv.nodeKey) ? null : sv.nodeKey)}>
              <div className="min-w-0">
                <div className="flex items-start gap-2">
                  <span className="grid h-5 w-5 shrink-0 place-items-center rounded-full text-[10px] font-bold"
                        style={{ background: failed ? "var(--wash-warn)" : "var(--wash-ok)", color: failed ? warning : healthy }}>
                    {sv.claimId}
                  </span>
                  <span className="text-[12.5px] leading-snug text-slate-200">{sv.label}</span>
                </div>
                <div className="mt-1.5 flex items-center gap-2 pl-7">
                  <span className="w-20 shrink-0 text-[10.5px] text-slate-500">solver prior</span>
                  {bar(sv.validity, "rgb(var(--bar-work))")}
                  <span className="readout w-9 shrink-0 text-right text-[10.5px] text-slate-500">{(sv.validity ?? 0).toFixed(2)}</span>
                </div>
                {lane?.posterior != null && (
                  <div className="mt-1 flex items-center gap-2 pl-7">
                    <span className="w-20 shrink-0 text-[10.5px] text-slate-500">posterior</span>
                    {bar(lane.posterior, lane.survived === false ? critical : healthy)}
                    <span className="readout w-9 shrink-0 text-right text-[10.5px] font-semibold"
                          style={{ color: lane.survived === false ? critical : "var(--text-2)" }}>{lane.posterior.toFixed(2)}</span>
                  </div>
                )}
                {(lane?.survived != null || against.length > 0) && (
                  <div className="mt-2 flex flex-wrap items-center gap-1.5 pl-7">
                    {lane?.survived != null && (
                      <Pill tone={lane.survived ? "ok" : "bad"}>{lane.survived ? "in the answer" : "rejected"}</Pill>
                    )}
                    {against.map((a) => (
                      <button key={a.code} type="button"
                              onClick={(e) => {
                                e.stopPropagation();
                                if (a.node) onSelect(a.node);
                              }}
                              className="rounded-full px-2 py-[1px] text-[10.5px] font-medium"
                              style={{ background: "var(--wash-bad)", color: critical }}>
                        contradicts {a.code}
                      </button>
                    ))}
                  </div>
                )}
              </div>
              <div className="space-y-1.5">
                {checks.map((c) => {
                  const kind = String(c.label ?? c.nodeKey).split("·")[0].trim();
                  const ink = c.status === "FAIL" ? critical : c.status === "UNCERTAIN" ? warning : healthy;
                  return (
                    <div key={c.nodeKey} className="flex items-center gap-2" title={c.label}>
                      <span className="w-24 shrink-0 truncate text-[10.5px] text-slate-500">{checkName(kind).toLowerCase()}</span>
                      {bar(c.validity, ink)}
                      <span className="readout w-9 shrink-0 text-right text-[10.5px]"
                            style={{ color: c.status === "FAIL" ? critical : "var(--text-3)" }}>{(c.validity ?? 0).toFixed(2)}</span>
                    </div>
                  );
                })}
              </div>
            </div>
          );
        })}
      </div>
    </div>
  );
}
