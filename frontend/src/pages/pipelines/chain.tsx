import { useEffect, useMemo, useState } from "react";
import { portal } from "../../api";
import { InfoTip, Micro, Note } from "../../system/primitives";
import { Bar, Code, Primary, Spine, SpineNode, Stat, Stats, Pill } from "../../system/hub";
import { useToast } from "../../components/ui";
import { BAND_COPY, Compliance, Pipeline, Policy, Run, SAMPLE_IMAGE, Step } from "./types";
import { Verdict, VerificationVerdict } from "./verification";

/* -------------------------------------------------------------------------- *
 * The chain, running
 * -------------------------------------------------------------------------- */

export function TryIt({ pipeline, onRan }: { pipeline: Pipeline; onRan: () => void }) {
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

  // Paced by what each step actually took: a slow specialist visibly takes
  // longer to land than a fast one, so the replay carries the run's real
  // shape instead of dealing out rows at one fixed beat.
  useEffect(() => {
    if (!run || shown >= run.trace.length) return;
    const slowest = Math.max(1, ...run.trace.map((s) => s.latencyMs));
    const step = run.trace[shown];
    const pace = 150 + 450 * ((step?.latencyMs ?? 0) / slowest);
    const t = setTimeout(() => setShown((n) => n + 1), pace);
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
      <Note>
        Runs the same code path your application would, including a disabled pipeline's refusal — a
        test route that skipped a step would be worse than no test route.
      </Note>

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
                className="hidden field"
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
              className="mt-1 w-full font-mono text-[11px] focus:border-[color:var(--accent-edge)] field"
            />
            {file && <Note className="mt-1">Ignored while a file is attached.</Note>}
          </label>
          <label className="block">
            <Micro>What your user asked</Micro>
            <input
              value={prompt}
              onChange={(e) => setPrompt(e.target.value)}
              className="mt-1 w-full focus:border-[color:var(--accent-edge)] field"
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
                  <p className="mt-1 flex items-center gap-1 text-xs text-slate-500">
                    No specialist answered — the model was told so
                    <InfoTip text={'"We looked and found nothing" and "we never looked" are different facts; only the first is evidence, so the model is not left to fill the silence.'} />
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

              <p className="flex items-center gap-1.5 text-xs text-slate-500">
                <span className="micro">trace</span>
                <span className="readout">{run.traceId}</span>
                <InfoTip text="Returned to your application with the answer, so it can show its user the same chain." />
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
      <Note className="mt-1">{copy.note}</Note>

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
            <div className="mt-1.5 flex flex-wrap items-center gap-1.5">
              <span className="micro">found in the answer</span>
              {compliance.markers.map((m) => (
                <Pill key={m} tone="info">{m}</Pill>
              ))}
            </div>
          )}
          {compliance.flatAssertions.length > 0 && (
            <p className="mt-1 text-xs" style={{ color: "var(--state-warning-ink)" }}>
              Flat assertions despite the instruction:{" "}
              {compliance.flatAssertions.map((m) => `"${m}"`).join(", ")}
            </p>
          )}
          <Note className="mt-1.5">
            Checked on the answer that came back, not assumed from the instruction — the policy can
            only ask, and a page that reported success because it asked would be reporting its own
            intent. The check is lexical: it reliably catches an instruction that produced flat,
            unqualified prose, and cannot tell a real hedge from a decorative one.
          </Note>
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
export function Chain({
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

  // In flight: the stages the request will pass through, with the request
  // itself travelling the rail and lighting each stage as it reaches it. Not a
  // spinner beside a list — the motion is the request moving through the
  // actual structure. It does not claim to know which step is running; the
  // run is one synchronous call, and the packet says only that it is inside.
  if (pending) {
    return (
      <div className="mt-5" aria-label="running" aria-busy="true">
        <Spine flowing>
          {skeleton.map((k, i) => (
            <SpineNode
              key={k}
              index={i}
              tone="idle"
              sensing
              head={<span className="text-slate-500">{k.toLowerCase()}</span>}
            />
          ))}
        </Spine>
      </div>
    );
  }

  return (
    <Spine progress={steps.length ? Math.min(shown, steps.length) / steps.length : 0}>
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
              {KIND_NOTE[s.kind] && <Note>{KIND_NOTE[s.kind]}</Note>}
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
        <Note className="mt-1">
          Recorded rather than left out. A step that did not run and a step that ran and found
          nothing look identical in the answer, and they need different fixes.
        </Note>
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
          <Note className="mt-1.5">
            Kept verbatim rather than rebuilt from the findings. When an answer is wrong this is the
            first thing worth reading, and a reconstruction is not the same artefact.
          </Note>
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
