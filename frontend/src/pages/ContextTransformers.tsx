import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { portal } from "../api";
import { Micro, PageHeader, Plane, Readout } from "../system/primitives";
import { BarChart, BeforeAfter, ChartFrame, foldTail } from "../system/charts";
import { ErrorState, useToast } from "../components/ui";

/**
 * The context layer.
 *
 * <p>The page is built around one claim, and every element on it is evidence for
 * or against that claim: <b>the same data, said in a way a model can reason
 * over, costs less and preserves more.</b>
 *
 * <p>So the layout is a pipeline read left to right — what you sent, what
 * Continuum recovered, what the model receives — with the token counts under it
 * and the unresolved ambiguities beside it. The ambiguities are given the same
 * visual weight as the savings on purpose: a layer that quietly guessed a
 * currency would show a better number here and be worse software.
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
  const [tab, setTab] = useState<"llm" | "structure" | "provenance" | "raw">("llm");
  const fileInput = useRef<HTMLInputElement | null>(null);

  const load = useCallback(async () => {
    try {
      const [caps, rows] = await Promise.all([
        portal.context.capabilities(),
        portal.context.recent(15),
      ]);
      setCapabilities(caps);
      setHistory(rows);
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
    <div className="space-y-4">
      <PageHeader
        title="Context Transformers"
        subtitle="Application data into a canonical form a model can reason over — deterministic, in process, no model involved"
      />

      {/* What this is, stated once. The distinction from Specialists is the
          thing developers get wrong, so it is said at the top rather than
          buried in a tooltip. */}
      <Plane className="p-4">
        <div className="flex flex-wrap items-start gap-x-6 gap-y-3">
          <div className="min-w-[16rem] flex-1">
            <Micro>What this is</Micro>
            <p className="mt-1 text-sm text-slate-400">
              A <span className="text-slate-200">Specialist</span> calls somebody else's model with
              your key. A <span className="text-slate-200">transformer</span> is Continuum doing the
              work itself: no API key, no model, no network. The same bytes produce the same output
              forever, which is what makes it safe to put in a cached, replayed, audited prompt.
            </p>
          </div>
          <div className="flex flex-wrap gap-2">
            {capabilities.map((c) => (
              <span
                key={c.name}
                title={c.summary}
                className="rounded-md border border-edge px-2 py-1 text-xs text-slate-300"
              >
                {c.label}
              </span>
            ))}
          </div>
        </div>
      </Plane>

      {/* Input */}
      <Plane className="space-y-3 p-4">
        <div className="flex flex-wrap items-baseline justify-between gap-2">
          <Micro>Give it something</Micro>
          <div className="flex gap-1">
            {BUDGETS.map((b) => (
              <button
                key={b.key}
                onClick={() => setBudget(b.key)}
                title={b.hint}
                className={`rounded px-2 py-0.5 text-[10px] uppercase tracking-wide transition-colors ${
                  budget === b.key
                    ? "chip-on"
                    : "text-slate-500 hover:text-slate-300"
                }`}
              >
                {b.label}
              </button>
            ))}
          </div>
        </div>

        <div className="flex flex-wrap items-center gap-2">
          <label className="cursor-pointer rounded-md border border-edge px-2.5 py-1 text-xs text-slate-300 transition-colors hover:border-aurora/60">
            Upload a file
            <input
              ref={fileInput}
              type="file"
              className="hidden"
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
            <span className="text-xs text-slate-600">
              A spreadsheet, a log file, or an email — recognised from its bytes, not its name.
            </span>
          )}
          <span className="mx-1 text-xs text-slate-700">or</span>
          {Object.entries(SAMPLES).map(([key, s]) => (
            <button
              key={key}
              onClick={() => {
                setPasted(s.body);
                setFile(null);
                if (fileInput.current) fileInput.current.value = "";
              }}
              className="rounded-md border border-edge px-2 py-1 text-xs text-slate-400 transition-colors hover:border-aurora/60 hover:text-slate-200"
            >
              try a sample {key}
            </button>
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
          className={`w-full rounded-md border border-edge bg-ink/60 px-2.5 py-2 font-mono text-[11px] text-slate-300 outline-none transition-colors focus:border-aurora/60 ${
            file ? "opacity-40" : ""
          }`}
        />

        <button
          onClick={run}
          disabled={busy}
          className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white transition-colors hover:bg-indigo-500 disabled:opacity-40"
        >
          {busy ? "Transforming…" : "Transform"}
        </button>
      </Plane>

      {result && <ResultView result={result} tab={tab} onTab={setTab} />}

      {history.length > 0 && (
        <Plane className="space-y-3 p-4">
          <div className="flex flex-wrap items-baseline justify-between gap-2">
            <Micro>Recent transformations</Micro>
            <span className="text-xs text-slate-600">
              {totals.saved > 0
                ? `${totals.saved.toLocaleString()} tokens saved across ${history.length} runs`
                : "no net saving yet"}
            </span>
          </div>
          <div className="overflow-x-auto">
            <table className="w-full min-w-[42rem] text-left text-xs">
              <thead>
                <tr className="text-slate-600">
                  <th className="pb-2 font-normal">Source</th>
                  <th className="pb-2 font-normal">Recovered as</th>
                  <th className="pb-2 font-normal">Structure</th>
                  <th className="pb-2 text-right font-normal">Before</th>
                  <th className="pb-2 text-right font-normal">After</th>
                  <th className="pb-2 text-right font-normal">Change</th>
                </tr>
              </thead>
              <tbody className="text-slate-400">
                {history.map((h) => (
                  <tr key={h.id} className="border-t border-edge/40">
                    <td className="py-1.5 font-mono text-[11px] text-slate-300">
                      {h.sourceName ?? "—"}
                    </td>
                    <td className="py-1.5">{h.contextType}</td>
                    <td className="py-1.5 text-slate-500">{h.structure ?? "—"}</td>
                    <td className="readout py-1.5 text-right">{h.tokensBefore.toLocaleString()}</td>
                    <td className="readout py-1.5 text-right">{h.tokensAfter.toLocaleString()}</td>
                    <td
                      className={`readout py-1.5 text-right ${
                        h.saved > 0 ? "text-emerald-400" : h.saved < 0 ? "text-amber-400" : ""
                      }`}
                    >
                      {h.saved === 0 ? "—" : `${h.saved > 0 ? "−" : "+"}${Math.abs(h.reduction * 100).toFixed(0)}%`}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </Plane>
      )}
    </div>
  );
}

/** The pipeline, the numbers, and what could not be determined. */
function ResultView({
  result,
  tab,
  onTab,
}: {
  result: Result;
  tab: "llm" | "structure" | "provenance" | "raw";
  onTab: (t: "llm" | "structure" | "provenance" | "raw") => void;
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
    <div className="space-y-4">
      {/* The pipeline, as a shape rather than a paragraph. */}
      <Plane className="p-4">
        <Micro>What happened</Micro>
        <div className="mt-3 flex flex-wrap items-stretch gap-2">
          <Stage
            label="Your input"
            value={result.sourceName ?? "input"}
            detail={`${s.before.toLocaleString()} tokens as text`}
          />
          <Arrow />
          <Stage
            label="Transformation"
            value={result.transformer ?? "none"}
            detail={result.transformed ? "deterministic, in process" : "nothing recognised it"}
            accent={result.transformed}
          />
          <Arrow />
          <Stage
            label="Canonical context"
            value={result.contextLabel}
            detail={Object.entries(result.structure)
              .slice(0, 3)
              .map(([k, v]) => `${v} ${k}`)
              .join(", ")}
            accent={result.transformed}
          />
          <Arrow />
          <Stage
            label="Sent to the model"
            value={`${s.after.toLocaleString()} tokens`}
            detail={grew ? "larger than the original" : `${(s.reduction * 100).toFixed(1)}% smaller`}
            accent={s.improved}
          />
        </div>
      </Plane>

      {/* The numbers. */}
      <div className="grid gap-4 lg:grid-cols-[2fr_1fr]">
        <Plane className="space-y-4 p-4">
          <div className="grid grid-cols-2 gap-4 sm:grid-cols-4">
            <Readout label="Before" value={s.before} unit="tokens" size="sm" />
            <Readout
              label="After"
              value={s.after}
              unit="tokens"
              size="sm"
              state={s.improved ? "active" : "degraded"}
            />
            <Readout
              label="Reduction"
              value={`${(s.reduction * 100).toFixed(1)}%`}
              size="sm"
              state={s.improved ? "active" : "degraded"}
              hint="Measured against a plain-text rendering of the same input — not against the raw bytes, which would report a saving that means nothing."
            />
            <Readout
              label="Unresolved"
              value={result.ambiguities.length}
              size="sm"
              state={result.ambiguities.length > 0 ? "degraded" : "idle"}
              hint="Things the transformer could not determine and refused to guess."
            />
          </div>
          {/* Two bars on one scale rather than a ratio meter: the claim of the
              whole layer is a comparison, and a comparison should be a length
              the reader can see instead of two numbers they subtract. */}
          <BeforeAfter
            beforeLabel="Raw"
            afterLabel="Canonical"
            before={s.before}
            after={s.after}
            unit="tok"
            goodDirection="down"
          />
          {grew && (
            // Said plainly rather than hidden. A structured rendering is
            // sometimes larger, and a page that only ever showed wins would be
            // advertising rather than instrumentation.
            <p className="text-xs text-amber-400/90">
              The canonical form is larger than the flattened original here. That is normal for
              small inputs, where the header block costs more than the repetition it removes — the
              structure is still worth having, but the token saving is not the reason.
            </p>
          )}
        </Plane>

        <Plane className="space-y-2 p-4">
          {Object.keys(result.structure).length === 0 ? (
            <>
              <Micro>Structure recovered</Micro>
              <p className="text-sm text-slate-500">Nothing structural was recovered.</p>
            </>
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
        </Plane>
      </div>

      {/* Ambiguities get their own panel, at full weight. */}
      {result.ambiguities.length > 0 && (
        <Plane className="space-y-2 border-amber-500/25 p-4">
          <Micro>Not determined — and deliberately not guessed</Micro>
          <p className="text-xs text-slate-500">
            These travel into the prompt as well as this page. A model told a unit is unknown says
            so; one that is handed a guessed unit reasons in it and never questions it.
          </p>
          <ul className="space-y-1.5 pt-1">
            {result.ambiguities.map((a, i) => (
              <li key={i} className="flex flex-wrap items-baseline gap-2 text-xs">
                <span className="rounded border border-amber-500/40 px-1 py-0.5 text-[10px] uppercase tracking-wide text-amber-400/90">
                  {a.kind}
                </span>
                <span className="font-mono text-slate-300">{a.where}</span>
                <span className="text-slate-500">{a.detail}</span>
              </li>
            ))}
          </ul>
        </Plane>
      )}

      {/* The output itself, in the four forms that matter. */}
      <Plane className="p-4">
        <div className="flex flex-wrap gap-1 border-b border-edge/60 pb-2">
          {[
            { k: "llm", label: "What the model receives" },
            { k: "structure", label: "Machine-readable" },
            { k: "provenance", label: `Provenance (${result.provenance.length})` },
            { k: "raw", label: "Original, flattened" },
          ].map((t) => (
            <button
              key={t.k}
              onClick={() => onTab(t.k as typeof tab)}
              className={`rounded px-2.5 py-1 text-xs transition-colors ${
                tab === t.k
                  ? "chip-on"
                  : "text-slate-500 hover:text-slate-300"
              }`}
            >
              {t.label}
            </button>
          ))}
        </div>

        <div className="pt-3">
          {tab === "llm" && (
            <pre className="max-h-[28rem] overflow-auto whitespace-pre-wrap break-words rounded-md bg-ink/70 p-3 font-mono text-[11px] leading-relaxed text-slate-300">
              {result.rendered}
            </pre>
          )}
          {tab === "structure" && (
            <pre className="max-h-[28rem] overflow-auto rounded-md bg-ink/70 p-3 font-mono text-[11px] leading-relaxed text-slate-300">
              {JSON.stringify(result.data, null, 2)}
            </pre>
          )}
          {tab === "provenance" &&
            (result.provenance.length === 0 ? (
              <p className="text-sm text-slate-500">
                No sources were recorded for this input.
              </p>
            ) : (
              <>
                <p className="pb-2 text-xs text-slate-500">
                  Where the values came from. A number a model was given that nobody can trace back
                  is a number nobody can check.
                </p>
                <ul className="max-h-[26rem] space-y-1 overflow-auto">
                  {result.provenance.map((p, i) => (
                    <li key={i} className="font-mono text-[11px] text-slate-400">
                      <span className="text-slate-300">{p.document}</span>
                      <span className="text-slate-600"> → </span>
                      <span className="text-aurora">{p.locator}</span>
                      {p.detail && <span className="text-slate-600"> ({p.detail})</span>}
                    </li>
                  ))}
                </ul>
              </>
            ))}
          {tab === "raw" && (
            <p className="text-sm text-slate-500">
              The comparison baseline is a plain-text rendering of your input — the CSV a
              spreadsheet flattens to, the log lines themselves. It is not shown here because it is
              your data; the {s.before.toLocaleString()}-token figure above is measured from it.
            </p>
          )}
        </div>
      </Plane>
    </div>
  );
}

function Stage({
  label,
  value,
  detail,
  accent = false,
}: {
  label: string;
  value: string;
  detail?: string;
  accent?: boolean;
}) {
  return (
    <div
      className={`min-w-[9rem] flex-1 rounded-md border px-3 py-2 transition-colors ${
        accent ? "border-aurora/40 bg-aurora/[0.04]" : "border-edge/60"
      }`}
    >
      <Micro>{label}</Micro>
      <div
        className={`mt-1 truncate text-sm font-medium ${
          accent ? "text-aurora" : "text-slate-200"
        }`}
        title={value}
      >
        {value}
      </div>
      {detail && <div className="mt-0.5 truncate text-[11px] text-slate-500" title={detail}>{detail}</div>}
    </div>
  );
}

function Arrow() {
  return (
    <div className="flex items-center self-center text-slate-700" aria-hidden>
      <svg width="18" height="10" viewBox="0 0 18 10" fill="none">
        <path
          d="M0 5h15M11 1l4 4-4 4"
          stroke="currentColor"
          strokeWidth="1.2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
    </div>
  );
}
