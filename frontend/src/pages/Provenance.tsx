import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { PageHeader, Readout, Switch } from "../system/primitives";
import { ErrorState, useToast } from "../components/ui";
import { Explain } from "../system/hub";

/**
 * Decision provenance.
 *
 * <p>Continuum already explains itself in a sentence on every response. This is
 * the same explanations as data — which is the difference between reading why
 * one answer happened and being able to ask why a thousand of them did.
 */

type Node = {
  seq: number;
  stage: string;
  choice: string;
  reason: string;
  alternatives: string | null;
  costDelta: number;
  latencyMs: number;
};

const STAGE_TONE: Record<string, string> = {
  ADMISSION: "text-amber-400",
  CACHE: "text-emerald-400",
  ROUTE: "text-aurora",
  PROVIDER: "text-indigo-400",
  CASCADE: "text-amber-400",
  QUALITY: "text-emerald-400",
  REPAIR: "text-amber-400",
  OUTPUT: "text-slate-300",
};

export default function Provenance() {
  const toast = useToast();
  const [status, setStatus] = useState<any | null>(null);
  const [requests, setRequests] = useState<string[]>([]);
  const [selected, setSelected] = useState<string | null>(null);
  const [graph, setGraph] = useState<any | null>(null);
  const [otel, setOtel] = useState<any | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      const [s, r] = await Promise.all([portal.provenance.status(), portal.provenance.requests(30)]);
      setStatus(s);
      setRequests(r);
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load provenance.");
    }
  }, []);

  useEffect(() => {
    void load();
    const t = setInterval(() => void load(), 5000);
    return () => clearInterval(t);
  }, [load]);

  useEffect(() => {
    if (!selected) {
      setGraph(null);
      setOtel(null);
      return;
    }
    portal.provenance.graph(selected).then(setGraph).catch(() => setGraph(null));
    setOtel(null);
  }, [selected]);

  if (error) return <ErrorState message={error} onRetry={load} />;

  const byStage: Record<string, number> = status?.byStage ?? {};
  const nodes: Node[] = graph?.decisions ?? [];

  return (
    <section className="space-y-8">
      <PageHeader
        glyph="list"
        tone="info"
        title="Decision Provenance"
        subtitle="Why Continuum did what it did — as data, not as a sentence."
      />

      <div className="plane grid grid-cols-2 gap-x-8 gap-y-5 p-4 sm:grid-cols-3 lg:grid-cols-4">
        <Readout label="Requests recorded" value={status?.requests ?? 0} size="sm" />
        <Readout label="Decisions" value={status?.decisions ?? 0} size="sm" />
        <Readout label="Stages seen" value={Object.keys(byStage).length} size="sm" />
        <Readout
          label="Recording"
          value={status?.enabled ? "on" : "off"}
          size="sm"
          state={status?.enabled ? "active" : "idle"}
        />
      </div>

      <div className="space-y-3">
        <Switch
          checked={!!status?.enabled}
          busy={busy}
          onChange={async (next) => {
            setBusy(true);
            try {
              await portal.provenance.configure({ enabled: next });
              toast(next ? "Recording decisions." : "Recording off.");
              await load();
            } catch (e: any) {
              toast(e?.message ?? "That did not work.", "error");
            } finally {
              setBusy(false);
            }
          }}
          label="Record decisions"
          hint="Off by default. Recording is cheap but not free, and a request path is the wrong place to add writes nobody asked for."
        />
        <p className="max-w-2xl text-xs leading-relaxed text-slate-600">
          One row per decision, so you can aggregate and alert on them.
        </p>
        <Explain title="Why not the routing reason">
          <p>
            Every response already carries one, like{" "}
            <span className="readout">mode=BALANCED · quality 0.55 (repair) · confidence 0.82</span>.
            That is fine for reading a single answer and useless for everything else — you cannot
            ask how often the cascade escalated last week, or what it cost.
          </p>
        </Explain>
      </div>

      <Degradation />

      <div className="grid gap-4 lg:grid-cols-[minmax(0,18rem)_1fr]">
        <div className="min-w-0">
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Recent requests</h2>
          {requests.length === 0 ? (
            <p className="mt-2 text-xs text-slate-500 max-w-2xl leading-relaxed">
              Nothing recorded yet. Turn recording on and send a request through the gateway.
            </p>
          ) : (
            <div className="mt-2 space-y-1">
              {requests.map((id) => (
                <button
                  key={id}
                  onClick={() => setSelected(id)}
                  className={`block w-full truncate rounded-md px-2 py-1.5 text-left font-mono text-[11px] transition-colors ${
                    selected === id
                      ? "bg-aurora/10 text-slate-200"
                      : "text-slate-500 hover:bg-edge/40 hover:text-slate-300"
                  }`}
                >
                  {id}
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="min-w-0">
          {!graph?.found ? (
            <p className="text-sm text-slate-500 max-w-2xl leading-relaxed">
              Pick a request to see every decision made about it, in order.
            </p>
          ) : (
            <div className="space-y-3">
              <div className="flex flex-wrap items-baseline gap-x-4 gap-y-1">
                <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Decision graph</h2>
                <span className="readout text-xs text-slate-500">
                  {nodes.length} decisions · {graph.totalLatencyMs}ms · $
                  {Number(graph.totalCost ?? 0).toFixed(6)}
                </span>
              </div>

              <div className="space-y-1.5">
                {nodes.map((n) => (
                  <div
                    key={n.seq}
                    className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded-md border border-edge/60 px-3 py-2"
                  >
                    <span className={`micro w-24 shrink-0 ${STAGE_TONE[n.stage] ?? "text-slate-500"}`}>
                      {n.stage}
                    </span>
                    <span className="shrink-0 text-sm text-slate-200">{n.choice}</span>
                    <span className="min-w-0 flex-1 truncate text-xs text-slate-500">{n.reason}</span>
                    {n.latencyMs > 0 && (
                      <span className="readout shrink-0 text-xs text-slate-500">{n.latencyMs}ms</span>
                    )}
                    {n.costDelta !== 0 && (
                      <span className="readout shrink-0 text-xs text-amber-400">
                        ${n.costDelta.toFixed(6)}
                      </span>
                    )}
                    {n.alternatives && (
                      <span className="w-full text-xs text-slate-600">
                        not chosen: {n.alternatives}
                      </span>
                    )}
                  </div>
                ))}
              </div>

              <button
                onClick={() =>
                  portal.provenance.otel(selected!).then(setOtel).catch(() => setOtel(null))
                }
                className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:border-aurora/50"
              >
                Show as OpenTelemetry spans
              </button>
              {otel && (
                <>
                  <pre className="well max-h-72 w-full max-w-full overflow-auto p-3 text-[11px] leading-relaxed text-slate-400">
                    {JSON.stringify(otel, null, 2)}
                  </pre>
                  <p className="text-xs text-slate-600 max-w-2xl leading-relaxed">
                    Uses the GenAI semantic conventions where they exist —{" "}
                    <span className="readout">gen_ai.request.model</span>,{" "}
                    <span className="readout">gen_ai.usage.cost</span> — so this groups correctly in
                    a dashboard that already exists. An observability feature only readable inside
                    the product it observes has solved the easy half of the problem.
                  </p>
                </>
              )}
            </div>
          )}
        </div>
      </div>
    </section>
  );
}

// --- Graceful degradation ---------------------------------------------------

/**
 * The ladder is on this page because it answers the same question provenance
 * does: what actually happened, and was it what was asked for.
 */
function Degradation() {
  const toast = useToast();
  const [status, setStatus] = useState<any | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async () => {
    try {
      setStatus(await portal.degradation.status());
    } catch {
      /* the page still works without this panel */
    }
  }, []);

  useEffect(() => {
    void load();
    const t = setInterval(() => void load(), 5000);
    return () => clearInterval(t);
  }, [load]);

  const byRung: Record<string, number> = status?.byRung ?? {};
  const recent: any[] = status?.recent ?? [];

  return (
    <div className="space-y-3">
      <Switch
        checked={!!status?.enabled}
        busy={busy}
        onChange={async (next) => {
          setBusy(true);
          try {
            await portal.degradation.configure({ enabled: next });
            toast(next ? "Degradation ladder on." : "Degradation ladder off.");
            await load();
          } catch (e: any) {
            toast(e?.message ?? "That did not work.", "error");
          } finally {
            setBusy(false);
          }
        }}
        label="Graceful degradation"
        hint="Off by default — when every model fails the gateway returns 502. With it on, it steps down: a previous answer to an equivalent question, or an honest message saying none could be produced."
      />

      <div className="flex flex-wrap gap-4">
        <Readout label="Descents" value={status?.events ?? 0} size="sm"
                 state={(status?.events ?? 0) > 0 ? "degraded" : "idle"} />
        <Readout label="Served from cache" value={byRung.CACHED ?? 0} size="sm" />
        <Readout label="Nothing to serve" value={byRung.STATIC ?? 0} size="sm" />
      </div>

      <p className="max-w-2xl text-xs leading-relaxed text-slate-600">
        Every degraded response names its rung: the model reads{" "}
        <span className="readout">degraded/cached</span> or{" "}
        <span className="readout">degraded/static</span>.
      </p>
      <Explain>
        <p>
          A degraded answer presented as a normal one is worse than an error — the caller cannot
          tell it should retry or warn its user. Silently succeeding is the failure mode this
          feature could most easily become.
        </p>
        <p>
          There is no "cheaper model" rung. The fallback chain already does that, several times,
          before this ladder is reached; adding it here would present ordinary work as degradation.
        </p>
      </Explain>

      {recent.length > 0 && (
        <div className="space-y-1">
          {recent.slice(0, 6).map((e, i) => (
            <div key={i} className="flex flex-wrap items-center gap-x-3 rounded-md border border-edge/60 px-3 py-1.5">
              <span className={`micro w-16 shrink-0 ${e.rung === "CACHED" ? "text-amber-400" : "text-rose-400"}`}>
                {e.rung}
              </span>
              <span className="min-w-0 flex-1 truncate text-xs text-slate-500">{e.reason}</span>
            </div>
          ))}
        </div>
      )}
    </div>
  );
}
