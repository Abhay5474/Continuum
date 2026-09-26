import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { api } from "../api";
import { Chip, Empty, Pill, Segmented, Stat, Stats, type Tone } from "../system/hub";
import { useOperator } from "../system/OperatorAccess";
import { StackedBar, type Datum } from "../system/charts";
import { Input, Select, Table, TD, TH, TR } from "../system/controls";
import { useToast } from "../components/ui";
import { dateOf, toMillis } from "../system/time";

/**
 * Models — every model each provider offers, as the provider itself says.
 *
 * <p>The catalogue is read from Groq's and Gemini's own model lists, and a
 * model counts as free only once it answered a test call on this deployment's
 * free-tier key. A model the provider stops listing is taken out of routing at
 * once and retired when the next list confirms it; requests still naming it go
 * to its replacement. Nothing here is scraped from a web page.
 */

type Model = {
  id: number;
  provider: string;
  name: string;
  displayName: string | null;
  description: string | null;
  kind: string;
  status: "ACTIVE" | "DISCOVERED" | "TESTING" | "UNAVAILABLE" | "DEPRECATED" | "REMOVED";
  statusReason: string | null;
  note: string | null;
  routable: boolean;
  quarantined: boolean;
  preview: boolean;
  contextWindow: number;
  maxOutputTokens: number;
  providerCreatedAt: unknown;
  verifiedAt: unknown;
  probedAt: unknown;
  probeOutcome: string | null;
  limits: { requestsPerDay?: number; tokensPerMinute?: number; source?: string } | null;
  missingSince: unknown;
  retiredAt: unknown;
  replacedBy: string | null;
  firstSeenAt: unknown;
  source: string;
  free: "VERIFIED" | "NO" | "UNKNOWN" | "BUILT_IN";
};

type Provider = {
  provider: string;
  label: string;
  configured: boolean;
  freeTier: boolean;
  lastListOkAt: unknown;
  lastAttemptAt: unknown;
  lastError: string | null;
  listedCount: number;
  defaultModel: string | null;
  pinnedModel: string | null;
  configuredModel: string | null;
  nextCheckAt: unknown;
};

type Policy = { provider: string; reviewed: string; notes: { title: string; body: string }[]; links: { label: string; url: string }[] };
type CheckState = { running: { trigger: string; startedAt: unknown; phase: string } | null; lastRun: any; nextManualAt: unknown };
type Catalog = { providers: Provider[]; models: Model[]; policies: Record<string, Policy>; check: CheckState };
type ModelEvent = { id: number; provider: string; model: string | null; type: string; detail: string; createdAt: unknown };

const FILTERS = [
  { value: "chat", label: "Chat models" },
  { value: "retired", label: "Retired" },
  { value: "other", label: "Not for chat" },
  { value: "all", label: "All" },
] as const;
type Filter = (typeof FILTERS)[number]["value"];

/** What a status means to someone deciding whether requests can use the model. */
function statusOf(m: Model): { label: string; tone: Tone } {
  if (m.quarantined) return { label: "set aside", tone: "warn" };
  if (m.kind !== "CHAT" && m.status === "ACTIVE") return { label: m.kind === "ALIAS" ? "alias" : "not for chat", tone: "mute" };
  switch (m.status) {
    case "ACTIVE":
      return m.verifiedAt || m.provider === "mock" ? { label: "in use", tone: "ok" } : { label: "assumed", tone: "info" };
    case "DISCOVERED":
    case "TESTING":
      return { label: "waiting for a test", tone: "info" };
    case "UNAVAILABLE":
      return { label: "not usable on this key", tone: "warn" };
    case "DEPRECATED":
      return { label: "missing from list", tone: "warn" };
    case "REMOVED":
      return { label: "retired", tone: "bad" };
  }
}

function ago(v: unknown): string {
  const t = toMillis(v);
  if (t == null) return "never";
  const s = Math.round((Date.now() - t) / 1000);
  const fut = s < 0;
  const a = Math.abs(s);
  const text = a < 60 ? `${a} s` : a < 3600 ? `${Math.round(a / 60)} min` : a < 86400 ? `${Math.round(a / 3600)} h` : `${Math.round(a / 86400)} days`;
  return fut ? `in ${text}` : `${text} ago`;
}

const EVENT_TONE: Record<string, Tone> = {
  VERIFIED: "ok",
  ADDED: "info",
  CATALOGUED: "info",
  RETURNED: "info",
  DEFAULT_CHANGED: "violet",
  REPLACED: "violet",
  PINNED: "violet",
  UNPINNED: "violet",
  MISSING: "warn",
  REPORTED_GONE: "warn",
  UNAVAILABLE: "warn",
  PROBES_PAUSED: "warn",
  CHECK_WARNING: "warn",
  RETIRED: "bad",
  CHECK_FAILED: "bad",
  PROBES_STOPPED: "bad",
  NO_USABLE_MODEL: "bad",
};

export default function Models() {
  const toast = useToast();
  const { operator } = useOperator();
  const [data, setData] = useState<Catalog | null>(null);
  const [events, setEvents] = useState<ModelEvent[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [filter, setFilter] = useState<Filter>("chat");
  const [q, setQ] = useState("");
  const [check, setCheck] = useState<CheckState | null>(null);
  const [asking, setAsking] = useState(false);
  const [, tick] = useState(0);

  const load = useCallback(() => {
    api.models
      .catalog()
      .then((c: Catalog) => {
        setData(c);
        setCheck(c.check);
        setError(null);
      })
      .catch((e) => setError(e?.message ?? "Could not load the catalogue"));
    api.models.events(60).then(setEvents).catch(() => {});
  }, []);
  useEffect(load, [load]);

  // While a check runs, follow it every two seconds — and only then. When it
  // finishes, reload once. No polling otherwise.
  const running = !!check?.running;
  const wasRunning = useRef(false);
  useEffect(() => {
    if (!running) {
      if (wasRunning.current) {
        wasRunning.current = false;
        load();
      }
      return;
    }
    wasRunning.current = true;
    const id = window.setInterval(() => {
      api.models.checkState().then(setCheck).catch(() => {});
    }, 2000);
    return () => window.clearInterval(id);
  }, [running, load]);

  // The cooldown counts down on screen without asking the server.
  const nextManual = toMillis(check?.nextManualAt);
  useEffect(() => {
    if (!nextManual || nextManual < Date.now()) return;
    const id = window.setInterval(() => tick((n) => n + 1), 1000);
    return () => window.clearInterval(id);
  }, [nextManual]);
  const cooling = !!nextManual && nextManual > Date.now();

  const checkNow = async () => {
    if (asking || running || cooling) return;
    setAsking(true);
    try {
      const r = await api.models.check();
      if (r.status === 202) {
        toast(r.body?.message ?? "Checking the providers", "info");
        api.models.checkState().then(setCheck).catch(() => {});
      } else if (r.status === 409 || r.status === 429 || r.status === 422) {
        toast(r.body?.message ?? "Not now", "info");
        api.models.checkState().then(setCheck).catch(() => {});
      } else {
        toast(r.body?.message ?? `Check failed (${r.status})`, "error");
      }
    } catch (e: any) {
      toast(e?.message ?? "Check failed", "error");
    } finally {
      setAsking(false);
    }
  };

  const models = data?.models ?? [];
  // Counted over providers with a key: a model nobody can call is not "in use".
  const configured = new Set((data?.providers ?? []).filter((p) => p.configured).map((p) => p.provider));
  const chat = models.filter((m) => m.kind === "CHAT" && configured.has(m.provider));
  const counts = {
    inUse: chat.filter((m) => m.routable).length,
    waiting: chat.filter((m) => m.status === "DISCOVERED" || m.status === "TESTING").length,
    unusable: chat.filter((m) => m.status === "UNAVAILABLE" || m.quarantined || m.status === "DEPRECATED").length,
    retired: chat.filter((m) => m.status === "REMOVED").length,
  };

  const shown = useMemo(() => {
    const needle = q.trim().toLowerCase();
    return models.filter((m) => {
      if (needle && !`${m.name} ${m.displayName ?? ""}`.toLowerCase().includes(needle)) return false;
      if (filter === "chat") return m.kind === "CHAT" && m.status !== "REMOVED";
      if (filter === "retired") return m.status === "REMOVED";
      if (filter === "other") return m.kind !== "CHAT";
      return true;
    });
  }, [models, filter, q]);

  const byProvider = useMemo(() => {
    const g = new Map<string, Model[]>();
    shown.forEach((m) => g.set(m.provider, [...(g.get(m.provider) ?? []), m]));
    return g;
  }, [shown]);

  const lastRun = check?.lastRun;
  const anyConfigured = (data?.providers ?? []).some((p) => p.configured);

  return (
    <div className="space-y-8">
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <div className="flex items-center gap-2.5">
            <Chip glyph="model" tone="accent" size={28} />
            <h1 className="text-[20px] font-semibold tracking-[-0.011em]">Models</h1>
          </div>
          <p className="mt-0.5 max-w-2xl text-sm leading-relaxed text-slate-500">
            Read from each provider's own model list · free only once a test call proves it · retired models replaced automatically
          </p>
        </div>
        <div className="plane w-full max-w-md px-4 py-3 sm:w-auto sm:min-w-[320px]" data-guide="models-check">
          <div className="flex items-center gap-3">
            <div className="min-w-0 flex-1">
              <div className="text-sm font-medium text-slate-200">{running ? "Checking the providers…" : "Model check"}</div>
              <div className="mt-0.5 text-[12px] leading-snug text-slate-500" aria-live="polite">
                {running
                  ? check?.running?.phase ?? "Working"
                  : lastRun
                    ? `Last checked ${ago(lastRun.startedAt)}${lastRun.outcome && lastRun.outcome !== "OK" ? ` · ${String(lastRun.outcome).toLowerCase()}` : ""}`
                    : "Not checked yet"}
              </div>
            </div>
            <button
              onClick={checkNow}
              disabled={asking || running || cooling || !anyConfigured}
              title={!anyConfigured ? "No provider API key is configured" : cooling ? "One check per ten minutes" : undefined}
              className="shrink-0 rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-3.5 py-1.5 text-[12.5px] font-medium text-white hover:opacity-90 disabled:opacity-50"
            >
              {running ? (
                <span className="inline-flex items-center gap-2">
                  <span className="h-3 w-3 animate-spin rounded-full border-2 border-white/40 border-t-white" aria-hidden />
                  Checking
                </span>
              ) : cooling ? (
                (() => {
                  const s = Math.max(1, Math.ceil(((nextManual ?? 0) - Date.now()) / 1000));
                  return s > 90 ? `Again in ${Math.ceil(s / 60)} min` : `Again in ${s} s`;
                })()
              ) : (
                "Check now"
              )}
            </button>
          </div>
          {lastRun && !running && (
            <div className="mt-2 border-t border-edge/60 pt-2 text-[11.5px] text-slate-500">
              Cost of the last check: {lastRun.listCalls} list call{lastRun.listCalls === 1 ? "" : "s"}, {lastRun.probeCalls} test call
              {lastRun.probeCalls === 1 ? "" : "s"} · automatic checks every 10 days
            </div>
          )}
        </div>
      </header>

      {error && <p className="text-sm" style={{ color: "var(--state-critical-ink)" }}>{error}</p>}

      {data && (
        <>
          <Stats cols={4}>
            <Stat label="In use" glyph="check" tone="green" value={counts.inUse} hint="Chat models requests can be sent to now" />
            <Stat label="Waiting for a test" glyph="clock" tone="blue" value={counts.waiting} hint="Listed by the provider; tested at the next check" />
            <Stat label="Not usable on this key" glyph="block" tone="amber" value={counts.unusable} hint="No free quota, no access, or missing from the list" />
            <Stat label="Retired" glyph="alert" tone={counts.retired ? "red" : "mute"} value={counts.retired} hint="No longer offered; requests naming them go to a replacement" />
          </Stats>

          <div className="grid items-start gap-4 lg:grid-cols-2">
            {data.providers.map((p) => (
              <ProviderCard
                key={p.provider}
                p={p}
                models={models.filter((m) => m.provider === p.provider)}
                policy={data.policies[p.provider]}
                operator={operator}
                onPinned={load}
              />
            ))}
          </div>

          <section className="space-y-3">
            <div className="flex flex-wrap items-center gap-3">
              <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Catalogue</h2>
              <Segmented value={filter} onChange={setFilter} options={FILTERS.map((f) => ({ value: f.value, label: f.label }))} />
              <span className="ml-auto w-full sm:w-64">
                <Input aria-label="Filter models" value={q} onChange={(e) => setQ(e.target.value)} placeholder="Filter by name" />
              </span>
            </div>
            {shown.length === 0 ? (
              <Empty title="No models match" glyph="model" hint={filter === "retired" ? "Nothing has been retired." : undefined} />
            ) : (
              [...byProvider.entries()].map(([provider, list]) => (
                <div key={provider} className="space-y-1.5">
                  <div className="micro">{provider === "mock" ? "Built-in demo models" : data.providers.find((x) => x.provider === provider)?.label ?? provider}</div>
                  <ModelTable
                    models={list}
                    defaultModel={data.providers.find((x) => x.provider === provider)?.defaultModel ?? null}
                    pinned={data.providers.find((x) => x.provider === provider)?.pinnedModel ?? null}
                  />
                </div>
              ))
            )}
          </section>

          <section className="space-y-2">
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">What changed</h2>
            {events.length === 0 ? (
              <p className="text-sm text-slate-500">Nothing yet. Changes appear here after a check: models added, verified, missing, retired and replaced.</p>
            ) : (
              <ol className="plane divide-y divide-edge/50 overflow-hidden">
                {events.slice(0, 40).map((e, i) => (
                  <li key={e.id} className="rise-in flex flex-wrap items-baseline gap-x-3 gap-y-1 px-4 py-2.5" style={{ animationDelay: `${Math.min(i, 12) * 30}ms` }}>
                    <span className="w-24 shrink-0 text-[11.5px] text-slate-500">{ago(e.createdAt)}</span>
                    <Pill tone={EVENT_TONE[e.type] ?? "mute"}>{e.type.toLowerCase().replace(/_/g, " ")}</Pill>
                    {e.model && <span className="font-mono text-[12px] text-slate-300">{e.model}</span>}
                    <span className="min-w-0 flex-1 text-[12.5px] text-slate-400">{e.detail}</span>
                  </li>
                ))}
              </ol>
            )}
          </section>
        </>
      )}
    </div>
  );
}

function ProviderCard({ p, models, policy, operator, onPinned }: { p: Provider; models: Model[]; policy?: Policy; operator: boolean; onPinned: () => void }) {
  const toast = useToast();
  const [busy, setBusy] = useState(false);
  const chat = models.filter((m) => m.kind === "CHAT");
  const parts: Datum[] = [
    { key: "use", label: "in use", value: chat.filter((m) => m.routable).length, color: "var(--state-healthy-ink)" },
    { key: "wait", label: "waiting for a test", value: chat.filter((m) => m.status === "DISCOVERED" || m.status === "TESTING").length, color: "var(--series-1)" },
    { key: "no", label: "not usable", value: chat.filter((m) => m.status === "UNAVAILABLE" || m.status === "DEPRECATED" || m.quarantined).length, color: "var(--state-warning-ink)" },
    { key: "gone", label: "retired", value: chat.filter((m) => m.status === "REMOVED").length, color: "var(--state-critical-ink)" },
  ].filter((d) => d.value > 0);
  const usable = chat.filter((m) => m.routable).map((m) => m.name);

  const pin = async (model: string) => {
    setBusy(true);
    try {
      await api.models.pin(p.provider, model || null);
      toast(model ? `${model} pinned as ${p.label}'s default` : "Default chosen automatically again", "success");
      onPinned();
    } catch (e: any) {
      toast(e?.message ?? "Pin failed", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <section className="plane space-y-3 p-5">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-[15px] font-semibold text-slate-100">{p.label}</h2>
        {p.configured ? (
          <Pill tone={p.freeTier ? "ok" : "warn"} dot>
            {p.freeTier ? "free-tier key" : "key not declared free-tier"}
          </Pill>
        ) : (
          <Pill tone="mute">no API key</Pill>
        )}
        {p.lastError && <Pill tone="bad">last check failed</Pill>}
      </div>

      <dl className="grid grid-cols-2 gap-x-4 gap-y-2 text-[12.5px]">
        <div>
          <dt className="micro">Default model</dt>
          <dd className="mt-0.5 break-all font-mono text-slate-200">{p.defaultModel ?? "—"}</dd>
        </div>
        <div>
          <dt className="micro">Listed by {p.label}</dt>
          <dd className="mt-0.5 text-slate-300">{p.lastListOkAt ? `${p.listedCount} models · ${ago(p.lastListOkAt)}` : "not read yet"}</dd>
        </div>
        <div>
          <dt className="micro">Next automatic check</dt>
          <dd className="mt-0.5 text-slate-300">{p.configured ? (p.nextCheckAt ? `${dateOf(p.nextCheckAt)} (${ago(p.nextCheckAt)})` : "—") : "needs an API key"}</dd>
        </div>
        <div>
          <dt className="micro">Preferred in config</dt>
          <dd className="mt-0.5 break-all font-mono text-slate-400">{p.configuredModel ?? "none — chosen automatically"}</dd>
        </div>
      </dl>
      {p.lastError && <p className="text-[12px]" style={{ color: "var(--state-critical-ink)" }}>{p.lastError} — nothing was changed.</p>}

      {parts.length > 0 && <StackedBar data={parts} height={20} unit="models" />}

      {operator && usable.length > 0 && (
        <label className="flex items-center gap-2 text-[12.5px] text-slate-400">
          <span className="shrink-0">Pin default</span>
          <Select aria-label={`Pin ${p.label} default`} value={p.pinnedModel ?? ""} disabled={busy} onChange={(e) => pin(e.target.value)}>
            <option value="">Automatic (recommended)</option>
            {usable.map((m) => (
              <option key={m} value={m}>
                {m}
              </option>
            ))}
          </Select>
        </label>
      )}

      {policy && (
        <details className="rounded-[var(--r-md)] border border-edge/60 px-3 py-2">
          <summary className="cursor-pointer select-none text-[12.5px] font-medium text-slate-300">
            What {p.label}'s terms say <span className="font-normal text-slate-500">· reviewed {policy.reviewed}</span>
          </summary>
          <div className="mt-2 space-y-2.5">
            {policy.notes.map((n) => (
              <div key={n.title}>
                <div className="text-[12.5px] font-medium text-slate-200">{n.title}</div>
                <p className="mt-0.5 text-[12px] leading-relaxed text-slate-400">{n.body}</p>
              </div>
            ))}
            <p className="text-[11.5px] text-slate-500">The provider's own pages are the authority; these notes summarise them.</p>
            <div className="flex flex-wrap gap-x-3 gap-y-1">
              {policy.links.map((l) => (
                <a key={l.url} href={l.url} target="_blank" rel="noopener noreferrer" className="text-[12px] underline underline-offset-2" style={{ color: "var(--accent-ink)" }}>
                  {l.label} ↗
                </a>
              ))}
            </div>
          </div>
        </details>
      )}
    </section>
  );
}

function ModelTable({ models, defaultModel, pinned }: { models: Model[]; defaultModel: string | null; pinned: string | null }) {
  const [open, setOpen] = useState<number | null>(null);
  return (
    <Table
      minWidth={760}
      label="Models"
      head={
        <tr>
          <TH>Model</TH>
          <TH>Status</TH>
          <TH>Free</TH>
          <TH align="right">Limits on this key</TH>
          <TH align="right">Context</TH>
          <TH align="right"><span className="sr-only">Details</span></TH>
        </tr>
      }
    >
      {models.map((m) => {
        const st = statusOf(m);
        return (
          <TR key={m.id}>
            <TD>
              <div className="flex flex-wrap items-center gap-1.5">
                <span className="break-all font-mono text-[12.5px] text-slate-200">{m.name}</span>
                {m.name === defaultModel && <Pill tone="violet">default</Pill>}
                {m.name === pinned && <Pill tone="violet">pinned</Pill>}
                {m.preview && <Pill tone="warn">preview</Pill>}
              </div>
              {m.displayName && m.displayName !== m.name && <div className="text-[11.5px] text-slate-500">{m.displayName}</div>}
            </TD>
            <TD>
              <Pill tone={st.tone} dot>
                {st.label}
              </Pill>
              {m.replacedBy && (m.status === "REMOVED" || m.status === "UNAVAILABLE") && (
                <div className="mt-0.5 text-[11.5px] text-slate-500">
                  → <span className="font-mono">{m.replacedBy}</span>
                </div>
              )}
            </TD>
            <TD>
              {m.free === "VERIFIED" ? (
                <span className="text-[12px]" style={{ color: "var(--state-healthy-ink)" }} title={`Answered a test call ${ago(m.verifiedAt)}`}>
                  ✓ verified {dateOf(m.verifiedAt)}
                </span>
              ) : m.free === "NO" ? (
                <span className="text-[12px]" style={{ color: "var(--state-warning-ink)" }}>no free quota</span>
              ) : m.free === "BUILT_IN" ? (
                <span className="text-[12px] text-slate-500">built in</span>
              ) : (
                <span className="text-[12px] text-slate-500">not yet known</span>
              )}
            </TD>
            <TD numeric>
              {m.limits?.requestsPerDay || m.limits?.tokensPerMinute ? (
                <span className="text-[12px]" title={m.limits.source}>
                  {m.limits.requestsPerDay ? `${m.limits.requestsPerDay.toLocaleString()} req/day` : ""}
                  {m.limits.requestsPerDay && m.limits.tokensPerMinute ? " · " : ""}
                  {m.limits.tokensPerMinute ? `${m.limits.tokensPerMinute.toLocaleString()} tok/min` : ""}
                </span>
              ) : (
                <span className="text-slate-500">—</span>
              )}
            </TD>
            <TD numeric>{m.contextWindow ? `${Math.round(m.contextWindow / 1000).toLocaleString()}k` : "—"}</TD>
            <TD numeric>
              <button onClick={() => setOpen(open === m.id ? null : m.id)} aria-expanded={open === m.id} className="text-[12px] text-slate-400 hover:text-slate-200">
                {open === m.id ? "hide" : "why"}
              </button>
              {open === m.id && (
                <div className="rise-in mt-2 max-w-[360px] space-y-1 text-left text-[12px] text-slate-400">
                  {m.statusReason && <p>{m.statusReason}</p>}
                  {m.note && m.note !== m.statusReason && <p>{m.note}</p>}
                  {m.missingSince != null && <p>Missing from the list since {dateOf(m.missingSince)}.</p>}
                  {m.retiredAt != null && <p>Retired {dateOf(m.retiredAt)}.</p>}
                  {m.probedAt != null && <p>Last tested {ago(m.probedAt)}{m.probeOutcome ? ` (${m.probeOutcome.toLowerCase().replace(/_/g, " ")})` : ""}.</p>}
                  <p className="text-slate-500">
                    {m.source === "live" ? "Named in the provider's own list" : "From the built-in list — not yet confirmed by the provider"}
                    {m.firstSeenAt ? ` · first seen ${dateOf(m.firstSeenAt)}` : ""}
                  </p>
                </div>
              )}
            </TD>
          </TR>
        );
      })}
    </Table>
  );
}
