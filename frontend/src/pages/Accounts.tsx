import { useEffect, useMemo, useState } from "react";
import { api, portal } from "../api";
import { Chip, Empty, Pill } from "../system/hub";
import { useOperator } from "../system/OperatorAccess";
import { BarChart, ChartFrame, type Datum } from "../system/charts";
import { Input, Labelled, Select, Table, TD, TH, TR } from "../system/controls";
import { CopyButton, useToast } from "../components/ui";
import { dateOf, dateTimeOf, toMillis } from "../system/time";

/**
 * Accounts — the operator's view of every account on the deployment.
 *
 * <p>The admin API (create an account, issue or revoke its keys, store its
 * provider credentials, put it on a plan) was reachable only with curl and a
 * shared token. It is the operator's, so this page is too: it asks for operator
 * access rather than showing a developer someone else's accounts.
 */

type Account = { id: string; name: string; email: string; createdAt: unknown };
type Key = { id: number; prefix: string; active: boolean; createdAt: string };
type Cred = { provider: string; createdAt?: unknown; updatedAt?: unknown };

const PROVIDERS = ["gemini", "groq", "openai", "anthropic", "mistral"];

export default function Accounts() {
  const { operator, request: unlock } = useOperator();
  const toast = useToast();
  const [rows, setRows] = useState<Account[] | null>(null);
  const [q, setQ] = useState("");
  const [open, setOpen] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [busy, setBusy] = useState(false);

  const load = () =>
    api
      .opGet<Account[]>("/api/admin/developers")
      .then(setRows)
      .catch(() => setRows([]));
  useEffect(() => {
    if (operator) load();
  }, [operator]);

  const perWeek: Datum[] = useMemo(() => {
    const m = new Map<string, number>();
    (rows ?? []).forEach((r) => {
      const t = toMillis(r.createdAt);
      if (t == null) return;
      const d = new Date(t);
      d.setHours(0, 0, 0, 0);
      d.setDate(d.getDate() - d.getDay());
      const k = d.toISOString().slice(0, 10);
      m.set(k, (m.get(k) ?? 0) + 1);
    });
    return [...m.entries()].sort().slice(-10).map(([k, v]) => ({ key: k, label: `week of ${dateOf(k)}`, value: v }));
  }, [rows]);

  const shown = (rows ?? []).filter((r) => !q.trim() || `${r.name} ${r.email} ${r.id}`.toLowerCase().includes(q.trim().toLowerCase()));

  const create = async () => {
    setBusy(true);
    try {
      const r = await api.opPost<Account>("/api/admin/developers", { name, email });
      toast(`Account created for ${r.email}`, "success");
      setName("");
      setEmail("");
      await load();
      setOpen(r.id);
    } catch (e: any) {
      toast(readError(e) ?? "Account was not created", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-8">
      <header>
        <div className="flex items-center gap-2.5">
          <Chip glyph="user" tone="accent" size={28} />
          <h1 className="text-[20px] font-semibold tracking-[-0.011em]">Accounts</h1>
          <Pill tone="warn">operator</Pill>
        </div>
        <p className="mt-1 text-[13px] text-slate-500">Every account on this deployment · plans, keys and provider credentials</p>
      </header>

      {!operator ? (
        <div className="plane flex flex-wrap items-center gap-3 px-5 py-4">
          <span className="min-w-0 flex-1 text-sm text-slate-400">This page lists every account on the deployment, so it needs operator access.</span>
          <button onClick={unlock} className="rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-3.5 py-1.5 text-[12.5px] font-medium text-white hover:opacity-90">
            Unlock operator access
          </button>
        </div>
      ) : (
        <>
          <div className="grid items-start gap-4 lg:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
            <section className="plane space-y-3 p-5">
              <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">New account</h2>
              <p className="text-[12px] text-slate-500">For a program or a customer onboarded by hand. It can be given keys and a plan straight away; the person can set a password later by signing up with the same address.</p>
              <div className="grid gap-3 sm:grid-cols-2">
                <Labelled label="Name">
                  <Input value={name} onChange={(e) => setName(e.target.value)} placeholder="Acme Corp" />
                </Labelled>
                <Labelled label="Email">
                  <Input type="email" value={email} onChange={(e) => setEmail(e.target.value)} placeholder="ops@acme.example" />
                </Labelled>
              </div>
              <div className="flex justify-end">
                <button
                  onClick={create}
                  disabled={busy || !name.trim() || !/^[^@\s]+@[^@\s]+\.[^@\s]+$/.test(email.trim())}
                  className="rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-3.5 py-1.5 text-[12.5px] font-medium text-white hover:opacity-90 disabled:opacity-50"
                >
                  {busy ? "Creating…" : "Create account"}
                </button>
              </div>
            </section>
            <ChartFrame title={`${rows?.length ?? "—"} accounts · new each week`} data={perWeek} valueLabel="Accounts">
              <BarChart data={perWeek} />
            </ChartFrame>
          </div>

          <section className="space-y-3">
            <div className="flex flex-wrap items-center gap-3">
              <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">All accounts</h2>
              <span className="ml-auto w-full sm:w-72">
                <Input aria-label="Filter accounts" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter by name, email or id" />
              </span>
            </div>
            {rows === null ? (
              <p className="text-sm text-slate-500">Loading…</p>
            ) : shown.length === 0 ? (
              <Empty title="No accounts match" glyph="user" />
            ) : (
              <div className="space-y-2">
                {shown.map((r) => (
                  <AccountRow key={r.id} account={r} open={open === r.id} onToggle={() => setOpen(open === r.id ? null : r.id)} />
                ))}
              </div>
            )}
          </section>
        </>
      )}
    </div>
  );
}

function AccountRow({ account, open, onToggle }: { account: Account; open: boolean; onToggle: () => void }) {
  return (
    <div className="plane overflow-hidden">
      <button onClick={onToggle} aria-expanded={open} className="flex w-full flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3 text-left">
        <span className="grid h-8 w-8 shrink-0 place-items-center rounded-full text-[12px] font-bold" style={{ background: "var(--wash-mute)", color: "var(--text-2)" }} aria-hidden>
          {(account.name || account.email || "?").slice(0, 1).toUpperCase()}
        </span>
        <span className="min-w-0 flex-1">
          <span className="block truncate text-sm font-medium text-slate-200">{account.name || "—"}</span>
          <span className="block truncate text-[12px] text-slate-500">{account.email}</span>
        </span>
        <span className="hidden font-mono text-[11px] text-slate-500 md:inline">{account.id}</span>
        <span className="text-[12px] text-slate-500">since {dateOf(account.createdAt)}</span>
      </button>
      {open && <AccountDetail id={account.id} />}
    </div>
  );
}

function AccountDetail({ id }: { id: string }) {
  const toast = useToast();
  const [keys, setKeys] = useState<Key[] | null>(null);
  const [creds, setCreds] = useState<Cred[] | null>(null);
  const [plans, setPlans] = useState<{ id: string; monthlyPriceUsd: number }[]>([]);
  const [plan, setPlan] = useState("");
  const [issued, setIssued] = useState<string | null>(null);
  const [provider, setProvider] = useState(PROVIDERS[0]);
  const [secret, setSecret] = useState("");
  const [busy, setBusy] = useState<string | null>(null);

  const base = `/api/admin/developers/${encodeURIComponent(id)}`;
  const loadKeys = () => api.opGet<Key[]>(`${base}/keys`).then(setKeys).catch(() => setKeys([]));
  const loadCreds = () => api.opGet<Cred[]>(`${base}/credentials`).then(setCreds).catch(() => setCreds([]));
  useEffect(() => {
    loadKeys();
    loadCreds();
    // The plan catalogue is the same one the billing page offers.
    portal
      .billing()
      .then((b) => {
        setPlans(b.plans ?? []);
        setPlan((b.plans ?? [])[0]?.id ?? "");
      })
      .catch(() => {});
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [id]);

  const run = async (label: string, fn: () => Promise<unknown>, done: string) => {
    setBusy(label);
    try {
      await fn();
      toast(done, "success");
    } catch (e: any) {
      toast(readError(e) ?? `${label} failed`, "error");
    } finally {
      setBusy(null);
    }
  };

  return (
    <div className="rise-in grid gap-5 border-t border-edge/60 px-4 py-4 lg:grid-cols-3">
      <div className="space-y-2">
        <h3 className="micro">Plan</h3>
        <div className="flex gap-2">
          <Select aria-label="Plan" value={plan} onChange={(e) => setPlan(e.target.value)}>
            {plans.map((p) => (
              <option key={p.id} value={p.id}>
                {p.id} · ${p.monthlyPriceUsd}/mo
              </option>
            ))}
          </Select>
          <button
            onClick={() => run("Plan", () => api.opPost(`${base}/plan`, { plan }), `Put on ${plan}, without payment`)}
            disabled={!plan || busy === "Plan"}
            className="shrink-0 rounded-[var(--r-md)] border border-edge px-3 text-[12.5px] text-slate-200 hover:border-slate-500/60 disabled:opacity-50"
          >
            Apply
          </button>
        </div>
        <p className="text-[11.5px] text-slate-500">Applied without a payment and recorded against you — for trials, invoices and enterprise deals.</p>
      </div>

      <div className="space-y-2">
        <div className="flex items-center">
          <h3 className="micro">API keys</h3>
          <button
            onClick={() =>
              run(
                "Issue",
                async () => {
                  const r = await api.opPost<{ apiKey: string }>(`${base}/keys`);
                  setIssued(r.apiKey);
                  await loadKeys();
                },
                "Key issued — shown once",
              )
            }
            disabled={busy === "Issue"}
            className="ml-auto rounded-full border border-edge px-2.5 py-0.5 text-[11.5px] text-slate-300 hover:border-slate-500/60"
          >
            Issue key
          </button>
        </div>
        {issued && (
          <div className="flex items-center gap-2 rounded-[var(--r-md)] p-2" style={{ background: "var(--wash-ok)" }}>
            <code className="min-w-0 flex-1 break-all font-mono text-[11px]" style={{ color: "var(--state-healthy-ink)" }}>{issued}</code>
            <CopyButton text={issued} />
          </div>
        )}
        {keys === null ? (
          <p className="text-[12px] text-slate-500">Loading…</p>
        ) : keys.length === 0 ? (
          <p className="text-[12px] text-slate-500">No keys yet.</p>
        ) : (
          <ul className="space-y-1">
            {keys.map((k) => (
              <li key={k.id} className="flex items-center gap-2 text-[12px]">
                <code className="font-mono text-slate-300">{k.prefix}…</code>
                <Pill tone={k.active ? "ok" : "mute"}>{k.active ? "active" : "revoked"}</Pill>
                <span className="text-slate-500">{dateOf(k.createdAt)}</span>
                {k.active && (
                  <button
                    onClick={() => run("Revoke", async () => { await api.opDelete(`/api/admin/keys/${k.id}`); await loadKeys(); }, "Key revoked")}
                    className="ml-auto text-[11.5px] hover:underline"
                    style={{ color: "var(--state-critical-ink)" }}
                  >
                    Revoke
                  </button>
                )}
              </li>
            ))}
          </ul>
        )}
      </div>

      <div className="space-y-2">
        <h3 className="micro">Provider credentials</h3>
        {creds && creds.length > 0 && (
          <Table
            head={
              <tr>
                <TH>Provider</TH>
                <TH>Stored</TH>
                <TH align="right"> </TH>
              </tr>
            }
          >
            {creds.map((c) => (
              <TR key={c.provider}>
                <TD>{c.provider}</TD>
                <TD muted>{dateTimeOf(c.updatedAt ?? c.createdAt)}</TD>
                <TD align="right">
                  <button
                    onClick={() => run("Remove", async () => { await api.opDelete(`${base}/credentials/${encodeURIComponent(c.provider)}`); await loadCreds(); }, `${c.provider} key removed`)}
                    className="text-[11.5px] hover:underline"
                    style={{ color: "var(--state-critical-ink)" }}
                  >
                    Remove
                  </button>
                </TD>
              </TR>
            ))}
          </Table>
        )}
        <div className="flex gap-2">
          <Select aria-label="Provider" value={provider} onChange={(e) => setProvider(e.target.value)} className="!w-32 shrink-0">
            {PROVIDERS.map((p) => (
              <option key={p}>{p}</option>
            ))}
          </Select>
          <Input aria-label="Provider API key" type="password" autoComplete="off" value={secret} onChange={(e) => setSecret(e.target.value)} placeholder="their provider key" />
          <button
            onClick={() =>
              run(
                "Store",
                async () => {
                  await api.opPost(`${base}/credentials`, { provider, secret });
                  setSecret("");
                  await loadCreds();
                },
                `${provider} key stored, encrypted`,
              )
            }
            disabled={!secret.trim() || busy === "Store"}
            className="shrink-0 rounded-[var(--r-md)] border border-edge px-3 text-[12.5px] text-slate-200 hover:border-slate-500/60 disabled:opacity-50"
          >
            Store
          </button>
        </div>
        <p className="text-[11.5px] text-slate-500">Encrypted at rest and never shown again, here or anywhere.</p>
      </div>
    </div>
  );
}

function readError(e: any): string | null {
  const b = e?.body;
  return (b && (b.error || b.message)) || (typeof e?.message === "string" ? e.message.replace(/^\d+: /, "") : null);
}
