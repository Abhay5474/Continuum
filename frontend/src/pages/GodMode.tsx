import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { portal } from "../api";
import { Chip } from "../system/hub";
import { Micro, Readout, StateDot, Meter } from "../system/primitives";
import { STATE, type StateKey } from "../system/tokens";
import { timeOf } from "../system/time";
import { Select } from "../system/controls";

/**
 * Adaptive Policy — the autonomous memory and policy engine.
 *
 * Opt-in, reversible, off by default. The page is organised around the four
 * questions the engine actually answers:
 *
 *   1. What does it remember?  — the tier cascade and context pressure
 *   2. How does it decide?     — the MemAct posteriors that pick the next action
 *   3. Can a change be proven safe? — digital-twin replay, baseline vs candidate
 *   4. What has it learned?    — the experience graph
 *
 * Everything drawn here is measured. Where the engine reports no observations
 * for an action or tier, that is stated rather than filled in.
 */

const MEMACT_ACTIONS = [
  { key: "SUMMARIZE_NOW", label: "Summarize now", desc: "Fold working memory into an episode" },
  { key: "DEFER", label: "Defer", desc: "Leave working memory alone for now" },
  { key: "PRUNE_DUPLICATES", label: "Prune duplicates", desc: "Collapse near-identical semantic nodes" },
  { key: "PROMOTE_EXPERIENCE", label: "Promote experience", desc: "Lift an episode into the experience graph" },
  { key: "ARCHIVE_COLD", label: "Archive cold", desc: "Move decayed memory to cold storage" },
];

const SCENARIOS = [
  { key: "HISTORICAL_REPLAY", label: "Historical replay", desc: "Replay real traffic against both policies" },
  { key: "PROVIDER_OUTAGE", label: "Provider outage", desc: "Degrade the busiest provider mid-replay" },
  { key: "HALLUCINATION_STORM", label: "Hallucination storm", desc: "Inject model-quality failures" },
];

const TIERS = [
  { key: "working", label: "Working", unit: "items", desc: "Live conversation turns, bounded by the context budget" },
  { key: "episodic", label: "Episodic", unit: "items", desc: "Summarised episodes" },
  { key: "semantic", label: "Semantic", unit: "nodes", desc: "Distilled, reusable experience" },
  { key: "archive", label: "Archive", unit: "spans", desc: "Cold storage — retained, not retrieved" },
];

function verdictState(v: string): StateKey {
  if (v === "PROMOTE") return "healthy";
  if (v === "ROLLBACK") return "critical";
  return "warning";
}

export default function GodMode() {
  const loggedIn = !!portal.session();

  const [status, setStatus] = useState<any | null>(null);
  const [actions, setActions] = useState<any[]>([]);
  const [sims, setSims] = useState<any[]>([]);
  const [graph, setGraph] = useState<any | null>(null);

  const [busy, setBusy] = useState(false);
  const [scenario, setScenario] = useState("HISTORICAL_REPLAY");
  const [lastConsolidation, setLastConsolidation] = useState<any | null>(null);
  const [ingestText, setIngestText] = useState("");
  const [retrieveQ, setRetrieveQ] = useState("");
  const [retrieved, setRetrieved] = useState<any[] | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [tab, setTab] = useState<"memory" | "policy" | "twin" | "graph" | "ops">("memory");

  const refresh = () => {
    portal.godmode.status().then(setStatus).catch(() => {});
    portal.godmode.actions().then(setActions).catch(() => {});
    portal.godmode.simulations().then(setSims).catch(() => {});
    portal.godmode.graph().then(setGraph).catch(() => {});
  };
  useEffect(() => {
    if (!loggedIn) return;
    refresh();
    const t = setInterval(refresh, 6000);
    return () => clearInterval(t);
  }, [loggedIn]);

  const enabled = !!status?.enabled;
  const mem = status?.memory ?? {};
  const memAct = status?.memAct ?? {};
  const quotas = status?.quotas ?? {};
  const ttls = status?.ttls ?? {};

  const fill = mem.contextFillFraction ?? 0;
  const fillState: StateKey = fill >= 0.9 ? "critical" : fill >= 0.7 ? "warning" : fill > 0 ? "active" : "idle";

  // The engine picks the action with the highest conservative (95% lower) bound,
  // so that is what we surface as "next action" rather than the posterior mean.
  const nextAction = useMemo(() => {
    let best: { key: string; lower: number } | null = null;
    for (const a of MEMACT_ACTIONS) {
      const row = memAct[a.key];
      if (!row) continue;
      const lower = row.lower95 ?? 0;
      if (!best || lower > best.lower) best = { key: a.key, lower };
    }
    return best;
  }, [memAct]);

  const toggle = async () => {
    setBusy(true);
    try {
      setStatus(enabled ? await portal.godmode.disable() : await portal.godmode.enable());
      refresh();
    } finally {
      setBusy(false);
    }
  };

  const setSetting = async (patch: any) => {
    setBusy(true);
    try {
      setStatus(
        await portal.godmode.settings({
          memactEnabled: status?.memactEnabled,
          twinGateEnabled: status?.twinGateEnabled,
          contextBudgetTokens: status?.contextBudgetTokens,
          ...patch,
        })
      );
    } finally {
      setBusy(false);
    }
  };

  const runSimulation = async () => {
    setBusy(true);
    setNote(null);
    try {
      const sim = await portal.godmode.simulate(scenario);
      setSims((prev) => [sim, ...prev]);
      setNote(`${scenario.replace(/_/g, " ").toLowerCase()} → ${sim.verdict}`);
    } catch (e: any) {
      setNote(e?.message ?? "Simulation failed");
    } finally {
      setBusy(false);
    }
  };

  const consolidate = async () => {
    setBusy(true);
    try {
      setLastConsolidation(await portal.godmode.consolidate());
      refresh();
    } finally {
      setBusy(false);
    }
  };

  if (!loggedIn) {
    return (
      <div className="mx-auto mt-16 max-w-md text-center">
        <Micro>Adaptive Policy</Micro>
        <p className="mt-2 text-sm text-slate-400 max-w-2xl leading-relaxed">
          The autonomous memory and policy engine is scoped to your account.
        </p>
        <Link to="/signin" className="mt-4 inline-block rounded border border-edge px-4 py-2 text-sm hover:border-aurora/50">
          Sign in →
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-7">
      {/* ---- header + master control ---- */}
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2.5">
            <Chip glyph="spark" tone="accent" size={28} />
            <h1 className="text-[20px] font-semibold tracking-[-0.011em]">Adaptive Policy</h1>
          </div>
          <p className="mt-0.5 text-sm text-slate-500 max-w-2xl leading-relaxed">
            Self-managing memory · policy proposals gated by digital-twin replay
          </p>
        </div>
        <button
          onClick={toggle}
          disabled={busy}
          className="flex items-center gap-2.5 rounded border px-4 py-2 text-sm transition-colors disabled:opacity-50"
          style={{
            borderColor: enabled ? `${STATE.healthy.color}66` : "rgb(var(--edge))",
            color: enabled ? STATE.healthy.ink : undefined,
          }}
        >
          <StateDot state={enabled ? "healthy" : "idle"} />
          {enabled ? "Engine running" : "Engine off"}
        </button>
      </header>

      {!enabled ? (
        <div className="text-center">
          <Micro>Off by default</Micro>
          <p className="mx-auto mt-2 max-w-md text-xs leading-relaxed text-slate-500">
            Nothing is retained while off. Enabling records gateway exchanges into your account's
            memory tiers only — wipe at any time.
          </p>
        </div>
      ) : (
        <>
          <Tabs tab={tab} setTab={setTab} />

          {tab === "memory" && (
            <>
          {/* ================= 1 · MEMORY ================= */}
          <section>
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <Micro>Memory · context pressure drives every decision</Micro>
              <span className="micro">
                budget {Number(mem.contextBudgetTokens ?? 0).toLocaleString()} tokens
              </span>
            </div>

            {/* the pressure gauge — the causal driver of consolidation */}
            <div className="mt-2 flex flex-wrap items-end gap-6">
              <div className="min-w-[240px] flex-1">
                <div className="flex items-baseline justify-between">
                  <span className="readout text-2xl font-semibold" style={{ color: STATE[fillState].ink }}>
                    {(fill * 100).toFixed(0)}%
                  </span>
                  <span className="readout text-[11px] text-slate-500">
                    {Number(mem.working?.tokens ?? 0).toLocaleString()} /{" "}
                    {Number(mem.contextBudgetTokens ?? 0).toLocaleString()} tokens in working memory
                  </span>
                </div>
                <div className="mt-1.5">
                  <Meter value={fill} state={fillState} height={6} />
                </div>
                <p className="mt-1.5 text-[11px] text-slate-500">
                  {fill >= 0.7
                    ? "Above the comfortable band — the engine is likely to summarise on its next pass."
                    : "Within budget — the engine will defer summarisation."}
                </p>
              </div>

              <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
                <Readout label="Next action" size="sm"
                  value={nextAction ? MEMACT_ACTIONS.find((a) => a.key === nextAction.key)?.label ?? "—" : "—"}
                  state={nextAction ? "active" : "idle"}
                  hint="Chosen by the highest 95% lower bound, not the highest mean" />
                <Readout label="Experience nodes" value={mem.semantic?.nodes ?? 0} size="sm"
                  state={(mem.semantic?.nodes ?? 0) > 0 ? "healthy" : "idle"} />
                <Readout label="Decisions logged" value={actions.length} size="sm" />
              </div>
            </div>

            {/* the cascade */}
            <div className="mt-5">
              <Cascade mem={mem} quotas={quotas} last={lastConsolidation} />
            </div>

            <div className="mt-3 flex flex-wrap items-center gap-x-6 gap-y-1 text-[10px] text-slate-500">
              <span>
                TTL — working {ttls.workingMinutes}m · episodic {ttls.episodicDays}d · semantic{" "}
                {ttls.semanticDays}d
              </span>
              <span>
                Quota — working {quotas.working} · episodic {quotas.episodic} · semantic{" "}
                {quotas.semanticNodes}
              </span>
            </div>
          </section>

            </>
          )}

          {tab === "policy" && (
            <>
          {/* ================= 2 · POLICY ================= */}
          <section className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_340px]">
            <div>
              <div className="flex flex-wrap items-baseline justify-between gap-2">
                <Micro>MemAct policy · what the engine believes about each action</Micro>
                <label className="flex items-center gap-1.5 text-[10px] text-slate-500">
                  <input
                    type="checkbox"
                    checked={!!status?.memactEnabled}
                    onChange={(e) => setSetting({ memactEnabled: e.target.checked })}
                    className="accent-aurora"
                  />
                  MemAct enabled
                </label>
              </div>
              <p className="mt-0.5 text-[10px] text-slate-600">
                bar = 95% lower bound → mean · wider is less proven · chosen on the lower bound
              </p>

              <div className="mt-3 space-y-2.5">
                {MEMACT_ACTIONS.map((a) => {
                  const row = memAct[a.key];
                  const chosen = nextAction?.key === a.key;
                  return (
                    <PosteriorBar
                      key={a.key}
                      label={a.label}
                      desc={a.desc}
                      row={row}
                      chosen={chosen}
                    />
                  );
                })}
              </div>
            </div>

            {/* decision ledger */}
            <div>
              <Micro>Decision ledger</Micro>

              <div className="mt-2 max-h-[340px] space-y-px overflow-y-auto pr-1">
                {actions.map((a) => {
                  const r = a.reward;
                  const st: StateKey = r == null ? "idle" : r > 0.05 ? "healthy" : r < -0.05 ? "critical" : "warning";
                  return (
                    <div key={a.id} className="flex flex-wrap items-baseline gap-x-2 gap-y-0.5 rounded px-2 py-1.5 text-[11px] hover:bg-edge/40">
                      <StateDot state={st} size={5} />
                      <span className="font-medium text-slate-300">{a.action}</span>
                      {a.tier && <span className="text-slate-600">{a.tier}</span>}
                      {r != null && (
                        <span className="readout ml-auto" style={{ color: STATE[st].ink }}>
                          {r > 0 ? "+" : ""}
                          {r.toFixed(2)}
                        </span>
                      )}
                      <span className="readout w-full text-[10px] text-slate-600">
                        {a.detail} · {timeOf(a.createdAt)}
                      </span>
                    </div>
                  );
                })}
                {actions.length === 0 && (
                  <div className="rounded border border-dashed border-edge/60 px-3 py-4 text-[11px] text-slate-500">
                    No autonomous actions yet.
                  </div>
                )}
              </div>
            </div>
          </section>

            </>
          )}

          {tab === "twin" && (
            <>
          {/* ================= 3 · DIGITAL TWIN ================= */}
          <section>
            <div className="flex flex-wrap items-baseline justify-between gap-3">
              <Micro>Digital twin · prove a policy change before it sees traffic</Micro>
              <label className="flex items-center gap-1.5 text-[10px] text-slate-500">
                <input
                  type="checkbox"
                  checked={!!status?.twinGateEnabled}
                  onChange={(e) => setSetting({ twinGateEnabled: e.target.checked })}
                  className="accent-aurora"
                />
                Gate promotions on the twin
              </label>
            </div>
            <p className="mt-0.5 text-[10px] text-slate-600">
              candidate replayed against real recorded traffic · confident regressions are vetoed
            </p>

            <div className="mt-3 flex flex-wrap items-end gap-2">
              <div>
                <Micro>Scenario</Micro>
                <Select
                  value={scenario}
                  onChange={(e) => setScenario(e.target.value)}
                >
                  {SCENARIOS.map((s) => (
                    <option key={s.key} value={s.key}>
                      {s.label}
                    </option>
                  ))}
                </Select>
              </div>
              <span className="pb-2 text-[10px] text-slate-500">
                {SCENARIOS.find((s) => s.key === scenario)?.desc}
              </span>
              <button
                onClick={runSimulation}
                disabled={busy}
                className="ml-auto rounded bg-[color:var(--accent-strong)] px-4 py-2 text-sm font-medium text-white transition-colors hover:opacity-90 disabled:opacity-50"
              >
                {busy ? "Replaying…" : "Run simulation"}
              </button>
            </div>
            {note && <p className="mt-2 text-[11px] text-slate-400">{note}</p>}

            <div className="mt-4 space-y-3">
              {sims.map((s) => (
                <SimulationRow key={s.id} sim={s} />
              ))}
              {sims.length === 0 && (
                <div className="text-center text-[11px] text-slate-500">
                  No simulations yet. A replay needs recorded gateway traffic to draw from.
                </div>
              )}
            </div>
          </section>

            </>
          )}

          {tab === "graph" && (
            <>
          {/* ================= 4 · EXPERIENCE GRAPH ================= */}
          <section>
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <Micro>Experience graph · what generalised into reusable knowledge</Micro>
              <span className="micro">node size is utility · ring is times used</span>
            </div>
            {(graph?.nodes ?? []).length === 0 ? (
              <div className="mt-2 text-center text-[11px] text-slate-500">
                Nothing distilled yet — episodes promote once repeatedly useful.
              </div>
            ) : (
              <div className="mt-2 grid-field rounded-lg border border-edge/60">
                <ExperienceGraph graph={graph} />
              </div>
            )}
          </section>

            </>
          )}

          {tab === "ops" && (
            <>
          {/* ================= operations ================= */}
          <section>
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Operations</h2>
            <div className="mt-2 grid gap-4 lg:grid-cols-3">
              <div>
                <span className="text-[11px] text-slate-400">Ingest a turn</span>
                <div className="mt-1 flex gap-2">
                  <input
                    value={ingestText}
                    onChange={(e) => setIngestText(e.target.value)}
                    placeholder="text to remember"
                    className="min-w-0 flex-1 field"
                  />
                  <button
                    disabled={!ingestText}
                    onClick={() =>
                      portal.godmode.ingest("manual", "user", ingestText).then(() => {
                        setIngestText("");
                        refresh();
                      })
                    }
                    className="rounded border border-edge px-3 py-1.5 text-xs hover:border-aurora/50 disabled:opacity-40"
                  >
                    Ingest
                  </button>
                </div>
              </div>

              <div>
                <span className="text-[11px] text-slate-400">Retrieve</span>
                <div className="mt-1 flex gap-2">
                  <input
                    value={retrieveQ}
                    onChange={(e) => setRetrieveQ(e.target.value)}
                    placeholder="query the memory"
                    className="min-w-0 flex-1 field"
                  />
                  <button
                    disabled={!retrieveQ}
                    onClick={() => portal.godmode.retrieve(retrieveQ).then(setRetrieved)}
                    className="rounded border border-edge px-3 py-1.5 text-xs hover:border-aurora/50 disabled:opacity-40"
                  >
                    Search
                  </button>
                </div>
              </div>

              <div className="flex items-end gap-2">
                <button
                  onClick={consolidate}
                  disabled={busy}
                  className="rounded border border-edge px-3 py-1.5 text-xs hover:border-aurora/50 disabled:opacity-50"
                >
                  Run consolidation
                </button>
                <button
                  onClick={() => {
                    if (confirm("Wipe every memory tier for your account? This cannot be undone.")) {
                      portal.godmode.wipe().then(() => {
                        setRetrieved(null);
                        setLastConsolidation(null);
                        refresh();
                      });
                    }
                  }}
                  className="rounded border px-3 py-1.5 text-xs transition-colors"
                  style={{ borderColor: `${STATE.critical.color}55`, color: STATE.critical.ink }}
                >
                  Wipe memory
                </button>
              </div>
            </div>

            {retrieved && (
              <div className="mt-3">
                <Micro>Retrieved · ranked by relevance</Micro>
                <div className="mt-1.5 space-y-1">
                  {retrieved.map((r: any, i: number) => (
                    <div key={i} className="flex items-start gap-2 rounded px-2 py-1.5 text-[11px] hover:bg-edge/40">
                      <span className="micro w-16 shrink-0">{r.tier}</span>
                      <span className="min-w-0 flex-1 text-slate-400">{r.text}</span>
                      {r.score != null && (
                        <span className="readout shrink-0 text-slate-500">{Number(r.score).toFixed(3)}</span>
                      )}
                    </div>
                  ))}
                  {retrieved.length === 0 && (
                    <div className="text-[11px] text-slate-500">No matches in memory.</div>
                  )}
                </div>
              </div>
            )}
          </section>
            </>
          )}

        </>
      )}
    </div>
  );
}

/**
 * Section switcher. The engine has four distinct stories plus its controls;
 * stacking all of them on one scroll made the page unreadable, so only one is
 * shown at a time.
 */
function Tabs({
  tab,
  setTab,
}: {
  tab: string;
  setTab: (t: any) => void;
}) {
  const items = [
    ["memory", "Memory"],
    ["policy", "Policy"],
    ["twin", "Digital twin"],
    ["graph", "Experience"],
    ["ops", "Controls"],
  ] as const;
  return (
    <div className="flex flex-wrap gap-1 border-b border-edge/60">
      {items.map(([k, label]) => (
        <button
          key={k}
          onClick={() => setTab(k)}
          className="-mb-px border-b-2 px-3 py-2 text-xs font-medium transition-colors"
          style={{
            borderColor: tab === k ? STATE.active.color : "transparent",
            color: tab === k ? "rgb(226 232 240)" : undefined,
          }}
        >
          {label}
        </button>
      ))}
    </div>
  );
}

/**
 * The tier cascade. Block width is that tier's occupancy against its quota, so
 * a tier approaching its limit is visibly full. Arrows carry the transition
 * counts from the last consolidation when one has been run.
 */
function Cascade({ mem, quotas, last }: { mem: any; quotas: any; last: any | null }) {
  const occupancy = (key: string) => {
    if (key === "working") return { n: mem.working?.items ?? 0, cap: quotas.working ?? 0 };
    if (key === "episodic") return { n: mem.episodic?.items ?? 0, cap: quotas.episodic ?? 0 };
    if (key === "semantic") return { n: mem.semantic?.nodes ?? 0, cap: quotas.semanticNodes ?? 0 };
    return { n: mem.archive?.spans ?? 0, cap: 0 };
  };
  const transitions = [
    { label: "summarise", n: last?.summarized },
    { label: "promote", n: last?.promoted },
    { label: "archive", n: last?.archived },
  ];

  return (
    <div className="flex flex-col gap-2 lg:flex-row lg:items-stretch">
      {TIERS.map((t, i) => {
        const { n, cap } = occupancy(t.key);
        const frac = cap ? Math.min(1, n / cap) : 0;
        const st: StateKey = cap && frac >= 0.9 ? "warning" : n > 0 ? "active" : "idle";
        return (
          <div key={t.key} className="flex flex-1 items-stretch gap-2">
            <div className="min-w-0 flex-1">
              <div className="h-full">
                <div className="flex items-baseline justify-between">
                  <span className="text-[11px] font-medium text-slate-300">{t.label}</span>
                  <span className="readout text-lg font-semibold" style={{ color: STATE[st].ink }}>
                    {n}
                  </span>
                </div>
                <div className="mt-1.5">
                  {cap ? (
                    <>
                      <Meter value={frac} state={st} height={3} />
                      <div className="mt-1 text-[9px] text-slate-600">
                        {n} / {cap} {t.unit}
                      </div>
                    </>
                  ) : (
                    <div className="text-[9px] text-slate-600">{n} {t.unit} · unbounded</div>
                  )}
                </div>
                <p className="mt-1.5 text-[10px] leading-snug text-slate-600">{t.desc}</p>
              </div>
            </div>

            {i < TIERS.length - 1 && (
              <div className="flex shrink-0 flex-col items-center justify-center px-0.5">
                <span className="text-slate-700">→</span>
                <span className="mt-0.5 whitespace-nowrap text-[8px] uppercase tracking-wider text-slate-600">
                  {transitions[i].label}
                </span>
                {transitions[i].n != null && (
                  <span
                    className="readout text-[10px] font-semibold"
                    style={{ color: transitions[i].n! > 0 ? STATE.healthy.ink : "#5A6478" }}
                  >
                    {transitions[i].n}
                  </span>
                )}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

/**
 * One MemAct action's posterior, drawn as the interval the engine reasons over:
 * the bar runs from the conservative 95% lower bound to the posterior mean.
 */
function PosteriorBar({
  label,
  desc,
  row,
  chosen,
}: {
  label: string;
  desc: string;
  row: any;
  chosen: boolean;
}) {
  const observed = row && (row.successes ?? 0) + (row.failures ?? 0) > 0;
  const mean = row?.posteriorMean ?? 0;
  const lower = row?.lower95 ?? 0;
  const color = chosen ? STATE.active.color : observed ? STATE.healthy.color : STATE.idle.color;

  return (
    <div
      className="rounded px-2 py-2 transition-colors"
      style={chosen ? { background: `${STATE.active.color}12` } : undefined}
    >
      <div className="flex flex-wrap items-baseline gap-2">
        <span className="text-[11px] font-medium text-slate-300">{label}</span>
        {chosen && (
          <span className="rounded px-1.5 py-0.5 text-[9px] font-semibold"
            style={{ background: `${STATE.active.color}22`, color: STATE.active.ink }}>
            next
          </span>
        )}
        <span className="ml-auto readout text-[10px] text-slate-500">
          {observed ? `${row.successes}✓ / ${row.failures}✗` : "no observations"}
        </span>
      </div>

      <div className="relative mt-1.5 h-2 w-full overflow-hidden rounded-full bg-edge">
        {/* the interval the engine reasons over */}
        <div
          className="absolute inset-y-0 rounded-full transition-all duration-500"
          style={{
            left: `${lower * 100}%`,
            width: `${Math.max(0, (mean - lower) * 100)}%`,
            background: `${color}66`,
          }}
        />
        {/* the conservative bound it actually decides on */}
        <div className="absolute inset-y-0 w-0.5" style={{ left: `${lower * 100}%`, background: color }} />
      </div>

      <div className="mt-1 flex items-baseline justify-between text-[9px] text-slate-600">
        <span>{desc}</span>
        <span className="readout">
          lower {(lower * 100).toFixed(0)}% · mean {(mean * 100).toFixed(0)}%
        </span>
      </div>
    </div>
  );
}

/** Baseline vs candidate for one twin replay, with the verdict. */
function SimulationRow({ sim }: { sim: any }) {
  const parse = (s: string | null) => {
    try {
      return s ? JSON.parse(s) : null;
    } catch {
      return null;
    }
  };
  const base = parse(sim.baselineMetricsJson);
  const cand = parse(sim.candidateMetricsJson);
  const st = verdictState(sim.verdict);

  const rate = (m: any) => {
    const t = (m?.successes ?? 0) + (m?.failures ?? 0);
    return t ? (m.successes ?? 0) / t : 0;
  };

  const rows = (base && cand
    ? [
        { label: "Success rate", b: rate(base), c: rate(cand), fmt: (v: number) => `${(v * 100).toFixed(1)}%`, higherBetter: true },
        { label: "Avg latency", b: base.avgLatencyMs ?? 0, c: cand.avgLatencyMs ?? 0, fmt: (v: number) => `${v.toFixed(0)}ms`, higherBetter: false },
        { label: "Avg cost", b: base.avgCostUsd ?? 0, c: cand.avgCostUsd ?? 0, fmt: (v: number) => `$${v.toFixed(6)}`, higherBetter: false },
      ]
    : []
  ).filter((r) => r.b > 0 || r.c > 0);

  return (
    <div>
      <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
        <StateDot state={st} />
        <span className="text-sm font-semibold" style={{ color: STATE[st].ink }}>
          {sim.verdict}
        </span>
        <span className="micro">{String(sim.scenario).replace(/_/g, " ").toLowerCase()}</span>
        <span className="readout text-[10px] text-slate-500">
          {Number(sim.replayedRequests ?? 0).toLocaleString()} replayed ·{" "}
          {((sim.confidence ?? 0) * 100).toFixed(0)}% confidence
        </span>
        <span className="ml-auto readout text-[10px] text-slate-600">
          {timeOf(sim.createdAt)}
        </span>
      </div>

      {sim.reason && <p className="mt-1.5 text-[11px] leading-relaxed text-slate-400">{sim.reason}</p>}

      {rows.length > 0 && (
        <div className="mt-3 space-y-2">
          {rows.map((r) => {
            const max = Math.max(r.b, r.c, 1e-9);
            const better = r.higherBetter ? r.c > r.b : r.c < r.b;
            const changed = Math.abs(r.c - r.b) > 1e-9;
            return (
              <div key={r.label} className="grid grid-cols-[92px_minmax(0,1fr)_auto] items-center gap-2 text-[10px]">
                <span className="micro">{r.label}</span>
                <div className="space-y-1">
                  <Bar frac={r.b / max} color="#5A6478" caption={`baseline ${r.fmt(r.b)}`} />
                  <Bar
                    frac={r.c / max}
                    color={changed ? (better ? STATE.healthy.color : STATE.critical.color) : STATE.active.color}
                    caption={`candidate ${r.fmt(r.c)}`}
                  />
                </div>
                <span
                  className="readout w-14 text-right"
                  style={{
                    color: !changed ? "#5A6478" : better ? STATE.healthy.ink : STATE.critical.ink,
                  }}
                >
                  {!changed ? "—" : `${better ? "better" : "worse"}`}
                </span>
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
}

function Bar({ frac, color, caption }: { frac: number; color: string; caption: string }) {
  return (
    <div className="flex items-center gap-2">
      <span className="relative h-1.5 flex-1 overflow-hidden rounded-full bg-edge">
        <span
          className="absolute inset-y-0 left-0 rounded-full transition-[width] duration-500"
          style={{ width: `${Math.max(0, Math.min(1, frac)) * 100}%`, background: color }}
        />
      </span>
      <span className="w-36 shrink-0 text-slate-500">{caption}</span>
    </div>
  );
}

/**
 * Experience graph. Nodes are laid out on a utility spiral — the most useful
 * knowledge sits at the centre — because utility is the property that decides
 * what survives pruning.
 */
function ExperienceGraph({ graph }: { graph: any }) {
  const W = 1000;
  const nodes: any[] = graph?.nodes ?? [];
  const edges: any[] = graph?.edges ?? [];
  const [sel, setSel] = useState<number | null>(null);

  // The spiral only needs as much room as it actually uses; a tall empty field
  // around a handful of nodes reads as a rendering fault rather than as a small
  // graph.
  const spiralRadius = nodes.length <= 1 ? 0 : 26 + Math.sqrt(nodes.length - 1) * 46;
  const H = Math.round(Math.max(150, Math.min(420, spiralRadius * 1.35 + 120)));

  const laid = useMemo(() => {
    const sorted = [...nodes].sort((a, b) => (b.utility ?? 0) - (a.utility ?? 0));
    const cx = W / 2;
    const cy = H / 2;
    return sorted.map((n, i) => {
      // Golden-angle spiral: rank by utility, highest nearest the centre.
      const a = i * 2.399963;
      const r = i === 0 ? 0 : 26 + Math.sqrt(i) * 46;
      return { ...n, x: cx + Math.cos(a) * r, y: cy + Math.sin(a) * r * 0.62 };
    });
  }, [nodes]);

  const posOf = useMemo(() => {
    const m = new Map<number, { x: number; y: number }>();
    laid.forEach((n) => m.set(n.id, { x: n.x, y: n.y }));
    return m;
  }, [laid]);

  const selected = laid.find((n) => n.id === sel) ?? null;
  const maxUses = Math.max(...nodes.map((n) => n.uses ?? 0), 1);

  return (
    <div className="grid gap-3 lg:grid-cols-[minmax(0,1fr)_260px]">
      <svg viewBox={`0 0 ${W} ${H}`} className="w-full" style={{ maxHeight: H }} onClick={() => setSel(null)}>
        {edges.map((e, i) => {
          const a = posOf.get(e.from);
          const b = posOf.get(e.to);
          if (!a || !b) return null;
          const dim = sel !== null && sel !== e.from && sel !== e.to;
          return (
            <line
              key={i}
              x1={a.x} y1={a.y} x2={b.x} y2={b.y}
              stroke={STATE.active.color}
              strokeOpacity={dim ? 0.05 : 0.12 + (e.weight ?? 0) * 0.35}
              strokeWidth={0.6 + (e.weight ?? 0) * 1.6}
            />
          );
        })}
        {laid.map((n) => {
          const util = Math.max(0, Math.min(1, n.utility ?? 0));
          const r = 7 + util * 15;
          const isSel = sel === n.id;
          const dim = sel !== null && !isSel;
          return (
            <g
              key={n.id}
              onClick={(ev) => {
                ev.stopPropagation();
                setSel(isSel ? null : n.id);
              }}
              className="cursor-pointer"
              opacity={dim ? 0.25 : 1}
              style={{ transition: "opacity 300ms" }}
            >
              <circle cx={n.x} cy={n.y} r={r} fill={STATE.active.color}
                fillOpacity={0.12 + util * 0.3} stroke={STATE.active.color}
                strokeOpacity={isSel ? 0.95 : 0.5} strokeWidth={isSel ? 1.8 : 1} />
              {/* usage ring — how often this knowledge has actually been reused */}
              {(n.uses ?? 0) > 0 && (
                <circle
                  cx={n.x} cy={n.y} r={r + 3.5} fill="none"
                  stroke={STATE.healthy.color} strokeWidth={1.4} strokeLinecap="round"
                  strokeDasharray={`${(2 * Math.PI * (r + 3.5) * ((n.uses ?? 0) / maxUses)).toFixed(1)} 999`}
                  transform={`rotate(-90 ${n.x} ${n.y})`}
                />
              )}
            </g>
          );
        })}
      </svg>

      <aside className="p-3">
        <Micro>Node</Micro>
        {selected ? (
          <div className="settle mt-2 space-y-2">
            <span className="micro">{selected.kind}</span>
            <p className="text-[11px] leading-relaxed text-slate-300">{selected.text}</p>
            <div className="flex items-baseline justify-between border-b border-edge/40 pb-1">
              <span className="micro">Utility</span>
              <span className="readout text-xs text-slate-200">{Number(selected.utility ?? 0).toFixed(3)}</span>
            </div>
            <div className="flex items-baseline justify-between border-b border-edge/40 pb-1">
              <span className="micro">Times used</span>
              <span className="readout text-xs text-slate-200">{selected.uses ?? 0}</span>
            </div>
          </div>
        ) : (
          <p className="mt-2 text-[11px] leading-relaxed text-slate-500">
            {nodes.length} node{nodes.length === 1 ? "" : "s"}, {edges.length} relation
            {edges.length === 1 ? "" : "s"}. Select one to read it.
          </p>
        )}
      </aside>
    </div>
  );
}
