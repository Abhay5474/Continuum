import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Meter, PageHeader, Plane, Readout, Switch } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import { Explain, Segmented } from "../system/hub";

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
    const t = setInterval(() => void load(), 3000);
    return () => clearInterval(t);
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
        subtitle="An agent that has lost the thread does not crash — it keeps working, and every step is billable."
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

        <p className="max-w-2xl text-xs leading-relaxed text-slate-600">
          Start in <span className="readout">MONITOR</span> — it records what it would have stopped
          without stopping anything. A false positive stops an agent that was working.
        </p>
      </div>

      <div className="space-y-3">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Try it against a sequence</h2>
        <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
          One step per line, oldest first. Nothing is executed — the detector reads the steps and
          says what it sees. Progress is left unreported here, which is the pessimistic case: an
          agent that cannot say whether it advanced is exactly the one worth watching.
        </p>

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
          value={draft}
          onChange={(e) => setDraft(e.target.value)}
          rows={6}
          spellCheck={false}
          className="w-full rounded-md border border-edge bg-ink/60 p-2.5 font-mono text-xs text-slate-200 outline-none focus:border-aurora/50"
        />

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
            <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
              <span className="readout text-sm font-medium text-slate-100">
                {verdict.looping ? verdict.kind : "NO LOOP"}
              </span>
              {verdict.looping && (
                <span className="micro">confidence {Number(verdict.confidence).toFixed(2)}</span>
              )}
              {verdict.halted && <span className="micro text-rose-400">run would be stopped</span>}
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
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">What it will not do</h2>
        <p className="mt-1.5 max-w-2xl text-xs leading-relaxed text-slate-600">
          Repetition alone never trips it — the signal is repetition <em>without progress</em>. And
          paraphrase detection is deliberately narrow.
        </p>
        <Explain title="Why both are deliberate">
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
        <div className="rounded-xl border border-dashed px-3 py-10 text-center text-sm text-slate-500" style={{ borderColor: "rgb(var(--card-edge))" }}>
          Nothing caught yet. Loops found in your agents — or by the inspector above — appear here
          with the steps they are accusing.
        </div>
      ) : (
        <div className="space-y-2">
          {events.map((e, i) => (
            <Plane key={i} className="space-y-2 p-3">
              <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
                <span className="readout text-sm text-slate-200">{e.kind}</span>
                <span className="micro">step {e.stepIndex}</span>
                {e.halted && <span className="micro text-rose-400">stopped the run</span>}
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
