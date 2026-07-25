import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import { Micro, Readout, Plane, StateDot, Trace } from "../system/primitives";
import { STATE, type StateKey } from "../system/tokens";
import DataView from "../system/DataView";

/**
 * Gateway — live request flow.
 *
 * The centre of this page is the stream of real requests: what was asked for,
 * where it was actually sent, how long it took, and whether the engine had to
 * absorb a provider failure on the way. A failover is the product's whole claim,
 * so it is the loudest thing in the stream rather than a footnote.
 */
export default function GatewayDashboard() {
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

  const refresh = () => {
    api.get<any>("/api/gateway/stats").then((s) => {
      setStats(s);
      setSeries((prev) => [...prev, s?.totalRequests ?? 0].slice(-40));
    }).catch(() => {});
    api.get<any[]>("/api/models").then(setModels).catch(() => {});
    api.get<any[]>("/api/gateway/requests?limit=40").then(setRequests).catch(() => {});
    api.get<any[]>("/api/gateway/health").then(setHealth).catch(() => {});
    api.get<any>("/api/gateway/healing/status").then(setHealing).catch(() => {});
  };
  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
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
      const res = await fetch("/api/gateway/chat", {
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

  const setStatus = (id: number, status: string) =>
    api.post(`/api/models/${id}/status?status=${status}`).then(refresh);

  const successRate = stats?.successRate ?? 1;
  const maxLatency = useMemo(
    () => Math.max(...requests.map((r) => r.latencyMs ?? 0), 1),
    [requests]
  );

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <h1 className="text-lg font-semibold tracking-tight">Gateway</h1>
          <p className="mt-0.5 text-sm text-slate-500">
            One OpenAI-compatible endpoint · routed, retried and failed over before your app sees it
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
          <Readout label="Requests" value={(stats?.totalRequests ?? 0).toLocaleString()} size="sm"
            state={(stats?.totalRequests ?? 0) > 0 ? "active" : "idle"} />
          <Readout label="Success" value={(successRate * 100).toFixed(1)} unit="%" size="sm"
            state={successRate >= 0.99 ? "healthy" : successRate >= 0.9 ? "warning" : "critical"} />
          <Readout label="Absorbed failures" value={stats?.failuresPrevented ?? 0} size="sm"
            state={(stats?.failuresPrevented ?? 0) > 0 ? "healthy" : "idle"}
            hint="Provider failures the engine handled before your app saw them" />
          <Readout label="Reached your app" value={stats?.developerVisibleFailures ?? 0} size="sm"
            state={(stats?.developerVisibleFailures ?? 0) > 0 ? "critical" : "healthy"} />
          <Readout label="Tokens" value={(stats?.totalTokens ?? 0).toLocaleString()} size="sm" />
          <Readout label="Spend" value={`$${(stats?.totalCostUsd ?? 0).toFixed(5)}`} size="sm" />
          <div>
            <Micro>Throughput</Micro>
            <div className="mt-1"><Trace points={series} state="active" width={110} height={22} /></div>
          </div>
        </div>
      </header>

      {/* ---- live request flow: the hero ---- */}
      <section>
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <Micro>Live request flow · newest first</Micro>
          <span className="micro">bar length is latency, relative to the slowest recent request</span>
        </div>

        {requests.length === 0 ? (
          <Plane className="mt-2 p-8 text-center">
            <p className="text-sm text-slate-400">
              No gateway traffic yet. Send a request with one of your API keys below, or point your
              app at <code className="font-mono text-xs text-neon">/api/gateway/chat</code>.
            </p>
          </Plane>
        ) : (
          <div className="mt-2 max-h-[420px] divide-y divide-edge/40 overflow-y-auto pr-1">
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
                    {new Date(r.createdAt).toLocaleTimeString()}
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
                  <span className="relative h-1.5 w-28 shrink-0 overflow-hidden rounded-full bg-ink">
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
                      style={{ background: `${STATE.warning.color}22`, color: STATE.warning.color }}
                      title="A provider failed and the engine rerouted before your app saw anything"
                    >
                      {r.failoverCount} failover{r.failoverCount > 1 ? "s" : ""} absorbed
                    </span>
                  )}
                  {!r.success && (
                    <span
                      className="rounded px-1.5 py-0.5 font-semibold"
                      style={{ background: `${STATE.critical.color}22`, color: STATE.critical.color }}
                    >
                      failed
                    </span>
                  )}

                  <span className="ml-auto flex items-center gap-3 text-slate-500">
                    <span title="Scored prompt complexity">c{(r.complexity ?? 0).toFixed(2)}</span>
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
        )}
      </section>

      <div className="grid gap-6 lg:grid-cols-2">
        {/* ---- provider health ---- */}
        <section>
          <Micro>Provider &amp; model health</Micro>
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
                    <span className="relative h-1 w-20 overflow-hidden rounded-full bg-ink">
                      <span className="absolute inset-y-0 left-0 rounded-full"
                        style={{ width: `${score * 100}%`, background: STATE[st].color }} />
                    </span>
                    <span className="ml-auto text-slate-500">
                      {h.calls} calls · {h.failures} fail · {avg}ms
                    </span>
                    {h.lastError && (
                      <div className="w-full truncate pl-4 text-[10px]" style={{ color: STATE.critical.color }}
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
              <Micro>Usage by provider</Micro>
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
        <section>
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <Micro>Self-healing ledger</Micro>
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
                <span style={{ color: STATE.active.color }}>{r.resolutionType}</span>
                <span className="font-mono text-slate-400">{r.workflowId}</span>
                <span className="text-slate-600">
                  seq {r.codeSequence} → {r.historySequence ?? "∅"}
                </span>
                <span className="ml-auto text-slate-600">
                  {r.resolvedAt ? new Date(r.resolvedAt).toLocaleTimeString() : ""}
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
                <span style={{ color: STATE.critical.color }}>{verifyOut.error}</span>
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

      {/* ---- model registry ---- */}
      <section>
        <Micro>Model registry &amp; lifecycle</Micro>
        <div className="mt-2 overflow-x-auto">
          <table className="w-full text-xs">
            <thead>
              <tr className="border-b border-edge/60 text-left">
                <th className="py-2"><span className="micro">Provider</span></th>
                <th className="py-2"><span className="micro">Model</span></th>
                <th className="py-2"><span className="micro">Status</span></th>
                <th className="py-2 text-right"><span className="micro">Context</span></th>
                <th className="py-2 text-right"><span className="micro">Lifecycle</span></th>
              </tr>
            </thead>
            <tbody>
              {models.map((m) => (
                <tr key={m.id} className="border-b border-edge/40">
                  <td className="py-2 text-slate-300">{m.provider}</td>
                  <td className="py-2 font-mono text-[11px] text-slate-400">{m.modelName}</td>
                  <td className="py-2">
                    <span className="flex items-center gap-1.5">
                      <StateDot state={m.status === "ACTIVE" ? "healthy" : "idle"} size={5} />
                      <span className="text-slate-400">{m.status}</span>
                    </span>
                  </td>
                  <td className="readout py-2 text-right text-slate-400">
                    {(m.contextWindow ?? 0).toLocaleString()}
                  </td>
                  <td className="py-2 text-right">
                    <select
                      value={m.status}
                      onChange={(e) => setStatus(m.id, e.target.value)}
                      className="rounded border border-edge bg-ink px-2 py-1 text-[11px] text-slate-300 outline-none focus:border-aurora/60"
                    >
                      {["DISCOVERED", "TESTING", "ACTIVE", "DEPRECATED", "REMOVED"].map((s) => (
                        <option key={s}>{s}</option>
                      ))}
                    </select>
                  </td>
                </tr>
              ))}
              {models.length === 0 && (
                <tr>
                  <td colSpan={5} className="py-3 text-[11px] text-slate-500">no models registered</td>
                </tr>
              )}
            </tbody>
          </table>
        </div>
      </section>

      {/* ---- playground ---- */}
      <section>
        <Micro>Send a request</Micro>
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
            className="rounded border border-edge bg-ink px-3 py-2 font-mono text-xs text-slate-100 outline-none placeholder:text-slate-600 focus:border-aurora/60"
          />
          <input
            value={prompt}
            onChange={(e) => setPrompt(e.target.value)}
            className="rounded border border-edge bg-ink px-3 py-2 text-sm text-slate-100 outline-none focus:border-aurora/60"
          />
          <button
            onClick={sendChat}
            disabled={!apiKey || sending}
            className="rounded bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-indigo-500 disabled:opacity-50"
          >
            {sending ? "Sending…" : "Send"}
          </button>
        </div>
        {chatOut && (
          <Plane inset className="mt-2 max-h-[420px] overflow-y-auto p-3">
            <DataView value={chatOut} />
          </Plane>
        )}
      </section>
    </div>
  );
}
