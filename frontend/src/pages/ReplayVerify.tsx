import { useEffect, useMemo, useState } from "react";
import { api } from "../api";
import { Readout, StateDot } from "../system/primitives";
import { STATE, type StateKey } from "../system/tokens";
import { timeOf } from "../system/time";

/**
 * Replay verification.
 *
 * Two questions, answered separately because they are answered differently. For
 * steps whose inputs and results are recorded, replaying the decisions proves
 * the run would make the same calls — no I/O, so it is safe on production
 * history. For model calls, the output is regenerated against today's provider
 * and scored for equivalence.
 *
 * A verification that could check nothing says so. It never reports a score.
 */

const VERDICT: Record<string, { label: string; state: StateKey; blurb: string }> = {
  VERIFIED: {
    label: "Verified",
    state: "healthy",
    blurb: "Replaying this history reproduces the same decisions.",
  },
  DIVERGED: {
    label: "Diverged",
    state: "critical",
    blurb: "Replay would not reproduce this run. The differences are below.",
  },
  NOTHING_TO_VERIFY: {
    label: "Nothing to verify",
    state: "idle",
    blurb:
      "This run has no steps that can be checked — no recorded step calls and no model outputs. No score is reported, because nothing was examined.",
  },
};

export default function ReplayVerify() {
  const [workflowId, setWorkflowId] = useState("");
  const [runs, setRuns] = useState<any[]>([]);
  const [report, setReport] = useState<any | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  // A bare id field is unusable if you do not already know the id.
  useEffect(() => {
    api.workflows().then((w) => setRuns(w.slice(0, 40))).catch(() => {});
  }, []);

  const run = async (id: string) => {
    if (!id) return;
    setBusy(true);
    setError(null);
    setReport(null);
    try {
      setReport(await api.post(`/api/replay/verify/${id}`));
    } catch (e: any) {
      setError(String(e.message ?? e));
    } finally {
      setBusy(false);
    }
  };

  const verdict = report ? VERDICT[report.verdict] ?? VERDICT.NOTHING_TO_VERIFY : null;
  const det = report?.deterministic;

  const divergences = useMemo(
    () => (det?.checks ?? []).filter((c: any) => !c.matched),
    [det]
  );

  return (
    <div className="space-y-8">
      <header>
        <h1 className="text-lg font-semibold tracking-tight">Replay Audit</h1>
        <p className="mt-0.5 max-w-2xl text-sm text-slate-500">
          Replays a run's decisions against its own recorded history to confirm it would execute
          identically — the property crash recovery depends on. Model outputs are additionally
          re-scored against today's provider. Nothing is re-sent to your services.
        </p>
      </header>

      <div className="flex flex-wrap gap-2">
        <input
          value={workflowId}
          onChange={(e) => setWorkflowId(e.target.value)}
          placeholder="workflow id"
          className="min-w-0 flex-1 rounded border border-edge bg-ink px-3 py-2 font-mono text-xs text-slate-200 outline-none focus:border-aurora/60"
        />
        <button
          onClick={() => run(workflowId)}
          disabled={busy || !workflowId}
          className="rounded bg-[color:var(--accent-strong)] px-4 py-2 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
        >
          {busy ? "Verifying…" : "Verify replay"}
        </button>
      </div>

      {runs.length > 0 && !report && (
        <div>
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Recent runs</h2>
          <div className="mt-1 divide-y divide-edge/40">
            {runs.map((r: any) => (
              <button
                key={r.workflowId}
                onClick={() => {
                  setWorkflowId(r.workflowId);
                  run(r.workflowId);
                }}
                className="flex w-full items-center gap-x-4 px-1 py-2 text-left text-[11px] transition-colors hover:bg-edge/40"
              >
                <StateDot
                  state={r.status === "FAILED" ? "critical" : r.status === "RUNNING" ? "active" : "healthy"}
                  size={6}
                />
                <span className="readout w-16 shrink-0 text-slate-600">{timeOf(r.createdAt)}</span>
                <span className="w-40 shrink-0 truncate text-slate-300">{r.workflowType}</span>
                <span className="w-20 shrink-0 text-slate-500">{r.status}</span>
                <span className="ml-auto truncate font-mono text-[10px] text-slate-600">{r.workflowId}</span>
              </button>
            ))}
          </div>
        </div>
      )}

      {error && (
        <div
          className="rounded border px-3 py-2 text-[11px]"
          style={{ borderColor: `${STATE.critical.color}55`, color: STATE.critical.color }}
        >
          {error}
        </div>
      )}

      {report && verdict && (
        <>
          <div className="flex flex-wrap items-start justify-between gap-4">
            <div className="flex items-start gap-3">
              <StateDot state={verdict.state} />
              <div>
                <div className="text-sm font-semibold" style={{ color: STATE[verdict.state].ink }}>
                  {verdict.label}
                </div>
                <p className="mt-0.5 max-w-xl text-xs text-slate-500">{verdict.blurb}</p>
              </div>
            </div>
            <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
              <Readout label="Checks" value={report.verifiedActivities ?? 0} size="sm"
                state={report.verifiedActivities ? "active" : "idle"} />
              <Readout label="Matched" value={report.passed ?? 0} size="sm"
                state={report.passed ? "healthy" : "idle"} />
              <Readout label="Diverged" value={report.failed ?? 0} size="sm"
                state={report.failed ? "critical" : "idle"} />
            </div>
          </div>

          {/* Scores exist only for model calls. Absent is shown as absent. */}
          {report.replayConfidence != null && (
            <div className="flex flex-wrap gap-x-10 gap-y-3">
              <Readout label="Replay confidence" value={pct(report.replayConfidence)} size="sm" state="healthy" />
              <Readout label="Semantic drift" value={pct(report.semanticDrift)} size="sm"
                state={(report.semanticDrift ?? 0) > 0.2 ? "warning" : "healthy"} />
              <Readout
                label="Decision consistency"
                value={report.decisionConsistency == null ? "not measured" : pct(report.decisionConsistency)}
                size="sm"
                state={report.decisionConsistency == null ? "idle" : "healthy"}
              />
            </div>
          )}

          {det?.applicable && det.checks?.length > 0 && (
            <section>
              <div className="flex items-baseline justify-between">
                <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Decision replay · {det.checked} steps</h2>
                <span className="text-[10px] text-slate-600">
                  {divergences.length === 0 ? "every step resolves identically" : `${divergences.length} differ`}
                </span>
              </div>
              <div className="mt-1 divide-y divide-edge/40">
                {det.checks.map((c: any, i: number) => (
                  <div key={`${c.stepId}-${i}`} className="py-2 text-[11px]">
                    <div className="flex flex-wrap items-baseline gap-x-3">
                      <StateDot state={c.matched ? "healthy" : "critical"} size={6} />
                      <span className="font-mono text-slate-300">{c.stepId}</span>
                      <span className="rounded border border-edge px-1.5 py-0.5 text-[10px] text-slate-500">
                        {c.kind}
                      </span>
                      <span className="text-slate-500">{c.detail}</span>
                    </div>
                    {!c.matched && (
                      <div className="mt-1.5 grid gap-2 pl-5 md:grid-cols-2">
                        <Diff label="replay would send" value={c.expected} tone={STATE.active.color} />
                        <Diff label="history recorded" value={c.recorded} tone={STATE.critical.color} />
                      </div>
                    )}
                  </div>
                ))}
              </div>
            </section>
          )}

          {report.items?.length > 0 && (
            <section>
              <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Model outputs · {report.items.length} re-scored</h2>
              <div className="mt-1 divide-y divide-edge/40">
                {report.items.map((it: any) => (
                  <div key={it.commandSeq} className="py-2.5 text-[11px]">
                    <div className="flex flex-wrap items-baseline gap-x-3">
                      <StateDot state={it.passed ? "healthy" : "critical"} size={6} />
                      <span className="readout text-slate-500">seq {it.commandSeq}</span>
                      <span className="readout text-slate-500">overall {it.overallScore?.toFixed(3)}</span>
                      <span className="text-slate-600">{it.explanation}</span>
                    </div>
                    <div className="mt-2 grid gap-2 pl-5 md:grid-cols-2">
                      <Diff label="recorded" value={it.historicalOutput} tone={STATE.idle.color} />
                      <Diff label="today" value={it.freshOutput} tone={STATE.active.color} />
                    </div>
                  </div>
                ))}
              </div>
            </section>
          )}

          {report.verdict === "NOTHING_TO_VERIFY" && (
            <div className="text-center text-xs text-slate-500">
              Publish a workflow definition and run it — its steps are recorded, which is what makes
              them verifiable.
            </div>
          )}
        </>
      )}
    </div>
  );
}

function Diff({ label, value, tone }: { label: string; value?: string; tone: string }) {
  return (
    <div>
      <div className="text-[10px] uppercase tracking-widest" style={{ color: tone }}>
        {label}
      </div>
      <pre className="mt-0.5 max-h-40 overflow-auto rounded border border-edge bg-ink p-2 font-mono text-[10px] leading-relaxed text-slate-400">
        {value ?? "—"}
      </pre>
    </div>
  );
}

/** Null means "not measured", which must never render as 0%. */
function pct(v: number | null | undefined) {
  return v == null ? "—" : `${(v * 100).toFixed(0)}%`;
}
