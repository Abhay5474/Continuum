import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Meter, PageHeader, Plane, Readout, Switch } from "../system/primitives";
import { BarChart, ChartFrame, foldTail } from "../system/charts";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { Explain, Empty } from "../system/hub";

/**
 * Cost-aware admission.
 *
 * <p>The two bars per caller are the point of the page. A caller whose token bar
 * is full while their request bar is nearly empty is exactly the case a
 * request-counting limiter cannot see, and seeing it is what justifies the
 * feature.
 */

type Caller = {
  caller: string;
  admitted: number;
  refused: number;
  refusedByTokens: number;
  tokensCharged: number;
  outstanding: number;
  requestShare: number;
  tokenShare: number;
  peakTokenSharePct: number;
};

type Status = {
  enabled: boolean;
  requestsPerMin: number;
  tokensPerMin: number;
  assumedCompletionTokens: number;
  reservationTtlSeconds: number;
  callers: Caller[];
};

export default function CostAdmission() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [reqs, setReqs] = useState<number | null>(null);
  const [toks, setToks] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      const s: Status = await portal.costAdmission.status();
      setStatus(s);
      setReqs((v) => (v === null ? s.requestsPerMin : v));
      setToks((v) => (v === null ? s.tokensPerMin : v));
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load cost-aware admission.");
    }
  }, []);

  useEffect(() => {
    void load();
    // Buckets refill continuously, so the shares move while you watch.
    const t = setInterval(() => void load(), 1500);
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

  const callers = status?.callers ?? [];

  // Emphasis rather than eight hues: the story is "this one is consuming the
  // budget", and colouring every caller differently buries exactly that.
  const tokensByCaller = foldTail(
    callers.map((c) => ({
      key: c.caller,
      label: c.caller.length > 22 ? c.caller.slice(0, 21) + "…" : c.caller,
      value: c.tokensCharged,
      hint: `${c.caller} — ${c.admitted} admitted, ${c.refused} refused`,
    }))
  );
  const heaviest = tokensByCaller.length > 1
    ? tokensByCaller.reduce((a, b) => (b.value > a.value ? b : a)).key
    : undefined;
  const admitted = callers.reduce((n, c) => n + c.admitted, 0);
  const refused = callers.reduce((n, c) => n + c.refused, 0);
  const byTokens = callers.reduce((n, c) => n + c.refusedByTokens, 0);
  const charged = callers.reduce((n, c) => n + c.tokensCharged, 0);
  const outstanding = callers.reduce((n, c) => n + c.outstanding, 0);

  return (
    <section className="space-y-8">
      <PageHeader
        glyph="coin"
        tone="info"
        title="Cost-Aware Limits"
        subtitle="A fifty-step agent carrying twenty thousand tokens is not one request in the way that “hello” is one request."
      />

      <div className="plane grid grid-cols-2 gap-x-8 gap-y-5 p-4 sm:grid-cols-3 lg:grid-cols-4">
        <Readout label="Admitted" value={admitted} size="sm" />
        <Readout
          label="Refused"
          value={refused}
          size="sm"
          state={refused > 0 ? "degraded" : "idle"}
        />
        <Readout
          label="Refused on tokens"
          value={byTokens}
          size="sm"
          state={byTokens > 0 ? "critical" : "idle"}
          hint="Would have passed a request-count limit."
        />
        <Readout
          label="Tokens charged"
          value={charged}
          size="sm"
          hint="Settled against what the provider actually reported."
        />
        <Readout
          label="In flight"
          value={outstanding}
          size="sm"
          state={outstanding > 0 ? "active" : "idle"}
          hint="Reservations held right now. If this climbs and never falls, they are leaking."
        />
      </div>

      <div className="space-y-3">
        <Switch
          checked={!!status?.enabled}
          busy={busy}
          onChange={(next) =>
            act(
              () => portal.costAdmission.configure({ enabled: next }),
              next ? "Cost-aware limits are on." : "Cost-aware limits are off."
            )
          }
          label="Limit by consumption, not request count"
          hint="Off by default. Turning it on can refuse traffic a request-count limit would have admitted."
        />

        <div className="flex flex-wrap items-end gap-4">
          <label className="text-xs text-slate-500">
            <span className="micro block">requests / minute</span>
            <input
              type="number"
              min={1}
              value={reqs ?? ""}
              onChange={(e) => setReqs(Number(e.target.value))}
              className="mt-1 w-28 field"
            />
          </label>
          <label className="text-xs text-slate-500">
            <span className="micro block">tokens / minute</span>
            <input
              type="number"
              min={1}
              step={1000}
              value={toks ?? ""}
              onChange={(e) => setToks(Number(e.target.value))}
              className="mt-1 w-32 field"
            />
          </label>
          <button
            disabled={busy}
            onClick={() =>
              act(
                () =>
                  portal.costAdmission.configure({
                    requestsPerMin: reqs ?? undefined,
                    tokensPerMin: toks ?? undefined,
                  }),
                "Allowances saved."
              )
            }
            className="rounded-md border border-aurora/50 bg-aurora/10 px-3 py-1.5 text-sm text-slate-100 hover:bg-aurora/20 disabled:opacity-40"
          >
            Save allowances
          </button>
        </div>

        <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
          Both refill continuously, and a request needs room in both. Whichever a caller is nearest
          to exhausting is the one that limits them.
        </p>
        <Explain>
          <p>
            So someone making many tiny calls is bounded by request count and someone making one
            enormous call is bounded by tokens. Neither can starve the other by choosing a shape,
            and nobody can save up a minute's worth to spend in one burst.
          </p>
        </Explain>
      </div>

      <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Per caller</h2>

      {status === null ? (
        <SkeletonRows rows={2} />
      ) : callers.length === 0 ? (
        <Empty title={"No traffic yet"} hint={"Once requests arrive, each caller's consumption of both allowances appears here."} />
      ) : (
        <div className="space-y-4">
          {/* Across callers, before the per-caller detail. One caller usually
              dominates a shared allowance, and that is invisible when the page
              only shows each caller's own share of its own limit. One hue: the
              bar length already encodes the value, so colouring by it would
              spend the identity channel twice. */}
          <div>
            <ChartFrame
              title="Tokens charged, by caller"
              valueLabel="Tokens"
              caption="Who is actually consuming the shared token budget."
              data={tokensByCaller}
            >
              <BarChart data={tokensByCaller} emphasis={heaviest} unit="tok" />
            </ChartFrame>
          </div>

          {callers.map((c) => {
            const tokenBound = c.tokenShare >= c.requestShare;
            return (
              <Plane key={c.caller} className="space-y-3 p-4">
                <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
                  <span className="break-all font-mono text-xs text-slate-300">{c.caller}</span>
                  <span className="flex-1" />
                  <span className="micro">{c.admitted} admitted</span>
                  {c.refused > 0 && (
                    <span className="micro text-amber-400">{c.refused} refused</span>
                  )}
                  {c.outstanding > 0 && (
                    <span className="micro">{c.outstanding} in flight</span>
                  )}
                </div>

                <div className="space-y-2">
                  <Meter
                    value={Math.max(0, Math.min(1, c.requestShare))}
                    state={!tokenBound ? "degraded" : "active"}
                    label={`Request allowance (${status.requestsPerMin}/min)`}
                    height={6}
                  />
                  <Meter
                    value={Math.max(0, Math.min(1, c.tokenShare))}
                    state={tokenBound ? "degraded" : "active"}
                    label={`Token allowance (${status.tokensPerMin.toLocaleString()}/min)`}
                    height={6}
                  />
                </div>

                <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
                  {tokenBound
                    ? `Bounded by tokens — this caller is using ${Math.round(
                        c.tokenShare * 100
                      )}% of the token allowance against ${Math.round(
                        c.requestShare * 100
                      )}% of the request allowance. A request-count limit would not see this.`
                    : `Bounded by request count — many small calls rather than a few large ones.`}
                  {c.tokensCharged > 0 &&
                    ` ${c.tokensCharged.toLocaleString()} tokens charged so far, peak token use ${c.peakTokenSharePct}%.`}
                </p>
              </Plane>
            );
          })}
        </div>
      )}

      <div>
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Reserve, then settle</h2>
        <p className="mt-1.5 max-w-2xl text-xs leading-relaxed text-slate-600">
          Real token cost is unknown until the response returns, so admission reserves an estimate
          and settles the true figure afterwards.
        </p>
        <Explain>
          <p>
            The estimate is the prompt plus the caller's completion cap, or{" "}
            <span className="readout">{status?.assumedCompletionTokens ?? 800}</span> tokens when
            they did not set one. Deliberately generous: under-reserving discovers the cost too
            late, over-reserving only makes them wait, and the excess is returned in full.
          </p>
          <p>
            Reservations are returned in a finally block and any missed one expires after{" "}
            <span className="readout">{status?.reservationTtlSeconds ?? 300}s</span>. If the in-flight
            count climbs and never falls, they are leaking.
          </p>
          <p>
            Held in memory, per instance — three instances behind a load balancer allow three times
            the traffic.
          </p>
        </Explain>
      </div>

      {callers.length > 0 && (
        <button
          disabled={busy}
          onClick={() => act(() => portal.costAdmission.reset(), "Counters cleared.")}
          className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:border-aurora/50 disabled:opacity-40"
        >
          Clear counters
        </button>
      )}
    </section>
  );
}
