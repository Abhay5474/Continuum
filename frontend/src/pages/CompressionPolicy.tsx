import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Meter, Micro, PageHeader, Plane, Readout, Switch } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";

/**
 * Adaptive compression policy.
 *
 * <p>The page is built around <b>target versus achieved</b>, per region. That
 * pairing is the whole feature: a target alone says what was asked for, and
 * without the achieved figure beside it there is no way to see that protected
 * spans stopped the budget being reached — which is the compressor correctly
 * refusing to drop content it was told to keep.
 */

type Region = {
  region: string;
  label: string;
  target: number;
  messages: number;
  tokensIn: number;
  tokensOut: number;
  achieved: number | null;
};

type Status = {
  enabled: boolean;
  compressionEnabled: boolean;
  defaultRatio: number;
  minTokens: number;
  shortPromptTokens: number;
  regions: Region[];
  skipped: { reason: string; count: number }[];
};

export default function CompressionPolicy() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setStatus(await portal.compressionPolicy.status());
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load the compression policy.");
    }
  }, []);

  useEffect(() => {
    void load();
    const t = setInterval(() => void load(), 2500);
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

  const regions = status?.regions ?? [];
  const totalIn = regions.reduce((n, r) => n + r.tokensIn, 0);
  const totalOut = regions.reduce((n, r) => n + r.tokensOut, 0);
  const saved = totalIn - totalOut;
  const skips = (status?.skipped ?? []).reduce((n, s) => n + s.count, 0);

  return (
    <section className="space-y-5">
      <PageHeader
        title="Compression Budget"
        subtitle="One ratio for the whole prompt is the wrong shape — instructions, examples and the question do not carry information at the same density."
      />

      <Plane className="grid grid-cols-2 gap-4 p-4 sm:grid-cols-4">
        <Readout label="Tokens in" value={totalIn} size="sm" />
        <Readout
          label="Tokens sent"
          value={totalOut}
          size="sm"
          state={totalOut > 0 ? "active" : "idle"}
        />
        <Readout
          label="Tokens saved"
          value={saved}
          size="sm"
          state={saved > 0 ? "healthy" : "idle"}
        />
        <Readout
          label="Prompts skipped"
          value={skips}
          size="sm"
          hint="Too short to be worth the fidelity cost."
        />
      </Plane>

      <Plane className="space-y-3 p-4">
        <Switch
          checked={!!status?.enabled}
          busy={busy}
          onChange={(next) =>
            act(
              () => portal.compressionPolicy.configure({ enabled: next }),
              next ? "Budget controller is on." : "Budget controller is off."
            )
          }
          label="Per-region compression budget"
          hint="Off by default. While it is off, every message the compressor touches gets the same ratio."
        />
        {!status?.compressionEnabled && (
          <p className="text-xs text-amber-400/90">
            Prompt compression itself is off, so nothing is being compressed and this budget has
            nothing to allocate. Turn it on under Prompt Guard.
          </p>
        )}
        <p className="text-xs text-slate-600">
          When off, every touched message is compressed to{" "}
          <span className="readout">{status?.defaultRatio ?? 0.55}</span>. When on, each region gets
          its own budget, and a prompt under{" "}
          <span className="readout">{status?.shortPromptTokens ?? 400}</span> tokens is left alone
          entirely — below that there is little to remove and the fidelity cost outweighs the saving.
        </p>
      </Plane>

      {status === null ? (
        <SkeletonRows rows={3} />
      ) : (
        <Plane className="space-y-4 p-4">
          <Micro>Budget per region — target against achieved</Micro>
          {regions.map((r) => {
            const achieved = r.achieved;
            // Above target means less was removed than allowed — usually
            // protected spans. Below target would mean over-compression.
            const over = achieved !== null && achieved < r.target - 0.02;
            return (
              <div key={r.region} className="space-y-1">
                <div className="flex flex-wrap items-baseline gap-x-3 gap-y-0.5">
                  <span className="text-sm text-slate-200">{r.label}</span>
                  <span className="micro">keep {Math.round(r.target * 100)}% target</span>
                  {achieved !== null && (
                    <span className={`micro ${over ? "text-amber-400" : "text-slate-400"}`}>
                      kept {Math.round(achieved * 100)}% achieved
                    </span>
                  )}
                  <span className="flex-1" />
                  <span className="micro">
                    {r.tokensIn > 0 ? `${r.tokensIn} → ${r.tokensOut} tokens` : "not seen yet"}
                  </span>
                </div>
                <Meter
                  value={achieved ?? r.target}
                  state={
                    r.region === "QUESTION" ? "healthy" : over ? "degraded" : "active"
                  }
                  height={6}
                />
              </div>
            );
          })}
          <p className="text-xs text-slate-600">
            A region sitting well <em>above</em> its target is one where protected spans dominate —
            numbers, identifiers, quoted text and code are never dropped, so a demonstration block
            full of clause numbers cannot reach an aggressive budget. That is the compressor
            refusing to remove content it was told to keep, and it is the correct outcome. A region
            below its target would be the real fault: compressing harder than asked.
          </p>
        </Plane>
      )}

      {(status?.skipped ?? []).length > 0 && (
        <Plane className="space-y-2 p-4">
          <Micro>Prompts the budget declined to compress</Micro>
          {status!.skipped.map((s, i) => (
            <div key={i} className="flex items-baseline gap-3">
              <span className="readout shrink-0 text-xs text-slate-400">×{s.count}</span>
              <span className="text-xs text-slate-500">{s.reason}</span>
            </div>
          ))}
        </Plane>
      )}

      <Plane className="p-4">
        <Micro>Where the numbers come from</Micro>
        <p className="mt-1.5 text-xs text-slate-600">
          LLMLingua (Jiang et al., EMNLP 2023) measures that instructions tolerate losing 10–20%,
          demonstrations 60–80%, and the question 0–10%. Examples are largely redundant with each
          other — that is what makes them examples — while an instruction is a list of requirements
          where every clause matters.
        </p>
        <p className="mt-2 text-xs text-slate-600">
          Region detection is a heuristic, and the two mistakes are not equally costly: calling an
          instruction a demonstration throws away most of it and silently changes what the model was
          asked to do. So a message is only classed as examples on strong evidence — two or more
          marker lines — and anything unrecognised falls back to the ratio used before this existed.
          <b className="text-slate-500"> Unsure means gentler, never harsher.</b>
        </p>
        <p className="mt-2 text-xs text-slate-600">
          The per-region tallies above are held in memory and reset when the service restarts. The
          cumulative token savings are stored durably and appear under Prompt Guard.
        </p>
      </Plane>

      {totalIn > 0 && (
        <button
          disabled={busy}
          onClick={() => act(() => portal.compressionPolicy.reset(), "Tallies cleared.")}
          className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:border-aurora/50 disabled:opacity-40"
        >
          Clear tallies
        </button>
      )}
    </section>
  );
}
