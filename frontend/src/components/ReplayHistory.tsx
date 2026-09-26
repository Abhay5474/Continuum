import { useEffect, useMemo, useState } from "react";
import { api } from "../api";
import { BarChart, ChartFrame, Donut, SeriesChart, type Datum } from "../system/charts";
import { Empty, Pill } from "../system/hub";
import { Table, TD, TH, TR } from "../system/controls";
import { dateTimeOf, toMillis } from "../system/time";

/**
 * Semantic replay reports: every model output that was regenerated and scored
 * against what the run originally produced. The verify call returned these for
 * one run at a time and forgot them; the history was stored but had no screen.
 */

export type ReplayReport = {
  id: number;
  workflowId: string;
  commandSeq: number;
  activityType: string;
  historicalOutput: string | null;
  freshOutput: string | null;
  freshProvider: string | null;
  similarityScore: number;
  intentScore: number;
  toolScore: number;
  structuredScore: number;
  constraintScore: number;
  overallScore: number;
  passed: boolean;
  method: string | null;
  explanation: string | null;
  createdAt: unknown;
};

const DIMENSIONS: [keyof ReplayReport, string][] = [
  ["similarityScore", "Similarity"],
  ["intentScore", "Intent"],
  ["toolScore", "Tool calls"],
  ["structuredScore", "Structure"],
  ["constraintScore", "Constraints"],
];

const pct = (n: number) => Math.round(n * 1000) / 10;

/** Recent verifications across every run: how often replay holds, and where it slips. */
export function ReplayTrends({ onOpen }: { onOpen: (workflowId: string) => void }) {
  const [rows, setRows] = useState<ReplayReport[] | null>(null);
  useEffect(() => {
    api.get<ReplayReport[]>("/api/replay/trends?limit=200").then(setRows).catch(() => setRows([]));
  }, []);

  const passFail: Datum[] = useMemo(() => {
    const ok = (rows ?? []).filter((r) => r.passed).length;
    return [
      { key: "pass", label: "Equivalent", value: ok, color: "var(--state-healthy-ink)" },
      { key: "fail", label: "Drifted", value: (rows ?? []).length - ok, color: "var(--state-critical-ink)" },
    ];
  }, [rows]);

  // A negative score means the dimension did not apply (no tool calls, no
  // structured output) — averaged in, it read as −100%.
  const { dims, notApplicable } = useMemo(() => {
    const dims: Datum[] = [];
    const notApplicable: string[] = [];
    for (const [k, label] of DIMENSIONS) {
      const vals = (rows ?? []).map((r) => Number(r[k])).filter((v) => Number.isFinite(v) && v >= 0);
      if (vals.length === 0) notApplicable.push(label.toLowerCase());
      else dims.push({ key: k, label, value: pct(vals.reduce((a, v) => a + v, 0) / vals.length), hint: `${vals.length} check${vals.length === 1 ? "" : "s"}` });
    }
    return { dims, notApplicable };
  }, [rows]);

  const series = useMemo(() => {
    const ordered = [...(rows ?? [])].sort((a, b) => (toMillis(a.createdAt) ?? 0) - (toMillis(b.createdAt) ?? 0));
    return [
      { key: "overall", label: "Overall score", points: ordered.map((r) => pct(r.overallScore)) },
      { key: "intent", label: "Intent", points: ordered.map((r) => pct(r.intentScore)) },
    ];
  }, [rows]);

  if (rows === null) return <p className="text-sm text-slate-500">Loading history…</p>;
  if (rows.length === 0)
    return (
      <Empty
        title="No model outputs re-scored yet"
        glyph="check"
        hint="Verifying a run that made model calls regenerates each output against today's provider and scores the two for equivalence. Those scores collect here."
      />
    );

  const passRate = passFail[0].value / rows.length;
  return (
    <section className="space-y-4">
      <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Model-output drift · last {rows.length} checks</h2>
      <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.4fr)]">
        <ChartFrame title="Still equivalent today" data={passFail} valueLabel="Checks">
          <Donut data={passFail} centerValue={`${Math.round(passRate * 100)}%`} centerLabel="equivalent" />
        </ChartFrame>
        <ChartFrame title="Score over time" caption="Oldest on the left. A falling line is a provider drifting away from what your runs recorded." data={series[0].points.map((v, i) => ({ key: String(i), label: `#${i + 1}`, value: v }))} unit="%">
          <SeriesChart series={series} unit="%" />
        </ChartFrame>
      </div>
      <ChartFrame
        title="Average by dimension"
        caption={`Where equivalence is lost: wording, intent, tool calls, output shape, or the run's constraints.${notApplicable.length ? ` Not applicable to these outputs: ${notApplicable.join(", ")}.` : ""}`}
        data={dims}
        unit="%"
      >
        <BarChart data={dims} unit="%" max={100} />
      </ChartFrame>
      <ReportTable rows={rows.slice(0, 40)} onOpen={onOpen} />
    </section>
  );
}

/** Every earlier check of one run, newest first. */
export function RunReports({ workflowId }: { workflowId: string }) {
  const [rows, setRows] = useState<ReplayReport[] | null>(null);
  useEffect(() => {
    setRows(null);
    api.get<ReplayReport[]>(`/api/replay/reports/${encodeURIComponent(workflowId)}`).then(setRows).catch(() => setRows([]));
  }, [workflowId]);
  if (!rows || rows.length === 0) return null;
  return (
    <section className="space-y-2">
      <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Model outputs checked for this run · {rows.length}</h2>
      <ReportTable rows={rows} />
    </section>
  );
}

function ReportTable({ rows, onOpen }: { rows: ReplayReport[]; onOpen?: (workflowId: string) => void }) {
  const [open, setOpen] = useState<number | null>(null);
  return (
    <Table
      minWidth={680}
      maxHeight={520}
      head={
        <tr>
          <TH>When</TH>
          {onOpen && <TH>Run</TH>}
          <TH>Step</TH>
          <TH width={170}>Score</TH>
          <TH>Verdict</TH>
          <TH align="right">Outputs</TH>
        </tr>
      }
    >
      {rows.map((r) => (
        <TR key={r.id}>
          <TD>
            <span className="whitespace-nowrap text-slate-400">{dateTimeOf(r.createdAt)}</span>
          </TD>
          {onOpen && (
            <TD>
              <button onClick={() => onOpen(r.workflowId)} className="max-w-[160px] truncate font-mono text-[11.5px] text-slate-300 underline-offset-2 hover:underline" title={r.workflowId}>
                {r.workflowId}
              </button>
            </TD>
          )}
          <TD>
            <span className="text-slate-300">{r.activityType}</span>
            <span className="ml-1 text-[11px] text-slate-500">#{r.commandSeq}</span>
          </TD>
          <TD>
            <span className="flex items-center gap-2">
              <span className="relative h-1.5 w-20 overflow-hidden rounded-full" style={{ background: "rgb(var(--card-rule))" }} aria-hidden>
                <span className="grow-x absolute inset-y-0 left-0 rounded-full" style={{ width: `${pct(r.overallScore)}%`, background: r.passed ? "var(--state-healthy-ink)" : "var(--state-critical-ink)" }} />
              </span>
              <span className="readout text-[12px]">{pct(r.overallScore)}%</span>
            </span>
          </TD>
          <TD>
            <Pill tone={r.passed ? "ok" : "bad"} dot>
              {r.passed ? "equivalent" : "drifted"}
            </Pill>
          </TD>
          <TD numeric>
            <button onClick={() => setOpen(open === r.id ? null : r.id)} aria-expanded={open === r.id} className="text-[12px] text-slate-400 hover:text-slate-200">
              {open === r.id ? "hide" : "compare"}
            </button>
            {open === r.id && (
              <div className="rise-in mt-2 grid min-w-[420px] gap-2 text-left sm:grid-cols-2">
                <OutputBox label="Recorded" text={r.historicalOutput} />
                <OutputBox label={`Today${r.freshProvider ? ` · ${r.freshProvider}` : ""}`} text={r.freshOutput} />
                {r.explanation && <p className="text-[11.5px] text-slate-500 sm:col-span-2">{r.explanation}</p>}
              </div>
            )}
          </TD>
        </TR>
      ))}
    </Table>
  );
}

function OutputBox({ label, text }: { label: string; text: string | null }) {
  return (
    <div className="min-w-0 rounded-[var(--r-md)] border border-edge/70 p-2">
      <div className="micro">{label}</div>
      <pre className="mt-1 max-h-40 overflow-auto whitespace-pre-wrap break-words font-mono text-[11px] text-slate-300">{text || "—"}</pre>
    </div>
  );
}
