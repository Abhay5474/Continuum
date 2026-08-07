import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { PageHeader, Switch } from "../system/primitives";
import { ErrorState, useToast } from "../components/ui";
import { dateTimeOf } from "../system/time";
import {
  Explain,
  Bar,
  Code,
  Dot,
  Empty,
  Facts,
  Field,
  Ghost,
  Hop,
  KindMark,
  Rail,
  Route,
  Row,
  RowSkeleton,
  Segmented,
  SidePanel,
  Stage,
  Stat,
  Stats,
} from "../system/hub";

/**
 * Response quality gate.
 *
 * <p>This page has an unusual burden of proof. The gate can rewrite an answer
 * before a customer sees it, so the interface has to make the case for letting
 * it — not assert that it works. Two things carry that:
 *
 * <ul>
 *   <li><b>Monitor mode is the default landing.</b> The counters read "would
 *       have acted on N", and nothing was changed. That is the evidence for
 *       enforcing, drawn from the account's own traffic.</li>
 *   <li><b>Repairs show their before and after.</b> A gate that fires often and
 *       fixes nothing is worse than no gate — it costs a second call and returns
 *       the same defect — so the repair success rate is prominent and every
 *       repair is inspectable side by side.</li>
 * </ul>
 *
 * <p><b>On the shape of this screen.</b> Three modes as three bordered cards
 * read as three products; they are one setting with three positions, and the
 * position you are in changes what the numbers underneath mean. The mode is a
 * segmented control now, and the case for moving it is stated directly beneath.
 */

type Dimension = { name: string; checked: number; failed: number; meanScore: number | null };
type Mode = "OFF" | "MONITOR" | "ENFORCE";
type Status = {
  mode: Mode;
  threshold: number;
  maxRepairs: number;
  budgetMs: number;
  checked: number;
  wouldAct: number;
  wouldActRate: number;
  repaired: number;
  blocked: number;
  repairAttempts: number;
  repairsImproved: number;
  repairSuccessRate: number | null;
  dimensions: Dimension[];
  extraCost: number;
  extraMs: number;
};

const MODES: [Mode, string, string][] = [
  ["OFF", "Off", "The gate never runs. Answers are returned exactly as the model produced them."],
  ["MONITOR", "Monitor", "Checks every answer and records what it would have done — without changing anything. Start here."],
  ["ENFORCE", "Enforce", "Checks and repairs. Only worth turning on once monitoring shows repairs actually help."],
];

const DIMENSION_NOTES: Record<string, string> = {
  adherence: "The explicit contract: format, item counts, not truncated, not a refusal",
  completeness: "A three-part question should not come back having answered one",
  grounding: "Figures asserted in the answer should appear in the supplied context",
  relevance: "The answer is about the question at all",
};

const DIMS = ["adherence", "completeness", "grounding", "relevance"];

export default function QualityGatePage() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [rows, setRows] = useState<any[] | null>(null);
  const [repairs, setRepairs] = useState<any[]>([]);
  const [repairStats, setRepairStats] = useState<any | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [s, c, r, rs] = await Promise.all([
        portal.quality.status(),
        portal.quality.checks(30),
        portal.quality.repairs(30),
        portal.quality.repairSummary(),
      ]);
      setStatus(s);
      setRows(c);
      setRepairs(r);
      setRepairStats(rs);
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load the quality gate.");
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

  const mode = status?.mode ?? "OFF";
  const monitoring = mode === "MONITOR";
  const successRate = status?.repairSuccessRate;
  const modeNote = MODES.find(([v]) => v === mode)?.[2];
  const selected = (rows ?? []).find((r) => r.id === open) ?? null;

  return (
    <div className="page-enter">
      <PageHeader
        title="Quality Gate"
        subtitle="Checks a finished answer against the request that asked for it. Off by default; the gate can rewrite an answer, so it has to earn that first."
      />

      {/* Where it sits. This is the only stage on the path that runs *after*
          the model, and that is the whole reason it is allowed to rewrite. */}
      <div className="mt-6">
        <Route>
          <Stage label="the model's answer" sub="as produced" />
          <Hop />
          <Stage
            label="Quality Gate"
            sub={
              mode === "OFF"
                ? "off"
                : monitoring
                  ? "watching, changing nothing"
                  : "repairing when it helps"
            }
            state={mode === "OFF" ? "off" : monitoring ? "on" : "on"}
            mark={<KindMark kind="moderation" size={26} />}
            selected
          />
          <Hop label={mode === "ENFORCE" ? "checked" : "unchanged"} />
          <Stage label="your app" sub="what the customer reads" />
        </Route>
      </div>

      {/* ---- mode ---- */}
      <div className="mt-7">
        <Segmented<Mode>
          value={mode}
          onChange={(v) => run(() => portal.quality.configure({ mode: v }), `Mode: ${v}`)}
          options={MODES.map(([value, label]) => ({
            value,
            label,
            badge:
              value === mode && value !== "OFF" ? (
                <span className="h-1.5 w-1.5 rounded-full" style={{ background: "var(--accent)" }} aria-hidden />
              ) : undefined,
          }))}
        />
        <p className="mt-3 max-w-2xl text-xs leading-relaxed text-slate-500">{modeNote}</p>
      </div>

      {/* ---- the case for or against enforcing ---- */}
      <div className="mt-7">
        <Stats>
          <Stat label="Checked" value={status?.checked ?? 0} />
          <Stat
            label={monitoring ? "Would have acted" : "Acted on"}
            value={status?.wouldAct ?? 0}
            hint={`${Math.round((status?.wouldActRate ?? 0) * 100)}% of answers`}
            tone={(status?.wouldAct ?? 0) > 0 ? "warn" : undefined}
          />
          <Stat
            label="Repairs that helped"
            value={successRate == null ? "—" : `${Math.round(successRate * 100)}%`}
            tone={successRate == null ? undefined : successRate >= 0.6 ? "ok" : "bad"}
            hint={`${status?.repairsImproved ?? 0} of ${status?.repairAttempts ?? 0} attempts`}
          />
          <Stat label="Blocked" value={status?.blocked ?? 0} hint="Refusals — never repaired" />
          <Stat
            label="Added latency"
            value={status?.extraMs ?? 0}
            unit="ms"
            hint={`$${(status?.extraCost ?? 0).toFixed(5)} spent repairing`}
          />
        </Stats>
      </div>

      {status && status.checked > 0 && (
        <Case
          tone={
            monitoring ? "idle" : successRate != null && successRate < 0.5 ? "bad" : successRate != null ? "ok" : "idle"
          }
        >
          {monitoring ? (
            <>
              Monitoring only — nothing has been changed. The gate would have acted on{" "}
              <span className="text-slate-300">{status.wouldAct}</span> of {status.checked} answers.
              {status.wouldActRate > 0.4 &&
                " That is a high rate; check the dimension breakdown below before enforcing, in case one check is producing noise."}
            </>
          ) : successRate != null && successRate < 0.5 ? (
            <>
              Fewer than half of repairs improved the answer. The gate is spending a second call and
              returning the same defect — monitor rather than enforce until that changes.
            </>
          ) : successRate != null ? (
            <>
              {status.repairsImproved} of {status.repairAttempts} repairs scored better than the
              original. Repairs that did not are discarded, so a failed repair costs a call but never
              a worse answer.
            </>
          ) : null}
        </Case>
      )}

      {/* ---- which check is doing the work ---- */}
      <section className="mt-10">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Dimensions</h2>
        <p className="mt-1 max-w-2xl text-xs leading-relaxed text-slate-500">
          One failing almost everything is usually a threshold problem, not a fleet of bad answers.
        </p>
        <div className="mt-3">
          <Rail>
            {(status?.dimensions ?? []).map((d) => {
              const rate = d.checked === 0 ? 0 : d.failed / d.checked;
              return (
                <Row
                  key={d.name}
                  title={<span className="capitalize">{d.name}</span>}
                  subtitle={DIMENSION_NOTES[d.name]}
                  trailing={
                    <>
                      <Bar
                        fraction={rate}
                        tone={rate > 0.5 ? "bad" : rate > 0 ? "warn" : "ok"}
                        width={90}
                      />
                      <span className="readout w-14 text-right text-[11px] text-slate-500">
                        {d.failed}/{d.checked}
                      </span>
                    </>
                  }
                />
              );
            })}
          </Rail>
        </div>
      </section>

      {/* ---- settings ---- */}
      <section className="mt-10">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Limits</h2>
        <div className="mt-4 flex flex-wrap items-start gap-x-8 gap-y-5">
          <Pick
            label="Act below"
            value={status?.threshold ?? 0.6}
            disabled={busy}
            onChange={(v) => run(() => portal.quality.configure({ threshold: v }), "Threshold updated")}
            options={[0.4, 0.6, 0.8].map((t) => [t, t.toFixed(1)])}
          />
          <Pick
            label="Repair attempts"
            value={status?.maxRepairs ?? 1}
            disabled={busy}
            onChange={(v) => run(() => portal.quality.configure({ maxRepairs: v }), "Repair limit updated")}
            options={[
              [0, "0 — check only"],
              [1, "1 attempt"],
              [2, "2 attempts"],
            ]}
          />
          <Pick
            label="Latency budget"
            value={status?.budgetMs ?? 4000}
            disabled={busy}
            onChange={(v) => run(() => portal.quality.configure({ budgetMs: v }), "Budget updated")}
            options={[1000, 2000, 4000, 8000].map((b) => [b, `${b / 1000}s`])}
            note="Past this the original answer is returned unchanged. A slow correct answer is worse than a fast flawed one for anything interactive."
          />
        </div>
        <div className="mt-5">
          <Ghost
            disabled={busy || !status?.checked}
            onClick={() => run(() => portal.quality.clear(), "History cleared")}
          >
            Clear history
          </Ghost>
        </div>
      </section>

      <div className="mt-10">
        <RepairEngine
          enabled={!!(status as any)?.repairEngineEnabled}
          busy={busy}
          stats={repairStats}
          attempts={repairs}
          onToggle={(next) =>
            run(
              () => portal.quality.configure({ repairEngineEnabled: next }),
              next ? "Repair engine on." : "Repair engine off."
            )
          }
        />
      </div>

      {/* ---- the checks themselves ---- */}
      <section className="mt-10">
        <h2 className="flex items-baseline gap-2 text-[13px] font-semibold tracking-tight text-slate-200">
          Checks
          {rows && <span className="readout text-[11px] font-normal text-slate-600">{rows.length}</span>}
          <span className="text-[11px] font-normal text-slate-600">newest first</span>
        </h2>
        <div className="mt-3">
          {rows === null ? (
            <RowSkeleton rows={4} />
          ) : rows.length === 0 ? (
            <Empty
              title="Nothing checked yet"
              hint="Set the mode to Monitor and send a request through the gateway. Monitoring records what the gate would have done without changing a single answer."
            />
          ) : (
            <Rail>
              {rows.map((r) => (
                <CheckRow key={r.id} row={r} selected={open === r.id} onOpen={() => setOpen(r.id)} />
              ))}
            </Rail>
          )}
        </div>
      </section>

      <CheckPanel row={selected} onClose={() => setOpen(null)} />
    </div>
  );
}

/**
 * The argument, on a coloured rule.
 *
 * <p>This paragraph is the one on the page that decides whether someone lets
 * the gate rewrite customer-facing text, so it gets a mark of its own rather
 * than being another line of grey body copy.
 */
function Case({ tone, children }: { tone: "ok" | "bad" | "idle"; children: React.ReactNode }) {
  const colour = {
    ok: "var(--state-healthy-ink)",
    bad: "var(--state-critical-ink)",
    idle: "var(--state-idle-ink)",
  }[tone];
  return (
    <p
      className="mt-5 max-w-2xl border-l-2 pl-3.5 text-xs leading-relaxed text-slate-400"
      style={{ borderColor: colour }}
    >
      {children}
    </p>
  );
}

/** A labelled select, without the box a form field used to come wrapped in. */
function Pick({
  label,
  value,
  onChange,
  options,
  disabled,
  note,
}: {
  label: string;
  value: number;
  onChange: (v: number) => void;
  options: [number, string][];
  disabled?: boolean;
  note?: string;
}) {
  return (
    <label className="block">
      <span className="micro">{label}</span>
      <select
        value={value}
        disabled={disabled}
        onChange={(e) => onChange(Number(e.target.value))}
        className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-[13px] text-slate-200 outline-none focus:border-[color:var(--accent-edge)]"
      >
        {options.map(([v, l]) => (
          <option key={v} value={v}>
            {l}
          </option>
        ))}
      </select>
      {note && <p className="mt-1.5 max-w-[17rem] text-[11.5px] leading-relaxed text-slate-600">{note}</p>}
    </label>
  );
}

/** The four dimension scores, as four marks rather than four numbers. */
function DimStrip({ dims }: { dims: Record<string, number> }) {
  return (
    <span className="flex w-24 shrink-0 gap-1" aria-hidden>
      {DIMS.map((d) => {
        const v = dims[d] ?? 1;
        return (
          <span
            key={d}
            title={`${d}: ${v.toFixed(2)}`}
            className="h-3.5 flex-1 rounded-sm"
            style={{
              background:
                v >= 1
                  ? "color-mix(in srgb, var(--state-healthy-ink) 45%, transparent)"
                  : v > 0
                    ? "color-mix(in srgb, var(--state-warning-ink) 55%, transparent)"
                    : "color-mix(in srgb, var(--state-critical-ink) 55%, transparent)",
            }}
          />
        );
      })}
    </span>
  );
}

function verdictOf(row: any): { label: string; tone: "ok" | "warn" | "bad" | "idle" } {
  if (row.applied === "REPAIR") return { label: "repaired", tone: "ok" };
  if (row.applied === "REPAIR_REJECTED") return { label: "repair discarded", tone: "idle" };
  if (row.applied === "BUDGET_EXCEEDED") return { label: "over budget", tone: "warn" };
  if (row.action === "PASS") return { label: "passed", tone: "idle" };
  if (row.action === "BLOCK") return { label: "blocked", tone: "bad" };
  return { label: "would repair", tone: "warn" };
}

function CheckRow({ row, selected, onOpen }: { row: any; selected: boolean; onOpen: () => void }) {
  const v = verdictOf(row);
  return (
    <Row
      selected={selected}
      onClick={onOpen}
      mark={
        <span className="flex w-14 shrink-0 flex-col items-start gap-1">
          <span className="readout text-[13px] text-slate-200">{row.score.toFixed(2)}</span>
          <DimStrip dims={row.dimensions ?? {}} />
        </span>
      }
      title={row.defects ?? "meets the request"}
      status={<Dot tone={v.tone} label={v.label} />}
      subtitle={`${String(row.mode).toLowerCase()} · ${row.model} · ${dateTimeOf(row.createdAt)}`}
    />
  );
}

/**
 * One check, opened.
 *
 * <p>Before and after stacked rather than side by side: the panel is narrower
 * than a two-column diff needs, and two 30-character columns of prose is worse
 * for reading than the same text one after the other.
 */
function CheckPanel({ row, onClose }: { row: any | null; onClose: () => void }) {
  const v = row ? verdictOf(row) : null;
  const hasRepair = !!row?.repairedAnswer;
  return (
    <SidePanel
      open={!!row}
      title={row ? `Scored ${row.score.toFixed(2)}` : ""}
      subtitle={row ? `${row.model} · ${dateTimeOf(row.createdAt)}` : undefined}
      mark={<KindMark kind="moderation" size={34} />}
      onClose={onClose}
    >
      {row && (
        <>
          <Field label="Verdict">
            <Dot tone={v!.tone} label={v!.label} />
            <p className="mt-1.5 leading-relaxed text-slate-400">
              {row.defects ?? "No defect this gate can check for."}
            </p>
          </Field>

          <Field label="Dimensions">
            <div className="space-y-1.5">
              {DIMS.map((d) => {
                const val = (row.dimensions ?? {})[d] ?? 1;
                return (
                  <div key={d} className="flex items-center gap-3">
                    <span className="w-28 shrink-0 text-[11.5px] capitalize text-slate-500">{d}</span>
                    <Bar fraction={val} tone={val >= 1 ? "ok" : val > 0 ? "warn" : "bad"} width={110} />
                    <span className="readout text-[11px] text-slate-500">{val.toFixed(2)}</span>
                  </div>
                );
              })}
            </div>
          </Field>

          {hasRepair ? (
            <>
              <Field label={`Before · ${row.score.toFixed(2)}`}>
                <Code>{row.originalAnswer}</Code>
              </Field>
              <Field label={`After · ${(row.repairScore ?? 0).toFixed(2)}`}>
                <Code>{row.repairedAnswer}</Code>
              </Field>
              <p className="mt-4 text-[11.5px] leading-relaxed text-slate-500">
                {row.applied === "REPAIR"
                  ? `The repair scored higher and was returned to the caller. It cost $${(row.extraCost ?? 0).toFixed(5)} and ${row.extraMs}ms.`
                  : "The repair did not score higher, so the original was returned unchanged. A failed repair costs a call but never a worse answer."}
              </p>
            </>
          ) : (
            <Field label="What happened">
              <p className="leading-relaxed text-slate-400">
                {row.action === "PASS"
                  ? "No defect this gate can check for. The answer was returned as produced."
                  : row.mode === "MONITOR"
                    ? `Monitoring — the gate would have repaired this answer but changed nothing. Defect: ${row.defects}`
                    : row.action === "BLOCK"
                      ? "The model refused. Repairing a refusal buys the same refusal twice, so it is never attempted."
                      : `No repair ran. Defect: ${row.defects}`}
              </p>
            </Field>
          )}
        </>
      )}
    </SidePanel>
  );
}

// --- Answer Repair Engine ---------------------------------------------------

/**
 * The attempt ledger, discards included.
 *
 * <p>The discarded attempts are the point. They are the evidence that the guard
 * against making an answer worse is doing something — an engine that never
 * discards anything is not being checked, and one that never keeps anything is
 * money going out with nothing coming back.
 */
function RepairEngine({
  enabled,
  busy,
  stats,
  attempts,
  onToggle,
}: {
  enabled: boolean;
  busy: boolean;
  stats: any | null;
  attempts: any[];
  onToggle: (next: boolean) => void;
}) {
  const kept = stats?.kept ?? 0;
  const discarded = stats?.discarded ?? 0;
  const total = kept + discarded;

  return (
    <section>
      <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Answer repair engine</h2>

      <div className="mt-4">
        <Switch
          checked={enabled}
          busy={busy}
          onChange={onToggle}
          label="Targeted repair"
          hint="Off by default. With it off the gate does one generic repair pass. With it on, each defect kind gets its own instruction, the answer is re-scored after every attempt, and an attempt that scored lower than what it replaced is thrown away."
        />
        <Explain title="Why the model never judges its own repair">
          <p>
            A model asked to reconsider will find fault with correct work and degrade it — Huang et
            al., <em>Large Language Models Cannot Self-Correct Reasoning Yet</em> (ICLR 2024). Every
            attempt here is scored by the same external gate, so a repair that did not help is
            discarded rather than shipped.
          </p>
        </Explain>
      </div>

      {total > 0 && (
        <div className="mt-6">
          <Stats>
            <Stat label="Attempts" value={total} />
            <Stat label="Kept" value={kept} tone={kept > 0 ? "ok" : undefined} />
            <Stat
              label="Discarded"
              value={discarded}
              tone={discarded > 0 ? "warn" : undefined}
              hint="Scored no better than the answer they replaced, so the original was kept."
            />
            <Stat
              label="Score gained"
              value={(stats?.totalScoreGained ?? 0).toFixed(2)}
              hint="Summed improvement across every kept attempt."
            />
          </Stats>
        </div>
      )}

      <div className="mt-5">
        {attempts.length === 0 ? (
          <Empty
            title="No repair attempts yet"
            hint="They appear here as the gate finds defects worth fixing — including the attempts that were thrown away."
          />
        ) : (
          <Rail>
            {attempts.map((a) => (
              <Row
                key={a.id}
                title={a.note}
                subtitle={a.defects || undefined}
                status={<Dot tone={a.kept ? "ok" : "idle"} label={a.kept ? "kept" : "discarded"} />}
                meta={
                  <Facts
                    items={[
                      { k: "strategy", v: a.strategy },
                      {
                        k: "score",
                        v: `${a.scoreBefore?.toFixed(2)} → ${a.scoreAfter?.toFixed(2)}`,
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
  );
}
