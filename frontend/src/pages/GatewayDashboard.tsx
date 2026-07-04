import { useEffect, useState } from "react";
import { api } from "../api";

export default function GatewayDashboard() {
  const [stats, setStats] = useState<any | null>(null);
  const [models, setModels] = useState<any[]>([]);
  const [requests, setRequests] = useState<any[]>([]);
  const [health, setHealth] = useState<any[]>([]);
  const [healing, setHealing] = useState<any | null>(null);
  const [verifyOut, setVerifyOut] = useState<any | null>(null);
  const [verifying, setVerifying] = useState(false);

  // onboarding + playground state
  const [devName, setDevName] = useState("MyAIApp");
  const [apiKey, setApiKey] = useState("");
  const [prompt, setPrompt] = useState("Provide first aid for a dog leg injury");
  const [chatOut, setChatOut] = useState<any | null>(null);
  const [msg, setMsg] = useState("");

  const refresh = () => {
    api.get<any>("/api/gateway/stats").then(setStats).catch(() => {});
    api.get<any[]>("/api/models").then(setModels).catch(() => {});
    api.get<any[]>("/api/gateway/requests?limit=15").then(setRequests).catch(() => {});
    api.get<any[]>("/api/gateway/health").then(setHealth).catch(() => {});
    api.get<any>("/api/gateway/healing/status").then(setHealing).catch(() => {});
  };

  const runVerifyScan = async () => {
    setVerifying(true);
    try {
      setVerifyOut(await api.post("/api/gateway/healing/verify", {}));
    } catch (e: any) {
      setVerifyOut({ error: e.message ?? String(e) });
    } finally {
      setVerifying(false);
    }
  };
  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 3000);
    return () => clearInterval(t);
  }, []);

  const onboard = async () => {
    try {
      const dev: any = await api.post("/api/admin/developers", { name: devName, email: "" });
      const key: any = await api.post(`/api/admin/developers/${dev.id}/keys`);
      setApiKey(key.apiKey);
      setMsg(`Created ${dev.id}. API key issued (shown once).`);
    } catch (e: any) {
      setMsg("Onboarding failed: " + (e.message ?? e));
    }
  };

  const sendChat = async () => {
    setChatOut(null);
    try {
      const res = await fetch("/api/gateway/chat", {
        method: "POST",
        headers: { "Content-Type": "application/json", Authorization: `Bearer ${apiKey}` },
        body: JSON.stringify({ model: "auto", messages: [{ role: "user", content: prompt }], maxTokens: 200 }),
      });
      setChatOut(await res.json());
    } catch (e: any) {
      setChatOut({ error: String(e) });
    }
  };

  const lifecycle = ["DISCOVERED", "TESTING", "ACTIVE", "DEPRECATED", "REMOVED"];
  const setStatus = (id: number, status: string) =>
    api.post(`/api/models/${id}/status?status=${status}`).then(refresh);

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-lg font-semibold">Developer Infrastructure Gateway</h1>
        <p className="text-sm text-slate-400">
          External apps integrate once against the Continuum gateway and get reliability, routing,
          model lifecycle management and observability — without touching provider SDKs.
        </p>
      </div>

      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <Stat label="Requests" value={stats?.totalRequests ?? "—"} />
        <Stat label="Success rate" value={stats ? `${Math.round(stats.successRate * 100)}%` : "—"} accent="text-emerald-300" />
        <Stat label="Failures prevented" value={stats?.failuresPrevented ?? "—"} accent="text-indigo-300" />
        <Stat label="Dev-visible failures" value={stats?.developerVisibleFailures ?? "—"} accent="text-rose-300" />
        <Stat label="Tokens" value={stats?.totalTokens ?? "—"} />
        <Stat label="Cost" value={stats ? `$${(stats.totalCostUsd ?? 0).toFixed(5)}` : "—"} />
      </div>

      <div className="grid gap-6 lg:grid-cols-2">
        <div className="rounded-lg border border-edge bg-panel p-4">
          <div className="font-medium">Quick start</div>
          <div className="mt-2 flex gap-2">
            <input value={devName} onChange={(e) => setDevName(e.target.value)}
              className="flex-1 rounded-md border border-edge bg-ink px-3 py-1.5 text-sm" />
            <button onClick={onboard} className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white">
              Create developer + key
            </button>
          </div>
          {apiKey && (
            <div className="mt-2 break-all rounded bg-ink p-2 font-mono text-xs text-emerald-300">{apiKey}</div>
          )}
          {msg && <div className="mt-1 text-xs text-slate-400">{msg}</div>}

          <div className="mt-4 font-medium">Try the gateway</div>
          <textarea value={prompt} onChange={(e) => setPrompt(e.target.value)}
            className="mt-2 h-16 w-full rounded-md border border-edge bg-ink p-2 text-sm" />
          <button onClick={sendChat} disabled={!apiKey}
            className="mt-2 rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white disabled:opacity-50">
            POST /api/gateway/chat
          </button>
          {chatOut && (
            <pre className="mt-2 overflow-x-auto rounded bg-ink p-2 text-xs text-slate-300">
              {JSON.stringify(chatOut, null, 2)}
            </pre>
          )}
        </div>

        <div className="rounded-lg border border-edge bg-panel p-4">
          <div className="font-medium">Provider / model health</div>
          <div className="mt-2 space-y-1 text-sm">
            {health.map((h) => (
              <div key={h.id} className="flex items-center gap-2 text-xs">
                <span className={`h-2 w-2 rounded-full ${h.healthScore >= 0.5 ? "bg-emerald-400" : "bg-rose-400"}`} />
                <span className="font-mono">{h.provider}/{h.modelName}</span>
                <span className="ml-auto text-slate-400">
                  {h.calls} calls · {h.failures} fail · health {(h.healthScore * 100).toFixed(0)}%
                </span>
              </div>
            ))}
            {health.length === 0 && <div className="text-xs text-slate-500">no calls yet</div>}
          </div>
          <div className="mt-3 text-xs text-slate-400">Provider usage</div>
          {stats?.providerUsage &&
            Object.entries(stats.providerUsage).map(([p, v]: any) => (
              <div key={p} className="flex justify-between text-xs">
                <span>{p}</span>
                <span className="text-slate-400">{v.requests} req · ${v.cost?.toFixed(5)}</span>
              </div>
            ))}
        </div>
      </div>

      <div className="rounded-lg border border-edge bg-panel">
        <div className="border-b border-edge px-4 py-2 font-medium">Model registry & lifecycle</div>
        <table className="w-full text-sm">
          <thead className="text-xs text-slate-400">
            <tr className="text-left">
              <th className="px-4 py-2">Provider</th><th className="px-4 py-2">Model</th>
              <th className="px-4 py-2">Status</th><th className="px-4 py-2">Context</th>
              <th className="px-4 py-2">Lifecycle</th>
            </tr>
          </thead>
          <tbody>
            {models.map((m) => (
              <tr key={m.id} className="border-t border-edge/50">
                <td className="px-4 py-2">{m.provider}</td>
                <td className="px-4 py-2 font-mono text-xs">{m.modelName}</td>
                <td className="px-4 py-2">{m.status}</td>
                <td className="px-4 py-2">{m.contextWindow.toLocaleString()}</td>
                <td className="px-4 py-2">
                  <select value={m.status} onChange={(e) => setStatus(m.id, e.target.value)}
                    className="rounded border border-edge bg-ink px-2 py-1 text-xs">
                    {lifecycle.map((s) => <option key={s}>{s}</option>)}
                  </select>
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      <div className="rounded-lg border border-indigo-500/40 bg-panel shadow-[0_0_24px_-12px_rgba(99,102,241,0.6)] transition-shadow duration-500">
        <div className="flex items-center gap-3 border-b border-edge px-4 py-3">
          <span className="relative flex h-2.5 w-2.5">
            <span className="absolute inline-flex h-full w-full animate-ping rounded-full bg-indigo-400 opacity-60" />
            <span className="relative inline-flex h-2.5 w-2.5 rounded-full bg-indigo-400" />
          </span>
          <div>
            <div className="font-semibold tracking-wide">Paradox Resolution Ledger</div>
            <div className="text-xs text-slate-400">
              Compulsory auto-healing of code↔history determinism divergence — deploys never crash in-flight workflows.
            </div>
          </div>
          <button onClick={runVerifyScan} disabled={verifying}
            className="ml-auto rounded-md bg-indigo-600 px-3 py-1.5 text-xs text-white transition-opacity hover:opacity-90 disabled:opacity-50">
            {verifying ? "Scanning…" : "Run verification scan"}
          </button>
        </div>

        <div className="grid grid-cols-2 gap-3 p-4 sm:grid-cols-5">
          <Stat label="Paradoxes resolved" value={healing?.totalResolutions ?? "—"} accent="text-indigo-300" />
          <Stat label="Healed workflows" value={healing?.healedWorkflows ?? "—"} accent="text-emerald-300" />
          <Stat label="Insertions mapped" value={healing?.byResolutionType?.INSERTION_MAPPED ?? "—"} />
          <Stat label="Deletions skipped" value={healing?.byResolutionType?.DELETION_SKIPPED ?? "—"} />
          <Stat label="Reorders aligned" value={healing?.byResolutionType?.REORDER_ALIGNED ?? "—"} />
        </div>

        <div className="px-4 pb-4">
          <div className="text-xs uppercase tracking-wide text-slate-400">Auto-healing timeline</div>
          <div className="mt-2 max-h-64 space-y-2 overflow-y-auto pr-1">
            {(healing?.recentResolutions ?? []).map((r: any, i: number) => (
              <div key={i}
                className="flex flex-wrap items-center gap-2 rounded-md border border-edge bg-ink/60 px-3 py-2 text-xs transition-colors duration-300 hover:border-indigo-500/50">
                <span className="rounded bg-indigo-500/20 px-2 py-0.5 font-semibold text-indigo-300">
                  PARADOX RESOLVED
                </span>
                <span className={`rounded px-2 py-0.5 font-medium ${
                  r.resolutionType === "INSERTION_MAPPED" ? "bg-emerald-500/20 text-emerald-300"
                    : r.resolutionType === "DELETION_SKIPPED" ? "bg-amber-500/20 text-amber-300"
                    : "bg-sky-500/20 text-sky-300"}`}>
                  {r.resolutionType}
                </span>
                <span className="rounded bg-slate-500/20 px-2 py-0.5 text-slate-300">HISTORY ALIGNED</span>
                <span className="font-mono text-slate-300">{r.workflowId}</span>
                <span className="text-slate-500">
                  seq {r.codeSequence} → {r.historySequence ?? "∅"} · {r.workflowType}
                </span>
                <span className="ml-auto text-slate-500">
                  {r.resolvedAt ? new Date(r.resolvedAt).toLocaleTimeString() : ""}
                </span>
              </div>
            ))}
            {(healing?.recentResolutions ?? []).length === 0 && (
              <div className="rounded-md border border-dashed border-edge px-3 py-3 text-xs text-slate-500">
                No divergence paradoxes detected — every deployed code graph currently matches its recorded history.
              </div>
            )}
          </div>

          {verifyOut && (
            <div className="mt-3 rounded-md border border-edge bg-ink p-3 text-xs">
              {verifyOut.error ? (
                <span className="text-rose-300">{verifyOut.error}</span>
              ) : (
                <>
                  <div className="flex items-center gap-2">
                    <span className={`rounded px-2 py-0.5 font-semibold ${
                      verifyOut.divergedInstances > 0
                        ? "bg-amber-500/20 text-amber-300" : "bg-emerald-500/20 text-emerald-300"}`}>
                      {verifyOut.divergedInstances > 0 ? "DIVERGENCES PENDING HEAL" : "ALL ALIGNED"}
                    </span>
                    <span className="text-slate-400">
                      scanned {verifyOut.scanned} running instance(s) · {verifyOut.divergedInstances} diverged
                    </span>
                  </div>
                  {verifyOut.divergedInstances > 0 && (
                    <pre className="mt-2 max-h-40 overflow-auto text-slate-400">
                      {JSON.stringify(verifyOut.results.filter((x: any) => x.diverged), null, 2)}
                    </pre>
                  )}
                </>
              )}
            </div>
          )}
        </div>
      </div>

      <div className="rounded-lg border border-edge bg-panel">
        <div className="border-b border-edge px-4 py-2 font-medium">Recent routing decisions</div>
        <div className="divide-y divide-edge text-sm">
          {requests.map((r) => (
            <div key={r.id} className="px-4 py-2">
              <div className="flex items-center gap-2">
                <span className={`rounded px-2 py-0.5 text-xs ${r.success ? "bg-emerald-500/20 text-emerald-300" : "bg-rose-500/20 text-rose-300"}`}>
                  {r.success ? "OK" : "FAIL"}
                </span>
                <span className="text-xs">{r.chosenProvider}/{r.chosenModel}</span>
                {r.failoverCount > 0 && <span className="text-xs text-amber-400">{r.failoverCount} failover(s)</span>}
                <span className="ml-auto text-xs text-slate-500">{r.latencyMs}ms</span>
              </div>
              <div className="text-xs text-slate-500">{r.routingReason}</div>
            </div>
          ))}
          {requests.length === 0 && <div className="px-4 py-3 text-xs text-slate-500">no gateway requests yet</div>}
        </div>
      </div>
    </div>
  );
}

function Stat({ label, value, accent }: { label: string; value: any; accent?: string }) {
  return (
    <div className="rounded-lg border border-edge bg-panel p-4">
      <div className="text-xs uppercase text-slate-400">{label}</div>
      <div className={`mt-1 text-2xl font-semibold ${accent ?? ""}`}>{value}</div>
    </div>
  );
}
