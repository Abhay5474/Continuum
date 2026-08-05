import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { api, portal } from "../api";
import { Micro, Readout, StateDot } from "../system/primitives";
import { STATE, type StateKey } from "../system/tokens";
import Tabs from "../system/Tabs";
import { Morph } from "../system/motion";
import { timeOf } from "../system/time";

/**
 * Author and run durable workflows.
 *
 * A definition is a graph of steps the engine interprets, so a developer gets
 * crash-safe execution, retries and exactly-once side effects without compiling
 * anything into the server. This page is where that graph is written, validated,
 * published and run.
 */

const TABS = [
  ["definitions", "Definitions"],
  ["editor", "Editor"],
  ["runs", "Runs"],
] as const;

const STARTER = `{
  "description": "Reserve stock and charge in parallel, then confirm",
  "steps": [
    {
      "id": "reserve",
      "call": {
        "method": "POST",
        "url": "https://api.example.com/reserve",
        "body": { "sku": "\${input.sku}", "qty": "\${input.qty}" }
      },
      "retries": 3,
      "timeoutSeconds": 30
    },
    {
      "id": "charge",
      "call": {
        "method": "POST",
        "url": "https://api.example.com/charge",
        "body": { "amount": "\${input.amount}" }
      }
    },
    {
      "id": "cool-off",
      "type": "WAIT",
      "waitSeconds": 5,
      "dependsOn": ["charge"]
    },
    {
      "id": "confirm",
      "dependsOn": ["reserve", "cool-off"],
      "condition": "\${steps.charge.paid} == true",
      "call": {
        "method": "POST",
        "url": "https://api.example.com/confirm",
        "body": { "hold": "\${steps.reserve.holdId}" }
      }
    }
  ],
  "onComplete": { "url": "https://api.example.com/webhooks/done" }
}`;

type Step = {
  id: string;
  type?: string;
  dependsOn?: string[];
  condition?: string;
  waitSeconds?: number;
  call?: { url?: string; method?: string };
};

/** What a finished run actually did, read back from its own recorded history. */
type RunFacts = {
  definition?: string;
  version?: number;
  steps: { id: string; outcome: "ok" | "skipped" }[];
  callback: boolean;
  durationMs: number | null;
  error: string | null;
};

export default function WorkflowBuilder() {
  const [tab, setTab] = useState<(typeof TABS)[number][0]>("definitions");
  const [defs, setDefs] = useState<any[]>([]);
  const [runs, setRuns] = useState<any[]>([]);
  const [facts, setFacts] = useState<Record<string, RunFacts>>({});
  const factsRef = useRef<Record<string, RunFacts>>({});

  const [name, setName] = useState("my-workflow");
  const [source, setSource] = useState(STARTER);
  const [error, setError] = useState<string | null>(null);
  const [note, setNote] = useState<string | null>(null);
  const [runInput, setRunInput] = useState('{ "sku": "WIDGET-1", "qty": 2, "amount": 4999 }');
  const [busy, setBusy] = useState(false);

  const refresh = () => {
    portal.defs.list().then(setDefs).catch(() => {});
    api
      .workflows()
      .then((w) => {
        const mine = w.filter((x: any) => x.workflowType === "Declarative");
        setRuns(mine);
        // A summary row says "17 events", which tells a developer nothing. What
        // each step returned is already in the run's own history, so read it
        // back for the rows on screen. Settled runs are read once and cached;
        // in-flight ones are re-read because their answer is still changing.
        mine.slice(0, 20).forEach((r: any) => {
          if (factsRef.current[r.workflowId] && r.status !== "RUNNING") return;
          api
            .workflow(r.workflowId)
            .then((d) => {
              const f = readFacts(d);
              factsRef.current = { ...factsRef.current, [r.workflowId]: f };
              setFacts(factsRef.current);
            })
            .catch(() => {});
        });
      })
      .catch(() => {});
  };
  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 5000);
    return () => clearInterval(t);
  }, []);

  // Parsed locally so the graph preview and errors are immediate, before publish.
  const parsed = useMemo(() => {
    try {
      const o = JSON.parse(source);
      return { spec: o as { steps?: Step[]; onComplete?: any }, err: null as string | null };
    } catch (e: any) {
      return { spec: null, err: e.message as string };
    }
  }, [source]);

  const layers = useMemo(() => computeLayers(parsed.spec?.steps ?? []), [parsed.spec]);

  const inputValid = useMemo(() => {
    if (!runInput.trim()) return true;
    try {
      JSON.parse(runInput);
      return true;
    } catch {
      return false;
    }
  }, [runInput]);

  const publish = async () => {
    setError(null);
    setNote(null);
    if (!parsed.spec) {
      setError(parsed.err);
      return;
    }
    setBusy(true);
    try {
      const r = await portal.defs.publish(name, parsed.spec);
      setNote(`Published ${r.name} v${r.version}`);
      refresh();
    } catch (e: any) {
      // The server validates the graph; surface its reason verbatim.
      setError(e?.message ?? "Publish failed");
    } finally {
      setBusy(false);
    }
  };

  const run = async (defName: string) => {
    setError(null);
    setNote(null);
    setBusy(true);
    try {
      const input = runInput.trim() ? JSON.parse(runInput) : {};
      const r = await portal.defs.run(defName, input);
      setNote(`Started ${r.definition} v${r.version} — ${r.workflowId}`);
      setTab("runs");
      refresh();
    } catch (e: any) {
      setError(e?.message ?? "Run failed");
    } finally {
      setBusy(false);
    }
  };

  const load = async (defName: string) => {
    const d = await portal.defs.get(defName);
    setName(d.name);
    setSource(JSON.stringify(d.spec, null, 2));
    setTab("editor");
  };

  // Median rather than mean: one slow outlier should not describe the rest.
  const medianDuration = useMemo(() => {
    const xs = Object.values(facts)
      .map((f) => f.durationMs)
      .filter((d): d is number => d != null)
      .sort((a, b) => a - b);
    return xs.length ? duration(xs[Math.floor(xs.length / 2)]) : "—";
  }, [facts]);

  const latest = useMemo(() => {
    const seen = new Set<string>();
    return defs.filter((d) => (seen.has(d.name) ? false : seen.add(d.name)));
  }, [defs]);

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <h1 className="text-lg font-semibold tracking-tight">Workflows</h1>
          <p className="mt-0.5 text-sm text-slate-500 max-w-2xl leading-relaxed">
            Author a durable graph · crash-safe, retried, exactly-once
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
          <Readout label="Definitions" value={latest.length} size="sm"
            state={latest.length > 0 ? "healthy" : "idle"} />
          <Readout label="Runs" value={runs.length} size="sm"
            state={runs.length > 0 ? "active" : "idle"} />
          <Readout label="Failed" value={runs.filter((r) => r.status === "FAILED").length} size="sm"
            state={runs.some((r) => r.status === "FAILED") ? "critical" : "idle"} />
          <Readout label="Median run" value={medianDuration} size="sm"
            state={medianDuration === "—" ? "idle" : "healthy"} />
        </div>
      </header>

      <Tabs items={TABS} tab={tab} setTab={setTab} />

      {(error || note) && (
        <div
          className="rounded border px-3 py-2 text-[11px]"
          style={{
            borderColor: error ? `${STATE.critical.color}55` : `${STATE.healthy.color}55`,
            color: error ? STATE.critical.color : STATE.healthy.color,
          }}
        >
          {error ?? note}
        </div>
      )}

      <Morph k={tab}>
        {tab === "definitions" && (
          <section>
            {/* Placed above the list because it is what Run sends, not a footnote. */}
            {latest.length > 0 && (
              <div className="mb-3">
                <div className="flex items-baseline justify-between">
                  <Micro>Run input</Micro>
                  <span className="text-[10px]" style={{ color: inputValid ? STATE.healthy.color : STATE.critical.color }}>
                    {inputValid ? "reachable as ${input.…}" : "invalid JSON"}
                  </span>
                </div>
                <textarea
                  value={runInput}
                  onChange={(e) => setRunInput(e.target.value)}
                  spellCheck={false}
                  className="mt-1 h-14 w-full rounded border bg-ink p-2 font-mono text-[11px] text-slate-200 outline-none focus:border-aurora/60"
                  style={{ borderColor: inputValid ? "rgb(var(--edge))" : `${STATE.critical.color}55` }}
                />
              </div>
            )}
            {latest.length === 0 ? (
              <div className="text-center">
                <Micro>No definitions yet</Micro>
                <p className="mx-auto mt-2 max-w-md text-xs text-slate-500">
                  A definition is a graph of HTTP steps the engine runs durably. Start one in the
                  editor.
                </p>
                <button
                  onClick={() => setTab("editor")}
                  className="mt-3 rounded border border-edge px-3 py-1.5 text-xs hover:border-aurora/50"
                >
                  Open the editor
                </button>
              </div>
            ) : (
              <div className="divide-y divide-edge/40">
                {latest.map((d) => (
                  <div key={d.name} className="flex flex-wrap items-center gap-x-4 gap-y-1 py-2.5 text-[11px]">
                    <StateDot state="healthy" size={6} />
                    <span className="min-w-0 flex-1">
                      <span className="text-sm font-medium text-slate-200">{d.name}</span>
                      {d.description && <span className="ml-2 text-slate-500">{d.description}</span>}
                    </span>
                    <span className="readout text-slate-500">v{d.version}</span>
                    <span className="readout text-slate-600">{d.steps} steps</span>
                    <button onClick={() => load(d.name)}
                      className="rounded border border-edge px-2 py-1 hover:border-aurora/50">Edit</button>
                    <button onClick={() => run(d.name)} disabled={busy || !inputValid}
                      className="rounded bg-[color:var(--accent-strong)] px-2.5 py-1 font-medium text-white hover:opacity-90 disabled:opacity-50">
                      Run
                    </button>
                    <button
                      onClick={() => {
                        if (confirm(`Delete every version of ${d.name}?`)) {
                          portal.defs.remove(d.name).then(refresh);
                        }
                      }}
                      className="rounded border px-2 py-1"
                      style={{ borderColor: `${STATE.critical.color}44`, color: STATE.critical.color }}
                    >
                      Delete
                    </button>
                  </div>
                ))}
              </div>
            )}

          </section>
        )}

        {tab === "editor" && (
          <section className="grid gap-4 lg:grid-cols-[minmax(0,1fr)_340px]">
            <div>
              <div className="flex flex-wrap items-end gap-2">
                <div className="min-w-0 flex-1">
                  <Micro>Name</Micro>
                  <input
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    className="mt-1 w-full rounded border border-edge bg-ink px-2 py-1.5 font-mono text-xs text-slate-100 outline-none focus:border-aurora/60"
                  />
                </div>
                <button
                  onClick={publish}
                  disabled={busy || !!parsed.err}
                  className="rounded bg-[color:var(--accent-strong)] px-4 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
                >
                  {busy ? "Publishing…" : "Publish version"}
                </button>
              </div>

              <div className="mt-2 flex items-baseline justify-between">
                <Micro>Definition</Micro>
                <span className="text-[10px]" style={{ color: parsed.err ? STATE.critical.color : STATE.healthy.color }}>
                  {parsed.err ? `invalid JSON — ${parsed.err}` : "valid JSON"}
                </span>
              </div>
              <textarea
                value={source}
                onChange={(e) => setSource(e.target.value)}
                spellCheck={false}
                className="mt-1 h-[460px] w-full rounded border bg-ink p-3 font-mono text-[11px] leading-relaxed text-slate-200 outline-none"
                style={{ borderColor: parsed.err ? `${STATE.critical.color}55` : "rgb(var(--edge))" }}
              />
            </div>

            <aside className="space-y-4">
              <div>
                <Micro>Execution plan</Micro>
                <p className="mt-0.5 text-[10px] text-slate-600">
                  steps in a layer run in parallel · layers run in order
                </p>
                {layers.error ? (
                  <div className="mt-2 rounded border px-2 py-1.5 text-[11px]"
                    style={{ borderColor: `${STATE.critical.color}55`, color: STATE.critical.color }}>
                    {layers.error}
                  </div>
                ) : (
                  <div className="mt-2 space-y-2">
                    {layers.layers.map((layer, i) => (
                      <div key={i} className="flex items-start gap-2">
                        <span className="readout mt-1 w-4 shrink-0 text-[10px] text-slate-600">{i + 1}</span>
                        <div className="flex flex-1 flex-wrap gap-1">
                          {layer.map((s) => (
                            <span
                              key={s.id}
                              title={s.condition ? `if ${s.condition}` : undefined}
                              className="rounded border px-1.5 py-0.5 text-[10px]"
                              style={{
                                borderColor: `${stepColor(s)}55`,
                                color: stepColor(s),
                              }}
                            >
                              {s.id}
                              {s.type === "WAIT" && ` · wait ${s.waitSeconds ?? "?"}s`}
                              {s.condition ? " · if" : ""}
                            </span>
                          ))}
                        </div>
                      </div>
                    ))}
                    {parsed.spec?.onComplete && (
                      <div className="flex items-start gap-2">
                        <span className="readout mt-1 w-4 shrink-0 text-[10px] text-slate-600">→</span>
                        <span className="rounded border border-edge px-1.5 py-0.5 text-[10px] text-slate-400">
                          callback
                        </span>
                      </div>
                    )}
                  </div>
                )}
              </div>

              <div>
                <Micro>Reference</Micro>
                <dl className="mt-1.5 space-y-1 text-[10px] text-slate-500">
                  <Ref k="${input.field}" v="value the run was started with" />
                  <Ref k="${steps.id.field}" v="an earlier step's response body" />
                  <Ref k="condition" v="e.g. ${steps.risk.score} > 0.8" />
                  <Ref k="type: WAIT" v="durable timer, waitSeconds" />
                  <Ref k="dependsOn" v="ordering; independent steps parallelise" />
                  <Ref k="onComplete" v="callback instead of polling" />
                </dl>
                <Link to="/docs" className="mt-2 inline-block text-[10px] text-neon hover:underline">
                  Full reference →
                </Link>
              </div>
            </aside>
          </section>
        )}

        {tab === "runs" && (
          <section>
            {runs.length === 0 ? (
              <div className="text-center text-xs text-slate-500">
                No runs yet. Publish a definition and run it.
              </div>
            ) : (
              <div className="divide-y divide-edge/40">
                <div className="flex items-center gap-x-4 pb-1 text-[10px] uppercase tracking-widest text-slate-600">
                  <span className="w-2" />
                  <span className="w-16">Started</span>
                  <span className="w-40">Definition</span>
                  <span className="w-20">Status</span>
                  <span className="w-14 text-right">Took</span>
                  <span className="flex-1 pl-4">Steps</span>
                </div>
                {runs.map((r: any) => {
                  const st: StateKey =
                    r.status === "FAILED" ? "critical" : r.status === "RUNNING" ? "active" : "healthy";
                  const f = facts[r.workflowId];
                  return (
                    <Link
                      key={r.workflowId}
                      to={`/workflows/${r.workflowId}`}
                      className="flex items-center gap-x-4 px-1 py-2.5 text-[11px] transition-colors hover:bg-edge/40"
                    >
                      <StateDot state={st} size={6} />
                      <span className="readout w-16 shrink-0 text-slate-600">
                        {timeOf(r.createdAt)}
                      </span>
                      <span className="w-40 shrink-0 truncate text-slate-300">
                        {f?.definition ?? "—"}
                        {f?.version != null && <span className="ml-1 text-slate-600">v{f.version}</span>}
                      </span>
                      <span className="w-20 shrink-0 font-medium" style={{ color: STATE[st].ink }}>
                        {r.status}
                      </span>
                      <span className="readout w-14 shrink-0 text-right text-slate-500">
                        {duration(f?.durationMs ?? null)}
                      </span>
                      {/* The steps themselves, in the order the engine recorded
                          them — a skipped guard is visible without opening the run. */}
                      <span className="flex flex-1 flex-wrap items-center gap-1 pl-4">
                        {(f?.steps ?? []).map((s) => (
                          <span
                            key={s.id}
                            title={s.outcome === "skipped" ? "guard was false — not executed" : "executed"}
                            className="rounded border px-1.5 py-0.5 text-[10px]"
                            style={
                              s.outcome === "skipped"
                                ? { borderColor: "rgb(var(--edge))", color: STATE.idle.color }
                                : { borderColor: `${STATE.healthy.color}44`, color: STATE.healthy.color }
                            }
                          >
                            {s.id}
                            {s.outcome === "skipped" && " ·skipped"}
                          </span>
                        ))}
                        {f?.callback && (
                          <span
                            className="rounded border px-1.5 py-0.5 text-[10px]"
                            style={{ borderColor: `${STATE.active.color}44`, color: STATE.active.color }}
                          >
                            callback delivered
                          </span>
                        )}
                        {f?.error && (
                          <span className="truncate" style={{ color: STATE.critical.color }}>
                            {f.error.replace(/^Activity '[^']+' failed permanently: /, "")}
                          </span>
                        )}
                        {!f && r.status === "RUNNING" && <span className="text-slate-600">in flight…</span>}
                      </span>
                    </Link>
                  );
                })}
              </div>
            )}
          </section>
        )}
      </Morph>
    </div>
  );
}

/**
 * Reads a run's outcome out of its own recorded result. Nothing here is
 * inferred — a step is only shown as skipped because the engine wrote that.
 */
function readFacts(detail: any): RunFacts {
  const s = detail?.summary ?? {};
  const started = s.createdAt ? Number(s.createdAt) * 1000 : null;
  const ended = s.updatedAt ? Number(s.updatedAt) * 1000 : null;

  let result: any = detail?.result;
  if (typeof result === "string") {
    try {
      result = JSON.parse(result);
    } catch {
      result = null;
    }
  }
  const stepMap = (result?.steps ?? {}) as Record<string, any>;
  return {
    definition: result?.definition ?? detail?.input?.definition,
    version: result?.version ?? detail?.input?.version,
    steps: Object.entries(stepMap).map(([id, v]) => ({
      id,
      outcome: v && typeof v === "object" && (v as any).skipped ? "skipped" : "ok",
    })),
    callback: result?.callbackStatus != null,
    durationMs: started && ended ? Math.max(0, ended - started) : null,
    // Why it failed belongs in the list; clicking through to find out is a
    // step a developer should not have to take.
    error: detail?.error ? String(detail.error) : null,
  };
}

function duration(ms: number | null) {
  if (ms == null) return "—";
  return ms < 1000 ? `${Math.round(ms)}ms` : `${(ms / 1000).toFixed(1)}s`;
}

function Ref({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex gap-2">
      <dt className="w-32 shrink-0 font-mono text-slate-400">{k}</dt>
      <dd>{v}</dd>
    </div>
  );
}

function stepColor(s: Step) {
  if (s.type === "WAIT") return STATE.warning.color;
  if (s.condition) return STATE.active.color;
  return STATE.healthy.color;
}

/**
 * Mirrors the engine's layering so the author sees the real execution plan
 * before publishing, including cycles the server would reject.
 */
function computeLayers(steps: Step[]): { layers: Step[][]; error: string | null } {
  if (!Array.isArray(steps) || steps.length === 0) {
    return { layers: [], error: null };
  }
  const ids = new Set(steps.map((s) => s?.id));
  const done = new Set<string>();
  const layers: Step[][] = [];
  let guard = 0;

  while (done.size < steps.length) {
    if (guard++ > 200) return { layers, error: "Too many layers." };
    const layer = steps.filter(
      (s) => s?.id && !done.has(s.id) && (s.dependsOn ?? []).every((d) => done.has(d))
    );
    if (layer.length === 0) {
      const unknown = steps
        .filter((s) => !done.has(s?.id))
        .flatMap((s) => (s.dependsOn ?? []).filter((d) => !ids.has(d)));
      return {
        layers,
        error: unknown.length
          ? `Unknown dependency: ${unknown[0]}`
          : "Steps form a dependency cycle.",
      };
    }
    layer.sort((a, b) => String(a.id).localeCompare(String(b.id)));
    layer.forEach((s) => done.add(s.id));
    layers.push(layer);
  }
  return { layers, error: null };
}
