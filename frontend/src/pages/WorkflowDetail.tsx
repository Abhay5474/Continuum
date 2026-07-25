import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api";
import type { WorkflowDetail } from "../types";
import StatusBadge from "../components/StatusBadge";
import DataView from "../system/DataView";

const EVENT_ICON: Record<string, string> = {
  WORKFLOW_STARTED: "\u25B6",       // ▶
  ACTIVITY_SCHEDULED: "\u25CB",     // ○
  ACTIVITY_STARTED: "\u25D4",       // ◔
  ACTIVITY_COMPLETED: "\u25CF",     // ●
  ACTIVITY_FAILED: "\u25B2",        // ▲
  RETRY_SCHEDULED: "\u21BB",        // ↻
  SIDE_EFFECT_RECORDED: "\u25C6",   // ◆
  WORKFLOW_COMPLETED: "\u2713",     // ✓
  WORKFLOW_FAILED: "\u2715",        // ✕
};

export default function WorkflowDetailPage() {
  const { id } = useParams();
  const [detail, setDetail] = useState<WorkflowDetail | null>(null);
  const [healing, setHealing] = useState<any | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    const load = () => {
      api.workflow(id).then(setDetail).catch((e) => setErr(String(e)));
      api.get<any>(`/api/gateway/healing/workflow/${id}`).then(setHealing).catch(() => {});
    };
    load();
    const t = setInterval(load, 1500);
    return () => clearInterval(t);
  }, [id]);

  if (err) return <div className="text-rose-400">{err}</div>;
  if (!detail) return <div className="text-slate-400">Loading…</div>;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <Link to="/dashboard" className="text-sm text-slate-400 hover:text-slate-200">
          ← Back
        </Link>
        <StatusBadge status={detail.summary.status} />
        <h1 className="text-lg font-semibold">{detail.summary.workflowType}</h1>
        <span className="font-mono text-xs text-slate-400">{detail.summary.workflowId}</span>
        {healing?.healed && (
          <>
            <span className="animate-pulse rounded bg-indigo-500/20 px-2 py-0.5 text-xs font-semibold text-indigo-300">
              PARADOX RESOLVED
            </span>
            <span className="rounded bg-emerald-500/20 px-2 py-0.5 text-xs font-medium text-emerald-300">
              HISTORY ALIGNED ×{healing.resolutionCount}
            </span>
          </>
        )}
      </div>

      {healing?.healed && (
        <div className="rounded-lg border border-indigo-500/40 bg-panel p-4 transition-colors duration-300">
          <div className="text-sm font-medium">Paradox Resolution Ledger (this instance)</div>
          <div className="mt-1 text-xs text-slate-400">
            This workflow survived a code-graph change: the replay engine virtualized the structural
            gaps below so execution continued without errors or duplicated side effects.
          </div>
          <div className="mt-2 space-y-1">
            {healing.ledger.map((l: any, i: number) => (
              <div key={i} className="flex flex-wrap items-center gap-2 text-xs">
                <span className={`rounded px-2 py-0.5 font-medium ${
                  l.resolutionType === "INSERTION_MAPPED" ? "bg-emerald-500/20 text-emerald-300"
                    : l.resolutionType === "DELETION_SKIPPED" ? "bg-amber-500/20 text-amber-300"
                    : "bg-sky-500/20 text-sky-300"}`}>
                  {l.resolutionType}
                </span>
                <span className="font-mono text-slate-300">
                  code seq {l.virtualizedPayload?.codeSeq} → history seq {l.virtualizedPayload?.historySeq}
                </span>
                <span className="text-slate-500">{l.virtualizedPayload?.activityType}</span>
                <span className="ml-auto text-slate-500">{new Date(l.createdAt).toLocaleTimeString()}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2 rounded-lg border border-edge bg-panel">
          <div className="border-b border-edge px-4 py-3 font-medium">Event Timeline</div>
          <ol className="relative space-y-0">
            {detail.events.map((e) => (
              <li key={e.sequenceNumber} className="flex gap-3 border-b border-edge/50 px-4 py-2">
                <span className="w-8 text-right font-mono text-xs text-slate-500">
                  {e.sequenceNumber}
                </span>
                <span>{EVENT_ICON[e.eventType] ?? "•"}</span>
                <div className="min-w-0">
                  <div className="text-sm font-medium">{e.eventType}</div>
                  {e.payload && (
                    <pre className="mt-0.5 overflow-x-auto whitespace-pre-wrap break-words text-xs text-slate-400">
                      {summarize(e.payload)}
                    </pre>
                  )}
                </div>
                <span className="ml-auto whitespace-nowrap text-xs text-slate-500">
                  {new Date(e.createdAt).toLocaleTimeString()}
                </span>
              </li>
            ))}
          </ol>
        </div>

        <div className="space-y-6">
          <Panel title="Activities">
            {detail.activities.map((a) => (
              <div key={a.sequenceNumber} className="flex items-center gap-2 py-1 text-sm">
                <StatusBadge status={a.status} />
                <span>{a.activityType}</span>
                {a.retryCount > 0 && (
                  <span className="text-xs text-amber-400">retries: {a.retryCount}</span>
                )}
              </div>
            ))}
            {detail.activities.length === 0 && <Empty />}
          </Panel>

          <Panel title="Outbox (exactly-once side effects)">
            {detail.outbox.map((o) => (
              <div key={o.id} className="flex items-center gap-2 py-1 text-sm">
                <StatusBadge status={o.status} />
                <span>{o.destination}</span>
                <span className="ml-auto text-xs text-slate-500">attempts: {o.attempts}</span>
              </div>
            ))}
            {detail.outbox.length === 0 && <Empty />}
          </Panel>

          <Panel title="Cost">
            <div className="flex justify-between text-sm">
              <span className="text-slate-400">Tokens</span>
              <span>{detail.tokens}</span>
            </div>
            <div className="flex justify-between text-sm">
              <span className="text-slate-400">Cost</span>
              <span>${detail.costUsd.toFixed(6)}</span>
            </div>
          </Panel>

          {detail.result && (
            <Panel title="Result">
              <DataView value={detail.result} />
            </Panel>
          )}
          {detail.error && (
            <Panel title="Error">
              <pre className="whitespace-pre-wrap text-xs text-rose-300">{detail.error}</pre>
            </Panel>
          )}
        </div>
      </div>
    </div>
  );
}

function Panel({ title, children }: { title: string; children: import("react").ReactNode }) {
  return (
    <div className="rounded-lg border border-edge bg-panel">
      <div className="border-b border-edge px-4 py-2 text-sm font-medium">{title}</div>
      <div className="px-4 py-2">{children}</div>
    </div>
  );
}

function Empty() {
  return <div className="py-1 text-xs text-slate-500">none</div>;
}

function summarize(payload: any): string {
  const s = JSON.stringify(payload);
  return s.length > 200 ? s.slice(0, 200) + "…" : s;
}
