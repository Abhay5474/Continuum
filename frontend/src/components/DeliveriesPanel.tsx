import { useEffect, useMemo, useState } from "react";
import { Link } from "react-router-dom";
import { api, hasOperator } from "../api";
import { ChartFrame, Donut, type Datum } from "../system/charts";
import { Empty, Pill, Segmented } from "../system/hub";
import { Table, TD, TH, TR } from "../system/controls";
import { dateTimeOf } from "../system/time";
import { visibleInterval } from "../system/poll";

/**
 * Outbound deliveries: every message the outbox handed to a sink — the email,
 * payment and notification side effects of your runs — with how many times its
 * idempotency key was delivered. That number is the exactly-once guarantee made
 * visible: it should always read 1.
 */

type Delivery = {
  destination: string;
  idempotencyKey: string;
  payload: string;
  deliveryCount: number;
  deliveredAt: unknown;
  workflowId: string | null;
};

const DEST_COLOR: Record<string, string> = {
  email: "var(--series-1)",
  payment: "var(--series-3)",
  notification: "var(--series-5)",
};

export default function DeliveriesPanel() {
  // The operator may look across the engine; everyone else sees their own runs.
  const [scope, setScope] = useState<"mine" | "engine">("mine");
  const [rows, setRows] = useState<Delivery[] | null>(null);
  const [err, setErr] = useState<string | null>(null);
  const [open, setOpen] = useState<string | null>(null);

  useEffect(() => {
    const load = () =>
      (scope === "engine" ? api.opGet<Delivery[]>("/api/deliveries") : api.get<Delivery[]>("/api/deliveries"))
        .then((r) => {
          setRows(r);
          setErr(null);
        })
        .catch((e) => {
          setRows([]);
          setErr(e?.message ?? "request failed");
        });
    load();
    return visibleInterval(load, 6000);
  }, [scope]);

  const byDest: Datum[] = useMemo(() => {
    const m = new Map<string, number>();
    (rows ?? []).forEach((d) => m.set(d.destination, (m.get(d.destination) ?? 0) + 1));
    return [...m.entries()].map(([k, v]) => ({ key: k, label: k, value: v, color: DEST_COLOR[k] }));
  }, [rows]);
  const dups = (rows ?? []).filter((d) => d.deliveryCount > 1);

  return (
    <section className="card rounded-lg border border-edge bg-panel">
      <div className="flex flex-wrap items-center gap-3 border-b border-edge px-4 py-3">
        <span className="font-medium">Outbound deliveries</span>
        {rows && rows.length > 0 && (
          <Pill tone={dups.length ? "bad" : "ok"} dot>
            {dups.length ? `${dups.length} delivered more than once` : "every key delivered exactly once"}
          </Pill>
        )}
        {hasOperator() && (
          <span className="ml-auto">
            <Segmented
              value={scope}
              onChange={setScope}
              options={[
                { value: "mine", label: "My runs" },
                { value: "engine", label: "Whole engine" },
              ]}
            />
          </span>
        )}
      </div>
      <div className="p-4">
        {err && <p className="mb-3 text-[12px]" style={{ color: "var(--state-critical-ink)" }}>{err}</p>}
        {rows === null ? (
          <p className="text-sm text-slate-500">Loading…</p>
        ) : rows.length === 0 ? (
          <Empty
            title="No deliveries yet"
            glyph="flow"
            hint="Runs that send an email, take a payment or post a notification hand it to the outbox, which delivers it once and records it here. Start a CustomerAnalysis run to see one."
          />
        ) : (
          <div className="grid items-start gap-4 lg:grid-cols-[260px_minmax(0,1fr)]">
            <ChartFrame title="By destination" data={byDest} valueLabel="Deliveries">
              <Donut data={byDest} size={112} thickness={12} centerValue={String(rows.length)} centerLabel="recent" />
            </ChartFrame>
            <Table
              minWidth={620}
              maxHeight={420}
              head={
                <tr>
                  <TH>When</TH>
                  <TH>Destination</TH>
                  <TH>Run</TH>
                  <TH>Idempotency key</TH>
                  <TH align="right">Times delivered</TH>
                </tr>
              }
            >
              {rows.slice(0, 200).map((d, i) => {
                const id = `${d.idempotencyKey}#${i}`;
                return (
                  <TR key={id}>
                    <TD>
                      <span className="whitespace-nowrap text-slate-400">{dateTimeOf(d.deliveredAt)}</span>
                    </TD>
                    <TD>
                      <span className="inline-flex items-center gap-1.5">
                        <span className="h-2 w-2 rounded-full" style={{ background: DEST_COLOR[d.destination] ?? "var(--series-mute)" }} aria-hidden />
                        {d.destination}
                      </span>
                    </TD>
                    <TD>
                      {d.workflowId ? (
                        <Link to={`/workflows/${d.workflowId}`} className="font-mono text-[11.5px] text-slate-300 underline-offset-2 hover:underline">
                          {d.workflowId.slice(0, 8)}
                        </Link>
                      ) : (
                        "—"
                      )}
                    </TD>
                    <TD>
                      <button onClick={() => setOpen(open === id ? null : id)} aria-expanded={open === id} className="max-w-[220px] truncate text-left font-mono text-[11.5px] text-slate-400 hover:text-slate-200" title="Show the payload">
                        {d.idempotencyKey}
                      </button>
                      {open === id && (
                        <pre className="rise-in mt-1 max-h-40 max-w-[420px] overflow-auto whitespace-pre-wrap break-words rounded-[var(--r-md)] border border-edge/70 p-2 font-mono text-[11px] text-slate-300">{pretty(d.payload)}</pre>
                      )}
                    </TD>
                    <TD numeric>
                      <span style={{ color: d.deliveryCount > 1 ? "var(--state-critical-ink)" : "var(--state-healthy-ink)" }}>× {d.deliveryCount}</span>
                    </TD>
                  </TR>
                );
              })}
            </Table>
          </div>
        )}
      </div>
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
