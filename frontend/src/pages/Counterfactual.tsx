import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Meter, PageHeader, Readout, Switch, Note } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { Card, CardHead, Explain, Empty } from "../system/hub";
import { BeforeAfter, seriesColor } from "../system/charts";

/** A modelled share is drawn hatched: the same colour, visibly not solid. */
const HATCH = "repeating-linear-gradient(135deg, transparent 0 3px, rgb(255 255 255 / .55) 3px 6px)";
import { Select } from "../system/controls";

/**
 * Counterfactual replay.
 *
 * <p>The number this page refuses to show is a single blended cost. It shows the
 * measured share and the modelled share separately, because the person about to
 * change their routing needs to know how much of the answer is measurement and
 * how much is a guess — and a blended figure hides exactly that.
 */

type Line = {
  arm: string;
  confident: boolean;
  requests: number;
  measuredRequests: number;
  cost: number;
  basis: string;
};

type Report = {
  policy: string;
  requests: number;
  actualCost: number;
  estimatedCost: number;
  delta: number;
  deltaPct: number | null;
  measuredCandidateCost: number;
  modelledCandidateCost: number;
  agreedFraction: number;
  unmodellable: number;
  lines: Line[];
  verdict: string;
  caveat: string;
  enabled?: boolean;
  message?: string;
};

type Status = {
  enabled: boolean;
  requestsAvailable: number;
  maxSample: number;
  actualSpend: number;
  arms: { arm: string; requests: number }[];
};

const usd = (n: number) => `$${n.toFixed(6)}`;

export default function Counterfactual() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [mode, setMode] = useState<"always" | "threshold">("always");
  const [arm, setArm] = useState("");
  const [at, setAt] = useState(0.5);
  const [below, setBelow] = useState("");
  const [above, setAbove] = useState("");
  const [report, setReport] = useState<Report | null>(null);

  const load = useCallback(async () => {
    try {
      const s: Status = await portal.counterfactual.status();
      setStatus(s);
      const names = s.arms.map((a) => a.arm);
      setArm((v) => v || names[0] || "");
      setBelow((v) => v || names[0] || "");
      setAbove((v) => v || names[names.length - 1] || "");
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load counterfactual replay.");
    }
  }, []);

  useEffect(() => {
    void load();
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

  const run = async () => {
    setBusy(true);
    try {
      const r: Report = await portal.counterfactual.evaluate(
        mode === "always" ? { alwaysArm: arm } : { at, below, above }
      );
      setReport(r);
      if (r.enabled === false) toast(r.message ?? "Evaluation is off.", "error");
    } catch (e: any) {
      toast(e?.message ?? "Could not replay that policy.", "error");
    } finally {
      setBusy(false);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  const armNames = (status?.arms ?? []).map((a) => a.arm);

  return (
    <section className="space-y-8">
      <PageHeader
        glyph="spark"
        tone="accent"
        title="Counterfactual Replay"
        subtitle="Replay past traffic under another routing policy"
      />

      <div className="plane grid grid-cols-2 gap-x-8 gap-y-5 p-4 sm:grid-cols-3 lg:grid-cols-4">
        <Readout label="Requests replayable" value={status?.requestsAvailable ?? 0} size="sm" />
        <Readout
          label="Actual spend"
          value={usd(status?.actualSpend ?? 0)}
          size="sm"
          hint="Over the requests available to replay."
        />
        <Readout label="Distinct models" value={armNames.length} size="sm" />
      </div>

      <div className="space-y-3">
        <Switch
          checked={!!status?.enabled}
          busy={busy}
          onChange={(next) =>
            act(
              () => portal.counterfactual.configure({ enabled: next }),
              next ? "Counterfactual replay is on." : "Counterfactual replay is off."
            )
          }
          label="Counterfactual evaluation"
          hint="Off by default. It reads the request log and changes nothing on the request path — no traffic is re-sent to any provider."
        />
        <Note>
          This is a batch evaluator, deliberately. Replaying one request against a different model
          and showing both answers is a demo; replaying every logged request against a candidate
          policy and reporting the cost delta is how you tune a routing threshold without
          experimenting on live traffic.
        </Note>
      </div>

      {status === null ? (
        <SkeletonRows rows={2} />
      ) : armNames.length === 0 ? (
        <Empty title={"No routed traffic logged yet"} hint={"Send traffic to create policies to replay against."} />
      ) : (
        <div className="space-y-3">
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Candidate policy</h2>

          <div className="flex flex-wrap gap-2">
            {(["always", "threshold"] as const).map((m) => (
              <button
                key={m}
                onClick={() => setMode(m)}
                className={`rounded-md border px-3 py-1.5 text-xs transition-colors ${
                  mode === m
                    ? "border-aurora/60 bg-aurora/10 text-slate-100"
                    : "border-edge text-slate-400 hover:border-aurora/40"
                }`}
              >
                {m === "always" ? "Send everything to one model" : "Split on complexity"}
              </button>
            ))}
          </div>

          {mode === "always" ? (
            <label className="block text-xs text-slate-500">
              <span className="micro block">model</span>
              <Select
                value={arm}
                onChange={(e) => setArm(e.target.value)}
              >
                {armNames.map((a) => (
                  <option key={a}>{a}</option>
                ))}
              </Select>
            </label>
          ) : (
            <div className="space-y-4">
            {/* The policy being defined, drawn on the axis it splits. */}
            <div className="max-w-xl" role="img" aria-label={`Complexity below ${at} goes to ${below}, otherwise ${above}`}>
              <div className="relative flex h-8 overflow-hidden rounded-lg text-[11px] font-medium">
                <span className="flex items-center justify-center truncate px-2" style={{ width: `${Math.max(0, Math.min(1, at)) * 100}%`, background: "color-mix(in srgb, var(--series-1) 20%, transparent)", color: "var(--series-1)" }}>
                  {below}
                </span>
                <span className="flex flex-1 items-center justify-center truncate px-2" style={{ background: "color-mix(in srgb, var(--series-2) 20%, transparent)", color: "var(--series-2)" }}>
                  {above}
                </span>
                <span className="absolute inset-y-0 w-0.5" style={{ left: `${Math.max(0, Math.min(1, at)) * 100}%`, background: "var(--accent)" }} />
              </div>
              <div className="relative mt-1 h-4 text-[10px] text-slate-500">
                <span className="absolute left-0">simple · 0</span>
                <span className="readout absolute -translate-x-1/2" style={{ left: `${Math.max(0, Math.min(1, at)) * 100}%`, color: "var(--accent-ink)" }}>{at.toFixed(2)}</span>
                <span className="absolute right-0">1 · complex</span>
              </div>
            </div>
            <div className="flex flex-wrap items-end gap-3">
              <label className="text-xs text-slate-500">
                <span className="micro block">complexity below</span>
                <input
                  type="number"
                  min={0}
                  max={1}
                  step={0.05}
                  value={at}
                  onChange={(e) => setAt(Number(e.target.value))}
                  className="mt-1 w-20 field"
                />
              </label>
              <label className="text-xs text-slate-500">
                <span className="micro block">use</span>
                <Select
                  value={below}
                  onChange={(e) => setBelow(e.target.value)}
                >
                  {armNames.map((a) => (
                    <option key={a}>{a}</option>
                  ))}
                </Select>
              </label>
              <label className="text-xs text-slate-500">
                <span className="micro block">otherwise</span>
                <Select
                  value={above}
                  onChange={(e) => setAbove(e.target.value)}
                >
                  {armNames.map((a) => (
                    <option key={a}>{a}</option>
                  ))}
                </Select>
              </label>
            </div>
            </div>
          )}

          <button
            disabled={busy || !status.enabled}
            onClick={() => void run()}
            className="rounded-md border border-aurora/50 bg-aurora/10 px-3 py-1.5 text-sm text-slate-100 hover:bg-aurora/20 disabled:opacity-40"
          >
            Replay {status.requestsAvailable} requests
          </button>
          {!status.enabled && (
            <span className="ml-3 text-xs text-slate-500">Turn evaluation on to replay.</span>
          )}
        </div>
      )}

      {report && report.enabled !== false && (
        <>
          <div className="space-y-3">
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Result — {report.policy}</h2>
            <Card>
              <CardHead glyph="coin" tone={report.delta <= 0 ? "green" : "orange"} title="Spend, as run and as replayed"
                        sub={`${report.requests} requests`} />
              <div className="mt-3 max-w-xl">
                <BeforeAfter
                  beforeLabel="As run"
                  afterLabel="Candidate"
                  before={report.actualCost}
                  after={report.estimatedCost}
                  format={(n) => usd(n)}
                />
              </div>
            </Card>
            <div className="grid grid-cols-2 gap-4 sm:grid-cols-3">
              <Readout label="Actually spent" value={usd(report.actualCost)} size="sm" />
              <Readout
                label="Candidate would cost"
                value={usd(report.estimatedCost)}
                size="sm"
                state={report.delta < 0 ? "healthy" : report.delta > 0 ? "degraded" : "idle"}
              />
              <Readout
                label="Difference"
                value={`${report.delta >= 0 ? "+" : "−"}${usd(Math.abs(report.delta))}`}
                size="sm"
                state={report.delta < 0 ? "healthy" : report.delta > 0 ? "degraded" : "idle"}
              />
            </div>
            <p className="text-sm text-slate-300 max-w-2xl leading-relaxed">{report.verdict}</p>
          </div>

          <div className="space-y-3">
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">How much of that is measured</h2>
            <Meter
              value={report.agreedFraction}
              state={report.agreedFraction > 0.8 ? "healthy" : report.agreedFraction > 0.4 ? "active" : "degraded"}
              label="Requests where the candidate agrees with what actually ran"
              height={8}
            />
            {/* The candidate's cost, split into what is known and what is
                estimated — the one thing a blended figure would hide. */}
            {report.estimatedCost > 0 && (
              <div className="max-w-xl">
                <div className="flex h-4 overflow-hidden rounded-full" role="img"
                     aria-label={`Measured ${usd(report.measuredCandidateCost)}, modelled ${usd(report.modelledCandidateCost)}`}>
                  <span style={{ width: `${(report.measuredCandidateCost / (report.measuredCandidateCost + report.modelledCandidateCost || 1)) * 100}%`, background: "var(--state-healthy-ink)" }} />
                  <span className="flex-1" style={{ background: "var(--state-warning-ink)", backgroundImage: HATCH }} />
                </div>
                <div className="mt-1.5 flex flex-wrap gap-x-4 text-[10.5px] text-slate-500">
                  <span className="flex items-center gap-1.5"><span className="h-2 w-3 rounded-sm" style={{ background: "var(--state-healthy-ink)" }} />measured — it happened</span>
                  <span className="flex items-center gap-1.5"><span className="h-2 w-3 rounded-sm" style={{ background: "var(--state-warning-ink)", backgroundImage: HATCH }} />modelled — an estimate</span>
                </div>
              </div>
            )}
            <div className="grid grid-cols-2 gap-4">
              <Readout
                label="Measured"
                value={usd(report.measuredCandidateCost)}
                size="sm"
                state="healthy"
                hint="The candidate would have made the same choice, so this is what happened."
              />
              <Readout
                label="Modelled"
                value={usd(report.modelledCandidateCost)}
                size="sm"
                state="degraded"
                hint="Estimated from that model's own history. Not measured."
              />
            </div>
            <p className="text-xs text-slate-500 max-w-2xl leading-relaxed">{report.caveat}</p>
          </div>

          <div className="space-y-2">
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Where the candidate would have sent traffic</h2>
            {report.lines.map((l, i) => (
              <div key={l.arm} className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="w-40 truncate font-mono text-xs text-slate-300">{l.arm}</span>
                {/* Requests this model would take: solid where measured, hatched where modelled. */}
                <span className="flex h-3 min-w-[6rem] flex-1 overflow-hidden rounded-full" style={{ background: "rgb(var(--card-rule))", maxWidth: 360 }}
                      role="img" aria-label={`${l.measuredRequests} of ${l.requests} measured`}>
                  <span style={{ width: `${(l.requests / Math.max(1, report.requests)) * (l.measuredRequests / Math.max(1, l.requests)) * 100}%`, background: seriesColor(i) }} />
                  <span style={{ width: `${(l.requests / Math.max(1, report.requests)) * (1 - l.measuredRequests / Math.max(1, l.requests)) * 100}%`, background: seriesColor(i), backgroundImage: HATCH }} />
                </span>
                <span className="micro">{l.requests} requests</span>
                <span className="readout text-xs text-slate-400">{usd(l.cost)}</span>
                <span
                  className={`micro ${
                    l.confident
                      ? "text-emerald-400"
                      : l.measuredRequests === 0
                        ? "text-amber-400"
                        : "text-slate-400"
                  }`}
                >
                  {l.confident
                    ? "all measured"
                    : l.measuredRequests === 0
                      ? "all modelled"
                      : `${l.measuredRequests}/${l.requests} measured`}
                </span>
                <span className="w-full text-[11px] text-slate-600">{l.basis}</span>
              </div>
            ))}
          </div>
        </>
      )}

      <div>
      <Explain title="Why measured and modelled are kept apart">
          <p><b className="text-slate-500">Continuum's routing is deterministic</b>, so the logs contain
          no evidence about the arms it did not pick. Measured and modelled are never blended.</p>
          <p>
            Estimating how a policy you did <em>not</em> run would have performed, from logs of the
            one you <em>did</em>, is off-policy evaluation. The standard tool is the doubly robust
            estimator (Dudík, Langford &amp; Li, ICML 2011): a model of each arm's reward, corrected
            by how likely the logging policy was to take that action.
          </p>
          <p>
            That correction needs the logging policy to have had some chance of taking the other
            action. Here the chosen arm has probability 1 and every other arm has 0. There is no
            overlap, and no arithmetic recovers information the logs do not contain.
          </p>
          <p>So the measured share and the modelled share are reported separately and never blended. A
          single number would hide the one thing worth knowing before changing your routing: how
          much of it is a guess.</p>
      </Explain>
      </div>
    </section>
  );
}
