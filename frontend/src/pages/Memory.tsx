import { useState } from "react";
import { api } from "../api";
import { Card, CardHead, Empty, Primary } from "../system/hub";
import { PageHeader } from "../system/primitives";
import { Select } from "../system/controls";

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
    <div className="space-y-8">
      <PageHeader
        glyph="layers"
        title="Memory"
        subtitle="Working, episodic, long-term and archived — held outside the context window and retrieved by relevance, recency and salience."
      />

      <div className="plane flex flex-wrap items-center gap-2 p-4">
        <span className="micro">Scope</span>
        <input
          value={scope}
          onChange={(e) => setScope(e.target.value)}
          className="min-w-0 flex-1 font-mono sm:flex-none field"
        />
        <button onClick={list} className="rounded-md border border-edge px-3 py-1.5 text-sm hover:bg-edge">
          Load
        </button>
        <button onClick={compress} className="rounded-md border border-edge px-3 py-1.5 text-sm hover:bg-edge">
          Compress episodic
        </button>
      </div>

      <div className="grid gap-4 lg:grid-cols-2">
        <Card>
          <CardHead glyph="layers" tone="violet" title="Store memory"
                    sub="Write one memory into a tier" />
          <textarea
            value={content}
            onChange={(e) => setContent(e.target.value)}
            placeholder="memory content…"
            className="mt-2 h-20 w-full field"
          />
          <div className="mt-2 flex gap-2">
            <Select
              value={tier}
              onChange={(e) => setTier(e.target.value)}
            >
              {TIERS.map((t) => (
                <option key={t}>{t}</option>
              ))}
            </Select>
            <Primary onClick={store}>Store</Primary>
          </div>
        </Card>

        <Card>
          <CardHead glyph="spark" tone="cyan" title="Retrieve"
                    sub="Ranked by relevance, recency and salience together" />
          <div className="mt-2 flex gap-2">
            <input
              value={query}
              onChange={(e) => setQuery(e.target.value)}
              placeholder="query…"
              className="flex-1 field"
            />
            <Primary onClick={retrieve}>Retrieve</Primary>
          </div>
          <div className="mt-3 space-y-1">
            {retrieved.map((m) => (
              <div key={m.id} className="text-xs">
                <span className="text-emerald-300">{m.relevance?.toFixed(3)}</span>{" "}
                <span className="text-slate-400">[{m.tier}]</span> {m.content?.slice(0, 80)}
              </div>
            ))}
          </div>
        </Card>
      </div>

      <Card pad={false}>
        <div className="px-4 pt-4">
          <CardHead glyph="list" tone="blue" title="All memories"
                    sub={`${entries.length} in this scope`} divided />
        </div>
        <div className="divide-y divide-edge text-sm">
          {entries.map((e) => (
            <div key={e.id} className="flex items-center gap-3 px-4 py-2">
              <span className="rounded bg-slate-500/20 px-2 py-0.5 text-xs">{e.tier}</span>
              <span className="text-xs text-slate-400">sal {e.salience?.toFixed(1)}</span>
              <span className="text-xs text-slate-400">acc {e.accessCount}</span>
              <span className="flex-1 truncate">{e.content}</span>
            </div>
          ))}
          {entries.length === 0 && (
            <div className="px-4 pb-4">
              <Empty
                title="Nothing stored in this scope"
                hint="Load a scope above, or store a memory to see it appear here."
              />
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}
