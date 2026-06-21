import { useEffect, useState } from "react";
import { api } from "../api";

const MODES = ["LOW_COST", "LOW_LATENCY", "HIGH_QUALITY", "BALANCED"];

export default function ModelRouter() {
  const [routing, setRouting] = useState<{ enabled: boolean; mode: string } | null>(null);
  const [providers, setProviders] = useState<any[]>([]);
  const [hedging, setHedging] = useState<any | null>(null);
  const [preview, setPreview] = useState<any | null>(null);
  const [prompt, setPrompt] = useState("Analyze and evaluate the risk of this complex financial report in depth");

  const refresh = () => {
    api.get<any>("/api/routing/state").then(setRouting).catch(() => {});
    api.get<any[]>("/api/routing/providers").then(setProviders).catch(() => {});
    api.get<any>("/api/hedging").then(setHedging).catch(() => {});
  };
  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 3000);
    return () => clearInterval(t);
  }, []);

  const runPreview = async () => {
    setPreview(await api.post("/api/routing/select", { userPrompt: prompt, mode: routing?.mode }));
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-lg font-semibold">AI-Aware Model Router</h1>
        <p className="text-sm text-slate-400">
          Cost / latency / quality-aware provider selection driven by measured runtime stats, plus
          tail-latency hedging. Operates above the provider layer; failover semantics are unchanged.
        </p>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <div className="rounded-lg border border-edge bg-panel p-4">
          <div className="font-medium">Routing</div>
          <div className="mt-3 flex items-center gap-3">
            <button
              onClick={() => api.post(`/api/routing/enable?enabled=${!routing?.enabled}`).then(refresh)}
              className={`rounded-md px-3 py-1.5 text-sm font-medium ${
                routing?.enabled ? "bg-emerald-600 text-white" : "border border-edge"
              }`}
            >
              {routing?.enabled ? "Enabled" : "Disabled"}
            </button>
            <select
              value={routing?.mode ?? "BALANCED"}
              onChange={(e) => api.post(`/api/routing/mode?mode=${e.target.value}`).then(refresh)}
              className="rounded-md border border-edge bg-ink px-3 py-1.5 text-sm"
            >
              {MODES.map((m) => (
                <option key={m}>{m}</option>
              ))}
            </select>
          </div>
        </div>

        <div className="rounded-lg border border-edge bg-panel p-4">
          <div className="font-medium">Tail-latency hedging</div>
          <div className="mt-3 flex items-center gap-3">
            <button
              onClick={() => api.post(`/api/hedging/enable?enabled=${!hedging?.enabled}`).then(refresh)}
              className={`rounded-md px-3 py-1.5 text-sm font-medium ${
                hedging?.enabled ? "bg-emerald-600 text-white" : "border border-edge"
              }`}
            >
              {hedging?.enabled ? "Enabled" : "Disabled"}
            </button>
            <span className="text-xs text-slate-400">
              threshold {hedging?.thresholdMs}ms · maxHedges {hedging?.maxHedges}
            </span>
          </div>
        </div>
      </div>

      <div className="rounded-lg border border-edge bg-panel">
        <div className="border-b border-edge px-4 py-2 font-medium">Measured provider stats</div>
        <table className="w-full text-sm">
          <thead className="text-xs text-slate-400">
            <tr className="text-left">
              <th className="px-4 py-2">Provider</th>
              <th className="px-4 py-2">Calls</th>
              <th className="px-4 py-2">Avg latency</th>
              <th className="px-4 py-2">Error rate</th>
              <th className="px-4 py-2">Total cost</th>
            </tr>
          </thead>
          <tbody>
            {providers.map((p) => (
              <tr key={p.provider} className="border-t border-edge/50">
                <td className="px-4 py-2 font-medium">{p.provider}</td>
                <td className="px-4 py-2">{p.calls}</td>
                <td className="px-4 py-2">{p.avgLatencyMs?.toFixed(1)} ms</td>
                <td className="px-4 py-2">{(p.errorRate * 100).toFixed(1)}%</td>
                <td className="px-4 py-2">${p.totalCostUsd?.toFixed(6)}</td>
              </tr>
            ))}
            {providers.length === 0 && (
              <tr>
                <td className="px-4 py-3 text-xs text-slate-500" colSpan={5}>
                  no calls recorded yet — run a workflow
                </td>
              </tr>
            )}
          </tbody>
        </table>
      </div>

      <div className="rounded-lg border border-edge bg-panel p-4">
        <div className="font-medium">Routing preview (real scoring)</div>
        <textarea
          value={prompt}
          onChange={(e) => setPrompt(e.target.value)}
          className="mt-2 h-20 w-full rounded-md border border-edge bg-ink p-2 text-sm"
        />
        <button onClick={runPreview} className="mt-2 rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white">
          Score providers
        </button>
        {preview && (
          <div className="mt-3 text-sm">
            <div className="text-slate-400">
              complexity {preview.complexity?.toFixed(3)} · chosen {JSON.stringify(preview.chosenChain)}
            </div>
            <div className="mt-2 space-y-1">
              {preview.scores?.map((s: any) => (
                <div key={s.provider} className="flex justify-between text-xs">
                  <span>{s.provider}</span>
                  <span className="text-slate-400">
                    total {s.totalScore?.toFixed(3)} (cost {s.costScore?.toFixed(2)}, lat{" "}
                    {s.latencyScore?.toFixed(2)}, qual {s.qualityScore?.toFixed(2)})
                  </span>
                </div>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
