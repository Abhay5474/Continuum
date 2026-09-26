import { useEffect, useMemo, useState } from "react";
import { api } from "../api";
import { BarChart, ChartFrame, StackedBar, type Datum } from "../system/charts";
import { Empty, Pill, Segmented } from "../system/hub";
import { Input, Labelled, Select, Table, TD, TH, TR } from "../system/controls";
import { SwitchThumb } from "../system/primitives";
import { useToast } from "./ui";
import { dateTimeOf } from "../system/time";

/**
 * The routing views that had an endpoint but no screen: the decision log, the
 * bandit's what-if ranking, and the hedging policy itself (only its on/off and
 * its metrics were reachable before).
 */

type Decision = {
  id: number;
  workflowId: string | null;
  mode: string;
  complexity: number;
  chosenProvider: string | null;
  chain: string | null;
  scoresJson: string | null;
  createdAt: unknown;
};

/* ------------------------------------------------------------------ *
 * Decisions
 * ------------------------------------------------------------------ */

export function DecisionsLog({ colorOf }: { colorOf: (provider: string) => string }) {
  const [rows, setRows] = useState<Decision[] | null>(null);
  const [limit, setLimit] = useState("100");
  const [open, setOpen] = useState<number | null>(null);

  useEffect(() => {
    api.get<Decision[]>(`/api/routing/decisions?limit=${limit}`).then(setRows).catch(() => setRows([]));
  }, [limit]);

  const share: Datum[] = useMemo(() => {
    const m = new Map<string, number>();
    (rows ?? []).forEach((r) => m.set(r.chosenProvider ?? "none", (m.get(r.chosenProvider ?? "none") ?? 0) + 1));
    return [...m.entries()]
      .sort((a, b) => b[1] - a[1])
      .map(([k, v]) => ({ key: k, label: k, value: v, color: colorOf(k) }));
  }, [rows, colorOf]);

  // Complexity bands: where the router spent its strong models.
  const bands: Datum[] = useMemo(() => {
    const edges = [0, 0.2, 0.4, 0.6, 0.8, 1.0001];
    return edges.slice(0, -1).map((lo, i) => {
      const hi = edges[i + 1];
      const n = (rows ?? []).filter((r) => r.complexity >= lo && r.complexity < hi).length;
      return { key: String(i), label: `${lo.toFixed(1)}–${Math.min(1, hi).toFixed(1)}`, value: n };
    });
  }, [rows]);

  if (rows === null) return <p className="text-sm text-slate-500">Loading decisions…</p>;

  return (
    <div className="space-y-6">
      <div className="flex flex-wrap items-center justify-between gap-3">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Routing decisions · newest first</h2>
        <Segmented
          value={limit}
          onChange={setLimit}
          options={[
            { value: "50", label: "50" },
            { value: "100", label: "100" },
            { value: "500", label: "500" },
          ]}
        />
      </div>

      {rows.length === 0 ? (
        <Empty
          title="No routing decisions yet"
          glyph="route"
          hint="Each gateway request the router scores is recorded here: its complexity, the provider chosen and the failover chain behind it. Turn the router on and send a request."
        />
      ) : (
        <>
          <div className="grid items-start gap-4 lg:grid-cols-2">
            <ChartFrame title="Where requests went" data={share} valueLabel="Requests">
              <StackedBar data={share} />
            </ChartFrame>
            <ChartFrame title="Complexity of routed requests" caption="0 = simple, 1 = needs the strongest model" data={bands} valueLabel="Requests">
              <BarChart data={bands} />
            </ChartFrame>
          </div>
          <Table
            minWidth={720}
            maxHeight={520}
            head={
              <tr>
                <TH>When</TH>
                <TH>Mode</TH>
                <TH width={160}>Complexity</TH>
                <TH>Chosen</TH>
                <TH>Failover chain</TH>
                <TH align="right">Scores</TH>
              </tr>
            }
          >
            {rows.map((r) => {
              const scores = parseScores(r.scoresJson);
              return (
                <TR key={r.id}>
                  <TD>
                    <span className="whitespace-nowrap text-slate-400">{dateTimeOf(r.createdAt)}</span>
                  </TD>
                  <TD>
                    <Pill tone="mute">{r.mode}</Pill>
                  </TD>
                  <TD>
                    <span className="flex items-center gap-2">
                      <span className="relative h-1.5 w-20 overflow-hidden rounded-full" style={{ background: "rgb(var(--card-rule))" }} aria-hidden>
                        <span className="grow-x absolute inset-y-0 left-0 rounded-full" style={{ width: `${Math.min(1, r.complexity) * 100}%`, background: "var(--series-1)" }} />
                      </span>
                      <span className="readout text-[12px]">{r.complexity.toFixed(2)}</span>
                    </span>
                  </TD>
                  <TD>
                    <span className="inline-flex items-center gap-1.5 font-medium text-slate-200">
                      <span className="h-2 w-2 rounded-sm" style={{ background: colorOf(r.chosenProvider ?? "") }} aria-hidden />
                      {r.chosenProvider ?? "—"}
                    </span>
                  </TD>
                  <TD>
                    <span className="text-[12px] text-slate-400">{(r.chain ?? "").split(",").filter(Boolean).join(" → ") || "—"}</span>
                  </TD>
                  <TD numeric>
                    {scores.length > 0 ? (
                      <button
                        onClick={() => setOpen(open === r.id ? null : r.id)}
                        aria-expanded={open === r.id}
                        className="text-[12px] text-slate-400 underline-offset-2 hover:text-slate-200 hover:underline"
                      >
                        {open === r.id ? "hide" : `${scores.length} scored`}
                      </button>
                    ) : (
                      "—"
                    )}
                    {open === r.id && (
                      <div className="rise-in mt-2 min-w-[220px] text-left">
                        <BarChart data={scores.map((s) => ({ ...s, color: colorOf(s.key) }))} height={14} />
                      </div>
                    )}
                  </TD>
                </TR>
              );
            })}
          </Table>
        </>
      )}
    </div>
  );
}

function parseScores(json: string | null): Datum[] {
  if (!json) return [];
  try {
    const v = JSON.parse(json);
    const entries: [string, number][] = Array.isArray(v)
      ? v.map((x: any) => [String(x.provider ?? x.name ?? "?"), Number(x.totalScore ?? x.score ?? x.value ?? 0)])
      : Object.entries(v).map(([k, x]: [string, any]) => [k, typeof x === "number" ? x : Number(x?.score ?? x?.total ?? 0)]);
    return entries
      .filter(([, n]) => Number.isFinite(n))
      .sort((a, b) => b[1] - a[1])
      .map(([k, n]) => ({ key: k, label: k, value: Math.round(n * 1000) / 1000 }));
  } catch {
    return [];
  }
}

/* ------------------------------------------------------------------ *
 * Bandit what-if
 * ------------------------------------------------------------------ */

export function BanditWhatIf({ providers, colorOf }: { providers: string[]; colorOf: (p: string) => string }) {
  const pool = providers.length ? providers : ["gemini", "groq", "openai"];
  const [complexity, setComplexity] = useState(0.5);
  const [w, setW] = useState({ quality: 0.6, cost: 0.2, latency: 0.2 });
  const [out, setOut] = useState<{ context: string; ranking: { provider: string; score: number; meanSuccess: number; observations: number }[] } | null>(null);
  const [err, setErr] = useState<string | null>(null);

  useEffect(() => {
    const t = setTimeout(() => {
      const q = new URLSearchParams({
        complexity: String(complexity),
        providers: pool.join(","),
        wQuality: String(w.quality),
        wCost: String(w.cost),
        wLatency: String(w.latency),
      });
      api
        .get<any>(`/api/routing/bandit/suggest?${q}`)
        .then((r) => {
          setOut(r);
          setErr(null);
        })
        .catch((e) => setErr(e?.message ?? "request failed"));
    }, 180);
    return () => clearTimeout(t);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [complexity, w.quality, w.cost, w.latency, pool.join(",")]);

  const data: Datum[] = (out?.ranking ?? []).map((r) => ({
    key: r.provider,
    label: r.provider,
    value: Math.round(r.score * 1000) / 1000,
    color: colorOf(r.provider),
    hint: `${r.observations} observation${r.observations === 1 ? "" : "s"} · mean success ${(r.meanSuccess * 100).toFixed(0)}%`,
  }));

  const slider = (label: string, value: number, set: (v: number) => void) => (
    <label className="block">
      <span className="flex items-baseline justify-between">
        <span className="micro">{label}</span>
        <span className="readout text-[12px] text-slate-300">{value.toFixed(2)}</span>
      </span>
      <input type="range" min={0} max={1} step={0.05} value={value} onChange={(e) => set(Number(e.target.value))} className="mt-1 w-full accent-[color:var(--accent)]" />
    </label>
  );

  return (
    <section className="plane p-5">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">What would the bandit pick?</h2>
        {out && <Pill tone="info">context · {out.context.toLowerCase()}</Pill>}
      </div>
      <p className="mt-0.5 text-[12px] text-slate-500">Rank providers for a request of a given complexity, under your own weighting. Nothing is sent.</p>
      <div className="mt-4 grid gap-6 lg:grid-cols-[minmax(0,280px)_minmax(0,1fr)]">
        <div className="space-y-3">
          {slider("Request complexity", complexity, setComplexity)}
          {slider("Weight · quality", w.quality, (v) => setW({ ...w, quality: v }))}
          {slider("Weight · cost", w.cost, (v) => setW({ ...w, cost: v }))}
          {slider("Weight · latency", w.latency, (v) => setW({ ...w, latency: v }))}
        </div>
        <div>
          {err ? <p className="text-sm" style={{ color: "var(--state-critical-ink)" }}>{err}</p> : <BarChart data={data} />}
          {data.length > 0 && (
            <p className="mt-2 text-[11.5px] text-slate-500">
              Top pick <b className="text-slate-300">{data[0].label}</b>
              {out?.ranking.every((r) => r.observations === 0) ? " · no observations yet, so this is the prior" : ""}
            </p>
          )}
        </div>
      </div>
    </section>
  );
}

/* ------------------------------------------------------------------ *
 * Hedging policy
 * ------------------------------------------------------------------ */

export function HedgingPolicy({ state, operator, onSaved }: { state: any; operator: boolean; onSaved: () => void }) {
  const toast = useToast();
  const [adaptive, setAdaptive] = useState<boolean>(!!state?.adaptive);
  const [thresholdMs, setThreshold] = useState(String(state?.thresholdMs ?? 800));
  const [maxHedges, setMaxHedges] = useState(String(state?.maxHedges ?? 1));
  const [budget, setBudget] = useState(state?.perRequestBudgetUsd && state.perRequestBudgetUsd !== "none" ? String(state.perRequestBudgetUsd) : "");
  const [cap, setCap] = useState(String(state?.hedgeRateCap ?? 0.05));
  const [minMs, setMinMs] = useState(String(state?.minThresholdMs ?? 50));
  const [busy, setBusy] = useState(false);

  // Follow the server until the operator starts editing.
  const [dirty, setDirty] = useState(false);
  useEffect(() => {
    if (dirty || !state) return;
    setAdaptive(!!state.adaptive);
    setThreshold(String(state.thresholdMs));
    setMaxHedges(String(state.maxHedges));
    setBudget(state.perRequestBudgetUsd && state.perRequestBudgetUsd !== "none" ? String(state.perRequestBudgetUsd) : "");
    setCap(String(state.hedgeRateCap));
    setMinMs(String(state.minThresholdMs));
  }, [state, dirty]);

  const edit = <T,>(set: (v: T) => void) => (v: T) => {
    setDirty(true);
    set(v);
  };

  const save = async () => {
    setBusy(true);
    try {
      const q = new URLSearchParams({ thresholdMs, maxHedges });
      if (budget.trim()) q.set("budgetUsd", budget.trim());
      if (adaptive) {
        q.set("adaptive", "true");
        q.set("hedgeRateCap", cap);
        q.set("minThresholdMs", minMs);
        await api.opPost(`/api/hedging/policy/adaptive?${q}`);
      } else {
        await api.opPost(`/api/hedging/policy?${q}`);
      }
      toast("Hedging policy saved", "success");
      setDirty(false);
      onSaved();
    } catch (e: any) {
      toast(e?.message ?? "Policy was not saved", "error");
    } finally {
      setBusy(false);
    }
  };

  const off = !operator;
  return (
    <div className="mt-5 rounded-[var(--r-lg)] border border-edge/70 p-4">
      <div className="flex flex-wrap items-center gap-3">
        <h3 className="text-[12.5px] font-semibold text-slate-200">Policy</h3>
        <span className="ml-auto flex items-center gap-2 text-[12px] text-slate-400">
          <SwitchThumb checked={adaptive} disabled={off} label="Adaptive trigger" onChange={edit(setAdaptive)} />
          {adaptive ? "Adaptive · fires past the live p95" : "Fixed trigger"}
        </span>
      </div>
      <div className="mt-3 grid grid-cols-2 gap-3 sm:grid-cols-3">
        <Labelled label={adaptive ? "Starting trigger (ms)" : "Trigger (ms)"}>
          <Input type="number" min={1} value={thresholdMs} disabled={off} onChange={(e) => edit(setThreshold)(e.target.value)} />
        </Labelled>
        <Labelled label="Max hedges">
          <Select value={maxHedges} disabled={off} onChange={(e) => edit(setMaxHedges)(e.target.value)}>
            {[1, 2, 3].map((n) => (
              <option key={n}>{n}</option>
            ))}
          </Select>
        </Labelled>
        <Labelled label="Budget per request ($)">
          <Input type="number" min={0} step="0.0001" placeholder="none" value={budget} disabled={off} onChange={(e) => edit(setBudget)(e.target.value)} />
        </Labelled>
        {adaptive && (
          <>
            <Labelled label="Hedge-rate cap">
              <Input type="number" min={0} max={1} step="0.01" value={cap} disabled={off} onChange={(e) => edit(setCap)(e.target.value)} />
            </Labelled>
            <Labelled label="Trigger floor (ms)">
              <Input type="number" min={1} value={minMs} disabled={off} onChange={(e) => edit(setMinMs)(e.target.value)} />
            </Labelled>
          </>
        )}
      </div>
      <div className="mt-3 flex items-center justify-end gap-2">
        {dirty && (
          <button onClick={() => setDirty(false)} className="text-[12px] text-slate-500 hover:text-slate-300">
            Discard
          </button>
        )}
        <button
          onClick={save}
          disabled={off || busy || !dirty}
          title={off ? "Engine-wide setting — unlock operator access to change it" : undefined}
          className="rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-3.5 py-1.5 text-[12.5px] font-medium text-white hover:opacity-90 disabled:opacity-50"
        >
          {busy ? "Saving…" : "Save policy"}
        </button>
      </div>
    </div>
  );
}
