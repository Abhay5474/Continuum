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
  routing: RoutingStep[];
  routingEnabled: boolean;
  verificationMode: "OFF" | "MONITOR" | "ENFORCE";
  enabled: boolean;
  policyEnabled: boolean;
  strongThreshold: number;
  weakThreshold: number;
  declineOnNoEvidence: boolean;
  runs: number;
};

type Condition =
  | "ALWAYS"
  | "IF_PREVIOUS_FOUND"
  | "IF_PREVIOUS_EMPTY"
  | "IF_PROMPT_MATCHES"
  | "IF_INPUT_IS";

type RoutingStep = { specialistId: number; when: Condition; pattern: string | null };

/** Written for the person choosing, not for the person who wrote the enum. */
const CONDITIONS: { value: Condition; label: string; hint: string; needsPattern?: string }[] = [
  { value: "ALWAYS", label: "Always", hint: "Runs on every request." },
  {
    value: "IF_PREVIOUS_EMPTY",
    label: "Only if nothing was found yet",
    hint: "Escalation — screen with something cheap, and only reach for the expensive model when the cheap one saw nothing.",
  },
  {
    value: "IF_PREVIOUS_FOUND",
    label: "Only if something was found",
    hint: "Drill-down — a general detector first, then a specific classifier once there is something to classify.",
  },
  {
    value: "IF_PROMPT_MATCHES",
    label: "Only if the question matches",
    hint: "Runs when the user's own question matches a pattern.",
    needsPattern: "bleed|wound|hurt",
  },
  {
    value: "IF_INPUT_IS",
    label: "Only for one kind of input",
    hint: "For a pipeline that accepts more than one kind.",
    needsPattern: "audio",
  },
];

type Band = "STRONG" | "MEDIUM" | "WEAK" | "NONE" | "UNAVAILABLE";

type Policy = {
  band: Band;
  action: "PASS" | "HEDGE" | "ASK_FOR_BETTER_INPUT" | "DECLINE";
  evidence: number;
  declined: boolean;
  reason: string;
};

type Compliance = {
  action: string;
  checked: boolean;
  complied: boolean;
  markers: string[];
  flatAssertions: string[];
  method: string;
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
  policy: Policy | null;
  compliance: Compliance | null;
  verification: Verification | null;
  trace: Step[];
};

type Verification = {
  verdict: "OK" | "WARN" | "FAIL";
  issues: { kind: string; detail: string; severe: boolean }[];
  covered: string[];
  uncovered: string[];
  replaced: boolean;
  method: string;
};

const BAND_COPY: Record<Band, { title: string; note: string; tone: string }> = {
  STRONG: {
    title: "Strong evidence",
    note: "Answered directly. Nothing was added to the prompt — a strong finding needs no instruction, and adding one would make every answer read as unsure.",
    tone: "text-emerald-400 border-emerald-500/40 bg-emerald-500/5",
  },
  MEDIUM: {
    title: "Moderate evidence",
    note: "The model was told to state its uncertainty out loud rather than leave it implied.",
    tone: "text-amber-400 border-amber-500/40 bg-amber-500/5",
  },
  WEAK: {
    title: "Too weak to advise on",
    note: "The model was forbidden from naming a condition and told to ask for better input instead. This is what stops a 0.31 detection becoming confident advice.",
    tone: "text-orange-400 border-orange-500/40 bg-orange-500/5",
  },
  NONE: {
    title: "Nothing found",
    note: "The analysis ran and found nothing above the reporting threshold. That is a real result — the model may still answer parts of the question that need no findings.",
    tone: "text-slate-400 border-edge bg-ink/40",
  },
  UNAVAILABLE: {
    title: "No analysis available",
    note: "No specialist answered, so nothing was examined. Not the same as finding nothing, and the model was explicitly forbidden from presenting it as an all-clear.",
    tone: "text-rose-400 border-rose-500/40 bg-rose-500/5",
  },
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
  const guarded = (pipelines ?? []).filter((p) => p.policyEnabled).length;
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
        <Readout
          label="Policy on"
          value={guarded}
          size="sm"
          state={guarded > 0 ? "active" : "idle"}
          hint="Pipelines where the strength of the evidence decides what the model may do with it."
        />
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
              onPolicy={(body) => act(() => portal.pipelines.update(p.id, body))}
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
  onPolicy,
}: {
  p: Pipeline;
  specialists: Specialist[];
  busy: boolean;
  open: boolean;
  onToggleOpen: () => void;
  onEnable: (next: boolean) => void;
  onDelete: () => void;
  onRan: () => void;
  onPolicy: (body: {
    policyEnabled?: boolean; strongThreshold?: number; weakThreshold?: number;
    declineOnNoEvidence?: boolean; routingEnabled?: boolean; routing?: RoutingStep[];
    verificationMode?: string;
  }) => void;
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
        {p.policyEnabled && (
          <span className="micro shrink-0 text-amber-400" title="Confidence policy is on">
            policy
          </span>
        )}
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

          <RoutingControls
            p={p}
            specialists={specialists}
            busy={busy}
            onChange={onPolicy}
          />

          <PolicyControls p={p} busy={busy} onChange={onPolicy} />

          <VerificationControls p={p} busy={busy} onChange={onPolicy} />

          <TryIt pipeline={p} onRan={onRan} />
        </div>
      )}
    </Plane>
  );
}

// --- specialist routing -----------------------------------------------------

/**
 * Which specialists run, rather than all of them, always.
 *
 * <p>Conditions can be edited while routing is off, so a developer can set one
 * up and watch it take effect the moment they flip the switch — rather than
 * having to turn on a behaviour change before they can configure it.
 */
function RoutingControls({
  p,
  specialists,
  busy,
  onChange,
}: {
  p: Pipeline;
  specialists: Specialist[];
  busy: boolean;
  onChange: (b: { routingEnabled?: boolean; routing?: RoutingStep[] }) => void;
}) {
  const steps: RoutingStep[] =
    p.routing?.length > 0
      ? p.routing
      : p.steps.map((id) => ({ specialistId: id, when: "ALWAYS" as Condition, pattern: null }));

  const name = (id: number) => specialists.find((s) => s.id === id)?.name ?? `#${id}`;

  const setStep = (i: number, patch: Partial<RoutingStep>) => {
    const next = steps.map((s, j) => (j === i ? { ...s, ...patch } : s));
    onChange({ routing: next });
  };

  return (
    <div className="rounded-md border border-edge/60 p-3">
      <Switch
        checked={p.routingEnabled}
        busy={busy}
        onChange={(next) => onChange({ routingEnabled: next })}
        label="Specialist routing"
        hint="Off by default — every step runs, which is what this pipeline did before. When on, each step's condition decides whether it runs at all."
      />

      <div className="mt-4 space-y-2">
        {steps.map((s, i) => {
          const meta = CONDITIONS.find((c) => c.value === s.when) ?? CONDITIONS[0];
          const first = i === 0;
          const impossible = first && (s.when === "IF_PREVIOUS_FOUND" || s.when === "IF_PREVIOUS_EMPTY");
          return (
            <div key={`${s.specialistId}-${i}`} className="rounded-md border border-edge/50 p-2.5">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <span className="readout flex h-5 w-5 shrink-0 items-center justify-center rounded-full bg-edge/50 text-[10px] text-slate-400">
                  {i + 1}
                </span>
                <span className="min-w-0 flex-1 truncate text-sm text-slate-300">
                  {name(s.specialistId)}
                </span>
              </div>

              <div className="mt-2 grid gap-2 sm:grid-cols-2">
                <label className="block">
                  <Micro>Runs when</Micro>
                  <select
                    value={s.when}
                    disabled={busy}
                    onChange={(e) =>
                      setStep(i, {
                        when: e.target.value as Condition,
                        pattern:
                          CONDITIONS.find((c) => c.value === e.target.value)?.needsPattern ?? null,
                      })
                    }
                    className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
                  >
                    {CONDITIONS.map((c) => (
                      <option
                        key={c.value}
                        value={c.value}
                        // Offered but not selectable in first position: it is a
                        // condition with nothing to refer to.
                        disabled={first && (c.value === "IF_PREVIOUS_FOUND" || c.value === "IF_PREVIOUS_EMPTY")}
                      >
                        {c.label}
                        {first && (c.value === "IF_PREVIOUS_FOUND" || c.value === "IF_PREVIOUS_EMPTY")
                          ? " — needs a step before it"
                          : ""}
                      </option>
                    ))}
                  </select>
                </label>

                {meta.needsPattern && (
                  <label className="block">
                    <Micro>{s.when === "IF_INPUT_IS" ? "Input kind" : "Pattern"}</Micro>
                    <input
                      value={s.pattern ?? ""}
                      disabled={busy}
                      placeholder={meta.needsPattern}
                      onChange={(e) => setStep(i, { pattern: e.target.value })}
                      className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 font-mono text-sm text-slate-200 outline-none focus:border-aurora/60"
                    />
                  </label>
                )}
              </div>

              <p className="mt-1.5 text-xs text-slate-600">{meta.hint}</p>
              {impossible && (
                <p className="mt-1 text-xs text-rose-400">
                  Nothing runs before this one, so there is no previous step for the condition to
                  look at.
                </p>
              )}
            </div>
          );
        })}
      </div>

      {!p.routingEnabled && steps.some((s) => s.when !== "ALWAYS") && (
        <p className="mt-3 text-xs text-amber-400/90">
          These conditions are saved but not in force — routing is off, so every step still runs.
        </p>
      )}
    </div>
  );
}

// --- confidence policy ------------------------------------------------------

function PolicyControls({
  p,
  busy,
  onChange,
}: {
  p: Pipeline;
  busy: boolean;
  onChange: (b: {
    policyEnabled?: boolean; strongThreshold?: number; weakThreshold?: number;
    declineOnNoEvidence?: boolean;
  }) => void;
}) {
  const [strong, setStrong] = useState(p.strongThreshold);
  const [weak, setWeak] = useState(p.weakThreshold);

  // The server refuses weak > strong; mirroring that here means the slider
  // cannot be dragged into a state the save will reject.
  const invalid = weak > strong;

  return (
    <div className="rounded-md border border-edge/60 p-3">
      <Switch
        checked={p.policyEnabled}
        busy={busy}
        onChange={(next) => onChange({ policyEnabled: next })}
        label="Confidence policy"
        hint="Off by default. When on, the strength of the evidence decides what the model is allowed to do with it — answer plainly, hedge, or ask for something better."
      />

      {p.policyEnabled && (
        <div className="mt-4 space-y-4">
          {/* The bands as a single bar, because they are only meaningful
              relative to each other and to the numbers on either side. */}
          <div>
            <div className="flex h-6 w-full overflow-hidden rounded-md border border-edge/60">
              <div
                className="flex items-center justify-center bg-orange-500/25 text-[10px] text-orange-300"
                style={{ width: `${weak * 100}%` }}
                title="Too weak to advise on"
              >
                {weak >= 0.18 && "ask for better"}
              </div>
              <div
                className="flex items-center justify-center bg-amber-500/25 text-[10px] text-amber-300"
                style={{ width: `${(strong - weak) * 100}%` }}
                title="Moderate — model told to hedge"
              >
                {strong - weak >= 0.14 && "hedge"}
              </div>
              <div
                className="flex items-center justify-center bg-emerald-500/25 text-[10px] text-emerald-300"
                style={{ width: `${(1 - strong) * 100}%` }}
                title="Strong — answered directly"
              >
                {1 - strong >= 0.14 && "answer"}
              </div>
            </div>
            <div className="mt-1 flex justify-between">
              <span className="readout text-[10px] text-slate-600">0.00</span>
              <span className="readout text-[10px] text-slate-600">1.00</span>
            </div>
          </div>

          <div className="grid gap-3 sm:grid-cols-2">
            <label className="block">
              <div className="flex items-baseline justify-between">
                <Micro>Weak below</Micro>
                <span className="readout text-xs text-slate-400">{weak.toFixed(2)}</span>
              </div>
              <input
                type="range"
                min={0}
                max={1}
                step={0.01}
                value={weak}
                disabled={busy}
                onChange={(e) => setWeak(Number(e.target.value))}
                onMouseUp={() => !invalid && onChange({ weakThreshold: weak })}
                onTouchEnd={() => !invalid && onChange({ weakThreshold: weak })}
                className="mt-1 w-full accent-amber-500"
                aria-label="Weak threshold"
              />
            </label>
            <label className="block">
              <div className="flex items-baseline justify-between">
                <Micro>Strong at or above</Micro>
                <span className="readout text-xs text-slate-400">{strong.toFixed(2)}</span>
              </div>
              <input
                type="range"
                min={0}
                max={1}
                step={0.01}
                value={strong}
                disabled={busy}
                onChange={(e) => setStrong(Number(e.target.value))}
                onMouseUp={() => !invalid && onChange({ strongThreshold: strong })}
                onTouchEnd={() => !invalid && onChange({ strongThreshold: strong })}
                className="mt-1 w-full accent-emerald-500"
                aria-label="Strong threshold"
              />
            </label>
          </div>

          {invalid && (
            <p className="text-xs text-rose-400">
              The weak threshold cannot sit above the strong one — there would be no middle band
              left to hedge in, which looks like a working policy and silently isn't.
            </p>
          )}

          <Switch
            checked={p.declineOnNoEvidence}
            busy={busy}
            onChange={(next) => onChange({ declineOnNoEvidence: next })}
            label="Refuse outright when there is no evidence at all"
            hint="Skips the model entirely and returns a fixed message. Cheaper and strictly safer; leaving it off keeps the model able to answer the parts of a question that need no findings."
          />
        </div>
      )}
    </div>
  );
}

// --- output verification ----------------------------------------------------

const MODES: { value: string; label: string; hint: string }[] = [
  { value: "OFF", label: "Off", hint: "No checking." },
  {
    value: "MONITOR",
    label: "Monitor",
    hint: "Check and record the verdict. The answer goes out unchanged — start here, so you find out how often your pipeline would have been stopped before you let it stop anything.",
  },
  {
    value: "ENFORCE",
    label: "Enforce",
    hint: "Replace an answer that fails with one that states the findings plainly. This changes what a real user reads.",
  },
];

function VerificationControls({
  p,
  busy,
  onChange,
}: {
  p: Pipeline;
  busy: boolean;
  onChange: (b: { verificationMode?: string }) => void;
}) {
  const mode = MODES.find((m) => m.value === p.verificationMode) ?? MODES[0];
  return (
    <div className="rounded-md border border-edge/60 p-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <Micro>Output verification</Micro>
        <span className="micro text-slate-500">{p.verificationMode}</span>
      </div>
      <p className="mt-1 text-xs text-slate-500">
        Does the advice match the findings? The confidence policy asks the model to behave and
        checks whether it did — both are about the instruction. This asks whether the answer is
        anchored to what the specialists actually found.
      </p>

      <div className="mt-3 flex flex-wrap gap-1.5">
        {MODES.map((m) => (
          <button
            key={m.value}
            disabled={busy}
            onClick={() => onChange({ verificationMode: m.value })}
            className={`rounded-md border px-2.5 py-1 text-xs transition-colors ${
              p.verificationMode === m.value
                ? "border-aurora/60 bg-aurora/10 text-slate-200"
                : "border-edge text-slate-400 hover:border-slate-600"
            } disabled:opacity-40`}
          >
            {m.label}
          </button>
        ))}
      </div>
      <p className="mt-2 text-xs text-slate-600">{mode.hint}</p>

      <p className="mt-2 text-xs text-slate-600">
        Three checks: certainty beyond the evidence, invented confidence figures, and which
        findings the advice actually addresses. Only the first two can fail an answer — coverage
        matching is lexical, so a model writing "laceration" for a finding labelled "open wound"
        reads as uncovered while having covered it perfectly, and failing that would punish good
        writing.
      </p>
    </div>
  );
}

const VERDICT_TONE: Record<string, string> = {
  OK: "text-emerald-400 border-emerald-500/40 bg-emerald-500/5",
  WARN: "text-amber-400 border-amber-500/40 bg-amber-500/5",
  FAIL: "text-rose-400 border-rose-500/40 bg-rose-500/5",
};

function VerificationVerdict({ v }: { v: Verification }) {
  return (
    <div className={`rounded-md border px-3 py-2.5 ${VERDICT_TONE[v.verdict]}`}>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span className="text-sm font-medium">
          {v.verdict === "OK"
            ? "The answer matches the findings."
            : v.verdict === "WARN"
              ? "The answer matches, with something worth noting."
              : "The answer does NOT match the findings."}
        </span>
        {v.replaced && <span className="micro">answer replaced</span>}
        <span className="micro opacity-70">measured, {v.method}</span>
      </div>

      {v.issues.length > 0 && (
        <ul className="mt-2 space-y-1">
          {v.issues.map((i, n) => (
            <li key={n} className="text-xs text-slate-400">
              <span className={i.severe ? "text-rose-400" : "text-amber-400"}>
                {i.severe ? "✕" : "!"}
              </span>{" "}
              {i.detail}
            </li>
          ))}
        </ul>
      )}

      {(v.covered.length > 0 || v.uncovered.length > 0) && (
        <p className="mt-2 text-xs text-slate-500">
          {v.covered.length > 0 && <>Addressed: {v.covered.join(", ")}. </>}
          {v.uncovered.length > 0 && <>Not mentioned: {v.uncovered.join(", ")}.</>}
        </p>
      )}

      {v.replaced && (
        <p className="mt-2 text-xs text-slate-500">
          The model's answer was discarded and replaced with one that states the findings plainly.
          Your application received the replacement, not the original.
        </p>
      )}
    </div>
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
              {run.policy && <PolicyVerdict policy={run.policy} compliance={run.compliance} />}

              {run.verification && <VerificationVerdict v={run.verification} />}

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

/**
 * The band the evidence landed in, and — separately — whether the model
 * actually did what it was asked.
 *
 * <p>Those two are kept visually apart on purpose. The policy can only ever
 * request; showing "hedged" because a hedge was requested would report intent as
 * observation, which is the failure this whole feature exists to avoid.
 */
function PolicyVerdict({ policy, compliance }: { policy: Policy; compliance: Compliance | null }) {
  const copy = BAND_COPY[policy.band];
  return (
    <div className={`rounded-md border px-3 py-2.5 ${copy.tone}`}>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
        <span className="text-sm font-medium">{copy.title}</span>
        {policy.evidence > 0 && (
          <span className="readout text-xs opacity-80">
            strongest finding {policy.evidence.toFixed(2)}
          </span>
        )}
        {policy.declined && <span className="micro">no model was called</span>}
      </div>
      <p className="mt-1 text-xs text-slate-400">{copy.note}</p>

      {compliance?.checked && (
        <div className="mt-2.5 border-t border-current/20 pt-2">
          <div className="flex flex-wrap items-baseline gap-x-2">
            <span
              className={`text-xs font-medium ${
                compliance.complied ? "text-emerald-400" : "text-rose-400"
              }`}
            >
              {compliance.complied
                ? "The model did what the policy asked."
                : "The model ignored the policy."}
            </span>
            <span className="micro opacity-70">measured, {compliance.method}</span>
          </div>
          {compliance.markers.length > 0 && (
            <p className="mt-1 text-xs text-slate-500">
              Found in the answer: {compliance.markers.map((m) => `"${m}"`).join(", ")}
            </p>
          )}
          {compliance.flatAssertions.length > 0 && (
            <p className="mt-1 text-xs text-amber-400/90">
              Flat assertions despite the instruction:{" "}
              {compliance.flatAssertions.map((m) => `"${m}"`).join(", ")}
            </p>
          )}
          <p className="mt-1 text-xs text-slate-600">
            Checked on the answer that came back, not assumed from the instruction — the policy can
            only ask, and a page that reported success because it asked would be reporting its own
            intent. The check is lexical: it reliably catches an instruction that produced flat,
            unqualified prose, and cannot tell a real hedge from a decorative one.
          </p>
        </div>
      )}
    </div>
  );
}

const KIND_NOTE: Record<string, string> = {
  INPUT: "What your application sent. It is never forwarded to the language model.",
  SPECIALIST: "A purpose-trained model looked at the raw input and reported what it saw.",
  ENRICHMENT: "The findings became words a language model can reason about, with the confidence stated rather than implied.",
  POLICY: "How strong the evidence is, and what the model is therefore allowed to do with it.",
  VERIFY: "Whether the model actually did what the policy asked — measured on the answer, not assumed from the instruction.",
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
    () => ["INPUT", "SPECIALIST", "ENRICHMENT", "POLICY", "MODEL", "OUTPUT"], []);

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
        // CONSTRAINED and DECLINED are the policy working, not something
        // breaking. Painting them the same red as a dead specialist would teach
        // people to ignore the colour.
        const bad = s.status === "FAILED" || s.status === "IGNORED";
        const acted = s.status === "CONSTRAINED" || s.status === "DECLINED"
          || s.status === "DEGRADED" || s.status === "WARNED" || s.status === "REPLACED";
        // A skipped step is neither a failure nor an intervention — it is work
        // that was correctly not done, and it reads as absence.
        const skippedStep = s.status === "SKIPPED";
        return (
          <div
            key={s.ordinal}
            className={`transition-all duration-300 ${visible ? "opacity-100" : "translate-y-1 opacity-0"}`}
          >
            <button
              onClick={() => onDetail(detail === s.ordinal ? null : s.ordinal)}
              className={`flex w-full items-center gap-3 rounded-md border px-3 py-2 text-left ${
                bad
                  ? "border-rose-500/40 bg-rose-500/5"
                  : acted
                    ? "border-amber-500/40 bg-amber-500/5"
                    : skippedStep
                      ? "border-dashed border-edge/50"
                      : "border-edge/60 hover:border-aurora/40"
              }`}
            >
              <span
                className={`h-2 w-2 shrink-0 rounded-full ${
                  bad
                    ? "bg-rose-400"
                    : acted
                      ? "bg-amber-400"
                      : skippedStep
                        ? "bg-slate-600"
                        : s.kind === "MODEL"
                          ? "bg-indigo-400"
                          : "bg-emerald-400"
                }`}
              />
              <span className="micro w-24 shrink-0">{s.kind}</span>
              <span
                className={`min-w-0 flex-1 truncate text-sm ${
                  skippedStep ? "text-slate-500" : "text-slate-300"
                }`}
              >
                {s.label}
              </span>
              {s.confidence != null && (
                <span className="readout shrink-0 text-xs text-slate-400">
                  {s.confidence.toFixed(2)}
                </span>
              )}
              {s.latencyMs > 0 && (
                <span className="readout shrink-0 text-xs text-slate-500">{s.latencyMs}ms</span>
              )}
              {(bad || acted || skippedStep) && (
                <span
                  className={`micro shrink-0 ${
                    bad ? "text-rose-400" : acted ? "text-amber-400" : "text-slate-500"
                  }`}
                >
                  {s.status}
                </span>
              )}
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
  // A skipped step's reason is the whole story, so it is shown as a sentence
  // rather than buried in a JSON blob.
  if (step.status === "SKIPPED") {
    return (
      <div className="mt-2">
        <p className="text-sm text-slate-300">{step.detail?.reason ?? "Condition not met."}</p>
        <p className="mt-1 text-xs text-slate-600">
          Recorded rather than left out. A step that did not run and a step that ran and found
          nothing look identical in the answer, and they need different fixes.
        </p>
      </div>
    );
  }

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
