import { useEffect, useState } from "react";
import { api } from "../api";

const FAILURE_TYPES = [
  "HALLUCINATION",
  "SCHEMA_CORRUPTION",
  "TOOL_CORRUPTION",
  "PROMPT_INJECTION",
  "CONTEXT_TRUNCATION",
  "MEMORY_CORRUPTION",
  "PROVIDER_DRIFT",
];

interface AiChaosState {
  active: boolean;
  rates: Record<string, number>;
}
interface Metrics {
  totalInjections: number;
  injectionsByType: Record<string, number>;
  affectedWorkflows: number;
  survived: number;
  failed: number;
  running: number;
  workflowSurvivalRate: number;
}

export default function AiChaosLab() {
  const [state, setState] = useState<AiChaosState | null>(null);
  const [metrics, setMetrics] = useState<Metrics | null>(null);
  const [events, setEvents] = useState<any[]>([]);

  const refresh = () => {
    api.get<AiChaosState>("/api/ai-chaos").then(setState).catch(() => {});
    api.get<Metrics>("/api/ai-chaos/metrics").then(setMetrics).catch(() => {});
    api.get<any[]>("/api/ai-chaos/events?limit=20").then(setEvents).catch(() => {});
  };
  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 2500);
    return () => clearInterval(t);
  }, []);

  const setRate = async (type: string, rate: number) => {
    await api.post(`/api/ai-chaos/rate?type=${type}&rate=${rate}`);
    refresh();
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-lg font-semibold">Model Failure Simulation</h1>
        <p className="text-sm text-slate-400">
          Inject AI-native failures — hallucinations, schema/tool corruption, prompt injection, context
          truncation, memory corruption, provider drift — then measure whether workflows survive.
        </p>
      </div>

      <div className="grid gap-4 lg:grid-cols-3">
        <div className="lg:col-span-2 rounded-lg border border-edge bg-panel p-4">
          <div className="mb-3 font-medium">Failure injectors (probability per LLM call)</div>
          <div className="space-y-3">
            {FAILURE_TYPES.map((t) => (
              <div key={t} className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="w-full text-sm sm:w-48">{t}</span>
                <input
                  type="range"
                  min={0}
                  max={1}
                  step={0.1}
                  value={state?.rates[t] ?? 0}
                  onChange={(e) => setRate(t, parseFloat(e.target.value))}
                  className="flex-1"
                />
                <span className="w-10 text-right text-sm tabular-nums">
                  {((state?.rates[t] ?? 0) * 100).toFixed(0)}%
                </span>
              </div>
            ))}
          </div>
          <button
            onClick={() => api.post("/api/ai-chaos/reset").then(refresh)}
            className="mt-4 rounded-md border border-edge px-3 py-1.5 text-sm hover:bg-edge"
          >
            Reset all
          </button>
        </div>

        <div className="space-y-4">
          <div className="rounded-lg border border-edge bg-panel p-4">
            <div className="font-medium">Survival metrics</div>
            <div className="mt-2 space-y-1 text-sm">
              <Row label="Total injections" value={metrics?.totalInjections ?? 0} />
              <Row label="Affected workflows" value={metrics?.affectedWorkflows ?? 0} />
              <Row label="Survived" value={metrics?.survived ?? 0} accent="text-emerald-300" />
              <Row label="Failed" value={metrics?.failed ?? 0} accent="text-rose-300" />
              <Row
                label="Survival rate"
                value={`${(((metrics?.workflowSurvivalRate ?? 1) * 100) || 0).toFixed(0)}%`}
                accent="text-indigo-300"
              />
            </div>
          </div>
          <div className="rounded-lg border border-edge bg-panel p-4">
            <div className="font-medium">By type</div>
            <div className="mt-2 space-y-1 text-xs text-slate-400">
              {Object.entries(metrics?.injectionsByType ?? {}).map(([k, v]) => (
                <div key={k} className="flex justify-between">
                  <span>{k}</span>
                  <span>{v}</span>
                </div>
              ))}
            </div>
          </div>
        </div>
      </div>

      <div className="rounded-lg border border-edge bg-panel">
        <div className="border-b border-edge px-4 py-2 font-medium">Recent injections</div>
        <div className="divide-y divide-edge text-sm">
          {events.map((e) => (
            <div key={e.id} className="flex items-center gap-3 px-4 py-2">
              <span className="rounded bg-rose-500/20 px-2 py-0.5 text-xs text-rose-300">{e.failureType}</span>
              <span className="font-mono text-xs text-slate-400">{(e.workflowId || "").slice(0, 8)}</span>
              <span className="text-xs text-slate-400">{e.detail}</span>
            </div>
          ))}
          {events.length === 0 && <div className="px-4 py-3 text-xs text-slate-500">no injections yet</div>}
        </div>
      </div>
    </div>
  );
}

function Row({ label, value, accent }: { label: string; value: any; accent?: string }) {
  return (
    <div className="flex justify-between">
      <span className="text-slate-400">{label}</span>
      <span className={accent}>{value}</span>
    </div>
  );
}
