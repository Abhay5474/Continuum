import { useCallback, useEffect, useState } from "react";
import { visibleInterval } from "../system/poll";
import { portal } from "../api";
import { Meter, PageHeader, Plane, Readout, Switch, Note, InfoTip } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { Explain, Segmented, Empty, Pill } from "../system/hub";

/**
 * Agent loop detection.
 *
 * <p>The page has to show two things at once: what was caught, and what the
 * detector thinks of a sequence you hand it. The second matters more before the
 * feature is trusted — a detector nobody has watched make a decision is one
 * nobody will turn on.
 */

type Event = {
  kind: string;
  stepIndex: number;
  confidence: number;
  reason: string;
  evidence: string;
  halted: boolean;
  workflowId: string | null;
  at: string;
};

type Status = {
  enabled: boolean;
  mode: string;
  detected: number;
  halted: number;
  byKind: Record<string, number>;
  recent: Event[];
};

const KIND_STATE: Record<string, "critical" | "degraded" | "active"> = {
  REPETITION: "critical",
  OSCILLATION: "critical",
  PARAPHRASE: "degraded",
};

const SAMPLES: { label: string; steps: string[] }[] = [
  {
    label: "Stuck: the same step, three times",
    steps: ["read config.yaml", "read config.yaml", "read config.yaml"],
  },
  {
    label: "Stuck: alternating between two plans",
    steps: [
      "check the auth service logs",
      "restart the auth service",
      "check the auth service logs",
      "restart the auth service",
      "check the auth service logs",
      "restart the auth service",
    ],
  },
  {
    label: "Working: iterating over files",
    steps: ["read file src/a.java", "read file src/b.java", "read file src/c.java"],
  },
];

export default function LoopGuard() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [draft, setDraft] = useState(SAMPLES[0].steps.join("\n"));
  const [verdict, setVerdict] = useState<any>(null);

  const load = useCallback(async () => {
    try {
      setStatus(await portal.loops.status());
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load loop detection.");
    }
  }, []);

  useEffect(() => {
    void load();
    return visibleInterval(() => void load(), 3000);
  }, [load]);

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

  const inspect = async () => {
    const steps = draft.split("\n").map((s) => s.trim()).filter(Boolean);
    if (steps.length === 0) {
      toast("Add at least one step.", "error");
      return;
    }
    setBusy(true);
    try {
      const v = await portal.loops.inspect({ workflowId: "console-probe", steps });
      setVerdict(v);
      await load();
    } catch (e: any) {
      // HALT mode answers 409 with the verdict. That is the feature working,
      // not a failure, so it is shown as a result rather than as an error.
      const body = e?.body ?? e?.data ?? null;
      if (body && typeof body === "object" && "kind" in body) {
        setVerdict({ ...body, halted: true });
        await load();
      } else {
        toast(e?.message ?? "Could not inspect those steps.", "error");
      }
    } finally {
      setBusy(false);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  const events = status?.recent ?? [];

  return (
    <section className="space-y-8">
      <PageHeader
        glyph="shield"
        tone="ok"
        title="Loop Detection"
        subtitle="Stops agents that repeat without progress"
      />

      <div className="plane grid grid-cols-2 gap-x-8 gap-y-5 p-4 sm:grid-cols-3 lg:grid-cols-4">
        <Readout
          label="Loops caught"
          value={status?.detected ?? 0}
          size="sm"
          state={(status?.detected ?? 0) > 0 ? "degraded" : "idle"}
        />
        <Readout
          label="Runs stopped"
          value={status?.halted ?? 0}
          size="sm"
          state={(status?.halted ?? 0) > 0 ? "critical" : "idle"}
          hint="Only in HALT mode. In MONITOR the run continues and this stays zero."
        />
        <Readout label="Repetition" value={status?.byKind?.REPETITION ?? 0} size="sm" />
        <Readout
          label="Oscillation"
          value={status?.byKind?.OSCILLATION ?? 0}
          size="sm"
          hint="A → B → A → B. The shape that runs longest before anyone notices."
        />
      </div>

      <div className="space-y-3">
        <Switch
          checked={!!status?.enabled}
          busy={busy}
          onChange={(next) =>
            act(
              () => portal.loops.configure({ enabled: next }),
              next ? "Loop detection is on." : "Loop detection is off."
            )
          }
          label="Agent loop detection"
          hint="Off by default. While it is off a runaway agent stops when somebody notices the invoice."
        />

        {/* One setting with two positions, drawn like every other mode in the
            console rather than as a pair of chips unique to this page. */}
        <div className="max-w-md">
          <Segmented<string>
            value={status?.mode ?? "MONITOR"}
            onChange={(m) => act(() => portal.loops.configure({ mode: m }), `Mode is now ${m}.`)}
            options={[
              { value: "MONITOR", label: "Monitor" },
              { value: "HALT", label: "Halt" },
            ]}
          />
        </div>
        <p className="max-w-2xl text-xs leading-relaxed text-slate-500">
          {status?.mode === "HALT"
            ? "A detected loop stops the run and returns the verdict."
            : "A detected loop is recorded; the run continues."}
        </p>

        <Note>
          Start in <span className="readout">MONITOR</span> — it records what it would have stopped
          without stopping anything. A false positive stops an agent that was working.
        </Note>
      </div>

      <div className="space-y-3">
        <h2 className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight text-slate-200">Try it against a sequence
          <InfoTip text="One step per line, oldest first. Nothing is executed — the detector reads the steps and says what it sees. Progress is left unreported here, which is the pessimistic case: an agent that cannot say whether it advanced is exactly the one worth watching." />
        </h2>

        <div className="flex flex-wrap gap-2">
          {SAMPLES.map((s) => (
            <button
              key={s.label}
              onClick={() => {
                setDraft(s.steps.join("\n"));
                setVerdict(null);
              }}
              className="rounded-md border border-edge px-2.5 py-1 text-xs text-slate-400 hover:border-aurora/40 hover:text-slate-200"
            >
              {s.label}
            </button>
          ))}
        </div>

        <textarea
          aria-label="Conversation to check for loops"
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          rows={6}
          spellCheck={false}
          className="w-full font-mono field"
        />

        <StepBeads steps={draft.split("\n").map((x) => x.trim()).filter(Boolean)} looping={verdict?.looping} />

        <div className="flex flex-wrap items-center gap-3">
          <button
            disabled={busy || !status?.enabled}
            onClick={() => void inspect()}
            className="rounded-md border border-aurora/50 bg-aurora/10 px-3 py-1.5 text-sm text-slate-100 hover:bg-aurora/20 disabled:opacity-40"
          >
            Inspect
          </button>
          {!status?.enabled && (
            <span className="text-xs text-slate-500">Turn detection on to run the inspector.</span>
          )}
        </div>

        {verdict && (
          <div
            className={`rounded-md border p-3 ${
              verdict.looping
                ? "border-rose-500/40 bg-rose-500/5"
                : "border-emerald-500/40 bg-emerald-500/5"
            }`}
          >
            <div className="flex flex-wrap items-center gap-x-3 gap-y-2">
              <Pill tone={verdict.looping ? "bad" : "ok"} dot>
                {verdict.looping ? humanKind(verdict.kind) : "no loop — making progress"}
              </Pill>
              {verdict.looping && (
                <span className="flex min-w-[10rem] items-center gap-2">
                  <span className="micro">confidence</span>
                  <span className="w-24"><Meter value={Math.max(0, Math.min(1, Number(verdict.confidence)))} state="critical" height={5} /></span>
                  <span className="readout text-[11px] text-slate-400">{Number(verdict.confidence).toFixed(2)}</span>
                </span>
              )}
              {verdict.halted && <Pill tone="bad">run would be stopped</Pill>}
            </div>
            <p className="mt-1 text-xs text-slate-400 max-w-2xl leading-relaxed">{verdict.reason}</p>
            {Array.isArray(verdict.evidence) && verdict.evidence.length > 0 && (
              <ul className="mt-2 space-y-0.5">
                {verdict.evidence.map((e: string, i: number) => (
                  <li key={i} className="font-mono text-[11px] text-slate-500">
                    {e}
                  </li>
                ))}
              </ul>
            )}
          </div>
        )}
      </div>

      <div>
      <Explain title="What it will not do">
          <p>Repetition alone never trips it — the signal is repetition <em>without progress</em>. And
          paraphrase detection is deliberately narrow.</p>
          <p>
            A loop over twenty files issues twenty similar steps and is not stuck. Arguments count
            as part of the step: <span className="readout">read file src/a.java</span> and{" "}
            <span className="readout">read file src/b.java</span> score 1.00 on prose similarity
            because the vectoriser drops filenames, so a check on wording alone would fire on
            exactly the case it must not.
          </p>
          <p>
            Measured on this codebase&rsquo;s vectoriser, a genuine reword can score 0.26 while two
            plainly different steps score 0.67 — the populations overlap and no threshold separates
            them. The threshold is set high: it catches near-identical rewording and misses the rest.
          </p>
      </Explain>
      </div>

      <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Loops caught</h2>

      {status === null ? (
        <SkeletonRows rows={2} />
      ) : events.length === 0 ? (
        <Empty title={"Nothing caught yet"} hint={"Detected loops appear here with their steps."} />
      ) : (
        <div className="space-y-2">
          {events.map((e, i) => (
            <Plane key={i} className="space-y-2 p-3">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-1">
                <Pill tone={KIND_STATE[e.kind] === "critical" ? "bad" : "warn"} dot>{humanKind(e.kind)}</Pill>
                <span className="micro">at step {e.stepIndex}</span>
                {e.halted && <Pill tone="bad">stopped the run</Pill>}
                <span className="flex-1" />
                <span className="micro">{new Date(e.at).toLocaleTimeString()}</span>
              </div>
              <Meter
                value={Math.max(0, Math.min(1, e.confidence))}
                state={KIND_STATE[e.kind] ?? "active"}
                height={4}
              />
              <p className="text-xs text-slate-400 max-w-2xl leading-relaxed">{e.reason}</p>
              {e.evidence && (
                <p className="break-words font-mono text-[11px] text-slate-500">{e.evidence}</p>
              )}
            </Plane>
          ))}
        </div>
      )}

      {events.length > 0 && (
        <button
          disabled={busy}
          onClick={() => act(() => portal.loops.clear(), "History cleared.")}
          className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:border-aurora/50 disabled:opacity-40"
        >
          Clear history
        </button>
      )}
    </section>
  );
}

/**
 * Bead colours: deep enough that a white letter clears 4.5:1 on each, and the
 * same in both themes so a pattern read once is read the same way again.
 */
const BEADS = ["#1f5fb0", "#a8431b", "#11734f", "#7a5200", "#9c2f5a", "#2f6b00", "#5a4bbf", "#a33030"];
function beadInk(i: number) {
  return BEADS[i % BEADS.length];
}

function humanKind(kind: string) {
  const k = String(kind ?? "").toUpperCase();
  return k === "REPETITION" ? "repeating one step" : k === "OSCILLATION" ? "alternating between plans" : k === "PARAPHRASE" ? "rewording the same step" : k.toLowerCase();
}

/**
 * The steps as beads: one letter and colour per distinct step.
 *
 * <p>A loop is a shape in a sequence, and the shape is what a person
 * recognises — A A A is stuck, A B A B is going round, A B C is working. The
 * letters come from the steps themselves as written, so the pattern updates as
 * the sequence is edited, before anything is sent.
 */
function StepBeads({ steps, looping }: { steps: string[]; looping?: boolean }) {
  if (steps.length === 0) return null;
  const ids = new Map<string, number>();
  const seq = steps.map((raw) => {
    const key = raw.toLowerCase().replace(/\s+/g, " ");
    if (!ids.has(key)) ids.set(key, ids.size);
    return ids.get(key)!;
  });
  const letter = (i: number) => String.fromCharCode(65 + (i % 26));
  const legend = [...ids.entries()];
  return (
    <div className="plane p-4" aria-label={`Pattern ${seq.map(letter).join(" ")}`} role="img">
      <div className="flex flex-wrap items-center gap-1.5">
        {seq.map((id, i) => (
          <span key={i} className="flex items-center gap-1.5">
            <span
              className="grid h-8 w-8 place-items-center rounded-full text-[12px] font-semibold"
              style={{
                // Set, not the class: the light theme remaps text-white to ink.
                color: "#fff",
                background: beadInk(id),
                boxShadow: looping ? "0 0 0 2px var(--wash-bad)" : undefined,
              }}
              title={steps[i]}
            >
              {letter(id)}
            </span>
            {i < seq.length - 1 && <span className="h-px w-2.5 bg-slate-400/60" aria-hidden />}
          </span>
        ))}
        <span className="ml-2 text-[11px] text-slate-500">
          {ids.size === seq.length ? "every step different" : `${seq.length} steps, ${ids.size} distinct`}
        </span>
      </div>
      <ul className="mt-3 grid gap-1 sm:grid-cols-2">
        {legend.map(([text, id]) => (
          <li key={id} className="flex min-w-0 items-center gap-2 text-[11.5px] text-slate-400">
            <span className="grid h-4 w-4 shrink-0 place-items-center rounded-full text-[9px] font-bold" style={{ color: "#fff", background: beadInk(id) }}>
              {letter(id)}
            </span>
            <span className="truncate font-mono">{text}</span>
          </li>
        ))}
      </ul>
    </div>
  );
}
