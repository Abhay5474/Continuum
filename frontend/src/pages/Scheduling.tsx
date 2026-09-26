import { useCallback, useEffect, useState } from "react";
import { visibleInterval } from "../system/poll";
import { portal } from "../api";
import { PageHeader, Plane, Readout, Switch, Note, InfoTip } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { Card, Explain, Empty, Pill, toneInk, type Tone } from "../system/hub";
import { Mechanism } from "../system/viz";
import { Select, Table, TH, TR, TD } from "../system/controls";

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
  const ordered = providers.reduce((n, p) => n + p.ordered, 0);
  const on = !!status?.enabled;

  return (
    <section className="space-y-8">
      <PageHeader
        glyph="clock"
        tone="info"
        title="Priority & Deadlines"
        subtitle="Who gets the next free slot"
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

      {/* The scheduler's job, drawn: when requests are waiting for a full
          provider, it decides who gets the next free slot — by band, then
          deadline, then how long each has waited — and refuses what could not
          finish in time anyway. */}
      <Card guide="scheduling-mechanism">
        <Mechanism
          summary={`${ordered} requests were ordered for a slot, ${promoted} of them ahead of earlier arrivals; ${aged} were lifted by waiting and ${missed} refused on deadline.`}
          nodes={[
            { id: "in", col: 0, span: 3, role: "end", glyph: "queue", label: "Waiting for a slot", sub: "only while a provider is full", value: waiting },
            {
              id: "core",
              col: 1,
              span: 3,
              role: "core",
              tone: "violet",
              glyph: "list",
              off: !on,
              label: "Scheduler",
              sub: on ? "band → deadline → time waited" : "off — first come, first served",
            },
            { id: "next", col: 2, row: 0, tone: "green", glyph: "check", label: "Next free slot", sub: `${promoted} jumped the queue`, value: ordered, off: !on },
            {
              id: "aged",
              col: 2,
              row: 1,
              tone: "blue",
              glyph: "up",
              label: "Lifted by waiting",
              sub: `+1 band per ${status?.agingStepSeconds ?? 120}s, up to 2`,
              value: aged,
              off: !on,
            },
            { id: "late", col: 2, row: 2, tone: "red", glyph: "block", label: "Refused on deadline", sub: "422 — could not finish in time", value: missed, off: !on },
            { id: "prov", col: 3, row: 0, span: 2, role: "end", glyph: "model", label: "The provider" },
          ]}
          links={[
            { from: "in", to: "core", weight: waiting + ordered + missed },
            { from: "core", to: "next", weight: ordered, tone: "green", off: !on },
            { from: "core", to: "aged", weight: aged, tone: "blue", off: !on },
            { from: "core", to: "late", weight: missed, tone: "red", off: !on },
            { from: "next", to: "prov", weight: ordered, tone: "green", off: !on },
            { from: "aged", to: "prov", weight: aged, tone: "blue", off: !on },
          ]}
        />
      </Card>

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
        <Note>
          Set <span className="readout">criticality</span> to choose a band and{" "}
          <span className="readout">deadlineMs</span> to say how long the result stays useful.
        </Note>
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
        <Note>
          Waiting is aged into the band — every{" "}
          <span className="readout">{status?.agingStepSeconds ?? 120}s</span> queued lifts a task one
          band, up to two. Without that, &ldquo;low priority&rdquo; quietly means &ldquo;never&rdquo;
          under sustained load.
        </Note>
      </div>

      <div className="space-y-3">
        <h2 className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight text-slate-200">
          Try the ordering
          <InfoTip text="Nothing is executed. The scheduler's own ordering is applied to these tasks, so the rules can be checked before real traffic depends on them." />
        </h2>

        <Table
          minWidth={520}
          head={
            <tr>
              <TH>Task</TH>
              <TH width={150}>Priority</TH>
              <TH>Waited (s)</TH>
              <TH>Deadline (s)</TH>
              <TH>Takes (s)</TH>
            </tr>
          }
        >
              {rows.map((r, i) => (
                <TR key={r.id}>
                  <TD className="font-mono">{r.id}</TD>
                  <TD>
                    <Select
                      aria-label={`${r.id}: priority`}
                      value={r.priority}
                      onChange={(e) => patch(i, { priority: e.target.value })}
                    >
                      <option>BATCH</option>
                      <option>NORMAL</option>
                      <option>INTERACTIVE</option>
                    </Select>
                  </TD>
                  <TD>
                    <Num label={`${r.id}: seconds waited`} value={r.waitedSeconds} onChange={(v) => patch(i, { waitedSeconds: v ?? 0 })} />
                  </TD>
                  <TD>
                    <Num
                      label={`${r.id}: deadline in seconds`}
                      value={r.deadlineSeconds}
                      placeholder="none"
                      onChange={(v) => patch(i, { deadlineSeconds: v })}
                    />
                  </TD>
                  <TD>
                    <Num
                      label={`${r.id}: seconds it takes`}
                      value={r.estimateSeconds}
                      onChange={(v) => patch(i, { estimateSeconds: v ?? 0 })}
                    />
                  </TD>
                </TR>
              ))}
        </Table>

        <button
          disabled={busy}
          onClick={() => void plan()}
          className="rounded-md border border-aurora/50 bg-aurora/10 px-3 py-1.5 text-sm text-slate-100 hover:bg-aurora/20 disabled:opacity-40"
        >
          Order them
        </button>

        {planned && <PlanTimeline planned={planned} rows={rows} />}
      </div>

      {status === null ? (
        <SkeletonRows rows={2} />
      ) : providers.length === 0 ? (
        <Empty title={"Nothing has queued yet"} hint={"Requests queue only when a provider is at capacity."} />
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
      <Explain title="What this does not do">
          <p>Each instance orders its own waiters. There is no global order across instances, and nothing is persisted.</p>
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

const BAND_TONE: Record<string, Tone> = { INTERACTIVE: "blue", NORMAL: "green", BATCH: "mute" };

/**
 * The scheduler's answer as a timeline, not a list.
 *
 * <p>Each runnable task is a bar laid end to end in the order it was given, as
 * if one slot served them in turn; its deadline is a mark on its own row. A
 * refused task is drawn from "now" at its full length, and the reason it was
 * refused is visible without reading anything: the bar runs past its mark.
 */
function PlanTimeline({ planned, rows }: { planned: Decision[]; rows: Row[] }) {
  const byId = new Map(rows.map((r) => [r.id, r]));
  const runnable = planned.filter((d) => d.runnable).sort((a, b) => a.rank - b.rank);
  const refused = planned.filter((d) => !d.runnable);
  let t = 0;
  const bars = [
    ...runnable.map((d) => {
      const r = byId.get(d.id);
      const est = r?.estimateSeconds ?? 0;
      const start = t;
      t += est;
      return { d, r, start, end: start + est };
    }),
    ...refused.map((d) => {
      const r = byId.get(d.id);
      return { d, r, start: 0, end: r?.estimateSeconds ?? 0 };
    }),
  ];
  // Scaled to the work, not to the furthest deadline: a ten-minute deadline
  // beside four-second tasks would shrink every bar to a sliver. A deadline
  // past the edge is written at the edge instead of drawn.
  const workEnd = Math.max(1, ...bars.map((b) => b.end));
  const horizon =
    Math.max(
      workEnd,
      ...bars.map((b) => (b.r?.deadlineSeconds != null && b.r.deadlineSeconds <= workEnd * 2 ? b.r.deadlineSeconds : 0)),
    ) * 1.15;
  const x = (v: number) => `${Math.min(100, (v / horizon) * 100)}%`;
  return (
    <Card>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <span className="micro">One slot, in the order given</span>
        <span className="flex items-center gap-3 text-[10.5px] text-slate-500">
          <span className="flex items-center gap-1"><span className="h-2 w-4 rounded-sm" style={{ background: toneInk("blue"), opacity: 0.7 }} />runs</span>
          <span className="flex items-center gap-1"><span className="h-3 w-px" style={{ background: "var(--state-critical-ink)" }} />deadline</span>
        </span>
      </div>
      <ol className="mt-3 space-y-2.5">
        {bars.map(({ d, r, start, end }) => {
          const tone = BAND_TONE[r?.priority ?? "NORMAL"] ?? "mute";
          const deadline = r?.deadlineSeconds ?? null;
          return (
            <li key={d.id} className="grid items-center gap-x-3 gap-y-1 sm:grid-cols-[190px_minmax(0,1fr)]">
              <div className="flex min-w-0 items-center gap-2">
                <span className="readout w-6 shrink-0 text-[11px] text-slate-500">{d.runnable ? `#${d.rank + 1}` : "—"}</span>
                <span className="truncate font-mono text-[11.5px] text-slate-200">{d.id}</span>
                <Pill tone={tone}>{(r?.priority ?? "").toLowerCase()}</Pill>
              </div>
              <div className="min-w-0">
                <div className="relative h-5 rounded-md" style={{ background: "rgb(var(--card-rule))" }}>
                  <span
                    className="absolute inset-y-0.5 rounded"
                    style={{
                      left: x(start),
                      width: `calc(${x(end)} - ${x(start)})`,
                      minWidth: 3,
                      background: d.runnable ? toneInk(tone === "mute" ? "blue" : tone) : "var(--state-critical-ink)",
                      opacity: d.runnable ? 0.7 : 0.35,
                      backgroundImage: d.runnable ? undefined : "repeating-linear-gradient(135deg, transparent 0 4px, rgb(255 255 255 / .35) 4px 7px)",
                    }}
                    title={`${start}s → ${end}s`}
                  />
                  {deadline != null && deadline < horizon && (
                    <span className="absolute -inset-y-1 w-[2px] rounded" style={{ left: x(deadline), background: "var(--state-critical-ink)" }}
                          title={`deadline ${deadline}s`} />
                  )}
                  {deadline != null && deadline >= horizon && (
                    <span className="readout absolute inset-y-0 right-1.5 flex items-center text-[10px] text-slate-500">due {deadline}s →</span>
                  )}
                </div>
                <div className={`mt-0.5 text-[10.5px] ${d.runnable ? "text-slate-500" : ""}`}
                     style={d.runnable ? undefined : { color: "var(--state-critical-ink)" }}>
                  {d.reason}
                </div>
              </div>
            </li>
          );
        })}
      </ol>
      <div className="mt-2 flex justify-between pl-0 text-[10px] text-slate-500 sm:pl-[202px]">
        <span>now</span>
        <span>{Math.round(horizon)}s</span>
      </div>
    </Card>
  );
}

function Num({
  value,
  onChange,
  placeholder,
  label,
}: {
  value: number | null;
  onChange: (v: number | null) => void;
  placeholder?: string;
  /** Read out in place of the column header a sighted user sees above it. */
  label?: string;
}) {
  return (
    <input
      type="number"
      aria-label={label}
      min={0}
      value={value ?? ""}
      placeholder={placeholder}
      onChange={(e) => onChange(e.target.value === "" ? null : Number(e.target.value))}
      className="w-20 field"
    />
  );
}
