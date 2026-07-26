import { useState } from "react";
import { api } from "../api";

const TIERS = ["WORKING", "EPISODIC", "LONG_TERM", "ARCHIVED"];

export default function Memory() {
  const [scope, setScope] = useState("agent-001");
  const [content, setContent] = useState("");
  const [tier, setTier] = useState("EPISODIC");
  const [query, setQuery] = useState("");
  const [entries, setEntries] = useState<any[]>([]);
  const [retrieved, setRetrieved] = useState<any[]>([]);

  const list = async () => setEntries(await api.get<any[]>(`/api/memory/${scope}`));
  const store = async () => {
    if (!content.trim()) return;
    await api.post("/api/memory/store", { scope, tier, content, salience: 0.6 });
    setContent("");
    list();
  };
  const retrieve = async () => {
    setRetrieved(await api.post("/api/memory/retrieve", { scope, query, topK: 5 }));
  };
  const compress = async () => {
    await api.post(`/api/memory/compress?scope=${scope}&useLlm=false`);
    list();
  };

  return (
    <div className="space-y-6">
      <div>
        <h1 className="text-lg font-semibold">Long-Context Memory</h1>
        <p className="text-sm text-slate-400">
          Hierarchical memory (working → episodic → long-term → archived) stored outside the context
          window. Retrieval ranks by relevance, recency and salience; compression summarizes cold memories.
        </p>
      </div>

      <div className="flex flex-wrap items-center gap-2">
        <span className="text-sm text-slate-400">scope</span>
        <input
          value={scope}
          onChange={(e) => setScope(e.target.value)}
          className="min-w-0 flex-1 rounded-md border border-edge bg-ink px-3 py-1.5 text-sm font-mono sm:flex-none"
        />
        <button onClick={list} className="rounded-md border border-edge px-3 py-1.5 text-sm hover:bg-edge">
          Load
        </button>
        <button onClick={compress} className="rounded-md border border-edge px-3 py-1.5 text-sm hover:bg-edge">
          Compress episodic
        </button>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <div className="rounded-lg border border-edge bg-panel p-4">
          <div className="font-medium">Store memory</div>
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder="memory content…"
            className="mt-2 h-20 w-full rounded-md border border-edge bg-ink p-2 text-sm"
          />
          <div className="mt-2 flex gap-2">
            <select
              value={tier}
              onChange={(e) => setTier(e.target.value)}
              className="rounded-md border border-edge bg-ink px-3 py-1.5 text-sm"
            >
              {TIERS.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
            <button onClick={store} className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white">
              Store
            </button>
          </div>
        </div>

        <div className="rounded-lg border border-edge bg-panel p-4">
          <div className="font-medium">Retrieve (relevance-ranked)</div>
          <div className="mt-2 flex gap-2">
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="query…"
              className="flex-1 rounded-md border border-edge bg-ink px-3 py-1.5 text-sm"
            />
            <button onClick={retrieve} className="rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white">
              Retrieve
            </button>
          </div>
          <div className="mt-3 space-y-1">
            {retrieved.map((m) => (
              <div key={m.id} className="text-xs">
                <span className="text-emerald-300">{m.relevance?.toFixed(3)}</span>{" "}
                <span className="text-slate-400">[{m.tier}]</span> {m.content?.slice(0, 80)}
              </div>
            ))}
          </div>
        </div>
      </div>

      <div className="rounded-lg border border-edge bg-panel">
        <div className="border-b border-edge px-4 py-2 font-medium">All memories ({entries.length})</div>
        <div className="divide-y divide-edge text-sm">
          {entries.map((e) => (
            <div key={e.id} className="flex items-center gap-3 px-4 py-2">
              <span className="rounded bg-slate-500/20 px-2 py-0.5 text-xs">{e.tier}</span>
              <span className="text-xs text-slate-400">sal {e.salience?.toFixed(1)}</span>
              <span className="text-xs text-slate-400">acc {e.accessCount}</span>
              <span className="flex-1 truncate">{e.content}</span>
            </div>
          ))}
          {entries.length === 0 && <div className="px-4 py-3 text-xs text-slate-500">no memories — load a scope</div>}
        </div>
      </div>
    </div>
  );
}
