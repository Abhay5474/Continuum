import { useCallback, useEffect, useState } from "react";
import { visibleInterval } from "../system/poll";
import { portal } from "../api";
import { Empty, Explain, Pill, StepChain } from "../system/hub";
import { Link } from "react-router-dom";
import { InfoTip, PageHeader, Plane, Readout, Switch } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";

/**
 * Saga compensation.
 *
 * <p>The number this page is built around is <b>stranded steps</b>, not rolled
 * back ones. A rollback that undid four things and could not undo a fifth is the
 * interesting case, and a console that only reported the four would be actively
 * misleading: the operator needs to know what is still out there, and this is
 * the only place they will find it.
 */

type Rollback = {
  workflowId: string | null;
  definition: string | null;
  failedStep: string | null;
  compensated: string[];
  uncompensated: string[];
  complete: boolean;
  summary: string;
  at: string;
};

type Status = {
  enabled: boolean;
  rollbacks: number;
  stepsCompensated: number;
  stepsStranded: number;
  partialRollbacks: number;
  recent: Rollback[];
};

const EXAMPLE = `{
  "steps": [
    {
      "id": "reserve",
      "call":       { "method": "POST",   "url": "https://inventory/reserve" },
      "compensate": { "method": "DELETE", "url": "https://inventory/reserve" }
    },
    {
      "id": "charge",
      "dependsOn": ["reserve"],
      "call":       { "method": "POST", "url": "https://payments/charge" },
      "compensate": { "method": "POST", "url": "https://payments/refund" }
    },
    {
      "id": "ship",
      "dependsOn": ["charge"],
      "call": { "method": "POST", "url": "https://fulfilment/ship" }
    }
  ]
}`;

export default function Saga() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setStatus(await portal.saga.status());
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load compensation.");
    }
  }, []);

  useEffect(() => {
    void load();
    return visibleInterval(() => void load(), 3000);
  }, [load]);

  const act = async (fn: () => Promise<unknown>, ok?: string) => {
    setBusy(true);
    try {
      await fn();
      if (ok) toast(ok);
      await load();
    } catch (e: any) {
      toast(e?.message ?? "That did not work.", "error");
    } finally {
      setBusy(false);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  const recent = status?.recent ?? [];
  const stranded = status?.stepsStranded ?? 0;

  return (
    <section className="space-y-8">
      <PageHeader
        glyph="flow"
        tone="accent"
        title="Saga Compensation"
        subtitle="Undo completed steps when a run fails"
      />

      <div className="plane grid grid-cols-2 gap-x-8 gap-y-5 p-4 sm:grid-cols-3 lg:grid-cols-4">
        <Readout label="Rollbacks" value={status?.rollbacks ?? 0} size="sm" />
        <Readout
          label="Steps undone"
          value={status?.stepsCompensated ?? 0}
          size="sm"
          state={(status?.stepsCompensated ?? 0) > 0 ? "healthy" : "idle"}
        />
        <Readout
          label="Steps stranded"
          value={stranded}
          size="sm"
          state={stranded > 0 ? "critical" : "idle"}
          hint="Completed, could not be undone, and their effects are still out there."
        />
        <Readout
          label="Partial rollbacks"
          value={status?.partialRollbacks ?? 0}
          size="sm"
          state={(status?.partialRollbacks ?? 0) > 0 ? "degraded" : "idle"}
        />
      </div>

      <Switch
        checked={!!status?.enabled}
        busy={busy}
        onChange={(next) =>
          act(
            () => portal.saga.configure({ enabled: next }),
            next ? "Compensation is on for new runs." : "Compensation is off."
          )
        }
        label="Compensate on failure"
        hint="Off by default: a rollback makes real calls to real systems. Pinned when a run starts, so it affects new runs only."
      />

      {/* How a rollback runs, drawn rather than described: the forward path,
          the failure, and the undo running back along it. */}
      <div className="plane space-y-3 p-4">
        <div className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight text-slate-200">
          How a rollback runs
          <InfoTip text="Add compensate beside a step's call. A charge cannot be rolled back, only refunded, so the undo is a call you write. Undo runs newest first: the refund before the reservation is released." />
        </div>
        <StepChain
          label="forward"
          steps={[
            { label: "reserve", state: "done" },
            { label: "charge", state: "done" },
            { label: "ship", state: "failed", note: "fails" },
          ]}
        />
        <StepChain
          label="undo"
          arrow="←"
          steps={[
            { label: "release", state: "undone", note: "DELETE /inventory/reserve — second" },
            { label: "refund", state: "undone", note: "POST /payments/refund — first" },
          ]}
        />
        <Explain title="Example definition">
          <pre className="overflow-x-auto rounded-md border border-edge bg-ink/60 p-3 font-mono text-[11px] leading-relaxed text-slate-400">
            {EXAMPLE}
          </pre>
        </Explain>
      </div>

      {status === null ? (
        <SkeletonRows rows={2} />
      ) : recent.length === 0 ? (
        <Empty title={"No rollbacks yet"} hint={"A workflow that fails partway shows what was undone here."} />
      ) : (
        <div className="space-y-2">
          <div className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight text-slate-200">
            Rollbacks
            <InfoTip text="↺ undone. ! stranded — completed with no compensation, so its effect remains and nothing else will clean it up." />
          </div>
          {recent.map((r, i) => (
            <Plane key={i} className="space-y-2.5 p-3.5">
              <div className="flex flex-wrap items-center gap-2">
                <span className="text-sm font-medium text-slate-200">{r.definition ?? "workflow"}</span>
                <Pill tone={r.complete ? "ok" : "bad"} dot>{r.complete ? "fully rolled back" : "partial"}</Pill>
                <span className="micro">at {r.failedStep ?? "unknown"}</span>
                <span className="flex-1" />
                {r.workflowId && (
                  <Link to={`/workflows/${r.workflowId}`} className="font-mono text-[11px] text-slate-500 hover:text-[color:var(--accent-ink)]">
                    {String(r.workflowId).slice(0, 8)}
                  </Link>
                )}
                <span className="micro">{new Date(r.at).toLocaleTimeString()}</span>
              </div>
              <StepChain
                arrow="·"
                steps={[
                  ...r.compensated.map((s: string) => ({ label: s, state: "undone" as const })),
                  ...r.uncompensated.map((s: string) => {
                    const m = /^(\S+)\s*\((.*)\)$/.exec(s);
                    return { label: m ? m[1] : s, state: "stranded" as const, note: m ? m[2] : "no compensation — its effect remains" };
                  }),
                ]}
              />
            </Plane>
          ))}
        </div>
      )}

      {recent.length > 0 && (
        <button
          disabled={busy}
          onClick={() => act(() => portal.saga.clear(), "Rollback history cleared.")}
          className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:border-aurora/50 disabled:opacity-40"
        >
          Clear history
        </button>
      )}
    </section>
  );
}
