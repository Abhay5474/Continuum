import { useEffect, useMemo, useState } from "react";
import { Link, useNavigate, useParams } from "react-router-dom";
import { api } from "../api";
import { Chip } from "../system/hub";
import type { WorkflowDetail } from "../types";
import StatusBadge from "../components/StatusBadge";
import DataView from "../system/DataView";
import { elapsed, humanMs, timeOf } from "../system/time";
import { Button } from "../system/controls";
import { useToast } from "../components/ui";
import { Waterfall, WaterfallLegend, type WaterfallRow } from "../components/Waterfall";
import { ExecutionFacts, Findings, TimeBudget } from "../components/RunDetail";
import { buildExecution, duration, type Execution } from "../system/execution/model";
import { findingsOf } from "../system/execution/findings";

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
  const [err, setErr] = useState<{ missing: boolean; message: string } | null>(null);
  const [stale, setStale] = useState(false);
  const [cancelling, setCancelling] = useState(false);
  const toast = useToast();
  const finished = detail != null && detail.summary.status !== "RUNNING";

  // Polls while the run can still change. It used to poll every 1.5s forever,
  // including on a run that finished hours ago; and one failed poll replaced
  // the whole page with the raw error, even with good data already on screen.
  useEffect(() => {
    if (!id) return;
    let alive = true;
    let timer: ReturnType<typeof setTimeout> | undefined;
    const load = async () => {
      try {
        const d = await api.workflow(id);
        if (!alive) return;
        setDetail(d);
        setErr(null);
        setStale(false);
        api.get<any>(`/api/gateway/healing/workflow/${id}`).then((h) => alive && setHealing(h)).catch(() => {});
        // Settled once the run has ended and nothing it started is still moving:
        // a step that was executing when the run was cancelled finishes later,
        // and stopping at the run's end left it showing RUNNING forever.
        const moving = d.activities.some((a) => a.status === "RUNNING" || a.status === "PENDING");
        if (d.summary.status !== "RUNNING" && !moving) return;
      } catch (e: any) {
        if (!alive) return;
        if (e?.status === 404 || e?.name === "ForbiddenError") {
          setErr({ missing: true, message: "" });
          return;
        }
        // Keep what is on screen and say it may be out of date; retry.
        setStale(true);
        setErr((prev) => prev ?? { missing: false, message: e?.body?.error ?? e?.message ?? String(e) });
      }
      timer = setTimeout(load, 1500);
    };
    setDetail(null);
    setErr(null);
    load();
    return () => {
      alive = false;
      clearTimeout(timer);
    };
  }, [id]);

  // The two halves of a cancelled saga point at each other: the cancelled run
  // links to the run that undid it, the rollback back to what it undid.
  const [rollbackId, setRollbackId] = useState<string | null>(null);
  const cancelledSaga = detail?.summary.workflowType === "Declarative" && isCancelled(detail?.error);
  useEffect(() => {
    setRollbackId(null);
    if (!id || !cancelledSaga) return;
    api.workflow(`${id}:rollback`).then(() => setRollbackId(`${id}:rollback`)).catch(() => {});
  }, [id, cancelledSaga]);
  const rolledBack: string | null =
    detail?.summary.workflowType === "DeclarativeRollback" ? (detail.input as any)?.cancelledWorkflowId ?? null : null;

  // One step chosen in the timeline is shown in the events and activities too.
  const [step, setStep] = useState<number | null>(null);
  useEffect(() => setStep(null), [id]);

  const nav = useNavigate();
  const [rerunning, setRerunning] = useState(false);
  const rerun = async () => {
    if (!id || rerunning) return;
    setRerunning(true);
    try {
      const r = await api.post<{ workflowId: string }>(`/api/workflows/${id}/rerun`);
      toast("Started again with the same input", "success");
      nav(`/workflows/${r.workflowId}`);
    } catch (e: any) {
      toast(`Could not start it again: ${e?.body?.error ?? e?.message ?? "request failed"}`, "error");
    } finally {
      setRerunning(false);
    }
  };

  const [resuming, setResuming] = useState(false);
  const resume = async () => {
    if (!id || resuming) return;
    setResuming(true);
    try {
      await api.post(`/api/workflows/${id}/resume`);
      setDetail(await api.workflow(id));
      toast("Trying again", "success");
    } catch (e: any) {
      toast(`Could not resume: ${e?.body?.message ?? e?.body?.error ?? e?.message ?? "request failed"}`, "error");
    } finally {
      setResuming(false);
    }
  };

  const cancel = async () => {
    if (!id || cancelling) return;
    if (!window.confirm("Stop this workflow? Steps already done stay done; anything waiting to run will not run.")) return;
    setCancelling(true);
    try {
      const r = await api.post<{ rollbackWorkflowId?: string }>(`/api/workflows/${id}/cancel`, {
        reason: "stopped from the console",
      });
      setDetail(await api.workflow(id));
      if (r.rollbackWorkflowId) setRollbackId(r.rollbackWorkflowId);
      toast(r.rollbackWorkflowId ? "Stopped — undoing the steps it completed" : "Workflow stopped", "success");
    } catch (e: any) {
      toast(`Could not stop the workflow: ${e?.body?.error ?? e?.message ?? "request failed"}`, "error");
    } finally {
      setCancelling(false);
    }
  };

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

  if (err?.missing)
    return (
      <div className="space-y-3">
        <Link to="/dashboard" className="text-sm text-slate-400 hover:text-slate-200">← Back</Link>
        <p className="text-sm text-slate-300">
          No workflow <span className="font-mono">{id}</span> in this account. It may have been started from another
          account, or the link is mistyped.
        </p>
      </div>
    );
  if (!detail)
    return err ? (
      <div className="text-sm text-rose-300" role="alert">Could not load this workflow: {err.message}. Retrying…</div>
    ) : (
      <div className="text-sm text-slate-400" role="status">Loading workflow {id}…</div>
    );

  return (
    <div className="space-y-8">
      <div className="flex flex-wrap items-center gap-3">
        <Link to="/dashboard" className="text-sm text-slate-400 hover:text-slate-200">
          ← Back
        </Link>
        <StatusBadge status={isCancelled(detail.error) ? "CANCELLED" : detail.stuck ? "STUCK" : detail.summary.status} />
        <div className="flex items-center gap-2.5">
          <Chip glyph="flow" tone="accent" size={28} />
          {/* A declarative run is named by its definition — "Declarative" is
              the engine's word for every one of them. */}
          {definitionOf(detail) ? (
            <h1 className="text-[20px] font-semibold tracking-[-0.011em]">
              {definitionOf(detail)!.name}
              <span className="ml-2 align-middle text-xs font-medium text-slate-500">
                v{definitionOf(detail)!.version} · {detail.summary.workflowType}
              </span>
            </h1>
          ) : (
            <h1 className="text-[20px] font-semibold tracking-[-0.011em]">{detail.summary.workflowType}</h1>
          )}
        </div>
        <span className="font-mono text-xs text-slate-400">{detail.summary.workflowId}</span>
        {stale && (
          <span className="text-xs text-amber-300" role="status" title={err?.message}>
            Connection lost — showing the last update
          </span>
        )}
        {!finished && (
          <Button variant="danger" size="sm" busy={cancelling} onClick={cancel} className="ml-auto"
            title="Stops the run. Waiting steps are withdrawn; with saga rollback on, completed steps are undone.">
            Stop workflow
          </Button>
        )}
        {finished && !(detail.summary.status === "FAILED" && detail.error && !isCancelled(detail.error)) && (
          <Button size="sm" busy={rerunning} onClick={rerun} className="ml-auto"
            title="Starts a new run with the same input. This one is kept as it is.">
            Run again
          </Button>
        )}
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

      {(rollbackId || rolledBack) && (
        <div className="-mt-4 flex items-center gap-2 text-[12.5px] text-slate-400">
          <span aria-hidden>↺</span>
          {rollbackId ? (
            <>
              Its completed steps are being undone in{" "}
              <Link className="font-medium text-[color:var(--accent-ink)] hover:underline" to={`/workflows/${rollbackId}`}>
                the rollback run
              </Link>
              .
            </>
          ) : (
            <>
              Undoes the steps completed by{" "}
              <Link className="font-mono text-[color:var(--accent-ink)] hover:underline" to={`/workflows/${rolledBack}`}>
                {rolledBack}
              </Link>
              , which was cancelled.
            </>
          )}
        </div>
      )}

      {detail.summary.status === "FAILED" && detail.error && !isCancelled(detail.error) && (
        <div role="alert" className="-mt-3 flex flex-wrap items-center gap-3 rounded-[var(--r-lg)] border border-rose-500/30 bg-rose-500/[0.07] px-4 py-3">
          <div className="min-w-0 flex-1">
            <div className="text-[13px] font-semibold text-rose-200">This run failed</div>
            <div className="mt-0.5 text-[12.5px] text-slate-300">{detail.error}</div>
          </div>
          <Button size="sm" busy={rerunning} onClick={rerun}>Run again</Button>
        </div>
      )}

      {detail.stuck && detail.summary.status === "RUNNING" && (
        <div role="alert" className="-mt-3 flex flex-wrap items-center gap-3 rounded-[var(--r-lg)] border border-amber-500/30 bg-amber-500/[0.07] px-4 py-3">
          <div className="min-w-0 flex-1">
            <div className="text-[13px] font-semibold text-amber-200">
              Stuck after {detail.stuck.attempts} tries
              <span className="ml-2 font-normal text-slate-400">since {timeOf(detail.stuck.since)}</span>
            </div>
            <div className="mt-0.5 break-words font-mono text-[12px] text-slate-300">{detail.stuck.error ?? "No error recorded"}</div>
            <div className="mt-1 text-[12px] text-slate-400">
              The engine stopped retrying so it would not loop forever. Nothing it did is lost. Fix the cause, then try again.
            </div>
          </div>
          <Button size="sm" busy={resuming} onClick={resume}>Try again</Button>
        </div>
      )}

      {healing?.healed && (
        <div className="card rounded-lg border border-indigo-500/40 bg-panel p-4 transition-colors duration-300">
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

      <RunWaterfall detail={detail} names={stepNames} step={step} onStep={setStep} />

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="min-w-0 lg:col-span-2 card rounded-lg border border-edge bg-panel">
          <div className="flex items-center border-b border-edge px-4 py-3">
            <span className="font-medium">Event Timeline</span>
            <button
              onClick={() => void exportEvents(detail.summary.workflowId)}
              className="ml-auto rounded-full border border-edge px-2.5 py-0.5 text-[11.5px] text-slate-400 hover:border-slate-500/60 hover:text-slate-200"
              title="Download this run's event log as JSON — the record replay and recovery are built from"
            >
              Export log
            </button>
          </div>
          <ol className="relative space-y-0">
            {detail.events.map((e, i) => (
              <EventRow
                key={e.sequenceNumber}
                event={e}
                previous={i > 0 ? detail.events[i - 1] : null}
                stepNames={stepNames}
                chosen={step != null && (unwrap(e.payload) as any)?.commandSeq === step}
              />
            ))}
          </ol>
        </div>

        <div className="space-y-6">
          <Panel title="Activities">
            {detail.activities.map((a) => (
              <div key={a.sequenceNumber} className="-mx-2 flex items-center gap-2 rounded px-2 py-1 text-sm"
                   style={step === a.sequenceNumber ? { background: "var(--accent-wash)", boxShadow: "inset 2px 0 0 var(--accent)" } : undefined}>
                <StatusBadge status={a.status} />
                <span>{stepNames[a.sequenceNumber] ?? a.activityType}</span>
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
            <Panel title={isCancelled(detail.error) ? "Cancelled" : "Error"}>
              <pre className="whitespace-pre-wrap text-xs text-rose-300">{detail.error}</pre>
            </Panel>
          )}
        </div>
      </div>
    </div>
  );
}

/**
 * The run as a waterfall: one row per step, on one clock.
 *
 * <p>The event list says what happened in order; this says how the run spent
 * its time — which step waited for a worker, which ran long, which failed and
 * backed off before trying again. A retried step used to be one long bar from
 * its first start to its last end, so a two-second backoff read as two seconds
 * of work. Each attempt is now its own bar, the backoff a dashed line between
 * them. Built from the execution timeline the API reads from the same events.
 */
function RunWaterfall({ detail, names, step, onStep }: {
  detail: WorkflowDetail;
  names: Record<number, string>;
  step: number | null;
  onStep: (seq: number | null) => void;
}) {
  const execution = (detail as { execution?: Execution | null }).execution ?? null;
  const model = useMemo(() => (execution ? buildExecution({ execution }) : null), [execution]);
  const findings = useMemo(() => (model && execution ? findingsOf(model, { execution }) : []), [model, execution]);
  if (!model || model.work.length === 0) return null;
  const seqOf = (id: string) => Number(id.replace(/^step-/, ""));
  const rows: WaterfallRow[] = [...model.work]
    .sort((a, b) => a.scheduled - b.scheduled)
    .map((w) => {
      const seq = seqOf(w.id);
      const label = names[seq] ?? w.title;
      const ran = w.end != null && w.attempts[0] ? w.end - w.attempts[0].start : null;
      return {
        id: w.id,
        level: 0,
        label,
        title: label,
        scheduled: w.scheduled,
        attempts: w.attempts,
        critical: w.critical,
        running: w.status === "running",
        retrying: w.status === "retrying",
        summary: `${label}, ${w.attempts.length} attempt${w.attempts.length === 1 ? "" : "s"}, ${w.status}`,
        value: (
          <span className="readout text-[11px]" style={{ color: w.status === "failed" ? "var(--state-critical-ink)" : "var(--text-3)" }}>
            {w.status === "failed" ? "failed" : w.status === "running" ? "running" : w.status === "retrying" ? "retrying" : ran != null ? duration(ran) : "waiting"}
            {w.attempts.length > 1 && <span className="ml-1.5 text-slate-500">×{w.attempts.length}</span>}
          </span>
        ),
      };
    });
  const chosen = step != null ? model.work.find((w) => seqOf(w.id) === step) ?? null : null;
  const issues = findings.filter((f) => f.id !== "outcome" && f.id !== "live");
  return (
    <div className="card rounded-lg border border-edge bg-panel p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <div className="font-medium">Run timeline</div>
        <WaterfallLegend />
      </div>
      <TimeBudget model={model} />
      <div className="mt-3 overflow-hidden rounded-md border border-edge/60 pt-2">
        <Waterfall
          rows={rows}
          span={model.span}
          live={model.live}
          madeAt={model.madeAt}
          selected={new Set(chosen ? [chosen.id] : [])}
          onSelect={(id) => onStep(id == null ? null : seqOf(id))}
          ariaLabel={`How the run executed: ${model.work.length} steps over ${duration(model.span)}`}
        />
      </div>
      {chosen ? (
        <div className="settle mt-3 grid gap-4 border-t border-edge/60 pt-3 md:grid-cols-[minmax(0,1fr)_minmax(0,1fr)]">
          <div>
            <div className="flex items-baseline justify-between">
              <span className="text-[13px] font-semibold text-slate-200">{names[step!] ?? chosen.title}</span>
              <button type="button" onClick={() => onStep(null)} className="text-[11px] text-slate-500 hover:text-slate-300">Close</button>
            </div>
            <p className="mt-1 text-[11.5px] text-slate-500">Its events are marked in the event timeline below.</p>
          </div>
          <ExecutionFacts work={chosen} model={model} />
        </div>
      ) : issues.length > 0 ? (
        <div className="mt-3 border-t border-edge/60 pt-1">
          <Findings findings={issues} onSelect={(target) => onStep(seqOf(target))}
                    hint="Choose a finding to show its step here and its events below." />
        </div>
      ) : null}
    </div>
  );
}

function Panel({ title, children }: { title: string; children: import("react").ReactNode }) {
  return (
    <div className="card rounded-lg border border-edge bg-panel">
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
  chosen = false,
}: {
  event: any;
  previous: any | null;
  stepNames: Record<number, string>;
  /** The step chosen in the timeline: its events stand out from the rest. */
  chosen?: boolean;
}) {
  const [open, setOpen] = useState(false);
  const payload = useMemo(() => unwrap(event.payload), [event.payload]);
  const gap = previous ? elapsed(previous.createdAt, event.createdAt) : null;
  const line = headline(payload, stepNames);
  const expandable = payload != null && typeof payload === "object";

  return (
    <li className="group border-b border-edge/50 px-4 py-2"
        style={chosen ? { background: "var(--accent-wash)", boxShadow: "inset 2px 0 0 var(--accent)" } : undefined}>
      <div className="flex items-baseline gap-3">
        <span className="w-8 shrink-0 text-right font-mono text-xs text-slate-500">
          {event.sequenceNumber}
        </span>
        <span className="shrink-0">{EVENT_ICON[event.eventType] ?? "•"}</span>
        <div className="min-w-0 flex-1">
          <div className="flex flex-wrap items-baseline gap-x-2">
            <span className="text-sm font-medium">{humanEvent(event.eventType, payload)}</span>
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
          {/* Why a step failed belongs on the row that says it failed, not
              behind "+ payload". */}
          {event.eventType === "ACTIVITY_FAILED" && (payload as any)?.error && (
            <div className="mt-0.5 text-xs text-rose-300">
              {String((payload as any).error)}
              {(payload as any).terminal === false && <span className="text-slate-500"> — will retry</span>}
            </div>
          )}
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

/** The definition a declarative run (or its rollback) was started from. */
function definitionOf(d: WorkflowDetail): { name: string; version: number } | null {
  const input: any = d.input;
  if (input && typeof input === "object" && typeof input.definition === "string") {
    return { name: input.definition, version: Number(input.version ?? 1) };
  }
  return null;
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

/** Cancellation is recorded as a failure whose reason starts "Cancelled:". */
const isCancelled = (error: unknown) => typeof error === "string" && error.startsWith("Cancelled:");

function humanEvent(t: string, payload?: any): string {
  if (t === "WORKFLOW_FAILED" && isCancelled(payload?.error)) return "Run cancelled";
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
    const step = authored.includes(":") ? authored.slice(authored.lastIndexOf(":") + 1) : authored;
    // A compensation is signed <workflowId>:compensate:<step>; naming it by the
    // step alone made a rollback's timeline read as if it re-ran the step.
    return authored.includes(":compensate:") ? `undo ${step}` : step;
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

/** Downloads the run's event log as JSON: the record replay and crash recovery are built from. */
async function exportEvents(workflowId: string) {
  const events = await api.get<unknown[]>(`/api/workflows/${encodeURIComponent(workflowId)}/events`);
  const url = URL.createObjectURL(new Blob([JSON.stringify(events, null, 2)], { type: "application/json" }));
  const a = document.createElement("a");
  a.href = url;
  a.download = `${workflowId}-events.json`;
  a.click();
  setTimeout(() => URL.revokeObjectURL(url), 1000);
}
