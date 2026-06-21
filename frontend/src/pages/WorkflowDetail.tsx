import { useEffect, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api";
import type { WorkflowDetail } from "../types";
import StatusBadge from "../components/StatusBadge";

const EVENT_ICON: Record<string, string> = {
  WORKFLOW_STARTED: "🚀",
  ACTIVITY_SCHEDULED: "📋",
  ACTIVITY_STARTED: "▶️",
  ACTIVITY_COMPLETED: "✅",
  ACTIVITY_FAILED: "⚠️",
  RETRY_SCHEDULED: "🔁",
  SIDE_EFFECT_RECORDED: "🎲",
  WORKFLOW_COMPLETED: "🏁",
  WORKFLOW_FAILED: "❌",
};

export default function WorkflowDetailPage() {
  const { id } = useParams();
  const [detail, setDetail] = useState<WorkflowDetail | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    if (!id) return;
    const load = () => api.workflow(id).then(setDetail).catch((e) => setErr(String(e)));
    load();
    const t = setInterval(load, 1500);
    return () => clearInterval(t);
  }, [id]);

  if (err) return <div className="text-rose-400">{err}</div>;
  if (!detail) return <div className="text-slate-400">Loading…</div>;

  return (
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <Link to="/" className="text-sm text-slate-400 hover:text-slate-200">
          ← Back
        </Link>
        <StatusBadge status={detail.summary.status} />
        <h1 className="text-lg font-semibold">{detail.summary.workflowType}</h1>
        <span className="font-mono text-xs text-slate-400">{detail.summary.workflowId}</span>
      </div>

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
              <pre className="overflow-x-auto whitespace-pre-wrap break-words text-xs text-slate-300">
                {JSON.stringify(detail.result, null, 2)}
              </pre>
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

function Panel({ title, children }: { title: string; children: React.ReactNode }) {
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
