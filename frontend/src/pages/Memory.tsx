import { useState } from "react";
import { api } from "../api";
import { Card, CardHead, Empty, Pill, Primary, toneInk, type Tone } from "../system/hub";
import { PageHeader } from "../system/primitives";

const TIERS = ["WORKING", "EPISODIC", "LONG_TERM", "ARCHIVED"];

/** Warm to cold: the tiers are an order, and the colour says so. */
const TIER_LOOK: Record<string, { label: string; tone: Tone; sub: string }> = {
  WORKING: { label: "Working", tone: "orange", sub: "in use now" },
  EPISODIC: { label: "Episodic", tone: "amber", sub: "recent events" },
  LONG_TERM: { label: "Long-term", tone: "blue", sub: "kept and consolidated" },
  ARCHIVED: { label: "Archived", tone: "mute", sub: "cold, rarely read" },
};
const tierLabel = (t: string) => TIER_LOOK[t]?.label ?? t;

/** A 0–1 score as a short bar with its figure — reads faster than three decimals. */
function Score({ value, tone = "blue", width = 56 }: { value: number; tone?: Tone; width?: number }) {
  const v = Math.max(0, Math.min(1, value || 0));
  return (
    <span className="inline-flex items-center gap-1.5">
      <span className="relative h-1.5 overflow-hidden rounded-full" style={{ width, background: "rgb(var(--card-rule))" }}>
        <span className="absolute inset-y-0 left-0 rounded-full" style={{ width: `${v * 100}%`, background: toneInk(tone) }} />
      </span>
      <span className="readout text-[10.5px] text-slate-500">{v.toFixed(2)}</span>
    </span>
  );
}

export default function Memory() {
  const [scope, setScope] = useState("agent-001");
  const [content, setContent] = useState("");
  const [tier, setTier] = useState("EPISODIC");
  const [query, setQuery] = useState("");
  const [entries, setEntries] = useState<any[]>([]);
  const [retrieved, setRetrieved] = useState<any[]>([]);
  const [only, setOnly] = useState<string | null>(null);

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
        subtitle="Four memory tiers, retrieved by relevance"
      />

      <div className="plane flex flex-wrap items-center gap-2 p-4">
        <span className="micro">Scope</span>
        <input
          aria-label="Memory scope"
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
          <div className="mt-2 flex flex-wrap items-center gap-2">
            <div role="radiogroup" aria-label="Memory tier" className="flex flex-wrap gap-1.5">
              {TIERS.map((t) => {
                const on = tier === t;
                const look = TIER_LOOK[t];
                return (
                  <button
                    key={t}
                    type="button"
                    role="radio"
                    aria-checked={on}
                    onClick={() => setTier(t)}
                    className="press rounded-full px-3 py-1 text-[12px] font-medium transition-shadow"
                    style={{
                      color: toneInk(look.tone),
                      background: on ? `color-mix(in srgb, ${toneInk(look.tone)} 16%, transparent)` : "transparent",
                      boxShadow: `inset 0 0 0 1px color-mix(in srgb, ${toneInk(look.tone)} ${on ? 70 : 30}%, transparent)`,
                    }}
                  >
                    {look.label}
                  </button>
                );
              })}
            </div>
            <span className="flex-1" />
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
              aria-label="Retrieval query"
              placeholder="query…"
              className="flex-1 field"
            />
            <Primary onClick={retrieve}>Retrieve</Primary>
          </div>
          <div className="mt-3 space-y-2">
            {retrieved.map((m) => (
              <div key={m.id} className="flex min-w-0 items-center gap-3 text-xs">
                <Score value={m.relevance ?? 0} tone="green" />
                <Pill tone={TIER_LOOK[m.tier]?.tone ?? "mute"}>{tierLabel(m.tier)}</Pill>
                <span className="min-w-0 flex-1 truncate text-slate-300">{m.content}</span>
              </div>
            ))}
          </div>
        </Card>
      </div>

      {/* The four tiers as the stack they are: warm at the top, cold at the
          bottom, each as full as the memories it holds. Pick one to see only
          its entries. */}
      {entries.length > 0 && (
        <Card>
          <CardHead glyph="memory" tone="violet" title="Where memories sit" sub="Warm to cold · pick a tier to filter" />
          <div className="mt-4 space-y-1.5">
            {TIERS.map((t) => {
              const n = entries.filter((e) => e.tier === t).length;
              const top = Math.max(1, ...TIERS.map((x) => entries.filter((e) => e.tier === x).length));
              const look = TIER_LOOK[t];
              const on = only === t;
              return (
                <button
                  key={t}
                  type="button"
                  onClick={() => setOnly(on ? null : t)}
                  aria-pressed={on}
                  className={`press grid w-full grid-cols-[110px_minmax(0,1fr)_44px] items-center gap-3 rounded-lg px-2 py-1.5 text-left transition-colors ${on ? "bg-slate-500/10" : "hover:bg-slate-500/[0.06]"}`}
                >
                  <span className="min-w-0">
                    <span className="block text-[12.5px] font-medium text-slate-200">{look.label}</span>
                    <span className="block truncate text-[10.5px] text-slate-500">{look.sub}</span>
                  </span>
                  <span className="relative h-5 overflow-hidden rounded-md" style={{ background: "rgb(var(--card-rule))" }}>
                    <span className="absolute inset-y-0 left-0 rounded-md transition-[width] duration-500"
                          style={{ width: `${(n / top) * 100}%`, background: toneInk(look.tone), opacity: 0.75 }} />
                  </span>
                  <span className="readout text-right text-[12px] text-slate-300">{n}</span>
                </button>
              );
            })}
          </div>
        </Card>
      )}

      <Card pad={false}>
        <div className="px-4 pt-4">
          <CardHead glyph="list" tone="blue" title="All memories"
                    sub={only ? `${entries.filter((e) => e.tier === only).length} in ${tierLabel(only)} · ${entries.length} in this scope` : `${entries.length} in this scope`} divided />
        </div>
        <div className="divide-y divide-edge text-sm">
          {entries.filter((e) => !only || e.tier === only).map((e) => (
            <div key={e.id} className="flex flex-wrap items-center gap-x-3 gap-y-1 px-4 py-2">
              <Pill tone={TIER_LOOK[e.tier]?.tone ?? "mute"}>{tierLabel(e.tier)}</Pill>
              <span className="min-w-0 flex-1 truncate">{e.content}</span>
              <span className="flex items-center gap-3" title="Salience: how much this memory matters · reads: how often it has been used">
                <Score value={e.salience ?? 0} tone="violet" width={44} />
                <span className="readout text-[10.5px] text-slate-500">{e.accessCount} read{e.accessCount === 1 ? "" : "s"}</span>
              </span>
            </div>
          ))}
          {entries.length === 0 && (
            <div className="px-4 pb-4">
              <Empty
                title="Nothing stored in this scope"
                hint="Load a scope or store a memory."
              />
            </div>
          )}
        </div>
      </Card>
    </div>
  );
}
