import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Meter, PageHeader, Readout, Switch } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { Explain } from "../system/hub";

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
        subtitle="What would last week's traffic have cost on a different routing policy — answered before you switch, not after."
      />

      <div className="flex flex-wrap gap-x-9 gap-y-4">
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
        <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
          This is a batch evaluator, deliberately. Replaying one request against a different model
          and showing both answers is a demo; replaying every logged request against a candidate
          policy and reporting the cost delta is how you tune a routing threshold without
          experimenting on live traffic.
        </p>
      </div>

      {status === null ? (
        <SkeletonRows rows={2} />
      ) : armNames.length === 0 ? (
        <div className="text-center text-sm text-slate-500">
          No routed traffic logged yet. Send requests through the gateway and the models they used
          become the candidate policies you can replay against.
        </div>
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
              <select
                value={arm}
                onChange={(e) => setArm(e.target.value)}
                className="mt-1 rounded border border-edge bg-ink/60 px-2 py-1 text-sm text-slate-200 outline-none focus:border-aurora/50"
              >
                {armNames.map((a) => (
                  <option key={a}>{a}</option>
                ))}
              </select>
            </label>
          ) : (
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
                  className="mt-1 w-20 rounded border border-edge bg-ink/60 px-2 py-1 text-sm text-slate-200 outline-none focus:border-aurora/50"
                />
              </label>
              <label className="text-xs text-slate-500">
                <span className="micro block">use</span>
                <select
                  value={below}
                  onChange={(e) => setBelow(e.target.value)}
                  className="mt-1 rounded border border-edge bg-ink/60 px-2 py-1 text-sm text-slate-200 outline-none focus:border-aurora/50"
                >
                  {armNames.map((a) => (
                    <option key={a}>{a}</option>
                  ))}
                </select>
              </label>
              <label className="text-xs text-slate-500">
                <span className="micro block">otherwise</span>
                <select
                  value={above}
                  onChange={(e) => setAbove(e.target.value)}
                  className="mt-1 rounded border border-edge bg-ink/60 px-2 py-1 text-sm text-slate-200 outline-none focus:border-aurora/50"
                >
                  {armNames.map((a) => (
                    <option key={a}>{a}</option>
                  ))}
                </select>
              </label>
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
                value={`${report.delta >= 0 ? "+" : ""}${usd(report.delta)}`}
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
            {report.lines.map((l) => (
              <div key={l.arm} className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
                <span className="font-mono text-xs text-slate-300">{l.arm}</span>
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
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Why the answer is split in two</h2>
        <p className="mt-1.5 max-w-2xl text-xs leading-relaxed text-slate-600">
          <b className="text-slate-500">Continuum's routing is deterministic</b>, so the logs contain
          no evidence about the arms it did not pick. Measured and modelled are never blended.
        </p>
        <Explain title="The estimator, and why it cannot be used here">
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
        </Explain>
        <p className="mt-2 text-xs text-slate-600 max-w-2xl leading-relaxed">
          So the measured share and the modelled share are reported separately and never blended. A
          single number would hide the one thing worth knowing before changing your routing: how
          much of it is a guess.
        </p>
      </div>
    </section>
  );
}
