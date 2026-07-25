import { useEffect, useMemo, useState } from "react";
import { api, portal } from "../api";
import { Micro, Readout, Plane, StateDot } from "../system/primitives";
import { STATE } from "../system/tokens";
import FeatureToggle from "../system/FeatureToggle";

/**
 * Context MMU — the memory space.
 *
 * An address-space map rather than a set of charts. The virtual context is what
 * the conversation would occupy unpaged; the resident set is what actually sits
 * inside the model's window. Everything else is paged out to semantic stubs and
 * faulted back in on reference.
 *
 * Only measured values are drawn. The backend reports per-stub source/stub
 * tokens, version, dirty flag and last-touch time; it does *not* report a tier
 * per stub, so pages are ordered and shaded by recency of touch and labelled as
 * such — no tier is claimed for a page the engine never assigned one to.
 */
export default function MmuProfiler() {
  const [profile, setProfile] = useState<any | null>(null);
  const [stubs, setStubs] = useState<any[]>([]);
  const [sel, setSel] = useState<string | null>(null);

  useEffect(() => {
    const load = () => {
      api.get<any>("/api/mmu/profile").then(setProfile).catch(() => {});
      api.get<any[]>("/api/mmu/stubs").then(setStubs).catch(() => {});
    };
    load();
    const t = setInterval(load, 4000);
    return () => clearInterval(t);
  }, []);

  const p = profile ?? {};
  const recent: any[] = p.recent ?? [];
  const last = recent[0];
  const active = !!p.requests;

  // Residency for the most recent request — both figures measured per request.
  const virtualTokens = last?.tokensWithoutMmu ?? p.tokensWithoutMmu ?? 0;
  const residentTokens = last?.tokensSent ?? p.tokensSent ?? 0;
  const pagedOut = Math.max(0, virtualTokens - residentTokens);
  const residentFrac = virtualTokens ? residentTokens / virtualTokens : 0;

  const selected = stubs.find((s) => s.stubId === sel) ?? null;

  // Recency buckets, used only for shading and ordering.
  const ranked = useMemo(() => {
    const now = Date.now();
    return stubs
      .map((s) => ({ ...s, age: now - new Date(s.updatedAt).getTime() }))
      .sort((a, b) => a.age - b.age);
  }, [stubs]);
  const maxAge = ranked.length ? Math.max(...ranked.map((s) => s.age), 1) : 1;

  return (
    <div className="space-y-6">
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <h1 className="text-lg font-semibold tracking-tight">Context MMU</h1>
          <p className="mt-0.5 text-sm text-slate-500">
            Working set stays resident · the rest pages out and faults back on reference
          </p>
        </div>
        <FeatureToggle status={portal.v7.status} enable={portal.v7.enable} disable={portal.v7.disable} />
      </header>

      {!active ? (
        <Plane className="p-8 text-center">
          <Micro>Address space idle</Micro>
          <p className="mx-auto mt-2 max-w-md text-sm text-slate-400">
Turn it on above, then send a long conversation through the gateway.
          </p>
        </Plane>
      ) : (
        <>
          {/* ---- the address space ---- */}
          <section>
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <Micro>Virtual context · last request</Micro>
              <span className="readout text-[11px] text-slate-500">
                {virtualTokens.toLocaleString()} tokens addressable
              </span>
            </div>

            <div className="mt-2 flex h-16 w-full overflow-hidden rounded-lg border border-edge/70">
              <div
                className="relative flex items-center justify-center transition-[width] duration-700 ease-out"
                style={{
                  width: `${Math.max(residentFrac * 100, 6)}%`,
                  background: `linear-gradient(180deg, ${STATE.active.color}33, ${STATE.active.color}14)`,
                  borderRight: `1px solid ${STATE.active.color}66`,
                }}
              >
                <div className="text-center">
                  <div className="readout text-sm font-semibold" style={{ color: STATE.active.color }}>
                    {residentTokens.toLocaleString()}
                  </div>
                  <div className="micro">Resident</div>
                </div>
              </div>
              <div className="relative flex flex-1 items-center justify-center bg-ink/60">
                <div
                  className="pointer-events-none absolute inset-0 opacity-40"
                  style={{
                    backgroundImage:
                      "repeating-linear-gradient(135deg, rgb(255 255 255 / 0.05) 0 1px, transparent 1px 7px)",
                  }}
                />
                <div className="relative text-center">
                  <div className="readout text-sm font-semibold text-slate-400">
                    {pagedOut.toLocaleString()}
                  </div>
                  <div className="micro">Paged out</div>
                </div>
              </div>
            </div>

            <div className="mt-2 flex flex-wrap items-center gap-x-6 gap-y-1">
              <span className="text-[11px] text-slate-400">
                <span className="readout font-semibold text-slate-200">
                  {((p.tokenReduction ?? 0) * 100).toFixed(0)}%
                </span>{" "}
                fewer tokens sent across {p.requests} request{p.requests === 1 ? "" : "s"}
              </span>
              <span className="text-[11px] text-slate-400">
                <span className="readout font-semibold text-slate-200">
                  {Number(p.compressionRatio ?? 1).toFixed(1)}×
                </span>{" "}
                compression
              </span>
            </div>
          </section>

          {/* ---- fault economics ---- */}
          <section className="grid grid-cols-2 gap-x-8 gap-y-4 border-y border-edge/60 py-4 sm:grid-cols-3 lg:grid-cols-6">
            <Readout label="Page faults" value={p.pageFaults ?? 0} size="sm"
              state={(p.pageFaults ?? 0) > 0 ? "warning" : "healthy"}
              hint="References to paged-out context that had to be faulted back in" />
            <Readout label="Prefetches" value={p.prefetches ?? 0} size="sm"
              state={(p.prefetches ?? 0) > 0 ? "healthy" : "idle"}
              hint="Pages brought in before they were referenced" />
            <Readout label="Fault p50" value={p.faultLatencyP50Ms ?? 0} unit="ms" size="sm" />
            <Readout label="Fault p95" value={p.faultLatencyP95Ms ?? 0} unit="ms" size="sm"
              state={(p.faultLatencyP95Ms ?? 0) > 400 ? "warning" : "idle"} />
            <Readout label="Materialize" value={Number(p.avgMaterializationMs ?? 0).toFixed(0)} unit="ms avg" size="sm"
              hint="Time to fold a stub's event stream back into full context" />
            <Readout label="Dirty flushes" value={p.dirtyFlushes ?? 0} size="sm"
              state={(p.dirtyFlushes ?? 0) > 0 ? "active" : "idle"}
              hint="Modified pages written back to the persistent stream" />
          </section>

          <div className="grid gap-6 lg:grid-cols-[minmax(0,1fr)_300px]">
            {/* ---- page table ---- */}
            <section>
              <Micro>Page table · {stubs.length} stub{stubs.length === 1 ? "" : "s"}</Micro>

              {ranked.length === 0 ? (
                <Plane className="mt-2 p-6 text-center text-xs text-slate-500">
                  No pages resident yet.
                </Plane>
              ) : (
                <div className="mt-2 flex flex-wrap gap-1.5">
                  {ranked.map((s) => {
                    const heat = 1 - s.age / maxAge;
                    const isSel = s.stubId === sel;
                    const color = s.dirty ? STATE.warning.color : STATE.active.color;
                    const alpha = Math.round(6 + heat * 26).toString(16).padStart(2, "0");
                    return (
                      <button
                        key={s.stubId}
                        onClick={() => setSel(isSel ? null : s.stubId)}
                        title={`${s.stubId} · ${s.sourceTokens}→${s.stubTokens} tokens · v${s.version}${s.dirty ? " · dirty" : ""}`}
                        className="relative h-12 w-12 rounded border transition-transform hover:-translate-y-0.5"
                        style={{
                          borderColor: isSel ? color : `${color}${heat > 0.5 ? "66" : "33"}`,
                          background: `${color}${alpha}`,
                          boxShadow: isSel ? `0 0 0 1px ${color}` : undefined,
                        }}
                      >
                        <span className="absolute inset-x-0 top-1 readout text-[9px] text-slate-400">
                          v{s.version}
                        </span>
                        <span
                          className="absolute inset-x-0 bottom-1 readout text-[9px] font-semibold"
                          style={{ color }}
                        >
                          {s.stubTokens}
                        </span>
                        {s.dirty && (
                          <span
                            className="absolute right-1 top-1 h-1.5 w-1.5 rounded-full"
                            style={{ background: STATE.warning.color }}
                          />
                        )}
                      </button>
                    );
                  })}
                </div>
              )}

              <div className="mt-3 flex flex-wrap items-center gap-4">
                <span className="flex items-center gap-1.5 text-[10px] text-slate-500">
                  <StateDot state="active" size={6} /> clean page
                </span>
                <span className="flex items-center gap-1.5 text-[10px] text-slate-500">
                  <StateDot state="warning" size={6} /> dirty — pending write-behind
                </span>
                <span className="text-[10px] text-slate-500">vN = times mutated · number = stub tokens</span>
                <span className="text-[10px] text-slate-500">brighter = touched more recently</span>
              </div>

              {/* ---- request rail ---- */}
              <div className="mt-6">
                <Micro>Request rail · newest first</Micro>
                <div className="mt-2 space-y-px">
                  {recent.slice(0, 14).map((r: any) => {
                    const cut = r.tokensWithoutMmu ? 1 - r.tokensSent / r.tokensWithoutMmu : 0;
                    return (
                      <div
                        key={r.id}
                        className="flex flex-wrap items-center gap-x-3 gap-y-1 rounded px-2 py-1.5 text-[11px] transition-colors hover:bg-edge/40"
                      >
                        <span className="readout w-16 shrink-0 text-slate-600">
                          {new Date(r.createdAt).toLocaleTimeString()}
                        </span>
                        <span className="relative h-1.5 w-24 shrink-0 overflow-hidden rounded-full bg-ink">
                          <span
                            className="absolute inset-y-0 left-0 rounded-full"
                            style={{ width: `${(1 - cut) * 100}%`, background: STATE.active.color }}
                          />
                        </span>
                        <span className="readout text-slate-400">
                          {r.tokensWithoutMmu.toLocaleString()} → {r.tokensSent.toLocaleString()}
                        </span>
                        <span className="readout" style={{ color: STATE.healthy.color }}>
                          −{(cut * 100).toFixed(0)}%
                        </span>
                        <span className="ml-auto flex items-center gap-3">
                          {r.prefetches > 0 && (
                            <span style={{ color: STATE.healthy.color }}>↑{r.prefetches} prefetch</span>
                          )}
                          {r.dirtyFlushes > 0 && (
                            <span style={{ color: STATE.active.color }}>✎{r.dirtyFlushes} flush</span>
                          )}
                          {r.pageFaults > 0 ? (
                            <span
                              className="rounded px-1.5 py-0.5 font-semibold"
                              style={{ background: `${STATE.warning.color}22`, color: STATE.warning.color }}
                              title="Generation suspended, page materialized from the persistent stream, then resumed"
                            >
                              ▲ {r.pageFaults} fault · {r.faultLatencyMs}ms
                            </span>
                          ) : (
                            <span className="text-slate-600">{r.stubsActive} resident</span>
                          )}
                        </span>
                      </div>
                    );
                  })}
                </div>
              </div>
            </section>

            {/* ---- page inspector ---- */}
            <aside>
              <Micro>Page inspector</Micro>
              {selected ? (
                <div className="settle mt-2 space-y-3">
                  <div className="flex items-center gap-2">
                    <StateDot state={selected.dirty ? "warning" : "active"} />
                    <span className="font-mono text-xs text-slate-200">{selected.stubId}</span>
                  </div>
                  <Plane inset className="p-3">
                    <p className="text-[11px] leading-relaxed text-slate-400">{selected.summary}</p>
                  </Plane>
                  <dl className="space-y-2">
                    <Row k="Source tokens" v={selected.sourceTokens.toLocaleString()} />
                    <Row k="Stub tokens" v={selected.stubTokens.toLocaleString()} />
                    <Row k="Compression"
                      v={`${(selected.sourceTokens / Math.max(1, selected.stubTokens)).toFixed(1)}×`} />
                    <Row k="Version" v={`v${selected.version}${selected.version > 1 ? " · mutated" : ""}`} />
                    <Row k="Write-behind" v={selected.dirty ? "pending" : "clean"} />
                    <Row k="Last touched" v={new Date(selected.updatedAt).toLocaleTimeString()} />
                  </dl>
                  <p className="text-[10px] leading-relaxed text-slate-600">
                    A mutated page is not rewritten in place — its stream is appended to, and the page
                    is re-folded from base plus deltas when it is next faulted in.
                  </p>
                </div>
              ) : (
                <Plane className="mt-2 p-4 text-[11px] leading-relaxed text-slate-500">
                  Select a page to inspect its summary, compression and write-behind state.
                </Plane>
              )}
            </aside>
          </div>
        </>
      )}
    </div>
  );
}

function Row({ k, v }: { k: string; v: string }) {
  return (
    <div className="flex items-baseline justify-between border-b border-edge/40 pb-1.5">
      <dt className="micro">{k}</dt>
      <dd className="readout text-xs text-slate-200">{v}</dd>
    </div>
  );
}
