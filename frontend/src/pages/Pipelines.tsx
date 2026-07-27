import { useCallback, useEffect, useMemo, useState } from "react";
import { portal } from "../api";
import { Micro, PageHeader, Plane, Readout, Switch } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";

/**
 * Pipelines — what an external application actually calls.
 *
 * <p>The application sends an image and a question, and receives advice. It is
 * never told that a detector ran, what it was called, or who hosts it.
 *
 * <p>The chain is the page. Everything else here — the form, the list, the
 * toggle — is configuration; the reason to open this page is to watch a request
 * go through the layer and come out the other side, and to be able to read the
 * exact words the model was given. A pipeline described in prose is a promise. A
 * pipeline whose chain you can watch is a fact.
 */

type Pipeline = {
  id: number;
  name: string;
  description: string | null;
  inputKind: string;
  systemPrompt: string | null;
  steps: number[];
  enabled: boolean;
  runs: number;
};

type Specialist = {
  id: number;
  name: string;
  status: "DRAFT" | "READY" | "UNPARSEABLE" | "FAILED";
  inputKind: string;
  minConfidence: number;
};

type Step = {
  ordinal: number;
  kind: "INPUT" | "SPECIALIST" | "ENRICHMENT" | "MODEL" | "OUTPUT" | string;
  label: string;
  status: string;
  confidence: number | null;
  cost: number;
  latencyMs: number;
  detail: any;
};

type Run = {
  response: string;
  traceId: string;
  findings: number;
  evidenceConfidence: number;
  anythingFound: boolean;
  analysisRan: boolean;
  model: string;
  cost: number;
  latencyMs: number;
  trace: Step[];
};

/** A 1×1 PNG. Enough to exercise the whole chain without asking for a file. */
const SAMPLE_IMAGE =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

export default function Pipelines() {
  const toast = useToast();
  const [pipelines, setPipelines] = useState<Pipeline[] | null>(null);
  const [specialists, setSpecialists] = useState<Specialist[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState<number | null>(null);

  const load = useCallback(async () => {
    try {
      const [p, s] = await Promise.all([portal.pipelines.list(), portal.specialists.list()]);
      setPipelines(p);
      setSpecialists(s);
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load pipelines.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const ready = specialists.filter((s) => s.status !== "DRAFT");

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

  if (error) return <ErrorState message={error} onRetry={load} />;

  const live = (pipelines ?? []).filter((p) => p.enabled).length;
  const totalRuns = (pipelines ?? []).reduce((n, p) => n + p.runs, 0);

  return (
    <section className="space-y-5">
      <PageHeader
        title="Pipelines"
        subtitle="Your application sends an image and gets advice. It never learns a detector was involved."
      />

      <Plane className="grid grid-cols-2 gap-4 p-4 sm:grid-cols-4">
        <Readout label="Pipelines" value={pipelines?.length ?? 0} size="sm" />
        <Readout
          label="Enabled"
          value={live}
          size="sm"
          state={live > 0 ? "active" : "idle"}
          hint="A pipeline is off until you turn it on. Nothing runs by default."
        />
        <Readout label="Specialists ready" value={ready.length} size="sm" />
        <Readout label="Runs" value={totalRuns} size="sm" />
      </Plane>

      {ready.length === 0 && (
        <Plane className="p-4">
          <p className="text-sm text-slate-300">You need a probed specialist first.</p>
          <p className="mt-1 text-xs text-slate-500">
            A pipeline whose specialist has never answered would move the failure from here, where
            you are looking at it, to a customer's request, where you are not. Register one on the{" "}
            <a href="/specialists" className="text-aurora underline underline-offset-2">
              Specialists
            </a>{" "}
            page and probe it.
          </p>
        </Plane>
      )}

      <NewPipeline
        specialists={ready}
        busy={busy}
        onAdd={(body) => act(() => portal.pipelines.add(body), `Pipeline "${body.name}" created — it is off until you enable it.`)}
      />

      {pipelines === null ? (
        <SkeletonRows rows={2} />
      ) : pipelines.length === 0 ? (
        <Plane className="p-6 text-center text-sm text-slate-500">
          No pipelines yet. A pipeline is the endpoint your application calls: it takes the raw
          input, runs your specialists over it, turns what they found into something a language
          model can reason about, and returns the answer.
        </Plane>
      ) : (
        <div className="space-y-2">
          {pipelines.map((p) => (
            <PipelineCard
              key={p.id}
              p={p}
              specialists={specialists}
              busy={busy}
              open={open === p.id}
              onToggleOpen={() => setOpen(open === p.id ? null : p.id)}
              onEnable={(next) =>
                act(
                  () => portal.pipelines.update(p.id, { enabled: next }),
                  next ? `"${p.name}" is live.` : `"${p.name}" is off.`
                )
              }
              onDelete={() => act(() => portal.pipelines.remove(p.id), "Pipeline removed.")}
              onRan={load}
            />
          ))}
        </div>
      )}
    </section>
  );
}

// --- creation ---------------------------------------------------------------

function NewPipeline({
  specialists,
  busy,
  onAdd,
}: {
  specialists: Specialist[];
  busy: boolean;
  onAdd: (b: { name: string; description: string; inputKind: string; systemPrompt: string; steps: number[] }) => void;
}) {
  const [adding, setAdding] = useState(false);
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [inputKind, setInputKind] = useState("image");
  const [systemPrompt, setSystemPrompt] = useState("");
  const [steps, setSteps] = useState<number[]>([]);

  const toggleStep = (id: number) =>
    setSteps((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  if (!adding) {
    return (
      <button
        onClick={() => setAdding(true)}
        disabled={specialists.length === 0}
        className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:border-aurora/50 disabled:opacity-40"
      >
        New pipeline
      </button>
    );
  }

  return (
    <Plane className="space-y-4 p-4">
      <div className="flex items-center justify-between">
        <Micro>New pipeline</Micro>
        <button onClick={() => setAdding(false)} className="text-xs text-slate-500 hover:text-slate-300">
          Cancel
        </button>
      </div>

      <div className="grid gap-3 sm:grid-cols-2">
        <label className="block">
          <Micro>Name</Micro>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            placeholder="animal-triage"
            className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
          />
          <p className="mt-1 text-xs text-slate-600">
            This goes in the URL your application calls, so letters, digits, hyphen and underscore
            only.
          </p>
        </label>
        <label className="block">
          <Micro>Input your app sends</Micro>
          <select
            value={inputKind}
            onChange={(e) => setInputKind(e.target.value)}
            className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
          >
            <option value="image">Image</option>
            <option value="text">Text</option>
            <option value="json">Data</option>
            <option value="audio">Audio</option>
          </select>
        </label>
      </div>

      <label className="block">
        <Micro>What it does</Micro>
        <input
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="Photo of an injured animal in, first-aid advice out"
          className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
        />
      </label>

      <label className="block">
        <Micro>Standing instructions for the model</Micro>
        <textarea
          value={systemPrompt}
          onChange={(e) => setSystemPrompt(e.target.value)}
          rows={3}
          placeholder="You are advising a member of the public on immediate first aid for an injured animal. Be brief and practical. Always say when a vet is needed."
          className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
        />
        <p className="mt-1 text-xs text-slate-600">
          Sent with every call, so your application does not have to repeat it. The findings and the
          user's own question are added underneath.
        </p>
      </label>

      <div>
        <Micro>Specialists, in the order they run</Micro>
        <div className="mt-2 space-y-1.5">
          {specialists.map((s) => {
            const idx = steps.indexOf(s.id);
            return (
              <button
                key={s.id}
                onClick={() => toggleStep(s.id)}
                className={`flex w-full items-center gap-3 rounded-md border px-3 py-2 text-left text-sm transition-colors ${
                  idx >= 0
                    ? "border-aurora/50 bg-aurora/5 text-slate-200"
                    : "border-edge text-slate-400 hover:border-slate-600"
                }`}
              >
                <span
                  className={`readout flex h-5 w-5 shrink-0 items-center justify-center rounded-full text-[10px] ${
                    idx >= 0 ? "bg-aurora/70 text-white" : "bg-edge/50 text-slate-500"
                  }`}
                >
                  {idx >= 0 ? idx + 1 : "·"}
                </span>
                <span className="min-w-0 flex-1 truncate">{s.name}</span>
                <span className="micro shrink-0">{s.inputKind}</span>
              </button>
            );
          })}
        </div>
      </div>

      <button
        disabled={busy || !name.trim() || steps.length === 0}
        onClick={() => {
          onAdd({ name: name.trim(), description, inputKind, systemPrompt, steps });
          setName("");
          setDescription("");
          setSystemPrompt("");
          setSteps([]);
          setAdding(false);
        }}
        className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
      >
        Create pipeline
      </button>
    </Plane>
  );
}

// --- one pipeline -----------------------------------------------------------

function PipelineCard({
  p,
  specialists,
  busy,
  open,
  onToggleOpen,
  onEnable,
  onDelete,
  onRan,
}: {
  p: Pipeline;
  specialists: Specialist[];
  busy: boolean;
  open: boolean;
  onToggleOpen: () => void;
  onEnable: (next: boolean) => void;
  onDelete: () => void;
  onRan: () => void;
}) {
  const named = p.steps
    .map((id) => specialists.find((s) => s.id === id)?.name ?? `#${id}`)
    .join(" → ");

  return (
    <Plane className="overflow-hidden">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
        <span className={`h-2 w-2 shrink-0 rounded-full ${p.enabled ? "bg-emerald-400" : "bg-slate-600"}`} />
        <button onClick={onToggleOpen} aria-expanded={open} className="min-w-0 flex-1 text-left">
          <div className="text-sm font-medium text-slate-200">{p.name}</div>
          <div className="truncate text-xs text-slate-500">
            {p.description || named} · {p.runs} {p.runs === 1 ? "run" : "runs"}
          </div>
        </button>
        <span className="micro shrink-0">{p.enabled ? "live" : "off"}</span>
        <button
          disabled={busy}
          onClick={onDelete}
          className="shrink-0 rounded border border-edge px-2 py-0.5 text-xs text-slate-400 hover:border-rose-500/50 hover:text-rose-300 disabled:opacity-40"
        >
          Remove
        </button>
      </div>

      {open && (
        <div className="space-y-4 border-t border-edge/60 px-4 py-4">
          <Switch
            checked={p.enabled}
            busy={busy}
            onChange={onEnable}
            label="Accept requests from your application"
            hint="Off by default. While it is off the endpoint answers 400 and nothing runs — so a half-configured pipeline cannot be reached by mistake."
          />

          <div>
            <Micro>The endpoint your application calls</Micro>
            <pre className="well mt-1.5 overflow-x-auto p-3 text-[11px] leading-relaxed text-slate-400">
{`curl -X POST https://your-continuum/api/gateway/pipeline/${p.name} \\
  -H "Authorization: Bearer cnt_live_..." \\
  -H "content-type: application/json" \\
  -d '{"input":{"image":"<base64>"},"prompt":"What should I do?"}'`}
            </pre>
            <p className="mt-1.5 text-xs text-slate-600">
              The response carries the answer and the chain behind it. Your application can show its
              user the working, or ignore the field entirely — but it never sees which provider ran
              or what credential was used.
            </p>
          </div>

          <TryIt pipeline={p} onRan={onRan} />
        </div>
      )}
    </Plane>
  );
}

// --- the chain --------------------------------------------------------------

function TryIt({ pipeline, onRan }: { pipeline: Pipeline; onRan: () => void }) {
  const toast = useToast();
  const [prompt, setPrompt] = useState("What should I do right now?");
  const [payload, setPayload] = useState(SAMPLE_IMAGE);
  const [run, setRun] = useState<Run | null>(null);
  const [running, setRunning] = useState(false);
  // How many steps of the returned chain are revealed. The run is synchronous,
  // so this replays it at reading speed rather than dropping five finished rows
  // at once — the point of the page is to see the layer do something.
  const [shown, setShown] = useState(0);
  const [detail, setDetail] = useState<number | null>(null);

  useEffect(() => {
    if (!run || shown >= run.trace.length) return;
    const t = setTimeout(() => setShown((n) => n + 1), 260);
    return () => clearTimeout(t);
  }, [run, shown]);

  const inputField = pipeline.inputKind === "image" ? "image" : pipeline.inputKind === "text" ? "text" : "data";

  const go = async () => {
    setRunning(true);
    setRun(null);
    setShown(0);
    setDetail(null);
    try {
      const r = (await portal.pipelines.run(pipeline.name, { [inputField]: payload }, prompt)) as Run;
      setRun(r);
      onRan();
    } catch (e: any) {
      toast(e?.message ?? "The run did not complete.", "error");
    } finally {
      setRunning(false);
    }
  };

  return (
    <div>
      <Micro>Try it</Micro>
      <p className="mt-1 text-xs text-slate-600">
        Runs the same code path your application would, including a disabled pipeline's refusal — a
        test route that skipped a step would be worse than no test route.
      </p>

      <div className="mt-2 grid gap-3 sm:grid-cols-[1fr_auto]">
        <div className="min-w-0 space-y-2">
          <label className="block">
            <Micro>{pipeline.inputKind === "image" ? "Image (base64)" : "Input"}</Micro>
            <textarea
              value={payload}
              onChange={(e) => setPayload(e.target.value)}
              rows={2}
              className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 font-mono text-[11px] text-slate-300 outline-none focus:border-aurora/60"
            />
          </label>
          <label className="block">
            <Micro>What your user asked</Micro>
            <input
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
            />
          </label>
        </div>
        <button
          onClick={go}
          disabled={running || !pipeline.enabled}
          title={pipeline.enabled ? undefined : "Enable the pipeline first."}
          className="h-fit self-end rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-40"
        >
          {running ? "Running…" : "Run"}
        </button>
      </div>

      {running && <Chain steps={[]} shown={0} pending detail={null} onDetail={() => {}} />}

      {run && (
        <div className="mt-4 space-y-3">
          <Chain steps={run.trace} shown={shown} detail={detail} onDetail={setDetail} />

          {shown >= run.trace.length && (
            <>
              <Plane inset className="grid grid-cols-2 gap-4 p-3 sm:grid-cols-4">
                <Readout label="Findings used" value={run.findings} size="sm" />
                <Readout
                  label="Evidence"
                  value={run.anythingFound ? run.evidenceConfidence.toFixed(2) : "—"}
                  size="sm"
                  state={
                    !run.analysisRan
                      ? "critical"
                      : !run.anythingFound
                        ? "idle"
                        : run.evidenceConfidence >= 0.7
                          ? "active"
                          : "degraded"
                  }
                  hint="The strongest thing a specialist saw. Not the model's confidence in its own answer."
                />
                <Readout label="Model" value={run.model || "—"} size="sm" />
                <Readout label="End to end" value={run.latencyMs} unit="ms" size="sm" />
              </Plane>

              {!run.analysisRan && (
                <p className="rounded-md border border-rose-500/40 bg-rose-500/5 px-3 py-2 text-xs text-rose-300">
                  No specialist answered, so nothing was examined. The model was told this
                  explicitly rather than being left to fill the silence — "we looked and found
                  nothing" and "we never looked" are different facts, and only the first one is
                  evidence.
                </p>
              )}

              <div>
                <Micro>What your application received</Micro>
                <div className="well mt-1.5 whitespace-pre-wrap p-3 text-sm leading-relaxed text-slate-300">
                  {run.response || "—"}
                </div>
              </div>

              <p className="text-xs text-slate-600">
                Trace <span className="readout text-slate-500">{run.traceId}</span> — returned to
                your application so it can show its own user the same chain.
              </p>
            </>
          )}
        </div>
      )}
    </div>
  );
}

const KIND_NOTE: Record<string, string> = {
  INPUT: "What your application sent. It is never forwarded to the language model.",
  SPECIALIST: "A purpose-trained model looked at the raw input and reported what it saw.",
  ENRICHMENT: "The findings became words a language model can reason about, with the confidence stated rather than implied.",
  MODEL: "The language model reasoned over the findings — not over the image, which it never saw.",
  OUTPUT: "The answer went back to your application.",
};

function Chain({
  steps,
  shown,
  pending = false,
  detail,
  onDetail,
}: {
  steps: Step[];
  shown: number;
  pending?: boolean;
  detail: number | null;
  onDetail: (n: number | null) => void;
}) {
  const skeleton = useMemo(
    () => ["INPUT", "SPECIALIST", "ENRICHMENT", "MODEL", "OUTPUT"], []);

  if (pending) {
    return (
      <div className="mt-4 space-y-1.5" aria-label="running">
        {skeleton.map((k, i) => (
          <div key={k} className="flex items-center gap-3 rounded-md border border-edge/60 px-3 py-2">
            <span
              className="h-2 w-2 shrink-0 animate-pulse rounded-full bg-aurora/60"
              style={{ animationDelay: `${i * 120}ms` }}
            />
            <span className="micro flex-1">{k}</span>
            <span className="text-xs text-slate-600">waiting</span>
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="space-y-1.5">
      {steps.map((s, i) => {
        const visible = i < shown;
        const bad = s.status !== "OK";
        return (
          <div
            key={s.ordinal}
            className={`transition-all duration-300 ${visible ? "opacity-100" : "translate-y-1 opacity-0"}`}
          >
            <button
              onClick={() => onDetail(detail === s.ordinal ? null : s.ordinal)}
              className={`flex w-full items-center gap-3 rounded-md border px-3 py-2 text-left ${
                bad ? "border-rose-500/40 bg-rose-500/5" : "border-edge/60 hover:border-aurora/40"
              }`}
            >
              <span
                className={`h-2 w-2 shrink-0 rounded-full ${
                  bad ? "bg-rose-400" : s.kind === "MODEL" ? "bg-indigo-400" : "bg-emerald-400"
                }`}
              />
              <span className="micro w-24 shrink-0">{s.kind}</span>
              <span className="min-w-0 flex-1 truncate text-sm text-slate-300">{s.label}</span>
              {s.confidence != null && (
                <span className="readout shrink-0 text-xs text-slate-400">
                  {s.confidence.toFixed(2)}
                </span>
              )}
              {s.latencyMs > 0 && (
                <span className="readout shrink-0 text-xs text-slate-500">{s.latencyMs}ms</span>
              )}
              {bad && <span className="micro shrink-0 text-rose-400">{s.status}</span>}
            </button>

            {detail === s.ordinal && (
              <div className="mt-1 rounded-md border border-edge/40 bg-ink/40 px-3 py-2">
                <p className="text-xs text-slate-500">{KIND_NOTE[s.kind] ?? ""}</p>
                <StepDetail step={s} />
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

/**
 * The enrichment step gets special treatment: its detail contains the exact
 * prose the model was handed, and that is the single most useful thing on this
 * page. Everything else is shown as its raw record.
 */
function StepDetail({ step }: { step: Step }) {
  const structured = step.kind === "ENRICHMENT" ? step.detail?.structured : null;

  if (structured) {
    const findings: any[] = structured.findings ?? [];
    return (
      <div className="mt-2 space-y-2">
        {findings.length > 0 && (
          <div className="space-y-1.5">
            {findings.map((f, i) => (
              // The label is the finding. On a narrow screen a single flex row
              // truncated it to nothing while the source name — the least
              // important thing here — survived at full width, so what the
              // detector saw gets its own line until there is room to share one.
              <div key={i} className="flex min-w-0 flex-wrap items-center gap-x-3 gap-y-0.5">
                <span className="min-w-0 flex-1 basis-full truncate text-xs text-slate-300 sm:basis-auto">
                  {f.label}
                </span>
                <span className="micro min-w-0 shrink truncate">{f.source}</span>
                <div className="h-1.5 w-20 shrink-0 overflow-hidden rounded-full bg-edge/60">
                  <div
                    className={`h-full rounded-full ${
                      f.confidence >= 0.7
                        ? "bg-emerald-500/70"
                        : f.confidence >= 0.4
                          ? "bg-amber-500/70"
                          : "bg-rose-500/70"
                    }`}
                    style={{ width: `${Math.max(3, f.confidence * 100)}%` }}
                  />
                </div>
                <span className="readout w-10 shrink-0 text-right text-xs text-slate-400">
                  {Number(f.confidence).toFixed(2)}
                </span>
              </div>
            ))}
          </div>
        )}
        {structured.belowThreshold > 0 && (
          <p className="text-xs text-amber-400/80">
            {structured.belowThreshold} further{" "}
            {structured.belowThreshold === 1 ? "signal was" : "signals were"} below the reporting
            threshold and withheld — passing one along invites the model to reason about it anyway.
          </p>
        )}
        {(structured.failedSpecialists ?? []).length > 0 && (
          <p className="text-xs text-rose-400/90">
            Did not respond: {(structured.failedSpecialists as string[]).join(", ")}
          </p>
        )}

        <div>
          <Micro>Exactly what the model was told</Micro>
          <pre className="well mt-1.5 max-h-96 overflow-auto whitespace-pre-wrap p-3 text-[11px] leading-relaxed text-slate-400">
            {step.detail?.prompt ?? "—"}
          </pre>
          <p className="mt-1 text-xs text-slate-600">
            Kept verbatim rather than rebuilt from the findings. When an answer is wrong this is the
            first thing worth reading, and a reconstruction is not the same artefact.
          </p>
        </div>
      </div>
    );
  }

  return (
    <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap text-[11px] leading-relaxed text-slate-500">
      {step.detail == null ? "—" : JSON.stringify(step.detail, null, 2)}
    </pre>
  );
}
