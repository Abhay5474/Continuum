import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Switch } from "../system/primitives";
import { ChartFrame, TargetVsActual } from "../system/charts";
import { ErrorState, useToast } from "../components/ui";
import { Explain, Chip } from "../system/hub";
import { Empty, Facts, Ghost, Hop, KindMark, Rail, Route, Row, RowSkeleton, Stage, Stat, Stats } from "../system/hub";

/**
 * Adaptive compression policy.
 *
 * <p>The page is built around <b>target versus achieved</b>, per region. That
 * pairing is the whole feature: a target alone says what was asked for, and
 * without the achieved figure beside it there is no way to see that protected
 * spans stopped the budget being reached — which is the compressor correctly
 * refusing to drop content it was told to keep.
 *
 * <p><b>On the shape of this screen.</b> This feature does nothing unless prompt
 * compression is on, and that lives on another page. The old layout stated the
 * dependency in a sentence of amber text halfway down, which is the easiest
 * thing on a screen to not read. It is now a stage on the path, greyed out when
 * it is off — you cannot look at this page without seeing that the thing
 * upstream of it is not running.
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
  const upstream = !!status?.compressionEnabled;
  const on = !!status?.enabled;

  return (
    <section className="page-enter">
      <header>
        <div className="flex items-center gap-2.5">
          <Chip glyph="gauge" tone="accent" size={34} />
          <h1 className="text-[22px] font-semibold tracking-tight text-slate-100">Compression Budget</h1>
        </div>
        <p className="mt-1 max-w-2xl text-[13px] leading-relaxed text-slate-500">
          One ratio for the whole prompt is the wrong shape — instructions, examples and the question
          do not carry information at the same density.
        </p>
      </header>

      {/* The dependency, drawn. This budget allocates work that the compressor
          upstream of it does; when that is off there is nothing to allocate,
          and a greyed stage says so before any number is read. */}
      <div className="mt-6">
        <Route>
          <Stage label="a prompt" sub="from your app" />
          <Hop />
          <Stage
            label="Compression"
            sub={upstream ? "on" : "off — nothing to allocate"}
            state={upstream ? "on" : "bad"}
            mark={<KindMark kind="extraction" size={26} />}
          />
          <Hop label="per region" />
          <Stage
            label="Budget controller"
            sub={on ? `${regions.length} regions` : `one flat ratio of ${status?.defaultRatio ?? 0.55}`}
            state={on ? "on" : "off"}
            mark={<KindMark kind="table" size={26} />}
            selected
          />
          <Hop label="billed" />
          <Stage label="the provider" sub="sees the trimmed prompt" />
        </Route>
        {!upstream && (
          <p className="mt-2 text-xs" style={{ color: "var(--state-warning-ink)" }}>
            Prompt compression itself is off, so this budget has nothing to allocate. Turn it on under
            Prompt Guard.
          </p>
        )}
      </div>

      <div className="mt-7">
        <Stats>
          <Stat label="Tokens in" value={totalIn} />
          <Stat label="Tokens sent" value={totalOut} tone={totalOut > 0 ? "accent" : undefined} />
          <Stat label="Tokens saved" value={saved} tone={saved > 0 ? "ok" : undefined} />
          <Stat
            label="Prompts skipped"
            value={skips}
            hint="Too short to be worth the fidelity cost."
          />
        </Stats>
      </div>

      <div className="mt-8">
        <Switch
          checked={on}
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
        <p className="mt-3 max-w-2xl text-xs leading-relaxed text-slate-600">
          When off, every touched message is compressed to{" "}
          <span className="readout text-slate-400">{status?.defaultRatio ?? 0.55}</span>. When on,
          each region gets its own budget, and a prompt under{" "}
          <span className="readout text-slate-400">{status?.shortPromptTokens ?? 400}</span> tokens is
          left alone entirely — below that there is little to remove and the fidelity cost outweighs
          the saving.
        </p>
      </div>

      <section className="mt-10">
        {status === null ? (
          <RowSkeleton rows={3} />
        ) : (
          <>
            {/* A dumbbell rather than a meter each. The reader's question is the
                gap between asked-for and achieved, and a bar showing only the
                achieved value makes them hold the target in their head. */}
            <div className="max-w-3xl">
            <ChartFrame
              title="Budget per region"
              valueLabel="Kept"
              data={regions.map((r) => ({
                key: r.region,
                label: r.label,
                value: r.achieved ?? r.target,
              }))}
            >
              <TargetVsActual
                format={(n) => `${Math.round(n * 100)}%`}
                rows={regions.map((r) => ({
                  key: r.region,
                  label: r.label,
                  target: r.target,
                  actual: r.achieved,
                  hint:
                    r.tokensIn > 0
                      ? `${r.tokensIn} → ${r.tokensOut} tokens across ${r.messages} messages`
                      : "not seen yet",
                }))}
              />
            </ChartFrame>
            </div>
            <p className="mt-3 max-w-2xl text-xs leading-relaxed text-slate-600">
              Above target is correct — protected spans stopped it. <em>Below</em> target is the
              real fault: compressing harder than asked.
            </p>
            <Explain>
              <p>
                Numbers, identifiers, quoted text and code are never dropped, so a demonstration
                block full of clause numbers cannot reach an aggressive budget. That is the
                compressor refusing to remove content it was told to keep.
              </p>
            </Explain>
          </>
        )}
      </section>

      <section className="mt-10">
        <h2 className="flex items-baseline gap-2 text-[13px] font-semibold tracking-tight text-slate-200">
          Declined to compress
          {skips > 0 && <span className="readout text-[11px] font-normal text-slate-600">{skips}</span>}
        </h2>
        <div className="mt-3">
          {(status?.skipped ?? []).length === 0 ? (
            <Empty
              title="Nothing has been declined"
              hint="Prompts short enough that trimming would cost more fidelity than it saves in tokens land here."
            />
          ) : (
            <Rail>
              {status!.skipped.map((s, i) => (
                <Row
                  key={i}
                  title={s.reason}
                  meta={<Facts items={[{ k: "prompts", v: s.count }]} />}
                />
              ))}
            </Rail>
          )}
        </div>
      </section>

      <section className="mt-10 max-w-2xl">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">
          Where the numbers come from
        </h2>
        <p className="mt-2 text-xs leading-relaxed text-slate-600 max-w-2xl">
          Budgets come from LLMLingua (Jiang et al., EMNLP 2023).{" "}
          <b className="text-slate-400">Unsure means gentler, never harsher.</b>
        </p>
        <Explain>
          <p>
            That paper measures that instructions tolerate losing 10–20%, demonstrations 60–80%, and
            the question 0–10%. Examples are largely redundant with each other — that is what makes
            them examples — while an instruction is a list of requirements where every clause
            matters.
          </p>
          <p>
            Region detection is a heuristic and the two mistakes are not equally costly: calling an
            instruction a demonstration throws away most of it. So a message is classed as examples
            only on strong evidence — two or more marker lines — and anything unrecognised falls
            back to the ratio used before this existed.
          </p>
          <p>
            Per-region tallies are held in memory and reset on restart. Cumulative savings are
            stored durably and appear under Prompt Guard.
          </p>
        </Explain>
      </section>

      {totalIn > 0 && (
        <div className="mt-8">
          <Ghost disabled={busy} onClick={() => act(() => portal.compressionPolicy.reset(), "Tallies cleared.")}>
            Clear tallies
          </Ghost>
        </div>
      )}
    </section>
  );
}
