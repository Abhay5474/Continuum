import { useCallback, useEffect, useMemo, useState } from "react";
import { portal } from "../api";
import { PageHeader } from "../system/primitives";
import { ErrorState, useToast } from "../components/ui";
import { dateTimeOf } from "../system/time";
import type { Kind } from "../system/hub";
import {
  Because,
  Code,
  CommandBar,
  Dot,
  Empty,
  Facts,
  Field,
  Flow,
  Ghost,
  KIND_LABEL,
  KindMark,
  Primary,
  ProviderLine,
  Rail,
  Row,
  RowSkeleton,
  Section,
  SidePanel,
  kindOf,
} from "../system/hub";

/**
 * Specialists — one workspace, not a directory.
 *
 * <p>This used to be two pages: a list of installed specialists and a "Hub" of
 * things you could add. Both rendered as grids of bordered cards carrying five
 * pills each, which is what a database looks like when it is printed onto a
 * screen. Every fact was present and none of it was legible, because a card has
 * nowhere to put structure except more chrome.
 *
 * <p>The rebuild starts from what someone actually arrives here to do, which is
 * one of exactly three things.
 *
 * <ol>
 *   <li><b>"I need something that can read a scan."</b> A capability search — so
 *       search is the primary control, it takes an intention rather than a
 *       keyword, and every result says why it matched.</li>
 *   <li><b>"Is the thing I installed working?"</b> A status check — so running
 *       specialists come first, each showing its state and last probe without
 *       being opened.</li>
 *   <li><b>"What does this actually return?"</b> Understanding — so selecting
 *       anything opens a panel whose centrepiece is the transformation drawn:
 *       input, specialist, output. That is the question being asked, and a
 *       paragraph is a worse answer than a picture.</li>
 * </ol>
 *
 * <p>Nothing about the API, the adapters, the probe lifecycle or the search
 * endpoint changed. Same data, arranged so it can be read.
 */

type Provider = {
  name: string;
  label: string;
  baseUrl: string | null;
  authStyle: string;
  authParam: string | null;
  inputKinds: string[];
  requiresBaseUrl: boolean;
};

type Connection = {
  id: number;
  name: string;
  provider: string;
  baseUrl: string;
  authStyle: string;
  hasCredential: boolean;
  status: "UNVERIFIED" | "VERIFIED" | "FAILING";
  lastError: string | null;
};

type EvidenceItem = {
  kind: "DETECTION" | "CLASSIFICATION" | "TEXT" | "FIELD" | "ROW" | "NOTE";
  label: string | null;
  confidence: number | null;
  scored: boolean;
  text: string | null;
};

type Specialist = {
  id: number;
  name: string;
  connectionId: number;
  modelPath: string;
  inputKind: string;
  minConfidence: number;
  timeoutSeconds: number;
  status: "DRAFT" | "READY" | "UNPARSEABLE" | "FAILED";
  probeStatus: number | null;
  probeMs: number | null;
  probeError: string | null;
  probedAt: string | null;
  probeResponse: string | null;
  toolKind: string;
  toolKindLabel: string;
  scored: boolean;
  probeFindings: EvidenceItem[];
};

type Entry = {
  id: string;
  title: string;
  description: string;
  provider: string;
  baseUrl: string;
  modelPath: string;
  inputKind: string;
  toolKind: string;
  toolKindLabel: string;
  scored: boolean;
  suggestedConfidence: number;
  tags: string[];
  needs: string[];
  note: string;
  source: string;
};

type SourceState = {
  name: string;
  available: boolean;
  live?: boolean;
  results?: number;
  reason?: string | null;
};

/** What each capability hands the model. Shown in the flow, not described. */
const OUTPUT_OF: Record<string, string> = {
  detection: "labelled regions, each with a confidence",
  classification: "labels with confidences",
  ocr: "recovered text · no confidence",
  transcription: "a transcript · no confidence",
  extraction: "named fields",
  moderation: "safety labels with scores",
  table: "columns, units and rows",
  incident: "patterns, exceptions, a timeline",
  conversation: "messages in order",
  document: "text and structure",
  custom: "whatever the endpoint returns",
};

const INPUT_LABEL: Record<string, string> = {
  image: "an image",
  audio: "a recording",
  text: "text",
  json: "a JSON payload",
  document: "a document",
};

const SUGGESTIONS = [
  "read text from a scan",
  "transcribe a recording",
  "detect objects in an image",
  "check text for abuse",
];

/** Why a row matched, in the words of the thing that matched. */
function matchReason(e: Entry, q: string): string | null {
  const t = q.toLowerCase().trim();
  if (!t) return null;
  const words = t.split(/\s+/).filter((w) => w.length > 2);
  const hay = `${e.title} ${e.description} ${e.tags.join(" ")} ${e.toolKindLabel}`.toLowerCase();
  if (!words.some((w) => hay.includes(w))) return null;
  if (e.toolKindLabel && words.some((w) => e.toolKindLabel.toLowerCase().includes(w))) {
    return `${e.toolKindLabel.toLowerCase()} is exactly what this does`;
  }
  const sentence = e.description.split(/(?<=\.)\s/)[0];
  return sentence.length > 96 ? sentence.slice(0, 95) + "…" : sentence;
}

export default function Specialists() {
  const toast = useToast();
  const [providers, setProviders] = useState<Provider[]>([]);
  const [connections, setConnections] = useState<Connection[] | null>(null);
  const [specialists, setSpecialists] = useState<Specialist[] | null>(null);
  const [entries, setEntries] = useState<Entry[]>([]);
  const [sources, setSources] = useState<SourceState[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [q, setQ] = useState("");
  const [sel, setSel] = useState<{ kind: "installed" | "catalogue"; id: string } | null>(null);
  const [connectFor, setConnectFor] = useState<string | null>(null);

  const load = useCallback(async () => {
    try {
      const [p, c, s] = await Promise.all([
        portal.specialists.providers(),
        portal.specialists.connections(),
        portal.specialists.list(),
      ]);
      setProviders(p);
      setConnections(c);
      setSpecialists(s);
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load specialists.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  // Same catalogue endpoint as before, debounced so typing an intention does
  // not fan out a request per keystroke.
  useEffect(() => {
    let cancelled = false;
    const t = setTimeout(async () => {
      try {
        const r = await portal.specialists.catalogue(q);
        if (!cancelled) {
          setEntries(r.entries ?? []);
          setSources(r.sources ?? []);
        }
      } catch {
        if (!cancelled) setEntries([]);
      }
    }, 170);
    return () => {
      cancelled = true;
      clearTimeout(t);
    };
  }, [q]);

  const run = async (fn: () => Promise<unknown>, ok: string) => {
    setBusy(true);
    try {
      await fn();
      toast(ok, "success");
      await load();
    } catch (e: any) {
      toast(e?.message ?? "That did not work.", "error");
      throw e;
    } finally {
      setBusy(false);
    }
  };

  const installed = specialists ?? [];
  const connectionOf = (id: number) => (connections ?? []).find((c) => c.id === id);

  const matchedInstalled = useMemo(() => {
    const t = q.toLowerCase().trim();
    if (!t) return installed;
    return installed.filter((s) =>
      `${s.name} ${s.toolKindLabel} ${s.modelPath} ${s.inputKind}`.toLowerCase().includes(t)
    );
  }, [installed, q]);

  // Grouped by what the capability does, in a fixed order so the list does not
  // reshuffle as results change.
  const grouped = useMemo(() => {
    const order: Kind[] = [
      "ocr", "transcription", "detection", "classification",
      "moderation", "extraction", "table", "incident", "conversation",
      "document", "custom",
    ];
    const by = new Map<Kind, Entry[]>();
    for (const e of entries) {
      const k = kindOf(e.toolKind || e.toolKindLabel);
      (by.get(k) ?? by.set(k, []).get(k)!).push(e);
    }
    return order.filter((k) => by.has(k)).map((k) => [k, by.get(k)!] as const);
  }, [entries]);

  const selected = useMemo(() => {
    if (!sel) return null;
    if (sel.kind === "installed") {
      const s = installed.find((x) => String(x.id) === sel.id);
      return s ? ({ kind: "installed", s } as const) : null;
    }
    const e = entries.find((x) => x.id === sel.id);
    return e ? ({ kind: "catalogue", e } as const) : null;
  }, [sel, installed, entries]);

  if (error) return <ErrorState message={error} onRetry={load} />;

  const searching = q.trim().length > 0;

  return (
    <div className="mx-auto max-w-5xl pb-24">
      <PageHeader
        title="Specialists"
        subtitle="A purpose-built model runs before the language model, and hands it evidence instead of a raw file"
      />

      <div className="mt-6">
        <CommandBar
          value={q}
          onChange={setQ}
          placeholder="What do you need it to do?   e.g. read text from a scan"
          suggestions={SUGGESTIONS}
        />
      </div>

      {(!searching || matchedInstalled.length > 0) && (
        <Section
          title={searching ? "Already running" : "Running"}
          count={specialists ? matchedInstalled.length : undefined}
          hint={
            installed.length === 0
              ? undefined
              : "Probed before use — a specialist that has not answered correctly cannot enter a pipeline."
          }
        >
          {specialists === null ? (
            <RowSkeleton rows={3} />
          ) : matchedInstalled.length === 0 ? (
            <Empty
              title="Nothing running yet"
              hint="Search above for a capability — reading a scan, transcribing a recording, detecting objects — and add it with your own provider key."
            />
          ) : (
            <Rail>
              {matchedInstalled.map((s) => {
                const kind = kindOf(s.toolKind || s.toolKindLabel);
                const conn = connectionOf(s.connectionId);
                const tone = s.status === "READY" ? "ok" : s.status === "DRAFT" ? "idle" : "bad";
                return (
                  <Row
                    key={s.id}
                    selected={sel?.kind === "installed" && sel.id === String(s.id)}
                    onClick={() => setSel({ kind: "installed", id: String(s.id) })}
                    mark={<KindMark kind={kind} />}
                    title={s.name}
                    status={
                      <Dot
                        tone={tone}
                        label={
                          s.status === "READY"
                            ? "Ready"
                            : s.status === "DRAFT"
                              ? "Not probed"
                              : s.status === "UNPARSEABLE"
                                ? "Answer not understood"
                                : "Failing"
                        }
                      />
                    }
                    subtitle={`${INPUT_LABEL[s.inputKind] ?? s.inputKind} → ${OUTPUT_OF[kind] ?? "a result"}`}
                    meta={
                      <Facts
                        items={[
                          { k: "via", v: conn?.provider ?? "—" },
                          { k: "model", v: s.modelPath || "—" },
                          s.scored
                            ? { k: "threshold", v: s.minConfidence.toFixed(2) }
                            : {
                                k: "threshold",
                                v: "not applicable",
                                title:
                                  "This tool returns content rather than scored findings, so a confidence threshold does not apply to it.",
                              },
                          ...(s.probeMs !== null ? [{ k: "probe", v: `${s.probeMs}ms` }] : []),
                        ]}
                      />
                    }
                    actions={
                      <>
                        <Ghost
                          disabled={busy}
                          onClick={() => void run(() => portal.specialists.probe(s.id), "Probe sent").catch(() => {})}
                        >
                          Probe
                        </Ghost>
                        <Ghost
                          tone="danger"
                          disabled={busy}
                          onClick={() => void run(() => portal.specialists.remove(s.id), "Removed").catch(() => {})}
                        >
                          Remove
                        </Ghost>
                      </>
                    }
                  />
                );
              })}
            </Rail>
          )}
        </Section>
      )}

      <Section
        title={searching ? "Capabilities that match" : "Add a capability"}
        count={entries.length || undefined}
        hint={
          searching
            ? undefined
            : "Each calls a third-party model with your own key. Continuum normalises whatever comes back into one shape."
        }
      >
        {entries.length === 0 ? (
          <Empty
            title={searching ? `Nothing matches “${q}”` : "No capabilities available"}
            hint={
              searching
                ? "Try naming the job rather than a vendor — “read text from a scan” rather than a product name."
                : undefined
            }
          />
        ) : (
          <div className="space-y-6">
            {grouped.map(([groupKind, rows]) => (
              <div key={groupKind}>
                {/* A category heading, not a bordered section. Sixteen rows in
                    one run is a directory; five runs of three is a menu. */}
                {!searching && (
                  <div className="mb-1.5 flex items-baseline gap-2 px-3">
                    <span className="text-[11.5px] font-medium text-slate-400">
                      {KIND_LABEL[groupKind]}
                    </span>
                    <span className="readout text-[10.5px] text-slate-700">{rows.length}</span>
                  </div>
                )}
                <Rail>
          {rows.map((e) => {
              const kind = kindOf(e.toolKind || e.toolKindLabel);
              const reason = matchReason(e, q);
              const live = e.tags?.includes("live");
              const free = e.tags?.includes("free");
              return (
                <Row
                  key={e.id}
                  selected={sel?.kind === "catalogue" && sel.id === e.id}
                  onClick={() => setSel({ kind: "catalogue", id: e.id })}
                  mark={<KindMark kind={kind} />}
                  title={e.title}
                  status={
                    live ? (
                      <Dot tone="busy" label="live from provider" />
                    ) : free ? (
                      <Dot tone="ok" label="free tier" />
                    ) : undefined
                  }
                  subtitle={`${INPUT_LABEL[e.inputKind] ?? e.inputKind} → ${OUTPUT_OF[kind] ?? "a result"}`}
                  meta={
                    reason ? (
                      <Because reason={reason} />
                    ) : (
                      <Facts
                        items={[
                          { k: "via", v: e.provider },
                          {
                            k: "needs",
                            v: e.needs.includes("baseUrl") ? "your endpoint + key" : "your API key",
                          },
                        ]}
                      />
                    )
                  }
                  actions={<Ghost tone="accent">Inspect</Ghost>}
                />
              );
            })}
                </Rail>
              </div>
            ))}
          </div>
        )}
      </Section>

      <Section
        title="Providers"
        hint="Your keys, encrypted at rest and decrypted only at call time. No endpoint returns one."
      >
        <Rail>
          {providers
            .filter((p) => p.name !== "http")
            .map((p) => {
              const conn = (connections ?? []).find((c) => c.provider === p.name);
              const src = sources.find((s) => s.name.toLowerCase() === p.name.toLowerCase());
              const label = p.label.replace(/\s*\(.*\)$/, "");
              return (
                <ProviderLine
                  key={p.name}
                  name={label}
                  connected={!!conn?.hasCredential}
                  detail={
                    conn?.hasCredential
                      ? conn.status === "FAILING"
                        ? conn.lastError ?? "The last call failed"
                        : src && !src.available && src.reason
                          ? src.reason
                          : `${conn.name} · takes ${p.inputKinds.join(", ")}`
                      : `Connect to use ${label} models with your own key`
                  }
                  action={
                    conn?.hasCredential ? (
                      <Ghost
                        tone="danger"
                        disabled={busy}
                        onClick={() =>
                          void run(
                            () => portal.specialists.deleteConnection(conn.id),
                            "Disconnected"
                          ).catch(() => {})
                        }
                      >
                        Disconnect
                      </Ghost>
                    ) : (
                      <Ghost tone="accent" onClick={() => setConnectFor(p.name)}>
                        Connect
                      </Ghost>
                    )
                  }
                />
              );
            })}
        </Rail>
      </Section>

      <DetailPanel
        selected={selected}
        connections={connections ?? []}
        busy={busy}
        onClose={() => setSel(null)}
        onProbe={(id) => void run(() => portal.specialists.probe(id), "Probe sent").catch(() => {})}
        onInstall={async (entryId, body) => {
          try {
            await run(async () => {
              const r: any = await portal.specialists.install(entryId, body);
              if (r?.specialist?.status && r.specialist.status !== "READY") {
                throw new Error(`Added, but the probe came back ${r.specialist.status}.`);
              }
            }, "Added and probed — ready to use");
            setSel(null);
          } catch {
            /* the toast already said why; the panel stays open so it can be fixed */
          }
        }}
      />

      <ConnectPanel
        provider={providers.find((p) => p.name === connectFor) ?? null}
        busy={busy}
        onClose={() => setConnectFor(null)}
        onSave={async (body) => {
          try {
            await run(() => portal.specialists.addConnection(body), "Connected");
            setConnectFor(null);
          } catch {
            /* stays open */
          }
        }}
      />
    </div>
  );
}

/* ------------------------------------------------------------------ *
 * Detail
 * ------------------------------------------------------------------ */

function DetailPanel({
  selected,
  connections,
  busy,
  onClose,
  onProbe,
  onInstall,
}: {
  selected:
    | { readonly kind: "installed"; readonly s: Specialist }
    | { readonly kind: "catalogue"; readonly e: Entry }
    | null;
  connections: Connection[];
  busy: boolean;
  onClose: () => void;
  onProbe: (id: number) => void;
  onInstall: (entryId: string, body: any) => void;
}) {
  const [name, setName] = useState("");
  const [modelPath, setModelPath] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [secret, setSecret] = useState("");
  const [reuse, setReuse] = useState<number | "">("");

  const entry = selected?.kind === "catalogue" ? selected.e : null;
  const entryId = entry?.id;

  useEffect(() => {
    if (!entry) return;
    setName(entry.title);
    setModelPath(entry.modelPath);
    setBaseUrl(entry.baseUrl);
    setSecret("");
    setReuse("");
    // Keyed on the entry, so switching between two capabilities resets the form
    // rather than carrying one's model path onto the other.
  }, [entryId]);

  if (!selected) {
    return (
      <SidePanel open={false} title="" onClose={onClose}>
        {null}
      </SidePanel>
    );
  }

  if (selected.kind === "installed") {
    const s = selected.s;
    const kind = kindOf(s.toolKind || s.toolKindLabel);
    const conn = connections.find((c) => c.id === s.connectionId);
    return (
      <SidePanel
        open
        onClose={onClose}
        mark={<KindMark kind={kind} size={38} />}
        title={s.name}
        subtitle={`${s.toolKindLabel} · via ${conn?.provider ?? "unknown provider"}`}
        footer={
          <div className="flex items-center justify-between gap-3">
            <span className="text-[11px] text-slate-600">
              {s.probedAt ? `Last probed ${dateTimeOf(s.probedAt)}` : "Never probed"}
            </span>
            <Primary onClick={() => onProbe(s.id)} disabled={busy}>
              Probe again
            </Primary>
          </div>
        }
      >
        <Field label="What it does">
          <Flow
            vertical
            mark={<KindMark kind={kind} size={26} />}
            input={INPUT_LABEL[s.inputKind] ?? s.inputKind}
            node={s.name}
            nodeSub={s.modelPath || conn?.provider}
            output={OUTPUT_OF[kind] ?? "a result"}
          />
        </Field>

        <Field label="Configuration">
          <Facts
            items={[
              { k: "model", v: s.modelPath || "—" },
              { k: "input", v: s.inputKind },
              { k: "timeout", v: `${s.timeoutSeconds}s` },
              {
                k: "threshold",
                v: s.scored ? s.minConfidence.toFixed(2) : "not applicable",
                title: s.scored
                  ? "Findings below this are not reported."
                  : "This tool returns content rather than scored findings, so a threshold would discard all of it or none.",
              },
            ]}
          />
        </Field>

        <Field label="Authentication">
          {conn
            ? `${conn.name} · ${conn.authStyle.toLowerCase()} · ${conn.hasCredential ? "key stored, encrypted" : "no key stored"}`
            : "No connection"}
        </Field>

        {s.probeFindings?.length > 0 && (
          <Field label="What the last probe returned">
            <div className="space-y-1.5">
              {s.probeFindings.slice(0, 6).map((f, i) => (
                <div key={i} className="flex items-baseline justify-between gap-3">
                  <span className="min-w-0 truncate text-slate-300">
                    {f.label ?? f.text ?? f.kind.toLowerCase()}
                  </span>
                  <span className="readout shrink-0 text-[11px]">
                    {f.scored && f.confidence !== null ? (
                      <span style={{ color: "var(--accent-ink)" }}>{f.confidence.toFixed(2)}</span>
                    ) : (
                      <span className="text-slate-600">unscored</span>
                    )}
                  </span>
                </div>
              ))}
            </div>
          </Field>
        )}

        {s.probeError && (
          <Field label="Last error">
            <span style={{ color: "var(--state-critical-ink)" }}>{s.probeError}</span>
          </Field>
        )}

        {s.probeResponse && (
          <Field label="Raw provider response">
            <Code>{s.probeResponse.slice(0, 1400)}</Code>
          </Field>
        )}
      </SidePanel>
    );
  }

  const e = selected.e;
  const kind = kindOf(e.toolKind || e.toolKindLabel);
  const reusable = connections.filter((c) => c.provider === e.provider && c.hasCredential);
  const usingExisting = reuse !== "";
  const needsPath = e.needs.includes("modelPath");
  const needsBase = e.needs.includes("baseUrl");
  const ready =
    name.trim() !== "" &&
    (!needsPath || modelPath.trim() !== "") &&
    (usingExisting || (secret.trim() !== "" && (!needsBase || baseUrl.trim() !== "")));

  return (
    <SidePanel
      open
      onClose={onClose}
      mark={<KindMark kind={kind} size={38} />}
      title={e.title}
      subtitle={`${e.toolKindLabel} · ${e.provider}`}
      footer={
        <div className="flex items-center justify-between gap-3">
          <span className="text-[11px] text-slate-600">Probed on add, so you find out now.</span>
          <Primary
            disabled={busy || !ready}
            onClick={() =>
              onInstall(e.id, {
                name: name.trim(),
                baseUrl: baseUrl.trim() || undefined,
                modelPath: modelPath.trim() || undefined,
                connectionId: usingExisting ? (reuse as number) : undefined,
                secret: usingExisting ? undefined : secret,
              })
            }
          >
            {busy ? "Adding…" : "Add and probe"}
          </Primary>
        </div>
      }
    >
      <Field label="What it does">
        <Flow
          vertical
          mark={<KindMark kind={kind} size={26} />}
          input={INPUT_LABEL[e.inputKind] ?? e.inputKind}
          node={e.title}
          nodeSub={e.provider}
          output={OUTPUT_OF[kind] ?? "a result"}
        />
      </Field>

      <Field label="Purpose">{e.description}</Field>
      <Field label="Worth knowing">
        <span className="text-slate-400">{e.note}</span>
      </Field>

      <div className="mt-6 border-t border-edge/70 pt-4">
        <div className="micro">Add it</div>

        <FormRow label="Name it">
          <input
            value={name}
            onChange={(ev) => setName(ev.target.value)}
            className={INPUT_CLASS}
          />
        </FormRow>

        {reusable.length > 0 && (
          <FormRow label="Credential">
            <select
              value={reuse}
              onChange={(ev) => setReuse(ev.target.value === "" ? "" : Number(ev.target.value))}
              className={INPUT_CLASS}
            >
              <option value="">Use a new key…</option>
              {reusable.map((c) => (
                <option key={c.id} value={c.id}>
                  Reuse “{c.name}”
                </option>
              ))}
            </select>
          </FormRow>
        )}

        {!usingExisting && (
          <>
            {needsBase && (
              <FormRow label="Base URL">
                <input
                  value={baseUrl}
                  onChange={(ev) => setBaseUrl(ev.target.value)}
                  placeholder="https://models.your-company.internal"
                  className={MONO_CLASS}
                />
              </FormRow>
            )}
            <FormRow
              label="API key"
              hint="Encrypted at rest, decrypted only at call time. No endpoint returns it."
            >
              <input
                type="password"
                value={secret}
                autoComplete="off"
                onChange={(ev) => setSecret(ev.target.value)}
                className={MONO_CLASS}
              />
            </FormRow>
          </>
        )}

        <FormRow label={needsPath ? "Model path (required)" : "Model path"}>
          <input
            value={modelPath}
            onChange={(ev) => setModelPath(ev.target.value)}
            placeholder={e.provider === "roboflow" ? "your-project/3" : "leave blank if unused"}
            className={MONO_CLASS}
          />
        </FormRow>
      </div>
    </SidePanel>
  );
}

/* ------------------------------------------------------------------ *
 * Connecting a provider
 * ------------------------------------------------------------------ */

function ConnectPanel({
  provider,
  busy,
  onClose,
  onSave,
}: {
  provider: Provider | null;
  busy: boolean;
  onClose: () => void;
  onSave: (b: any) => void;
}) {
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [secret, setSecret] = useState("");
  const providerName = provider?.name;

  useEffect(() => {
    if (!provider) return;
    setName(`${provider.label.replace(/\s*\(.*\)$/, "")} key`);
    setBaseUrl(provider.baseUrl ?? "");
    setSecret("");
  }, [providerName]);

  const label = provider?.label.replace(/\s*\(.*\)$/, "") ?? "";
  const ready =
    !!provider &&
    name.trim() !== "" &&
    secret.trim() !== "" &&
    (!provider.requiresBaseUrl || baseUrl.trim() !== "");

  return (
    <SidePanel
      open={!!provider}
      onClose={onClose}
      title={`Connect ${label}`}
      subtitle="Your key, your account. Continuum never hosts a model."
      footer={
        <div className="flex justify-end">
          <Primary
            disabled={busy || !ready}
            onClick={() =>
              onSave({
                name: name.trim(),
                provider: provider!.name,
                baseUrl: baseUrl.trim() || undefined,
                secret,
              })
            }
          >
            {busy ? "Connecting…" : "Connect"}
          </Primary>
        </div>
      }
    >
      {provider && (
        <>
          <Field label="What this unlocks">
            Capabilities that take {provider.inputKinds.join(", ")} and run on {label}.
          </Field>

          <FormRow label="Label">
            <input value={name} onChange={(e) => setName(e.target.value)} className={INPUT_CLASS} />
          </FormRow>

          {(provider.requiresBaseUrl || provider.baseUrl) && (
            <FormRow label={`Base URL${provider.requiresBaseUrl ? " (required)" : ""}`}>
              <input
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
                className={MONO_CLASS}
              />
            </FormRow>
          )}

          <FormRow
            label="API key"
            hint="Stored with AES-GCM and decrypted only at call time. Never returned by an endpoint, never written to a log, never sent to a language model."
          >
            <input
              type="password"
              value={secret}
              autoComplete="off"
              onChange={(e) => setSecret(e.target.value)}
              className={MONO_CLASS}
            />
          </FormRow>
        </>
      )}
    </SidePanel>
  );
}

const INPUT_CLASS =
  "mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-[13px] text-slate-200 outline-none transition-colors focus:border-slate-500";
const MONO_CLASS =
  "mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 font-mono text-[12px] text-slate-200 outline-none transition-colors focus:border-slate-500";

function FormRow({
  label,
  hint,
  children,
}: {
  label: string;
  hint?: string;
  children: React.ReactNode;
}) {
  return (
    <label className="mt-3 block">
      <span className="text-[11.5px] text-slate-500">{label}</span>
      {children}
      {hint && <span className="mt-1 block text-[11px] leading-relaxed text-slate-600">{hint}</span>}
    </label>
  );
}
