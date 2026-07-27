import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Micro, PageHeader, Plane, Readout } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { dateTimeOf } from "../system/time";

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

type Status = {
  mode: "OFF" | "ON_DEMAND" | "ADAPTIVE" | "ALWAYS";
  samples: number;
  temperature: number;
  lowConfidence: number;
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
    const t = setInterval(load, 6000);
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

  const maxBucket = Math.max(1, ...(status?.histogram ?? []).map((h) => h.count));

  return (
    <div className="space-y-6">
      <PageHeader
        title="Answer Confidence"
        subtitle="Asks the same question several times and measures whether the model agrees with itself. Disagreement about meaning — not wording — is what a hallucination looks like from the outside."
      />

      {/* ---- mode ---- */}
      <section className="space-y-3">
        <Micro>When to measure</Micro>
        <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
          {MODES.map(([value, name, note]) => {
            const active = status?.mode === value;
            return (
              <button
                key={value}
                disabled={busy}
                onClick={() => run(() => portal.uncertainty.configure({ mode: value }), `Mode: ${name}`)}
                className={`rounded-lg border p-3 text-left transition-colors disabled:opacity-50 ${
                  active ? "border-aurora/60 bg-aurora/10" : "border-edge hover:border-aurora/40"
                }`}
              >
                <div className="flex items-center gap-2">
                  <span
                    className={`h-2 w-2 shrink-0 rounded-full ${active ? "bg-aurora" : "bg-edge"}`}
                  />
                  <span className="text-sm font-medium text-slate-200">{name}</span>
                </div>
                <p className="mt-1 text-xs text-slate-500">{note}</p>
              </button>
            );
          })}
        </div>
      </section>

      {/* ---- what it found ---- */}
      <Plane className="grid gap-6 p-5 sm:grid-cols-2 lg:grid-cols-5">
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
      </Plane>

      {/* ---- distribution ---- */}
      {(status?.measured ?? 0) > 0 && (
        <section className="space-y-3">
          <Micro>Confidence distribution · a single average would hide a split workload</Micro>
          <Plane className="p-5">
            <div className="flex h-28 items-end gap-1.5">
              {status!.histogram.map((h) => (
                <div key={h.from} className="flex min-w-0 flex-1 flex-col items-center justify-end gap-1">
                  <div
                    className={`w-full rounded-sm transition-all duration-500 ${
                      h.to <= status!.lowConfidence ? "bg-amber-500/60" : "bg-aurora/60"
                    }`}
                    style={{ height: `${Math.max(h.count > 0 ? 4 : 1, (h.count / maxBucket) * 100)}%` }}
                    title={`${h.count} answer${h.count === 1 ? "" : "s"} at confidence ${h.from.toFixed(1)}–${h.to.toFixed(1)}`}
                  />
                  <span className="readout text-[9px] text-slate-600">{h.from.toFixed(1)}</span>
                </div>
              ))}
            </div>
            <p className="mt-3 text-xs text-slate-500">
              Amber bars fall below your low-confidence threshold and are flagged in the response.
            </p>
          </Plane>
        </section>
      )}

      {/* ---- settings ---- */}
      <section className="space-y-3">
        <Micro>Sampling</Micro>
        <Plane className="flex flex-wrap items-end gap-6 p-5">
          <label>
            <span className="micro">Samples</span>
            <select
              value={status?.samples ?? 3}
              disabled={busy}
              onChange={(e) =>
                run(() => portal.uncertainty.configure({ samples: Number(e.target.value) }), "Sample count updated")
              }
              className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
            >
              {[2, 3, 4, 5, 7].map((n) => (
                <option key={n} value={n}>
                  {n} — {n}× tokens
                </option>
              ))}
            </select>
          </label>
          <label>
            <span className="micro">Temperature</span>
            <select
              value={status?.temperature ?? 0.7}
              disabled={busy}
              onChange={(e) =>
                run(() => portal.uncertainty.configure({ temperature: Number(e.target.value) }), "Temperature updated")
              }
              className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
            >
              {[0.3, 0.5, 0.7, 1.0].map((t) => (
                <option key={t} value={t}>
                  {t.toFixed(1)}
                </option>
              ))}
            </select>
            <p className="mt-1 max-w-[15rem] text-xs text-slate-600">
              At zero every sample is identical and the measurement would report certainty about
              everything, so it cannot be set there.
            </p>
          </label>
          <label>
            <span className="micro">Flag below</span>
            <select
              value={status?.lowConfidence ?? 0.5}
              disabled={busy}
              onChange={(e) =>
                run(
                  () => portal.uncertainty.configure({ lowConfidence: Number(e.target.value) }),
                  "Threshold updated"
                )
              }
              className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
            >
              {[0.3, 0.5, 0.7, 0.9].map((t) => (
                <option key={t} value={t}>
                  {t.toFixed(1)}
                </option>
              ))}
            </select>
          </label>
          <button
            disabled={busy || !status?.measured}
            onClick={() => run(() => portal.uncertainty.clear(), "History cleared")}
            className="ml-auto rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:bg-edge/50 disabled:opacity-40"
          >
            Clear history
          </button>
        </Plane>
      </section>

      {/* ---- the disagreement itself ---- */}
      <section className="space-y-3">
        <Micro>Measurements · click one to see what the samples actually said</Micro>
        {rows === null ? (
          <SkeletonRows rows={4} />
        ) : rows.length === 0 ? (
          <Plane className="p-8 text-center text-sm text-slate-500">
            Nothing measured yet. Set a mode above, then send a request through the gateway — or add{" "}
            <code className="text-slate-400">"measureUncertainty": true</code> to a single call.
          </Plane>
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
    <Plane className="overflow-hidden">
      <button
        onClick={onToggle}
        aria-expanded={open}
        className="flex w-full min-w-0 items-center gap-4 px-4 py-3 text-left transition-colors hover:bg-edge/30"
      >
        <ConfidenceDial value={row.confidence} low={low} />
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm text-slate-300">{row.prompt ?? "—"}</div>
          <div className="mt-0.5 text-xs text-slate-500">
            {row.samples} samples · {row.clusters} distinct meaning{row.clusters === 1 ? "" : "s"} ·{" "}
            {row.model} · {dateTimeOf(row.createdAt)}
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
            <p className="text-xs text-slate-500">No breakdown stored for this measurement.</p>
          ) : (
            <div className="space-y-2">
              {breakdown.map((c, i) => (
                <div key={i} className="flex min-w-0 gap-3">
                  <div className="flex w-16 shrink-0 flex-col items-end">
                    <span className="readout text-xs text-slate-300">
                      {c.size}/{row.samples}
                    </span>
                    <div className="mt-1 h-1 w-full overflow-hidden rounded-full bg-edge/60">
                      <div
                        className={i === 0 ? "h-full bg-aurora/70" : "h-full bg-slate-500/60"}
                        style={{ width: `${(c.share ?? 0) * 100}%` }}
                      />
                    </div>
                  </div>
                  <p className="min-w-0 flex-1 text-xs leading-relaxed text-slate-400">
                    {c.representative}
                  </p>
                </div>
              ))}
              <p className="border-t border-edge/40 pt-2 text-xs text-slate-600">
                {row.clusters === 1
                  ? "Every sample said the same thing. That is what a confident answer looks like."
                  : `The model gave ${row.clusters} incompatible answers to one question. Entropy ${row.entropy.toFixed(2)} nats.`}
                {row.extraCost > 0 && ` Measuring cost $${row.extraCost.toFixed(5)} and ${row.extraMs}ms.`}
              </p>
            </div>
          )}
        </div>
      )}
    </Plane>
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
          className={low ? "text-amber-400" : "text-aurora"}
        />
      </svg>
      <span className="readout absolute inset-0 flex items-center justify-center text-[10px] text-slate-300">
        {value.toFixed(2).replace("0.", ".")}
      </span>
    </div>
  );
}
