import { useState } from "react";
import { api } from "../api";

export default function ReplayVerify() {
  const [workflowId, setWorkflowId] = useState("");
  const [report, setReport] = useState<any | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const run = async () => {
    setBusy(true);
    setError(null);
    try {
      setReport(await api.post(`/api/replay/verify/${workflowId}`));
    } catch (e: any) {
      setError(String(e.message ?? e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-lg font-semibold">Semantic Replay Verification</h1>
        <p className="text-sm text-slate-400">
          Re-run a workflow's LLM activities against today's provider and score whether the historical
          outputs remain semantically equivalent — would this workflow still behave correctly now?
        </p>
      </div>

      <div className="flex gap-2">
        <input
          value={workflowId}
          onChange={(e) => setWorkflowId(e.target.value)}
          placeholder="workflow id"
          className="flex-1 rounded-md border border-edge bg-ink px-3 py-2 text-sm font-mono"
        />
        <button
          onClick={run}
          disabled={busy || !workflowId}
          className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white disabled:opacity-50"
        >
          {busy ? "Verifying…" : "Verify replay"}
        </button>
      </div>
      {error && <div className="text-sm text-rose-400">{error}</div>}

      {report && (
        <>
          <div className="grid grid-cols-2 gap-3 sm:grid-cols-4">
            <Metric label="Replay confidence" value={pct(report.replayConfidence)} accent="text-indigo-300" />
            <Metric label="Semantic drift" value={pct(report.semanticDrift)} accent="text-amber-300" />
            <Metric label="Decision consistency" value={pct(report.decisionConsistency)} accent="text-emerald-300" />
            <Metric label="Passed / failed" value={`${report.passed} / ${report.failed}`} />
          </div>

          <div className="rounded-lg border border-edge bg-panel">
            <div className="border-b border-edge px-4 py-2 font-medium">Per-activity</div>
            <div className="divide-y divide-edge">
              {report.items?.map((it: any) => (
                <div key={it.commandSeq} className="px-4 py-3">
                  <div className="flex items-center gap-2 text-sm">
                    <span
                      className={`rounded px-2 py-0.5 text-xs ${
                        it.passed ? "bg-emerald-500/20 text-emerald-300" : "bg-rose-500/20 text-rose-300"
                      }`}
                    >
                      {it.passed ? "PASS" : "FAIL"}
                    </span>
                    <span className="text-slate-400">seq {it.commandSeq}</span>
                    <span className="text-slate-400">overall {it.overallScore?.toFixed(3)}</span>
                  </div>
                  <div className="mt-1 text-xs text-slate-500">{it.explanation}</div>
                  <div className="mt-2 grid gap-2 md:grid-cols-2">
                    <pre className="overflow-x-auto rounded bg-ink p-2 text-xs text-slate-400">
                      hist: {it.historicalOutput?.slice(0, 200)}
                    </pre>
                    <pre className="overflow-x-auto rounded bg-ink p-2 text-xs text-slate-400">
                      fresh: {it.freshOutput?.slice(0, 200)}
                    </pre>
                  </div>
                </div>
              ))}
            </div>
          </div>
        </>
      )}
    </div>
  );
}

function pct(v: number) {
  return `${(((v ?? 0) * 100) || 0).toFixed(0)}%`;
}
function Metric({ label, value, accent }: { label: string; value: any; accent?: string }) {
  return (
    <div className="rounded-lg border border-edge bg-panel p-4">
      <div className="text-xs uppercase text-slate-400">{label}</div>
      <div className={`mt-1 text-2xl font-semibold ${accent ?? ""}`}>{value}</div>
    </div>
  );
}
