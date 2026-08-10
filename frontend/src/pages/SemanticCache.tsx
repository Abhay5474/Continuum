import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { PageHeader, Switch } from "../system/primitives";
import { ChartFrame, Donut } from "../system/charts";
import { ErrorState, useToast } from "../components/ui";
import { dateTimeOf } from "../system/time";
import {
  Bar,
  Card,
  Empty,
  Facts,
  Ghost,
  Hop,
  KindMark,
  Rail,
  Route,
  Row,
  Pill,
  RowSkeleton,
  Stage,
  Stat,
  Stats,
} from "../system/hub";
import { Select } from "../system/controls";

/**
 * Semantic cache.
 *
 * <p>Matching is by meaning, not by string: "summarise the refund policy" and
 * "summarize the refund policy for us" are the same question, and an exact-match
 * cache would miss both times. The threshold is therefore the whole product —
 * too low and the cache answers a question nobody asked, which the caller cannot
 * detect. It is exposed here, with its consequence stated, rather than hidden in
 * a config file.
 *
 * <p><b>On the shape of this screen.</b> The threshold was three bordered cards
 * in a row, which said "pick one of three products" rather than "this is one
 * number on a scale and here is what moving it costs you". It is now a scale,
 * with the trade named at each end — because the reason to be on this page is
 * almost always to decide whether a hit you cannot see is worth the money.
 */

type Status = {
  enabled: boolean;
  similarityThreshold: number;
  ttlSeconds: number;
  hits: number;
  misses: number;
  hitRate: number;
  tokensSaved: number;
  costSaved: number;
  entries: number;
};

/** Named bands, because 0.92 means nothing without its consequence. */
const BANDS: [number, string, string][] = [
  [0.85, "Loose", "Catches more rewordings. Some answers will be near misses."],
  [0.92, "Balanced", "Serves clear rephrasings only. The recommended setting."],
  [0.98, "Strict", "Essentially identical prompts. Fewest hits, no surprises."],
];

/**
 * Money, without rounding a real saving down to nothing.
 *
 * <p>Four decimal places turned $0.0000132 into "$0.0000", which reads as "this
 * has saved you nothing" — the opposite of what the number says. Below the
 * displayable floor it says so, rather than lying with a zero.
 */
function money(n: number) {
  if (n > 0 && n < 0.0001) return "<$0.0001";
  return `$${n.toFixed(4)}`;
}

const TTLS: [number, string][] = [
  [3600, "1 hour"],
  [86400, "1 day"],
  [604800, "7 days"],
  [2592000, "30 days"],
];

export default function SemanticCache() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [rows, setRows] = useState<any[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [s, e] = await Promise.all([portal.cache.status(), portal.cache.entries(25)]);
      setStatus(s);
      setRows(e);
      setError(null);
    } catch (err: any) {
      setError(err?.message ?? "Could not load the cache.");
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

  const total = (status?.hits ?? 0) + (status?.misses ?? 0);
  const savedPct = total > 0 ? Math.round((status?.hitRate ?? 0) * 100) : 0;
  const on = status?.enabled ?? false;
  const threshold = status?.similarityThreshold ?? 0.92;

  // Colours here are status, not identity: a hit is good and a miss is neutral,
  // and that meaning is the point. A categorical slot would say "these are two
  // different things" when what matters is that one of them is the win.
  const cacheSplit = [
    { key: "hits", label: "Served from cache", value: status?.hits ?? 0, color: "var(--series-3)" },
    { key: "misses", label: "Went to a provider", value: status?.misses ?? 0, color: "var(--series-mute)" },
  ];

  const maxHits = Math.max(1, ...(rows ?? []).map((r) => r.hitCount ?? 0));

  return (
    <div className="page-enter">
      <PageHeader
        glyph="cache"
        tone={on ? "ok" : "mute"}
        title="Semantic Cache"
        badge={
          <Pill tone={on ? "ok" : "mute"} dot>
            {on ? "Live" : "Off"}
          </Pill>
        }
        subtitle="Answers a repeated question from a previous answer instead of paying a provider for it again. Scoped to your account, and off by default."
      />

      {/* Where the cache sits. It is the one stage on the path that can end a
          request early, and that is worth drawing rather than describing. */}
      <div className="mt-6">
        <Route>
          <Stage label="a prompt" sub="from your app" />
          <Hop />
          <Stage
            label="Semantic cache"
            sub={on ? (total > 0 ? `${savedPct}% answered here` : "warming up") : "off"}
            state={on ? "on" : "off"}
            mark={<KindMark kind="conversation" size={26} />}
            selected
          />
          <Hop label={total > 0 ? `${100 - savedPct}% carry on` : undefined} />
          <Stage label="the provider" sub="only for a genuine miss" />
        </Route>
      </div>

      <div className="mt-7" data-guide="cache-toggle">
        <Switch
          label="Semantic cache"
          hint="Matches incoming prompts against your recent answers by meaning. A hit never crosses accounts or models."
          checked={on}
          busy={busy || status === null}
          onChange={(next) => run(() => portal.cache.setEnabled(next), next ? "Cache enabled" : "Cache disabled")}
        />
      </div>

      <div className="mt-8 grid items-start gap-x-10 gap-y-8 lg:grid-cols-[auto_minmax(0,1fr)]">
        {/* The ring earns its place here because the hole holds the one number
            the page is about. Two segments, so it is a proportion at a glance
            rather than a comparison — for comparing close values this would be
            a bar. */}
        <div className="w-full max-w-sm">
          <ChartFrame
            title="Where requests went"
            data={cacheSplit}
            valueLabel="Requests"
            caption={total > 0 ? undefined : "Nothing has been asked yet — the ring fills in with traffic."}
          >
            <Donut data={cacheSplit} centerValue={`${savedPct}%`} centerLabel="hit rate" unit="reqs" />
          </ChartFrame>
        </div>

        <div data-guide="cache-stats">
        <Stats>
          <Stat
            label="Tokens saved"
            glyph="spark"
            value={status?.tokensSaved ?? 0}
            tone={status?.tokensSaved ? "ok" : undefined}
            hint="Tokens that were never sent to a provider because a stored answer matched."
          />
          <Stat
            label="Cost avoided"
            glyph="coin"
            value={money(status?.costSaved ?? 0)}
            hint="What the cached calls originally cost."
          />
          <Stat label="Entries held" glyph="layers" value={status?.entries ?? 0} />
          <Stat
            label="Answered from cache"
            glyph="check"
            value={status?.hits ?? 0}
            tone={status?.hits ? "ok" : undefined}
          />
        </Stats>
        </div>
      </div>

      <section className="mt-10">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Match threshold</h2>
        <p className="mt-1 max-w-2xl text-xs leading-relaxed text-slate-500">
          How close an incoming prompt has to be before a stored answer is served. A false hit is
          worse than a miss, because the caller cannot tell it happened.
        </p>

        {/* A scale, not three products. The three named points sit on it, so
            picking one is visibly picking a position between two costs rather
            than choosing between unrelated options. */}
        <Card className="mt-4" guide="cache-threshold">
        <div className="max-w-2xl">
          <div className="flex items-baseline justify-between text-[11px]">
            <span className="text-slate-500">more hits, some of them wrong</span>
            <span className="text-slate-500">fewer hits, none of them wrong</span>
          </div>
          <div className="relative mt-2 h-1 rounded-full" style={{ background: "rgb(var(--edge))" }}>
            <span
              className="absolute top-1/2 h-3 w-3 -translate-x-1/2 -translate-y-1/2 rounded-full transition-[left] duration-300 ease-out"
              style={{
                left: `${((threshold - 0.8) / 0.2) * 100}%`,
                background: "var(--accent)",
                boxShadow: "0 0 0 3px var(--accent-wash)",
              }}
              aria-hidden
            />
          </div>
          <div className="mt-4 flex flex-wrap gap-x-6 gap-y-3">
            {BANDS.map(([value, name, note]) => {
              const active = Math.abs(threshold - value) < 0.005;
              return (
                <button
                  key={name}
                  disabled={busy}
                  onClick={() =>
                    run(() => portal.cache.configure({ similarityThreshold: value }), `Threshold set to ${name}`)
                  }
                  className="min-w-0 flex-1 basis-48 text-left transition-opacity disabled:opacity-50"
                >
                  <div className="flex items-baseline gap-2">
                    <span
                      className="text-[13px] font-medium"
                      style={{ color: active ? "var(--accent-ink)" : "var(--text-2)" }}
                    >
                      {name}
                    </span>
                    <span className="readout text-[11px] text-slate-600">{value.toFixed(2)}</span>
                  </div>
                  <p className="mt-0.5 text-[11.5px] leading-relaxed text-slate-500">{note}</p>
                  <span
                    className="mt-1.5 block h-[2px] w-full rounded-full transition-opacity duration-200"
                    style={{ background: "var(--accent)", opacity: active ? 1 : 0 }}
                    aria-hidden
                  />
                </button>
              );
            })}
          </div>
        </div>

        <div className="mt-7 flex flex-wrap items-end gap-x-8 gap-y-4 border-t border-edge/60 pt-5" data-guide="cache-lifetime">
          <label className="min-w-0">
            <span className="micro">Entry lifetime</span>
            <Select
              value={status?.ttlSeconds ?? 86400}
              disabled={busy}
              onChange={(e) =>
                run(() => portal.cache.configure({ ttlSeconds: Number(e.target.value) }), "Lifetime updated")
              }
            >
              {TTLS.map(([v, label]) => (
                <option key={v} value={v}>
                  {label}
                </option>
              ))}
            </Select>
          </label>
          <div className="flex items-center gap-3">
            <span className="text-xs text-slate-500">{status?.entries ?? 0} stored</span>
            <Ghost
              disabled={busy || !status?.entries}
              onClick={() => run(() => portal.cache.clear(), "Cache cleared")}
            >
              Clear cache
            </Ghost>
          </div>
        </div>
        </Card>
      </section>

      <section className="mt-10">
        <h2 className="flex items-baseline gap-2 text-[13px] font-semibold tracking-tight text-slate-200">
          Stored answers
          {rows && <span className="readout text-[11px] font-normal text-slate-600">{rows.length}</span>}
        </h2>
        <div className="mt-3">
          {rows === null ? (
            <RowSkeleton rows={3} />
          ) : rows.length === 0 ? (
            <Empty
              title="Nothing cached yet"
              hint={
                on
                  ? "Send a request through the gateway; the answer is stored and the next equivalent question is served from here."
                  : "Turn the cache on to start storing answers."
              }
            />
          ) : (
            <Rail>
              {rows.map((r) => (
                <Row
                  key={r.id}
                  title={r.prompt}
                  subtitle={`${r.model ?? "unknown model"} · expires ${dateTimeOf(r.expiresAt)}`}
                  meta={
                    <Facts
                      items={[
                        { k: "tokens", v: r.tokens },
                        {
                          k: "served",
                          v: (
                            <span className="inline-flex items-center gap-2">
                              <Bar fraction={(r.hitCount ?? 0) / maxHits} width={48} />
                              <span className="readout">{r.hitCount}×</span>
                            </span>
                          ),
                          title: "How many times this stored answer has been reused",
                        },
                      ]}
                    />
                  }
                />
              ))}
            </Rail>
          )}
        </div>
      </section>
    </div>
  );
}
