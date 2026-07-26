import { useEffect, useMemo, useState } from "react";
import { api, isOperator } from "../api";
import { Micro, Readout, Plane, StateDot, Meter } from "../system/primitives";
import { STATE, type StateKey } from "../system/tokens";
import Tabs from "../system/Tabs";
import { Morph } from "../system/motion";

/**
 * Routing — the provider network.
 *
 * Traffic enters at the gateway and is dispatched to one of several upstream
 * providers. The network shows where it actually went (edge weight is measured
 * call share), how healthy each destination is, and what the bandit currently
 * believes about them.
 *
 * The belief panel shows the real Beta posteriors the sampler draws from —
 * Beta(discountedSuccesses+1, discountedFailures+1) per (context, provider) —
 * ranked, as an interval and a mean. Overlapping intervals mean the bandit is
 * still exploring. If it has no observations for a context, that context is
 * reported as unobserved rather than drawn.
 */

const MODES = ["LOW_COST", "LOW_LATENCY", "HIGH_QUALITY", "BALANCED"];
const CONTEXTS = ["SIMPLE", "MODERATE", "COMPLEX"];

/** Providers are tints of one hue, so state colour stays reserved for health. */
const SERIES = ["#4C8BF5", "#7DA9FF", "#38BDF8", "#A5B4FC", "#60A5FA", "#93C5FD"];
const seriesColor = (i: number) => SERIES[i % SERIES.length];

const RT_TABS = [
  ["network", "Dispatch"],
  ["learning", "Learning & hedging"],
  ["probe", "Probe"],
] as const;

export default function ModelRouter() {
  // Routing objective and hedging policy apply to the whole engine, so they are
  // the operator's to change. Shown either way — a disabled control that
  // explains itself beats one that returns 403.
  const operator = isOperator();
  const [tab, setTab] = useState<"network" | "learning" | "probe">("network");
  const [routing, setRouting] = useState<any | null>(null);
  const [providers, setProviders] = useState<any[]>([]);
  const [health, setHealth] = useState<any[]>([]);
  const [bandit, setBandit] = useState<any | null>(null);
  const [hedging, setHedging] = useState<any | null>(null);
  const [hedgeMetrics, setHedgeMetrics] = useState<any | null>(null);
  const [prompt, setPrompt] = useState("Summarise this contract clause and flag any risk.");
  const [probe, setProbe] = useState<any | null>(null);
  const [probing, setProbing] = useState(false);

  const refresh = () => {
    api.get<any>("/api/routing/state").then(setRouting).catch(() => {});
    api.get<any[]>("/api/routing/providers").then(setProviders).catch(() => {});
    api.get<any[]>("/api/gateway/health").then(setHealth).catch(() => {});
    api.get<any>("/api/routing/bandit").then(setBandit).catch(() => {});
    api.get<any>("/api/hedging").then(setHedging).catch(() => {});
    api.get<any>("/api/hedging/metrics").then(setHedgeMetrics).catch(() => {});
  };
  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 5000);
    return () => clearInterval(t);
  }, []);

  const runProbe = async () => {
    setProbing(true);
    try {
      setProbe(await api.post("/api/routing/select", { userPrompt: prompt, mode: routing?.mode }));
    } catch (e: any) {
      setProbe({ error: e?.message ?? String(e) });
    } finally {
      setProbing(false);
    }
  };

  // Health is reported per provider/model; roll it up per provider.
  const healthByProvider = useMemo(() => {
    const m = new Map<string, { score: number; calls: number; failures: number; lastError: string | null }>();
    health.forEach((h) => {
      const cur = m.get(h.provider) ?? { score: 0, calls: 0, failures: 0, lastError: null };
      m.set(h.provider, {
        score: Math.max(cur.score, h.healthScore ?? 1),
        calls: cur.calls + (h.calls ?? 0),
        failures: cur.failures + (h.failures ?? 0),
        lastError: h.lastError ?? cur.lastError,
      });
    });
    return m;
  }, [health]);

  const totalCalls = providers.reduce((a, p) => a + (p.calls ?? 0), 0);
  const ranked = useMemo(
    () => [...providers].sort((a, b) => (b.calls ?? 0) - (a.calls ?? 0)),
    [providers]
  );
  const colorOf = useMemo(() => {
    const m = new Map<string, string>();
    ranked.forEach((p, i) => m.set(p.provider, seriesColor(i)));
    return m;
  }, [ranked]);

  const providerState = (p: any): StateKey => {
    const h = healthByProvider.get(p.provider);
    const score = h?.score ?? 1;
    if (score < 0.3) return "critical";
    if (score < 0.7) return "degraded";
    if (score < 0.9) return "warning";
    return (p.calls ?? 0) > 0 ? "healthy" : "idle";
  };

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <h1 className="text-lg font-semibold tracking-tight">Routing</h1>
          <p className="mt-0.5 text-sm text-slate-500">
            Scored per request · non-stationary contextual bandit · tail-latency hedging
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
          <div>
            <Micro>Router</Micro>
            <button
              onClick={() => api.post(`/api/routing/enable?enabled=${!routing?.enabled}`).then(refresh)}
              disabled={!operator}
              title={operator ? undefined : "Engine-wide setting — operator only"}
              className="mt-1 flex items-center gap-2 rounded border border-edge px-3 py-1.5 text-xs transition-colors hover:border-aurora/50 disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:border-edge"
            >
              <StateDot state={routing?.enabled ? "healthy" : "idle"} />
              {routing?.enabled ? "Enabled" : "Disabled"}
            </button>
          </div>
          <div>
            <Micro>Objective</Micro>
            <select
              value={routing?.mode ?? "BALANCED"}
              onChange={(e) => api.post(`/api/routing/mode?mode=${e.target.value}`).then(refresh)}
              disabled={!operator}
              title={operator ? undefined : "Engine-wide setting — operator only"}
              className="mt-1 rounded border border-edge bg-ink px-2 py-1.5 text-xs text-slate-200 outline-none focus:border-aurora/60 disabled:cursor-not-allowed disabled:opacity-60"
            >
              {MODES.map((m) => (
                <option key={m}>{m}</option>
              ))}
            </select>
          </div>
        </div>
      </header>

      <Tabs items={RT_TABS} tab={tab} setTab={setTab} />

      <Morph k={tab}>
        {tab === "network" && (<>
      {/* ---- the network ---- */}
      <section>
        <Micro>Dispatch network · edge weight is measured call share</Micro>
        {ranked.length === 0 ? (
          <Plane className="mt-2 p-8 text-center text-sm text-slate-500">
            No provider traffic yet. Send a request through the gateway and the network will populate.
          </Plane>
        ) : (
          <div className="mt-2 grid-field rounded-lg border border-edge/60">
            <Network
              providers={ranked}
              totalCalls={totalCalls}
              colorOf={colorOf}
              stateOf={providerState}
              healthByProvider={healthByProvider}
            />
          </div>
        )}
      </section>

      {/* ---- provider table ---- */}
      {ranked.length > 0 && (
        <section>
          <Micro>Providers</Micro>
          <div className="mt-2 overflow-x-auto">
            <table className="w-full text-xs">
              <thead>
                <tr className="border-b border-edge/60 text-left">
                  <Th>Provider</Th>
                  <Th>Share</Th>
                  <Th right>Calls</Th>
                  <Th right>Success</Th>
                  <Th right>Avg latency</Th>
                  <Th right>Tokens</Th>
                  <Th right>Cost</Th>
                  <Th right>Health</Th>
                </tr>
              </thead>
              <tbody>
                {ranked.map((p) => {
                  const st = providerState(p);
                  const share = totalCalls ? (p.calls ?? 0) / totalCalls : 0;
                  const success = p.calls ? (p.successes ?? 0) / p.calls : 1;
                  const avgLat = p.calls ? Math.round((p.totalLatencyMs ?? 0) / p.calls) : 0;
                  const h = healthByProvider.get(p.provider);
                  return (
                    <tr key={p.provider} className="border-b border-edge/40">
                      <td className="py-2">
                        <span className="flex items-center gap-2">
                          <span className="h-2 w-2 rounded-sm" style={{ background: colorOf.get(p.provider) }} />
                          <span className="text-slate-200">{p.provider}</span>
                        </span>
                      </td>
                      <td className="w-32 py-2 pr-4">
                        <Meter value={share} state="active" height={3} />
                      </td>
                      <Td>{(p.calls ?? 0).toLocaleString()}</Td>
                      <Td style={{ color: success >= 0.99 ? STATE.healthy.color : success >= 0.9 ? STATE.warning.color : STATE.critical.color }}>
                        {(success * 100).toFixed(1)}%
                      </Td>
                      <Td>{avgLat}ms</Td>
                      <Td>{((p.promptTokens ?? 0) + (p.completionTokens ?? 0)).toLocaleString()}</Td>
                      <Td>${(p.totalCostUsd ?? 0).toFixed(5)}</Td>
                      <td className="py-2 text-right">
                        <span className="inline-flex items-center gap-1.5" title={h?.lastError ?? undefined}>
                          <StateDot state={st} size={6} />
                          <span className="readout" style={{ color: STATE[st].color }}>
                            {h ? `${(h.score * 100).toFixed(0)}%` : "—"}
                          </span>
                        </span>
                      </td>
                    </tr>
                  );
                })}
              </tbody>
            </table>
          </div>
        </section>
      )}

        </>)}
        {tab === "learning" && (<>
      <div className="grid gap-6 lg:grid-cols-2">
        {/* ---- bandit beliefs ---- */}
        <section>
          <Micro>Bandit belief · Beta posterior per context</Micro>
          <p className="mt-0.5 text-[10px] text-slate-600">
            {bandit?.nonStationary ? `discounted γ=${bandit.gamma} · tracks drift` : "undiscounted"} ·
            wider interval = less certain · overlap = still exploring
          </p>
          <div className="mt-3 space-y-4">
            {CONTEXTS.map((c) => {
              const row = bandit?.byContext?.[c];
              return (
                <div key={c}>
                  <div className="flex items-baseline justify-between">
                    <span className="micro">{c}</span>
                    {!row && <span className="text-[10px] text-slate-600">unobserved</span>}
                  </div>
                  {row ? (
                    <BetaPanel row={row} colorOf={colorOf} />
                  ) : (
                    <div className="mt-1 h-14 rounded border border-dashed border-edge/60" />
                  )}
                </div>
              );
            })}
          </div>
        </section>

        {/* ---- hedging ---- */}
        <section>
          <div className="flex items-baseline justify-between gap-2">
            <Micro>Tail-latency hedging</Micro>
            <button
              onClick={() => api.post(`/api/hedging/enable?enabled=${!hedging?.enabled}`).then(refresh)}
              disabled={!operator}
              title={operator ? undefined : "Engine-wide setting — operator only"}
              className="flex items-center gap-1.5 rounded border border-edge px-2 py-1 text-[10px] transition-colors hover:border-aurora/50 disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:border-edge"
            >
              <StateDot state={hedging?.enabled ? "healthy" : "idle"} size={6} />
              {hedging?.enabled ? "On" : "Off"}
            </button>
          </div>
          <p className="mt-0.5 text-[10px] text-slate-600">
            second request fires past the trigger, capped by hedge rate
          </p>

          {hedgeMetrics ? (
            <>
              <LatencyRail m={hedgeMetrics} />
              <div className="mt-4 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-4">
                <Readout label="p50" value={hedgeMetrics.latencyP50Ms ?? 0} unit="ms" size="sm" />
                <Readout label="p95" value={hedgeMetrics.latencyP95Ms ?? 0} unit="ms" size="sm"
                  state={(hedgeMetrics.latencyP95Ms ?? 0) > 2000 ? "warning" : "idle"} />
                <Readout label="p99" value={hedgeMetrics.latencyP99Ms ?? 0} unit="ms" size="sm" />
                <Readout label="Hedged" value={hedgeMetrics.totalHedged ?? 0} size="sm"
                  state={(hedgeMetrics.totalHedged ?? 0) > 0 ? "active" : "idle"} />
              </div>
              <div className="mt-3">
                <Meter
                  label={`Hedge rate vs cap (${((hedgeMetrics.hedgeRateCap ?? 0) * 100).toFixed(0)}%)`}
                  value={(hedgeMetrics.observedHedgeRate ?? 0) / Math.max(0.0001, hedgeMetrics.hedgeRateCap ?? 1)}
                  state={
                    (hedgeMetrics.observedHedgeRate ?? 0) > (hedgeMetrics.hedgeRateCap ?? 1) ? "warning" : "healthy"
                  }
                />
                <div className="mt-1 flex justify-between text-[10px] text-slate-500">
                  <span>
                    observed {((hedgeMetrics.observedHedgeRate ?? 0) * 100).toFixed(2)}% of{" "}
                    {hedgeMetrics.totalRequests ?? 0} requests
                  </span>
                  <span>
                    trigger {hedgeMetrics.effectiveThresholdMs ?? 0}ms
                    {hedgeMetrics.adaptive ? " (adaptive)" : " (fixed)"}
                  </span>
                </div>
              </div>
            </>
          ) : (
            <Plane className="mt-3 p-6 text-center text-xs text-slate-500">No hedging metrics yet.</Plane>
          )}
        </section>
      </div>

        </>)}
        {tab === "probe" && (<>
      {/* ---- routing probe ---- */}
      <section>
        <Micro>Probe · score a prompt without sending it</Micro>
        <div className="mt-2 flex flex-wrap gap-2">
          <input
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            className="min-w-0 flex-1 rounded border border-edge bg-ink px-3 py-2 text-sm text-slate-100 outline-none focus:border-aurora/60"
          />
          <button
            onClick={runProbe}
            disabled={probing}
            className="rounded bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-indigo-500 disabled:opacity-50"
          >
            {probing ? "Scoring…" : "Score"}
          </button>
        </div>

        {probe && !probe.error && (
          <div className="settle mt-3 grid gap-4 lg:grid-cols-[280px_minmax(0,1fr)]">
            <div className="space-y-2">
              <Readout label="Chosen" value={probe.chosenChain?.[0] ?? "—"} size="sm" state="active" />
              <div className="flex gap-6">
                <Readout label="Complexity" value={(probe.complexity ?? 0).toFixed(2)} size="sm" />
                <Readout label="Prompt tokens" value={probe.approxPromptTokens ?? 0} size="sm" />
              </div>
              {probe.chosenChain?.length > 1 && (
                <div>
                  <Micro>Failover chain</Micro>
                  <div className="mt-1 flex flex-wrap items-center gap-1 text-[11px] text-slate-400">
                    {probe.chosenChain.map((c: string, i: number) => (
                      <span key={c} className="flex items-center gap-1">
                        {i > 0 && <span className="text-slate-600">→</span>}
                        {c}
                      </span>
                    ))}
                  </div>
                </div>
              )}
            </div>
            <div>
              <Micro>Scores</Micro>
              <div className="mt-1 space-y-1">
                {(probe.scores ?? []).map((s: any) => {
                  const max = Math.max(...(probe.scores ?? []).map((x: any) => x.score ?? 0), 0.0001);
                  const chosen = s.provider === probe.chosenChain?.[0];
                  return (
                    <div key={`${s.provider}-${s.model ?? ""}`} className="flex items-center gap-2 text-[11px]">
                      <span className={`w-28 shrink-0 truncate ${chosen ? "text-slate-200" : "text-slate-500"}`}>
                        {s.provider}
                      </span>
                      <span className="relative h-1.5 flex-1 overflow-hidden rounded-full bg-ink">
                        <span
                          className="absolute inset-y-0 left-0 rounded-full transition-[width] duration-500"
                          style={{
                            width: `${((s.score ?? 0) / max) * 100}%`,
                            background: chosen ? STATE.active.color : "#3A4356",
                          }}
                        />
                      </span>
                      <span className="readout w-12 text-right text-slate-400">{(s.score ?? 0).toFixed(3)}</span>
                    </div>
                  );
                })}
              </div>
              {probe.explanation && (
                <p className="mt-2 text-[11px] leading-relaxed text-slate-500">{probe.explanation}</p>
              )}
            </div>
          </div>
        )}
        {probe?.error && (
          <p className="mt-2 text-[11px]" style={{ color: STATE.critical.color }}>
            {probe.error}
          </p>
        )}
      </section>
        </>)}
      </Morph>
    </div>
  );
}

/** Gateway → providers, with edge weight proportional to measured call share. */
function Network({
  providers,
  totalCalls,
  colorOf,
  stateOf,
  healthByProvider,
}: {
  providers: any[];
  totalCalls: number;
  colorOf: Map<string, string>;
  stateOf: (p: any) => StateKey;
  healthByProvider: Map<string, any>;
}) {
  const W = 1000;
  const rowH = 78;
  const H = Math.max(220, providers.length * rowH + 60);
  const gx = 130;
  const px = W - 210;

  return (
    <svg viewBox={`0 0 ${W} ${H}`} className="w-full" style={{ maxHeight: 420 }}>
      {/* gateway */}
      <g>
        <rect x={gx - 58} y={H / 2 - 26} width={116} height={52} rx={8}
          style={{ fill: "rgb(var(--topo-node))" }} stroke={STATE.active.color} strokeOpacity={0.5} />
        <text x={gx} y={H / 2 - 2} textAnchor="middle" className="text-[11px] font-semibold"
          style={{ fill: "rgb(var(--topo-text))", letterSpacing: "0.08em" }}>
          GATEWAY
        </text>
        <text x={gx} y={H / 2 + 13} textAnchor="middle" className="text-[9px]"
          style={{ fill: "rgb(var(--topo-label))" }}>
          {totalCalls.toLocaleString()} calls
        </text>
      </g>

      {providers.map((p, i) => {
        const y = 44 + i * rowH + rowH / 2 - 10;
        const share = totalCalls ? (p.calls ?? 0) / totalCalls : 0;
        const st = stateOf(p);
        const color = colorOf.get(p.provider) ?? STATE.active.color;
        const stColor = STATE[st].color;
        const avgLat = p.calls ? Math.round((p.totalLatencyMs ?? 0) / p.calls) : 0;
        const h = healthByProvider.get(p.provider);
        const attention = st === "degraded" || st === "critical";
        return (
          <g key={p.provider}>
            {/* dispatch path — thickness is the real share of traffic */}
            <path
              d={`M ${gx + 58} ${H / 2} C ${(gx + px) / 2} ${H / 2}, ${(gx + px) / 2} ${y}, ${px - 4} ${y}`}
              fill="none"
              stroke={color}
              strokeOpacity={0.18 + share * 0.5}
              strokeWidth={1 + share * 9}
              strokeLinecap="round"
            />
            <text
              x={(gx + px) / 2}
              y={(H / 2 + y) / 2 - 6}
              textAnchor="middle"
              className="text-[9px]"
              style={{ fill: "rgb(var(--topo-label))" }}
            >
              {(share * 100).toFixed(0)}%
            </text>

            {/* provider node */}
            {attention && <circle cx={px + 44} cy={y} r={8} fill={stColor} className="incident-pulse" />}
            <rect x={px} y={y - 20} width={176} height={40} rx={8}
              style={{ fill: "rgb(var(--topo-node))" }} stroke={stColor} strokeOpacity={0.55}
              className={attention ? "degrading" : ""} />
            <circle cx={px + 14} cy={y} r={3.5} fill={stColor} />
            <text x={px + 26} y={y - 2} className="text-[11px] font-semibold"
              style={{ fill: "rgb(var(--topo-text))" }}>
              {p.provider}
            </text>
            <text x={px + 26} y={y + 12} className="text-[9px]" style={{ fill: "rgb(var(--topo-label))" }}>
              {avgLat}ms · {h ? `${(h.score * 100).toFixed(0)}% health` : "no health data"}
            </text>
          </g>
        );
      })}
    </svg>
  );
}

/**
 * Beta posteriors for one context. Curves are the actual densities the sampler
 * draws from, normalised to the panel height.
 */
/**
 * What the bandit believes for one context.
 *
 * This was five overlapping density curves in a small panel, which was pretty
 * and unreadable — you could not tell which arm was ahead. It is now a ranked
 * bar per provider: the bar spans the 95% credible interval and the marker is
 * the mean, so the leader, the spread and the overlap are all readable at a
 * glance. Same posterior, legible ordering.
 */
function BetaPanel({ row, colorOf }: { row: Record<string, any>; colorOf: Map<string, string> }) {
  const arms = Object.entries(row)
    .map(([provider, a]: [string, any]) => {
      const alpha = (a.discountedSuccesses ?? 0) + 1;
      const beta = (a.discountedFailures ?? 0) + 1;
      const mean = alpha / (alpha + beta);
      // Normal approximation to the Beta interval — adequate at this scale and
      // far cheaper than sampling on every poll.
      const sd = Math.sqrt((alpha * beta) / ((alpha + beta) ** 2 * (alpha + beta + 1)));
      return {
        provider,
        mean,
        lo: Math.max(0, mean - 1.96 * sd),
        hi: Math.min(1, mean + 1.96 * sd),
        obs: a.updates ?? 0,
        latency: Math.round(a.avgLatencyMs ?? 0),
      };
    })
    .sort((x, y) => y.mean - x.mean);

  return (
    <div className="mt-1.5 space-y-1.5">
      {arms.map((a, i) => {
        const color = colorOf.get(a.provider) ?? seriesColor(i);
        const leading = i === 0 && arms.length > 1;
        return (
          <div key={a.provider} className="grid grid-cols-[96px_minmax(0,1fr)_92px] items-center gap-2">
            <span className="flex items-center gap-1.5 truncate text-[11px]">
              <span className="h-1.5 w-1.5 shrink-0 rounded-full" style={{ background: color }} />
              <span className={leading ? "text-slate-200" : "text-slate-400"}>{a.provider}</span>
            </span>

            <span className="relative h-4">
              <span className="absolute inset-x-0 top-1/2 h-px -translate-y-1/2 bg-edge" />
              {/* 95% credible interval */}
              <span
                className="absolute top-1/2 h-1.5 -translate-y-1/2 rounded-full transition-all duration-500"
                style={{ left: `${a.lo * 100}%`, width: `${Math.max(0.6, (a.hi - a.lo) * 100)}%`, background: `${color}44` }}
              />
              {/* posterior mean */}
              <span
                className="absolute top-1/2 h-3 w-[2px] -translate-y-1/2 rounded transition-all duration-500"
                style={{ left: `${a.mean * 100}%`, background: color }}
              />
            </span>

            <span className="readout text-right text-[10px] text-slate-500">
              {(a.mean * 100).toFixed(0)}% · {a.obs} obs
            </span>
          </div>
        );
      })}
      <div className="flex justify-between text-[9px] text-slate-600">
        <span>0%</span>
        <span>bar = 95% interval · tick = mean</span>
        <span>100%</span>
      </div>
    </div>
  );
}

/** Latency distribution with the hedge trigger marked on it. */
function LatencyRail({ m }: { m: any }) {
  const p50 = m.latencyP50Ms ?? 0;
  const p95 = m.latencyP95Ms ?? 0;
  const p99 = m.latencyP99Ms ?? 0;
  const trigger = m.effectiveThresholdMs ?? 0;

  // With no samples the three percentiles are all zero and would stack on the
  // left edge, which reads as a broken axis rather than as "nothing measured".
  if (p99 <= 0) {
    return (
      <div className="mt-3 flex h-10 items-center justify-center rounded border border-dashed border-edge/60">
        <span className="micro">No latency samples yet · trigger {trigger}ms</span>
      </div>
    );
  }

  const max = Math.max(p99, trigger, 1) * 1.15;
  const pct = (v: number) => `${(v / max) * 100}%`;

  return (
    <div className="mt-3">
      <div className="relative h-10 rounded border border-edge/60 bg-ink/60">
        {/* p50 → p99 body */}
        <div
          className="absolute inset-y-2 rounded-sm"
          style={{ left: pct(p50), width: pct(Math.max(0, p99 - p50)), background: `${STATE.active.color}33` }}
        />
        {[
          { v: p50, label: "p50", c: STATE.active.color },
          { v: p95, label: "p95", c: STATE.warning.color },
          { v: p99, label: "p99", c: STATE.degraded.color },
        ].map((k) => (
          <div key={k.label} className="absolute inset-y-0" style={{ left: pct(k.v) }}>
            <div className="h-full w-px" style={{ background: k.c }} />
            <span className="absolute -top-0.5 left-1 text-[9px]" style={{ color: k.c }}>
              {k.label}
            </span>
          </div>
        ))}
        {/* the hedge trigger */}
        <div className="absolute inset-y-0" style={{ left: pct(trigger) }}>
          <div className="h-full w-px" style={{ background: STATE.healthy.color }} />
          <span className="absolute bottom-0 left-1 text-[9px]" style={{ color: STATE.healthy.color }}>
            trigger
          </span>
        </div>
      </div>
      <div className="mt-1 text-[10px] text-slate-500">
        A hedge fires only for requests that cross the trigger — the shaded body is the observed
        p50–p99 spread.
      </div>
    </div>
  );
}

function Th({ children, right }: { children: React.ReactNode; right?: boolean }) {
  return <th className={`py-2 font-medium ${right ? "text-right" : ""}`}><span className="micro">{children}</span></th>;
}
function Td({ children, style }: { children: React.ReactNode; style?: React.CSSProperties }) {
  return <td className="readout py-2 text-right text-slate-300" style={style}>{children}</td>;
}
