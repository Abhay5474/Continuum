import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Micro, PageHeader, Plane, Readout, Switch } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { dateTimeOf } from "../system/time";

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
 */

type Dimension = { name: string; checked: number; failed: number; meanScore: number | null };
type Status = {
  mode: "OFF" | "MONITOR" | "ENFORCE";
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

const MODES: [Status["mode"], string, string][] = [
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

  const monitoring = status?.mode === "MONITOR";
  const successRate = status?.repairSuccessRate;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Quality Gate"
        subtitle="Checks a finished answer against the request that asked for it. Off by default; the gate can rewrite an answer, so it has to earn that first."
      />

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

      {/* ---- mode ---- */}
      <section className="space-y-3">
        <Micro>Mode</Micro>
        <div className="grid gap-3 sm:grid-cols-3">
          {MODES.map(([value, name, note]) => {
            const active = status?.mode === value;
            return (
              <button
                key={value}
                disabled={busy}
                onClick={() => run(() => portal.quality.configure({ mode: value }), `Mode: ${name}`)}
                className={`rounded-lg border p-3 text-left transition-colors disabled:opacity-50 ${
                  active ? "border-aurora/60 bg-aurora/10" : "border-edge hover:border-aurora/40"
                }`}
              >
                <div className="flex items-center gap-2">
                  <span className={`h-2 w-2 shrink-0 rounded-full ${active ? "bg-aurora" : "bg-edge"}`} />
                  <span className="text-sm font-medium text-slate-200">{name}</span>
                </div>
                <p className="mt-1 text-xs text-slate-500">{note}</p>
              </button>
            );
          })}
        </div>
      </section>

      {/* ---- the case for or against enforcing ---- */}
      <div className="flex flex-wrap gap-x-9 gap-y-4">
        <Readout label="Checked" value={status?.checked ?? 0} />
        <Readout
          label={monitoring ? "Would have acted" : "Acted on"}
          value={status?.wouldAct ?? 0}
          hint={`${Math.round((status?.wouldActRate ?? 0) * 100)}% of answers`}
          state={(status?.wouldAct ?? 0) > 0 ? "warning" : "idle"}
        />
        <Readout
          label="Repairs that helped"
          value={successRate == null ? "—" : `${Math.round(successRate * 100)}%`}
          state={successRate == null ? "idle" : successRate >= 0.6 ? "healthy" : "critical"}
          hint={`${status?.repairsImproved ?? 0} of ${status?.repairAttempts ?? 0} attempts`}
        />
        <Readout label="Blocked" value={status?.blocked ?? 0} hint="Refusals — never repaired" />
        <Readout
          label="Added latency"
          value={status?.extraMs ?? 0}
          unit="ms"
          hint={`$${(status?.extraCost ?? 0).toFixed(5)} spent repairing`}
        />
      </div>

      {status && status.checked > 0 && (
        <p className="text-xs text-slate-500">
          {monitoring ? (
            <>
              Monitoring only — nothing has been changed. The gate would have acted on{" "}
              <span className="text-slate-300">{status.wouldAct}</span> of {status.checked} answers.
              {status.wouldActRate > 0.4 &&
                " That is a high rate; check the dimension breakdown below before enforcing, in case one check is producing noise."}
            </>
          ) : successRate != null && successRate < 0.5 ? (
            <span className="text-rose-400">
              Fewer than half of repairs improved the answer. The gate is spending a second call and
              returning the same defect — monitor rather than enforce until that changes.
            </span>
          ) : successRate != null ? (
            <span className="text-emerald-400">
              {status.repairsImproved} of {status.repairAttempts} repairs scored better than the
              original. Repairs that did not are discarded, so a failed repair costs a call but never
              a worse answer.
            </span>
          ) : null}
        </p>
      )}

      {/* ---- which check is doing the work ---- */}
      <section className="space-y-3">
        <Micro>Dimensions · which check is doing the work, and which is only noise</Micro>
        <Plane className="divide-y divide-edge/40">
          {(status?.dimensions ?? []).map((d) => {
            const rate = d.checked === 0 ? 0 : d.failed / d.checked;
            return (
              <div key={d.name} className="flex flex-wrap items-center gap-x-4 gap-y-2 p-4">
                <div className="min-w-0 flex-1">
                  <div className="text-sm font-medium capitalize text-slate-200">{d.name}</div>
                  <div className="text-xs text-slate-500">{DIMENSION_NOTES[d.name]}</div>
                </div>
                <div className="flex w-40 shrink-0 items-center gap-2">
                  <div className="h-1.5 flex-1 overflow-hidden rounded-full bg-edge/60">
                    <div
                      className={`h-full rounded-full transition-all duration-500 ${
                        rate > 0.5 ? "bg-rose-500/70" : rate > 0 ? "bg-amber-500/70" : "bg-emerald-500/60"
                      }`}
                      style={{ width: `${Math.max(rate > 0 ? 3 : 0, rate * 100)}%` }}
                    />
                  </div>
                  <span className="readout w-20 shrink-0 text-right text-xs text-slate-400">
                    {d.failed}/{d.checked}
                  </span>
                </div>
              </div>
            );
          })}
        </Plane>
      </section>

      {/* ---- settings ---- */}
      <section className="space-y-3">
        <Micro>Limits</Micro>
        <Plane className="flex flex-wrap items-end gap-6 p-5">
          <label>
            <span className="micro">Act below</span>
            <select
              value={status?.threshold ?? 0.6}
              disabled={busy}
              onChange={(e) =>
                run(() => portal.quality.configure({ threshold: Number(e.target.value) }), "Threshold updated")
              }
              className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
            >
              {[0.4, 0.6, 0.8].map((t) => (
                <option key={t} value={t}>
                  {t.toFixed(1)}
                </option>
              ))}
            </select>
          </label>
          <label>
            <span className="micro">Repair attempts</span>
            <select
              value={status?.maxRepairs ?? 1}
              disabled={busy}
              onChange={(e) =>
                run(() => portal.quality.configure({ maxRepairs: Number(e.target.value) }), "Repair limit updated")
              }
              className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
            >
              <option value={0}>0 — check only</option>
              <option value={1}>1 attempt</option>
              <option value={2}>2 attempts</option>
            </select>
          </label>
          <label>
            <span className="micro">Latency budget</span>
            <select
              value={status?.budgetMs ?? 4000}
              disabled={busy}
              onChange={(e) =>
                run(() => portal.quality.configure({ budgetMs: Number(e.target.value) }), "Budget updated")
              }
              className="mt-1 block rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
            >
              {[1000, 2000, 4000, 8000].map((b) => (
                <option key={b} value={b}>
                  {b / 1000}s
                </option>
              ))}
            </select>
            <p className="mt-1 max-w-[16rem] text-xs text-slate-600">
              Past this the original answer is returned unchanged. A slow correct answer is worse than
              a fast flawed one for anything interactive.
            </p>
          </label>
          <button
            disabled={busy || !status?.checked}
            onClick={() => run(() => portal.quality.clear(), "History cleared")}
            className="ml-auto rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:bg-edge/50 disabled:opacity-40"
          >
            Clear history
          </button>
        </Plane>
      </section>

      {/* ---- the checks themselves ---- */}
      <section className="space-y-3">
        <Micro>Checks · newest first</Micro>
        {rows === null ? (
          <SkeletonRows rows={4} />
        ) : rows.length === 0 ? (
          <Plane className="p-8 text-center text-sm text-slate-500">
            Nothing checked yet. Set the mode to Monitor and send a request through the gateway.
          </Plane>
        ) : (
          <div className="space-y-2">
            {rows.map((r) => (
              <Check key={r.id} row={r} open={open === r.id} onToggle={() => setOpen(open === r.id ? null : r.id)} />
            ))}
          </div>
        )}
      </section>
    </div>
  );
}

function Check({ row, open, onToggle }: { row: any; open: boolean; onToggle: () => void }) {
  const dims: Record<string, number> = row.dimensions ?? {};
  const hasRepair = !!row.repairedAnswer;

  const badge =
    row.applied === "REPAIR"
      ? ["Repaired", "text-emerald-400 border-emerald-500/40"]
      : row.applied === "REPAIR_REJECTED"
        ? ["Repair discarded", "text-slate-400 border-edge"]
        : row.applied === "BUDGET_EXCEEDED"
          ? ["Over budget", "text-amber-400 border-amber-500/40"]
          : row.action === "PASS"
            ? ["Passed", "text-slate-500 border-edge"]
            : row.action === "BLOCK"
              ? ["Blocked", "text-rose-400 border-rose-500/40"]
              : ["Would repair", "text-amber-400 border-amber-500/40"];

  return (
    <Plane className="overflow-hidden">
      <button
        onClick={onToggle}
        aria-expanded={open}
        className="flex w-full min-w-0 items-center gap-4 px-4 py-3 text-left transition-colors hover:bg-edge/30"
      >
        <span className="readout w-10 shrink-0 text-sm text-slate-300">{row.score.toFixed(2)}</span>
        <div className="flex w-28 shrink-0 gap-1">
          {["adherence", "completeness", "grounding", "relevance"].map((d) => (
            <span
              key={d}
              title={`${d}: ${(dims[d] ?? 1).toFixed(2)}`}
              className={`h-4 flex-1 rounded-sm ${
                (dims[d] ?? 1) >= 1 ? "bg-emerald-500/40" : (dims[d] ?? 1) > 0 ? "bg-amber-500/50" : "bg-rose-500/50"
              }`}
            />
          ))}
        </div>
        <div className="min-w-0 flex-1">
          <div className="truncate text-sm text-slate-400">{row.defects ?? "meets the request"}</div>
          <div className="mt-0.5 text-xs text-slate-600">
            {row.mode} · {row.model} · {dateTimeOf(row.createdAt)}
          </div>
        </div>
        <span className={`shrink-0 rounded border px-2 py-0.5 text-[10px] uppercase tracking-wider ${badge[1]}`}>
          {badge[0]}
        </span>
        <span className={`shrink-0 text-slate-600 transition-transform ${open ? "rotate-90" : ""}`}>›</span>
      </button>

      {open && (
        <div className="border-t border-edge/60 px-4 py-3">
          {hasRepair ? (
            <>
              <div className="grid gap-3 lg:grid-cols-2">
                <div className="min-w-0">
                  <div className="micro mb-1.5">Before · {row.score.toFixed(2)}</div>
                  <p className="well max-h-48 overflow-y-auto p-3 text-xs leading-relaxed text-slate-400">
                    {row.originalAnswer}
                  </p>
                </div>
                <div className="min-w-0">
                  <div className="micro mb-1.5 text-emerald-400">
                    After · {(row.repairScore ?? 0).toFixed(2)}
                  </div>
                  <p className="well max-h-48 overflow-y-auto p-3 text-xs leading-relaxed text-slate-300">
                    {row.repairedAnswer}
                  </p>
                </div>
              </div>
              <p className="mt-3 text-xs text-slate-500">
                {row.applied === "REPAIR"
                  ? `The repair scored higher and was returned to the caller. It cost $${(row.extraCost ?? 0).toFixed(5)} and ${row.extraMs}ms.`
                  : `The repair did not score higher, so the original was returned unchanged. A failed repair costs a call but never a worse answer.`}
              </p>
            </>
          ) : (
            <p className="text-xs text-slate-500">
              {row.action === "PASS"
                ? "No defect this gate can check for. The answer was returned as produced."
                : row.mode === "MONITOR"
                  ? `Monitoring — the gate would have repaired this answer but changed nothing. Defect: ${row.defects}`
                  : row.action === "BLOCK"
                    ? "The model refused. Repairing a refusal buys the same refusal twice, so it is never attempted."
                    : `No repair ran. Defect: ${row.defects}`}
            </p>
          )}
        </div>
      )}
    </Plane>
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
    <section className="space-y-3">
      <Micro>Answer Repair Engine</Micro>

      <Plane className="space-y-3 p-4">
        <Switch
          checked={enabled}
          busy={busy}
          onChange={onToggle}
          label="Targeted repair"
          hint="Off by default. With it off the gate does one generic repair pass. With it on, each defect kind gets its own instruction, the answer is re-scored after every attempt, and an attempt that scored lower than what it replaced is thrown away."
        />
        <p className="text-xs text-slate-600">
          A model asked to reconsider will find fault with correct work and degrade it — that is
          the finding in Huang et al., <em>Large Language Models Cannot Self-Correct Reasoning
          Yet</em> (ICLR 2024). Nothing here relies on the model's opinion of its own answer: every
          attempt is scored by the same external gate, so a repair that did not help is discarded
          rather than shipped.
        </p>
      </Plane>

      {total > 0 && (
        <div className="flex flex-wrap gap-x-9 gap-y-4">
          <Readout label="Attempts" value={total} size="sm" />
          <Readout label="Kept" value={kept} size="sm" state={kept > 0 ? "healthy" : "idle"} />
          <Readout
            label="Discarded"
            value={discarded}
            size="sm"
            state={discarded > 0 ? "degraded" : "idle"}
            hint="Scored no better than the answer they replaced, so the original was kept."
          />
          <Readout
            label="Score gained"
            value={(stats?.totalScoreGained ?? 0).toFixed(2)}
            size="sm"
            hint="Summed improvement across every kept attempt."
          />
        </div>
      )}

      {attempts.length === 0 ? (
        <Plane className="p-6 text-center text-sm text-slate-500">
          No repair attempts yet. They appear here as the gate finds defects worth fixing —
          including the attempts that were thrown away.
        </Plane>
      ) : (
        <div className="space-y-1.5">
          {attempts.map((a) => (
            <Plane key={a.id} className="px-4 py-2.5">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span
                  className={`h-2 w-2 shrink-0 rounded-full ${
                    a.kept ? "bg-emerald-400" : "bg-slate-600"
                  }`}
                />
                <span className="micro w-28 shrink-0">{a.strategy}</span>
                <span className="min-w-0 flex-1 truncate text-sm text-slate-300">{a.note}</span>
                <span className="readout shrink-0 text-xs text-slate-400">
                  {a.scoreBefore?.toFixed(2)} → {a.scoreAfter?.toFixed(2)}
                </span>
                <span className={`micro shrink-0 ${a.kept ? "text-emerald-400" : "text-slate-500"}`}>
                  {a.kept ? "kept" : "discarded"}
                </span>
              </div>
              {a.defects && (
                <p className="mt-1 truncate text-xs text-slate-600">{a.defects}</p>
              )}
            </Plane>
          ))}
        </div>
      )}
    </section>
  );
}
