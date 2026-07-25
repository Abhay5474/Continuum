import { useEffect, useMemo, useState } from "react";
import { Link, useParams } from "react-router-dom";
import { api } from "../api";
import type { WorkflowDetail } from "../types";
import StatusBadge from "../components/StatusBadge";
import DataView from "../system/DataView";
import { elapsed, humanMs, timeOf } from "../system/time";

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

  // Only the scheduling event carries the step's name. Started and completed
  // events reference it by commandSeq, so the name is carried across here
  // rather than leaving two thirds of the timeline saying "httpStep".
  const stepNames = useMemo(() => {
    const m: Record<number, string> = {};
    for (const e of detail?.events ?? []) {
      const p: any = unwrap(e.payload);
      if (p?.commandSeq == null) continue;
      const name = stepOf(p);
      if (name) m[p.commandSeq] = name;
    }
    return m;
  }, [detail]);

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
                <span className="ml-auto text-slate-500">{timeOf(l.createdAt)}</span>
              </div>
            ))}
          </div>
        </div>
      )}

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="lg:col-span-2 rounded-lg border border-edge bg-panel">
          <div className="border-b border-edge px-4 py-3 font-medium">Event Timeline</div>
          <ol className="relative space-y-0">
            {detail.events.map((e, i) => (
              <EventRow
                key={e.sequenceNumber}
                event={e}
                previous={i > 0 ? detail.events[i - 1] : null}
                stepNames={stepNames}
              />
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

/**
 * One event in the run's history.
 *
 * The payload arrives with JSON nested inside JSON strings, which stringifies
 * into a wall of escaped quotes. So it is unwrapped first and shown as
 * structure: a headline naming what the event was about, the gap since the
 * previous event — which is how a durable wait becomes visible — and the full
 * payload only when asked for.
 */
function EventRow({
  event,
  previous,
  stepNames,
}: {
  event: any;
  previous: any | null;
  stepNames: Record<number, string>;
}) {
  const [open, setOpen] = useState(false);
  const payload = useMemo(() => unwrap(event.payload), [event.payload]);
  const gap = previous ? elapsed(previous.createdAt, event.createdAt) : null;
  const line = headline(payload, stepNames);
  const expandable = payload != null && typeof payload === "object";

  return (
    <li className="group border-b border-edge/50 px-4 py-2">
      <div className="flex items-baseline gap-3">
        <span className="w-8 shrink-0 text-right font-mono text-xs text-slate-500">
          {event.sequenceNumber}
        </span>
        <span className="shrink-0">{EVENT_ICON[event.eventType] ?? "•"}</span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-baseline gap-x-2">
            <span className="text-sm font-medium">{humanEvent(event.eventType)}</span>
            {line && <span className="truncate font-mono text-xs text-slate-400">{line}</span>}
            {expandable && (
              // Kept quiet: the payload is for when a summary is not enough,
              // so it should not compete with the summary for attention.
              <button
                onClick={() => setOpen((o) => !o)}
                className={`text-[10px] text-slate-500 transition-opacity hover:text-slate-300 focus:opacity-100 ${
                  open ? "opacity-100" : "opacity-0 group-hover:opacity-100"
                }`}
              >
                {open ? "− payload" : "+ payload"}
              </button>
            )}
          </div>
          {open && (
            <div className="mt-1.5 border-l border-edge pl-3">
              <DataView value={payload} />
            </div>
          )}
        </div>
        {/* Gap from the previous event: a six-second wait should be legible. */}
        <span className="w-14 shrink-0 text-right font-mono text-[10px] text-slate-600">
          {gap != null && gap >= 100 ? `+${humanMs(gap)}` : ""}
        </span>
        <span className="w-20 shrink-0 whitespace-nowrap text-right text-xs text-slate-500">
          {timeOf(event.createdAt)}
        </span>
      </div>
    </li>
  );
}

/** Recursively parses JSON that the API embedded as a string. */
function unwrap(v: unknown, depth = 0): unknown {
  if (depth > 6) return v;
  if (typeof v === "string") {
    const t = v.trim();
    if ((t.startsWith("{") && t.endsWith("}")) || (t.startsWith("[") && t.endsWith("]"))) {
      try {
        return unwrap(JSON.parse(t), depth + 1);
      } catch {
        return v;
      }
    }
    return v;
  }
  if (Array.isArray(v)) return v.map((x) => unwrap(x, depth + 1));
  if (v && typeof v === "object") {
    return Object.fromEntries(Object.entries(v).map(([k, x]) => [k, unwrap(x, depth + 1)]));
  }
  return v;
}

const EVENT_LABEL: Record<string, string> = {
  WORKFLOW_STARTED: "Run started",
  ACTIVITY_SCHEDULED: "Step scheduled",
  ACTIVITY_STARTED: "Step started",
  ACTIVITY_COMPLETED: "Step completed",
  ACTIVITY_FAILED: "Step failed",
  RETRY_SCHEDULED: "Retry scheduled",
  SIDE_EFFECT_RECORDED: "Side effect recorded",
  WORKFLOW_COMPLETED: "Run completed",
  WORKFLOW_FAILED: "Run failed",
};

function humanEvent(t: string): string {
  return EVENT_LABEL[t] ?? t.toLowerCase().replace(/_/g, " ");
}

/**
 * The step's own name, when the payload carries one.
 *
 * A declarative step signs its call with {@code <workflowId>:<stepId>}, so that
 * key names the step the author wrote. The engine's own idempotency key ends in
 * a command sequence number, which names nothing, so it is not used.
 */
function stepOf(p: any): string | null {
  const authored: unknown = p?.input?.idempotencyKey ?? p?.input?.stepId;
  if (typeof authored === "string" && authored) {
    return authored.includes(":") ? authored.slice(authored.lastIndexOf(":") + 1) : authored;
  }
  return null;
}

/** The one fact that identifies an event at a glance. */
function headline(p: any, stepNames: Record<number, string> = {}): string | null {
  if (!p || typeof p !== "object") return null;
  const step = stepOf(p) ?? (p.commandSeq != null ? stepNames[p.commandSeq] : null);
  const url: string | undefined = p.input?.url;
  const path = url ? String(url).replace(/^https?:\/\/[^/]+/, "") : null;

  if (p.activityType) {
    const parts = [step ?? p.activityType];
    if (path) parts.push(path);
    if (p.attempt > 1) parts.push(`attempt ${p.attempt}`);
    return parts.join("  ");
  }
  if (p.workflowType) return p.workflowType;
  return null;
}
