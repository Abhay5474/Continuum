import { useEffect, useMemo, useState } from "react";
import { api, portal } from "../api";

/**
 * V7 — Context Memory Profiler: mission-control instrumentation for the
 * Paging MMU. Renders telemetry, the L1/L2/L3 hierarchy with migrating
 * blocks, and the request timeline with page-fault interrupts.
 */
export default function MmuProfiler() {
  const [profile, setProfile] = useState<any | null>(null);
  const [stubs, setStubs] = useState<any[]>([]);
  const [me, setMe] = useState<any | null>(null);

  useEffect(() => {
    portal.me().then(setMe).catch(() => {});
  }, []);
  useEffect(() => {
    const load = () => {
      api.get<any>(`/api/mmu/profile${me?.id ? `?developerId=${me.id}` : ""}`)
        .then(setProfile).catch(() => {});
      if (me?.id) api.get<any[]>(`/api/mmu/stubs?developerId=${me.id}`).then(setStubs).catch(() => {});
    };
    load();
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, [me]);

  const p = profile ?? {};
  const recent: any[] = p.recent ?? [];
  const pct = (x: number | undefined) => x == null ? "—" : `${(x * 100).toFixed(0)}%`;

  return (
    <div className="space-y-5">
      <div>
        <h1 className="text-lg font-semibold text-gradient">V7 — Context Memory Profiler</h1>
        <p className="text-sm text-slate-400">
          The Paging MMU in real time: L1 active window, L2 semantic stubs, L3 immutable event
          streams. Enable it per developer in the Developer Portal; every virtualized request lands
          here with measurable efficiency metrics.
        </p>
      </div>

      {/* ---- telemetry dashboard ---- */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-4 lg:grid-cols-7">
        <Tile label="Token reduction" value={pct(p.tokenReduction)} accent="text-neon" glow />
        <Tile label="Tokens w/o MMU" value={p.tokensWithoutMmu?.toLocaleString() ?? "—"} />
        <Tile label="Tokens sent" value={p.tokensSent?.toLocaleString() ?? "—"} accent="text-emerald-300" />
        <Tile label="Est. cost saved" value={pct(p.estimatedCostSaved)} accent="text-emerald-300" />
        <Tile label="Fault latency P50/P95" value={p.requests ? `${p.faultLatencyP50Ms}/${p.faultLatencyP95Ms}ms` : "—"} />
        <Tile label="Materialization" value={p.avgMaterializationMs != null ? `${Number(p.avgMaterializationMs).toFixed(0)}ms` : "—"} />
        <Tile label="Compression" value={p.compressionRatio ? `${Number(p.compressionRatio).toFixed(1)}x` : "—"} accent="text-violet-300" />
      </div>

      <div className="grid gap-5 lg:grid-cols-2">
        {/* ---- hierarchy visualizer ---- */}
        <div className="glass p-4">
          <div className="text-xs font-semibold uppercase tracking-wider text-slate-400">
            Memory hierarchy
          </div>
          <HierarchyVisualizer profile={p} stubs={stubs} />
        </div>

        {/* ---- request timeline with fault interrupts ---- */}
        <div className="glass p-4">
          <div className="flex items-center justify-between">
            <div className="text-xs font-semibold uppercase tracking-wider text-slate-400">
              Request timeline
            </div>
            <div className="text-[10px] text-slate-500">
              ⚡ = page fault · ↑ = prefetch · ✎ = dirty flush
            </div>
          </div>
          <div className="mt-3 max-h-72 space-y-1.5 overflow-y-auto pr-1">
            {recent.map((r) => {
              const reduction = r.tokensWithoutMmu === 0 ? 0
                : 1 - r.tokensSent / r.tokensWithoutMmu;
              return (
                <div key={r.id} className="flex items-center gap-2 rounded-md border border-edge bg-ink/60 px-3 py-1.5 text-xs transition-colors hover:border-aurora/40">
                  <span className="font-mono text-slate-500">
                    {new Date(r.createdAt).toLocaleTimeString()}
                  </span>
                  <span className="text-slate-300">
                    {r.tokensWithoutMmu.toLocaleString()} → {r.tokensSent.toLocaleString()} tok
                  </span>
                  <span className="rounded bg-neon/10 px-1.5 py-0.5 text-[10px] text-neon">
                    −{(reduction * 100).toFixed(0)}%
                  </span>
                  {r.prefetches > 0 && (
                    <span className="text-[10px] text-emerald-300">↑{r.prefetches}</span>
                  )}
                  {r.dirtyFlushes > 0 && (
                    <span className="text-[10px] text-amber-300">✎{r.dirtyFlushes}</span>
                  )}
                  {r.pageFaults > 0 && (
                    <span className="ml-auto flex items-center gap-1 rounded bg-amber-500/15 px-2 py-0.5 text-[10px] font-bold text-amber-300 animate-pulse"
                      title="page fault: generation suspended, page materialized from L3, resumed">
                      ⚡ {r.pageFaults} fault · {r.faultLatencyMs}ms
                    </span>
                  )}
                  {r.pageFaults === 0 && (
                    <span className="ml-auto text-[10px] text-slate-600">{r.stubsActive} stubs</span>
                  )}
                </div>
              );
            })}
            {recent.length === 0 && (
              <div className="rounded-md border border-dashed border-edge px-3 py-6 text-center text-xs text-slate-500">
                No virtualized requests yet. Enable V7 in the Developer Portal, then send a long
                conversation through the gateway.
              </div>
            )}
          </div>
        </div>
      </div>

      {/* ---- L2 stub ledger ---- */}
      {stubs.length > 0 && (
        <div className="glass p-4">
          <div className="text-xs font-semibold uppercase tracking-wider text-slate-400">
            L2 semantic stubs ({stubs.length})
          </div>
          <div className="mt-2 max-h-56 space-y-1 overflow-y-auto">
            {stubs.map((s) => (
              <div key={s.stubId} className="flex flex-wrap items-center gap-2 rounded-md bg-ink/60 px-3 py-1.5 text-xs">
                <span className="font-mono text-[10px] text-violet-300">{s.stubId}</span>
                <span className="rounded bg-edge px-1.5 py-0.5 text-[10px] text-slate-400">v{s.version}</span>
                {s.version > 1 && (
                  <span className="rounded bg-amber-500/15 px-1.5 py-0.5 text-[10px] text-amber-300">MUTATED</span>
                )}
                <span className="truncate text-slate-400">{s.summary}</span>
                <span className="ml-auto whitespace-nowrap text-[10px] text-slate-600">
                  {s.sourceTokens} → {s.stubTokens} tok
                </span>
              </div>
            ))}
          </div>
        </div>
      )}
    </div>
  );
}

function Tile({ label, value, accent, glow }: { label: string; value: any; accent?: string; glow?: boolean }) {
  return (
    <div className={`glass p-3 ${glow ? "shadow-glow-cyan" : ""}`}>
      <div className="text-[10px] uppercase tracking-wider text-slate-500">{label}</div>
      <div className={`mt-0.5 text-xl font-bold ${accent ?? "text-slate-200"}`}>{value}</div>
    </div>
  );
}

/** L1/L2/L3 tiers with animated blocks migrating between them. */
function HierarchyVisualizer({ profile, stubs }: { profile: any; stubs: any[] }) {
  const l1Blocks = 6;
  const l2Blocks = Math.min(10, stubs.length);
  const l3Blocks = Math.min(14, stubs.length + 4);
  const migrating = useMemo(() => (profile?.requests ?? 0) > 0, [profile]);

  const Tier = ({ name, desc, blocks, color, delay }: any) => (
    <div className="flex items-center gap-3">
      <div className="w-24 shrink-0">
        <div className={`text-sm font-bold ${color}`}>{name}</div>
        <div className="text-[9px] leading-tight text-slate-500">{desc}</div>
      </div>
      <div className="flex flex-1 flex-wrap gap-1">
        {Array.from({ length: blocks }).map((_, i) => (
          <span key={i}
            className={`h-4 w-7 rounded-sm ${color.replace("text-", "bg-")}/25 ring-1 ${color.replace("text-", "ring-")}/40 ${
              migrating && i === blocks - 1 ? "animate-floaty" : ""}`}
            style={{ animationDelay: `${delay + i * 0.12}s` }} />
        ))}
        {blocks === 0 && <span className="text-[10px] text-slate-600">empty</span>}
      </div>
    </div>
  );

  return (
    <div className="mt-3 space-y-1.5">
      <Tier name="L1" desc="active token window (sent to model)" blocks={l1Blocks} color="text-neon" delay={0} />
      <MigrationArrow label={`evict → summarize${migrating ? " · live" : ""}`} active={migrating} />
      <Tier name="L2" desc="semantic stubs [MEMORY_REF]" blocks={l2Blocks} color="text-violet-300" delay={0.2} />
      <MigrationArrow label="page out → immutable events" active={migrating} />
      <Tier name="L3" desc="Postgres event streams (disk)" blocks={l3Blocks} color="text-slate-400" delay={0.4} />
      <div className="mt-2 flex items-center gap-2 text-[10px] text-slate-500">
        <span className="rounded bg-emerald-500/15 px-1.5 py-0.5 text-emerald-300">↑ prefetch: {profile?.prefetches ?? 0}</span>
        <span className="rounded bg-amber-500/15 px-1.5 py-0.5 text-amber-300">⚡ faults: {profile?.pageFaults ?? 0}</span>
        <span className="rounded bg-violet-500/15 px-1.5 py-0.5 text-violet-300">✎ dirty flushes: {profile?.dirtyFlushes ?? 0}</span>
      </div>
    </div>
  );
}

function MigrationArrow({ label, active }: { label: string; active: boolean }) {
  return (
    <div className="ml-24 flex items-center gap-2 py-0.5">
      <div className={`h-0.5 w-24 rounded-full ${active ? "shimmer-line" : "bg-edge"}`} />
      <span className="text-[9px] text-slate-600">{label}</span>
    </div>
  );
}
