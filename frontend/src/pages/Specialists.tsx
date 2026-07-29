import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Micro, PageHeader, Plane, Readout } from "../system/primitives";
import { ErrorState, SkeletonRows, useToast } from "../components/ui";
import Hub from "./Hub";
import { dateTimeOf } from "../system/time";

/**
 * Specialist models — the layer that calls something other than the LLM.
 *
 * <p>The probe view is the reason this page exists. Nobody can tell a developer
 * the shape of their own model's response reliably, so Continuum sends one real
 * request and shows two things side by side: what the endpoint actually
 * returned, and what Continuum understood from it. A developer who can see both
 * knows whether the integration works. A developer reading a description of it
 * is guessing.
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

/**
 * One thing a tool observed. Confidence is nullable on purpose: an OCR engine
 * has no opinion about how sure it is, and rendering 0.00 there would read as
 * "certainly not".
 */
type EvidenceItem = {
  kind: "DETECTION" | "CLASSIFICATION" | "TEXT" | "FIELD" | "ROW" | "NOTE";
  label?: string;
  confidence: number | null;
  scored: boolean;
  text?: string;
  attributes?: Record<string, unknown>;
};

export default function Specialists() {
  const toast = useToast();
  const [providers, setProviders] = useState<Provider[]>([]);
  const [connections, setConnections] = useState<Connection[] | null>(null);
  const [specialists, setSpecialists] = useState<Specialist[] | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [open, setOpen] = useState<number | null>(null);

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
    load();
  }, [load]);

  const run = async (fn: () => Promise<any>, message: string) => {
    setBusy(true);
    try {
      await fn();
      await load();
      toast(message);
    } catch (e: any) {
      toast(e?.message ?? "That did not work", "error");
    } finally {
      setBusy(false);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  const ready = (specialists ?? []).filter((s) => s.status === "READY").length;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Specialists"
        subtitle="Call a smaller, sharper model before the big one. Continuum shapes its findings into context the language model can reason about."
      />

      <Plane className="grid gap-6 p-5 sm:grid-cols-4">
        <Readout label="Connections" value={connections?.length ?? 0} />
        <Readout
          label="Verified"
          value={(connections ?? []).filter((c) => c.status === "VERIFIED").length}
          state={(connections ?? []).some((c) => c.status === "FAILING") ? "critical" : "idle"}
        />
        <Readout label="Specialists" value={specialists?.length ?? 0} />
        <Readout label="Ready" value={ready} state={ready > 0 ? "healthy" : "idle"} />
      </Plane>

      {/* The Hub sits above the manual forms on purpose: adding by hand means
          knowing six things, and most people should not have to. */}
      <Hub onInstalled={load} />

      <ConnectionsSection
        providers={providers}
        connections={connections}
        busy={busy}
        onAdd={(body) => run(() => portal.specialists.addConnection(body), "Connection added")}
        onDelete={(id) => run(() => portal.specialists.deleteConnection(id), "Connection removed")}
      />

      <SpecialistsSection
        connections={connections ?? []}
        specialists={specialists}
        busy={busy}
        open={open}
        onToggle={(id) => setOpen(open === id ? null : id)}
        onAdd={(body) => run(() => portal.specialists.add(body), "Specialist registered — probe it next")}
        onProbe={(id) => run(() => portal.specialists.probe(id), "Probe sent")}
        onDelete={(id) => run(() => portal.specialists.remove(id), "Specialist removed")}
      />
    </div>
  );
}

/* ---------------------------------------------------------------- connections */

function ConnectionsSection({
  providers,
  connections,
  busy,
  onAdd,
  onDelete,
}: {
  providers: Provider[];
  connections: Connection[] | null;
  busy: boolean;
  onAdd: (b: any) => void;
  onDelete: (id: number) => void;
}) {
  const [adding, setAdding] = useState(false);
  const [provider, setProvider] = useState("roboflow");
  const [name, setName] = useState("");
  const [baseUrl, setBaseUrl] = useState("");
  const [secret, setSecret] = useState("");

  const selected = providers.find((p) => p.name === provider);

  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <Micro>Connections · your credentials, encrypted and never shown again</Micro>
        <button
          onClick={() => setAdding((a) => !a)}
          className="rounded-md border border-edge px-2.5 py-1 text-xs text-slate-300 hover:border-aurora/50"
        >
          {adding ? "Cancel" : "Add connection"}
        </button>
      </div>

      {adding && (
        <Plane className="space-y-3 p-5">
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="min-w-0">
              <span className="micro">Provider</span>
              <select
                value={provider}
                onChange={(e) => setProvider(e.target.value)}
                className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
              >
                {providers.map((p) => (
                  <option key={p.name} value={p.name}>
                    {p.label}
                  </option>
                ))}
              </select>
            </label>
            <label className="min-w-0">
              <span className="micro">Name it</span>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Vet clinic Roboflow"
                className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
              />
            </label>
          </div>

          {selected?.requiresBaseUrl && (
            <label className="block min-w-0">
              <span className="micro">Endpoint</span>
              <input
                value={baseUrl}
                onChange={(e) => setBaseUrl(e.target.value)}
                placeholder="https://models.example.com"
                className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
              />
            </label>
          )}

          <label className="block min-w-0">
            <span className="micro">API key</span>
            <input
              type="password"
              value={secret}
              onChange={(e) => setSecret(e.target.value)}
              placeholder="Your own key for this provider"
              className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
            />
            <p className="mt-1 text-xs text-slate-600">
              Encrypted before it is stored and decrypted only when a call is made. It is never
              returned to this page, logged, or included in an error.
            </p>
          </label>

          <button
            disabled={busy || !name.trim() || (selected?.requiresBaseUrl && !baseUrl.trim())}
            onClick={() => {
              onAdd({ name, provider, baseUrl: baseUrl || undefined, secret: secret || undefined });
              setName("");
              setBaseUrl("");
              setSecret("");
              setAdding(false);
            }}
            className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
          >
            Add connection
          </button>
        </Plane>
      )}

      {connections === null ? (
        <SkeletonRows rows={2} />
      ) : connections.length === 0 ? (
        <Plane className="p-6 text-center text-sm text-slate-500">
          No connections yet. Add one to point Continuum at a model you already have access to.
        </Plane>
      ) : (
        <Plane className="divide-y divide-edge/40">
          {connections.map((c) => (
            <div key={c.id} className="flex flex-wrap items-center gap-x-4 gap-y-1 px-4 py-3">
              <span
                className={`h-2 w-2 shrink-0 rounded-full ${
                  c.status === "VERIFIED"
                    ? "bg-emerald-400"
                    : c.status === "FAILING"
                      ? "bg-rose-400"
                      : "bg-slate-600"
                }`}
              />
              <div className="min-w-0 flex-1">
                <div className="text-sm text-slate-200">{c.name}</div>
                <div className="truncate text-xs text-slate-500">
                  {c.provider} · {c.baseUrl} · {c.authStyle.toLowerCase()} auth
                  {c.hasCredential ? "" : " · no credential"}
                </div>
                {c.lastError && <div className="mt-0.5 text-xs text-rose-400">{c.lastError}</div>}
              </div>
              <span className="micro shrink-0">{c.status}</span>
              <button
                disabled={busy}
                onClick={() => onDelete(c.id)}
                className="shrink-0 rounded border border-edge px-2 py-0.5 text-xs text-slate-400 hover:border-rose-500/50 hover:text-rose-300 disabled:opacity-40"
              >
                Remove
              </button>
            </div>
          ))}
        </Plane>
      )}
    </section>
  );
}

/* ---------------------------------------------------------------- specialists */

function SpecialistsSection({
  connections,
  specialists,
  busy,
  open,
  onToggle,
  onAdd,
  onProbe,
  onDelete,
}: {
  connections: Connection[];
  specialists: Specialist[] | null;
  busy: boolean;
  open: number | null;
  onToggle: (id: number) => void;
  onAdd: (b: any) => void;
  onProbe: (id: number) => void;
  onDelete: (id: number) => void;
}) {
  const [adding, setAdding] = useState(false);
  const [connectionId, setConnectionId] = useState<number | "">("");
  const [name, setName] = useState("");
  const [modelPath, setModelPath] = useState("");
  const [minConfidence, setMinConfidence] = useState(0.3);

  return (
    <section className="space-y-3">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <Micro>Specialists · a model, and what a probe learned about it</Micro>
        <button
          disabled={connections.length === 0}
          onClick={() => setAdding((a) => !a)}
          className="rounded-md border border-edge px-2.5 py-1 text-xs text-slate-300 hover:border-aurora/50 disabled:opacity-40"
        >
          {adding ? "Cancel" : "Add specialist"}
        </button>
      </div>

      {adding && (
        <Plane className="space-y-3 p-5">
          <div className="grid gap-3 sm:grid-cols-2">
            <label className="min-w-0">
              <span className="micro">Connection</span>
              <select
                value={connectionId}
                onChange={(e) => setConnectionId(Number(e.target.value))}
                className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
              >
                <option value="">Choose…</option>
                {connections.map((c) => (
                  <option key={c.id} value={c.id}>
                    {c.name}
                  </option>
                ))}
              </select>
            </label>
            <label className="min-w-0">
              <span className="micro">Name it</span>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder="Animal injury detector"
                className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
              />
            </label>
            <label className="min-w-0">
              <span className="micro">Model path</span>
              <input
                value={modelPath}
                onChange={(e) => setModelPath(e.target.value)}
                placeholder="animal-injury/3"
                className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
              />
            </label>
            <label className="min-w-0">
              <span className="micro">Ignore findings below</span>
              <select
                value={minConfidence}
                onChange={(e) => setMinConfidence(Number(e.target.value))}
                className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-sm text-slate-200"
              >
                {[0.1, 0.3, 0.5, 0.7].map((v) => (
                  <option key={v} value={v}>
                    {v.toFixed(1)}
                  </option>
                ))}
              </select>
              <p className="mt-1 text-xs text-slate-600">
                Weaker findings are withheld entirely rather than passed on with a caveat — a
                detection nobody trusts should not reach the model at all.
              </p>
            </label>
          </div>
          <button
            disabled={busy || !connectionId || !name.trim() || !modelPath.trim()}
            onClick={() => {
              onAdd({ connectionId, name, modelPath, minConfidence });
              setName("");
              setModelPath("");
              setAdding(false);
            }}
            className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
          >
            Register specialist
          </button>
        </Plane>
      )}

      {specialists === null ? (
        <SkeletonRows rows={2} />
      ) : specialists.length === 0 ? (
        <Plane className="p-6 text-center text-sm text-slate-500">
          No specialists yet. Register one against a connection, then probe it — Continuum sends a
          real request and shows you exactly what comes back.
        </Plane>
      ) : (
        <div className="space-y-2">
          {specialists.map((s) => (
            <SpecialistCard
              key={s.id}
              s={s}
              busy={busy}
              open={open === s.id}
              onToggle={() => onToggle(s.id)}
              onProbe={() => onProbe(s.id)}
              onDelete={() => onDelete(s.id)}
            />
          ))}
        </div>
      )}
    </section>
  );
}

const STATUS_NOTE: Record<Specialist["status"], string> = {
  DRAFT: "Never probed. Cannot be used until one real request has succeeded.",
  READY: "Probed, and Continuum understood the response.",
  UNPARSEABLE: "The endpoint answered, so the credential and path are right — but nothing could be read from the response.",
  FAILED: "The probe did not reach a usable answer.",
};

function SpecialistCard({
  s,
  busy,
  open,
  onToggle,
  onProbe,
  onDelete,
}: {
  s: Specialist;
  busy: boolean;
  open: boolean;
  onToggle: () => void;
  onProbe: () => void;
  onDelete: () => void;
}) {
  const dot =
    s.status === "READY"
      ? "bg-emerald-400"
      : s.status === "FAILED"
        ? "bg-rose-400"
        : s.status === "UNPARSEABLE"
          ? "bg-amber-400"
          : "bg-slate-600";

  return (
    <Plane className="overflow-hidden">
      <div className="flex flex-wrap items-center gap-x-4 gap-y-2 px-4 py-3">
        <span className={`h-2 w-2 shrink-0 rounded-full ${dot}`} />
        <button onClick={onToggle} aria-expanded={open} className="min-w-0 flex-1 text-left">
          <div className="text-sm font-medium text-slate-200">{s.name}</div>
          <div className="truncate text-xs text-slate-500">
            {s.modelPath} · {s.inputKind} in · {s.toolKindLabel}
            {s.scored
              ? ` · ignores below ${s.minConfidence.toFixed(2)}`
              : " · returns content, so no confidence threshold applies"}
            {s.probedAt ? ` · probed ${dateTimeOf(s.probedAt)}` : ""}
          </div>
        </button>
        <span className="micro shrink-0">{s.status}</span>
        <button
          disabled={busy}
          onClick={onProbe}
          className="shrink-0 rounded-md border border-edge px-2.5 py-1 text-xs text-slate-300 hover:border-aurora/50 disabled:opacity-40"
        >
          {s.probedAt ? "Re-probe" : "Probe"}
        </button>
        <button
          disabled={busy}
          onClick={onDelete}
          className="shrink-0 rounded border border-edge px-2 py-0.5 text-xs text-slate-400 hover:border-rose-500/50 hover:text-rose-300 disabled:opacity-40"
        >
          Remove
        </button>
      </div>

      {open && (
        <div className="border-t border-edge/60 px-4 py-3">
          <p className="text-xs text-slate-500">{STATUS_NOTE[s.status]}</p>

          {s.probeError && <p className="mt-2 text-xs text-rose-400">{s.probeError}</p>}

          {s.probedAt && (
            <>
              <div className="mt-3 grid gap-3 lg:grid-cols-2">
                <div className="min-w-0">
                  <div className="micro mb-1.5">
                    What your model returned{s.probeStatus ? ` · HTTP ${s.probeStatus}` : ""}
                    {s.probeMs != null ? ` · ${s.probeMs}ms` : ""}
                  </div>
                  <pre className="well max-h-56 overflow-auto p-3 text-[11px] leading-relaxed text-slate-400">
                    {s.probeResponse ?? "—"}
                  </pre>
                </div>
                <div className="min-w-0">
                  <div className="micro mb-1.5 text-aurora">What Continuum understood</div>
                  {s.probeFindings.length === 0 ? (
                    <div className="well p-3 text-[11px] text-slate-500">
                      Nothing parseable. The adapter did not recognise this shape — the response is
                      shown on the left so you can see what it expected to find.
                    </div>
                  ) : (
                    <div className="well space-y-1.5 p-3">
                      {s.probeFindings.map((e, i) => (
                        <EvidenceRow key={i} e={e} />
                      ))}
                    </div>
                  )}
                </div>
              </div>
              <p className="mt-2 text-xs text-slate-600">
                Both sides are kept because a description of an integration is not evidence it works.
                The left is your endpoint's own words; the right is the shape every downstream step
                will actually see.
              </p>
            </>
          )}
        </div>
      )}
    </Plane>
  );
}

/**
 * One piece of evidence, drawn according to what it actually is.
 *
 * <p>A confidence bar is only drawn for evidence that carries a confidence.
 * Drawing an empty bar for recovered text would say "zero percent sure", which
 * is the opposite of "no measurement was taken".
 */
function EvidenceRow({ e }: { e: EvidenceItem }) {
  if (e.kind === "TEXT") {
    return (
      <div className="min-w-0">
        <div className="flex items-baseline gap-2">
          <span className="micro text-sky-400">text</span>
          {e.label && <span className="micro">{e.label}</span>}
          <span className="micro text-slate-500">no confidence — extracted content</span>
        </div>
        <p className="mt-1 max-h-32 overflow-auto whitespace-pre-wrap break-words font-mono text-[11px] leading-relaxed text-slate-300">
          {e.text}
        </p>
      </div>
    );
  }

  if (e.kind === "FIELD") {
    return (
      <div className="flex min-w-0 items-baseline gap-2">
        <span className="micro text-violet-400">field</span>
        <span className="shrink-0 text-xs text-slate-400">{e.label}</span>
        <span className="min-w-0 flex-1 truncate font-mono text-xs text-slate-300">{e.text}</span>
      </div>
    );
  }

  if (e.kind === "ROW") {
    return (
      <div className="flex min-w-0 items-baseline gap-2">
        <span className="micro text-amber-400">row</span>
        <span className="min-w-0 flex-1 truncate font-mono text-[11px] text-slate-300">
          {JSON.stringify(e.attributes ?? {})}
        </span>
      </div>
    );
  }

  if (e.kind === "NOTE") {
    return (
      <div className="flex min-w-0 items-baseline gap-2">
        <span className="micro">note</span>
        <span className="min-w-0 flex-1 text-xs text-slate-400">{e.text}</span>
      </div>
    );
  }

  // DETECTION / CLASSIFICATION — the original shape, unchanged when scored.
  const c = e.confidence;
  return (
    <div className="flex min-w-0 items-center gap-3">
      <span className="min-w-0 flex-1 truncate text-xs text-slate-300">{e.label}</span>
      {c === null ? (
        <span className="micro shrink-0 text-slate-500">unscored</span>
      ) : (
        <>
          <div className="h-1.5 w-20 shrink-0 overflow-hidden rounded-full bg-edge/60">
            <div
              className={`h-full rounded-full ${
                c >= 0.7 ? "bg-emerald-500/70" : c >= 0.4 ? "bg-amber-500/70" : "bg-rose-500/70"
              }`}
              style={{ width: `${Math.max(3, c * 100)}%` }}
            />
          </div>
          <span className="readout w-10 shrink-0 text-right text-xs text-slate-400">
            {c.toFixed(2)}
          </span>
        </>
      )}
    </div>
  );
}
