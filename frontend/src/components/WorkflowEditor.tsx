import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Link } from "react-router-dom";
import { Pill, Segmented, toneInk, toneWash, type Tone } from "../system/hub";
import { Micro } from "../system/primitives";
import { Select } from "../system/controls";

/**
 * The workflow editor.
 *
 * <p>A definition is a graph, so it is edited as one: each step is a card with
 * the fields the engine actually reads, the graph beside it redraws as the
 * steps change, and every rule the server will enforce at publish time is
 * checked here first, next to the field it concerns. The JSON is still one
 * click away, for pasting and for anyone who prefers it; both views edit the
 * same definition.
 */

export type Call = { method?: string; url?: string; headers?: Record<string, string>; body?: unknown };
export type Step = {
  id: string;
  type?: "HTTP" | "WAIT";
  call?: Call;
  waitSeconds?: number;
  condition?: string;
  dependsOn?: string[];
  retries?: number;
  timeoutSeconds?: number;
  compensate?: Call;
};
export type Spec = { description?: string; steps: Step[]; onComplete?: Call };

const METHODS = ["GET", "POST", "PUT", "PATCH", "DELETE"];
const ID_RE = /^[A-Za-z0-9_-]{1,64}$/;

export const TEMPLATES: { key: string; label: string; spec: Spec }[] = [
  {
    key: "parallel",
    label: "Parallel, then confirm",
    spec: {
      description: "Reserve stock and charge in parallel, then confirm",
      steps: [
        { id: "reserve", call: { method: "POST", url: "https://api.example.com/reserve", body: { sku: "${input.sku}", qty: "${input.qty}" } }, retries: 3, timeoutSeconds: 30 },
        { id: "charge", call: { method: "POST", url: "https://api.example.com/charge", body: { amount: "${input.amount}" } } },
        { id: "cool-off", type: "WAIT", waitSeconds: 5, dependsOn: ["charge"] },
        { id: "confirm", dependsOn: ["reserve", "cool-off"], condition: "${steps.charge.paid} == true", call: { method: "POST", url: "https://api.example.com/confirm", body: { hold: "${steps.reserve.holdId}" } } },
      ],
      onComplete: { url: "https://api.example.com/webhooks/done" },
    },
  },
  {
    key: "saga",
    label: "Booking with rollback",
    spec: {
      description: "Book a trip; undo what succeeded if a later step fails",
      steps: [
        { id: "flight", call: { method: "POST", url: "https://api.example.com/flights", body: { trip: "${input.trip}" } }, compensate: { method: "DELETE", url: "https://api.example.com/flights/${steps.flight.id}" } },
        { id: "hotel", dependsOn: ["flight"], call: { method: "POST", url: "https://api.example.com/hotels", body: { trip: "${input.trip}" } }, compensate: { method: "DELETE", url: "https://api.example.com/hotels/${steps.hotel.id}" } },
        { id: "charge", dependsOn: ["hotel"], call: { method: "POST", url: "https://api.example.com/charge", body: { amount: "${input.amount}" } } },
      ],
    },
  },
  {
    key: "approval",
    label: "Wait, then notify",
    spec: {
      description: "Score a request, wait an hour if it is risky, then notify",
      steps: [
        { id: "score", call: { method: "POST", url: "https://api.example.com/risk", body: { user: "${input.user}" } } },
        { id: "hold", type: "WAIT", waitSeconds: 3600, dependsOn: ["score"], condition: "${steps.score.risk} > 0.8" },
        { id: "notify", dependsOn: ["hold"], call: { method: "POST", url: "https://api.example.com/notify" } },
      ],
      onComplete: { method: "POST", url: "https://api.example.com/webhooks/done" },
    },
  },
  { key: "blank", label: "Blank", spec: { steps: [{ id: "step-1", call: { method: "POST", url: "https://" } }] } },
];

/* ------------------------------------------------------------------ *
 * Validation — the server's rules (WorkflowSpec.validate), run as you type
 * ------------------------------------------------------------------ */

export type Problems = { global: string[]; byStep: string[][] };

function checkUrl(url: string | undefined): string | null {
  if (!url || !url.trim()) return "needs a URL";
  const u = url.replace(/\$\{[^}]*\}/g, "x");
  return /^https?:\/\/[^/\s]+/i.test(u) ? null : "URL must be an absolute http(s) address";
}

export function validate(spec: Spec | null): Problems {
  const out: Problems = { global: [], byStep: [] };
  if (!spec) return out;
  const steps = Array.isArray(spec.steps) ? spec.steps : [];
  if (steps.length === 0) out.global.push("A workflow needs at least one step.");
  if (steps.length > 100) out.global.push("A workflow is limited to 100 steps.");
  const ids = steps.map((s) => s?.id);
  steps.forEach((s, i) => {
    const p: string[] = [];
    if (!s?.id || !ID_RE.test(s.id)) p.push("id must be 1–64 letters, digits, _ or -");
    else if (ids.indexOf(s.id) !== i) p.push(`duplicate id “${s.id}”`);
    if (s?.type === "WAIT") {
      if (!s.waitSeconds || s.waitSeconds < 1 || s.waitSeconds > 86400) p.push("wait must be 1 s – 24 h");
    } else {
      const u = checkUrl(s?.call?.url);
      if (u) p.push(u);
      if (s?.call?.method && !METHODS.includes(String(s.call.method).toUpperCase())) p.push("unknown HTTP method");
    }
    if (s?.compensate?.url) {
      const u = checkUrl(s.compensate.url);
      if (u) p.push("rollback: " + u);
    }
    if (s?.retries != null && (s.retries < 0 || s.retries > 25)) p.push("retries must be 0–25");
    if (s?.timeoutSeconds != null && (s.timeoutSeconds < 1 || s.timeoutSeconds > 300)) p.push("timeout must be 1–300 s");
    for (const d of s?.dependsOn ?? []) {
      if (d === s.id) p.push("depends on itself");
      else if (!ids.includes(d)) p.push(`depends on unknown step “${d}”`);
    }
    out.byStep.push(p);
  });
  if (spec.onComplete) {
    const u = checkUrl(spec.onComplete.url);
    if (u) out.global.push("Callback " + u + ".");
  }
  if (out.byStep.every((p) => p.length === 0) && layersOf(steps).cycle) out.global.push("Steps form a dependency cycle.");
  return out;
}

/** The engine's layering: a step runs once everything it depends on has. */
export function layersOf(steps: Step[]): { layers: Step[][]; cycle: boolean } {
  const done = new Set<string>();
  const layers: Step[][] = [];
  const valid = steps.filter((s) => s?.id);
  let guard = 0;
  while (done.size < valid.length && guard++ < 200) {
    const layer = valid.filter((s) => !done.has(s.id) && (s.dependsOn ?? []).every((d) => done.has(d)));
    if (layer.length === 0) return { layers, cycle: true };
    layer.forEach((s) => done.add(s.id));
    layers.push(layer);
  }
  return { layers, cycle: false };
}

const count = (p: Problems) => p.global.length + p.byStep.reduce((n, s) => n + s.length, 0);

/* ------------------------------------------------------------------ *
 * The editor
 * ------------------------------------------------------------------ */

export default function WorkflowEditor({
  name,
  setName,
  source,
  setSource,
  onPublish,
  onPublishAndRun,
  busy,
  runInput,
  setRunInput,
}: {
  name: string;
  setName: (v: string) => void;
  source: string;
  setSource: (v: string) => void;
  onPublish: () => void;
  onPublishAndRun: () => void;
  busy: boolean;
  runInput: string;
  setRunInput: (v: string) => void;
}) {
  const [view, setView] = useState<"visual" | "json">("visual");
  const [selected, setSelected] = useState<number | null>(0);

  const parsed = useMemo(() => {
    try {
      const o = JSON.parse(source);
      if (!o || typeof o !== "object" || Array.isArray(o)) return { spec: null, err: "The definition must be a JSON object." };
      return { spec: { ...o, steps: Array.isArray(o.steps) ? o.steps : [] } as Spec, err: null as string | null };
    } catch (e: any) {
      return { spec: null, err: String(e.message) };
    }
  }, [source]);

  const problems = useMemo(() => validate(parsed.spec), [parsed.spec]);
  const nProblems = parsed.err ? 1 : count(problems);
  const inputOk = useMemo(() => {
    try {
      if (runInput.trim()) JSON.parse(runInput);
      return true;
    } catch {
      return false;
    }
  }, [runInput]);

  const commit = (next: Spec) => setSource(JSON.stringify(next, null, 2));
  const spec = parsed.spec;
  const setStep = (i: number, patch: Partial<Step>) => {
    if (!spec) return;
    const steps = spec.steps.map((s, j) => (j === i ? clean({ ...s, ...patch }) : s));
    commit({ ...spec, steps });
  };
  const renameStep = (i: number, id: string) => {
    if (!spec) return;
    const old = spec.steps[i].id;
    // Renaming carries the dependencies with it, so the graph does not break.
    const steps = spec.steps.map((s, j) =>
      j === i ? { ...s, id } : { ...s, dependsOn: s.dependsOn?.map((d) => (d === old ? id : d)) },
    );
    commit({ ...spec, steps });
  };
  const addStep = (type: "HTTP" | "WAIT") => {
    if (!spec) return;
    let n = spec.steps.length + 1;
    while (spec.steps.some((s) => s.id === `step-${n}`)) n++;
    const last = spec.steps[spec.steps.length - 1];
    const step: Step =
      type === "WAIT"
        ? { id: `wait-${n}`, type: "WAIT", waitSeconds: 60, dependsOn: last ? [last.id] : [] }
        : { id: `step-${n}`, call: { method: "POST", url: "https://" }, dependsOn: last ? [last.id] : [] };
    commit({ ...spec, steps: [...spec.steps, clean(step)] });
    setSelected(spec.steps.length);
  };
  const removeStep = (i: number) => {
    if (!spec) return;
    const gone = spec.steps[i].id;
    const steps = spec.steps
      .filter((_, j) => j !== i)
      .map((s) => clean({ ...s, dependsOn: s.dependsOn?.filter((d) => d !== gone) }));
    commit({ ...spec, steps });
    setSelected(null);
  };
  const moveStep = (i: number, by: number) => {
    if (!spec) return;
    const j = i + by;
    if (j < 0 || j >= spec.steps.length) return;
    const steps = [...spec.steps];
    [steps[i], steps[j]] = [steps[j], steps[i]];
    commit({ ...spec, steps });
    setSelected(j);
  };

  return (
    <div className="space-y-4">
      {/* ---- toolbar ---------------------------------------------------- */}
      <div className="plane flex flex-wrap items-end gap-3 p-4">
        <label className="min-w-[14rem] flex-1">
          <Micro>Name</Micro>
          <input
            value={name}
            onChange={(e) => setName(e.target.value)}
            spellCheck={false}
            aria-invalid={!ID_RE.test(name)}
            className="mt-1 w-full font-mono field"
          />
        </label>
        <label className="min-w-[14rem] flex-[2]">
          <Micro>Description</Micro>
          <input
            value={spec?.description ?? ""}
            disabled={!spec}
            placeholder="What this workflow does"
            onChange={(e) => spec && commit(clean({ ...spec, description: e.target.value || undefined }))}
            className="mt-1 w-full field"
          />
        </label>
        <label>
          <Micro>Start from</Micro>
          <Select
            aria-label="Start from a template"
            className="mt-1"
            value=""
            onChange={(e) => {
              const t = TEMPLATES.find((x) => x.key === e.target.value);
              if (t && (count(problems) === 0 || confirm("Replace the current definition with this template?"))) {
                commit(t.spec);
                setSelected(0);
              }
            }}
          >
            <option value="">Template…</option>
            {TEMPLATES.map((t) => (
              <option key={t.key} value={t.key}>
                {t.label}
              </option>
            ))}
          </Select>
        </label>
        <div className="ml-auto flex items-center gap-2">
          <span
            className="inline-flex h-[var(--h-md)] items-center gap-1.5 rounded-full px-3 text-[12px] font-medium"
            style={{ background: toneWash(nProblems ? "bad" : "ok"), color: toneInk(nProblems ? "bad" : "ok") }}
            aria-live="polite"
          >
            <span className="h-1.5 w-1.5 rounded-full" style={{ background: "currentColor" }} aria-hidden />
            {nProblems ? `${nProblems} to fix` : "Ready to publish"}
          </span>
          <button
            onClick={onPublish}
            disabled={busy || nProblems > 0 || !ID_RE.test(name)}
            title={nProblems ? "Fix the highlighted problems first" : undefined}
            className="h-[var(--h-lg)] rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-4 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {busy ? "Publishing…" : "Publish version"}
          </button>
        </div>
      </div>

      {(parsed.err || problems.global.length > 0) && (
        <div role="alert" className="rise-in rounded-xl border px-4 py-2.5 text-[12.5px]"
             style={{ borderColor: "var(--state-critical-ink)", color: "var(--state-critical-ink)", background: toneWash("bad") }}>
          {parsed.err ? `The JSON does not parse: ${parsed.err}` : problems.global.join(" ")}
        </div>
      )}

      <section className="plane p-4">
        <div className="mb-2 flex flex-wrap items-baseline justify-between gap-2">
          <h2 className="text-[13px] font-semibold text-slate-100">Execution graph</h2>
          <span className="text-[11px] text-slate-500">columns run in order · a column's steps run in parallel</span>
        </div>
        {spec ? (
          <WorkflowGraph
            spec={spec}
            problems={problems}
            selected={selected}
            onSelect={(i) => {
              setSelected(i);
              setView("visual");
            }}
          />
        ) : (
          <p className="py-10 text-center text-sm text-slate-500">Fix the JSON to see the graph.</p>
        )}
        <GraphKey />
      </section>

      <div className="grid gap-4 lg:grid-cols-[minmax(0,1.35fr)_minmax(0,1fr)]">
        {/* ---- steps / JSON ---------------------------------------------- */}
        <section className="plane min-w-0 p-4">
          <div className="mb-3 flex items-center justify-between gap-3">
            <Segmented
              value={view}
              onChange={(v) => {
                if (v === "visual" && parsed.err) return;
                setView(v);
              }}
              options={[
                { value: "visual", label: "Steps" },
                { value: "json", label: "JSON" },
              ]}
            />
            {view === "visual" && spec && (
              <div className="flex gap-2">
                <button onClick={() => addStep("HTTP")} className="rounded-full border border-edge px-3 py-1 text-[12px] font-medium text-slate-200 hover:border-slate-500/60">
                  + HTTP step
                </button>
                <button onClick={() => addStep("WAIT")} className="rounded-full border border-edge px-3 py-1 text-[12px] font-medium text-slate-200 hover:border-slate-500/60">
                  + Wait
                </button>
              </div>
            )}
          </div>

          {view === "json" || !spec ? (
            <CodeArea value={source} onChange={setSource} invalid={!!parsed.err} />
          ) : (
            <ol className="space-y-2.5">
              {spec.steps.map((s, i) => (
                <StepCard
                  key={i}
                  index={i}
                  step={s}
                  all={spec.steps}
                  problems={problems.byStep[i] ?? []}
                  open={selected === i}
                  onToggle={() => setSelected(selected === i ? null : i)}
                  onChange={(patch) => setStep(i, patch)}
                  onRename={(id) => renameStep(i, id)}
                  onRemove={() => removeStep(i)}
                  onMove={(by) => moveStep(i, by)}
                />
              ))}
              <CallbackCard
                call={spec.onComplete}
                onChange={(c) => commit(clean({ ...spec, onComplete: c }))}
              />
            </ol>
          )}
        </section>

        {/* ---- graph + run --------------------------------------------- */}
        <div className="min-w-0 space-y-4">

          <section className="plane p-4">
            <div className="flex items-baseline justify-between">
              <h2 className="text-[13px] font-semibold text-slate-100">Try it</h2>
              <span className="text-[11px]" style={{ color: toneInk(inputOk ? "ok" : "bad") }}>
                {inputOk ? "readable as ${input.field}" : "input is not valid JSON"}
              </span>
            </div>
            <textarea
              aria-label="Run input (JSON)"
              value={runInput}
              onChange={(e) => setRunInput(e.target.value)}
              spellCheck={false}
              rows={3}
              className="mt-2 w-full font-mono text-[12px] field"
            />
            <div className="mt-2 flex flex-wrap items-center justify-between gap-2">
              <InputRefs spec={spec} />
              <button
                onClick={onPublishAndRun}
                disabled={busy || nProblems > 0 || !inputOk || !ID_RE.test(name)}
                className="rounded-[var(--r-md)] border border-edge px-4 py-1.5 text-sm font-medium text-slate-200 hover:border-slate-500/60 disabled:opacity-50"
              >
                Publish &amp; run
              </button>
            </div>
          </section>

          <Reference />
        </div>
      </div>
    </div>
  );
}

/** Drops empty optional fields, so the JSON stays what the author wrote. */
function clean<T extends object>(o: T): T {
  const out: any = { ...o };
  for (const [k, v] of Object.entries(out)) {
    if (v === undefined || v === "" || (Array.isArray(v) && v.length === 0 && k === "dependsOn")) delete out[k];
  }
  return out;
}

/* ------------------------------------------------------------------ *
 * One step
 * ------------------------------------------------------------------ */

function typeTone(s: Step): Tone {
  return s.type === "WAIT" ? "warn" : s.condition ? "violet" : "info";
}

function StepCard({
  index,
  step,
  all,
  problems,
  open,
  onToggle,
  onChange,
  onRename,
  onRemove,
  onMove,
}: {
  index: number;
  step: Step;
  all: Step[];
  problems: string[];
  open: boolean;
  onToggle: () => void;
  onChange: (patch: Partial<Step>) => void;
  onRename: (id: string) => void;
  onRemove: () => void;
  onMove: (by: number) => void;
}) {
  const tone = typeTone(step);
  const wait = step.type === "WAIT";
  const summary = wait
    ? `wait ${human(step.waitSeconds ?? 0)}`
    : `${(step.call?.method ?? "POST").toUpperCase()} ${shortUrl(step.call?.url)}`;
  const others = all.filter((s) => s.id && s.id !== step.id);
  // A step opened from the graph or just added may be far down the list.
  const card = useRef<HTMLLIElement>(null);
  useEffect(() => {
    if (open) card.current?.scrollIntoView({ block: "nearest", behavior: "smooth" });
  }, [open]);
  return (
    <li
      ref={card}
      className="rise-in overflow-hidden rounded-2xl border bg-slate-500/[0.04]"
      style={{ borderColor: problems.length ? "var(--state-critical-ink)" : open ? toneInk(tone) : "rgb(var(--card-edge))", animationDelay: `${index * 40}ms` }}
    >
      <button onClick={onToggle} aria-expanded={open} className="flex w-full items-center gap-3 px-3.5 py-2.5 text-left">
        <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full text-[11px] font-bold" style={{ background: toneWash(tone), color: toneInk(tone) }}>
          {index + 1}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate font-mono text-[13px] font-medium text-slate-100">{step.id || "(no id)"}</span>
          <span className="block truncate text-[11.5px] text-slate-500">{summary}</span>
        </span>
        {step.condition && <Pill tone="violet">if</Pill>}
        {step.compensate?.url && <Pill tone="warn">rollback</Pill>}
        {problems.length > 0 && <Pill tone="bad">{problems.length}</Pill>}
        <svg className={`spin-to shrink-0 text-slate-500 ${open ? "rotate-180" : ""}`} width="12" height="12" viewBox="0 0 12 12" aria-hidden>
          <path d="M3 4.5 6 7.5 9 4.5" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
        </svg>
      </button>

      {open && (
        <div className="space-y-3 border-t border-edge/60 px-3.5 pb-3.5 pt-3">
          {problems.length > 0 && (
            <ul className="space-y-0.5 text-[12px]" style={{ color: "var(--state-critical-ink)" }}>
              {problems.map((p) => (
                <li key={p}>• {p}</li>
              ))}
            </ul>
          )}
          <div className="grid gap-3 sm:grid-cols-[minmax(0,1fr)_auto]">
            <Field label="Step id">
              <input value={step.id} onChange={(e) => onRename(e.target.value)} spellCheck={false} className="w-full font-mono field" />
            </Field>
            <Field label="Kind" group>
              <div className="flex h-[var(--h-lg)] items-center gap-1 rounded-[var(--r-md)] p-0.5" style={{ background: "var(--wash-mute)" }} role="radiogroup" aria-label="Step kind">
                {(["HTTP", "WAIT"] as const).map((k) => {
                  const on = (step.type ?? "HTTP") === k;
                  return (
                    <button
                      key={k}
                      role="radio"
                      aria-checked={on}
                      onClick={() =>
                        onChange(
                          k === "WAIT"
                            ? { type: "WAIT", waitSeconds: step.waitSeconds ?? 60, call: undefined }
                            : { type: undefined, waitSeconds: undefined, call: step.call ?? { method: "POST", url: "https://" } },
                        )
                      }
                      className="h-full rounded-[calc(var(--r-md)-2px)] px-3 text-[12px] font-medium"
                      style={on ? { background: "rgb(var(--card))", color: "rgb(var(--topo-text))" } : { color: "var(--text-2)" }}
                    >
                      {k === "HTTP" ? "HTTP call" : "Wait"}
                    </button>
                  );
                })}
              </div>
            </Field>
          </div>

          {wait ? (
            <Field label={`Wait · ${human(step.waitSeconds ?? 0)}`} group>
              <div className="flex flex-wrap items-center gap-2">
                <input
                  type="number"
                  min={1}
                  max={86400}
                  value={step.waitSeconds ?? ""}
                  onChange={(e) => onChange({ waitSeconds: e.target.value === "" ? undefined : Number(e.target.value) })}
                  className="w-32 field"
                  aria-label="Wait seconds"
                />
                <span className="text-[12px] text-slate-500">seconds</span>
                {[5, 60, 900, 3600, 86400].map((v) => (
                  <button key={v} onClick={() => onChange({ waitSeconds: v })} className="rounded-full border border-edge px-2.5 py-0.5 text-[11.5px] text-slate-300 hover:border-slate-500/60">
                    {human(v)}
                  </button>
                ))}
              </div>
            </Field>
          ) : (
            <CallFields call={step.call ?? {}} onChange={(call) => onChange({ call })} />
          )}

          <Field label="Runs after" group>
            {others.length === 0 ? (
              <span className="text-[12px] text-slate-500">No other steps yet — this one starts the run.</span>
            ) : (
              <div className="flex flex-wrap gap-1.5">
                {others.map((o) => {
                  const on = step.dependsOn?.includes(o.id) ?? false;
                  return (
                    <button
                      key={o.id}
                      aria-pressed={on}
                      onClick={() =>
                        onChange({ dependsOn: on ? step.dependsOn!.filter((d) => d !== o.id) : [...(step.dependsOn ?? []), o.id] })
                      }
                      className="rounded-full border px-2.5 py-0.5 font-mono text-[11.5px]"
                      style={
                        on
                          ? { borderColor: "transparent", background: toneWash("info"), color: toneInk("info") }
                          : { borderColor: "rgb(var(--card-edge))", color: "var(--text-2)" }
                      }
                    >
                      {on ? "✓ " : ""}
                      {o.id}
                    </button>
                  );
                })}
              </div>
            )}
          </Field>

          <Field label="Only if (optional)">
            <input
              value={step.condition ?? ""}
              onChange={(e) => onChange({ condition: e.target.value || undefined })}
              placeholder="${steps.charge.paid} == true"
              spellCheck={false}
              className="w-full font-mono field"
            />
          </Field>

          {!wait && (
            <div className="grid grid-cols-2 gap-3">
              <Field label="Retries">
                <input type="number" min={0} max={25} value={step.retries ?? 3} onChange={(e) => onChange({ retries: Number(e.target.value) })} className="w-full field" />
              </Field>
              <Field label="Timeout (s)">
                <input type="number" min={1} max={300} value={step.timeoutSeconds ?? 30} onChange={(e) => onChange({ timeoutSeconds: Number(e.target.value) })} className="w-full field" />
              </Field>
            </div>
          )}

          {!wait && (
            <details open={!!step.compensate?.url} className="rounded-xl border border-edge/60 px-3 py-2">
              <summary className="cursor-pointer select-none text-[12.5px] font-medium text-slate-300">
                Rollback call <span className="font-normal text-slate-500">— undoes this step if a later one fails</span>
              </summary>
              <div className="mt-2 space-y-2">
                <CallFields compact call={step.compensate ?? { method: "DELETE" }} onChange={(c) => onChange({ compensate: c.url ? c : undefined })} />
              </div>
            </details>
          )}

          <div className="flex flex-wrap items-center justify-between gap-2 pt-1">
            <div className="flex gap-1.5">
              <button onClick={() => onMove(-1)} className="rounded-full border border-edge px-2.5 py-0.5 text-[12px] text-slate-300 hover:border-slate-500/60" aria-label="Move step up">
                ↑
              </button>
              <button onClick={() => onMove(1)} className="rounded-full border border-edge px-2.5 py-0.5 text-[12px] text-slate-300 hover:border-slate-500/60" aria-label="Move step down">
                ↓
              </button>
            </div>
            <button onClick={onRemove} className="rounded-full border px-3 py-0.5 text-[12px]" style={{ borderColor: "var(--state-critical-ink)", color: "var(--state-critical-ink)" }}>
              Remove step
            </button>
          </div>
        </div>
      )}
    </li>
  );
}

function CallFields({ call, onChange, compact = false }: { call: Call; onChange: (c: Call) => void; compact?: boolean }) {
  const [bodyText, setBodyText] = useState(() => (call.body === undefined ? "" : JSON.stringify(call.body, null, 2)));
  const [bodyErr, setBodyErr] = useState<string | null>(null);
  const headers = Object.entries(call.headers ?? {});
  // Keep the text in step when the definition changes underneath (JSON view, template).
  const lastBody = useRef(call.body);
  useEffect(() => {
    if (call.body !== lastBody.current) {
      lastBody.current = call.body;
      setBodyText(call.body === undefined ? "" : JSON.stringify(call.body, null, 2));
      setBodyErr(null);
    }
  }, [call.body]);
  return (
    <div className="space-y-3">
      <div className="flex gap-2">
        <Select aria-label="HTTP method" className="!w-28 shrink-0" value={(call.method ?? "POST").toUpperCase()} onChange={(e) => onChange({ ...call, method: e.target.value })}>
          {METHODS.map((m) => (
            <option key={m}>{m}</option>
          ))}
        </Select>
        <input
          value={call.url ?? ""}
          onChange={(e) => onChange({ ...call, url: e.target.value })}
          placeholder="https://api.example.com/…"
          spellCheck={false}
          aria-label="URL"
          className="min-w-0 flex-1 font-mono field"
        />
      </div>
      {!compact && (
        <>
          <Field label={bodyErr ? `Body — ${bodyErr}` : "Body (JSON, optional)"} tone={bodyErr ? "bad" : undefined}>
            <textarea
              value={bodyText}
              rows={4}
              spellCheck={false}
              onChange={(e) => {
                const t = e.target.value;
                setBodyText(t);
                if (!t.trim()) {
                  setBodyErr(null);
                  lastBody.current = undefined;
                  onChange({ ...call, body: undefined });
                  return;
                }
                try {
                  const v = JSON.parse(t);
                  setBodyErr(null);
                  lastBody.current = v;
                  onChange({ ...call, body: v });
                } catch {
                  setBodyErr("not valid JSON yet");
                }
              }}
              className="w-full font-mono text-[12px] field"
              placeholder='{ "amount": "${input.amount}" }'
            />
          </Field>
          <Field label="Headers" group>
            <div className="space-y-1.5">
              {headers.map(([k, v], i) => (
                <div key={i} className="flex gap-2">
                  <input
                    value={k}
                    onChange={(e) => {
                      const next = Object.fromEntries(headers.map(([hk, hv], j) => (j === i ? [e.target.value, hv] : [hk, hv])));
                      onChange({ ...call, headers: next });
                    }}
                    placeholder="Header"
                    className="w-40 font-mono field"
                    aria-label="Header name"
                  />
                  <input
                    value={v}
                    onChange={(e) => onChange({ ...call, headers: { ...call.headers, [k]: e.target.value } })}
                    placeholder="value"
                    className="min-w-0 flex-1 font-mono field"
                    aria-label="Header value"
                  />
                  <button
                    onClick={() => {
                      const next = { ...call.headers };
                      delete next[k];
                      onChange({ ...call, headers: Object.keys(next).length ? next : undefined });
                    }}
                    className="px-2 text-slate-500 hover:text-slate-200"
                    aria-label={`Remove header ${k}`}
                  >
                    ×
                  </button>
                </div>
              ))}
              <button
                onClick={() => onChange({ ...call, headers: { ...call.headers, [`X-Header-${headers.length + 1}`]: "" } })}
                className="text-[12px] font-medium text-slate-400 hover:text-slate-200"
              >
                + Add header
              </button>
            </div>
          </Field>
        </>
      )}
    </div>
  );
}

function CallbackCard({ call, onChange }: { call?: Call; onChange: (c: Call | undefined) => void }) {
  const on = !!call;
  return (
    <li className="rounded-2xl border border-dashed border-edge px-3.5 py-2.5">
      <div className="flex items-center gap-3">
        <span className="grid h-7 w-7 shrink-0 place-items-center rounded-full text-[13px]" style={{ background: toneWash("ok"), color: toneInk("ok") }} aria-hidden>
          ⇢
        </span>
        <span className="min-w-0 flex-1">
          <span className="block text-[13px] font-medium text-slate-100">When finished, call back</span>
          <span className="block text-[11.5px] text-slate-500">so your app is told instead of polling</span>
        </span>
        <button
          role="switch"
          aria-checked={on}
          aria-label="Completion callback"
          onClick={() => onChange(on ? undefined : { method: "POST", url: "https://" })}
          className="relative h-6 w-11 shrink-0 rounded-full transition-colors"
          style={{ background: on ? "var(--accent-strong)" : "rgb(var(--card-edge))" }}
        >
          <span className="absolute top-0.5 h-5 w-5 rounded-full bg-white shadow transition-[left]" style={{ left: on ? 22 : 2 }} />
        </button>
      </div>
      {on && (
        <div className="mt-3">
          <CallFields compact call={call!} onChange={onChange} />
        </div>
      )}
    </li>
  );
}

/* ------------------------------------------------------------------ *
 * The graph
 * ------------------------------------------------------------------ */

const NODE_W = 168;
const NODE_H = 58;
const COL_GAP = 58;
const ROW_GAP = 16;

function WorkflowGraph({
  spec,
  problems,
  selected,
  onSelect,
}: {
  spec: Spec;
  problems: Problems;
  selected: number | null;
  onSelect: (i: number) => void;
}) {
  const { layers, cycle } = useMemo(() => layersOf(spec.steps), [spec.steps]);
  const indexOf = (id: string) => spec.steps.findIndex((s) => s.id === id);
  const cols = layers.length + (spec.onComplete ? 1 : 0);
  const rows = Math.max(1, ...layers.map((l) => l.length));
  const width = 56 + cols * (NODE_W + COL_GAP);
  const height = Math.max(rows * (NODE_H + ROW_GAP) - ROW_GAP, NODE_H) + 20;

  const pos = new Map<string, { x: number; y: number }>();
  layers.forEach((layer, c) => {
    const colH = layer.length * (NODE_H + ROW_GAP) - ROW_GAP;
    const top = (height - colH) / 2;
    layer.forEach((s, r) => pos.set(s.id, { x: 56 + c * (NODE_W + COL_GAP), y: top + r * (NODE_H + ROW_GAP) }));
  });
  const startY = height / 2;
  const cb = spec.onComplete ? { x: 56 + layers.length * (NODE_W + COL_GAP), y: height / 2 - NODE_H / 2 } : null;
  const edge = (x1: number, y1: number, x2: number, y2: number) => {
    const dx = Math.max(24, (x2 - x1) / 2);
    return `M${x1} ${y1} C${x1 + dx} ${y1}, ${x2 - dx} ${y2}, ${x2} ${y2}`;
  };

  // Fit the panel: scale down to 60% before resorting to a scrollbar, so the
  // whole graph is visible at once in the common case.
  const box = useRef<HTMLDivElement>(null);
  const [avail, setAvail] = useState(width);
  useEffect(() => {
    const el = box.current;
    if (!el) return;
    const ro = new ResizeObserver(([e]) => setAvail(e.contentRect.width));
    ro.observe(el);
    return () => ro.disconnect();
  }, []);
  const scale = Math.max(0.6, Math.min(1, avail / width));

  if (spec.steps.length === 0) return <p className="py-10 text-center text-sm text-slate-500">Add a step to start the graph.</p>;

  return (
    <div ref={box} className="overflow-x-auto pb-1">
      <div style={{ width: width * scale, height: height * scale }}>
      <div className="relative origin-top-left" style={{ width, height, transform: scale < 1 ? `scale(${scale})` : undefined }}>
        <svg width={width} height={height} className="absolute inset-0" aria-hidden>
          {layers.flat().map((s) => {
            const to = pos.get(s.id)!;
            const deps = (s.dependsOn ?? []).filter((d) => pos.has(d));
            const from = deps.length
              ? deps.map((d) => ({ x: pos.get(d)!.x + NODE_W, y: pos.get(d)!.y + NODE_H / 2 }))
              : [{ x: 22, y: startY }];
            return from.map((f, k) => (
              <path
                key={s.id + k}
                d={edge(f.x, f.y, to.x, to.y + NODE_H / 2)}
                fill="none"
                stroke={s.condition ? toneInk("violet") : "rgb(148 163 184 / 0.75)"}
                strokeWidth={s.condition ? 1.8 : 1.6}
                strokeDasharray={s.condition ? "5 4" : undefined}
                className="wf-edge"
              />
            ));
          })}
          {cb &&
            layers[layers.length - 1]?.map((s) => {
              const p = pos.get(s.id)!;
              return <path key={"cb" + s.id} d={edge(p.x + NODE_W, p.y + NODE_H / 2, cb.x, cb.y + NODE_H / 2)} fill="none" stroke={toneInk("ok")} strokeOpacity="0.55" strokeWidth="1.6" className="wf-edge" />;
            })}
          <circle cx={16} cy={startY} r={7} fill={toneInk("accent")} />
        </svg>
        <span className="absolute text-[10px] font-medium uppercase tracking-wider text-slate-500" style={{ left: 2, top: startY + 12 }}>
          input
        </span>

        {layers.flat().map((s, n) => {
          const p = pos.get(s.id)!;
          const i = indexOf(s.id);
          const tone = typeTone(s);
          const bad = (problems.byStep[i] ?? []).length > 0;
          const on = selected === i;
          return (
            <button
              key={s.id}
              onClick={() => onSelect(i)}
              className="wf-node absolute flex flex-col justify-center rounded-2xl border px-3 text-left"
              style={{
                left: p.x,
                top: p.y,
                width: NODE_W,
                height: NODE_H,
                background: "rgb(var(--card))",
                borderColor: bad ? "var(--state-critical-ink)" : on ? toneInk(tone) : "rgb(var(--card-edge))",
                boxShadow: on ? `0 0 0 3px ${toneWash(tone)}` : "0 4px 14px -8px rgb(0 0 0 / 0.35)",
                animationDelay: `${Math.min(n * 45, 600)}ms`,
              }}
              aria-label={`Step ${s.id}${bad ? ", has problems" : ""}`}
            >
              <span className="flex items-center gap-1.5">
                <span className="h-2 w-2 shrink-0 rounded-full" style={{ background: toneInk(tone) }} aria-hidden />
                <span className="truncate font-mono text-[12.5px] font-semibold text-slate-100">{s.id}</span>
              </span>
              <span className="mt-0.5 truncate text-[11px] text-slate-500">
                {s.type === "WAIT" ? `wait ${human(s.waitSeconds ?? 0)}` : `${(s.call?.method ?? "POST").toUpperCase()} ${shortUrl(s.call?.url)}`}
              </span>
              <span className="absolute -top-2 right-2 flex gap-1">
                {s.condition && <Badge tone="violet">if</Badge>}
                {s.compensate?.url && <Badge tone="warn">undo</Badge>}
                {bad && <Badge tone="bad">!</Badge>}
              </span>
            </button>
          );
        })}

        {cb && (
          <div
            className="wf-node absolute flex items-center gap-2 rounded-2xl border border-dashed px-3"
            style={{ left: cb.x, top: cb.y, width: NODE_W, height: NODE_H, borderColor: toneInk("ok"), animationDelay: `${Math.min(layers.flat().length * 45, 600)}ms` }}
          >
            <span className="text-[16px]" style={{ color: toneInk("ok") }} aria-hidden>
              ⇢
            </span>
            <span className="min-w-0">
              <span className="block text-[12px] font-semibold text-slate-100">callback</span>
              <span className="block truncate text-[11px] text-slate-500">{shortUrl(spec.onComplete?.url)}</span>
            </span>
          </div>
        )}
      </div>
      </div>
      {cycle && <p className="mt-2 text-[12px]" style={{ color: "var(--state-critical-ink)" }}>Some steps wait on each other in a circle, so they never run.</p>}
    </div>
  );
}

function Badge({ tone, children }: { tone: Tone; children: ReactNode }) {
  return (
    <span className="rounded-full px-1.5 text-[9.5px] font-bold uppercase leading-4" style={{ background: toneWash(tone), color: toneInk(tone), boxShadow: `inset 0 0 0 1px ${toneInk(tone)}` }}>
      {children}
    </span>
  );
}

function GraphKey() {
  const items: [Tone, string][] = [
    ["info", "HTTP call"],
    ["warn", "wait"],
    ["violet", "conditional"],
    ["ok", "callback"],
  ];
  return (
    <div className="mt-3 flex flex-wrap gap-x-4 gap-y-1 text-[11px] text-slate-500">
      {items.map(([t, l]) => (
        <span key={l} className="inline-flex items-center gap-1.5">
          <span className="h-2 w-2 rounded-full" style={{ background: toneInk(t) }} aria-hidden />
          {l}
        </span>
      ))}
    </div>
  );
}

/* ------------------------------------------------------------------ *
 * JSON view, input hints, reference
 * ------------------------------------------------------------------ */

function CodeArea({ value, onChange, invalid }: { value: string; onChange: (v: string) => void; invalid: boolean }) {
  const gutter = useRef<HTMLDivElement>(null);
  const lines = value.split("\n").length;
  return (
    <div
      className="flex overflow-hidden rounded-xl border font-mono text-[12.5px] leading-[1.6]"
      style={{ borderColor: invalid ? "var(--state-critical-ink)" : "rgb(var(--card-edge))", background: "rgb(var(--card))" }}
    >
      <div ref={gutter} aria-hidden className="select-none overflow-hidden py-3 pl-3 pr-2 text-right text-slate-500" style={{ height: 620, background: "var(--wash-mute)" }}>
        {Array.from({ length: lines }, (_, i) => (
          <div key={i}>{i + 1}</div>
        ))}
      </div>
      <textarea
        aria-label="Workflow definition (JSON)"
        value={value}
        onChange={(e) => onChange(e.target.value)}
        onScroll={(e) => gutter.current && (gutter.current.scrollTop = e.currentTarget.scrollTop)}
        spellCheck={false}
        wrap="off"
        className="min-w-0 flex-1 resize-none bg-transparent px-3 py-3 text-slate-200 outline-none"
        style={{ height: 620, tabSize: 2 }}
        onKeyDown={(e) => {
          if (e.key === "Tab") {
            e.preventDefault();
            const t = e.currentTarget;
            const { selectionStart: a, selectionEnd: b } = t;
            onChange(value.slice(0, a) + "  " + value.slice(b));
            requestAnimationFrame(() => t.setSelectionRange(a + 2, a + 2));
          }
        }}
      />
    </div>
  );
}

/** The ${input.…} fields the definition reads, so the run input can be checked against them. */
function InputRefs({ spec }: { spec: Spec | null }) {
  const used = useMemo(() => {
    const s = JSON.stringify(spec ?? {});
    return [...new Set([...s.matchAll(/\$\{input\.([A-Za-z0-9_]+)/g)].map((m) => m[1]))];
  }, [spec]);
  if (!used.length) return <span className="text-[11px] text-slate-500">This definition reads no input.</span>;
  return (
    <span className="flex flex-wrap items-center gap-1 text-[11px] text-slate-500">
      reads
      {used.map((u) => (
        <code key={u} className="rounded bg-slate-500/10 px-1.5 py-0.5 text-slate-300">
          input.{u}
        </code>
      ))}
    </span>
  );
}

function Reference() {
  const rows: [string, string][] = [
    ["${input.field}", "the value the run was started with"],
    ["${steps.id.field}", "an earlier step's response body"],
    ["Only if", "e.g. ${steps.risk.score} > 0.8 — skipped when false"],
    ["Wait", "a durable timer; survives restarts"],
    ["Runs after", "ordering; steps with nothing in common run in parallel"],
    ["Rollback call", "run in reverse order if a later step fails"],
  ];
  return (
    <details className="plane p-4">
      <summary className="cursor-pointer select-none text-[13px] font-semibold text-slate-100">Reference</summary>
      <dl className="mt-3 space-y-1.5 text-[12px]">
        {rows.map(([k, v]) => (
          <div key={k} className="grid grid-cols-[9.5rem_1fr] gap-2">
            <dt className="font-mono text-slate-300">{k}</dt>
            <dd className="text-slate-500">{v}</dd>
          </div>
        ))}
      </dl>
      <Link to="/docs" className="mt-3 inline-block text-[12px] font-medium" style={{ color: "var(--accent-ink)" }}>
        Full reference →
      </Link>
    </details>
  );
}

/** A labelled control. `group` is for several controls under one caption, which a <label> cannot name. */
function Field({ label, children, tone, group }: { label: string; children: ReactNode; tone?: Tone; group?: boolean }) {
  const caption = (
    <span className="micro mb-1 block" style={tone ? { color: toneInk(tone) } : undefined}>
      {label}
    </span>
  );
  return group ? (
    <div className="min-w-0" role="group" aria-label={label}>
      {caption}
      {children}
    </div>
  ) : (
    <label className="block min-w-0">
      {caption}
      {children}
    </label>
  );
}

function human(s: number) {
  if (!s) return "0 s";
  if (s < 60) return `${s} s`;
  if (s < 3600) return `${Math.round(s / 60)} min`;
  if (s < 86400) return `${+(s / 3600).toFixed(1)} h`;
  return `${+(s / 86400).toFixed(1)} d`;
}

function shortUrl(u?: string) {
  if (!u) return "—";
  return u.replace(/^https?:\/\//, "");
}
