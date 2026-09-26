import { useCallback, useEffect, useState } from "react";
import { visibleInterval } from "../system/poll";
import { portal } from "../api";
import { PageHeader, Readout, Switch, Note } from "../system/primitives";
import { ChartFrame, Histogram, seriesColor } from "../system/charts";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { dateTimeOf } from "../system/time";
import { Segmented, Empty, Pill } from "../system/hub";
import { Select } from "../system/controls";

/**
 * Semantic uncertainty.
 *
 * <p>The number on its own is not the product. A confidence of 0.19 tells a
 * developer nothing they can act on; the three incompatible answers that
 * produced it tell them everything. So the centre of this page is the
 * disagreement itself — each measurement expands into the meaning classes the
 * samples fell into, sized by how many samples agreed.
 *
 * <p>The cost of measuring is shown next to what it bought, because k samples
 * means k times the tokens and pretending otherwise would be dishonest.
 */

type Mode = "OFF" | "ON_DEMAND" | "ADAPTIVE" | "ALWAYS";

type Status = {
  mode: Mode;
  samples: number;
  temperature: number;
  lowConfidence: number;
  adaptiveEnabled: boolean;
  overturnThreshold: number;
  measured: number;
  lowConfidenceCount: number;
  lowConfidenceRate: number;
  avgConfidence: number;
  extraCost: number;
  extraMs: number;
  histogram: { from: number; to: number; count: number }[];
};

const MODES: [Status["mode"], string, string][] = [
  ["OFF", "Off", "No measurement. Answers carry no confidence field."],
  ["ON_DEMAND", "On demand", "Only when the request sets measureUncertainty. You choose per call."],
  ["ADAPTIVE", "Adaptive", "Only where the cascade's judge was already unsure. Recommended — the multiplier hits a slice, not everything."],
  ["ALWAYS", "Always", "Every request. Honest, and k times the bill."],
];

export default function Uncertainty() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [rows, setRows] = useState<any[] | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [s, m] = await Promise.all([portal.uncertainty.status(), portal.uncertainty.measurements(20)]);
      setStatus(s);
      setRows(m);
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load uncertainty settings.");
    }
  }, []);

  useEffect(() => {
    load();
    return visibleInterval(load, 6000);
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

  return (
    <div className="space-y-8">
      <PageHeader
        glyph="gauge"
        tone="warn"
        title="Answer Confidence"
        subtitle="Samples an answer several times · measures agreement"
      />

      {/* ---- mode ----
          One setting with four positions, not four products. As four bordered
          cards the choice read as a menu of features and the note under each
          competed with the other three; as a control with one consequence
          stated beneath it, only the position you are in has to be read. */}
      <section>
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">When to measure</h2>
        <div className="mt-3">
          <Segmented<Mode>
            value={status?.mode ?? "OFF"}
            onChange={(v) =>
              run(
                () => portal.uncertainty.configure({ mode: v }),
                `Mode: ${MODES.find(([m]) => m === v)?.[1] ?? v}`
              )
            }
            options={MODES.map(([value, label]) => ({
              value,
              label,
              badge:
                status?.mode === value && value !== "OFF" ? (
                  <span
                    className="h-1.5 w-1.5 rounded-full"
                    style={{ background: "var(--accent)" }}
                    aria-hidden
                  />
                ) : undefined,
            }))}
          />
        </div>
        {/* What the position means, shown on ten requests: which of them get
            sampled, and what each sampled one costs. */}
        <div className="mt-4 flex flex-wrap items-center gap-x-5 gap-y-2">
          <Coverage mode={status?.mode ?? "OFF"} samples={status?.samples ?? 3} />
          <p className="min-w-0 max-w-md flex-1 text-xs leading-relaxed text-slate-500">
            {MODES.find(([m]) => m === (status?.mode ?? "OFF"))?.[2]}
          </p>
        </div>
      </section>

      {/* ---- what it found ---- */}
      <div className="plane grid grid-cols-2 gap-x-8 gap-y-5 p-4 sm:grid-cols-3 lg:grid-cols-4">
        <Readout label="Measured" value={status?.measured ?? 0} />
        <Readout
          label="Mean confidence"
          value={(status?.avgConfidence ?? 0).toFixed(2)}
          state={(status?.avgConfidence ?? 0) >= 0.7 ? "healthy" : (status?.avgConfidence ?? 0) > 0 ? "warning" : "idle"}
        />
        <Readout
          label="Flagged low"
          value={status?.lowConfidenceCount ?? 0}
          state={(status?.lowConfidenceCount ?? 0) > 0 ? "warning" : "idle"}
          hint={`below ${(status?.lowConfidence ?? 0.5).toFixed(2)}`}
        />
        <Readout
          label="Extra spend"
          value={`$${(status?.extraCost ?? 0).toFixed(5)}`}
          hint="What the resampling cost"
        />
        <Readout label="Added latency" value={status?.extraMs ?? 0} unit="ms" hint="Mean, per measurement" />
      </div>

      {/* ---- distribution ---- */}
      {(status?.measured ?? 0) > 0 && (
        <section className="space-y-3">
          <div>
            {/* Ordered bins, so the x-axis carries meaning and a magnitude ramp
                is correct here — unlike nominal categories, where a ramp would
                re-encode bar height as colour. */}
            <ChartFrame
              title="Confidence distribution · a single average would hide a split workload"
              valueLabel="Answers"
              caption="Each bar is a confidence band. A workload split between certain and unsure averages to a middle figure that describes neither."
              data={status!.histogram.map((h) => ({
                key: String(h.from),
                label: `${h.from.toFixed(1)}–${h.to.toFixed(1)}`,
                value: h.count,
              }))}
            >
              <Histogram
                height={140}
                xLabel="confidence"
                endLabel={(status!.histogram[status!.histogram.length - 1]?.to ?? 1).toFixed(1)}
                bins={status!.histogram.map((h) => ({
                  label: h.from.toFixed(1),
                  value: h.count,
                  // Flagged bands in amber, so the share of traffic that comes
                  // back marked "unsure" is a colour, not a sum.
                  color: h.to <= status!.lowConfidence + 1e-9 ? "var(--state-warning-ink)" : "var(--state-healthy-ink)",
                  hint: `${h.count} answer${h.count === 1 ? "" : "s"} at confidence ${h.from.toFixed(1)}–${h.to.toFixed(1)}`,
                }))}
              />
            </ChartFrame>
            <Note className="mt-3">
              Bands at or below{" "}
              <span className="readout text-amber-400">
                {status!.lowConfidence.toFixed(2)}
              </span>{" "}
              are flagged as low confidence in the response your application receives.
            </Note>
          </div>
        </section>
      )}

      {/* ---- adaptive stopping ---- */}
      <section className="space-y-3">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Adaptive consensus</h2>
        <div className="space-y-3">
          <Switch
            checked={!!status?.adaptiveEnabled}
            busy={busy}
            onChange={(next) =>
              run(
                () => portal.uncertainty.configure({ adaptiveEnabled: next }),
                next ? "Adaptive consensus on." : "Adaptive consensus off."
              )
            }
            label="Stop sampling once the answer is decided"
            hint="Off by default — every measurement draws the full sample count. With it on, sampling stops as soon as further samples could not reasonably change the answer, so an easy question costs two samples and a contested one still costs the budget."
          />

          <label className="block max-w-sm">
            <span className="micro">Stop when the chance of being overturned is below</span>
            <Select
              value={status?.overturnThreshold ?? 0.05}
              disabled={busy || !status?.adaptiveEnabled}
              onChange={(e) =>
                run(
                  () => portal.uncertainty.configure({ overturnThreshold: Number(e.target.value) }),
                  "Threshold updated"
                )
              }
            >
              {[0.01, 0.02, 0.05, 0.1, 0.2].map((n) => (
                <option key={n} value={n}>
                  {(n * 100).toFixed(0)}% — {n <= 0.02 ? "cautious, more samples" : n >= 0.1 ? "eager, fewer samples" : "balanced"}
                </option>
              ))}
            </Select>
          </label>

          <Note>
            Never stops below two samples, never exceeds the budget — so it can only ever cost less.
          </Note>
          <Note>
            It cannot shorten a genuinely contested question, and should not — disagreement is
            exactly what the extra samples are for. The saving comes from the easy majority of
            traffic.
          </Note>
        </div>
      </section>

      {/* ---- settings ---- */}
      <section className="space-y-3">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Sampling</h2>
        {/* items-start, not items-end: a note under one control used to push
            that control's label down and leave the row misaligned. */}
        <div className="flex flex-wrap items-start gap-6">
          <label>
            <span className="micro">Samples</span>
            <Select
              value={status?.samples ?? 3}
              disabled={busy}
              onChange={(e) =>
                run(() => portal.uncertainty.configure({ samples: Number(e.target.value) }), "Sample count updated")
              }
            >
              {[2, 3, 4, 5, 7].map((n) => (
                <option key={n} value={n}>
                  {n} — {n}× tokens
                </option>
              ))}
            </Select>
          </label>
          <label>
            <span className="micro">Temperature</span>
            <Select
              value={status?.temperature ?? 0.7}
              disabled={busy}
              onChange={(e) =>
                run(() => portal.uncertainty.configure({ temperature: Number(e.target.value) }), "Temperature updated")
              }
            >
              {[0.3, 0.5, 0.7, 1.0].map((t) => (
                <option key={t} value={t}>
                  {t.toFixed(1)}
                </option>
              ))}
            </Select>
          </label>
          <label>
            <span className="micro">Flag below</span>
            <Select
              value={status?.lowConfidence ?? 0.5}
              disabled={busy}
              onChange={(e) =>
                run(
                  () => portal.uncertainty.configure({ lowConfidence: Number(e.target.value) }),
                  "Threshold updated"
                )
              }
            >
              {[0.3, 0.5, 0.7, 0.9].map((t) => (
                <option key={t} value={t}>
                  {t.toFixed(1)}
                </option>
              ))}
            </Select>
          </label>
          <button
            disabled={busy || !status?.measured}
            onClick={() => run(() => portal.uncertainty.clear(), "History cleared")}
            className="ml-auto rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:bg-edge/50 disabled:opacity-40"
          >
            Clear history
          </button>
          <Note>
            Temperature cannot be set to zero: every sample would be identical and the measurement
            would report certainty about everything.
          </Note>
        </div>
      </section>

      {/* ---- the disagreement itself ---- */}
      <section className="space-y-3">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Measurements · click one to see what the samples actually said</h2>
        {rows === null ? (
          <SkeletonRows rows={4} />
        ) : rows.length === 0 ? (
          <Empty title={"Nothing measured yet"} hint={<>Set a mode above, then send a request through the gateway — or add{" "} <code className="text-slate-400">"measureUncertainty": true</code> to a single call.</>} />
        ) : (
          <div className="space-y-2">
            {rows.map((r) => (
              <Measurement
                key={r.id}
                row={r}
                lowBar={status?.lowConfidence ?? 0.5}
                open={open === r.id}
                onToggle={() => setOpen(open === r.id ? null : r.id)}
              />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

/**
 * One measurement, expanding into its meaning classes.
 *
 * <p>The collapsed row carries the confidence and the cluster count; the
 * expansion carries the actual answers. That ordering matters — the number is
 * what a program branches on, the answers are what a human needs to judge
 * whether the number is right.
 */
function Measurement({
  row,
  lowBar,
  open,
  onToggle,
}: {
  row: any;
  lowBar: number;
  open: boolean;
  onToggle: () => void;
}) {
  const low = row.confidence < lowBar;
  const breakdown: any[] = row.breakdown ?? [];

  return (
    <div className="overflow-hidden">
      <button
        onClick={onToggle}
        aria-expanded={open}
        className="flex w-full min-w-0 items-center gap-4 px-4 py-3 text-left transition-colors hover:bg-edge/30"
      >
        <ConfidenceDial value={row.confidence} low={low} />
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm text-slate-300">{row.prompt ?? "—"}</div>
          <div className="mt-1 flex flex-wrap items-center gap-x-3 gap-y-1 text-xs text-slate-500">
            <VoteStrip breakdown={breakdown} samples={row.samples} />
            <span>
              {row.clusters === 1 ? "all agree" : `${row.clusters} different answers`} · {row.model} · {dateTimeOf(row.createdAt)}
            </span>
          </div>
        </div>
        {low && (
          <span className="shrink-0 rounded border border-amber-500/40 px-2 py-0.5 text-[10px] font-medium uppercase tracking-wider text-amber-300">
            Low
          </span>
        )}
        <span className={`shrink-0 text-slate-600 transition-transform ${open ? "rotate-90" : ""}`}>›</span>
      </button>

      {open && (
        <div className="border-t border-edge/60 px-4 py-3">
          {breakdown.length === 0 ? (
            <p className="text-xs text-slate-500 max-w-2xl leading-relaxed">No breakdown stored for this measurement.</p>
          ) : (
            <div className="space-y-2">
              {breakdown.map((c, i) => (
                <div key={i} className="flex min-w-0 items-start gap-3">
                  {/* One dot per sample: filled for the ones that gave this answer. */}
                  <div className="flex shrink-0 flex-col items-start gap-1 pt-0.5" style={{ width: Math.max(64, row.samples * 11) }}>
                    <span className="flex gap-[3px]" role="img" aria-label={`${c.size} of ${row.samples} samples`}>
                      {Array.from({ length: row.samples }, (_, k) => (
                        <span
                          key={k}
                          className="h-2 w-2 rounded-full"
                          style={
                            k < c.size
                              ? { background: seriesColor(i) }
                              : { boxShadow: "inset 0 0 0 1.2px rgb(var(--card-edge))" }
                          }
                        />
                      ))}
                    </span>
                    <span className="readout text-[10.5px] text-slate-500">{c.size} of {row.samples}</span>
                  </div>
                  <p className="min-w-0 flex-1 text-xs leading-relaxed text-slate-400 max-w-2xl">
                    {c.representative}
                  </p>
                </div>
              ))}
              {/* The verdict as figures: agreement, spread, what measuring cost. */}
              <div className="flex flex-wrap items-center gap-2 border-t border-edge/40 pt-2.5">
                <Pill tone={row.clusters === 1 ? "ok" : "warn"} dot>
                  {row.clusters === 1 ? "samples agree" : `${row.clusters} conflicting answers`}
                </Pill>
                <span className="micro">entropy {row.entropy.toFixed(2)} nats</span>
                {row.extraCost > 0 && (
                  <span className="micro">cost ${row.extraCost.toFixed(5)} · {row.extraMs}ms</span>
                )}
              </div>
            </div>
          )}
        </div>
      )}
    </div>
  );
}

/**
 * Every sample of one measurement, in a row, coloured by the answer it gave.
 *
 * <p>Agreement is the whole idea of the feature, and it is a picture: five of
 * one colour is a settled answer, three and two is a split, five colours is a
 * guess. Read before the number beside it.
 */
function VoteStrip({ breakdown, samples }: { breakdown: any[]; samples: number }) {
  const cells: number[] = [];
  breakdown.forEach((c, i) => {
    for (let k = 0; k < (c.size ?? 0); k++) cells.push(i);
  });
  while (cells.length < samples) cells.push(-1);
  return (
    <span
      className="inline-flex gap-[2px]"
      role="img"
      aria-label={breakdown.map((c) => c.size).join(" / ") + ` of ${samples} samples agreed`}
    >
      {cells.slice(0, Math.max(samples, cells.length)).map((ci, k) => (
        <span
          key={k}
          className="h-3 w-[7px] rounded-[2px]"
          style={ci < 0 ? { boxShadow: "inset 0 0 0 1px rgb(var(--card-edge))" } : { background: seriesColor(ci) }}
        />
      ))}
    </span>
  );
}

/**
 * Ten requests, and which of them a mode samples.
 *
 * <p>The four modes differ in exactly one thing — how much traffic pays the
 * multiplier — so that is what is drawn: filled squares are sampled, each one
 * costing the sample count in tokens.
 */
function Coverage({ mode, samples }: { mode: Mode; samples: number }) {
  // Illustrative positions, the same every render: a mode's shape, not data.
  const picked: Record<Mode, number[]> = {
    OFF: [],
    ON_DEMAND: [2, 7],
    ADAPTIVE: [1, 4, 8],
    ALWAYS: [0, 1, 2, 3, 4, 5, 6, 7, 8, 9],
  };
  const on = new Set(picked[mode]);
  const ink = mode === "ADAPTIVE" ? "var(--state-warning-ink)" : "var(--state-active-ink)";
  return (
    <div className="flex items-center gap-3" role="img"
         aria-label={`${on.size} of every 10 requests sampled, each at ${samples} times the tokens`}>
      <span className="flex gap-1">
        {Array.from({ length: 10 }, (_, i) => (
          <span
            key={i}
            className="grid h-4 w-4 place-items-center rounded-[4px] text-[8px] font-bold"
            style={
              on.has(i)
                ? { background: ink, color: "rgb(255 255 255)" }
                : { boxShadow: "inset 0 0 0 1px rgb(var(--card-edge))" }
            }
          >
            {on.has(i) && mode === "ON_DEMAND" ? "✓" : ""}
          </span>
        ))}
      </span>
      <span className="readout whitespace-nowrap text-[11px] text-slate-400">
        {on.size}/10 sampled{on.size > 0 ? ` · ×${samples} tokens each` : ""}
      </span>
    </div>
  );
}

/** A small radial gauge — confidence reads faster as an arc than as a number. */
function ConfidenceDial({ value, low }: { value: number; low: boolean }) {
  const r = 13;
  const c = 2 * Math.PI * r;
  const filled = Math.max(0, Math.min(1, value)) * c;
  return (
    <div className="relative h-9 w-9 shrink-0">
      <svg viewBox="0 0 32 32" className="h-9 w-9 -rotate-90">
        <circle cx="16" cy="16" r={r} fill="none" stroke="currentColor" strokeWidth="3" className="text-edge" />
        <circle
          cx="16"
          cy="16"
          r={r}
          fill="none"
          stroke="currentColor"
          strokeWidth="3"
          strokeLinecap="round"
          strokeDasharray={`${filled} ${c}`}
          style={{ color: low ? "var(--state-warning-ink)" : "var(--state-healthy-ink)" }}
        />
      </svg>
      <span className="readout absolute inset-0 flex items-center justify-center text-[10px] text-slate-300">
        {value.toFixed(2).replace("0.", ".")}
      </span>
    </div>
  );
}
