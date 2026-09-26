import { useEffect, useMemo, useState } from "react";
import { BASE, portal } from "../api";
import { Pill } from "../system/hub";
import { Input, Labelled, Select, Table, TD, TH, TR } from "../system/controls";
import { BarChart, ChartFrame, Histogram, type Datum } from "../system/charts";
import { dateTimeOf } from "../system/time";
import { CopyButton } from "./ui";

/**
 * The endpoints your program calls, callable from here.
 *
 * <p>These authenticate with a Continuum API key rather than the console
 * session — which is why none of them had a screen. The key is typed or taken
 * from the one just issued, held in this tab's memory only, and never stored.
 */

type Kind = "json" | "file" | "none";
type Endpoint = { id: string; label: string; method: "GET" | "POST"; path: (p: string) => string; kind: Kind; key: boolean; body?: string };

const ENDPOINTS: Endpoint[] = [
  {
    id: "openai",
    label: "Chat · OpenAI-compatible",
    method: "POST",
    path: () => "/v1/chat/completions",
    kind: "json",
    key: true,
    body: JSON.stringify({ model: "auto", messages: [{ role: "user", content: "Say hello in five words." }] }, null, 2),
  },
  {
    id: "chat",
    label: "Chat · gateway",
    method: "POST",
    path: () => "/api/gateway/chat",
    kind: "json",
    key: true,
    body: JSON.stringify({ model: "auto", messages: [{ role: "user", content: "Name three prime numbers." }] }, null, 2),
  },
  { id: "models", label: "Models you can ask for", method: "GET", path: () => "/v1/models", kind: "none", key: false },
  {
    id: "pipeline",
    label: "Run a pipeline",
    method: "POST",
    path: (p) => `/api/gateway/pipeline/${encodeURIComponent(p || "my-pipeline")}`,
    kind: "json",
    key: true,
    body: JSON.stringify({ prompt: "Summarise this.", input: { text: "Continuum routes, verifies and records every request." } }, null, 2),
  },
  {
    id: "pipeline-upload",
    label: "Run a pipeline on a file",
    method: "POST",
    path: (p) => `/api/gateway/pipeline/${encodeURIComponent(p || "my-pipeline")}/upload`,
    kind: "file",
    key: true,
  },
  { id: "transform", label: "Transform a document for a model", method: "POST", path: () => "/api/gateway/context/transform", kind: "file", key: true },
];

export default function ApiExplorer({ issuedKey }: { issuedKey?: string }) {
  const [key, setKey] = useState(issuedKey ?? "");
  useEffect(() => {
    if (issuedKey) setKey(issuedKey);
  }, [issuedKey]);
  const [ep, setEp] = useState<Endpoint>(ENDPOINTS[0]);
  const [body, setBody] = useState(ENDPOINTS[0].body ?? "");
  const [pipelines, setPipelines] = useState<string[]>([]);
  const [pipeline, setPipeline] = useState("");
  const [file, setFile] = useState<File | null>(null);
  const [prompt, setPrompt] = useState("");
  const [budget, setBudget] = useState("standard");
  const [busy, setBusy] = useState(false);
  const [out, setOut] = useState<{ status: number; ms: number; body: string } | null>(null);

  useEffect(() => {
    portal.pipelines
      .list()
      .then((ps) => {
        const names = ps.map((p: any) => p.name).filter(Boolean);
        setPipelines(names);
        if (names[0]) setPipeline(names[0]);
      })
      .catch(() => {});
  }, []);

  const pick = (id: string) => {
    const next = ENDPOINTS.find((e) => e.id === id)!;
    setEp(next);
    setBody(next.body ?? "");
    setOut(null);
  };

  const path = ep.path(pipeline);
  let bodyErr: string | null = null;
  if (ep.kind === "json") {
    try {
      JSON.parse(body);
    } catch {
      bodyErr = "not valid JSON";
    }
  }
  const needsKey = ep.key && !key.trim();
  const needsFile = ep.kind === "file" && !file;
  const needsPipeline = ep.id.startsWith("pipeline") && !pipeline;

  const send = async () => {
    setBusy(true);
    const t0 = performance.now();
    try {
      const headers: Record<string, string> = {};
      if (ep.key) headers.Authorization = `Bearer ${key.trim()}`;
      let payload: BodyInit | undefined;
      let url = `${BASE}${path}`;
      if (ep.kind === "json") {
        headers["Content-Type"] = "application/json";
        payload = body;
      } else if (ep.kind === "file" && file) {
        const form = new FormData();
        form.append("file", file);
        if (ep.id === "transform") url += `?budget=${encodeURIComponent(budget)}`;
        else if (prompt.trim()) url += `?prompt=${encodeURIComponent(prompt.trim())}`;
        payload = form;
      }
      const res = await fetch(url, { method: ep.method, headers, body: payload });
      const text = await res.text();
      setOut({ status: res.status, ms: Math.round(performance.now() - t0), body: pretty(text) });
    } catch (e: any) {
      setOut({ status: 0, ms: Math.round(performance.now() - t0), body: e?.message ?? "network error" });
    } finally {
      setBusy(false);
    }
  };

  const curl = useMemo(() => {
    const origin = typeof window !== "undefined" ? window.location.origin : "";
    const auth = ep.key ? ` \\\n  -H "Authorization: Bearer $CONTINUUM_API_KEY"` : "";
    if (ep.kind === "json")
      return `curl -X ${ep.method} ${origin}${path}${auth} \\\n  -H "Content-Type: application/json" \\\n  -d '${compact(body)}'`;
    if (ep.kind === "file") {
      const q = ep.id === "transform" ? `?budget=${budget}` : prompt.trim() ? `?prompt=${encodeURIComponent(prompt.trim())}` : "";
      return `curl -X POST "${origin}${path}${q}"${auth} \\\n  -F "file=@${file?.name ?? "document.pdf"}"`;
    }
    return `curl ${origin}${path}`;
  }, [ep, path, body, budget, prompt, file]);

  const ok = out && out.status >= 200 && out.status < 300;
  return (
    <section className="plane space-y-4 p-5">
      <div>
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">API explorer</h2>
        <p className="mt-0.5 text-[12px] text-slate-500">Call the endpoints your app uses, with one of your keys. The key stays in this tab and is never saved.</p>
      </div>
      <div className="grid gap-3 md:grid-cols-[minmax(0,1fr)_minmax(0,1.2fr)]">
        <Labelled label="Endpoint">
          <Select value={ep.id} onChange={(e) => pick(e.target.value)}>
            {ENDPOINTS.map((e) => (
              <option key={e.id} value={e.id}>
                {e.method} · {e.label}
              </option>
            ))}
          </Select>
        </Labelled>
        <Labelled label={ep.key ? "Continuum API key" : "Continuum API key (not needed)"}>
          <Input type="password" autoComplete="off" spellCheck={false} placeholder="cnt_live_…" value={key} onChange={(e) => setKey(e.target.value)} disabled={!ep.key} />
        </Labelled>
      </div>
      <div className="flex flex-wrap items-center gap-2 font-mono text-[12px]">
        <Pill tone={ep.method === "GET" ? "info" : "violet"}>{ep.method}</Pill>
        <span className="min-w-0 break-all text-slate-300">{path}</span>
      </div>

      {ep.id.startsWith("pipeline") && (
        <Labelled label="Pipeline">
          {pipelines.length ? (
            <Select value={pipeline} onChange={(e) => setPipeline(e.target.value)}>
              {pipelines.map((n) => (
                <option key={n}>{n}</option>
              ))}
            </Select>
          ) : (
            <Input value={pipeline} onChange={(e) => setPipeline(e.target.value)} placeholder="pipeline name — create one under Pipelines" />
          )}
        </Labelled>
      )}

      {ep.kind === "json" && (
        <Labelled label={bodyErr ? `Body — ${bodyErr}` : "Body"}>
          <textarea value={body} onChange={(e) => setBody(e.target.value)} rows={7} spellCheck={false} className="w-full font-mono text-[12px] field" />
        </Labelled>
      )}
      {ep.kind === "file" && (
        <div className="grid gap-3 md:grid-cols-2">
          <Labelled label="File">
            <input type="file" onChange={(e) => setFile(e.target.files?.[0] ?? null)} className="block w-full text-[12.5px] text-slate-300 file:mr-3 file:rounded-full file:border-0 file:bg-[color:var(--wash-mute)] file:px-3 file:py-1.5 file:text-[12px] file:font-medium file:text-[color:rgb(var(--topo-text))]" />
          </Labelled>
          {ep.id === "transform" ? (
            <Labelled label="How much to keep">
              <Select value={budget} onChange={(e) => setBudget(e.target.value)}>
                <option value="summary">summary</option>
                <option value="standard">standard</option>
                <option value="full">full</option>
              </Select>
            </Labelled>
          ) : (
            <Labelled label="Prompt (optional)">
              <Input value={prompt} onChange={(e) => setPrompt(e.target.value)} placeholder="What to do with the file" />
            </Labelled>
          )}
        </div>
      )}

      <div className="flex flex-wrap items-center justify-end gap-2">
        {needsKey && <span className="text-[12px] text-slate-500">Paste a key, or issue one above</span>}
        <button
          onClick={send}
          disabled={busy || !!bodyErr || needsKey || needsFile || needsPipeline}
          className="rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-4 py-2 text-[13px] font-medium text-white hover:opacity-90 disabled:opacity-50"
        >
          {busy ? "Sending…" : "Send request"}
        </button>
      </div>

      {out && (
        <div className="rise-in space-y-2">
          <div className="flex flex-wrap items-center gap-2">
            <Pill tone={ok ? "ok" : "bad"} dot>
              {out.status || "no response"}
            </Pill>
            <span className="readout text-[12px] text-slate-400">{out.ms} ms</span>
          </div>
          <pre className="max-h-80 overflow-auto rounded-[var(--r-md)] border border-edge/70 p-3 font-mono text-[11.5px] leading-relaxed text-slate-300">{out.body}</pre>
        </div>
      )}
      <details className="rounded-[var(--r-md)] border border-edge/60 px-3 py-2">
        <summary className="cursor-pointer select-none text-[12.5px] text-slate-400">The same call, from a terminal</summary>
        <div className="mt-2 flex items-start gap-2">
          <pre className="min-w-0 flex-1 overflow-auto font-mono text-[11.5px] text-slate-300">{curl}</pre>
          <CopyButton text={curl} />
        </div>
      </details>
    </section>
  );
}

/** Your recent gateway requests, as your key saw them. */
export function RequestLog() {
  const [rows, setRows] = useState<any[] | null>(null);
  useEffect(() => {
    portal.requests(100).then(setRows).catch(() => setRows([]));
  }, []);
  const byModel: Datum[] = useMemo(() => {
    const m = new Map<string, number>();
    (rows ?? []).forEach((r) => m.set(`${r.chosenProvider}/${r.chosenModel}`, (m.get(`${r.chosenProvider}/${r.chosenModel}`) ?? 0) + 1));
    return [...m.entries()].sort((a, b) => b[1] - a[1]).map(([k, v]) => ({ key: k, label: k, value: v }));
  }, [rows]);
  // Eight equal bins from zero to the slowest request.
  const { latency, latencyEnd } = useMemo(() => {
    const vals = (rows ?? []).map((r) => Number(r.latencyMs ?? 0));
    const top = Math.max(1, ...vals);
    const step = Math.max(1, Math.ceil(top / 8));
    const bins = Array.from({ length: 8 }, (_, i) => ({ label: String(i * step), value: 0 }));
    vals.forEach((v) => bins[Math.min(7, Math.floor(v / step))].value++);
    return { latency: bins, latencyEnd: String(step * 8) };
  }, [rows]);
  if (!rows || rows.length === 0) return null;
  const failed = rows.filter((r) => !r.success).length;
  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Your recent requests</h2>
        <Pill tone={failed ? "warn" : "ok"} dot>
          {failed ? `${failed} of ${rows.length} failed` : `all ${rows.length} succeeded`}
        </Pill>
      </div>
      <div className="grid items-start gap-4 lg:grid-cols-2">
        <ChartFrame title="Latency" data={latency.map((b) => ({ key: b.label, label: `${b.label} ms`, value: b.value }))} valueLabel="Requests">
          <Histogram bins={latency} xLabel="ms" endLabel={latencyEnd} />
        </ChartFrame>
        <ChartFrame title="Answered by" data={byModel} valueLabel="Requests">
          <BarChart data={byModel} categorical />
        </ChartFrame>
      </div>
      <Table
        minWidth={680}
        maxHeight={360}
        head={
          <tr>
            <TH>When</TH>
            <TH>Answered by</TH>
            <TH>Why</TH>
            <TH align="right">Latency</TH>
            <TH align="right">Tokens</TH>
            <TH align="right">Cost</TH>
          </tr>
        }
      >
        {rows.map((r) => (
          <TR key={r.id}>
            <TD>
              <span className="whitespace-nowrap text-slate-400">{dateTimeOf(r.createdAt)}</span>
            </TD>
            <TD>
              <span className="inline-flex items-center gap-1.5">
                <span className="h-1.5 w-1.5 rounded-full" style={{ background: r.success ? "var(--state-healthy-ink)" : "var(--state-critical-ink)" }} aria-hidden />
                {r.chosenProvider}/{r.chosenModel}
                {r.failoverCount > 0 && <Pill tone="warn">{r.failoverCount} failover</Pill>}
              </span>
            </TD>
            <TD>
              <span className="block max-w-[320px] truncate text-[12px] text-slate-500" title={r.routingReason}>
                {r.routingReason}
              </span>
            </TD>
            <TD numeric>{r.latencyMs} ms</TD>
            <TD numeric>{r.tokens}</TD>
            <TD numeric>${(r.costUsd ?? 0).toFixed(6)}</TD>
          </TR>
        ))}
      </Table>
    </section>
  );
}

function pretty(s: string) {
  try {
    return JSON.stringify(JSON.parse(s), null, 2);
  } catch {
    return s;
  }
}

function compact(s: string) {
  try {
    return JSON.stringify(JSON.parse(s));
  } catch {
    return s.replace(/'/g, "'\\''");
  }
}
