import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Micro, PageHeader, Plane, Readout, Switch } from "../system/primitives";
import { EmptyState, ErrorState, SkeletonRows, useToast } from "../components/ui";
import { dateTimeOf } from "../system/time";

/**
 * Semantic cache.
 *
 * <p>Matching is by meaning, not by string: "summarise the refund policy" and
 * "summarize the refund policy for us" are the same question, and an exact-match
 * cache would miss both times. The threshold is therefore the whole product —
 * too low and the cache answers a question nobody asked, which the caller cannot
 * detect. It is exposed here, with its consequence stated, rather than hidden in
 * a config file.
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

  const savedPct = status && status.hits + status.misses > 0 ? Math.round(status.hitRate * 100) : 0;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Semantic Cache"
        subtitle="Answers a repeated question from a previous answer instead of paying a provider for it again. Scoped to your account and off by default."
      />

      <Plane className="p-5">
        <Switch
          label="Semantic cache"
          hint="Matches incoming prompts against your recent answers by meaning. A hit never crosses accounts or models."
          checked={status?.enabled ?? false}
          busy={busy || status === null}
          onChange={(next) => run(() => portal.cache.setEnabled(next), next ? "Cache enabled" : "Cache disabled")}
        />
      </Plane>

      <Plane className="grid gap-6 p-5 sm:grid-cols-2 lg:grid-cols-5">
        <Readout label="Hit rate" value={savedPct} unit="%" state={savedPct > 0 ? "healthy" : "idle"} />
        <Readout label="Hits" value={status?.hits ?? 0} />
        <Readout label="Misses" value={status?.misses ?? 0} />
        <Readout
          label="Tokens saved"
          value={status?.tokensSaved ?? 0}
          state={status?.tokensSaved ? "healthy" : "idle"}
        />
        <Readout
          label="Cost avoided"
          value={`$${(status?.costSaved ?? 0).toFixed(4)}`}
          hint="What the cached calls originally cost"
        />
      </Plane>

      <section className="space-y-3">
        <Micro>Match threshold</Micro>
        <Plane className="p-5">
          <p className="text-sm text-slate-400">
            How close an incoming prompt has to be before a stored answer is served. A false hit is
            worse than a miss, because the caller cannot tell it happened.
          </p>
          <div className="mt-4 grid gap-3 sm:grid-cols-3">
            {BANDS.map(([value, name, note]) => {
              const active = Math.abs((status?.similarityThreshold ?? 0.92) - value) < 0.005;
              return (
                <button
                  key={name}
                  disabled={busy}
                  onClick={() =>
                    run(() => portal.cache.configure({ similarityThreshold: value }), `Threshold set to ${name}`)
                  }
                  className={`rounded-lg border p-3 text-left transition-colors disabled:opacity-50 ${
                    active ? "border-aurora/60 bg-aurora/10" : "border-edge hover:border-aurora/40"
                  }`}
                >
                  <div className="flex items-baseline justify-between gap-2">
                    <span className="text-sm font-medium text-slate-200">{name}</span>
                    <span className="readout text-xs text-slate-500">{value.toFixed(2)}</span>
                  </div>
                  <p className="mt-1 text-xs text-slate-500">{note}</p>
                </button>
              );
            })}
          </div>

          <div className="mt-5 flex flex-wrap items-end gap-4 border-t border-edge/60 pt-4">
            <label className="min-w-0">
              <span className="micro">Entry lifetime</span>
              <select
                value={status?.ttlSeconds ?? 86400}
                disabled={busy}
                onChange={(e) =>
                  run(() => portal.cache.configure({ ttlSeconds: Number(e.target.value) }), "Lifetime updated")
                }
                className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
              >
                <option value={3600}>1 hour</option>
                <option value={86400}>1 day</option>
                <option value={604800}>7 days</option>
                <option value={2592000}>30 days</option>
              </select>
            </label>
            <div className="ml-auto flex items-center gap-3">
              <span className="text-xs text-slate-500">{status?.entries ?? 0} stored</span>
              <button
                disabled={busy || !status?.entries}
                onClick={() => run(() => portal.cache.clear(), "Cache cleared")}
                className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:bg-edge/50 disabled:opacity-40"
              >
                Clear cache
              </button>
            </div>
          </div>
        </Plane>
      </section>

      <section className="space-y-3">
        <Micro>Stored answers</Micro>
        {rows === null ? (
          <SkeletonRows rows={3} />
        ) : rows.length === 0 ? (
          <EmptyState
            title="Nothing cached yet"
            hint={
              status?.enabled
                ? "Send a request through the gateway; the answer is stored and the next equivalent question is served from here."
                : "Turn the cache on to start storing answers."
            }
          />
        ) : (
          <Plane className="overflow-x-auto">
            <table className="w-full min-w-[600px] text-sm">
              <thead>
                <tr className="border-b border-edge/70 text-left">
                  {["Prompt", "Model", "Hits", "Tokens", "Expires"].map((h) => (
                    <th key={h} className="px-4 py-2 micro font-medium">
                      {h}
                    </th>
                  ))}
                </tr>
              </thead>
              <tbody>
                {rows.map((r) => (
                  <tr key={r.id} className="border-b border-edge/40 last:border-0">
                    <td className="max-w-md truncate px-4 py-2 text-slate-300">{r.prompt}</td>
                    <td className="whitespace-nowrap px-4 py-2 text-slate-400">{r.model ?? "—"}</td>
                    <td className="px-4 py-2 readout text-slate-300">{r.hitCount}</td>
                    <td className="px-4 py-2 readout text-slate-500">{r.tokens}</td>
                    <td className="whitespace-nowrap px-4 py-2 text-slate-500">{dateTimeOf(r.expiresAt)}</td>
                  </tr>
                ))}
              </tbody>
            </table>
          </Plane>
        )}
      </section>
    </div>
  );
}
