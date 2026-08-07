import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Micro, PageHeader, Plane, Readout, Switch } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { Explain } from "../system/hub";

/**
 * Priority and deadline scheduling.
 *
 * <p>The live queue is usually empty — the wait it orders is bounded at a
 * quarter second, so by the time you look it has drained. That is why the
 * planner is on the page rather than beside it: the ordering rules are the part
 * worth checking, and a scheduler nobody has watched make a decision is one
 * nobody will turn on.
 */

type Decision = { id: string; rank: number; runnable: boolean; reason: string };

type ProviderQueue = {
  provider: string;
  waiting: number;
  peakWaiting: number;
  ordered: number;
  promoted: number;
  aged: number;
  missedDeadline: number;
  queue: Decision[];
};

type Status = {
  enabled: boolean;
  agingStepSeconds: number;
  providers: ProviderQueue[];
};

type Row = {
  id: string;
  priority: string;
  waitedSeconds: number;
  deadlineSeconds: number | null;
  estimateSeconds: number;
};

const DEFAULT_ROWS: Row[] = [
  { id: "nightly-backfill", priority: "BATCH", waitedSeconds: 300, deadlineSeconds: null, estimateSeconds: 4 },
  { id: "chat-reply", priority: "INTERACTIVE", waitedSeconds: 0, deadlineSeconds: 30, estimateSeconds: 4 },
  { id: "report-refresh", priority: "NORMAL", waitedSeconds: 5, deadlineSeconds: 600, estimateSeconds: 4 },
  { id: "expired-lookup", priority: "INTERACTIVE", waitedSeconds: 0, deadlineSeconds: 2, estimateSeconds: 9 },
];

export default function Scheduling() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [rows, setRows] = useState<Row[]>(DEFAULT_ROWS);
  const [planned, setPlanned] = useState<Decision[] | null>(null);

  const load = useCallback(async () => {
    try {
      setStatus(await portal.scheduling.status());
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load scheduling.");
    }
  }, []);

  useEffect(() => {
    void load();
    // The queue is a live quantity that drains in well under a second.
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

  const plan = async () => {
    setBusy(true);
    try {
      const out = await portal.scheduling.order({
        tasks: rows.map((r) => ({
          id: r.id,
          priority: r.priority,
          waitedSeconds: r.waitedSeconds,
          deadlineSeconds: r.deadlineSeconds ?? undefined,
          estimateSeconds: r.estimateSeconds,
        })),
      });
      setPlanned(out.decisions ?? []);
    } catch (e: any) {
      toast(e?.message ?? "Could not order those tasks.", "error");
    } finally {
      setBusy(false);
    }
  };

  const patch = (i: number, next: Partial<Row>) =>
    setRows((rs) => rs.map((r, j) => (i === j ? { ...r, ...next } : r)));

  if (error) return <ErrorState message={error} onRetry={load} />;

  const providers = status?.providers ?? [];
  const waiting = providers.reduce((n, p) => n + p.waiting, 0);
  const promoted = providers.reduce((n, p) => n + p.promoted, 0);
  const aged = providers.reduce((n, p) => n + p.aged, 0);
  const missed = providers.reduce((n, p) => n + p.missedDeadline, 0);

  return (
    <section className="space-y-8">
      <PageHeader
        glyph="clock"
        tone="info"
        title="Priority & Deadlines"
        subtitle="Admission control answers whether there is room. This answers who gets it."
      />

      <div className="plane grid grid-cols-2 gap-x-8 gap-y-5 p-4 sm:grid-cols-3 lg:grid-cols-4">
        <Readout
          label="Waiting now"
          value={waiting}
          size="sm"
          state={waiting > 0 ? "active" : "idle"}
        />
        <Readout
          label="Jumped the queue"
          value={promoted}
          size="sm"
          hint="Went ahead of a request that arrived earlier."
        />
        <Readout
          label="Lifted by waiting"
          value={aged}
          size="sm"
          hint="Aging moved a task up a band so it would not starve."
        />
        <Readout
          label="Refused on deadline"
          value={missed}
          size="sm"
          state={missed > 0 ? "degraded" : "idle"}
          hint="Could not have finished in time, so the slot went to something that could."
        />
      </div>

      <div className="space-y-3">
        <Switch
          checked={!!status?.enabled}
          busy={busy}
          onChange={(next) =>
            act(
              () => portal.scheduling.configure({ enabled: next }),
              next ? "Scheduling is on." : "Scheduling is off."
            )
          }
          label="Priority and deadline scheduling"
          hint="Off by default. While it is off a free slot goes to whichever waiting request happened to poll at the right moment."
        />
        <p className="max-w-2xl text-xs leading-relaxed text-slate-600">
          Set <span className="readout">criticality</span> to choose a band and{" "}
          <span className="readout">deadlineMs</span> to say how long the result stays useful.
        </p>
        <Explain>
          <p>
            Ordering only applies while requests are waiting for capacity, so it does nothing until
            admission control is on and a provider is near its inferred limit.
          </p>
          <p>
            A request that cannot meet its deadline even with an immediate start comes back{" "}
            <span className="readout">422</span> rather than running — spending a slot on a result
            nobody can use also delays the requests that could still make theirs.
          </p>
        </Explain>
        <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
          Waiting is aged into the band — every{" "}
          <span className="readout">{status?.agingStepSeconds ?? 120}s</span> queued lifts a task one
          band, up to two. Without that, &ldquo;low priority&rdquo; quietly means &ldquo;never&rdquo;
          under sustained load.
        </p>
      </div>

      <div className="space-y-3">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Order a queue without running it</h2>
        <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
          Nothing is executed. The same ordering the scheduler uses is applied to the tasks below,
          so the rules can be checked before they are trusted with real traffic.
        </p>

        <div className="overflow-x-auto rounded-xl border p-3 shadow-card" style={{ borderColor: "rgb(var(--card-edge))", background: "rgb(var(--card))" }}>
          <table className="w-full min-w-[520px] text-xs">
            <thead>
              <tr className="text-left">
                <th className="pb-1 pr-3 font-normal"><Micro>task</Micro></th>
                <th className="pb-1 pr-3 font-normal"><Micro>priority</Micro></th>
                <th className="pb-1 pr-3 font-normal"><Micro>waited (s)</Micro></th>
                <th className="pb-1 pr-3 font-normal"><Micro>deadline (s)</Micro></th>
                <th className="pb-1 font-normal"><Micro>takes (s)</Micro></th>
              </tr>
            </thead>
            <tbody>
              {rows.map((r, i) => (
                <tr key={r.id}>
                  <td className="py-1 pr-3 font-mono text-slate-300">{r.id}</td>
                  <td className="py-1 pr-3">
                    <select
                      value={r.priority}
                      onChange={(e) => patch(i, { priority: e.target.value })}
                      className="rounded border border-edge bg-ink/60 px-1.5 py-0.5 text-slate-300 outline-none focus:border-aurora/50"
                    >
                      <option>BATCH</option>
                      <option>NORMAL</option>
                      <option>INTERACTIVE</option>
                    </select>
                  </td>
                  <td className="py-1 pr-3">
                    <Num value={r.waitedSeconds} onChange={(v) => patch(i, { waitedSeconds: v ?? 0 })} />
                  </td>
                  <td className="py-1 pr-3">
                    <Num
                      value={r.deadlineSeconds}
                      placeholder="none"
                      onChange={(v) => patch(i, { deadlineSeconds: v })}
                    />
                  </td>
                  <td className="py-1">
                    <Num
                      value={r.estimateSeconds}
                      onChange={(v) => patch(i, { estimateSeconds: v ?? 0 })}
                    />
                  </td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>

        <button
          disabled={busy}
          onClick={() => void plan()}
          className="rounded-md border border-aurora/50 bg-aurora/10 px-3 py-1.5 text-sm text-slate-100 hover:bg-aurora/20 disabled:opacity-40"
        >
          Order them
        </button>

        {planned && (
          <ol className="space-y-1.5">
            {planned.map((d) => (
              <li
                key={d.id}
                className={`flex flex-wrap items-baseline gap-x-3 gap-y-0.5 rounded-md border p-2 ${
                  d.runnable ? "border-edge" : "border-rose-500/40 bg-rose-500/5"
                }`}
              >
                <span className="readout w-6 shrink-0 text-xs text-slate-500">
                  {d.runnable ? `#${d.rank + 1}` : "—"}
                </span>
                <span className="font-mono text-xs text-slate-200">{d.id}</span>
                <span
                  className={`text-[11px] ${d.runnable ? "text-slate-500" : "text-rose-400"}`}
                >
                  {d.reason}
                </span>
              </li>
            ))}
          </ol>
        )}
      </div>

      {status === null ? (
        <SkeletonRows rows={2} />
      ) : providers.length === 0 ? (
        <div className="rounded-xl border border-dashed px-3 py-10 text-center text-sm text-slate-500" style={{ borderColor: "rgb(var(--card-edge))" }}>
          Nothing has queued yet. A request only enters the queue when a provider is at its inferred
          limit, and the wait is bounded at a quarter second — so this stays empty until you are
          genuinely near capacity.
        </div>
      ) : (
        <div className="space-y-2">
          {providers.map((p) => (
            <Plane key={p.provider} className="space-y-2 p-3">
              <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
                <span className="text-sm font-medium text-slate-200">{p.provider}</span>
                <span className="micro">{p.ordered} ordered</span>
                <span className="micro">peak queue {p.peakWaiting}</span>
                {p.missedDeadline > 0 && (
                  <span className="micro text-amber-400">{p.missedDeadline} refused on deadline</span>
                )}
              </div>
              {p.queue.length === 0 ? (
                <p className="text-xs text-slate-500 max-w-2xl leading-relaxed">Nothing waiting right now.</p>
              ) : (
                <ol className="space-y-1">
                  {p.queue.map((d) => (
                    <li key={d.id + d.rank} className="flex flex-wrap items-baseline gap-x-3">
                      <span className="readout w-6 shrink-0 text-xs text-slate-500">
                        #{d.rank + 1}
                      </span>
                      <span className="font-mono text-xs text-slate-300">{d.id}</span>
                      <span className="text-[11px] text-slate-500">{d.reason}</span>
                    </li>
                  ))}
                </ol>
              )}
            </Plane>
          ))}
        </div>
      )}

      <div>
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">What this does not do</h2>
        <p className="mt-1.5 max-w-2xl text-xs leading-relaxed text-slate-600">
          Each instance orders its own waiters. There is no global order across instances, and
          nothing is persisted.
        </p>
        <Explain title="Why not">
          <p>
            A shared queue needs a round trip to reach, and at a quarter-second wait that trip costs
            more than the ordering saves. A waiter exists only while its request is blocked, so a
            restart has no queue to lose.
          </p>
        </Explain>
      </div>

      {providers.length > 0 && (
        <button
          disabled={busy}
          onClick={() => act(() => portal.scheduling.reset(), "Counters cleared.")}
          className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:border-aurora/50 disabled:opacity-40"
        >
          Clear counters
        </button>
      )}
    </section>
  );
}

function Num({
  value,
  onChange,
  placeholder,
}: {
  value: number | null;
  onChange: (v: number | null) => void;
  placeholder?: string;
}) {
  return (
    <input
      type="number"
      min={0}
      value={value ?? ""}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
      className="w-20 rounded border border-edge bg-ink/60 px-1.5 py-0.5 text-slate-300 outline-none focus:border-aurora/50"
    />
  );
}
