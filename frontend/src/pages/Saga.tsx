import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { PageHeader, Plane, Readout, Switch } from "../system/primitives";
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
    const t = setInterval(() => void load(), 3000);
    return () => clearInterval(t);
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
        subtitle="Durable execution guarantees each step runs once. It does not guarantee the set of them is all-or-nothing."
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

      <div className="space-y-3">
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
          hint="Off by default. Rollback issues real calls to real systems, so nobody should discover it by being opted in."
        />
        <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
          Read once when a run starts and pinned to it, so this affects new runs only.
        </p>
      </div>

      <div className="space-y-3">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">How to declare a compensation</h2>
        <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
          Add <span className="readout">compensate</span> beside a step&rsquo;s{" "}
          <span className="readout">call</span>. You cannot roll back a charge at a payment
          provider — you can only issue a refund, which is why the undo is a call you write rather
          than something the engine can infer.
        </p>
        <pre className="overflow-x-auto rounded-md border border-edge bg-ink/60 p-3 font-mono text-[11px] leading-relaxed text-slate-400">
          {EXAMPLE}
        </pre>
        <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
          If <span className="readout">ship</span> fails, the refund runs first and the reservation
          is released second — reverse order of completion. That is not a detail: releasing the
          stock before the money is returned leaves a window where someone else can buy it.
        </p>
      </div>

      {status === null ? (
        <SkeletonRows rows={2} />
      ) : recent.length === 0 ? (
        <div className="rounded-xl border border-dashed px-3 py-10 text-center text-sm text-slate-500" style={{ borderColor: "rgb(var(--card-edge))" }}>
          No rollbacks yet. When a workflow with compensations fails partway, what was undone — and
          what could not be — appears here.
        </div>
      ) : (
        <div className="space-y-2">
          {recent.map((r, i) => (
            <Plane key={i} className="space-y-2 p-3">
              <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                <span className="text-sm font-medium text-slate-200">
                  {r.definition ?? "workflow"}
                </span>
                <span className="micro">failed at {r.failedStep ?? "unknown"}</span>
                <span
                  className={`micro ${r.complete ? "text-emerald-400" : "text-rose-400"}`}
                >
                  {r.complete ? "fully rolled back" : "partial"}
                </span>
                <span className="flex-1" />
                <span className="micro">{new Date(r.at).toLocaleTimeString()}</span>
              </div>

              <p className="text-xs text-slate-400 max-w-2xl leading-relaxed">{r.summary}</p>

              {r.compensated.length > 0 && (
                <div className="flex flex-wrap items-center gap-x-2 gap-y-1">
                  <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">undone</h2>
                  {r.compensated.map((s, j) => (
                    <span key={j} className="readout text-[11px] text-emerald-400">
                      {j > 0 && <span className="text-slate-600">→ </span>}
                      {s}
                    </span>
                  ))}
                </div>
              )}

              {r.uncompensated.length > 0 && (
                <div className="rounded-md border border-rose-500/40 bg-rose-500/5 p-2">
                  <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">still out there</h2>
                  <div className="mt-1 flex flex-wrap gap-x-3 gap-y-1">
                    {r.uncompensated.map((s, j) => (
                      <span key={j} className="readout text-[11px] text-rose-400">
                        {s}
                      </span>
                    ))}
                  </div>
                  <p className="mt-1 text-[11px] text-slate-500">
                    These steps completed and have no compensation, so their effects remain. Nothing
                    else will clean them up.
                  </p>
                </div>
              )}

              {r.workflowId && (
                <p className="break-all font-mono text-[11px] text-slate-600">{r.workflowId}</p>
              )}
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
