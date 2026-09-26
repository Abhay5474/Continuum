import { useEffect, useMemo, useRef, useState } from "react";
import { visibleInterval } from "../system/poll";
import { Link } from "react-router-dom";
import { api, BASE } from "../api";
import { useToast } from "../components/ui";
import { Chip, Segmented, Stat, Stats } from "../system/hub";
import { useOperator } from "../system/OperatorAccess";
import { Readout, Plane, StateDot } from "../system/primitives";
import { STATE, type StateKey } from "../system/tokens";
import DataView from "../system/DataView";
import GatewayResult from "../components/GatewayResult";
import Tabs from "../system/Tabs";
import { Morph, Spotlight } from "../system/motion";
import { timeOf } from "../system/time";
import { trafficSeries } from "../system/traffic";
import { ChartFrame, Donut, Histogram, Scatter, foldTail, seriesColor } from "../system/charts";
import { Select, Table, TH, TR, TD } from "../system/controls";

/**
 * Gateway — live request flow.
 *
 * The centre of this page is the stream of real requests: what was asked for,
 * where it was actually sent, how long it took, and whether the engine had to
 * absorb a provider failure on the way. A failover is the product's whole claim,
 * so it is the loudest thing in the stream rather than a footnote.
 */
const GW_TABS = [
  ["flow", "Request flow"],
  ["health", "Health"],
  ["models", "Models"],
  ["send", "Send a request"],
] as const;

export default function GatewayDashboard() {
  const toast = useToast();
  const [tab, setTab] = useState<"flow" | "health" | "models" | "send">("flow");
  const [stats, setStats] = useState<any | null>(null);
  const [models, setModels] = useState<any[]>([]);
  const [requests, setRequests] = useState<any[]>([]);
  const [health, setHealth] = useState<any[]>([]);
  const [healing, setHealing] = useState<any | null>(null);
  const [verifyOut, setVerifyOut] = useState<any | null>(null);
  const [verifying, setVerifying] = useState(false);
  const [series, setSeries] = useState<number[]>([]);

  // playground — the key is supplied by the developer, never minted here
  const [apiKey, setApiKey] = useState("");
  const [prompt, setPrompt] = useState("Provide first aid for a dog leg injury");
  const [chatOut, setChatOut] = useState<any | null>(null);
  const [sending, setSending] = useState(false);

  // The registry can be read whole or as just what the router may use now.
  const [activeOnly, setActiveOnly] = useState(false);
  const activeOnlyRef = useRef(false);
  activeOnlyRef.current = activeOnly;
  useEffect(() => {
    api.get<any[]>(activeOnly ? "/api/models/active" : "/api/models").then(setModels).catch(() => {});
  }, [activeOnly]);

  const refresh = () => {
    api.get<any>("/api/gateway/stats").then((s) => {
      setStats(s);
      setSeries((prev) => [...prev, s?.totalRequests ?? 0].slice(-40));
    }).catch(() => {});
    api.get<any[]>(activeOnlyRef.current ? "/api/models/active" : "/api/models").then(setModels).catch(() => {});
    api.get<any[]>("/api/gateway/requests?limit=40").then(setRequests).catch(() => {});
    api.get<any[]>("/api/gateway/health").then(setHealth).catch(() => {});
    api.get<any>("/api/gateway/healing/status").then(setHealing).catch(() => {});
  };
  useEffect(() => {
    refresh();
    return visibleInterval(refresh, 4000);
  }, []);

  const runVerifyScan = async () => {
    setVerifying(true);
    try {
      setVerifyOut(await api.post("/api/gateway/healing/verify", {}));
    } catch (e: any) {
      setVerifyOut({ error: e?.message ?? String(e) });
    } finally {
      setVerifying(false);
    }
  };

  const sendChat = async () => {
    setChatOut(null);
    setSending(true);
    try {
      // Through BASE: a console served apart from its API (VITE_API_BASE) used to
      // send this one request to itself.
      const res = await fetch(`${BASE}/api/gateway/chat`, {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
        body: JSON.stringify({ model: "auto", messages: [{ role: "user", content: prompt }], maxTokens: 200 }),
      });
      setChatOut(await res.json());
    } catch (e: any) {
      setChatOut({ error: String(e) });
    } finally {
      setSending(false);
      refresh();
    }
  };

  // The model catalogue is shared by every tenant, so retiring one is the
  // operator's call rather than any single customer's.
  const { operator, request: unlock } = useOperator();
  const setStatus = (id: number, status: string) =>
    api.opPost(`/api/models/${id}/status?status=${status}`).then(refresh);

  const successRate = stats?.successRate ?? 1;

  /** Arrivals per poll, not the running total — a cumulative line only ever
      goes up, which makes every sparkline of it the same shape. */
  const arrivals = useMemo(
    () => series.slice(1).map((v, i) => Math.max(0, v - series[i])),
    [series]
  );
  /** Oldest first, because a sparkline reads left to right and the feed is
      newest first. */
  const latencies = useMemo(
    () => requests.map((r) => r.latencyMs ?? 0).reverse(),
    [requests]
  );
  const shape = useMemo(() => trafficSeries(requests), [requests]);
  const maxLatency = useMemo(
    () => Math.max(...requests.map((r) => r.latencyMs ?? 0), 1),
    [requests]
  );

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2.5">
            <Chip glyph="route" tone="accent" size={28} />
            <h1 className="text-[20px] font-semibold tracking-[-0.011em]">Gateway</h1>
          </div>
          <p className="mt-0.5 text-sm text-slate-500 max-w-2xl leading-relaxed">
            One OpenAI-compatible endpoint · routed, retried and failed over before your app sees it
          </p>
        </div>
      </header>

      {/* The band a gateway page exists for. Each figure carries its own recent
          shape, so "97 requests" is also "and they arrived like this" — the
          second question anyone asks, answered without a click. */}
      <div data-guide="gateway-stats">
      <Stats cols={4}>
        <Stat
          label="Requests"
          glyph="activity"
          tone="violet"
          value={(stats?.totalRequests ?? 0).toLocaleString()}
          series={arrivals.some((a) => a > 0) ? arrivals : shape.arrivals}
        />
        <Stat
          label="Success"
          glyph="check"
          unit="%"
          tone={successRate >= 0.99 ? "ok" : successRate >= 0.9 ? "warn" : "bad"}
          value={(successRate * 100).toFixed(1)}
          series={shape.health}
          hint="Rolling over the last five requests: a dip is a failover or a failure"
        />
        <Stat
          label="Absorbed failures"
          glyph="shield"
          tone="blue"
          value={stats?.failuresPrevented ?? 0}
          hint="Provider failures the engine handled before your app saw them"
        />
        <Stat
          label="Reached your app"
          glyph="alert"
          tone={(stats?.developerVisibleFailures ?? 0) > 0 ? "bad" : "ok"}
          value={stats?.developerVisibleFailures ?? 0}
          hint="Failures your application had to deal with itself"
        />
        <Stat
          label="Latency"
          glyph="clock"
          unit="ms"
          tone="cyan"
          value={latencies.length ? Math.round(latencies[latencies.length - 1]) : "—"}
          series={latencies}
          hint="The most recent request, over the shape of the last forty"
        />
        <Stat label="Tokens" glyph="layers" tone="amber" value={(stats?.totalTokens ?? 0).toLocaleString()} series={shape.tokens} hint="Tokens per request, over the recent ones" />
        <Stat label="Spend" glyph="coin" tone="orange" value={`$${(stats?.totalCostUsd ?? 0).toFixed(5)}`} series={shape.cumulativeCost} hint="Spend accumulating across the recent requests" />
        <Stat
          label="Models available"
          glyph="chip"
          tone="green"
          value={models.length}
        />
      </Stats>
      </div>

      <div data-guide="gateway-tabs">
      <Tabs items={GW_TABS} tab={tab} setTab={setTab} />
      </div>

      <Morph k={tab}>
        {tab === "flow" && (<>
      {/* ---- live request flow: the hero ---- */}
      {requests.length >= 3 && (
        <section className="mb-6">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Traffic shape · last {requests.length} requests</h2>
          </div>
          <TrafficShape requests={requests} />
        </section>
      )}
      <section>
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Live request flow · newest first</h2>
          <span className="micro">bar length is latency, relative to the slowest recent request</span>
        </div>

        {requests.length === 0 ? (
          <div className="mt-2 text-center">
            <p className="text-sm text-slate-400 max-w-2xl leading-relaxed">
              No gateway traffic yet. Send a request with one of your API keys below, or point your
              app at <code className="font-mono text-xs text-neon">/api/gateway/chat</code>.
            </p>
          </div>
        ) : (
          <div data-guide="gateway-flow">
          <Spotlight className="plane mt-2 max-h-[420px] overflow-y-auto px-3">
            <div className="divide-y divide-edge/40">
            {requests.map((r) => {
              const st: StateKey = !r.success ? "critical" : r.failoverCount > 0 ? "warning" : "healthy";
              return (
                <div
                  key={r.id}
                  title={r.routingReason ?? undefined}
                  className="flex flex-wrap items-center gap-x-3 gap-y-1 py-2 text-[11px]"
                >
                  <StateDot state={st} size={6} />
                  <span className="readout w-16 shrink-0 text-slate-600">
                    {timeOf(r.createdAt)}
                  </span>

                  {/* what was asked for → where it actually went */}
                  <span className="flex min-w-0 shrink-0 items-center gap-1.5">
                    <span className="text-slate-500">{r.requestedModel || "auto"}</span>
                    <span className="text-slate-700">→</span>
                    <span className="font-medium text-slate-200">
                      {r.chosenProvider}/{r.chosenModel}
                    </span>
                  </span>

                  {/* latency bar */}
                  <span className="relative h-1.5 w-28 shrink-0 overflow-hidden rounded-full bg-edge">
                    <span
                      className="absolute inset-y-0 left-0 rounded-full"
                      style={{
                        width: `${((r.latencyMs ?? 0) / maxLatency) * 100}%`,
                        background: STATE[st].color,
                      }}
                    />
                  </span>
                  <span className="readout w-14 shrink-0 text-slate-400">{r.latencyMs}ms</span>

                  {r.failoverCount > 0 && (
                    <span
                      className="rounded px-1.5 py-0.5 font-semibold"
                      style={{ background: `${STATE.warning.color}22`, color: STATE.warning.ink }}
                      title="A provider failed and the engine rerouted before your app saw anything"
                    >
                      {r.failoverCount} failover{r.failoverCount > 1 ? "s" : ""} absorbed
                    </span>
                  )}
                  {!r.success && (
                    <span
                      className="rounded px-1.5 py-0.5 font-semibold"
                      style={{ background: `${STATE.critical.color}22`, color: STATE.critical.ink }}
                    >
                      failed
                    </span>
                  )}

                  {/* The id a caller receives as request_id / X-Continuum-Request-Id,
                      so "request req_812 failed" can be found here; and, when the
                      decision trail was recorded, the way into it. */}
                  <span className="ml-auto flex shrink-0 items-center gap-2">
                    {r.traceId && (
                      <Link
                        to={`/provenance?request=${r.traceId}`}
                        className="font-medium text-[color:var(--accent-ink)] hover:underline"
                        data-tip="Every decision behind this request: routing, provider, output"
                      >
                        Why?
                      </Link>
                    )}
                    <button
                      type="button"
                      onClick={() => {
                        void navigator.clipboard?.writeText(`req_${r.id}`);
                        toast(`Copied req_${r.id}`, "success");
                      }}
                      className="readout rounded px-1 text-slate-500 hover:text-slate-200"
                      data-tip="Copy request id"
                      aria-label={`Copy request id req_${r.id}`}
                    >
                      req_{r.id}
                    </button>
                  </span>

                  <span className="ml-auto flex items-center gap-3 text-slate-500">
                    <span title="Scored prompt complexity">
                      <span className="micro mr-1 inline">cx</span>
                      <span className="readout">{(r.complexity ?? 0).toFixed(2)}</span>
                    </span>
                    <span className="readout">{r.tokens} tok</span>
                    <span className="readout">${(r.costUsd ?? 0).toFixed(5)}</span>
                  </span>

                  {r.routingReason && (r.failoverCount > 0 || !r.success) && (
                    <div className="w-full pl-[5.5rem] text-[10px] text-slate-600">{r.routingReason}</div>
                  )}
                </div>
              );
            })}
            </div>
          </Spotlight>
          </div>
        )}
      </section>
        </>)}
        {tab === "health" && (<>
      <div className="grid items-start gap-6 lg:grid-cols-2">
        {/* ---- provider health ---- */}
        <section className="plane p-5">
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Provider &amp; model health</h2>
          {health.length === 0 ? (
            <Plane className="mt-2 p-5 text-center text-xs text-slate-500">No calls recorded yet.</Plane>
          ) : (
            <div className="mt-2 space-y-1.5">
              {health.map((h) => {
                const score = h.healthScore ?? 1;
                const st: StateKey =
                  score < 0.3 ? "critical" : score < 0.7 ? "degraded" : score < 0.9 ? "warning" : "healthy";
                const avg = h.calls ? Math.round((h.totalLatencyMs ?? 0) / h.calls) : 0;
                return (
                  <div key={h.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 text-[11px]">
                    <StateDot state={st} size={6} />
                    <span className="font-mono text-slate-300">
                      {h.provider}/{h.modelName}
                    </span>
                    <span className="relative h-1 w-20 overflow-hidden rounded-full bg-edge">
                      <span className="absolute inset-y-0 left-0 rounded-full"
                        style={{ width: `${score * 100}%`, background: STATE[st].color }} />
                    </span>
                    <span className="ml-auto text-slate-500">
                      {h.calls} calls · {h.failures} fail · {avg}ms
                    </span>
                    {h.lastError && (
                      <div className="w-full truncate pl-4 text-[10px]" style={{ color: STATE.critical.ink }}
                        title={h.lastError}>
                        {h.lastError}
                      </div>
                    )}
                  </div>
                );
              })}
            </div>
          )}

          {stats?.providerUsage && Object.keys(stats.providerUsage).length > 0 && (
            <div className="mt-4">
              <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Usage by provider</h2>
              <div className="mt-1.5 space-y-1">
                {Object.entries(stats.providerUsage).map(([p, v]: any) => (
                  <div key={p} className="flex items-baseline justify-between text-[11px]">
                    <span className="text-slate-400">{p}</span>
                    <span className="readout text-slate-500">
                      {v.requests} req · ${Number(v.cost ?? 0).toFixed(5)}
                    </span>
                  </div>
                ))}
              </div>
            </div>
          )}
        </section>

        {/* ---- self-healing ledger ---- */}
        <section className="plane p-5">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Self-healing ledger</h2>
            <button
              onClick={runVerifyScan}
              disabled={verifying}
              className="rounded border border-edge px-2.5 py-1 text-[10px] transition-colors hover:border-aurora/50 disabled:opacity-50"
            >
              {verifying ? "Scanning…" : "Run verification scan"}
            </button>
          </div>
          <p className="mt-0.5 text-[10px] text-slate-600">
            code/history divergence reconciled instead of crashing in-flight runs
          </p>

          <div className="mt-3 grid grid-cols-2 gap-x-6 gap-y-3 sm:grid-cols-3">
            <Readout label="Resolutions" value={healing?.totalResolutions ?? 0} size="sm"
              state={(healing?.totalResolutions ?? 0) > 0 ? "active" : "idle"} />
            <Readout label="Healed workflows" value={healing?.healedWorkflows ?? 0} size="sm"
              state={(healing?.healedWorkflows ?? 0) > 0 ? "healthy" : "idle"} />
            <Readout label="Insertions" value={healing?.byResolutionType?.INSERTION_MAPPED ?? 0} size="sm" />
            <Readout label="Deletions" value={healing?.byResolutionType?.DELETION_SKIPPED ?? 0} size="sm" />
            <Readout label="Reorders" value={healing?.byResolutionType?.REORDER_ALIGNED ?? 0} size="sm" />
          </div>

          <div className="mt-3 max-h-56 space-y-1 overflow-y-auto pr-1">
            {(healing?.recentResolutions ?? []).map((r: any, i: number) => (
              <div key={i} className="flex flex-wrap items-center gap-2 rounded px-2 py-1.5 text-[10px] hover:bg-edge/40">
                <StateDot state="active" size={5} />
                <span style={{ color: STATE.active.ink }}>{r.resolutionType}</span>
                <span className="font-mono text-slate-400">{r.workflowId}</span>
                <span className="text-slate-600">
                  seq {r.codeSequence} → {r.historySequence ?? "∅"}
                </span>
                <span className="ml-auto text-slate-600">
                  {r.resolvedAt ? timeOf(r.resolvedAt) : ""}
                </span>
              </div>
            ))}
            {(healing?.recentResolutions ?? []).length === 0 && (
              <div className="rounded border border-dashed border-edge/60 px-3 py-3 text-[11px] text-slate-500">
                No divergence detected — every deployed code graph matches its recorded history.
              </div>
            )}
          </div>

          {verifyOut && (
            <Plane inset className="mt-3 p-3 text-[11px]">
              {verifyOut.error ? (
                <span style={{ color: STATE.critical.ink }}>{verifyOut.error}</span>
              ) : (
                <>
                  <div className="flex flex-wrap items-center gap-2">
                    <StateDot state={verifyOut.divergedInstances > 0 ? "warning" : "healthy"} size={6} />
                    <span
                      style={{
                        color:
                          verifyOut.divergedInstances > 0 ? STATE.warning.color : STATE.healthy.color,
                      }}
                    >
                      {verifyOut.divergedInstances > 0 ? "Divergences pending heal" : "All aligned"}
                    </span>
                    <span className="text-slate-500">
                      scanned {verifyOut.scanned} running instance(s)
                    </span>
                  </div>
                  {verifyOut.divergedInstances > 0 && (
                    <div className="mt-2 max-h-52 overflow-y-auto">
                      <DataView value={(verifyOut.results ?? []).filter((x: any) => x.diverged)} />
                    </div>
                  )}
                </>
              )}
            </Plane>
          )}
        </section>
      </div>

        </>)}
        {tab === "models" && (<>
      {/* ---- model registry ---- */}
      <section>
        <div className="flex flex-wrap items-center justify-between gap-2">
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Model registry &amp; lifecycle</h2>
          <span className="ml-auto" />
          <Segmented
            value={activeOnly ? "active" : "all"}
            onChange={(v) => setActiveOnly(v === "active")}
            options={[
              { value: "all", label: "All models" },
              { value: "active", label: "Routable now" },
            ]}
          />
          {operator ? (
            <button
              onClick={() => api.opPost("/api/models/discover").then(refresh)}
              className="rounded border border-edge px-2.5 py-1 text-[11px] text-slate-300 hover:border-aurora/50"
            >
              Re-run discovery
            </button>
          ) : (
            <button
              onClick={unlock}
              className="rounded border border-amber-500/40 px-2.5 py-1 text-[11px] text-amber-300 hover:bg-amber-500/10"
            >
              Read-only · unlock operator access
            </button>
          )}
        </div>
        <div className="mt-2">
          <Table
            minWidth={620}
            head={
              <tr>
                <TH>Provider</TH>
                <TH>Model</TH>
                <TH>Status</TH>
                <TH align="right">Context</TH>
                <TH align="right" width={170}>Lifecycle</TH>
              </tr>
            }
          >
              {models.map((m) => (
                <TR key={m.id}>
                  <TD className="font-medium text-slate-200">{m.provider}</TD>
                  <TD muted className="font-mono">{m.modelName}</TD>
                  <TD>
                    <span className="flex items-center gap-1.5">
                      <StateDot state={m.status === "ACTIVE" ? "healthy" : "idle"} size={5} />
                      <span className="text-slate-400">{m.status}</span>
                    </span>
                  </TD>
                  <TD numeric>
                    {/* Context windows span 32k to a million; a log bar keeps
                        the small ones visible while still showing the gap. */}
                    <span className="inline-flex items-center justify-end gap-2">
                      <span className="relative hidden h-1.5 w-16 overflow-hidden rounded-full sm:inline-block" style={{ background: "rgb(var(--card-rule))" }} aria-hidden>
                        <span className="absolute inset-y-0 left-0 rounded-full"
                              style={{ width: `${Math.max(4, Math.min(100, (Math.log10(Math.max(1, m.contextWindow ?? 0)) - 3) / 3.2 * 100))}%`, background: "var(--state-active-ink)" }} />
                      </span>
                      {(m.contextWindow ?? 0).toLocaleString()}
                    </span>
                  </TD>
                  <TD align="right">
                    <Select
                      value={m.status}
                      onChange={(e) => setStatus(m.id, e.target.value)}
                      disabled={!operator}
                      title={operator ? undefined : "Shared model catalogue — unlock operator access to change it"}
                    >
                      {["DISCOVERED", "TESTING", "ACTIVE", "DEPRECATED", "REMOVED"].map((s) => (
                        <option key={s}>{s}</option>
                      ))}
                    </Select>
                  </TD>
                </TR>
              ))}
              {models.length === 0 && (
                <TR>
                  <TD muted className="py-4">No models registered.</TD>
                </TR>
              )}
          </Table>
        </div>
      </section>

        </>)}
        {tab === "send" && (<>
      {/* ---- playground ---- */}
      <section>
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Send a request</h2>
        <p className="mt-1 text-[11px] text-slate-500">
          Paste one of your API keys to send a request through the gateway.{" "}
          <Link to="/portal" className="text-neon hover:underline">Create a key →</Link>
        </p>
        <div className="mt-2 grid gap-2 lg:grid-cols-[280px_minmax(0,1fr)_auto]">
          <input
            value={apiKey}
            onChange={(e) => setApiKey(e.target.value)}
            type="password"
            placeholder="cnt_live_…"
            className="font-mono field"
          />
          <input
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            className="field"
          />
          <button
            onClick={sendChat}
            disabled={!apiKey || sending}
            className="rounded bg-[color:var(--accent-strong)] px-4 py-2 text-sm font-medium text-white transition-colors hover:opacity-90 disabled:opacity-50"
          >
            {sending ? "Sending…" : "Send"}
          </button>
        </div>
        {chatOut && (
          <Plane inset className="mt-2 max-h-[420px] overflow-y-auto p-3">
            <GatewayResult out={chatOut} />
          </Plane>
        )}
      </section>
        </>)}
      </Morph>
    </div>
  );
}

/**
 * The overall shape of recent traffic, above the per-request feed: how latency
 * is spread, which models carried it, and whether bigger requests are the slow
 * ones. The feed answers "what happened to this request"; this answers "what is
 * my traffic like".
 */
function TrafficShape({ requests }: { requests: any[] }) {
  const lat = requests.map((r) => r.latencyMs ?? 0).sort((a, b) => a - b);
  const q = (f: number) => lat[Math.min(lat.length - 1, Math.floor(f * lat.length))];
  const p50 = q(0.5);
  const p95 = q(0.95);
  const bins = 8;
  const hi = Math.max(1, lat[lat.length - 1]);
  const step = Math.ceil(hi / bins) || 1;
  const hist = Array.from({ length: bins }, (_, i) => {
    const lo = i * step;
    return {
      label: `${lo}`,
      value: lat.filter((v) => v >= lo && (i === bins - 1 ? true : v < lo + step)).length,
      hint: `${lo}–${lo + step} ms`,
      color: lo + step <= p50 ? "var(--state-healthy-ink)" : lo >= p95 ? "var(--state-warning-ink)" : "var(--series-1)",
    };
  });
  const byModel = new Map<string, number>();
  requests.forEach((r) => {
    const k = `${r.chosenProvider ?? "?"} · ${r.chosenModel ?? "?"}`;
    byModel.set(k, (byModel.get(k) ?? 0) + 1);
  });
  const mix = foldTail([...byModel.entries()].map(([k, v]) => ({ key: k, label: k, value: v })), 5);
  const dot = (r: any) => (!r.success ? "var(--state-critical-ink)" : r.failoverCount ? "var(--state-warning-ink)" : seriesColor(0));
  return (
    <div className="mt-2 grid gap-3 lg:grid-cols-3">
      <ChartFrame
        title="Latency spread"
        caption={`Half finish within ${p50} ms (green); the slowest 5% take ${p95} ms or more (amber).`}
        data={hist.map((b) => ({ key: b.label, label: b.hint, value: b.value }))}
        unit="requests"
      >
        <Histogram bins={hist} height={110} xLabel="ms" endLabel={`${bins * step}`} />
      </ChartFrame>
      <ChartFrame title="Which models answered" data={mix} unit="requests">
        <Donut data={mix} size={120} centerValue={String(requests.length)} centerLabel="requests" />
      </ChartFrame>
      <ChartFrame
        title="Size against speed"
        caption="Each dot is a request. Amber needed failover; red failed."
        data={requests.map((r) => ({ key: String(r.id), label: `req_${r.id} · ${r.tokens ?? 0} tok`, value: r.latencyMs ?? 0 }))}
        unit="ms"
      >
        <Scatter
          points={[...requests].reverse().map((r) => ({ x: r.tokens ?? 0, y: r.latencyMs ?? 0, color: dot(r), label: `req_${r.id}` }))}
          xLabel="tokens"
          yLabel="ms"
          xFormat={(n) => String(Math.round(n))}
          yFormat={(n) => String(Math.round(n))}
          height={180}
        />
      </ChartFrame>
    </div>
  );
}
