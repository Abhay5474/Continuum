import { useEffect, useMemo, useState } from "react";
import { api } from "../api";
import { Chip } from "../system/hub";
import { useOperator } from "../system/OperatorAccess";
import { Readout, Plane, StateDot, Meter } from "../system/primitives";
import { STATE, type StateKey } from "../system/tokens";
import Tabs from "../system/Tabs";
import { Morph } from "../system/motion";
import { Select, Table, TH, TR, TD, Tag } from "../system/controls";

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

/**
 * How a provider order is decided. Naming these is the point: "routing enabled"
 * previously implied a choice the gateway never actually made, and the bandit
 * that appears further down this page was never consulted by anything.
 */
const STRATEGIES: [string, string][] = [
  ["STATIC", "Static — availability order"],
  ["HEURISTIC", "Heuristic — cost/latency/quality scorer"],
  ["LEARNED", "Learned — contextual bandit"],
];
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
  // the operator's to change. Shown either way — but a disabled control that
  // explains itself is only half an answer, so each one also offers the way in.
  const { operator, request: unlock } = useOperator();
  const [tab, setTab] = useState<"network" | "learning" | "probe">("network");
  const [routing, setRouting] = useState<any | null>(null);
  const [providers, setProviders] = useState<any[]>([]);
  const [health, setHealth] = useState<any[]>([]);
  const [bandit, setBandit] = useState<any | null>(null);
  const [hedging, setHedging] = useState<any | null>(null);
  const [hedgeMetrics, setHedgeMetrics] = useState<any | null>(null);
  const [comparison, setComparison] = useState<any | null>(null);
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
    api.get<any>("/api/routing/comparison?limit=500").then(setComparison).catch(() => {});
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
    <div className="space-y-8">
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2.5">
            <Chip glyph="route" tone="accent" size={28} />
            <h1 className="text-[20px] font-semibold tracking-[-0.011em]">Routing</h1>
          </div>
          <p className="mt-0.5 text-sm text-slate-500 max-w-2xl leading-relaxed">
            Scored per request · non-stationary contextual bandit · tail-latency hedging
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-x-6 gap-y-3">
          <div>
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Router</h2>
            <button
              onClick={() => api.opPost(`/api/routing/enable?enabled=${!routing?.enabled}`).then(refresh)}
              disabled={!operator}
              title={operator ? undefined : "Engine-wide setting — unlock operator access to change it"}
              className="mt-1 flex items-center gap-2 rounded border border-edge px-3 py-1.5 text-xs transition-colors hover:border-aurora/50 disabled:cursor-not-allowed disabled:opacity-60 disabled:hover:border-edge"
            >
              <StateDot state={routing?.enabled ? "healthy" : "idle"} />
              {routing?.enabled ? "Enabled" : "Disabled"}
            </button>
          </div>
          <div>
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Strategy</h2>
            <Select
              value={routing?.configuredStrategy ?? "HEURISTIC"}
              onChange={(e) => api.opPost(`/api/routing/strategy?strategy=${e.target.value}`).then(refresh)}
              disabled={!operator}
              title={operator ? undefined : "Engine-wide setting — unlock operator access to change it"}
            >
              {STRATEGIES.map(([v, label]) => (
                <option key={v} value={v}>{label}</option>
              ))}
            </Select>
          </div>
          <div>
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Objective</h2>
            <Select
              value={routing?.mode ?? "BALANCED"}
              onChange={(e) => api.opPost(`/api/routing/mode?mode=${e.target.value}`).then(refresh)}
              disabled={!operator}
              title={operator ? undefined : "Engine-wide setting — unlock operator access to change it"}
            >
              {MODES.map((m) => (
                <option key={m}>{m}</option>
              ))}
            </Select>
          </div>
        </div>
      </header>

      {/* The controls above are disabled for a developer, which used to be the
          end of the story — the page offered no way to reach the permission it
          required, so the switches read as broken rather than protected. */}
      {!operator && (
        <div className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-lg border border-amber-500/30 bg-amber-500/[0.06] px-4 py-2.5 text-sm">
          <span className="text-amber-300">Read-only.</span>
          <span className="min-w-0 flex-1 text-slate-400">
            The router and hedging apply to every tenant on this deployment and change what it
            spends, so changing them needs operator access.
          </span>
          <button
            onClick={unlock}
            className="rounded-md border border-amber-500/40 px-2.5 py-1 text-xs font-medium text-amber-300 hover:bg-amber-500/10"
          >
            Unlock
          </button>
        </div>
      )}

      <Tabs items={RT_TABS} tab={tab} setTab={setTab} />

      <Morph k={tab}>
        {tab === "network" && (<>
      {/* ---- the network ---- */}
      <section>
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Dispatch network · edge weight is measured call share</h2>
        {ranked.length === 0 ? (
          <div className="mt-2 text-center text-sm text-slate-500">
            No provider traffic yet. Send a request through the gateway and the network will populate.
          </div>
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
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200" data-guide="router-providers">Providers</h2>
          <div className="mt-2">
            <Table
              minWidth={760}
              head={
                <tr>
                  <TH>Provider</TH>
                  <TH width={140}>Share</TH>
                  <TH align="right">Calls</TH>
                  <TH align="right">Success</TH>
                  <TH align="right">Avg latency</TH>
                  <TH align="right">Tokens</TH>
                  <TH align="right">Cost</TH>
                  <TH align="right">Health</TH>
                </tr>
              }
            >
                {ranked.map((p) => {
                  const st = providerState(p);
                  const share = totalCalls ? (p.calls ?? 0) / totalCalls : 0;
                  const success = p.calls ? (p.successes ?? 0) / p.calls : 1;
                  const avgLat = p.calls ? Math.round((p.totalLatencyMs ?? 0) / p.calls) : 0;
                  const h = healthByProvider.get(p.provider);
                  return (
                    <TR key={p.provider}>
                      <TD>
                        <span className="flex items-center gap-2">
                          <span className="h-2 w-2 shrink-0 rounded-sm" style={{ background: colorOf.get(p.provider) }} />
                          <span className="font-medium text-slate-200">{p.provider}</span>
                        </span>
                      </TD>
                      <TD>
                        <Meter value={share} state="active" height={3} />
                      </TD>
                      <TD numeric>{(p.calls ?? 0).toLocaleString()}</TD>
                      <TD numeric>
                        <span style={{ color: success >= 0.99 ? STATE.healthy.ink : success >= 0.9 ? STATE.warning.ink : STATE.critical.ink }}>
                          {(success * 100).toFixed(1)}%
                        </span>
                      </TD>
                      <TD numeric>{avgLat}ms</TD>
                      <TD numeric>{((p.promptTokens ?? 0) + (p.completionTokens ?? 0)).toLocaleString()}</TD>
                      <TD numeric>${(p.totalCostUsd ?? 0).toFixed(5)}</TD>
                      <TD numeric>
                        <span className="inline-flex items-center gap-1.5" title={h?.lastError ?? undefined}>
                          <StateDot state={st} size={6} />
                          <span style={{ color: STATE[st].ink }}>
                            {h ? `${(h.score * 100).toFixed(0)}%` : "—"}
                          </span>
                        </span>
                      </TD>
                    </TR>
                  );
                })}
            </Table>
          </div>
        </section>
      )}

        </>)}
        {tab === "learning" && (<>
      <LearningLedger comparison={comparison} strategy={routing?.strategy} />
      <div className="grid gap-6 lg:grid-cols-2">
        {/* ---- bandit beliefs ---- */}
        <section>
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Bandit belief · Beta posterior per context</h2>
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
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Tail-latency hedging</h2>
            <button
              onClick={() => api.opPost(`/api/hedging/enable?enabled=${!hedging?.enabled}`).then(refresh)}
              disabled={!operator}
              title={operator ? undefined : "Engine-wide setting — unlock operator access to change it"}
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
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Probe · score a prompt without sending it</h2>
        <div className="mt-2 flex flex-wrap gap-2">
          <input
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            className="min-w-0 flex-1 field"
          />
          <button
            onClick={runProbe}
            disabled={probing}
            className="rounded bg-[color:var(--accent-strong)] px-4 py-2 text-sm font-medium text-white transition-colors hover:opacity-90 disabled:opacity-50"
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
                  <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Failover chain</h2>
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
              <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Scores</h2>
              <div className="mt-1 space-y-1">
                {(probe.scores ?? []).map((s: any) => {
                  const max = Math.max(...(probe.scores ?? []).map((x: any) => x.score ?? 0), 0.0001);
                  const chosen = s.provider === probe.chosenChain?.[0];
                  return (
                    <div key={`${s.provider}-${s.model ?? ""}`} className="flex items-center gap-2 text-[11px]">
                      <span className={`w-28 shrink-0 truncate ${chosen ? "text-slate-200" : "text-slate-500"}`}>
                        {s.provider}
                      </span>
                      <span className="relative h-1.5 flex-1 overflow-hidden rounded-full bg-edge">
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
          <p className="mt-2 text-[11px]" style={{ color: STATE.critical.ink }}>
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
          <span className="absolute bottom-0 left-1 text-[9px]" style={{ color: STATE.healthy.ink }}>
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

/**
 * What learned routing actually did.
 *
 * <p>Every decision records the provider the active strategy chose alongside the
 * provider the heuristic scorer would have chosen. Rows where they agree carry
 * no information about whether learning helps, so they are reported separately —
 * an aggregate success rate is dominated by them and would look reassuring
 * whatever the bandit did.
 *
 * <p>The live tape underneath is the part worth watching: a request arrives,
 * and you can see the bandit either follow the scorer or override it, and how
 * that turned out.
 */
function LearningLedger({ comparison, strategy }: { comparison: any; strategy?: string }) {
  const diverged = comparison?.whenDiverged;
  const agreed = comparison?.whenAgreed;
  const recent: any[] = comparison?.recent ?? [];
  const divergenceRate = comparison?.divergenceRate ?? 0;

  const pct = (v: number | null | undefined) => (v == null ? "—" : `${(v * 100).toFixed(1)}%`);
  const delta =
    diverged?.successRate != null && agreed?.successRate != null
      ? diverged.successRate - agreed.successRate
      : null;

  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Decision ledger · learned routing against its own baseline</h2>
        <span className="micro">
          strategy in force: <span className="text-slate-300">{strategy ?? "—"}</span>
        </span>
      </div>

      {(comparison?.decisions ?? 0) === 0 ? (
        <div className="rounded-xl border border-dashed px-3 py-10 text-center text-sm text-slate-500" style={{ borderColor: "rgb(var(--card-edge))" }}>
          No routing decisions recorded yet. Send traffic through the gateway and every choice —
          and the choice it overrode — lands here.
        </div>
      ) : (
        <>
          <div className="plane grid grid-cols-2 gap-x-8 gap-y-5 p-4 sm:grid-cols-3 lg:grid-cols-4">
            <Readout label="Decisions" value={comparison.decisions} size="sm" />
            <Readout
              label="Overrode scorer"
              value={comparison.diverged}
              size="sm"
              hint={`${(divergenceRate * 100).toFixed(0)}% of traffic`}
              state={comparison.diverged > 0 ? "active" : "idle"}
            />
            <Readout label="Explored" value={comparison.explored} size="sm"
              hint="Deliberately tried a thin arm" />
            <Readout
              label="Success when overriding"
              value={pct(diverged?.successRate)}
              size="sm"
              state={delta == null ? "idle" : delta >= 0 ? "healthy" : "critical"}
            />
            <Readout label="Success when agreeing" value={pct(agreed?.successRate)} size="sm" />
          </div>

          {delta != null && (comparison.diverged ?? 0) > 0 && (
            <p className={`text-xs ${delta >= 0 ? "text-emerald-400" : "text-rose-400"}`}>
              On the {comparison.diverged} request{comparison.diverged === 1 ? "" : "s"} where learning
              changed the answer, success was {Math.abs(delta * 100).toFixed(1)} points{" "}
              {delta >= 0 ? "higher" : "lower"} than where it agreed
              {diverged?.avgCost != null && agreed?.avgCost != null && agreed.avgCost > 0
                ? `, at ${(((diverged.avgCost - agreed.avgCost) / agreed.avgCost) * 100).toFixed(0)}% the cost`
                : ""}
              .
            </p>
          )}

          <Table
            minWidth={560}
            maxHeight={420}
            head={
              <tr>
                <TH>Context</TH>
                <TH>Scorer wanted</TH>
                <TH>Actually ran</TH>
                <TH>Outcome</TH>
                <TH align="right">Latency</TH>
                <TH align="right">Cost</TH>
              </tr>
            }
          >
            {recent.map((r) => (
              <TR key={r.id}>
                <TD muted>{r.context}</TD>
                <TD muted>{r.baseline ?? "—"}</TD>
                <TD>
                  <span className={r.diverged ? "font-medium text-neon" : "text-slate-300"}>
                    {r.chosen}
                  </span>
                  {r.diverged && <span className="ml-1.5"><Tag tone="accent">override</Tag></span>}
                  {r.explored && <span className="ml-1.5"><Tag tone="amber">explore</Tag></span>}
                </TD>
                <TD>
                  <StateDot state={r.success ? "healthy" : "critical"} size={5} />
                </TD>
                <TD numeric>{r.latencyMs}ms</TD>
                <TD numeric muted>${(r.cost ?? 0).toFixed(5)}</TD>
              </TR>
            ))}
          </Table>
        </>
      )}
    </section>
  );
}
