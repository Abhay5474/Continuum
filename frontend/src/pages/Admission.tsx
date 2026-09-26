import { useCallback, useEffect, useState } from "react";
import { visibleInterval } from "../system/poll";
import { portal } from "../api";
import { Meter, PageHeader, Readout, Switch, Note } from "../system/primitives";
import { BarChart, BeforeAfter, ChartFrame, SeriesChart, StackedBar, foldTail } from "../system/charts";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { Card, CardHead, Explain, Empty } from "../system/hub";
import { Gauge } from "../system/viz";

/**
 * Congestion-controlled admission.
 *
 * <p>The page exists to show a number nobody configured. The concurrency limit
 * is inferred from latency — it moves while you watch it — and the point of
 * drawing it live is that a limit somebody typed would not move at all.
 */

type ProviderState = {
  provider: string;
  limit: number;
  rawLimit: number;
  minRttMs: number;
  lastRttMs: number;
  gradient: number;
  samples: number;
  drops: number;
  inFlight: number;
  peakInFlight: number;
  admitted: number;
  queued: number;
  shed: number;
  shedBy: Record<string, number>;
  limitHistory: number[];
};

type Status = {
  enabled: boolean;
  queueMs: number;
  providers: ProviderState[];
  sheddingPoints: Record<string, number>;
};

export default function Admission() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setStatus(await portal.admission.status());
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load admission control.");
    }
  }, []);

  useEffect(() => {
    void load();
    // The limit is a live quantity; a static reading of it would misrepresent
    // the whole feature.
    return visibleInterval(() => void load(), 1500);
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

  const providers = status?.providers ?? [];
  const totalShed = providers.reduce((n, p) => n + p.shed, 0);
  const totalAdmitted = providers.reduce((n, p) => n + p.admitted, 0);
  const totalQueued = providers.reduce((n, p) => n + p.queued, 0);
  const inFlight = providers.reduce((n, p) => n + p.inFlight, 0);
  const on = !!status?.enabled;
  // The shedding decision is taken on the busiest provider's utilisation.
  const utilisation = providers.reduce((m, p) => Math.max(m, p.limit > 0 ? p.inFlight / p.limit : 0), 0);

  return (
    <section className="space-y-8">
      <PageHeader
        glyph="gauge"
        tone="info"
        title="Admission Control"
        subtitle="Provider capacity, measured from latency"
      />

      <div className="plane grid grid-cols-2 gap-x-8 gap-y-5 p-4 sm:grid-cols-3 lg:grid-cols-4">
        <Readout label="In flight" value={inFlight} size="sm" state={inFlight > 0 ? "active" : "idle"} />
        <Readout label="Admitted" value={totalAdmitted} size="sm" />
        <Readout
          label="Queued briefly"
          value={totalQueued}
          size="sm"
          hint="Waited for a slot rather than being refused."
        />
        <Readout
          label="Shed"
          value={totalShed}
          size="sm"
          state={totalShed > 0 ? "degraded" : "idle"}
          hint="Refused deliberately, lowest importance first."
        />
      </div>

      <div className="space-y-3">
        <Switch
          checked={!!status?.enabled}
          busy={busy}
          onChange={(next) =>
            act(
              () => portal.admission.configure({ enabled: next }),
              next ? "Admission control is on." : "Admission control is off."
            )
          }
          label="Congestion-controlled admission"
          hint="Off by default. While it is off every request goes straight to the provider and overload is the provider's problem — which it solves with 429s."
        />
        <Note>
          A request waits up to {status?.queueMs ?? 250}ms for a slot, then gets{" "}
          <span className="readout">429</span> with a <span className="readout">Retry-After</span>.
        </Note>
        <Explain>
          <p>
            A fast honest refusal is worth more than a slow one: the caller can retry, degrade, or
            tell its user, none of which it can do while blocked.
          </p>
        </Explain>
      </div>

      <div>
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">How importance decides who is refused first</h2>
        <Card className="mt-3">
          <ShedScale points={status?.sheddingPoints ?? {}} utilisation={utilisation} on={on} />
        </Card>
        <Note className="mt-2">
          Set <span className="readout">criticality</span> on the request. Anything unrecognised
          reads as <span className="readout">NORMAL</span>, never as background.
        </Note>
        <Explain>
          <p>
            Past capacity something is refused; the only question is whether it is chosen or random.
            Background work goes first, and interactive requests may overshoot — the limit is an
            estimate, and being wrong about a waiting user costs more than one queued call.
          </p>
        </Explain>
      </div>

      {status === null ? (
        <SkeletonRows rows={2} />
      ) : providers.length === 0 ? (
        <Empty title={"No provider has been observed yet"} hint={"Send traffic to see each provider's inferred limit."} />
      ) : (
        <div className="space-y-2">
          {providers.map((p) => (
            <ProviderCard key={p.provider} p={p} />
          ))}
        </div>
      )}

      {providers.length > 0 && (
        <button
          disabled={busy}
          onClick={() => act(() => portal.admission.reset(), "Learned limits cleared.")}
          className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:border-aurora/50 disabled:opacity-40"
        >
          Forget learned limits
        </button>
      )}
    </section>
  );
}

function ProviderCard({ p }: { p: ProviderState }) {
  // Utilisation against the inferred limit — the number the shedding decision
  // is actually made on.
  const utilisation = p.limit > 0 ? p.inFlight / p.limit : 0;
  const congested = p.gradient < 0.8;

  const outcomes = {
    total: p.admitted + p.queued + p.shed,
    data: [
      { key: "admitted", label: "Admitted", value: p.admitted, color: "var(--series-3)" },
      { key: "queued", label: "Queued", value: p.queued, color: "var(--series-4)" },
      { key: "shed", label: "Shed", value: p.shed, color: "var(--series-8)" },
    ],
  };

  const shedReasons = foldTail(
    Object.entries(p.shedBy ?? {})
      .filter(([, v]) => v > 0)
      .map(([k, v]) => ({ key: k, label: k, value: v }))
  );

  return (
    <Card className="space-y-4">
      <CardHead glyph="model" tone="blue" title={p.provider}
                sub={`${p.samples} samples${p.drops > 0 ? ` · ${p.drops} backoffs` : ""}`} />

      <div className="grid items-center gap-6 sm:grid-cols-[auto_minmax(0,1fr)]">
        {/* In flight against the limit it was measured to have: the one
            reading the refusal decision is taken on. */}
        <Gauge
          value={p.inFlight}
          max={Math.max(1, p.limit)}
          display={`${p.inFlight}/${p.limit}`}
          label="In flight / limit"
          sub={`peak ${p.peakInFlight}`}
        />
        {/* Congestion is latency rising above its best — drawn as two lengths,
            because that ratio is what brings the limit down. */}
        <div className="min-w-0">
          <div className="micro mb-2">Latency against its best</div>
          <BeforeAfter
            beforeLabel="Best"
            afterLabel="Latest"
            before={Math.round(p.minRttMs)}
            after={Math.round(p.lastRttMs)}
            unit="ms"
            goodDirection="down"
          />
        </div>
      </div>

      <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
        <Readout
          label="Inferred limit"
          value={p.limit}
          size="sm"
          state="active"
          hint="Measured from latency, not configured."
        />
        <Readout label="In flight" value={p.inFlight} size="sm" />
        <Readout
          label="Best latency"
          value={Math.round(p.minRttMs)}
          unit="ms"
          size="sm"
          hint="The baseline congestion is judged against."
        />
        <Readout
          label="Latest"
          value={Math.round(p.lastRttMs)}
          unit="ms"
          size="sm"
          state={congested ? "degraded" : "idle"}
        />
      </div>

      <div>
        <Meter
          value={Math.min(1, utilisation)}
          state={utilisation > 0.9 ? "critical" : utilisation > 0.7 ? "degraded" : "active"}
          label={`Utilisation of the inferred limit`}
          height={6}
        />
      </div>

      {/* The limit is the whole feature, and it is a shape over time: an AIMD
          sawtooth reads as "it is probing and backing off", which a single
          current number cannot say. Hover gives the reading. */}
      {p.limitHistory.length > 1 && (
        <ChartFrame
          title="Inferred limit over time"
          valueLabel="Limit"
          data={p.limitHistory.map((v, i) => ({
            key: String(i),
            label: `reading ${i + 1}`,
            value: v,
          }))}
        >
          <SeriesChart
            height={90}
            unit="concurrent"
            format={(n) => n.toFixed(0)}
            series={[
              {
                key: "limit",
                label: "Inferred limit",
                points: p.limitHistory,
                color: congested ? "var(--state-degraded-ink)" : "var(--series-1)",
              },
            ]}
          />
        </ChartFrame>
      )}

      {/* Part-to-whole, because the question is what share of traffic this
          provider actually served. Status colours, not identity ones: admitted
          is good and shed is not, and that meaning is the point. */}
      {outcomes.total > 0 && (
        <ChartFrame
          title="What happened to requests"
          valueLabel="Requests"
          data={outcomes.data}
        >
          <StackedBar data={outcomes.data} unit="reqs" />
        </ChartFrame>
      )}

      {shedReasons.length > 0 && (
        <ChartFrame
          title="Why requests were shed"
          valueLabel="Requests"
          caption="Shedding is deliberate refusal under load, so the reason is the actionable part."
          data={shedReasons}
        >
          <BarChart data={shedReasons} unit="reqs" />
        </ChartFrame>
      )}

      <div className="flex flex-wrap items-center gap-x-4 gap-y-1 text-xs text-slate-500">
        <span>
          gradient <span className="readout text-slate-300">{p.gradient.toFixed(2)}</span>
        </span>
        <span>peak in flight {p.peakInFlight}</span>
      </div>

      <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
        {congested
          ? `Latest latency is ${(p.lastRttMs / Math.max(1, p.minRttMs)).toFixed(1)}× the best seen, so the limit is coming down — before this provider starts refusing anything.`
          : "Latency is close to the best seen, so there is no queue building at the provider and the limit can grow when the traffic justifies it."}
      </p>
    </Card>
  );
}

/**
 * Where each importance class starts being refused, on one utilisation axis.
 *
 * <p>Three separate meters said three numbers; one axis says the order. The
 * needle is the busiest provider's load right now, so "are we refusing
 * anything, and whose" is read off where the needle sits among the marks.
 */
function ShedScale({
  points,
  utilisation,
  on,
}: {
  points: Record<string, number>;
  utilisation: number;
  on: boolean;
}) {
  const marks = Object.entries(points).sort((a, b) => a[1] - b[1]);
  const top = Math.max(1.4, ...marks.map(([, v]) => v + 0.1));
  const x = (v: number) => `${(Math.min(v, top) / top) * 100}%`;
  const tones = ["var(--state-warning-ink)", "var(--state-degraded-ink)", "var(--state-critical-ink)"];
  const refusing = marks.filter(([, v]) => utilisation >= v).map(([k]) => k.toLowerCase());
  return (
    <div>
      <div className="flex items-baseline justify-between gap-3">
        <span className="micro">Load on the busiest provider</span>
        <span className="readout text-[12px]" style={{ color: refusing.length ? "var(--state-degraded-ink)" : "var(--state-healthy-ink)" }}>
          {Math.round(utilisation * 100)}%{" "}
          <span className="text-slate-500">
            {!on ? "· admission off" : refusing.length ? `· refusing ${refusing.join(", ")}` : "· everything served"}
          </span>
        </span>
      </div>
      <div className="relative mt-9 h-3 rounded-full" style={{ background: "rgb(var(--card-rule))" }}>
        {/* the zones: served, then each class refused in turn */}
        {marks.map(([k, v], i) => (
          <span
            key={k}
            className="absolute inset-y-0 rounded-r-full"
            style={{
              left: x(v),
              right: 0,
              background: tones[Math.min(i, tones.length - 1)],
              opacity: 0.22,
            }}
            aria-hidden
          />
        ))}
        <span className="absolute inset-y-0 left-0 rounded-l-full" style={{ width: x(marks[0]?.[1] ?? top), background: "var(--state-healthy-ink)", opacity: 0.22 }} aria-hidden />
        {marks.map(([k, v], i) => (
          <span key={`m-${k}`} className="absolute -top-8 flex -translate-x-1/2 flex-col items-center" style={{ left: x(v) }}>
            <span className="whitespace-nowrap text-[10px] font-semibold uppercase tracking-[0.08em]" style={{ color: tones[Math.min(i, tones.length - 1)] }}>
              {k.toLowerCase()}
            </span>
            <span className="readout text-[10px] text-slate-500">{Math.round(v * 100)}%</span>
            <span className="mt-0.5 h-[22px] w-px" style={{ background: tones[Math.min(i, tones.length - 1)] }} aria-hidden />
          </span>
        ))}
        {/* the needle */}
        <span
          className="absolute top-1/2 h-5 w-5 -translate-x-1/2 -translate-y-1/2 rounded-full border-[3px] transition-[left] duration-500 ease-out"
          style={{ left: x(utilisation), borderColor: "rgb(var(--card))", background: "var(--accent)", boxShadow: "0 0 0 1px var(--accent-edge), 0 2px 6px rgba(0,0,0,.2)" }}
          aria-label={`Current load ${Math.round(utilisation * 100)}%`}
          role="img"
        />
      </div>
      <div className="relative mt-2 h-4 text-[10px] text-slate-500">
        <span className="absolute left-0">idle</span>
        <span className="absolute -translate-x-1/2 whitespace-nowrap" style={{ left: x(1) }}>limit</span>
        <span className="absolute right-0">{Math.round(top * 100)}%</span>
      </div>
    </div>
  );
}
