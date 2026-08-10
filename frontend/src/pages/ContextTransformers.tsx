import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { portal } from "../api";
import { Micro, Switch } from "../system/primitives";
import { BarChart, BeforeAfter, ChartFrame, foldTail } from "../system/charts";
import { ErrorState, useToast } from "../components/ui";
import {
  Bar,
  Code,
  Dot,
  Empty,
  Facts,
  Ghost,
  Hop,
  KindMark,
  Primary,
  Rail,
  Route,
  Row,
  Segmented,
  Stage,
  Stat,
  Stats,
  kindOf, Chip } from "../system/hub";

/**
 * The context layer.
 *
 * <p>The page is built around one claim, and every element on it is evidence for
 * or against that claim: <b>the same data, said in a way a model can reason
 * over, costs less and preserves more.</b>
 *
 * <p>So the layout is a path read left to right — what you sent, what Continuum
 * recovered, what the model receives — with the token counts under it and the
 * unresolved ambiguities beside it. The ambiguities are given the same visual
 * weight as the savings on purpose: a layer that quietly guessed a currency
 * would show a better number here and be worse software.
 */

type Ambiguity = { kind: string; where: string; detail: string };
type Source = { document: string; locator: string; detail: string | null };

type Result = {
  transformed: boolean;
  transformer: string | null;
  contextType: string;
  contextLabel: string;
  sourceName: string | null;
  rendered: string;
  tokenStats: {
    before: number;
    after: number;
    saved: number;
    reduction: number;
    improved: boolean;
  };
  structure: Record<string, number>;
  ambiguities: Ambiguity[];
  provenance: Source[];
  data: Record<string, unknown>;
};

type Capability = {
  name: string;
  label: string;
  produces: string;
  producesLabel: string;
  summary: string;
};

type HistoryRow = {
  id: number;
  contextType: string;
  transformer: string | null;
  sourceName: string | null;
  tokensBefore: number;
  tokensAfter: number;
  saved: number;
  reduction: number;
  ambiguities: number;
  structure: string | null;
  createdAt: string;
};

const BUDGETS = [
  { key: "summary", label: "Summary", hint: "Shape and totals only — for routing and previews." },
  { key: "standard", label: "Standard", hint: "Enough for real reasoning over the data." },
  { key: "full", label: "Full", hint: "Everything, with sources. An audit view, not a prompt." },
];

/** A worked example per transformer, so the page does something on first visit. */
const SAMPLES: Record<string, { name: string; body: string }> = {
  logs: {
    name: "outage.log",
    body: Array.from({ length: 40 }, (_, i) =>
      `2026-08-02T14:0${i % 6}:${String(i % 60).padStart(2, "0")}Z INFO [api-gateway] ` +
      `GET /v1/orders/${100000 + i} 200 ${20 + (i % 40)}ms`
    )
      .concat(
        Array.from({ length: 12 }, (_, i) =>
          `2026-08-02T14:0${3 + (i % 6)}:${String(i % 60).padStart(2, "0")}Z ERROR [payments] ` +
          `Timeout calling upstream billing-api after 30000ms`
        )
      )
      .concat([
        "java.net.SocketTimeoutException: Read timed out",
        "\tat com.acme.payments.UpstreamClient.charge(UpstreamClient.java:88)",
        "\tat com.acme.payments.ChargeService.run(ChargeService.java:41)",
      ])
      .join("\n"),
  },
  spreadsheet: {
    name: "regions.csv",
    body:
      "Region,Revenue (₹ crore),Units\n" +
      "West,125.4,8200\nEast,98.1,6400\nNorth,76.5,5100\nTotal,300.0,19700\n",
  },
};

type Tab = "llm" | "structure" | "provenance" | "raw";

/** Which request paths hand a transformed context to a model. */
type Reach = {
  gatewayEnabled: boolean;
  pipelineAlwaysOn: boolean;
  minChars: number;
  minLines: number;
};

/** {@code SEMANTIC_TABLE} and {@code timelineEvents} are field names, not prose. */
function words(raw: string) {
  return String(raw ?? "")
    .replace(/_/g, " ")
    .replace(/([a-z])([A-Z])/g, "$1 $2")
    .toLowerCase();
}

export default function ContextTransformers() {
  const toast = useToast();
  const [capabilities, setCapabilities] = useState<Capability[]>([]);
  const [history, setHistory] = useState<HistoryRow[]>([]);
  const [result, setResult] = useState<Result | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [budget, setBudget] = useState("standard");
  const [pasted, setPasted] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [tab, setTab] = useState<Tab>("llm");
  const [reach, setReach] = useState<Reach | null>(null);
  const fileInput = useRef<HTMLInputElement | null>(null);

  const load = useCallback(async () => {
    try {
      const [caps, rows, st] = await Promise.all([
        portal.context.capabilities(),
        portal.context.recent(15),
        portal.context.status(),
      ]);
      setCapabilities(caps);
      setHistory(rows);
      setReach(st);
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load the context layer.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const run = async () => {
    if (!file && !pasted.trim()) {
      toast("Upload a file or paste some data first.", "error");
      return;
    }
    setBusy(true);
    setResult(null);
    try {
      const r = file
        ? await portal.context.transformFile(file, budget)
        : await portal.context.transformText(pasted, "pasted-input", budget);
      setResult(r);
      setTab("llm");
      if (!r.transformed) {
        toast("Nothing recognised this input — it was passed through unchanged.", "error");
      }
      void load();
    } catch (e: any) {
      toast(e?.message ?? "The transformation did not complete.", "error");
    } finally {
      setBusy(false);
    }
  };

  const totals = useMemo(() => {
    const before = history.reduce((n, h) => n + h.tokensBefore, 0);
    const after = history.reduce((n, h) => n + h.tokensAfter, 0);
    return { before, after, saved: before - after, reduction: before ? (before - after) / before : 0 };
  }, [history]);

  if (error) return <ErrorState message={error} onRetry={load} />;

  return (
    <div className="page-enter">
      <header>
        <div className="flex items-center gap-2.5">
          <Chip glyph="layers" tone="accent" size={28} />
          <h1 className="text-[20px] font-semibold tracking-[-0.011em] text-slate-100">
          Context Transformers
        </h1>
        </div>
        <p className="mt-1 max-w-2xl text-[13px] leading-relaxed text-slate-500">
          Application data into a canonical form a model can reason over — deterministic, in process,
          with no model involved.
        </p>
      </header>

      {/* The distinction from Specialists is the thing developers get wrong, so
          it is drawn rather than written: nothing on this path leaves the
          process. */}
      <div className="mt-6">
        <Route>
          <Stage label="your data" sub="a file, or bytes on the wire" />
          <Hop label="in process" />
          <Stage
            label="a transformer"
            sub="no key, no model, no network"
            state="on"
            mark={<KindMark kind="table" size={26} />}
            selected
          />
          <Hop label="canonical" />
          {/* This stage used to read "the prompt" unconditionally, which was
              only ever true for pipelines. It now says which paths are live. */}
          <Stage
            label="the prompt"
            sub={
              reach?.gatewayEnabled
                ? "pipelines and chat completions"
                : "pipelines only — chat is off"
            }
            state={reach ? (reach.gatewayEnabled ? "on" : "off") : "plain"}
          />
        </Route>
        <p className="mt-3 max-w-2xl text-[12.5px] leading-relaxed text-slate-500">
          A <span className="text-slate-300">Specialist</span> calls somebody else's model with your
          key. A <span className="text-slate-300">transformer</span> is Continuum doing the work
          itself. The same bytes produce the same output forever, which is what makes it safe to put
          in a cached, replayed, audited prompt.
        </p>
      </div>

      <Reaches
        reach={reach}
        busy={busy}
        onToggle={async (next) => {
          setBusy(true);
          try {
            setReach(await portal.context.setGateway(next));
            toast(
              next
                ? "Chat completions will now be given the canonical form."
                : "Chat completions go to the provider verbatim again."
            );
          } catch (e: any) {
            toast(e?.message ?? "Could not change that setting.", "error");
          } finally {
            setBusy(false);
          }
        }}
      />

      {capabilities.length > 0 && (
        <section className="mt-8">
          <h2 className="flex items-baseline gap-2 text-[13px] font-semibold tracking-tight text-slate-200">
            What it recognises
            <span className="readout text-[11px] font-normal text-slate-600">{capabilities.length}</span>
          </h2>
          <div className="mt-3">
            <Rail>
              {capabilities.map((c) => (
                <Row
                  key={c.name}
                  mark={<KindMark kind={kindOf(`${c.produces} ${c.name}`)} size={28} />}
                  // The label already reads "Email thread → Conversation", so
                  // a "produces: Conversation" fact underneath would say the
                  // same thing twice at two different weights.
                  title={c.label}
                  subtitle={c.summary}
                />
              ))}
            </Rail>
          </div>
        </section>
      )}

      {/* Input */}
      <section className="mt-9">
        <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-2">
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Give it something</h2>
          <div className="flex items-center gap-4">
            <span className="micro">render at</span>
            {BUDGETS.map((b) => {
              const on = budget === b.key;
              return (
                <button
                  key={b.key}
                  onClick={() => setBudget(b.key)}
                  title={b.hint}
                  className="relative text-[12px] transition-colors"
                  style={{ color: on ? "var(--accent-ink)" : "var(--text-3)" }}
                >
                  {b.label}
                  <span
                    className="absolute inset-x-0 -bottom-1 h-[1.5px] transition-opacity"
                    style={{ background: "var(--accent)", opacity: on ? 1 : 0 }}
                    aria-hidden
                  />
                </button>
              );
            })}
          </div>
        </div>

        <div className="mt-4 flex flex-wrap items-center gap-2">
          <label
            className="cursor-pointer rounded-md px-2.5 py-1 text-[11.5px] text-slate-300 transition-colors hover:text-slate-100"
            style={{ boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }}
          >
            Upload a file
            <input
              ref={fileInput}
              type="file"
              className="hidden field"
              onChange={(e) => {
                setFile(e.target.files?.[0] ?? null);
                setPasted("");
              }}
            />
          </label>
          {file ? (
            <span className="flex items-center gap-2 text-xs text-slate-400">
              <span className="font-mono">{file.name}</span>
              <span className="text-slate-600">{(file.size / 1024).toFixed(0)} KB</span>
              <button
                onClick={() => {
                  setFile(null);
                  if (fileInput.current) fileInput.current.value = "";
                }}
                className="text-slate-500 underline hover:text-slate-300"
              >
                remove
              </button>
            </span>
          ) : (
            <span className="text-[11.5px] text-slate-600">
              A spreadsheet, a log file, or an email — recognised from its bytes, not its name.
            </span>
          )}
          <span className="mx-1 text-xs text-slate-700">or</span>
          {Object.entries(SAMPLES).map(([key, s]) => (
            <Ghost
              key={key}
              onClick={() => {
                setPasted(s.body);
                setFile(null);
                if (fileInput.current) fileInput.current.value = "";
              }}
            >
              try a sample {key}
            </Ghost>
          ))}
        </div>

        <textarea
          value={pasted}
          onChange={(e) => {
            setPasted(e.target.value);
            if (e.target.value) setFile(null);
          }}
          disabled={!!file}
          rows={5}
          placeholder="…or paste a log, a CSV, or a raw email here."
          className={`mt-3 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-2 font-mono text-[11px] text-slate-300 outline-none transition-colors focus:border-[color:var(--accent-edge)] ${
            file ? "opacity-40" : ""
          }`}
        />

        <div className="mt-3">
          <Primary onClick={run} disabled={busy}>
            {busy ? "Transforming…" : "Transform"}
          </Primary>
        </div>
      </section>

      {result && (
        <div className="mt-9">
          <ResultView result={result} tab={tab} onTab={setTab} />
        </div>
      )}

      <section className="mt-10">
        <div className="flex flex-wrap items-baseline justify-between gap-x-6 gap-y-1">
          <h2 className="flex items-baseline gap-2 text-[13px] font-semibold tracking-tight text-slate-200">
            Recent transformations
            {history.length > 0 && (
              <span className="readout text-[11px] font-normal text-slate-600">{history.length}</span>
            )}
          </h2>
          {history.length > 0 && (
            <span className="text-xs text-slate-600">
              {totals.saved > 0
                ? `${totals.saved.toLocaleString()} tokens saved across ${history.length} runs`
                : "no net saving yet"}
            </span>
          )}
        </div>
        <div className="mt-3">
          {history.length === 0 ? (
            <Empty
              title="Nothing transformed yet"
              hint="Transform something above, or send a file through the gateway. Every run is recorded here with its token cost before and after."
            />
          ) : (
            <Rail>
              {history.map((h) => {
                const grew = h.saved < 0;
                return (
                  <Row
                    key={h.id}
                    mark={<KindMark kind={kindOf(h.contextType)} size={28} />}
                    title={h.sourceName ?? "pasted input"}
                    subtitle={`${words(h.contextType)}${h.structure ? ` · ${words(h.structure)}` : ""}`}
                    status={
                      <span
                        className="readout text-[11px]"
                        style={{
                          color: h.saved > 0
                            ? "var(--state-healthy-ink)"
                            : grew
                              ? "var(--state-warning-ink)"
                              : "var(--text-3)",
                        }}
                      >
                        {h.saved === 0
                          ? "no change"
                          : `${h.saved > 0 ? "−" : "+"}${Math.abs(h.reduction * 100).toFixed(0)}%`}
                      </span>
                    }
                    meta={
                      <Facts
                        items={[
                          { k: "before", v: h.tokensBefore.toLocaleString() },
                          { k: "after", v: h.tokensAfter.toLocaleString() },
                          ...(h.ambiguities > 0
                            ? [{ k: "unresolved", v: h.ambiguities, title: "Things it refused to guess" }]
                            : []),
                        ]}
                      />
                    }
                  />
                );
              })}
            </Rail>
          )}
        </div>
      </section>
    </div>
  );
}

/**
 * Which request paths actually hand the canonical form to a model.
 *
 * <p>The page did not say. It drew one arrow into "the prompt" and left you to
 * assume that covered everything you send, when in fact it covered the pipeline
 * endpoint and not {@code /v1/chat/completions} — the one most callers use. A
 * feature that works on one of two paths and shows one path is not a UI detail;
 * it is the page making a claim the system does not honour.
 *
 * <p>Two rows, because the two paths genuinely differ and collapsing them into
 * a single switch would imply turning it off stops transformation everywhere.
 * It does not: a pipeline is configured, watched and traced, so it transforms
 * unconditionally and always has.
 */
function Reaches({
  reach,
  busy,
  onToggle,
}: {
  reach: Reach | null;
  busy: boolean;
  onToggle: (next: boolean) => void;
}) {
  return (
    <section className="mt-8">
      <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">
        Where this reaches a model
      </h2>
      <div className="mt-3">
        <Rail>
          <Row
            mark={<KindMark kind="detection" size={28} />}
            title="Pipelines"
            subtitle="A recognised payload is transformed before the model sees it, and the exact prose appears in the run's trace."
            status={<Dot tone="ok" label="always on" />}
          />
          <Row
            mark={<KindMark kind="conversation" size={28} />}
            title="Chat completions"
            subtitle="Data pasted into a user message is transformed on its way out. Prose is never restructured."
            meta={
              reach ? (
                <Facts
                  items={[
                    { k: "reads", v: "a whole message, or a fenced block" },
                    {
                      k: "floor",
                      v: `${reach.minChars} characters · ${reach.minLines} lines`,
                      title:
                        "Below this the saving is not worth having, and a short message is the one a coincidence could ruin.",
                    },
                  ]}
                />
              ) : undefined
            }
            trailing={
              <Switch
                label="Transform data in chat messages"
                checked={!!reach?.gatewayEnabled}
                busy={busy || reach === null}
                onChange={onToggle}
              />
            }
          />
        </Rail>
      </div>
    </section>
  );
}

/** The path, the numbers, and what could not be determined. */
function ResultView({
  result,
  tab,
  onTab,
}: {
  result: Result;
  tab: Tab;
  onTab: (t: Tab) => void;
}) {
  const s = result.tokenStats;
  const grew = !s.improved && s.before > 0;

  const structureBars = foldTail(
    Object.entries(result.structure)
      .filter(([, v]) => typeof v === "number" && v > 0)
      .map(([k, v]) => ({
        key: k,
        label: k.replace(/([A-Z])/g, " $1").toLowerCase(),
        value: v as number,
      })),
    7
  );

  return (
    <div>
      <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">What happened</h2>

      <div className="mt-3">
        <Route>
          <Stage
            label={result.sourceName ?? "your input"}
            sub={`${s.before.toLocaleString()} tokens as text`}
          />
          <Hop />
          <Stage
            label={result.transformer ?? "nothing recognised it"}
            sub={result.transformed ? "deterministic, in process" : "passed through unchanged"}
            state={result.transformed ? "on" : "bad"}
            mark={<KindMark kind={kindOf(result.contextType)} size={26} />}
            selected={result.transformed}
          />
          <Hop />
          <Stage
            label={result.contextLabel}
            sub={
              Object.entries(result.structure)
                .slice(0, 3)
                .map(([k, v]) => `${v} ${words(k)}`)
                .join(", ") || "no structure recovered"
            }
          />
          <Hop label="rendered" />
          <Stage
            label={`${s.after.toLocaleString()} tokens`}
            sub={grew ? "larger than the original" : `${(s.reduction * 100).toFixed(1)}% smaller`}
            state={s.improved ? "on" : "off"}
          />
        </Route>
      </div>

      <div className="mt-7">
        <Stats>
          <Stat label="Before" value={s.before.toLocaleString()} unit="tokens" />
          <Stat
            label="After"
            value={s.after.toLocaleString()}
            unit="tokens"
            tone={s.improved ? "ok" : "warn"}
          />
          {/* "Reduction: −504%" is a double negative the reader has to unpick.
              When the canonical form is larger the measurement has a different
              name, so it is given one. */}
          <Stat
            label={s.improved ? "Reduction" : "Growth"}
            value={`${Math.abs(s.reduction * 100).toFixed(1)}%`}
            tone={s.improved ? "ok" : "warn"}
            hint="Measured against a plain-text rendering of the same input — not against the raw bytes, which would report a saving that means nothing."
          />
          <Stat
            label="Unresolved"
            value={result.ambiguities.length}
            tone={result.ambiguities.length > 0 ? "warn" : undefined}
            hint="Things the transformer could not determine and refused to guess."
          />
        </Stats>
      </div>

      <div className="mt-7 grid items-start gap-x-10 gap-y-8 lg:grid-cols-2">
        {/* Two bars on one scale rather than a ratio meter: the claim of the
            whole layer is a comparison, and a comparison should be a length the
            reader can see instead of two numbers they subtract. */}
        <ChartFrame
          title="Tokens, before and after"
          valueLabel="Tokens"
          caption="Against a plain-text rendering of the same input, not against the raw bytes."
          data={[
            { key: "before", label: "Raw", value: s.before },
            { key: "after", label: "Canonical", value: s.after },
          ]}
        >
          <BeforeAfter
            beforeLabel="Raw"
            afterLabel="Canonical"
            before={s.before}
            after={s.after}
            unit="tok"
            goodDirection="down"
          />
        </ChartFrame>

        {structureBars.length === 0 ? (
          <div>
            <Micro>Structure recovered</Micro>
            <p className="mt-1.5 text-[13px] text-slate-500">Nothing structural was recovered.</p>
          </div>
        ) : (
          <ChartFrame
            title="Structure recovered"
            valueLabel="Count"
            caption="What the transformer found that a flat export would have lost."
            data={structureBars}
          >
            <BarChart data={structureBars} />
          </ChartFrame>
        )}
      </div>

      {grew && (
        // Said plainly rather than hidden. A structured rendering is sometimes
        // larger, and a page that only ever showed wins would be advertising
        // rather than instrumentation.
        <p
          className="mt-6 max-w-2xl border-l-2 pl-3.5 text-xs leading-relaxed text-slate-400"
          style={{ borderColor: "var(--state-warning-ink)" }}
        >
          The canonical form is larger than the flattened original here. That is normal for small
          inputs, where the header block costs more than the repetition it removes — the structure is
          still worth having, but the token saving is not the reason.
        </p>
      )}

      {/* Ambiguities at full weight, on their own rule. */}
      {result.ambiguities.length > 0 && (
        <section className="mt-9">
          <h3
            className="border-l-2 pl-3.5 text-[13px] font-semibold tracking-tight"
            style={{ borderColor: "var(--state-warning-ink)", color: "var(--state-warning-ink)" }}
          >
            Not determined — and deliberately not guessed
          </h3>
          <p className="mt-2 max-w-2xl text-xs leading-relaxed text-slate-500">
            These travel into the prompt as well as this page. A model told a unit is unknown says
            so; one that is handed a guessed unit reasons in it and never questions it.
          </p>
          <div className="mt-3">
            <Rail>
              {result.ambiguities.map((a, i) => (
                <Row
                  key={i}
                  title={<span className="font-mono text-[12.5px]">{a.where}</span>}
                  subtitle={a.detail}
                  status={
                    <span className="micro" style={{ color: "var(--state-warning-ink)" }}>
                      {a.kind}
                    </span>
                  }
                />
              ))}
            </Rail>
          </div>
        </section>
      )}

      {/* The output itself, in the four forms that matter. */}
      <section className="mt-9">
        <Segmented<Tab>
          value={tab}
          onChange={onTab}
          options={[
            { value: "llm", label: "What the model receives" },
            { value: "structure", label: "Machine-readable" },
            {
              value: "provenance",
              label: "Provenance",
              badge: (
                <span className="readout text-[10px] text-slate-600">{result.provenance.length}</span>
              ),
            },
            { value: "raw", label: "Original, flattened" },
          ]}
        />

        <div className="mt-4">
          {tab === "llm" && <Code>{result.rendered}</Code>}
          {tab === "structure" && <Code>{JSON.stringify(result.data, null, 2)}</Code>}
          {tab === "provenance" &&
            (result.provenance.length === 0 ? (
              <p className="text-[13px] text-slate-500">No sources were recorded for this input.</p>
            ) : (
              <>
                <p className="max-w-2xl pb-3 text-xs leading-relaxed text-slate-500">
                  Where the values came from. A number a model was given that nobody can trace back
                  is a number nobody can check.
                </p>
                <Rail>
                  {result.provenance.slice(0, 60).map((p, i) => (
                    <Row
                      key={i}
                      title={<span className="font-mono text-[12px]">{p.document}</span>}
                      subtitle={p.detail ?? undefined}
                      status={
                        <span className="readout text-[11px]" style={{ color: "var(--accent-ink)" }}>
                          {p.locator}
                        </span>
                      }
                    />
                  ))}
                </Rail>
                {result.provenance.length > 60 && (
                  <p className="mt-2 text-xs text-slate-600 max-w-2xl leading-relaxed">
                    {result.provenance.length - 60} more sources, in the machine-readable form.
                  </p>
                )}
              </>
            ))}
          {tab === "raw" && (
            <div className="max-w-2xl">
              <p className="text-[13px] leading-relaxed text-slate-500">
                The comparison baseline is a plain-text rendering of your input — the CSV a
                spreadsheet flattens to, the log lines themselves. It is not shown here because it is
                your data; the {s.before.toLocaleString()}-token figure above is measured from it.
              </p>
              <div className="mt-4 flex items-center gap-3">
                <span className="micro w-20 shrink-0">baseline</span>
                <Bar fraction={1} tone="mute" width={180} />
                <span className="readout text-[11px] text-slate-500">
                  {s.before.toLocaleString()}
                </span>
              </div>
              <div className="mt-1.5 flex items-center gap-3">
                <span className="micro w-20 shrink-0">canonical</span>
                <Bar
                  fraction={s.before > 0 ? s.after / s.before : 0}
                  tone={s.improved ? "ok" : "warn"}
                  width={180}
                />
                <span className="readout text-[11px] text-slate-500">{s.after.toLocaleString()}</span>
              </div>
            </div>
          )}
        </div>
      </section>
    </div>
  );
}
