import { useCallback, useEffect, useId, useState } from "react";
import { visibleInterval } from "../system/poll";
import { portal } from "../api";
import { Card, Empty, Pill, type Tone } from "../system/hub";
import { Gauge } from "../system/viz";
import { PageHeader, Readout, Switch } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { dateTimeOf } from "../system/time";
import { Select } from "../system/controls";

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
    return visibleInterval(load, 4000);
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
        glyph="shield"
        tone="warn"
        title="Semantic Breaker"
        subtitle="Pulls a model when its answers degrade"
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

      <div className="plane grid grid-cols-2 gap-x-8 gap-y-5 p-4 sm:grid-cols-3 lg:grid-cols-4">
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
          <Empty
            title={status.enabled ? "No observations yet" : "The breaker is not armed"}
            hint={
              status.enabled
                ? "Send traffic through the gateway. Each model gets a breaker once it has enough history to know what normal looks like."
                : "Arm it above to start watching answer quality per model."
            }
          />
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
        <div className="plane flex flex-wrap items-end gap-6 p-4">
          <label>
            <span className="micro">Warm-up</span>
            <Select
              value={status?.warmup ?? 30}
              disabled={busy}
              onChange={(e) => run(() => portal.breaker.configure({ warmup: Number(e.target.value) }), "Warm-up updated")}
            >
              {[10, 30, 60, 120].map((n) => (
                <option key={n} value={n}>
                  {n} answers
                </option>
              ))}
            </Select>
            <p className="mt-1 max-w-[14rem] text-xs text-slate-600">
              Observations before a baseline is trusted. Nothing can trip before this.
            </p>
          </label>
          <label>
            <span className="micro">Noise floor</span>
            <Select
              value={status?.slack ?? 0.05}
              disabled={busy}
              onChange={(e) => run(() => portal.breaker.configure({ slack: Number(e.target.value) }), "Noise floor updated")}
            >
              {[0.02, 0.05, 0.1, 0.15].map((n) => (
                <option key={n} value={n}>
                  {n.toFixed(2)}
                </option>
              ))}
            </Select>
          </label>
          <label>
            <span className="micro">Trip at</span>
            <Select
              value={status?.threshold ?? 0.75}
              disabled={busy}
              onChange={(e) => run(() => portal.breaker.configure({ threshold: Number(e.target.value) }), "Threshold updated")}
            >
              {[0.4, 0.75, 1.5, 3.0].map((n) => (
                <option key={n} value={n}>
                  {n.toFixed(2)} {n <= 0.4 ? "— eager" : n >= 1.5 ? "— patient" : ""}
                </option>
              ))}
            </Select>
          </label>
          <label>
            <span className="micro">Cooldown</span>
            <Select
              value={status?.cooldownSeconds ?? 300}
              disabled={busy}
              onChange={(e) =>
                run(() => portal.breaker.configure({ cooldownSeconds: Number(e.target.value) }), "Cooldown updated")
              }
            >
              {[60, 300, 900, 3600].map((n) => (
                <option key={n} value={n}>
                  {n < 3600 ? `${n / 60} min` : "1 hour"}
                </option>
              ))}
            </Select>
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
          <Empty title={"Nothing has tripped"} hint={"Every transition lands here with the evidence that caused it."} />
        ) : (
          <div className="divide-y divide-edge/40">
            {events.map((e) => (
              <div key={e.id} className="flex flex-wrap items-baseline gap-x-3 gap-y-1 px-4 py-2.5 text-xs">
                <Pill tone={kindTone(e.kind)} dot>{String(e.kind).toLowerCase().replace("_", " ")}</Pill>
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

function kindTone(kind: string): Tone {
  return kind === "TRIPPED" || kind === "REOPENED" ? "bad" : kind === "RECOVERED" ? "ok" : "warn";
}

const STATES: { key: Breaker["state"]; label: string; sub: string; tone: Tone }[] = [
  { key: "CLOSED", label: "Serving", sub: "traffic flows", tone: "ok" },
  { key: "HALF_OPEN", label: "Testing", sub: "one probe let through", tone: "warn" },
  { key: "OPEN", label: "Diverted", sub: "traffic sent elsewhere", tone: "bad" },
];

/**
 * The breaker's three positions, with the one it is in lit.
 *
 * <p>A breaker is a switch with three positions, so it is drawn as one: the
 * reader sees not only where it is but where it could go, which a single
 * coloured dot beside the model name never said.
 */
function StateTrack({ state, learning }: { state: Breaker["state"]; learning: boolean }) {
  return (
    <div className="grid grid-cols-3 gap-1 rounded-xl bg-slate-500/10 p-1" role="img"
         aria-label={learning ? "Learning its baseline; cannot trip yet" : `State: ${STATES.find((x) => x.key === state)?.label}`}>
      {STATES.map((x) => {
        const on = !learning && x.key === state;
        return (
          <div
            key={x.key}
            className={`min-w-0 rounded-lg px-2.5 py-1.5 transition-colors duration-300 ${on ? "glass-pill" : ""}`}
          >
            <div className="flex items-center gap-1.5">
              <span className="h-2 w-2 shrink-0 rounded-full"
                    style={{ background: on ? `var(--state-${x.tone === "ok" ? "healthy" : x.tone === "warn" ? "warning" : "critical"}-ink)` : "rgb(var(--card-edge))" }} />
              <span className={`truncate text-[12px] font-medium ${on ? "text-slate-100" : "text-slate-500"}`}>{x.label}</span>
            </div>
            <div className={`truncate pl-3.5 text-[10.5px] ${on ? "text-slate-400" : "text-slate-600"}`}>{x.sub}</div>
          </div>
        );
      })}
    </div>
  );
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
    <Card>
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

      <div className="mt-4">
        <StateTrack state={b.state} learning={!b.warm} />
      </div>

      <div className="mt-4 grid items-center gap-5 sm:grid-cols-[auto_minmax(0,1fr)]">
        {/* Before it is warm there is nothing to trip on — so the dial shows
            how far it has got learning what normal is. After, the dial is
            pressure: accumulated shortfall against the trip point, which moves
            before the trip does. */}
        {!b.warm ? (
          <Gauge
            value={b.observations}
            max={Math.max(1, b.warmup)}
            display={`${b.observations}/${b.warmup}`}
            label="Learning normal"
            sub="answers observed"
            tone="info"
            size={140}
          />
        ) : (
          <Gauge
            value={Math.round(Math.min(1, b.pressure) * 100)}
            max={100}
            display={`${Math.round(b.pressure * 100)}%`}
            label="Pressure to trip"
            sub={`${b.accumulated.toFixed(2)} shortfall accrued`}
            warnAt={0.5}
            badAt={0.9}
            size={140}
          />
        )}
        {/* quality trace against the baseline */}
        <div className="min-w-0">
          <div className="mb-1.5 flex items-baseline justify-between gap-2">
            <span className="micro">Answer quality · recent</span>
            {b.baseline != null && (
              <span className="flex items-center gap-1.5 text-[10.5px] text-slate-500">
                <span className="inline-block h-0 w-4 border-t border-dashed border-slate-500" />normal {b.baseline.toFixed(2)}
              </span>
            )}
          </div>
          <QualityTrace trace={b.trace} baseline={b.baseline} />
        </div>
      </div>
    </Card>
  );
}

/**
 * The quality trace with the baseline drawn through it.
 *
 * <p>The baseline line is the point: a trace on its own is just a wiggle, and
 * what matters is whether it has moved below where it used to sit.
 */
function QualityTrace({ trace, baseline }: { trace: number[]; baseline: number | null }) {
  const clip = `below-${useId().replace(/:/g, "")}`;
  const w = 100;
  const h = 40;
  if (!trace || trace.length < 2) {
    return (
      <div className="flex h-16 items-center justify-center rounded-lg border border-dashed border-edge/60 px-2 text-[11px] text-slate-500">
        not enough history to draw
      </div>
    );
  }
  const step = w / (trace.length - 1);
  // Fitted to the data, floor to 1: quality lives near the top of 0–1, and on
  // a full-range axis a real dip was a pixel. A gap under the lowest reading
  // keeps a flat trace from reading as a floor.
  const lo = Math.max(0, Math.min(...trace, baseline ?? 1) - 0.15);
  const y = (v: number) => h - ((Math.max(lo, Math.min(1, v)) - lo) / (1 - lo || 1)) * h;
  const pts = trace.map((v, i) => [i * step, y(v)] as const);
  const d = pts.map(([px, py], i) => `${i === 0 ? "M" : "L"}${px.toFixed(2)},${py.toFixed(2)}`).join(" ");
  const by = baseline != null ? y(baseline) : null;
  // The area between the trace and the baseline, clipped to below it: the
  // shortfall the breaker is accumulating, as a shape.
  const area = by != null ? `${d} L${w},${by} L0,${by} Z` : null;

  return (
    <svg viewBox={`0 0 ${w} ${h}`} preserveAspectRatio="none" className="h-16 w-full" role="img"
         aria-label={`Quality trace of ${trace.length} answers${baseline != null ? ` against a baseline of ${baseline.toFixed(2)}` : ""}`}>
      <defs>
        {by != null && (
          <clipPath id={clip}>
            <rect x="0" y={by} width={w} height={h - by} />
          </clipPath>
        )}
      </defs>
      {area && by != null && (
        <path d={area} fill="var(--state-critical-ink)" opacity="0.18" clipPath={`url(#${clip})`} />
      )}
      {by != null && (
        <line x1="0" x2={w} y1={by} y2={by} stroke="rgb(100 116 139)" strokeWidth="1" strokeDasharray="3 3"
              vectorEffect="non-scaling-stroke" />
      )}
      <path d={d} fill="none" stroke="var(--state-active-ink)" strokeWidth="1.6" vectorEffect="non-scaling-stroke"
            strokeLinejoin="round" />
    </svg>
  );
}
