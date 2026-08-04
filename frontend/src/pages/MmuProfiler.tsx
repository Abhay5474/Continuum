import { useEffect, useMemo, useState } from "react";
import { api, portal } from "../api";
import { Micro, Switch } from "../system/primitives";
import FeatureToggle from "../system/FeatureToggle";
import { timeOf, toMillis } from "../system/time";
import {
  Bar,
  Dot,
  Empty,
  Facts,
  Field,
  Hop,
  KindMark,
  Rail,
  Route,
  Row,
  SidePanel,
  Stage,
  Stat,
  Stats,
} from "../system/hub";

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
  const [busyWs, setBusyWs] = useState(false);
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
      .map((s) => ({ ...s, age: now - (toMillis(s.updatedAt) ?? now) }))
      .sort((a, b) => a.age - b.age);
  }, [stubs]);
  const maxAge = ranked.length ? Math.max(...ranked.map((s) => s.age), 1) : 1;

  const setWorkingSet = async (next: boolean) => {
    setBusyWs(true);
    try {
      await api.get<any>("/api/mmu/profile");
      await fetch("/api/mmu/settings", {
        method: "PUT",
        headers: {
          "Content-Type": "application/json",
          Authorization: `Bearer ${localStorage.getItem("continuum.portal.session") ?? ""}`,
        },
        body: JSON.stringify({ workingSet: next }),
      });
      api.get<any>("/api/mmu/profile").then(setProfile).catch(() => {});
    } finally {
      setBusyWs(false);
    }
  };

  return (
    <div className="page-enter">
      <header className="flex flex-wrap items-start gap-4">
        <div className="min-w-0 flex-1">
          <h1 className="text-[22px] font-semibold tracking-tight text-slate-100">Context Optimizer</h1>
          <p className="mt-1 max-w-2xl text-[13px] leading-relaxed text-slate-500">
            The working set stays resident inside the model's window. Everything else pages out to a
            semantic stub and faults back in when it is referenced.
          </p>
        </div>
        <FeatureToggle status={portal.v7.status} enable={portal.v7.enable} disable={portal.v7.disable} />
      </header>

      <div className="mt-6">
        <Route>
          <Stage
            label="the conversation"
            sub={virtualTokens ? `${virtualTokens.toLocaleString()} tokens addressable` : "unpaged"}
          />
          <Hop label="paged" />
          <Stage
            label="Context Optimizer"
            sub={active ? `${stubs.length} page${stubs.length === 1 ? "" : "s"} held` : "idle"}
            state={active ? "on" : "off"}
            mark={<KindMark kind="table" size={26} />}
            selected
          />
          <Hop label="resident" />
          <Stage
            label="the model's window"
            sub={residentTokens ? `${residentTokens.toLocaleString()} tokens sent` : "nothing sent yet"}
          />
        </Route>
      </div>

      {/* Working-set assembly: what is kept is decided by the current request,
          not by what happens to be newest. */}
      <div className="mt-8">
        <Switch
          checked={!!p.workingSet}
          busy={busyWs}
          onChange={setWorkingSet}
          label="Working-set assembly"
          hint="Off by default — eviction is positional, oldest paged out first. With it on, what stays resident is scored against the request being answered now, so an order number stated in message three survives a question asked in message forty."
        />
        <p className="mt-3 max-w-2xl text-xs leading-relaxed text-slate-600">
          Scored 0.7 × relevance to the current request + 0.3 × recency. Relevance outweighs recency
          because recency is only a proxy for it, and when a direct measurement is available the proxy
          should not outvote it. The measure is lexical, so it will miss a paraphrase sharing no
          vocabulary — better than position, worse than understanding.
        </p>
      </div>

      {!active ? (
        <div className="mt-10">
          <Empty
            title="Address space idle"
            hint="Turn it on above, then send a long conversation through the gateway. Nothing is paged until there is more context than the window can hold."
          />
        </div>
      ) : (
        <>
          {/* ---- the address space ---- */}
          <section className="mt-10">
            <div className="flex flex-wrap items-baseline justify-between gap-2">
              <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">
                Virtual context · last request
              </h2>
              <span className="readout text-[11px] text-slate-500">
                {virtualTokens.toLocaleString()} tokens addressable
              </span>
            </div>

            <div
              className="mt-3 flex h-16 w-full overflow-hidden rounded-lg"
              style={{ boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }}
            >
              <div
                className="relative flex items-center justify-center transition-[width] duration-700 ease-out"
                style={{
                  width: `${Math.max(residentFrac * 100, 6)}%`,
                  background: "var(--accent-wash)",
                  borderRight: "1px solid var(--accent-edge)",
                }}
              >
                <div className="text-center">
                  <div className="readout text-sm font-semibold" style={{ color: "var(--accent-ink)" }}>
                    {residentTokens.toLocaleString()}
                  </div>
                  <div className="micro">Resident</div>
                </div>
              </div>
              <div className="relative flex flex-1 items-center justify-center bg-ink/60">
                {/* Hatched, because "paged out" is absence rather than a second
                    category — a solid fill would read as two things of equal
                    standing sharing the window. */}
                <div
                  className="pointer-events-none absolute inset-0 opacity-40"
                  style={{
                    backgroundImage:
                      "repeating-linear-gradient(135deg, rgb(127 140 165 / 0.16) 0 1px, transparent 1px 7px)",
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

            <div className="mt-3 flex flex-wrap items-center gap-x-8 gap-y-1">
              <span className="text-[11.5px] text-slate-500">
                <span className="readout font-semibold text-slate-200">
                  {((p.tokenReduction ?? 0) * 100).toFixed(0)}%
                </span>{" "}
                fewer tokens sent across {p.requests} request{p.requests === 1 ? "" : "s"}
              </span>
              <span className="text-[11.5px] text-slate-500">
                <span className="readout font-semibold text-slate-200">
                  {Number(p.compressionRatio ?? 1).toFixed(1)}×
                </span>{" "}
                compression
              </span>
            </div>
          </section>

          {/* ---- fault economics ---- */}
          <section className="mt-9">
            <Stats>
              <Stat
                label="Page faults"
                value={p.pageFaults ?? 0}
                tone={(p.pageFaults ?? 0) > 0 ? "warn" : "ok"}
                hint="References to paged-out context that had to be faulted back in."
              />
              <Stat
                label="Prefetches"
                value={p.prefetches ?? 0}
                tone={(p.prefetches ?? 0) > 0 ? "ok" : undefined}
                hint="Pages brought in before they were referenced."
              />
              <Stat label="Fault p50" value={p.faultLatencyP50Ms ?? 0} unit="ms" />
              <Stat
                label="Fault p95"
                value={p.faultLatencyP95Ms ?? 0}
                unit="ms"
                tone={(p.faultLatencyP95Ms ?? 0) > 400 ? "warn" : undefined}
              />
              <Stat
                label="Materialize"
                value={Number(p.avgMaterializationMs ?? 0).toFixed(0)}
                unit="ms avg"
                hint="Time to fold a stub's event stream back into full context."
              />
              <Stat
                label="Dirty flushes"
                value={p.dirtyFlushes ?? 0}
                tone={(p.dirtyFlushes ?? 0) > 0 ? "accent" : undefined}
                hint="Modified pages written back to the persistent stream."
              />
            </Stats>
          </section>

          {/* ---- page table ---- */}
          <section className="mt-10">
            <h2 className="flex items-baseline gap-2 text-[13px] font-semibold tracking-tight text-slate-200">
              Page table
              <span className="readout text-[11px] font-normal text-slate-600">{stubs.length}</span>
            </h2>

            {ranked.length === 0 ? (
              <div className="mt-3">
                <Empty title="No pages resident yet" />
              </div>
            ) : (
              /* Kept as a grid of cells, which is not the card grid this
                 redesign removed elsewhere: a page table *is* a grid of equal
                 slots, and each cell here carries three measured values in
                 twelve square millimetres. */
              <div className="mt-3 flex flex-wrap gap-1.5">
                {ranked.map((s) => {
                  const heat = 1 - s.age / maxAge;
                  const isSel = s.stubId === sel;
                  const colour = s.dirty ? "var(--state-warning-ink)" : "var(--accent)";
                  return (
                    <button
                      key={s.stubId}
                      onClick={() => setSel(isSel ? null : s.stubId)}
                      title={`${s.stubId} · ${s.sourceTokens}→${s.stubTokens} tokens · v${s.version}${s.dirty ? " · dirty" : ""}`}
                      className="relative h-12 w-12 rounded-md transition-transform duration-150 hover:-translate-y-0.5"
                      style={{
                        background: `color-mix(in srgb, ${colour} ${Math.round(4 + heat * 14)}%, transparent)`,
                        boxShadow: isSel
                          ? `inset 0 0 0 1.5px ${colour}`
                          : `inset 0 0 0 1px color-mix(in srgb, ${colour} ${heat > 0.5 ? 40 : 20}%, transparent)`,
                      }}
                    >
                      <span className="readout absolute inset-x-0 top-1 text-[9px] text-slate-500">
                        v{s.version}
                      </span>
                      <span
                        className="readout absolute inset-x-0 bottom-1 text-[9px] font-semibold"
                        style={{ color: colour }}
                      >
                        {s.stubTokens}
                      </span>
                      {s.dirty && (
                        <span
                          className="absolute right-1 top-1 h-1.5 w-1.5 rounded-full"
                          style={{ background: "var(--state-warning-ink)" }}
                        />
                      )}
                    </button>
                  );
                })}
              </div>
            )}

            <div className="mt-3 flex flex-wrap items-center gap-x-5 gap-y-1.5">
              <Dot tone="busy" label="clean page" />
              <Dot tone="warn" label="dirty — pending write-behind" />
              <span className="text-[10.5px] text-slate-600">vN = times mutated · number = stub tokens</span>
              <span className="text-[10.5px] text-slate-600">brighter = touched more recently</span>
            </div>
          </section>

          {/* ---- request rail ---- */}
          <section className="mt-10">
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">
              Requests · newest first
            </h2>
            <div className="mt-3">
              <Rail>
                {recent.slice(0, 14).map((r: any) => {
                  const cut = r.tokensWithoutMmu ? 1 - r.tokensSent / r.tokensWithoutMmu : 0;
                  return (
                    <Row
                      key={r.id}
                      title={
                        <span className="readout text-[12.5px]">
                          {r.tokensWithoutMmu.toLocaleString()} → {r.tokensSent.toLocaleString()}
                          <span className="ml-2" style={{ color: "var(--state-healthy-ink)" }}>
                            −{(cut * 100).toFixed(0)}%
                          </span>
                        </span>
                      }
                      subtitle={timeOf(r.createdAt)}
                      status={
                        r.pageFaults > 0 ? (
                          <Dot
                            tone="warn"
                            label={`${r.pageFaults} fault · ${r.faultLatencyMs}ms`}
                          />
                        ) : (
                          <span className="text-[11px] text-slate-600">{r.stubsActive} resident</span>
                        )
                      }
                      meta={
                        <div className="flex flex-wrap items-center gap-x-4 gap-y-1">
                          {/* The bar is what was sent, against what would have
                              been sent unpaged. Same scale on every row. */}
                          <Bar fraction={1 - cut} tone="accent" width={120} />
                          <Facts
                            items={[
                              ...(r.prefetches > 0 ? [{ k: "prefetched", v: r.prefetches }] : []),
                              ...(r.dirtyFlushes > 0 ? [{ k: "flushed", v: r.dirtyFlushes }] : []),
                            ]}
                          />
                        </div>
                      }
                    />
                  );
                })}
              </Rail>
            </div>
          </section>
        </>
      )}

      {/* ---- page inspector ---- */}
      <SidePanel
        open={!!selected}
        title={<span className="font-mono text-[13px]">{selected?.stubId ?? ""}</span>}
        subtitle={selected ? (selected.dirty ? "dirty — write-behind pending" : "clean") : undefined}
        mark={<KindMark kind="document" size={34} />}
        onClose={() => setSel(null)}
      >
        {selected && (
          <>
            <Field label="Summary held in place of the text">
              <p className="leading-relaxed text-slate-400">{selected.summary}</p>
            </Field>
            <Field label="Compression">
              <div className="flex items-center gap-3">
                <Bar
                  fraction={selected.stubTokens / Math.max(1, selected.sourceTokens)}
                  tone="accent"
                  width={140}
                />
                <span className="readout text-[12px] text-slate-300">
                  {(selected.sourceTokens / Math.max(1, selected.stubTokens)).toFixed(1)}×
                </span>
              </div>
              <p className="mt-1.5 text-[11.5px] text-slate-600">
                {selected.sourceTokens.toLocaleString()} source tokens held as{" "}
                {selected.stubTokens.toLocaleString()}.
              </p>
            </Field>
            <Field label="State">
              <Facts
                items={[
                  { k: "version", v: `v${selected.version}${selected.version > 1 ? " · mutated" : ""}` },
                  { k: "write-behind", v: selected.dirty ? "pending" : "clean" },
                  { k: "last touched", v: timeOf(selected.updatedAt) },
                ]}
              />
            </Field>
            <p className="mt-5 text-[11.5px] leading-relaxed text-slate-600">
              <Micro>Why a version rather than a rewrite</Micro>
              A mutated page is not rewritten in place — its stream is appended to, and the page is
              re-folded from base plus deltas when it is next faulted in.
            </p>
          </>
        )}
      </SidePanel>
    </div>
  );
}
