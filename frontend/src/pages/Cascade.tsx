import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Meter, Micro, PageHeader, Plane, Readout, Switch } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";

/**
 * Verify-then-Escalate.
 *
 * <p>The page has to answer one question honestly: is the cascade saving money
 * without quietly shipping worse answers? So the savings figure never appears
 * alone. Beside it sit the two numbers that would expose it as a lie — how many
 * escalations were wasted, and how many the judge missed on the audited slice.
 *
 * <p>The escalation ladder is the visual centre. Every request enters at the
 * cheap tier and either leaves there or climbs, and the ladder shows the split
 * as it actually happened rather than as a percentage in a table.
 */

type Tier = { provider: string; model: string; costPer1k: number; label: string | null };
type Status = {
  enabled: boolean;
  threshold: number;
  auditRate: number;
  escalationCap: number;
  available: boolean;
  cheapTier: Tier | null;
  strongTier: Tier | null;
  requests: number;
  escalated: number;
  escalationRate: number;
  overCap: boolean;
  audited: number;
  spend: number;
  strongOnlySpend: number;
  saved: number;
  savedPct: number;
  wastedEscalations: number;
  auditSamples: number;
  missedEscalations: number;
  missRate: number | null;
  calibration: { observations: number; calibrated: boolean; curve: any[] };
  speculation: {
    escalationRate: number;
    breakEven: number;
    extraSpendUsd: number;
    extraSpendPct: number | null;
    latencySavedMs: number;
    worthwhile: boolean;
    summary: string;
  };
};

/**
 * Named thresholds. 0.75 means nothing on its own; what it costs you does.
 */
const BANDS: [number, string, string][] = [
  [0.55, "Frugal", "Accepts more cheap answers. Lowest bill, most risk of a weak answer slipping through."],
  [0.75, "Balanced", "Escalates on any clear defect. The recommended setting."],
  [0.9, "Careful", "Escalates on the slightest doubt. Closest to always using the strong model."],
];

export default function Cascade() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [rows, setRows] = useState<any[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [s, d] = await Promise.all([portal.cascade.status(), portal.cascade.decisions(40)]);
      setStatus(s);
      setRows(d);
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load the cascade.");
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 5000);
    return () => clearInterval(t);
  }, [load]);

  const run = async (fn: () => Promise<any>, message: string) => {
    setBusy(true);
    try {
      setStatus(await fn());
      await load();
      toast(message);
    } catch (e: any) {
      toast(e?.message ?? "That did not work", "error");
    } finally {
      setBusy(false);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  const accepted = (status?.requests ?? 0) - (status?.escalated ?? 0);

  return (
    <div className="space-y-6">
      <PageHeader
        title="Model Cascade"
        subtitle="Answer with the cheap model, check the answer, and pay for the expensive one only when the check fails."
      />

      {status && !status.available && (
        <div className="rounded-lg border border-amber-500/30 bg-amber-500/[0.06] px-4 py-2.5 text-sm text-slate-400">
          <span className="text-amber-300">Not available.</span> A cascade needs two active models at
          different prices. This deployment's registry offers no meaningful price gap, so the cascade
          declines rather than adding a second call for nothing.
        </div>
      )}

      <Plane className="p-5">
        <Switch
          label="Verify-then-escalate"
          hint="Every request starts on the cheap tier. The answer is judged against the request, and only a failed judgement pays for the strong tier."
          checked={status?.enabled ?? false}
          busy={busy || status === null}
          locked={status && !status.available ? "No second tier available on this deployment." : undefined}
          onChange={(next) =>
            run(() => portal.cascade.setEnabled(next), next ? "Cascade enabled" : "Cascade disabled")
          }
        />
      </Plane>

      {/* ---- the ladder: where requests actually left ---- */}
      <section className="space-y-3">
        <Micro>Escalation ladder · live</Micro>
        <Ladder status={status} accepted={accepted} />
      </section>

      {/* ---- savings, next to the numbers that could disprove them ---- */}
      <section className="space-y-3">
        <Micro>Did it work</Micro>
        <Plane className="grid gap-6 p-5 sm:grid-cols-2 lg:grid-cols-4">
          <Readout
            label="Saved"
            value={`$${(status?.saved ?? 0).toFixed(4)}`}
            state={(status?.saved ?? 0) > 0 ? "healthy" : "idle"}
            hint={`vs $${(status?.strongOnlySpend ?? 0).toFixed(4)} on the strong model alone`}
          />
          <Readout
            label="Reduction"
            value={Math.round((status?.savedPct ?? 0) * 100)}
            unit="%"
            state={(status?.savedPct ?? 0) > 0 ? "healthy" : "idle"}
          />
          <Readout
            label="Wasted escalations"
            value={status?.wastedEscalations ?? 0}
            state={(status?.wastedEscalations ?? 0) > 0 ? "warning" : "idle"}
            hint="Escalated, and the strong model agreed anyway"
          />
          <Readout
            label="Missed escalations"
            value={status?.missedEscalations ?? 0}
            state={(status?.missedEscalations ?? 0) > 0 ? "critical" : "healthy"}
            hint={
              (status?.auditSamples ?? 0) === 0
                ? "No audit samples yet"
                : `of ${status?.auditSamples} audited`
            }
          />
        </Plane>

        {status && status.requests > 0 && (
          <p className="text-xs text-slate-500">
            {status.missedEscalations > 0 ? (
              <span className="text-rose-400">
                On the audited slice the judge accepted {status.missedEscalations} answer
                {status.missedEscalations === 1 ? "" : "s"} the strong model disagreed with. Raise the
                threshold.
              </span>
            ) : status.auditSamples > 0 ? (
              <span className="text-emerald-400">
                Across {status.auditSamples} audited request{status.auditSamples === 1 ? "" : "s"}, the
                strong model agreed with every answer the judge accepted.
              </span>
            ) : (
              "Turn up the audit rate to measure the escalations the judge is missing — savings alone cannot tell you."
            )}
          </p>
        )}

        {status?.overCap && (
          <p className="text-xs text-amber-400">
            Escalation rate is {(status.escalationRate * 100).toFixed(0)}%, above the{" "}
            {(status.escalationCap * 100).toFixed(0)}% cap. At this rate the cascade costs more than
            using the strong model directly.
          </p>
        )}
      </section>

      {/* ---- tiers ---- */}
      <section className="space-y-3">
        <Micro>Tiers · derived from the active model registry by price</Micro>
        <div className="grid gap-3 sm:grid-cols-2">
          <TierPlane tier={status?.cheapTier ?? null} role="Cheap tier" note="Every request starts here" />
          <TierPlane tier={status?.strongTier ?? null} role="Strong tier" note="Only on a failed judgement" />
        </div>
      </section>

      {/* ---- threshold ---- */}
      <section className="space-y-3">
        <Micro>Escalation threshold</Micro>
        <Plane className="p-5">
          <p className="text-sm text-slate-400">
            How confident the judge must be to let a cheap answer through. The score is calibrated
            against your own traffic, so this means the same thing as your workload changes.
          </p>
          <div className="mt-4 grid gap-3 sm:grid-cols-3">
            {BANDS.map(([value, name, note]) => {
              const active = Math.abs((status?.threshold ?? 0.75) - value) < 0.005;
              return (
                <button
                  key={name}
                  disabled={busy}
                  onClick={() => run(() => portal.cascade.configure({ threshold: value }), `Threshold: ${name}`)}
                  className={`rounded-lg border p-3 text-left transition-colors disabled:opacity-50 ${
                    active ? "border-aurora/60 bg-aurora/10" : "border-edge hover:border-aurora/40"
                  }`}
                >
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="text-sm font-medium text-slate-200">{name}</span>
                    <span className="readout text-xs text-slate-500">{value.toFixed(2)}</span>
                  </div>
                  <p className="mt-1 text-xs text-slate-500">{note}</p>
                </button>
              );
            })}
          </div>

          <div className="mt-5 flex flex-wrap items-end gap-5 border-t border-edge/60 pt-4">
            <label className="min-w-0">
              <span className="micro">Audit rate</span>
              <select
                value={status?.auditRate ?? 0.05}
                disabled={busy}
                onChange={(e) =>
                  run(() => portal.cascade.configure({ auditRate: Number(e.target.value) }), "Audit rate updated")
                }
                className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
              >
                <option value={0}>Off — no miss measurement</option>
                <option value={0.02}>2% of requests</option>
                <option value={0.05}>5% of requests</option>
                <option value={0.1}>10% of requests</option>
              </select>
              <p className="mt-1 max-w-xs text-xs text-slate-600">
                Runs both tiers on a sample to find the escalations the judge missed. Costs a second
                call on those requests.
              </p>
            </label>
            <div className="ml-auto flex items-center gap-3">
              <span className="text-xs text-slate-500">
                calibration: {status?.calibration?.observations ?? 0} labelled
              </span>
              <button
                disabled={busy || !status?.requests}
                onClick={() => run(() => portal.cascade.reset(), "Cascade history cleared")}
                className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:bg-edge/50 disabled:opacity-40"
              >
                Reset history
              </button>
            </div>
          </div>
        </Plane>
      </section>

      {/* ---- should this cascade run speculatively? ---- */}
      {status?.speculation && (
        <Plane className="space-y-3 p-4">
          <Micro>Should the strong model run in parallel instead of afterwards?</Micro>
          <p className="text-xs text-slate-600">
            Speculative execution fires both tiers at once and returns whichever the judge accepts,
            turning the cascade&rsquo;s latency penalty into a cost penalty. Whether that is a good
            trade depends entirely on how often this account actually escalates — so the answer is
            computed from the rate measured above, not from intuition.
          </p>

          <Meter
            value={Math.min(1, status.speculation.escalationRate)}
            state={status.speculation.worthwhile ? "healthy" : "degraded"}
            label={`Escalation rate against the ${Math.round(
              status.speculation.breakEven * 100
            )}% break-even`}
            height={8}
          />

          <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
            <Readout
              label="Extra spend"
              value={`$${status.speculation.extraSpendUsd.toFixed(5)}`}
              size="sm"
              state="degraded"
              hint="Paying for the strong model on requests that never needed it."
            />
            <Readout
              label="Latency saved"
              value={Math.round(status.speculation.latencySavedMs)}
              unit="ms"
              size="sm"
              state="healthy"
              hint="On escalating requests, the strong call was already running."
            />
            <Readout
              label="Verdict"
              value={status.speculation.worthwhile ? "worth it" : "not yet"}
              size="sm"
              state={status.speculation.worthwhile ? "healthy" : "idle"}
            />
          </div>

          <p className="text-xs text-slate-500">{status.speculation.summary}</p>
          <p className="text-xs text-slate-600">
            Whether a millisecond is worth a cent is a product decision, not an arithmetic one. Both
            numbers are shown rather than collapsed into a single score, because there is no
            universal exchange rate between latency and money.
          </p>
        </Plane>
      )}

      {/* ---- calibration curve ---- */}
      {(status?.calibration?.observations ?? 0) > 0 && (
        <section className="space-y-3">
          <Micro>Calibration · raw judge score → measured probability the cheap answer sufficed</Micro>
          <CalibrationCurve
            curve={status!.calibration.curve}
            threshold={status!.threshold}
            calibrated={status!.calibration.calibrated}
          />
        </section>
      )}

      {/* ---- decision tape ---- */}
      <section className="space-y-3">
        <Micro>Decisions · newest first</Micro>
        {rows === null ? (
          <SkeletonRows rows={4} />
        ) : rows.length === 0 ? (
          <Plane className="p-8 text-center text-sm text-slate-500">
            No decisions yet. Send a request through the gateway with the cascade on and each one
            appears here with the judge's reasoning.
          </Plane>
        ) : (
          <Plane className="overflow-x-auto">
            <table className="w-full min-w-[680px] text-xs">
              <thead>
                <tr className="border-b border-edge/60 text-left">
                  {["Confidence", "Outcome", "Why", "Model", "Cost", "Latency"].map((h) => (
                    <th key={h} className="px-3 py-2">
                      <span className="micro">{h}</span>
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} className="border-b border-edge/30 last:border-0">
                    <td className="px-3 py-2">
                      <ConfidenceBar value={r.confidence} threshold={r.threshold} />
                    </td>
                    <td className="whitespace-nowrap px-3 py-2">
                      {r.escalated ? (
                        <span className="text-amber-400">escalated</span>
                      ) : r.audit ? (
                        <span className="text-slate-400">accepted · audited</span>
                      ) : (
                        <span className="text-emerald-400">accepted</span>
                      )}
                      {r.agreedWithStrong === true && r.escalated && (
                        <span className="ml-1.5 text-[10px] text-slate-600">(agreed — wasted)</span>
                      )}
                      {r.agreedWithStrong === false && !r.escalated && (
                        <span className="ml-1.5 text-[10px] text-rose-400">(missed)</span>
                      )}
                    </td>
                    <td className="max-w-xs truncate px-3 py-2 text-slate-500" title={r.concerns ?? ""}>
                      {r.concerns ?? "no concerns"}
                    </td>
                    <td className="whitespace-nowrap px-3 py-2 text-slate-400">
                      {r.escalated ? r.strongModel : r.cheapModel}
                    </td>
                    <td className="readout px-3 py-2 text-slate-500">${(r.cost ?? 0).toFixed(5)}</td>
                    <td className="readout px-3 py-2 text-slate-500">{r.totalLatencyMs}ms</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Plane>
        )}
      </section>
    </div>
  );
}

/**
 * Where requests left the cascade, as proportion rather than percentage.
 *
 * <p>A stacked bar rather than two numbers, because the thing worth seeing at a
 * glance is the shape of the split — a cascade escalating most of its traffic is
 * costing money, and that should be obvious without arithmetic.
 */
function Ladder({ status, accepted }: { status: Status | null; accepted: number }) {
  const total = status?.requests ?? 0;
  const escalated = status?.escalated ?? 0;
  const pctAccepted = total === 0 ? 0 : (accepted / total) * 100;

  return (
    <Plane className="p-5">
      <div className="flex h-9 w-full min-w-0 overflow-hidden rounded border border-edge/70">
        {total === 0 ? (
          <div className="flex flex-1 items-center justify-center text-xs text-slate-600">
            no traffic yet
          </div>
        ) : (
          <>
            <div
              className="flex items-center justify-center bg-emerald-500/25 text-[11px] font-medium text-emerald-200 transition-all duration-500"
              style={{ width: `${pctAccepted}%` }}
              title={`${accepted} answered by the cheap tier`}
            >
              {pctAccepted > 14 && `${accepted} cheap`}
            </div>
            <div
              className="flex items-center justify-center bg-amber-500/25 text-[11px] font-medium text-amber-200 transition-all duration-500"
              style={{ width: `${100 - pctAccepted}%` }}
              title={`${escalated} escalated to the strong tier`}
            >
              {100 - pctAccepted > 14 && `${escalated} escalated`}
            </div>
          </>
        )}
      </div>
      <div className="mt-3 flex flex-wrap items-center gap-x-6 gap-y-2 text-xs text-slate-500">
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-sm bg-emerald-500/60" />
          answered by {status?.cheapTier?.model ?? "the cheap tier"}
        </span>
        <span className="flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-sm bg-amber-500/60" />
          escalated to {status?.strongTier?.model ?? "the strong tier"}
        </span>
        <span className="ml-auto readout">
          {total} request{total === 1 ? "" : "s"}
        </span>
      </div>
    </Plane>
  );
}

function TierPlane({ tier, role, note }: { tier: Tier | null; role: string; note: string }) {
  return (
    <Plane className="p-4">
      <Micro>{role}</Micro>
      {tier ? (
        <>
          <div className="mt-1.5 text-sm font-medium text-slate-200">{tier.model}</div>
          <div className="mt-0.5 text-xs text-slate-500">
            {tier.provider}
            {tier.label ? ` · ${tier.label}` : ""} · ${tier.costPer1k.toFixed(5)}/1k in
          </div>
        </>
      ) : (
        <div className="mt-1.5 text-sm text-slate-600">—</div>
      )}
      <p className="mt-2 text-xs text-slate-600">{note}</p>
    </Plane>
  );
}

/**
 * The calibration curve, drawn from the tenant's own labelled outcomes.
 *
 * <p>Bins below the evidence floor are drawn hollow: showing a confident bar
 * from four samples would be exactly the overclaiming the calibrator exists to
 * prevent.
 */
function CalibrationCurve({
  curve,
  threshold,
  calibrated,
}: {
  curve: any[];
  threshold: number;
  calibrated: boolean;
}) {
  return (
    <Plane className="p-5">
      <div className="flex h-32 items-end gap-1">
        {curve.map((p) => {
          const h = p.calibrated == null ? 0 : Math.max(2, p.calibrated * 100);
          const belowThreshold = p.rawTo <= threshold;
          return (
            <div key={p.bin} className="flex min-w-0 flex-1 flex-col items-center justify-end gap-1">
              <div
                className={`w-full rounded-sm transition-all duration-500 ${
                  p.calibrated == null
                    ? "border border-dashed border-edge"
                    : belowThreshold
                      ? "bg-amber-500/50"
                      : "bg-aurora/60"
                }`}
                style={{ height: `${p.calibrated == null ? 8 : h}%` }}
                title={
                  p.calibrated == null
                    ? `${p.samples} samples — not enough to calibrate`
                    : `raw ${p.rawFrom.toFixed(1)}–${p.rawTo.toFixed(1)} → ${(p.calibrated * 100).toFixed(0)}% sufficient (${p.samples} samples)`
                }
              />
              <span className="readout text-[9px] text-slate-600">{p.rawFrom.toFixed(1)}</span>
            </div>
          );
        })}
      </div>
      <p className="mt-3 text-xs text-slate-500">
        {calibrated
          ? "Bars show how often a cheap answer at that judge score actually matched the strong model. Amber bars fall below your threshold and would escalate."
          : "Not enough labelled outcomes yet — the raw judge score is being used unchanged. Hollow bars are bins with too few samples to trust."}
      </p>
    </Plane>
  );
}

/** Confidence against the threshold that decided it, on one line. */
function ConfidenceBar({ value, threshold }: { value: number; threshold: number }) {
  const pass = value >= threshold;
  return (
    <div className="flex min-w-0 items-center gap-2">
      <div className="relative h-1.5 w-16 shrink-0 overflow-hidden rounded-full bg-edge/60">
        <div
          className={`h-full rounded-full ${pass ? "bg-emerald-500/70" : "bg-amber-500/70"}`}
          style={{ width: `${Math.max(2, Math.min(100, value * 100))}%` }}
        />
        <div
          className="absolute top-0 h-full w-px bg-slate-400"
          style={{ left: `${Math.min(100, threshold * 100)}%` }}
          title={`threshold ${threshold.toFixed(2)}`}
        />
      </div>
      <span className="readout text-slate-400">{value.toFixed(2)}</span>
    </div>
  );
}
