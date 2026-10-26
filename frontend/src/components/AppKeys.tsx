import { useState } from "react";
import { portal, selectApp } from "../api";
import { appsChanged, useApps, useSelectedApp } from "../system/apps";
import { useToast } from "./ui";

type Key = {
  id: number;
  prefix: string;
  label: string | null;
  appId: number | null;
  appName: string | null;
  active: boolean;
  createdAt: string;
  lastUsedAt: string | null;
};

const BUTTON =
  "inline-flex items-center rounded-[var(--r-md)] border border-[rgb(var(--card-edge))] px-2.5 py-1 text-[12px] font-medium text-slate-300 transition-colors hover:border-slate-500/60 hover:bg-[color:rgb(var(--card-hover))]";

/**
 * Continuum API keys, grouped by the app each one speaks for.
 *
 * <p>An app is what a person thinks in — "the shop", "the vet chat" — and a
 * key is how that app proves who it is. So the name lives on the app, a key is
 * added to an app (to replace a leaked one without losing the app's history),
 * and the console can be pointed at one app from here.
 */
export default function AppKeys({
  keys,
  onIssued,
  onChanged,
}: {
  keys: Key[];
  onIssued: (apiKey: string) => void;
  onChanged: () => void;
}) {
  const { apps } = useApps();
  const inView = useSelectedApp();
  const toast = useToast();
  const [creating, setCreating] = useState(false);
  const [name, setName] = useState("");
  const [editing, setEditing] = useState<number | null>(null);
  const [draft, setDraft] = useState("");
  const [busy, setBusy] = useState(false);

  const changed = () => {
    appsChanged();
    onChanged();
  };

  const issue = async (appId?: number) => {
    setBusy(true);
    try {
      const r = await portal.issueKey(appId ? undefined : name.trim() || undefined, appId);
      onIssued(r.apiKey);
      toast(appId ? "Key added to the app ✓" : "App created with its first key ✓", "success");
      setCreating(false);
      setName("");
      changed();
    } catch (e: any) {
      toast(e.message ?? "Could not issue key", "error");
    } finally {
      setBusy(false);
    }
  };

  const rename = async (appId: number) => {
    if (!draft.trim()) return;
    setBusy(true);
    try {
      await portal.renameApp(appId, draft.trim());
      setEditing(null);
      changed();
    } catch (e: any) {
      toast(e.message ?? "Could not rename", "error");
    } finally {
      setBusy(false);
    }
  };

  const revoke = async (k: Key) => {
    if (!confirm(`Revoke ${k.prefix}…? Anything using it stops working immediately. The app's data stays.`)) return;
    try {
      await portal.revokeKey(k.id);
      toast("Key revoked", "success");
      changed();
    } catch (e: any) {
      toast(e.message ?? "Could not revoke key", "error");
    }
  };

  const groups = (apps ?? []).map((a) => ({ app: a, keys: keys.filter((k) => k.appId === a.id) }));
  const loose = keys.filter((k) => k.appId == null || !(apps ?? []).some((a) => a.id === k.appId));

  return (
    <div id="keys" className="scroll-mt-24">
      <div className="flex flex-wrap items-center gap-2">
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Apps &amp; API keys</h2>
        <span className="text-[11.5px] text-slate-500">One app per application that calls Continuum. Its data is kept apart from your other apps'.</span>
        <button onClick={() => setCreating((c) => !c)} className={`${BUTTON} ml-auto`}>
          {creating ? "Cancel" : "Issue new key"}
        </button>
      </div>

      {creating && (
        <div className="mt-2 flex flex-wrap items-center gap-2 rounded-xl border border-card-edge bg-card p-3 shadow-card animate-fade-up">
          <input
            autoFocus
            value={name}
            maxLength={120}
            onChange={(e) => setName(e.target.value)}
            onKeyDown={(e) => e.key === "Enter" && !busy && issue()}
            placeholder="Name the app, e.g. Shopping app"
            aria-label="App name"
            className="field min-w-0 flex-1"
          />
          <button
            onClick={() => issue()}
            disabled={busy}
            className="rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-3 py-1.5 text-[12.5px] font-semibold text-white disabled:opacity-60"
          >
            Create app &amp; key
          </button>
          <span className="w-full text-[11.5px] text-slate-500">
            Replacing a key for an app you already have? Use <b>Add key</b> on that app instead, so it keeps its history.
          </span>
        </div>
      )}

      <div className="mt-2 space-y-2">
        {groups.map(({ app, keys: ks }) => {
          const active = ks.some((k) => k.active);
          return (
            <div
              key={app.id}
              className={`rounded-xl border border-card-edge bg-card p-3 shadow-card ${active ? "" : "opacity-70"}`}
            >
              <div className="flex flex-wrap items-center gap-2">
                {editing === app.id ? (
                  <>
                    <input
                      autoFocus
                      value={draft}
                      maxLength={120}
                      onChange={(e) => setDraft(e.target.value)}
                      onKeyDown={(e) => {
                        if (e.key === "Enter") rename(app.id);
                        if (e.key === "Escape") setEditing(null);
                      }}
                      aria-label="New app name"
                      className="field h-8 min-w-0 flex-1 text-[13px]"
                    />
                    <button onClick={() => rename(app.id)} disabled={busy || !draft.trim()} className={BUTTON}>Save</button>
                    <button onClick={() => setEditing(null)} className={BUTTON}>Cancel</button>
                  </>
                ) : (
                  <>
                    <span className="text-[13.5px] font-semibold text-slate-100">{app.name}</span>
                    <button
                      onClick={() => {
                        setEditing(app.id);
                        setDraft(app.name);
                      }}
                      aria-label={`Rename ${app.name}`}
                      data-tip="Rename"
                      className="rounded-md p-1 text-slate-500 transition-colors hover:bg-slate-500/[0.1] hover:text-slate-200"
                    >
                      <svg viewBox="0 0 16 16" className="h-3.5 w-3.5" fill="none" stroke="currentColor" strokeWidth="1.5">
                        <path d="M10.5 2.5l3 3L6 13H3v-3l7.5-7.5z" strokeLinejoin="round" />
                      </svg>
                    </button>
                    {inView === String(app.id) && (
                      <span className="rounded-full px-2 py-px text-[10px] font-semibold uppercase tracking-wider"
                        style={{ background: "var(--accent-wash)", color: "var(--accent-ink)" }}>
                        In view
                      </span>
                    )}
                    <div className="ml-auto flex gap-1.5">
                      {inView !== String(app.id) && (
                        <button onClick={() => selectApp(String(app.id))} className={BUTTON}
                          data-tip="Every console page shows this app's data">
                          Show its data
                        </button>
                      )}
                      <button onClick={() => issue(app.id)} disabled={busy} className={BUTTON}
                        data-tip="Another key for this app, sharing its history — to replace a leaked key">
                        Add key
                      </button>
                    </div>
                  </>
                )}
              </div>
              <div className="mt-2 space-y-1">
                {ks.map((k) => <KeyRow key={k.id} k={k} onRevoke={() => revoke(k)} />)}
                {ks.length === 0 && <div className="text-xs text-slate-500">no keys</div>}
              </div>
            </div>
          );
        })}
        {loose.map((k) => <KeyRow key={k.id} k={k} onRevoke={() => revoke(k)} />)}
        {keys.length === 0 && !creating && (
          <div className="text-xs text-slate-500">No apps yet. Issue a key to create your first one.</div>
        )}
      </div>
    </div>
  );
}

function KeyRow({ k, onRevoke }: { k: Key; onRevoke: () => void }) {
  return (
    <div className="flex items-center gap-2 text-xs">
      <span className="font-mono text-slate-300">{k.prefix}…</span>
      <span className={k.active ? "text-emerald-300" : "text-rose-300"}>{k.active ? "active" : "revoked"}</span>
      <span className="text-slate-500">
        {k.lastUsedAt ? `last used ${new Date(k.lastUsedAt).toLocaleString()}` : "never used"}
      </span>
      {k.active && (
        <button onClick={onRevoke} className="ml-auto rounded border border-edge px-2 py-0.5 text-rose-300 transition-colors hover:bg-rose-500/10">
          Revoke
        </button>
      )}
    </div>
  );
}
