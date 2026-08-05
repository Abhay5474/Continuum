import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { PageHeader, Readout, Switch } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { dateTimeOf } from "../system/time";

/**
 * Semantic circuit breaker.
 *
 * <p>The whole argument for this feature is that a provider can be up, fast,
 * returning 200s, and quietly worse — a failure a latency dashboard shows as
 * green. So this page deliberately shows what a latency dashboard cannot: the
 * quality trace against the learned baseline, and how much accumulated shortfall
 * has built up underneath it.
 *
 * <p>Pressure is the number worth watching, because it moves <em>before</em> the
 * trip. A breaker at 70% pressure is a provider degrading right now, and seeing
 * that is more useful than being told after the fact that it tripped.
 */

type Breaker = {
  provider: string;
  model: string;
  state: "CLOSED" | "OPEN" | "HALF_OPEN";
  baseline: number | null;
  recentMean: number | null;
  accumulated: number;
  pressure: number;
  warm: boolean;
  observations: number;
  warmup: number;
  trips: number;
  openedAt: string | null;
  trace: number[];
};

type Status = {
  enabled: boolean;
  warmup: number;
  slack: number;
  threshold: number;
  cooldownSeconds: number;
  breakers: Breaker[];
  open: number;
  totalTrips: number;
};

export default function BreakerPage() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [events, setEvents] = useState<any[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [s, e] = await Promise.all([portal.breaker.status(), portal.breaker.events(30)]);
      setStatus(s);
      setEvents(e);
      setError(null);
    } catch (err: any) {
      setError(err?.message ?? "Could not load the circuit breaker.");
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 4000);
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

  return (
    <div className="space-y-8">
      <PageHeader
        title="Semantic Breaker"
        subtitle="Trips a model out of rotation when its answers get worse — not when it errors. Off by default."
      />

      <div>
        <Switch
          label="Semantic circuit breaker"
          hint="Scores every answer against a learned baseline and diverts traffic away from a model whose quality has drifted. Your traffic trips your breakers only — no account can reroute another's."
          checked={status?.enabled ?? false}
          busy={busy || status === null}
          onChange={(next) =>
            run(() => portal.breaker.configure({ enabled: next }), next ? "Breaker armed" : "Breaker disarmed")
          }
        />
      </div>

      <div className="flex flex-wrap gap-x-9 gap-y-4">
        <Readout label="Watching" value={status?.breakers.length ?? 0} unit="models" />
        <Readout
          label="Diverted"
          value={status?.open ?? 0}
          state={(status?.open ?? 0) > 0 ? "critical" : "healthy"}
        />
        <Readout label="Trips" value={status?.totalTrips ?? 0} hint="Since history was last cleared" />
        <Readout
          label="Cooldown"
          value={Math.round((status?.cooldownSeconds ?? 300) / 60)}
          unit="min"
          hint="Before a probe is allowed through"
        />
      </div>

      {/* ---- the breakers ---- */}
      <section className="space-y-3">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Models · quality against the learned baseline</h2>
        {status === null ? (
          <SkeletonRows rows={3} />
        ) : status.breakers.length === 0 ? (
          <div className="text-center text-sm text-slate-500">
            {status.enabled
              ? "No observations yet. Send traffic through the gateway and each model gets a breaker once it has enough history to know what normal looks like."
              : "Arm the breaker to start watching answer quality per model."}
          </div>
        ) : (
          <div className="space-y-3">
            {status.breakers.map((b) => (
              <BreakerCard
                key={`${b.provider}/${b.model}`}
                b={b}
                busy={busy}
                onReset={() =>
                  run(() => portal.breaker.reset(b.provider, b.model), `${b.model} reset — relearning baseline`)
                }
              />
            ))}
          </div>
        )}
      </section>

      {/* ---- settings ---- */}
      <section className="space-y-3">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Sensitivity</h2>
        <div className="flex flex-wrap items-end gap-6">
          <label>
            <span className="micro">Warm-up</span>
            <select
              value={status?.warmup ?? 30}
              disabled={busy}
              onChange={(e) => run(() => portal.breaker.configure({ warmup: Number(e.target.value) }), "Warm-up updated")}
              className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
            >
              {[10, 30, 60, 120].map((n) => (
                <option key={n} value={n}>
                  {n} answers
                </option>
              ))}
            </select>
            <p className="mt-1 max-w-[14rem] text-xs text-slate-600">
              Observations before a baseline is trusted. Nothing can trip before this.
            </p>
          </label>
          <label>
            <span className="micro">Noise floor</span>
            <select
              value={status?.slack ?? 0.05}
              disabled={busy}
              onChange={(e) => run(() => portal.breaker.configure({ slack: Number(e.target.value) }), "Noise floor updated")}
              className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
            >
              {[0.02, 0.05, 0.1, 0.15].map((n) => (
                <option key={n} value={n}>
                  {n.toFixed(2)}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span className="micro">Trip at</span>
            <select
              value={status?.threshold ?? 0.75}
              disabled={busy}
              onChange={(e) => run(() => portal.breaker.configure({ threshold: Number(e.target.value) }), "Threshold updated")}
              className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
            >
              {[0.4, 0.75, 1.5, 3.0].map((n) => (
                <option key={n} value={n}>
                  {n.toFixed(2)} {n <= 0.4 ? "— eager" : n >= 1.5 ? "— patient" : ""}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span className="micro">Cooldown</span>
            <select
              value={status?.cooldownSeconds ?? 300}
              disabled={busy}
              onChange={(e) =>
                run(() => portal.breaker.configure({ cooldownSeconds: Number(e.target.value) }), "Cooldown updated")
              }
              className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
            >
              {[60, 300, 900, 3600].map((n) => (
                <option key={n} value={n}>
                  {n < 3600 ? `${n / 60} min` : "1 hour"}
                </option>
              ))}
            </select>
          </label>
          <button
            disabled={busy || (status?.breakers.length ?? 0) === 0}
            onClick={() => run(() => portal.breaker.clear(), "Breaker history cleared")}
            className="ml-auto rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:bg-edge/50 disabled:opacity-40"
          >
            Clear all
          </button>
        </div>
      </section>

      {/* ---- transitions ---- */}
      <section className="space-y-3">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Transitions</h2>
        {events === null ? (
          <SkeletonRows rows={2} />
        ) : events.length === 0 ? (
          <div className="text-center text-sm text-slate-500">
            Nothing has tripped. Every transition lands here with the evidence that caused it.
          </div>
        ) : (
          <div className="divide-y divide-edge/40">
            {events.map((e) => (
              <div key={e.id} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-4 py-2.5 text-xs">
                <span className={`shrink-0 font-medium uppercase tracking-wider ${kindColour(e.kind)}`}>
                  {e.kind}
                </span>
                <span className="shrink-0 text-slate-300">
                  {e.provider}/{e.model}
                </span>
                <span className="min-w-0 flex-1 text-slate-500">{e.detail}</span>
                <span className="readout shrink-0 text-slate-600">{dateTimeOf(e.createdAt)}</span>
              </div>
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function kindColour(kind: string) {
  return kind === "TRIPPED" || kind === "REOPENED"
    ? "text-rose-400"
    : kind === "RECOVERED"
      ? "text-emerald-400"
      : "text-amber-400";
}

/**
 * One model's breaker.
 *
 * <p>Three things at once, because they only mean something together: where the
 * quality trace is now against the baseline it learned, how much shortfall has
 * accumulated underneath, and what state that has put the breaker in.
 */
function BreakerCard({ b, busy, onReset }: { b: Breaker; busy: boolean; onReset: () => void }) {
  const open = b.state === "OPEN";
  const probing = b.state === "HALF_OPEN";

  return (
    <div>
      <div className="flex flex-wrap items-start gap-x-4 gap-y-2">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2">
            <span
              className={`h-2 w-2 shrink-0 rounded-full ${
                open ? "bg-rose-400" : probing ? "bg-amber-400" : b.warm ? "bg-emerald-400" : "bg-slate-600"
              }`}
            />
            <span className="text-sm font-medium text-slate-200">{b.model}</span>
            <span className="text-xs text-slate-500">{b.provider}</span>
          </div>
          <div className="mt-1 text-xs text-slate-500">
            {!b.warm ? (
              <>
                Learning what normal looks like — {b.observations} of {b.warmup} answers. Cannot trip yet.
              </>
            ) : open ? (
              <>
                Diverted since {dateTimeOf(b.openedAt)} · baseline {b.baseline?.toFixed(2)}, recent{" "}
                {b.recentMean?.toFixed(2)}
              </>
            ) : probing ? (
              <>Cooldown elapsed — one request is being let through to test recovery</>
            ) : (
              <>
                baseline {b.baseline?.toFixed(2)} · recent {b.recentMean?.toFixed(2)} ·{" "}
                {b.observations} answers{b.trips > 0 ? ` · ${b.trips} previous trip${b.trips === 1 ? "" : "s"}` : ""}
              </>
            )}
          </div>
        </div>
        <button
          disabled={busy || (b.state === "CLOSED" && b.trips === 0)}
          onClick={onReset}
          className="shrink-0 rounded-md border border-edge px-2.5 py-1 text-xs text-slate-300 hover:bg-edge/50 disabled:opacity-40"
        >
          Reset
        </button>
      </div>

      {/* quality trace against the baseline */}
      <div className="mt-3">
        <QualityTrace trace={b.trace} baseline={b.baseline} />
      </div>

      {/* accumulated shortfall — this moves before the trip does */}
      <div className="mt-3 flex items-center gap-3">
        <span className="micro w-20 shrink-0">Pressure</span>
        <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-edge/60">
          <div
            className={`h-full rounded-full transition-all duration-500 ${
              b.pressure >= 1 ? "bg-rose-500" : b.pressure > 0.5 ? "bg-amber-500/80" : "bg-aurora/60"
            }`}
            style={{ width: `${Math.max(b.pressure > 0 ? 2 : 0, b.pressure * 100)}%` }}
          />
        </div>
        <span className="readout w-24 shrink-0 text-right text-xs text-slate-500">
          {b.accumulated.toFixed(2)} accrued
        </span>
      </div>
    </div>
  );
}

/**
 * The quality trace with the baseline drawn through it.
 *
 * <p>The baseline line is the point: a trace on its own is just a wiggle, and
 * what matters is whether it has moved below where it used to sit.
 */
function QualityTrace({ trace, baseline }: { trace: number[]; baseline: number | null }) {
  const w = 100;
  const h = 28;
  if (!trace || trace.length < 2) {
    return (
      <div className="flex h-7 items-center rounded border border-dashed border-edge/60 px-2 text-[10px] text-slate-600">
        not enough history to draw
      </div>
    );
  }
  const step = w / (trace.length - 1);
  const y = (v: number) => h - Math.max(0, Math.min(1, v)) * h;
  const d = trace.map((v, i) => `${i === 0 ? "M" : "L"}${(i * step).toFixed(2)},${y(v).toFixed(2)}`).join(" ");

  return (
    <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="h-7 w-full" aria-hidden="true">
      {baseline != null && (
        <line
          x1="0"
          x2={w}
          y1={y(baseline)}
          y2={y(baseline)}
          stroke="currentColor"
          strokeWidth="0.5"
          strokeDasharray="2 2"
          className="text-slate-500"
        />
      )}
      <path d={d} fill="none" stroke="currentColor" strokeWidth="1" className="text-aurora" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}
