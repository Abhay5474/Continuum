import { useCallback, useEffect, useState } from "react";
import { visibleInterval } from "../system/poll";
import { portal } from "../api";
import { Meter, PageHeader, Switch, Note, InfoTip } from "../system/primitives";
import { ErrorState, useToast } from "../components/ui";
import {
  Card,
  CardHead,
  Dot,
  Explain,
  Empty,
  Ghost,
  Hop,
  KindMark,
  Rail,
  Route,
  Row,
  RowSkeleton,
  Stage,
  Stat,
  Stats,
} from "../system/hub";
import { BeforeAfter } from "../system/charts";
import { Select } from "../system/controls";

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
const BANDS: [number, string, string, string][] = [
  [0.55, "Frugal", "Accepts more cheap answers. Lowest bill, most risk of a weak answer slipping through.", "lowest cost"],
  [0.75, "Balanced", "Escalates on any clear defect. The recommended setting.", "recommended"],
  [0.9, "Careful", "Escalates on the slightest doubt. Closest to always using the strong model.", "highest quality"],
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
    return visibleInterval(load, 5000);
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
    <div>
      <PageHeader
        glyph="route"
        tone="accent"
        title="Model Cascade"
        subtitle="Cheap model first · strong model only when needed"
      />

      {/* The ladder, as a path. Two tiers and a judge between them is the whole
          feature, and it is a shape rather than a paragraph. */}
      <div className="mt-6">
        <Route>
          <Stage label="a request" sub="from your app" />
          <Hop />
          <Stage
            label={status?.cheapTier?.model ?? "cheap tier"}
            sub="every request starts here"
            state={status?.enabled ? "on" : "off"}
            mark={<KindMark kind="classification" size={26} />}
          />
          <Hop label="judged" />
          <Stage
            label={status?.strongTier?.model ?? "strong tier"}
            sub={
              (status?.requests ?? 0) > 0
                ? `${Math.round((status?.escalationRate ?? 0) * 100)}% climb here`
                : "only on a failed judgement"
            }
            state={status?.enabled ? "on" : "off"}
            mark={<KindMark kind="classification" size={26} />}
            selected
          />
          <Hop />
          <Stage label="your app" sub="one answer, either way" />
        </Route>
      </div>

      {status && !status.available && (
        <p
          className="mt-4 max-w-2xl border-l-2 pl-3.5 text-xs leading-relaxed text-slate-400"
          style={{ borderColor: "var(--state-warning-ink)" }}
        >
          <span style={{ color: "var(--state-warning-ink)" }}>Not available.</span> A cascade needs
          two models at different prices; this registry has no meaningful price gap.
        </p>
      )}

      <div className="mt-7">
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
      </div>

      {/* ---- the ladder: where requests actually left ---- */}
      <section className="mt-9">
        <h2 className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight text-slate-200">
          Where requests left
        </h2>
        <div className="mt-3">
          <Ladder status={status} accepted={accepted} />
        </div>
      </section>

      {/* ---- savings, next to the numbers that could disprove them ---- */}
      <section className="mt-9">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Did it work
          <InfoTip text="Shown with the two numbers that would disprove it." />
        </h2>
        <div className="mt-4">
          <Stats>
            <Stat
              label="Saved"
              value={`$${(status?.saved ?? 0).toFixed(4)}`}
              tone={(status?.saved ?? 0) > 0 ? "ok" : undefined}
              hint={`vs $${(status?.strongOnlySpend ?? 0).toFixed(4)} on the strong model alone`}
            />
            <Stat
              label="Reduction"
              value={Math.round((status?.savedPct ?? 0) * 100)}
              unit="%"
              tone={(status?.savedPct ?? 0) > 0 ? "ok" : undefined}
            />
            <Stat
              label="Wasted escalations"
              value={status?.wastedEscalations ?? 0}
              tone={(status?.wastedEscalations ?? 0) > 0 ? "warn" : undefined}
              hint="Escalated, and the strong model agreed anyway"
            />
            <Stat
              label="Missed escalations"
              value={status?.missedEscalations ?? 0}
              tone={(status?.missedEscalations ?? 0) > 0 ? "bad" : "ok"}
              hint={
                (status?.auditSamples ?? 0) === 0
                  ? "No audit samples yet"
                  : `of ${status?.auditSamples} audited`
              }
            />
          </Stats>
        </div>

        {status && status.requests > 0 && (
          <Card className="mt-4">
            <CardHead glyph="coin" tone="green" title="Spend, with and without the cascade"
                      sub="Same requests, priced two ways" />
            <div className="mt-3 max-w-xl">
              <BeforeAfter
                beforeLabel="Strong only"
                afterLabel="Cascade"
                before={status.strongOnlySpend}
                after={status.spend}
                format={(n) => (n > 0 && n < 0.0001 ? "<$0.0001" : `$${n.toFixed(4)}`)}
              />
            </div>
          </Card>
        )}

        {status && status.requests > 0 && (
          <p
            className="mt-5 max-w-2xl border-l-2 pl-3.5 text-xs leading-relaxed text-slate-400"
            style={{
              borderColor:
                status.missedEscalations > 0
                  ? "var(--state-critical-ink)"
                  : status.auditSamples > 0
                    ? "var(--state-healthy-ink)"
                    : "var(--state-idle-ink)",
            }}
          >
            {status.missedEscalations > 0 ? (
              <>
                The judge accepted {status.missedEscalations} answer
                {status.missedEscalations === 1 ? "" : "s"} the strong model disagreed with. Raise the
                threshold.
              </>
            ) : status.auditSamples > 0 ? (
              <>
                The strong model agreed with every accepted answer, across{" "}
                {status.auditSamples} audited request{status.auditSamples === 1 ? "" : "s"}.
              </>
            ) : (
              "Turn up the audit rate to measure what the judge is missing."
            )}
          </p>
        )}

        {status?.overCap && (
          <p className="mt-3 max-w-2xl text-xs leading-relaxed" style={{ color: "var(--state-warning-ink)" }}>
            Escalation rate is {(status.escalationRate * 100).toFixed(0)}%, above the{" "}
            {(status.escalationCap * 100).toFixed(0)}% cap. At this rate the cascade costs more than
            using the strong model directly.
          </p>
        )}
      </section>

      {/* ---- tiers ---- */}
      <section className="mt-9">
        <h2 className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight text-slate-200">Tiers
          <InfoTip text="From the model registry, by price." />
        </h2>
        <div className="mt-3">
          <Rail>
            <TierRow tier={status?.cheapTier ?? null} role="Cheap" note="Every request starts here" />
            <TierRow tier={status?.strongTier ?? null} role="Strong" note="Only on a failed judgement" />
          </Rail>
        </div>
      </section>

      {/* ---- threshold ---- */}
      <section className="mt-9">
        <h2 className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight text-slate-200">
          Escalation threshold
          <InfoTip text="How sure the judge must be to accept a cheap answer." />
        </h2>

        <div className="mt-5 max-w-2xl">
          <div className="flex items-baseline justify-between text-[11px]">
            <span className="text-slate-500">cheaper, more weak answers accepted</span>
            <span className="text-slate-500">costlier, almost nothing slips</span>
          </div>
          <div className="relative mt-2 h-1 rounded-full" style={{ background: "rgb(var(--edge))" }}>
            <span
              className="absolute top-1/2 h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full transition-[left] duration-300 ease-out"
              style={{
                left: `${(((status?.threshold ?? 0.75) - 0.5) / 0.45) * 100}%`,
                background: "var(--accent)",
                boxShadow: "0 0 0 3px var(--accent-wash)",
              }}
              aria-hidden
            />
          </div>
          <div className="mt-4 flex flex-wrap gap-x-6 gap-y-3">
            {BANDS.map(([value, name, note, tag]) => {
              const active = Math.abs((status?.threshold ?? 0.75) - value) < 0.005;
              return (
                <button
                  key={name}
                  disabled={busy}
                  onClick={() => run(() => portal.cascade.configure({ threshold: value }), `Threshold: ${name}`)}
                  className="min-w-0 flex-1 basis-40 text-left transition-opacity disabled:opacity-50"
                  data-tip={note}
                  aria-pressed={active}
                >
                  <div className="flex items-baseline gap-2">
                    <span
                      className="text-[13px] font-medium"
                      style={{ color: active ? "var(--accent-ink)" : "var(--text-2)" }}
                    >
                      {name}
                    </span>
                    <span className="readout text-[11px] text-slate-600">{value.toFixed(2)}</span>
                  </div>
                  <span className="micro mt-0.5 block">{tag}</span>
                  <span
                    className="mt-1.5 block h-[2px] w-full rounded-full transition-opacity duration-200"
                    style={{ background: "var(--accent)", opacity: active ? 1 : 0 }}
                    aria-hidden
                  />
                </button>
              );
            })}
          </div>
        </div>

        <div className="mt-7 flex flex-wrap items-start gap-x-8 gap-y-4">
          <label className="min-w-0">
            <span className="micro">Audit rate</span>
            <Select
              value={status?.auditRate ?? 0.05}
              disabled={busy}
              onChange={(e) =>
                run(() => portal.cascade.configure({ auditRate: Number(e.target.value) }), "Audit rate updated")
              }
            >
              <option value={0}>Off — no miss measurement</option>
              <option value={0.02}>2% of requests</option>
              <option value={0.05}>5% of requests</option>
              <option value={0.1}>10% of requests</option>
            </Select>
            <Note className="mt-1.5">
              Runs both tiers on a sample to find missed escalations. Costs a second call on those.
            </Note>
          </label>
          <div className="flex items-center gap-3 self-end">
            <span className="text-xs text-slate-500">
              calibration: {status?.calibration?.observations ?? 0} labelled
            </span>
            <Ghost
              disabled={busy || !status?.requests}
              onClick={() => run(() => portal.cascade.reset(), "Cascade history cleared")}
            >
              Reset history
            </Ghost>
          </div>
        </div>
      </section>

      {/* ---- should this cascade run speculatively? ---- */}
      {status?.speculation && (
        <section className="mt-10">
          <h2 className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight text-slate-200">
            Parallel escalation
          <InfoTip text="Firing both tiers at once trades cost for latency. Whether that pays depends on how often you escalate." />
        </h2>

          <div className="mt-4 max-w-xl">
            <Meter
              value={Math.min(1, status.speculation.escalationRate)}
              state={status.speculation.worthwhile ? "healthy" : "degraded"}
              label={`Escalation rate against the ${Math.round(
                status.speculation.breakEven * 100
              )}% break-even`}
              height={8}
            />
          </div>

          <div className="mt-5">
            <Stats>
              <Stat
                label="Extra spend"
                value={`$${status.speculation.extraSpendUsd.toFixed(5)}`}
                tone="warn"
                hint="Paying for the strong model on requests that never needed it."
              />
              <Stat
                label="Latency saved"
                value={Math.round(status.speculation.latencySavedMs)}
                unit="ms"
                tone="ok"
                hint="On escalating requests, the strong call was already running."
              />
              <Stat
                label="Verdict"
                value={status.speculation.worthwhile ? "worth it" : "not yet"}
                tone={status.speculation.worthwhile ? "ok" : undefined}
              />
            </Stats>
          </div>

          {/* Too few decisions: show how far along the sample is, rather than
              a paragraph apologising for the lack of one. */}
          {(status?.requests ?? 0) < 30 ? (
            <div className="mt-4 max-w-xl">
              <Meter
                value={Math.min(1, (status?.requests ?? 0) / 30)}
                state="idle"
                label={`Sample ${status?.requests ?? 0} / 30 decisions — the verdict needs 30`}
                height={6}
              />
            </div>
          ) : (
            <p className="mt-4 max-w-2xl text-xs text-slate-500">{status.speculation.summary}</p>
          )}
          <Explain title="Why there is no single score">
            <p>
              Whether a millisecond is worth a cent is a product decision, not an arithmetic one.
              There is no universal exchange rate between latency and money, so both numbers are
              shown rather than collapsed into one.
            </p>
          </Explain>
        </section>
      )}

      {/* ---- calibration curve ---- */}
      {(status?.calibration?.observations ?? 0) > 0 && (
        <section className="mt-10">
          <h2 className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight text-slate-200">Calibration
          <InfoTip text="Judge score against how often the cheap answer actually sufficed." />
        </h2>
          <div className="mt-4">
            <CalibrationCurve
              curve={status!.calibration.curve}
              threshold={status!.threshold}
              calibrated={status!.calibration.calibrated}
            />
          </div>
        </section>
      )}

      {/* ---- decision tape ---- */}
      <section className="mt-10">
        <h2 className="flex items-baseline gap-2 text-[13px] font-semibold tracking-tight text-slate-200">
          Decisions
          {rows && <span className="readout text-[11px] font-normal text-slate-600">{rows.length}</span>}
          <span className="text-[11px] font-normal text-slate-600">newest first</span>
        </h2>
        <div className="mt-3">
          {rows === null ? (
            <RowSkeleton rows={4} />
          ) : rows.length === 0 ? (
            <Empty
              title="No decisions yet"
              hint="Turn the cascade on and send a request."
            />
          ) : (
            <Rail>
              {rows.map((r) => {
                const wasted = r.agreedWithStrong === true && r.escalated;
                const missed = r.agreedWithStrong === false && !r.escalated;
                return (
                  <Row
                    key={r.id}
                    title={r.concerns ?? "no concerns"}
                    subtitle={`${r.escalated ? r.strongModel : r.cheapModel} · $${(r.cost ?? 0).toFixed(5)} · ${r.totalLatencyMs}ms`}
                    status={
                      <Dot
                        tone={missed ? "bad" : r.escalated ? "warn" : "ok"}
                        label={
                          missed
                            ? "accepted — should not have been"
                            : wasted
                              ? "escalated — agreed anyway"
                              : r.escalated
                                ? "escalated"
                                : r.audit
                                  ? "accepted · audited"
                                  : "accepted"
                        }
                      />
                    }
                    trailing={<ConfidenceBar value={r.confidence} threshold={r.threshold} />}
                  />
                );
              })}
            </Rail>
          )}
        </div>
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
    <div>
      <div
        className="flex h-9 w-full min-w-0 overflow-hidden rounded-md"
        style={{ boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }}
      >
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
        <span className="readout ml-auto">
          {total} request{total === 1 ? "" : "s"}
        </span>
      </div>
    </div>
  );
}

function TierRow({ tier, role, note }: { tier: Tier | null; role: string; note: string }) {
  return (
    <Row
      mark={<KindMark kind="classification" size={28} />}
      title={tier?.model ?? "—"}
      subtitle={note}
      status={<span className="micro">{role}</span>}
      trailing={
        tier ? (
          <span className="text-right">
            <span className="readout block text-[12.5px] text-slate-300">
              ${tier.costPer1k.toFixed(5)}
            </span>
            <span className="text-[10.5px] text-slate-600">per 1k in</span>
          </span>
        ) : undefined
      }
      meta={
        tier ? (
          <span className="text-[11.5px] text-slate-600">
            {tier.provider}
            {tier.label ? ` · ${tier.label}` : ""}
          </span>
        ) : undefined
      }
    />
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
    <div className="max-w-3xl">
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
      <p className="mt-3 text-xs text-slate-500 max-w-2xl leading-relaxed">
        {calibrated
          ? "Amber bars fall below your threshold and would escalate."
          : "Not enough labelled outcomes yet — the raw score is used unchanged. Hollow bars have too few samples to trust."}
      </p>
    </div>
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
