import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Micro, Plane } from "../system/primitives";
import { useToast } from "../components/ui";

/**
 * The Hub — find something to plug in, and add it in one step.
 *
 * <p>Adding a specialist by hand means knowing the provider, base URL, auth
 * style, model path, a sensible confidence threshold and a timeout, and then
 * remembering to probe it. This is the same thing with the parts Continuum
 * already knows filled in, and the probe run for you — so what comes back is
 * either a working integration or the exact reason it isn't.
 *
 * <p>Entries are templates by task shape rather than a directory of specific
 * third-party models. That is a real limitation and it is stated on screen: a
 * catalogue that hard-coded model ids nobody had probed would give you a
 * one-click add that 404s.
 */

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
  /** Whether this kind of tool produces confidences at all. */
  scored: boolean;
  suggestedConfidence: number;
  tags: string[];
  needs: string[];
  note: string;
  source: string;
};

type SourceState = { name: string; available: boolean };

export default function Hub({ onInstalled }: { onInstalled: () => void }) {
  const toast = useToast();
  const [query, setQuery] = useState("");
  const [entries, setEntries] = useState<Entry[] | null>(null);
  const [sources, setSources] = useState<SourceState[]>([]);
  const [open, setOpen] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  const load = useCallback(async (q: string) => {
    try {
      const r = await portal.specialists.catalogue(q);
      setEntries(r.entries);
      setSources(r.sources ?? []);
    } catch {
      setEntries([]);
    }
  }, []);

  useEffect(() => {
    const t = setTimeout(() => void load(query), 180);
    return () => clearTimeout(t);
  }, [query, load]);

  const down = sources.filter((s) => !s.available);

  return (
    <Plane className="space-y-3 p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <Micro>Hub</Micro>
        <span className="text-xs text-slate-600">
          {sources.map((s) => s.name).join(", ") || "no sources"}
        </span>
      </div>

      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="What do you need? Try: ocr, detection, invoice, moderation, whisper"
        aria-label="Search the Hub"
        className="w-full rounded-md border border-edge bg-ink/60 px-3 py-2 text-sm text-slate-200 outline-none focus:border-aurora/60"
      />

      {down.length > 0 && (
        // Said out loud, because an empty result set means nothing without it.
        <p className="text-xs text-amber-400/90">
          {down.map((s) => s.name).join(", ")} could not be reached, so these results are
          incomplete — this is not the same as finding nothing.
        </p>
      )}

      {entries === null ? (
        <p className="text-sm text-slate-500">Loading…</p>
      ) : entries.length === 0 ? (
        <p className="text-sm text-slate-500">
          Nothing matches "{query}". The Hub covers integration shapes — detection,
          classification, OCR, transcription, extraction, moderation — rather than a directory of
          specific models.
        </p>
      ) : (
        <div className="space-y-2">
          {entries.map((e) => (
            <EntryCard
              key={e.id}
              e={e}
              open={open === e.id}
              busy={busy}
              onToggle={() => setOpen(open === e.id ? null : e.id)}
              onInstall={async (body) => {
                setBusy(true);
                try {
                  const r = await portal.specialists.install(e.id, body);
                  const status = r.specialist?.status;
                  toast(
                    status === "READY"
                      ? `${body.name} is ready — probed and understood.`
                      : `${body.name} was created, but the probe came back ${status}.`,
                    status === "READY" ? "success" : "error"
                  );
                  setOpen(null);
                  onInstalled();
                } catch (err: any) {
                  toast(err?.message ?? "That did not work.", "error");
                } finally {
                  setBusy(false);
                }
              }}
            />
          ))}
        </div>
      )}

      <p className="text-xs text-slate-600">
        Entries are templates by task, not a directory of specific models — nothing here claims a
        particular third-party model exists, because a one-click add that 404s is worse than
        pasting an id you already have.
      </p>
    </Plane>
  );
}

function EntryCard({
  e,
  open,
  busy,
  onToggle,
  onInstall,
}: {
  e: Entry;
  open: boolean;
  busy: boolean;
  onToggle: () => void;
  onInstall: (b: {
    name: string; baseUrl?: string; modelPath?: string; minConfidence?: number;
    connectionId?: number; secret?: string;
  }) => void;
}) {
  const [name, setName] = useState(e.title);
  const [baseUrl, setBaseUrl] = useState(e.baseUrl);
  const [modelPath, setModelPath] = useState(e.modelPath);
  const [secret, setSecret] = useState("");
  const [connectionId, setConnectionId] = useState<number | "">("");
  const [reusable, setReusable] = useState<any[]>([]);

  useEffect(() => {
    if (!open) return;
    portal.specialists.reusableConnections(e.id).then(setReusable).catch(() => setReusable([]));
  }, [open, e.id]);

  const reusing = connectionId !== "";
  const needsPath = e.needs.includes("modelPath");
  const needsBase = e.needs.includes("baseUrl");
  const ready =
    name.trim() !== "" &&
    (!needsPath || modelPath.trim() !== "") &&
    (reusing || (secret.trim() !== "" && (!needsBase || baseUrl.trim() !== "")));

  return (
    <div className="rounded-md border border-edge/60">
      <button onClick={onToggle} aria-expanded={open} className="w-full px-3 py-2.5 text-left">
        <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1">
          <span className="text-sm font-medium text-slate-200">{e.title}</span>
          <span className="micro">{e.inputKind} in</span>
          <span className="micro text-aurora">{e.toolKindLabel}</span>
          {e.scored ? (
            <span className="readout text-[10px] text-slate-500">
              suggests {e.suggestedConfidence.toFixed(2)}
            </span>
          ) : (
            <span
              className="readout text-[10px] text-slate-500"
              title="This kind of tool returns content rather than scored detections, so a confidence threshold does not apply to it."
            >
              no confidence
            </span>
          )}
        </div>
        <p className="mt-0.5 text-xs text-slate-500">{e.description}</p>
      </button>

      {open && (
        <div className="space-y-3 border-t border-edge/60 px-3 py-3">
          <p className="text-xs text-slate-500">{e.note}</p>

          <label className="block">
            <Micro>Name it</Micro>
            <input
              value={name}
              onChange={(ev) => setName(ev.target.value)}
              className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
            />
          </label>

          {reusable.length > 0 && (
            <label className="block">
              <Micro>Credential</Micro>
              <select
                value={connectionId}
                onChange={(ev) =>
                  setConnectionId(ev.target.value === "" ? "" : Number(ev.target.value))
                }
                className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
              >
                <option value="">Use a new one…</option>
                {reusable.map((c) => (
                  <option key={c.id} value={c.id}>
                    Reuse "{c.name}"
                  </option>
                ))}
              </select>
            </label>
          )}

          {!reusing && (
            <>
              {needsBase && (
                <label className="block">
                  <Micro>Base URL</Micro>
                  <input
                    value={baseUrl}
                    onChange={(ev) => setBaseUrl(ev.target.value)}
                    placeholder="https://models.your-company.internal"
                    className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 text-sm text-slate-200 outline-none focus:border-aurora/60"
                  />
                </label>
              )}
              <label className="block">
                <Micro>API key</Micro>
                <input
                  type="password"
                  value={secret}
                  onChange={(ev) => setSecret(ev.target.value)}
                  autoComplete="off"
                  className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 font-mono text-sm text-slate-200 outline-none focus:border-aurora/60"
                />
                <p className="mt-1 text-xs text-slate-600">
                  Encrypted at rest and decrypted only at call time. No endpoint returns it —
                  not to this console, not to a log, not in an error.
                </p>
              </label>
            </>
          )}

          <label className="block">
            <Micro>{needsPath ? "Model path (required)" : "Path"}</Micro>
            <input
              value={modelPath}
              onChange={(ev) => setModelPath(ev.target.value)}
              placeholder={e.provider === "roboflow" ? "your-project/3" : "/predict"}
              className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 font-mono text-sm text-slate-200 outline-none focus:border-aurora/60"
            />
          </label>

          <button
            disabled={busy || !ready}
            onClick={() =>
              onInstall({
                name: name.trim(),
                baseUrl: baseUrl.trim() || undefined,
                modelPath: modelPath.trim() || undefined,
                connectionId: reusing ? (connectionId as number) : undefined,
                secret: reusing ? undefined : secret,
              })
            }
            className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
          >
            {busy ? "Adding…" : "Add and probe"}
          </button>
          <p className="text-xs text-slate-600">
            Probed straight away rather than left to you — an unprobed specialist cannot go into a
            pipeline anyway, so deferring it only means finding out later that the credential was
            wrong.
          </p>
        </div>
      )}
    </div>
  );
}
