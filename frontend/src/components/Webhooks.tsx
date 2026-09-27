import { useEffect, useState } from "react";
import { portal } from "../api";
import { InfoTip, Switch } from "../system/primitives";
import { Pill } from "../system/hub";
import { Table, TD, TH, TR } from "../system/controls";
import { CodeBlock, CopyButton, useToast } from "./ui";
import { dateTimeOf } from "../system/time";

const LABEL: Record<string, string> = {
  "workflow.completed": "completed",
  "workflow.failed": "failed",
  "workflow.cancelled": "cancelled",
  "workflow.stuck": "stuck",
};

const VERIFY = `// Node: check a delivery before trusting it
import crypto from "node:crypto";

function verify(rawBody, header, secret) {
  const { t, v1 } = Object.fromEntries(header.split(",").map((p) => p.split("=")));
  if (Math.abs(Date.now() / 1000 - Number(t)) > 300) return false; // too old
  const expected = crypto.createHmac("sha256", secret).update(\`\${t}.\${rawBody}\`).digest("hex");
  return crypto.timingSafeEqual(Buffer.from(v1), Buffer.from(expected));
}
// Then drop a repeat: remember each Idempotency-Key you have handled.`;

/**
 * The account's webhook endpoints. A run's outcome is queued in the same
 * transaction as the outcome itself and delivered once per endpoint, signed,
 * with an Idempotency-Key that stays the same on every retry.
 */
export default function Webhooks() {
  const toast = useToast();
  const [list, setList] = useState<any[] | null>(null);
  const [events, setEvents] = useState<string[]>([]);
  const [url, setUrl] = useState("");
  const [picked, setPicked] = useState<string[]>([]);
  const [busy, setBusy] = useState(false);
  const [secret, setSecret] = useState<{ id: number; value: string } | null>(null);
  const [open, setOpen] = useState<number | null>(null);

  const load = () =>
    portal.webhooks
      .list()
      .then((r) => {
        setList(r.endpoints);
        setEvents(r.events);
        setPicked((p) => (p.length ? p : r.events));
      })
      .catch(() => setList([]));
  useEffect(() => {
    load();
  }, []);

  const failed = (e: any, what: string) =>
    toast(`${what}: ${e?.body?.message ?? e?.body?.error ?? e?.message ?? "request failed"}`, "error");

  const add = async () => {
    setBusy(true);
    try {
      const r = await portal.webhooks.create(url.trim(), picked);
      setSecret({ id: r.endpoint.id, value: r.secret });
      setUrl("");
      await load();
    } catch (e) {
      failed(e, "Endpoint not added");
    } finally {
      setBusy(false);
    }
  };

  const act = async (what: string, fn: () => Promise<unknown>, ok?: string) => {
    try {
      await fn();
      if (ok) toast(ok, "success");
      await load();
    } catch (e) {
      failed(e, what);
    }
  };

  const toggle = (ev: string) => setPicked((p) => (p.includes(ev) ? p.filter((x) => x !== ev) : [...p, ev]));
  const validUrl = /^https?:\/\/[^\s]+$/i.test(url.trim());

  return (
    <div>
      <h2 className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight text-slate-200">
        Webhooks
        <InfoTip text="Your server is told when a run finishes, fails, is cancelled or gets stuck. Each event is sent once per endpoint, signed with the endpoint's secret, with an Idempotency-Key that stays the same if it has to be retried." />
      </h2>

      <div className="plane mt-3 space-y-3 p-4">
        <div className="flex flex-wrap items-center gap-2">
          <input
            aria-label="Endpoint URL"
            value={url}
            onChange={(e) => setUrl(e.target.value)}
            placeholder="https://example.com/continuum-events"
            className="field min-w-0 flex-1"
          />
          <button
            onClick={add}
            disabled={busy || !validUrl || picked.length === 0}
            className="rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-3.5 py-1.5 text-[12.5px] font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {busy ? "Adding…" : "Add endpoint"}
          </button>
        </div>
        <fieldset className="flex flex-wrap items-center gap-x-4 gap-y-1 text-[12.5px] text-slate-300">
          <legend className="sr-only">Events to send</legend>
          <span className="text-slate-500">Send when a run is</span>
          {events.map((ev) => (
            <label key={ev} className="inline-flex items-center gap-1.5">
              <input type="checkbox" checked={picked.includes(ev)} onChange={() => toggle(ev)} />
              {LABEL[ev] ?? ev}
            </label>
          ))}
        </fieldset>

        {secret && (
          <div className="rounded-[var(--r-md)] border border-emerald-500/30 bg-emerald-500/[0.06] p-3" role="status">
            <div className="text-[12.5px] font-medium text-emerald-300">Signing secret — shown once</div>
            <div className="mt-1 flex items-center gap-2">
              <code className="min-w-0 flex-1 break-all font-mono text-xs text-slate-200">{secret.value}</code>
              <CopyButton text={secret.value} />
            </div>
          </div>
        )}
      </div>

      <div className="mt-3 space-y-2">
        {list === null ? (
          <p className="text-sm text-slate-500">Loading…</p>
        ) : list.length === 0 ? (
          <p className="text-xs text-slate-500">No endpoints yet. Add one above to hear about runs as they finish.</p>
        ) : (
          list.map((w) => (
            <div key={w.id} className="plane overflow-hidden">
              <div className="flex flex-wrap items-center gap-x-3 gap-y-2 px-4 py-3">
                <span className="min-w-0 flex-1 truncate font-mono text-[12.5px] text-slate-200" title={w.url}>{w.url}</span>
                <span className="flex flex-wrap gap-1">
                  {w.events.map((ev: string) => (
                    <Pill key={ev}>{LABEL[ev] ?? ev}</Pill>
                  ))}
                </span>
                <LastDelivery d={w.lastDelivery} />
              </div>
              <div className="flex flex-wrap items-center gap-2 border-t border-edge/50 px-4 py-2 text-xs">
                <Switch
                  checked={w.enabled}
                  onChange={(next) => act("Not changed", () => portal.webhooks.update(w.id, { enabled: next }))}
                  label={w.enabled ? "On" : "Off"}
                />
                <button onClick={() => act("Test not sent", () => portal.webhooks.test(w.id), "Test event queued")}
                  className="ml-auto rounded border border-edge px-2 py-1 hover:bg-edge/40">Send test</button>
                <button
                  onClick={() => act("Secret not changed", async () => {
                    if (!window.confirm("Replace the signing secret? The old one stops working at once.")) return;
                    const r = await portal.webhooks.rotate(w.id);
                    setSecret({ id: w.id, value: r.secret });
                  })}
                  className="rounded border border-edge px-2 py-1 hover:bg-edge/40">New secret</button>
                <button onClick={() => setOpen(open === w.id ? null : w.id)} aria-expanded={open === w.id}
                  className="rounded border border-edge px-2 py-1 hover:bg-edge/40">Deliveries</button>
                <button
                  onClick={() => act("Not removed", async () => {
                    if (!window.confirm(`Stop sending events to ${w.url}?`)) return;
                    await portal.webhooks.remove(w.id);
                  }, "Endpoint removed")}
                  className="rounded border border-edge px-2 py-1 text-rose-300 hover:bg-rose-500/10">Remove</button>
              </div>
              {open === w.id && <Deliveries id={w.id} />}
            </div>
          ))
        )}
      </div>

      <details className="mt-3">
        <summary className="cursor-pointer text-xs text-slate-400 hover:text-slate-200">How to verify a delivery</summary>
        <p className="mt-2 text-[12px] text-slate-500">
          Every request carries <span className="font-mono">Continuum-Signature: t=…,v1=…</span> — an HMAC-SHA256 of{" "}
          <span className="font-mono">"t.body"</span> with the endpoint's secret — and an{" "}
          <span className="font-mono">Idempotency-Key</span> that is the same on every retry of the same event.
        </p>
        <CodeBlock code={VERIFY} language="javascript" className="mt-2" />
      </details>
    </div>
  );
}

function LastDelivery({ d }: { d: any }) {
  if (!d) return <span className="text-[11.5px] text-slate-500">nothing sent yet</span>;
  const ok = d.statusCode != null && d.statusCode >= 200 && d.statusCode < 300;
  return (
    <span className={`text-[11.5px] ${ok ? "text-emerald-300" : "text-rose-300"}`} title={d.error ?? undefined}>
      {d.event} · {d.statusCode ?? "no answer"} · {dateTimeOf(d.attemptedAt)}
    </span>
  );
}

function Deliveries({ id }: { id: number }) {
  const [rows, setRows] = useState<any[] | null>(null);
  useEffect(() => {
    let alive = true;
    const load = () => portal.webhooks.deliveries(id).then((r) => alive && setRows(r)).catch(() => alive && setRows([]));
    load();
    const t = setInterval(load, 4000);
    return () => {
      alive = false;
      clearInterval(t);
    };
  }, [id]);
  if (rows === null) return <p className="px-4 pb-3 text-xs text-slate-500">Loading…</p>;
  if (rows.length === 0) return <p className="px-4 pb-3 text-xs text-slate-500">No deliveries yet.</p>;
  return (
    <div className="px-4 pb-3">
      <Table
        label="Recent deliveries"
        head={
          <tr>
            <TH>When</TH>
            <TH>Event</TH>
            <TH>Try</TH>
            <TH>Answer</TH>
            <TH>Time</TH>
          </tr>
        }
      >
        {rows.map((r) => (
          <TR key={r.id}>
            <TD>{dateTimeOf(r.attemptedAt)}</TD>
            <TD>{r.event}</TD>
            <TD>{r.attempt}</TD>
            <TD>
              <span className={r.statusCode >= 200 && r.statusCode < 300 ? "text-emerald-300" : "text-rose-300"} title={r.error ?? undefined}>
                {r.statusCode ?? r.error ?? "no answer"}
              </span>
            </TD>
            <TD>{r.durationMs} ms</TD>
          </TR>
        ))}
      </Table>
    </div>
  );
}
