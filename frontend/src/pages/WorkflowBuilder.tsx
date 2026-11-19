import { useEffect, useMemo, useRef, useState } from "react";
import { visibleInterval } from "../system/poll";
import { Link } from "react-router-dom";
import { api, portal } from "../api";
import { Chip } from "../system/hub";
import { Micro, Readout, StateDot } from "../system/primitives";
import { STATE, type StateKey } from "../system/tokens";
import Tabs from "../system/Tabs";
import { Morph } from "../system/motion";
import { timeOf } from "../system/time";
import WorkflowEditor, { TEMPLATES, SAMPLE_KEYS } from "../components/WorkflowEditor";

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

const STARTER = JSON.stringify(TEMPLATES[0].spec, null, 2);

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
  const [runInput, setRunInput] = useState(JSON.stringify(TEMPLATES[0].spec.exampleInput ?? {}, null, 1));
  // Each definition is run with its own input: prefilled from the example it was
  // published with, editable on its card.
  const [inputs, setInputs] = useState<Record<string, string>>({});
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
    return visibleInterval(refresh, 5000);
  }, []);



  const publish = async (): Promise<boolean> => {
    setError(null);
    setNote(null);
    let spec: unknown;
    try {
      spec = JSON.parse(source);
    } catch (e: any) {
      setError(e?.message ?? "The definition is not valid JSON");
      return false;
    }
    setBusy(true);
    try {
      const r = await portal.defs.publish(name, spec);
      setNote(`Published ${r.name} v${r.version}`);
      refresh();
      return true;
    } catch (e: any) {
      // The server validates the graph; surface its reason verbatim.
      setError(e?.message ?? "Publish failed");
      return false;
    } finally {
      setBusy(false);
    }
  };

  const inputFor = (d: any) =>
    inputs[d.name] ?? JSON.stringify(d.exampleInput ?? {}, null, 1);
  const validJson = (t: string) => {
    if (!t.trim()) return true;
    try {
      JSON.parse(t);
      return true;
    } catch {
      return false;
    }
  };

  const addSamples = async () => {
    setError(null);
    setNote(null);
    setBusy(true);
    try {
      const published: string[] = [];
      for (const key of SAMPLE_KEYS) {
        const t = TEMPLATES.find((x) => x.key === key);
        if (!t) continue;
        const r = await portal.defs.publish(t.name, t.spec);
        published.push(`${r.name} v${r.version}`);
      }
      setNote(`Published ${published.join(", ")}. They call built-in sandbox services, so Run works right away.`);
      refresh();
    } catch (e: any) {
      setError(e?.message ?? "Could not publish the samples");
    } finally {
      setBusy(false);
    }
  };

  const run = async (defName: string, text?: string) => {
    setError(null);
    setNote(null);
    setBusy(true);
    try {
      const source = text ?? runInput;
      const input = source.trim() ? JSON.parse(source) : {};
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
    if (d.spec?.exampleInput !== undefined) setRunInput(JSON.stringify(d.spec.exampleInput, null, 1));
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
          <div className="flex items-center gap-2.5">
            <Chip glyph="flow" tone="accent" size={28} />
            <h1 className="text-[20px] font-semibold tracking-[-0.011em]">Workflows</h1>
          </div>
          <p className="mt-0.5 text-sm text-slate-500 max-w-2xl leading-relaxed">
            Author a durable graph · crash-safe, retried, exactly-once
          </p>
        </div>
        <div className="flex flex-wrap items-end gap-x-8 gap-y-3">
          <Readout label="Definitions" value={latest.length} size="sm"
            state={latest.length > 0 ? "healthy" : "idle"} />
          <Readout label="Runs" value={runs.length} size="sm"
            state={runs.length > 0 ? "active" : "idle"} />
          <Readout label="Failed" value={runs.filter((r) => r.status === "FAILED" && !r.cancelled).length} size="sm"
            state={runs.some((r) => r.status === "FAILED" && !r.cancelled) ? "critical" : "idle"} />
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
            color: error ? STATE.critical.ink : STATE.healthy.ink,
          }}
        >
          {error ?? note}
        </div>
      )}

      <Morph k={tab}>
        {tab === "definitions" && (
          <section>
            <div className="mb-3 flex flex-wrap items-center gap-3">
              <p className="min-w-0 flex-1 text-[12px] text-slate-500">
                Each workflow runs with the input on its card. Steps that call sandbox:// services are
                answered by Continuum itself, so those workflows run with nothing else started.
              </p>
              <button onClick={addSamples} disabled={busy}
                className="rounded-[var(--r-md)] border border-edge px-3.5 py-1.5 text-[13px] font-medium text-slate-200 hover:border-slate-500/60 disabled:opacity-50">
                Add ready-to-run samples
              </button>
            </div>
            {latest.length === 0 ? (
              <div className="text-center">
                <Micro>No definitions yet</Micro>
                <p className="mx-auto mt-2 max-w-md text-xs text-slate-500">
                  A definition is a graph of HTTP steps the engine runs durably. Add the samples to try
                  one now, or start your own in the editor.
                </p>
                <div className="mt-3 flex justify-center gap-2">
                  <button onClick={addSamples} disabled={busy}
                    className="rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-3.5 py-1.5 text-[13px] font-medium text-white hover:opacity-90 disabled:opacity-50">
                    Add ready-to-run samples
                  </button>
                  <button
                    onClick={() => setTab("editor")}
                    className="rounded border border-edge px-3 py-1.5 text-xs hover:border-aurora/50"
                  >
                    Open the editor
                  </button>
                </div>
              </div>
            ) : (
              <div className="grid gap-3 md:grid-cols-2 xl:grid-cols-3">
                {latest.map((d, i) => (
                  <div key={d.name} className="card rise-in flex flex-col gap-3 rounded-2xl border border-card-edge bg-card p-4 shadow-card" style={{ animationDelay: `${i * 50}ms` }}>
                    <div className="flex items-start gap-3">
                      <Chip glyph="flow" tone="accent" size={32} />
                      <div className="min-w-0 flex-1">
                        <div className="truncate font-mono text-[14px] font-semibold text-slate-100">{d.name}</div>
                        <div className="mt-0.5 line-clamp-2 text-[12px] text-slate-500">{d.description || "No description"}</div>
                      </div>
                    </div>
                    <div className="flex flex-wrap gap-1.5 text-[11px]">
                      <span className="rounded-full bg-slate-500/10 px-2 py-0.5 text-slate-300">v{d.version}</span>
                      <span className="rounded-full bg-slate-500/10 px-2 py-0.5 text-slate-300">{d.steps} step{d.steps === 1 ? "" : "s"}</span>
                      <span className="rounded-full bg-slate-500/10 px-2 py-0.5 text-slate-300">
                        {runs.filter((r) => facts[r.workflowId]?.definition === d.name).length} runs
                      </span>
                    </div>
                    {(() => {
                      const targets: string[] = d.targets ?? [];
                      const external = targets.filter((t) => t !== "sandbox");
                      const local = external.filter((t) => /^(localhost|127\.|0\.0\.0\.0|\[?::1)/.test(t));
                      return (
                        <div className="text-[11.5px] leading-snug text-slate-400" style={external.length && !local.length ? undefined : { color: local.length ? STATE.warning.ink : STATE.healthy.ink }}>
                          {external.length === 0
                            ? "Calls sandbox services only · runs anywhere"
                            : local.length
                            ? `Calls ${local.join(", ")} · that service must be running, and Continuum started with CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS=true`
                            : `Calls ${external.join(", ")}`}
                        </div>
                      );
                    })()}
                    <div>
                      <Micro>Run input</Micro>
                      <textarea
                        aria-label={`Run input for ${d.name} (JSON)`}
                        value={inputFor(d)}
                        onChange={(e) => setInputs((m) => ({ ...m, [d.name]: e.target.value }))}
                        spellCheck={false}
                        className="mt-1 h-16 w-full font-mono text-[11px] field"
                        style={{ borderColor: validJson(inputFor(d)) ? "rgb(var(--edge))" : `${STATE.critical.color}55` }}
                      />
                    </div>
                    <div className="mt-auto flex gap-2">
                      <button onClick={() => run(d.name, inputFor(d))} disabled={busy || !validJson(inputFor(d))}
                        className="rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-3.5 py-1.5 text-[13px] font-medium text-white hover:opacity-90 disabled:opacity-50">
                        Run
                      </button>
                      <button onClick={() => load(d.name)}
                        className="rounded-[var(--r-md)] border border-edge px-3.5 py-1.5 text-[13px] font-medium text-slate-200 hover:border-slate-500/60">
                        Open in editor
                      </button>
                      <button
                        onClick={() => {
                          if (confirm(`Delete every version of ${d.name}?`)) {
                            portal.defs.remove(d.name).then(refresh);
                          }
                        }}
                        className="ml-auto rounded-[var(--r-md)] border px-3 py-1.5 text-[13px]"
                        style={{ borderColor: `${STATE.critical.color}44`, color: STATE.critical.ink }}
                      >
                        Delete
                      </button>
                    </div>
                  </div>
                ))}
                <button
                  onClick={() => {
                    setName("my-workflow");
                    setSource(STARTER);
                    setTab("editor");
                  }}
                  className="grid min-h-[150px] place-items-center rounded-2xl border border-dashed border-edge text-[13px] font-medium text-slate-400 hover:border-slate-500/60 hover:text-slate-200"
                >
                  + New workflow
                </button>
              </div>
            )}

          </section>
        )}

        {tab === "editor" && (
          <WorkflowEditor
            name={name}
            setName={setName}
            source={source}
            setSource={setSource}
            busy={busy}
            runInput={runInput}
            setRunInput={setRunInput}
            onPublish={() => void publish()}
            onPublishAndRun={async () => {
              if (await publish()) await run(name);
            }}
          />
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
                    r.cancelled ? "idle" : r.stuck ? "warning" : r.status === "FAILED" ? "critical" : r.status === "RUNNING" ? "active" : "healthy";
                  const f = facts[r.workflowId];
                  return (
                    <Link
                      key={r.workflowId}
                      to={`/workflows/${r.workflowId}`}
                      data-morph
                      className="flex items-center gap-x-4 px-1 py-2.5 text-[11px] hover:bg-edge/40"
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
                                ? { borderColor: "rgb(var(--edge))", color: STATE.idle.ink }
                                : { borderColor: `${STATE.healthy.color}44`, color: STATE.healthy.ink }
                            }
                          >
                            {s.id}
                            {s.outcome === "skipped" && " ·skipped"}
                          </span>
                        ))}
                        {f?.callback && (
                          <span
                            className="rounded border px-1.5 py-0.5 text-[10px]"
                            style={{ borderColor: `${STATE.active.color}44`, color: STATE.active.ink }}
                          >
                            callback delivered
                          </span>
                        )}
                        {f?.error && (
                          <span className="truncate" style={{ color: STATE.critical.ink }}>
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



