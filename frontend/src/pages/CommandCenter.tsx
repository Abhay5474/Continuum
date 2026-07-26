import { useState } from "react";
import { Link } from "react-router-dom";
import ContinuumCore from "../system/ContinuumCore";
import { useTelemetry } from "../system/useTelemetry";
import { Micro, Readout, StateDot, Trace, Meter } from "../system/primitives";
import { STATE } from "../system/tokens";
import type { Subsystem } from "../system/ContinuumCore";
import type { Telemetry } from "../system/useTelemetry";

/**
 * AI Systems Command Centre.
 *
 * One screen that answers "what is Continuum doing right now?". The subsystems
 * are not nine separate cards: the topology is the primary surface, and the rails
 * around it are instrument readouts on the same continuous plane. Selecting a
 * node in the topology focuses this whole screen on that subsystem.
 */
export default function CommandCenter() {
  const t = useTelemetry();
  const [selected, setSelected] = useState<string | null>(null);

  const sel = t.subsystems.find((s) => s.id === selected) ?? null;
  const installed = t.subsystems.filter((s) => s.installed);
  const attention = installed.filter(
    (s) => s.state === "degraded" || s.state === "critical" || s.state === "warning"
  );
  // A running chaos experiment is itself a reason the system is not nominal, even
  // when no individual subsystem has degraded yet.
  const reasons = attention.length + (t.experimentActive ? 1 : 0);

  return (
    <div className="relative -mx-4 -mt-6 lg:min-h-[calc(100vh-56px)]">
      {/* The spatial field the whole console sits on. */}
      <div className="pointer-events-none absolute inset-0 grid-field opacity-70" />

      <div className="relative px-4 pt-5">
        {/* The visual heading here is the state readout below, which is the
            right call for an instrument. A screen reader still needs the page
            named, so the heading exists — it just is not drawn twice. */}
        <h1 className="sr-only">Command Centre</h1>
        {/* ---- system header: the one-line state of the world ---- */}
        <div className="flex flex-wrap items-end gap-x-8 gap-y-3 border-b border-edge/60 pb-4">
          <div>
            <Micro>System state</Micro>
            <div className="mt-1 flex items-center gap-2">
              <StateDot state={t.coreState} size={10} />
              <span
                className="text-xl font-semibold tracking-tight"
                style={{ color: STATE[t.coreState].color }}
              >
                {STATE[t.coreState].label}
              </span>
              <span className="text-xs text-slate-500">
                {installed.length} subsystems ·{" "}
                {reasons === 0 ? "all nominal" : `${reasons} needing attention`}
              </span>
            </div>
          </div>

          <div className="ml-auto flex flex-wrap items-end gap-x-8 gap-y-3">
            <Readout label="Requests" value={t.headline.requests.toLocaleString()} size="sm"
              state={t.headline.requests > 0 ? "active" : "idle"} />
            <Readout label="Success" value={(t.headline.successRate * 100).toFixed(1)} unit="%" size="sm"
              state={t.headline.successRate >= 0.99 ? "healthy" : t.headline.successRate >= 0.9 ? "warning" : "critical"} />
            <Readout label="Absorbed failures" value={t.headline.failoversPrevented} size="sm"
              state={t.headline.failoversPrevented > 0 ? "healthy" : "idle"} />
            <Readout label="Tokens" value={t.headline.tokens.toLocaleString()} size="sm" />
            <Readout label="Spend" value={`$${t.headline.costUsd.toFixed(5)}`} size="sm" />
            <div>
              <Micro>Throughput</Micro>
              <div className="mt-1">
                <Trace points={t.series.requests} state="active" width={110} height={22} />
              </div>
            </div>
          </div>
        </div>

        {/* ---- the topology and its instrument rails ---- */}
        <div className="grid gap-6 py-4 lg:grid-cols-[minmax(0,1fr)_320px]">
          <div className="relative flex flex-col justify-center py-2 lg:min-h-[68vh] lg:py-0">
            {t.loading ? (
              <div className="flex h-[52vh] items-center justify-center">
                <span className="micro animate-pulse">Establishing telemetry link…</span>
              </div>
            ) : (
              <ContinuumCore
                subsystems={t.subsystems}
                coreState={t.coreState}
                load={t.load}
                selected={selected}
                onSelect={setSelected}
              />
            )}
            <div className="mt-1 text-center">
              <span className="micro">
                {sel ? "Click the field to deselect" : "Select a subsystem to inspect"}
              </span>
            </div>
          </div>

          {/* ---- right rail: inspector when focused, system digest otherwise ---- */}
          <div className="space-y-4">
            {sel ? (
              <Inspector sel={sel} onClose={() => setSelected(null)} />
            ) : selected === "core" ? (
              <CoreInspector t={t} onClose={() => setSelected(null)} />
            ) : (
              <>
                <section>
                  <Micro>Subsystems</Micro>
                  <div className="mt-2 space-y-px">
                    {t.subsystems.map((s) => (
                      <button
                        key={s.id}
                        onClick={() => setSelected(s.id)}
                        className="flex w-full items-center gap-2.5 rounded px-2 py-1.5 text-left transition-colors hover:bg-edge/40"
                      >
                        <StateDot state={s.installed ? s.state : "offline"} />
                        <span className={`text-xs ${s.installed ? "text-slate-300" : "text-slate-600"}`}>
                          {s.name}
                        </span>
                        <span className="ml-auto readout text-[10px]"
                          style={{ color: STATE[s.installed ? s.state : "offline"].color }}>
                          {s.installed ? STATE[s.state].label : "absent"}
                        </span>
                      </button>
                    ))}
                  </div>
                </section>

                <section>
                  <Micro>Runtime</Micro>
                  <div className="mt-2 grid grid-cols-3 gap-2">
                    <Readout label="Running" value={t.headline.running} size="sm"
                      state={t.headline.running > 0 ? "active" : "idle"} />
                    <Readout label="Done" value={t.headline.completed} size="sm"
                      state={t.headline.completed > 0 ? "healthy" : "idle"} />
                    <Readout label="Failed" value={t.headline.failed} size="sm"
                      state={t.headline.failed > 0 ? "critical" : "idle"} />
                  </div>
                </section>

                <section>
                  <Micro>Recent system events</Micro>
                  <div className="mt-2 space-y-1.5">
                    {t.events.map((e, i) => (
                      <div key={i} className="flex items-start gap-2 text-[11px] leading-relaxed">
                        <span className="mt-1"><StateDot state={e.kind} size={6} /></span>
                        <span className="text-slate-400">{e.text}</span>
                      </div>
                    ))}
                  </div>
                </section>

                {t.workflows.length > 0 && (
                  <section>
                    <Micro>Active workflows</Micro>
                    <div className="mt-2 space-y-px">
                      {t.workflows.slice(0, 6).map((w: any) => (
                        <Link
                          key={w.workflowId}
                          to={`/workflows/${w.workflowId}`}
                          className="flex items-center gap-2 rounded px-2 py-1 transition-colors hover:bg-edge/40"
                        >
                          <StateDot
                            state={w.status === "FAILED" ? "critical" : w.status === "RUNNING" ? "active" : "healthy"}
                            size={6}
                          />
                          <span className="truncate text-[11px] text-slate-400">{w.workflowType}</span>
                          <span className="ml-auto font-mono text-[10px] text-slate-600">
                            {String(w.workflowId).slice(0, 8)}
                          </span>
                        </Link>
                      ))}
                    </div>
                  </section>
                )}
              </>
            )}
          </div>
        </div>
      </div>
    </div>
  );
}

/** Focused view of one subsystem — the "zoom in" of the topology. */
function Inspector({ sel, onClose }: { sel: Subsystem; onClose: () => void }) {
  const color = STATE[sel.installed ? sel.state : "offline"].color;
  return (
    <div className="settle space-y-4">
      <div className="flex items-start gap-2">
        <div>
          <Micro>Subsystem</Micro>
          <div className="mt-0.5 flex items-center gap-2">
            <StateDot state={sel.installed ? sel.state : "offline"} />
            <span className="text-base font-semibold text-slate-100">{sel.name}</span>
          </div>
        </div>
        <button onClick={onClose} className="ml-auto micro transition-colors hover:text-slate-300">
          Close
        </button>
      </div>

      <p className="text-xs leading-relaxed text-slate-400">{sel.summary}</p>

      {!sel.installed ? (
        <div className="well p-3">
          <div className="text-[11px] leading-relaxed text-slate-500">
            This subsystem is part of the architecture but is not deployed in this build.
            Nothing is being measured for it, so no figures are shown.
          </div>
        </div>
      ) : (
        <>
          <div className="well p-3">
            <Meter label="Path activity" value={sel.flow} state={sel.state} />
          </div>
          <div className="space-y-2">
            {sel.metrics.map((m) => (
              <div key={m.label} className="flex items-baseline justify-between border-b border-edge/40 pb-1.5">
                <span className="micro">{m.label}</span>
                <span className="readout text-sm text-slate-200">{m.value}</span>
              </div>
            ))}
          </div>
          {sel.route && (
            <Link
              to={sel.route}
              className="inline-flex items-center gap-1.5 rounded border px-3 py-1.5 text-xs font-medium transition-colors"
              style={{ borderColor: `${color}55`, color }}
            >
              Enter {sel.name} →
            </Link>
          )}
        </>
      )}
    </div>
  );
}

/** The core itself: the aggregate view. */
function CoreInspector({ t, onClose }: { t: Telemetry; onClose: () => void }) {
  return (
    <div className="settle space-y-4">
      <div className="flex items-start">
        <div>
          <Micro>Continuum Core</Micro>
          <div className="mt-0.5 flex items-center gap-2">
            <StateDot state={t.coreState} />
            <span className="text-base font-semibold text-slate-100">{STATE[t.coreState].label}</span>
          </div>
        </div>
        <button onClick={onClose} className="ml-auto micro transition-colors hover:text-slate-300">
          Close
        </button>
      </div>
      <p className="text-xs leading-relaxed text-slate-400">
        The core state is the worst state among installed subsystems. It reflects what your
        traffic would actually experience, not an average.
      </p>
      <div className="well p-3">
        <Meter label="System load" value={t.load} state={t.load > 0.8 ? "warning" : "active"} />
      </div>
      <div>
        <Micro>Success rate</Micro>
        <div className="mt-2">
          <Trace points={t.series.success} state="healthy" width={280} height={44} />
        </div>
      </div>
    </div>
  );
}
