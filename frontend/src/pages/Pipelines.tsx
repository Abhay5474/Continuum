import { useCallback, useEffect, useMemo, useState } from "react";
import type { ReactNode } from "react";
import { portal } from "../api";
import { Micro, Switch } from "../system/primitives";
import {
  Bar,
  CommandBar,
  Code,
  Dot,
  Empty,
  Facts,
  Field,
  Flow,
  Ghost,
  KindMark,
  Primary,
  Rail,
  Row,
  RowSkeleton,
  Section,
  Segmented,
  SidePanel,
  Spine,
  SpineNode,
  Split,
  Stat,
  Stats,
  kindOf, Chip, Notice } from "../system/hub";
import { ErrorState, useToast } from "../components/ui";

/**
 * Pipelines — what an external application actually calls.
 *
 * <p>The application sends an image and a question, and receives advice. It is
 * never told that a detector ran, what it was called, or who hosts it.
 *
 * <p>The chain is the page. Everything else here is configuration; the reason to
 * open this page is to watch a request go through the layer and come out the
 * other side, and to be able to read the exact words the model was given. A
 * pipeline described in prose is a promise. A pipeline whose chain you can watch
 * is a fact.
 *
 * <p><b>On the shape of this screen.</b> It used to be a stack of cards, each of
 * which opened into an accordion holding five bordered boxes stacked vertically:
 * routing, policy, verification, endpoint, try-it. That layout asserted those
 * five were equally important and simultaneously relevant, which is false — you
 * are doing exactly one of them at a time, and nine visits out of ten it is the
 * last one. So: the list of pipelines stays put on the left, the one you picked
 * fills the right, and its areas are a segmented control that opens on Run.
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

const BAND_COPY: Record<Band, { title: string; note: string; tone: "ok" | "warn" | "bad" | "idle" }> = {
  STRONG: {
    title: "Strong evidence",
    note: "Answered directly. Nothing was added to the prompt — a strong finding needs no instruction, and adding one would make every answer read as unsure.",
    tone: "ok",
  },
  MEDIUM: {
    title: "Moderate evidence",
    note: "The model was told to state its uncertainty out loud rather than leave it implied.",
    tone: "warn",
  },
  WEAK: {
    title: "Too weak to advise on",
    note: "The model was forbidden from naming a condition and told to ask for better input instead. This is what stops a 0.31 detection becoming confident advice.",
    tone: "warn",
  },
  NONE: {
    title: "Nothing found",
    note: "The analysis ran and found nothing above the reporting threshold. That is a real result — the model may still answer parts of the question that need no findings.",
    tone: "idle",
  },
  UNAVAILABLE: {
    title: "No analysis available",
    note: "No specialist answered, so nothing was examined. Not the same as finding nothing, and the model was explicitly forbidden from presenting it as an all-clear.",
    tone: "bad",
  },
};

/** A 1×1 PNG. Enough to exercise the whole chain without asking for a file. */
const SAMPLE_IMAGE =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

const INPUT_LABEL: Record<string, string> = {
  image: "an image",
  text: "text",
  json: "structured data",
  audio: "a recording",
};

type Area = "run" | "chain" | "guards" | "endpoint";

export default function Pipelines() {
  const toast = useToast();
  const [pipelines, setPipelines] = useState<Pipeline[] | null>(null);
  const [specialists, setSpecialists] = useState<Specialist[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<number | null>(null);
  const [area, setArea] = useState<Area>("run");
  const [q, setQ] = useState("");
  const [creating, setCreating] = useState(false);

  const load = useCallback(async () => {
    try {
      const [p, s] = await Promise.all([portal.pipelines.list(), portal.specialists.list()]);
      setPipelines(p);
      setSpecialists(s);
      setError(null);
      // Opening on nothing selected would show an empty right-hand column beside
      // a full list, which reads as broken rather than as a choice to be made.
      setSelected((cur) => (cur != null && p.some((x: Pipeline) => x.id === cur) ? cur : p[0]?.id ?? null));
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

  const all = pipelines ?? [];
  const needle = q.trim().toLowerCase();
  const shown = needle
    ? all.filter((p) =>
        [p.name, p.description ?? "", p.inputKind].join(" ").toLowerCase().includes(needle)
      )
    : all;
  const live = all.filter((p) => p.enabled).length;
  const guarded = all.filter((p) => p.policyEnabled).length;
  const totalRuns = all.reduce((n, p) => n + p.runs, 0);
  const current = all.find((p) => p.id === selected) ?? null;

  return (
    <section className="page-enter">
      <header>
        <div className="flex items-center gap-2.5">
          <Chip glyph="flow" tone="accent" size={34} />
          <h1 className="text-[22px] font-semibold tracking-tight text-slate-100">Pipelines</h1>
        </div>
        <p className="mt-1 max-w-2xl text-[13px] leading-relaxed text-slate-500">
          Your application sends an input and a question, and gets an answer back. It never learns a
          specialist was involved, which one, or who hosts it.
        </p>
      </header>

      <div className="mt-6">
        <Stats>
          <Stat label="Pipelines" value={all.length} />
          <Stat
            label="Accepting requests"
            value={live}
            tone={live > 0 ? "ok" : undefined}
            hint="A pipeline is off until you turn it on. Nothing runs by default."
          />
          <Stat
            label="Policy on"
            value={guarded}
            tone={guarded > 0 ? "accent" : undefined}
            hint="Pipelines where the strength of the evidence decides what the model may do with it."
          />
          <Stat label="Runs" value={totalRuns} />
        </Stats>
      </div>

      {ready.length === 0 && (
        <div className="mt-6">
          <Notice
            tone="warn"
            title="No probed specialist yet"
            body={
              <>
                A pipeline whose specialist has never answered moves the failure from here, where you
                are looking at it, to a customer's request, where you are not.
              </>
            }
            right={
              <a
                href="/specialists"
                style={{ color: "var(--accent-ink)" }}
                className="underline underline-offset-2"
              >
                Register one and probe it
              </a>
            }
          />
        </div>
      )}

      {/* Before there is anything to list, the split is the wrong shape: the
          explanation of what a pipeline is would be set in a 254px column
          beside an empty pane. So the first-run state gets the whole width. */}
      {pipelines !== null && all.length === 0 ? (
        <div className="mt-10 max-w-2xl">
          <h2 className="text-[15px] font-semibold tracking-tight text-slate-100">
            No pipelines yet
          </h2>
          <p className="mt-2 text-[13px] leading-relaxed text-slate-500">
            A pipeline is the endpoint your application calls. It takes the raw input, runs your
            specialists over it, turns what they found into something a language model can reason
            about, and returns the answer — without the model ever seeing the input itself.
          </p>
          <div className="mt-5">
            <Flow
              input="an image and a question"
              node="your specialists, then the model"
              nodeSub="findings in, prose out"
              output="an answer, and the chain behind it"
            />
          </div>
          <div className="mt-6">
            <Primary onClick={() => setCreating(true)} disabled={ready.length === 0}>
              New pipeline
            </Primary>
          </div>
        </div>
      ) : (
      <div className="mt-8">
        <Split
          list={
            <Section
              title="Pipelines"
              count={all.length}
              action={
                <Ghost tone="accent" onClick={() => setCreating(true)} disabled={ready.length === 0}>
                  New
                </Ghost>
              }
            >
              {all.length > 6 && (
                <div className="mb-3">
                  <CommandBar value={q} onChange={setQ} placeholder="Filter pipelines" />
                </div>
              )}
              {pipelines === null ? (
                <RowSkeleton rows={3} />
              ) : shown.length === 0 ? (
                <Empty
                  title={all.length === 0 ? "No pipelines yet" : "Nothing matches"}
                  hint={
                    all.length === 0
                      ? "A pipeline is the endpoint your application calls: it takes the raw input, runs your specialists over it, turns what they found into something a language model can reason about, and returns the answer."
                      : undefined
                  }
                />
              ) : (
                <Rail>
                  {shown.map((p) => (
                    <Row
                      key={p.id}
                      mark={<KindMark kind={kindOf(p.inputKind)} size={28} />}
                      title={p.name}
                      subtitle={p.description || `${p.steps.length} step${p.steps.length === 1 ? "" : "s"}`}
                      status={<Dot tone={p.enabled ? "ok" : "idle"} label={p.enabled ? "live" : "off"} />}
                      selected={p.id === selected}
                      onClick={() => {
                        setSelected(p.id);
                        setArea("run");
                      }}
                    />
                  ))}
                </Rail>
              )}
            </Section>
          }
          detail={
            current ? (
              <Detail
                key={current.id}
                p={current}
                specialists={specialists}
                busy={busy}
                area={area}
                onArea={setArea}
                onEnable={(next) =>
                  act(
                    () => portal.pipelines.update(current.id, { enabled: next }),
                    next ? `"${current.name}" is live.` : `"${current.name}" is off.`
                  )
                }
                onDelete={() => act(() => portal.pipelines.remove(current.id), "Pipeline removed.")}
                onRan={load}
                onPolicy={(body) => act(() => portal.pipelines.update(current.id, body))}
              />
            ) : (
              <div />
            )
          }
        />
      </div>
      )}

      <NewPipeline
        open={creating}
        specialists={ready}
        busy={busy}
        onClose={() => setCreating(false)}
        onAdd={(body) =>
          act(
            () => portal.pipelines.add(body),
            `Pipeline "${body.name}" created — it is off until you enable it.`
          )
        }
      />
    </section>
  );
}

/* -------------------------------------------------------------------------- *
 * The selected pipeline
 * -------------------------------------------------------------------------- */

function Detail({
  p,
  specialists,
  busy,
  area,
  onArea,
  onEnable,
  onDelete,
  onRan,
  onPolicy,
}: {
  p: Pipeline;
  specialists: Specialist[];
  busy: boolean;
  area: Area;
  onArea: (a: Area) => void;
  onEnable: (next: boolean) => void;
  onDelete: () => void;
  onRan: () => void;
  onPolicy: (body: {
    policyEnabled?: boolean; strongThreshold?: number; weakThreshold?: number;
    declineOnNoEvidence?: boolean; routingEnabled?: boolean; routing?: RoutingStep[];
    verificationMode?: string;
  }) => void;
}) {
  return (
    <div>
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2.5 text-[17px] font-semibold tracking-tight text-slate-100">
            {p.name}
            <Dot tone={p.enabled ? "ok" : "idle"} label={p.enabled ? "accepting requests" : "off"} />
          </h2>
          {p.description && <p className="mt-1 text-[12.5px] text-slate-500">{p.description}</p>}
          <div className="mt-2">
            <Facts
              items={[
                { k: "accepts", v: INPUT_LABEL[p.inputKind] ?? p.inputKind },
                { k: "runs", v: p.runs },
                { k: "policy", v: p.policyEnabled ? "on" : "off" },
                { k: "verification", v: p.verificationMode.toLowerCase() },
              ]}
            />
          </div>
        </div>
        <Ghost tone="danger" onClick={onDelete} disabled={busy}>
          Remove
        </Ghost>
      </div>

      {/* The chain, drawn. This is the answer to "what is this thing" and it
          belongs above every control, not inside one of them. */}
      <div className="mt-5">
        <ChainDiagram p={p} specialists={specialists} />
      </div>

      <div className="mt-6">
        <Segmented<Area>
          value={area}
          onChange={onArea}
          options={[
            { value: "run", label: "Run it" },
            { value: "chain", label: "Chain" },
            {
              value: "guards",
              label: "Guards",
              badge:
                p.policyEnabled || p.verificationMode !== "OFF" ? (
                  <span
                    className="h-1.5 w-1.5 rounded-full"
                    style={{ background: "var(--accent)" }}
                    aria-hidden
                  />
                ) : undefined,
            },
            { value: "endpoint", label: "Endpoint" },
          ]}
        />
      </div>

      <div className="mt-5">
        {area === "run" && <TryIt pipeline={p} onRan={onRan} />}
        {area === "chain" && (
          <RoutingControls p={p} specialists={specialists} busy={busy} onChange={onPolicy} />
        )}
        {area === "guards" && (
          <div className="space-y-7">
            <PolicyControls p={p} busy={busy} onChange={onPolicy} />
            <VerificationControls p={p} busy={busy} onChange={onPolicy} />
          </div>
        )}
        {area === "endpoint" && <Endpoint p={p} busy={busy} onEnable={onEnable} />}
      </div>
    </div>
  );
}

/**
 * Input, the specialists in order, the model, the answer.
 *
 * <p>The old page said this in a sentence — {@code "detector → classifier"} —
 * next to the description. A sentence cannot show that the model never sees the
 * image, which is the single most important fact about the architecture, so this
 * draws it: the input terminates at the specialists, and only their findings
 * carry on to the right.
 */
function ChainDiagram({ p, specialists }: { p: Pipeline; specialists: Specialist[] }) {
  const named = p.steps.map((id) => specialists.find((s) => s.id === id) ?? null);
  const Node = ({
    children,
    accent = false,
    bad = false,
    sub,
    mark,
  }: {
    children: ReactNode;
    accent?: boolean;
    /** A specialist that has never answered. Stated here rather than only on
        its own page, because a chain drawn as healthy when a link in it is dead
        is the diagram lying. */
    bad?: boolean;
    sub?: ReactNode;
    mark?: ReactNode;
  }) => (
    <div
      className="flex min-w-0 shrink-0 items-center gap-2 rounded-lg px-3 py-2"
      style={{
        background: bad
          ? "color-mix(in srgb, var(--state-critical-ink) 7%, transparent)"
          : accent
            ? "var(--accent-wash)"
            : "rgb(var(--panel))",
        boxShadow: bad
          ? "inset 0 0 0 1px color-mix(in srgb, var(--state-critical-ink) 34%, transparent)"
          : accent
            ? "inset 0 0 0 1px var(--accent-edge)"
            : "inset 0 0 0 1px rgb(var(--edge))",
      }}
    >
      {mark}
      <div className="min-w-0">
        <div className="truncate text-[12.5px] text-slate-200">{children}</div>
        {sub && (
          <div
            className="truncate text-[10.5px]"
            style={{ color: bad ? "var(--state-critical-ink)" : undefined }}
          >
            <span className={bad ? "" : "text-slate-500"}>{sub}</span>
          </div>
        )}
      </div>
    </div>
  );
  const Link = ({ label }: { label?: string }) => (
    <div className="flex shrink-0 flex-col items-center justify-center gap-0.5 px-0.5">
      <svg width="20" height="8" viewBox="0 0 20 8" fill="none" className="text-slate-700" aria-hidden>
        <path d="M0 4h15m0 0-3.5-3M15 4l-3.5 3" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      {label && <span className="text-[9.5px] leading-none text-slate-600">{label}</span>}
    </div>
  );

  return (
    <div className="-mx-1 overflow-x-auto px-1 pb-1">
      <div className="flex min-w-max items-center gap-1">
        <Node sub="from your app" mark={<KindMark kind={kindOf(p.inputKind)} size={24} />}>
          {INPUT_LABEL[p.inputKind] ?? p.inputKind}
        </Node>
        {named.length === 0 ? (
          <>
            <Link />
            <Node sub="nothing configured">no specialist</Node>
          </>
        ) : (
          named.map((s, i) => (
            <span key={i} className="flex items-center gap-1">
              <Link label={i === 0 ? "raw" : undefined} />
              <Node
                accent
                bad={s?.status === "FAILED" || s?.status === "UNPARSEABLE"}
                sub={s?.status === "READY" ? "ready" : (s?.status ?? "unknown").toLowerCase()}
                mark={<KindMark kind={kindOf(`${s?.name ?? ""} ${s?.inputKind ?? ""}`)} size={24} />}
              >
                {s?.name ?? `#${p.steps[i]}`}
              </Node>
            </span>
          ))
        )}
        <Link label="findings" />
        <Node sub="never sees the input">language model</Node>
        <Link />
        <Node sub="answer + trace">your app</Node>
      </div>
    </div>
  );
}

/* -------------------------------------------------------------------------- *
 * Endpoint
 * -------------------------------------------------------------------------- */

function Endpoint({
  p,
  busy,
  onEnable,
}: {
  p: Pipeline;
  busy: boolean;
  onEnable: (next: boolean) => void;
}) {
  return (
    <div className="space-y-5">
      <Switch
        checked={p.enabled}
        busy={busy}
        onChange={onEnable}
        label="Accept requests from your application"
        hint="Off by default. While it is off the endpoint answers 400 and nothing runs — so a half-configured pipeline cannot be reached by mistake."
      />
      <div>
        <Micro>The call your application makes</Micro>
        <div className="mt-1.5">
          <Code>
{`curl -X POST https://your-continuum/api/gateway/pipeline/${p.name} \\
  -H "Authorization: Bearer cnt_live_..." \\
  -H "content-type: application/json" \\
  -d '{"input":{"image":"<base64>"},"prompt":"What should I do?"}'`}
          </Code>
        </div>
        <p className="mt-2 max-w-2xl text-xs leading-relaxed text-slate-600">
          The response carries the answer and the chain behind it. Your application can show its user
          the working, or ignore the field entirely — but it never sees which provider ran or what
          credential was used.
        </p>
      </div>
    </div>
  );
}

/* -------------------------------------------------------------------------- *
 * Creation
 * -------------------------------------------------------------------------- */

function NewPipeline({
  open,
  specialists,
  busy,
  onClose,
  onAdd,
}: {
  open: boolean;
  specialists: Specialist[];
  busy: boolean;
  onClose: () => void;
  onAdd: (b: { name: string; description: string; inputKind: string; systemPrompt: string; steps: number[] }) => void;
}) {
  const [name, setName] = useState("");
  const [description, setDescription] = useState("");
  const [inputKind, setInputKind] = useState("image");
  const [systemPrompt, setSystemPrompt] = useState("");
  const [steps, setSteps] = useState<number[]>([]);

  const toggleStep = (id: number) =>
    setSteps((s) => (s.includes(id) ? s.filter((x) => x !== id) : [...s, id]));

  const submit = () => {
    onAdd({ name: name.trim(), description, inputKind, systemPrompt, steps });
    setName("");
    setDescription("");
    setSystemPrompt("");
    setSteps([]);
    onClose();
  };

  return (
    <SidePanel
      open={open}
      title="New pipeline"
      subtitle="Off until you enable it"
      onClose={onClose}
      footer={
        <div className="flex items-center justify-between gap-3">
          <span className="text-[11.5px] text-slate-600">
            {steps.length === 0 ? "Pick at least one specialist" : `${steps.length} in the chain`}
          </span>
          <Primary disabled={busy || !name.trim() || steps.length === 0} onClick={submit}>
            Create pipeline
          </Primary>
        </div>
      }
    >
      <Field label="Name">
        <input
          value={name}
          onChange={(e) => setName(e.target.value)}
          placeholder="animal-triage"
          className="w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-[color:var(--accent-edge)]"
        />
        <p className="mt-1 text-[11.5px] text-slate-600">
          This goes in the URL your application calls, so letters, digits, hyphen and underscore only.
        </p>
      </Field>

      <Field label="Input your app sends">
        <select
          value={inputKind}
          onChange={(e) => setInputKind(e.target.value)}
          className="w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-[color:var(--accent-edge)]"
        >
          <option value="image">Image</option>
          <option value="text">Text</option>
          <option value="json">Data</option>
          <option value="audio">Audio</option>
        </select>
      </Field>

      <Field label="What it does">
        <input
          value={description}
          onChange={(e) => setDescription(e.target.value)}
          placeholder="Photo of an injured animal in, first-aid advice out"
          className="w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-[color:var(--accent-edge)]"
        />
      </Field>

      <Field label="Standing instructions for the model">
        <textarea
          value={systemPrompt}
          onChange={(e) => setSystemPrompt(e.target.value)}
          rows={4}
          placeholder="You are advising a member of the public on immediate first aid for an injured animal. Be brief and practical. Always say when a vet is needed."
          className="w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-[color:var(--accent-edge)]"
        />
        <p className="mt-1 text-[11.5px] leading-relaxed text-slate-600">
          Sent with every call, so your application does not have to repeat it. The findings and the
          user's own question are added underneath.
        </p>
      </Field>

      <Field label="Specialists, in the order they run">
        <div className="-mx-1">
          <Rail>
            {specialists.map((s) => {
              const idx = steps.indexOf(s.id);
              return (
                <Row
                  key={s.id}
                  mark={
                    <span
                      className="readout grid h-6 w-6 shrink-0 place-items-center rounded-full text-[10px]"
                      style={
                        idx >= 0
                          ? { background: "var(--accent-strong)", color: "#fff" }
                          : { background: "rgb(var(--edge))", color: "var(--text-3)" }
                      }
                    >
                      {idx >= 0 ? idx + 1 : "·"}
                    </span>
                  }
                  title={s.name}
                  subtitle={`takes ${INPUT_LABEL[s.inputKind] ?? s.inputKind}`}
                  selected={idx >= 0}
                  onClick={() => toggleStep(s.id)}
                />
              );
            })}
          </Rail>
        </div>
      </Field>
    </SidePanel>
  );
}

/* -------------------------------------------------------------------------- *
 * Specialist routing
 * -------------------------------------------------------------------------- */

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
    <div>
      <Switch
        checked={p.routingEnabled}
        busy={busy}
        onChange={(next) => onChange({ routingEnabled: next })}
        label="Specialist routing"
        hint="Off by default — every step runs, which is what this pipeline did before. When on, each step's condition decides whether it runs at all."
      />

      {/* On the spine, because these are steps in an order and the condition on
          step three is about what step two found. */}
      <div className="mt-6">
        <Spine>
          {steps.map((s, i) => {
            const meta = CONDITIONS.find((c) => c.value === s.when) ?? CONDITIONS[0];
            const first = i === 0;
            const impossible = first && (s.when === "IF_PREVIOUS_FOUND" || s.when === "IF_PREVIOUS_EMPTY");
            const conditional = s.when !== "ALWAYS";
            return (
              <SpineNode
                key={`${s.specialistId}-${i}`}
                index={i}
                tone={impossible ? "bad" : conditional ? "accent" : "idle"}
                head={name(s.specialistId)}
                aside={meta.hint}
                open
              >
                <div className="grid gap-3 pb-2 sm:grid-cols-2">
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
                      className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-[12.5px] text-slate-200 outline-none focus:border-[color:var(--accent-edge)]"
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
                        className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 font-mono text-[12.5px] text-slate-200 outline-none focus:border-[color:var(--accent-edge)]"
                      />
                    </label>
                  )}
                </div>

                {impossible && (
                  <p className="pb-2 text-xs" style={{ color: "var(--state-critical-ink)" }}>
                    Nothing runs before this one, so there is no previous step for the condition to
                    look at.
                  </p>
                )}
              </SpineNode>
            );
          })}
        </Spine>
      </div>

      {!p.routingEnabled && steps.some((s) => s.when !== "ALWAYS") && (
        <p className="mt-4 text-xs" style={{ color: "var(--state-warning-ink)" }}>
          These conditions are saved but not in force — routing is off, so every step still runs.
        </p>
      )}
    </div>
  );
}

/* -------------------------------------------------------------------------- *
 * Confidence policy
 * -------------------------------------------------------------------------- */

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
    <div>
      <Switch
        checked={p.policyEnabled}
        busy={busy}
        onChange={(next) => onChange({ policyEnabled: next })}
        label="Confidence policy"
        hint="Off by default. When on, the strength of the evidence decides what the model is allowed to do with it — answer plainly, hedge, or ask for something better."
      />

      {p.policyEnabled && (
        <div className="mt-5 space-y-5">
          {/* The bands as a single bar, because they are only meaningful
              relative to each other and to the numbers on either side. */}
          <div>
            <div className="flex h-7 w-full overflow-hidden rounded-md" style={{ boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }}>
              <div
                className="flex items-center justify-center text-[10px] transition-[width] duration-200"
                style={{
                  width: `${weak * 100}%`,
                  background: "color-mix(in srgb, var(--state-degraded-ink) 22%, transparent)",
                  color: "var(--state-degraded-ink)",
                }}
                title="Too weak to advise on"
              >
                {weak >= 0.18 && "ask for better"}
              </div>
              <div
                className="flex items-center justify-center text-[10px] transition-[width] duration-200"
                style={{
                  width: `${(strong - weak) * 100}%`,
                  background: "color-mix(in srgb, var(--state-warning-ink) 22%, transparent)",
                  color: "var(--state-warning-ink)",
                }}
                title="Moderate — model told to hedge"
              >
                {strong - weak >= 0.14 && "hedge"}
              </div>
              <div
                className="flex items-center justify-center text-[10px] transition-[width] duration-200"
                style={{
                  width: `${(1 - strong) * 100}%`,
                  background: "color-mix(in srgb, var(--state-healthy-ink) 20%, transparent)",
                  color: "var(--state-healthy-ink)",
                }}
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

          <div className="grid gap-4 sm:grid-cols-2">
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
                className="mt-1 w-full"
                style={{ accentColor: "var(--state-warning-ink)" }}
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
                className="mt-1 w-full"
                style={{ accentColor: "var(--state-healthy-ink)" }}
                aria-label="Strong threshold"
              />
            </label>
          </div>

          {invalid && (
            <p className="text-xs" style={{ color: "var(--state-critical-ink)" }}>
              The weak threshold cannot sit above the strong one — there would be no middle band left
              to hedge in, which looks like a working policy and silently isn't.
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

/* -------------------------------------------------------------------------- *
 * Output verification
 * -------------------------------------------------------------------------- */

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
    <div>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-[13px] font-semibold tracking-tight text-slate-200">Output verification</h3>
        <span className="micro">{p.verificationMode}</span>
      </div>
      <p className="mt-1 max-w-2xl text-xs leading-relaxed text-slate-500">
        Does the advice match the findings? The confidence policy asks the model to behave and checks
        whether it did — both are about the instruction. This asks whether the answer is anchored to
        what the specialists actually found.
      </p>

      <div className="mt-3 flex flex-wrap gap-1.5">
        {MODES.map((m) => {
          const on = p.verificationMode === m.value;
          return (
            <button
              key={m.value}
              disabled={busy}
              onClick={() => onChange({ verificationMode: m.value })}
              style={
                on
                  ? { background: "var(--accent-wash)", boxShadow: "inset 0 0 0 1px var(--accent-edge)", color: "var(--accent-ink)" }
                  : { boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }
              }
              className={`rounded-md px-2.5 py-1 text-xs transition-colors disabled:opacity-40 ${
                on ? "" : "text-slate-400 hover:text-slate-200"
              }`}
            >
              {m.label}
            </button>
          );
        })}
      </div>
      <p className="mt-2 max-w-2xl text-xs leading-relaxed text-slate-500">{mode.hint}</p>

      <p className="mt-2.5 max-w-2xl text-xs leading-relaxed text-slate-600">
        Three checks: certainty beyond the evidence, invented confidence figures, and which findings
        the advice actually addresses. Only the first two can fail an answer — coverage matching is
        lexical, so a model writing "laceration" for a finding labelled "open wound" reads as
        uncovered while having covered it perfectly, and failing that would punish good writing.
      </p>
    </div>
  );
}

/**
 * A verdict, stated rather than framed.
 *
 * <p>Full-width tinted panels were used for all three of these, so a run with a
 * policy verdict and a verification verdict produced two stacked coloured slabs
 * and the answer underneath them looked like an afterthought. A coloured rule
 * down the left carries the same state at a tenth of the ink.
 */
function Verdict({
  tone,
  title,
  children,
}: {
  tone: "ok" | "warn" | "bad" | "idle";
  title: ReactNode;
  children?: ReactNode;
}) {
  const colour = {
    ok: "var(--state-healthy-ink)",
    warn: "var(--state-warning-ink)",
    bad: "var(--state-critical-ink)",
    idle: "var(--state-idle-ink)",
  }[tone];
  return (
    <div className="border-l-2 pl-3.5" style={{ borderColor: colour }}>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-[13px] font-medium" style={{ color: colour }}>
        {title}
      </div>
      {children}
    </div>
  );
}

function VerificationVerdict({ v }: { v: Verification }) {
  const tone = v.verdict === "OK" ? "ok" : v.verdict === "WARN" ? "warn" : "bad";
  return (
    <Verdict
      tone={tone}
      title={
        <>
          <span>
            {v.verdict === "OK"
              ? "The answer matches the findings."
              : v.verdict === "WARN"
                ? "The answer matches, with something worth noting."
                : "The answer does NOT match the findings."}
          </span>
          {v.replaced && <span className="micro">answer replaced</span>}
          <span className="micro opacity-70">measured, {v.method}</span>
        </>
      }
    >
      {v.issues.length > 0 && (
        <ul className="mt-1.5 space-y-1">
          {v.issues.map((i, n) => (
            <li key={n} className="text-xs text-slate-400">
              <span style={{ color: i.severe ? "var(--state-critical-ink)" : "var(--state-warning-ink)" }}>
                {i.severe ? "✕" : "!"}
              </span>{" "}
              {i.detail}
            </li>
          ))}
        </ul>
      )}

      {(v.covered.length > 0 || v.uncovered.length > 0) && (
        <p className="mt-1.5 text-xs leading-relaxed text-slate-500 max-w-2xl">
          {v.covered.length > 0 && <>Addressed: {v.covered.join(", ")}. </>}
          {v.uncovered.length > 0 && <>Not mentioned: {v.uncovered.join(", ")}.</>}
        </p>
      )}

      {v.replaced && (
        <p className="mt-1.5 text-xs leading-relaxed text-slate-600 max-w-2xl">
          The model's answer was discarded and replaced with one that states the findings plainly.
          Your application received the replacement, not the original.
        </p>
      )}
    </Verdict>
  );
}

/* -------------------------------------------------------------------------- *
 * The chain, running
 * -------------------------------------------------------------------------- */

function TryIt({ pipeline, onRan }: { pipeline: Pipeline; onRan: () => void }) {
  const toast = useToast();
  const [prompt, setPrompt] = useState("What should I do right now?");
  const [payload, setPayload] = useState(SAMPLE_IMAGE);
  const [run, setRun] = useState<Run | null>(null);
  const [running, setRunning] = useState(false);
  /** A chosen file wins over the pasted payload; null means use the textarea. */
  const [file, setFile] = useState<File | null>(null);
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
      // A file, when one was chosen, goes up as a file. Base64-ing it in the
      // browser first would only mean the server decoding it again.
      const r = (file
        ? await portal.pipelines.runFile(pipeline.name, file, prompt)
        : await portal.pipelines.run(pipeline.name, { [inputField]: payload }, prompt)) as Run;
      setRun(r);
      onRan();
    } catch (e: any) {
      toast(e?.message ?? "The run did not complete.", "error");
    } finally {
      setRunning(false);
    }
  };

  const done = run && shown >= run.trace.length;

  return (
    <div>
      <p className="max-w-2xl text-xs leading-relaxed text-slate-500">
        Runs the same code path your application would, including a disabled pipeline's refusal — a
        test route that skipped a step would be worse than no test route.
      </p>

      <div className="mt-4 grid gap-4 sm:grid-cols-[minmax(0,1fr)_auto]">
        <div className="min-w-0 space-y-3">
          <div className="flex flex-wrap items-center gap-2">
            <label
              className="cursor-pointer rounded-md px-2.5 py-1 text-[11.5px] text-slate-300 transition-colors hover:text-slate-100"
              style={{ boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }}
            >
              Upload a file
              <input
                type="file"
                className="hidden"
                onChange={(e) => setFile(e.target.files?.[0] ?? null)}
              />
            </label>
            {file ? (
              <span className="flex items-center gap-2 text-xs text-slate-400">
                <span className="font-mono">{file.name}</span>
                <span className="text-slate-600">{(file.size / 1024).toFixed(0)} KB</span>
                <button
                  onClick={() => setFile(null)}
                  className="text-slate-500 underline hover:text-slate-300"
                >
                  remove
                </button>
              </span>
            ) : (
              <span className="max-w-md text-[11.5px] leading-relaxed text-slate-600">
                A PDF, image or audio file. Continuum reads what it is from the bytes — a
                born-digital PDF is read in place, with no OCR call and no recognition errors.
              </span>
            )}
          </div>

          <label className={`block ${file ? "opacity-40" : ""}`}>
            <Micro>{pipeline.inputKind === "image" ? "Image (base64)" : "Input"}</Micro>
            <textarea
              value={payload}
              onChange={(e) => setPayload(e.target.value)}
              disabled={!!file}
              rows={2}
              className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 font-mono text-[11px] text-slate-300 outline-none focus:border-[color:var(--accent-edge)]"
            />
            {file && <p className="mt-1 text-xs text-slate-600 max-w-2xl leading-relaxed">Ignored while a file is attached.</p>}
          </label>
          <label className="block">
            <Micro>What your user asked</Micro>
            <input
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-[color:var(--accent-edge)]"
            />
          </label>
        </div>
        <div className="self-end">
          <Primary
            onClick={go}
            disabled={running || !pipeline.enabled}
          >
            {running ? "Running…" : "Run"}
          </Primary>
          {!pipeline.enabled && (
            <p className="mt-1.5 max-w-[9rem] text-[11px] leading-snug text-slate-600">
              Enable the pipeline on the Endpoint tab first.
            </p>
          )}
        </div>
      </div>

      {running && <Chain steps={[]} shown={0} pending detail={null} onDetail={() => {}} />}

      {run && (
        <div className="mt-6 space-y-5">
          <Chain steps={run.trace} shown={shown} detail={detail} onDetail={setDetail} />

          {done && (
            <>
              {run.policy && <PolicyVerdict policy={run.policy} compliance={run.compliance} />}

              {run.verification && <VerificationVerdict v={run.verification} />}

              <Stats>
                <Stat label="Findings used" value={run.findings} />
                <Stat
                  label="Evidence"
                  value={run.anythingFound ? run.evidenceConfidence.toFixed(2) : "—"}
                  tone={
                    !run.analysisRan
                      ? "bad"
                      : !run.anythingFound
                        ? undefined
                        : run.evidenceConfidence >= 0.7
                          ? "ok"
                          : "warn"
                  }
                  hint="The strongest thing a specialist saw. Not the model's confidence in its own answer."
                />
                <Stat label="Model" value={run.model || "—"} />
                <Stat label="End to end" value={run.latencyMs} unit="ms" />
              </Stats>

              {!run.analysisRan && (
                <Verdict tone="bad" title="Nothing was examined.">
                  <p className="mt-1 max-w-2xl text-xs leading-relaxed text-slate-500">
                    No specialist answered. The model was told this explicitly rather than being left
                    to fill the silence — "we looked and found nothing" and "we never looked" are
                    different facts, and only the first one is evidence.
                  </p>
                </Verdict>
              )}

              <div>
                <Micro>What your application received</Micro>
                <div
                  className="mt-1.5 whitespace-pre-wrap rounded-lg px-3.5 py-3 text-sm leading-relaxed text-slate-300"
                  style={{ background: "rgb(var(--panel))", boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }}
                >
                  {run.response || "—"}
                </div>
              </div>

              <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
                Trace <span className="readout text-slate-500">{run.traceId}</span> — returned to your
                application so it can show its own user the same chain.
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
    <Verdict
      tone={copy.tone}
      title={
        <>
          <span>{copy.title}</span>
          {policy.evidence > 0 && (
            <span className="readout text-xs opacity-80">
              strongest finding {policy.evidence.toFixed(2)}
            </span>
          )}
          {policy.declined && <span className="micro">no model was called</span>}
        </>
      }
    >
      <p className="mt-1 max-w-2xl text-xs leading-relaxed text-slate-500">{copy.note}</p>

      {compliance?.checked && (
        <div className="mt-3 border-t border-edge/60 pt-2.5">
          <div className="flex flex-wrap items-baseline gap-x-2">
            <span
              className="text-xs font-medium"
              style={{
                color: compliance.complied ? "var(--state-healthy-ink)" : "var(--state-critical-ink)",
              }}
            >
              {compliance.complied
                ? "The model did what the policy asked."
                : "The model ignored the policy."}
            </span>
            <span className="micro opacity-70">measured, {compliance.method}</span>
          </div>
          {compliance.markers.length > 0 && (
            <p className="mt-1 text-xs text-slate-500 max-w-2xl leading-relaxed">
              Found in the answer: {compliance.markers.map((m) => `"${m}"`).join(", ")}
            </p>
          )}
          {compliance.flatAssertions.length > 0 && (
            <p className="mt-1 text-xs" style={{ color: "var(--state-warning-ink)" }}>
              Flat assertions despite the instruction:{" "}
              {compliance.flatAssertions.map((m) => `"${m}"`).join(", ")}
            </p>
          )}
          <p className="mt-1.5 max-w-2xl text-xs leading-relaxed text-slate-600">
            Checked on the answer that came back, not assumed from the instruction — the policy can
            only ask, and a page that reported success because it asked would be reporting its own
            intent. The check is lexical: it reliably catches an instruction that produced flat,
            unqualified prose, and cannot tell a real hedge from a decorative one.
          </p>
        </div>
      )}
    </Verdict>
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

/**
 * One request, as it went through the layer.
 *
 * <p>On a spine rather than as a stack of bordered rows. The border-per-step
 * version read as six unrelated records; the rail says these happened in this
 * order, and the node colour says which of them intervened.
 */
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

  // The slowest step sets the scale, so the latency bars compare against each
  // other rather than against an arbitrary ceiling.
  const slowest = Math.max(1, ...steps.map((s) => s.latencyMs));

  if (pending) {
    return (
      <div className="mt-5" aria-label="running">
        <Spine>
          {skeleton.map((k, i) => (
            <SpineNode
              key={k}
              index={i}
              tone="idle"
              head={<span className="text-slate-500">{k.toLowerCase()}</span>}
              trailing={
                <span
                  className="h-1.5 w-1.5 shrink-0 animate-pulse rounded-full"
                  style={{ background: "var(--accent)", animationDelay: `${i * 120}ms` }}
                  aria-hidden
                />
              }
            />
          ))}
        </Spine>
      </div>
    );
  }

  return (
    <Spine>
      {steps.map((s, i) => {
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
          <SpineNode
            key={s.ordinal}
            index={i}
            revealed={i < shown}
            tone={bad ? "bad" : acted ? "warn" : skippedStep ? "skipped" : s.kind === "MODEL" ? "accent" : "ok"}
            head={
              <span className={skippedStep ? "text-slate-500" : undefined}>
                <span className="micro mr-2">{s.kind}</span>
                {s.label}
              </span>
            }
            aside={
              (bad || acted || skippedStep) ? s.status.toLowerCase() : undefined
            }
            trailing={
              <span className="flex shrink-0 items-center gap-2.5">
                {s.confidence != null && (
                  <span className="readout text-xs text-slate-400">{s.confidence.toFixed(2)}</span>
                )}
                {s.latencyMs > 0 && (
                  <>
                    <Bar
                      fraction={s.latencyMs / slowest}
                      tone={bad ? "bad" : acted ? "warn" : "mute"}
                      width={44}
                    />
                    <span className="readout w-12 text-right text-[11px] text-slate-500">
                      {s.latencyMs}ms
                    </span>
                  </>
                )}
              </span>
            }
            onClick={() => onDetail(detail === s.ordinal ? null : s.ordinal)}
            open={detail === s.ordinal}
          >
            <div className="pb-2">
              <p className="max-w-2xl text-xs leading-relaxed text-slate-500">
                {KIND_NOTE[s.kind] ?? ""}
              </p>
              <StepDetail step={s} />
            </div>
          </SpineNode>
        );
      })}
    </Spine>
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
        <p className="text-[13px] text-slate-300">{step.detail?.reason ?? "Condition not met."}</p>
        <p className="mt-1 max-w-2xl text-xs leading-relaxed text-slate-600">
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
      <div className="mt-3 space-y-3">
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
                <Bar
                  fraction={Number(f.confidence)}
                  tone={f.confidence >= 0.7 ? "ok" : f.confidence >= 0.4 ? "warn" : "bad"}
                />
                <span className="readout w-10 shrink-0 text-right text-xs text-slate-400">
                  {Number(f.confidence).toFixed(2)}
                </span>
              </div>
            ))}
          </div>
        )}
        {structured.belowThreshold > 0 && (
          <p className="max-w-2xl text-xs leading-relaxed" style={{ color: "var(--state-warning-ink)" }}>
            {structured.belowThreshold} further{" "}
            {structured.belowThreshold === 1 ? "signal was" : "signals were"} below the reporting
            threshold and withheld — passing one along invites the model to reason about it anyway.
          </p>
        )}
        {(structured.failedSpecialists ?? []).length > 0 && (
          <p className="text-xs" style={{ color: "var(--state-critical-ink)" }}>
            Did not respond: {(structured.failedSpecialists as string[]).join(", ")}
          </p>
        )}

        <div>
          <Micro>Exactly what the model was told</Micro>
          <div className="mt-1.5">
            <Code>{step.detail?.prompt ?? "—"}</Code>
          </div>
          <p className="mt-1.5 max-w-2xl text-xs leading-relaxed text-slate-600">
            Kept verbatim rather than rebuilt from the findings. When an answer is wrong this is the
            first thing worth reading, and a reconstruction is not the same artefact.
          </p>
        </div>
      </div>
    );
  }

  return (
    <div className="mt-2">
      <Code>{step.detail == null ? "—" : JSON.stringify(step.detail, null, 2)}</Code>
    </div>
  );
}
