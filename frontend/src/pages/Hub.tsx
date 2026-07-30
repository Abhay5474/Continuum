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
 * <p>Results come from two kinds of source, and the difference is shown rather
 * than blurred. A <b>template</b> ships with Continuum: always there, but it
 * describes a shape you still have to point at something. A <b>live</b> result
 * comes from a provider's own directory and names a model that exists right now.
 * Mixing them without saying which is which would leave you unable to tell a
 * one-click install from a form you have to finish.
 *
 * <p>A source that cannot answer says why, on screen. "No results" and "your
 * Roboflow key is not connected" send you to completely different places.
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

type SourceState = {
  name: string;
  available: boolean;
  /** LIVE queries a provider's real directory; TEMPLATE is a shipped shape. */
  type?: "LIVE" | "TEMPLATE";
  live?: boolean;
  results?: number;
  /** Why it could not answer. Null when it did. */
  reason?: string | null;
};

export default function Hub({ onInstalled }: { onInstalled: () => void }) {
  const toast = useToast();
  const [query, setQuery] = useState("");
  const [entries, setEntries] = useState<Entry[] | null>(null);
  const [sources, setSources] = useState<SourceState[]>([]);
  const [open, setOpen] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [refreshing, setRefreshing] = useState(false);

  const apply = (r: any) => {
    setEntries(r.entries ?? []);
    setSources(r.sources ?? []);
  };

  const load = useCallback(async (q: string) => {
    try {
      apply(await portal.specialists.catalogue(q));
    } catch {
      setEntries([]);
    }
  }, []);

  useEffect(() => {
    const t = setTimeout(() => void load(query), 180);
    return () => clearTimeout(t);
  }, [query, load]);

  const refresh = async () => {
    setRefreshing(true);
    try {
      apply(await portal.specialists.refreshCatalogue(query));
      toast("Live directories re-queried.", "success");
    } catch {
      toast("Could not refresh the directories.", "error");
    } finally {
      setRefreshing(false);
    }
  };

  const down = sources.filter((s) => !s.available);
  const anyLive = sources.some((s) => s.live);

  return (
    <Plane className="space-y-3 p-4">
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <Micro>Hub</Micro>
        <div className="flex flex-wrap items-center gap-2">
          {sources.map((s) => (
            <SourceChip key={s.name} s={s} />
          ))}
          {anyLive && (
            <button
              onClick={refresh}
              disabled={refreshing}
              title="Live directories are cached for ten minutes. Use this after publishing a new model."
              className="rounded border border-edge px-2 py-0.5 text-[10px] uppercase tracking-wide text-slate-400 hover:border-aurora/60 hover:text-slate-200 disabled:opacity-50"
            >
              {refreshing ? "Refreshing…" : "Refresh"}
            </button>
          )}
        </div>
      </div>

      <input
        value={query}
        onChange={(e) => setQuery(e.target.value)}
        placeholder="What do you need? Try: ocr, detection, invoice, moderation, whisper"
        aria-label="Search the Hub"
        className="w-full rounded-md border border-edge bg-ink/60 px-3 py-2 text-sm text-slate-200 outline-none focus:border-aurora/60"
      />

      {down.length > 0 && (
        // Said out loud with the reason, because an empty result set means
        // nothing without it — and "no key connected" and "unreachable" need
        // completely different things done about them.
        <div className="space-y-1 rounded-md border border-amber-500/30 bg-amber-500/5 px-3 py-2">
          {down.map((s) => (
            <p key={s.name} className="text-xs text-amber-400/90">
              <span className="font-medium">{s.name} is not answering.</span>{" "}
              {s.reason ?? "No reason was given."}
            </p>
          ))}
          <p className="text-xs text-slate-500">
            These results are incomplete, which is not the same as finding nothing.
          </p>
        </div>
      )}

      {entries === null ? (
        <p className="text-sm text-slate-500">Loading…</p>
      ) : entries.length === 0 ? (
        <p className="text-sm text-slate-500">
          Nothing matches "{query}". Templates cover integration shapes — detection,
          classification, OCR, transcription, extraction, moderation. Live directories only
          search when you type something, so an empty box shows the shelf rather than the world.
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
        Template entries describe a task shape, not a specific third-party model — nothing marked
        Template claims a particular model exists, because a one-click add that 404s is worse than
        pasting an id you already have. Entries marked Live came from a provider's own directory
        and name a model that exists now.
      </p>
    </Plane>
  );
}

/** One source and its state, so an empty result set can be read correctly. */
function SourceChip({ s }: { s: SourceState }) {
  const live = !!s.live;
  const tone = !s.available
    ? "border-amber-500/40 text-amber-400/90"
    : live
      ? "border-aurora/50 text-aurora"
      : "border-edge text-slate-500";
  return (
    <span
      title={
        s.reason ??
        (live
          ? "Searches this provider's real directory. Results name models that exist right now."
          : "Ships with Continuum. Entries are task shapes you point at your own endpoint.")
      }
      className={`rounded border px-1.5 py-0.5 text-[10px] uppercase tracking-wide ${tone}`}
    >
      {s.name} · {live ? "live" : "template"}
      {s.available && typeof s.results === "number" && s.results > 0 ? ` · ${s.results}` : ""}
      {!s.available ? " · off" : ""}
    </span>
  );
}

/**
 * What the "path" field means differs per provider, and calling it "Path"
 * everywhere left people pasting a URL into a field that wanted a model name.
 */
const PATH_LABEL: Record<string, string> = {
  deepgram: "Model",
  assemblyai: "Model",
  ocrspace: "Path",
};

const PATH_HINT: Record<string, string> = {
  roboflow: "your-project/3",
  deepgram: "nova-2",
  assemblyai: "leave blank",
  ocrspace: "leave blank",
};

const PATH_NOTE: Record<string, string> = {
  deepgram: "A Deepgram model name, not a URL. nova-2 is the sensible default.",
  assemblyai: "Not used — AssemblyAI picks the model itself. Leave it blank.",
  ocrspace: "Not used — OCR.space has a single endpoint. Leave it blank.",
};

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
          {e.tags?.includes("live") ? (
            <span
              className="rounded border border-aurora/50 px-1 py-0.5 text-[10px] uppercase tracking-wide text-aurora"
              title={`Found in ${e.source}'s directory. This model exists right now.`}
            >
              live · {e.source}
            </span>
          ) : (
            <span
              className="rounded border border-edge px-1 py-0.5 text-[10px] uppercase tracking-wide text-slate-500"
              title="A shape that ships with Continuum. You point it at your own endpoint."
            >
              template
            </span>
          )}
          {e.tags?.includes("free") && (
            <span
              className="rounded border border-emerald-500/40 px-1 py-0.5 text-[10px] uppercase tracking-wide text-emerald-400/90"
              title="This provider has a free tier or free credits, so you can wire it up end to end before paying anyone."
            >
              free tier
            </span>
          )}
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
            <Micro>{needsPath ? "Model path (required)" : PATH_LABEL[e.provider] ?? "Path"}</Micro>
            <input
              value={modelPath}
              onChange={(ev) => setModelPath(ev.target.value)}
              placeholder={PATH_HINT[e.provider] ?? "/predict"}
              className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-2.5 py-1.5 font-mono text-sm text-slate-200 outline-none focus:border-aurora/60"
            />
            {!needsPath && PATH_NOTE[e.provider] && (
              <p className="mt-1 text-xs text-slate-600">{PATH_NOTE[e.provider]}</p>
            )}
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
