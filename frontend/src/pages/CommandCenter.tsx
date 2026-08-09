import { useMemo, useState } from "react";
import { Link } from "react-router-dom";
import ContinuumCore from "../system/ContinuumCore";
import { useTelemetry } from "../system/useTelemetry";
import { Micro, StateDot, Trace, Meter } from "../system/primitives";
import { STATE } from "../system/tokens";
import {
  Allocation,
  BarList,
  Card,
  CardHead,
  Event,
  Feed,
  Pill,
  Stat,
  Stats,
  type Slice,
} from "../system/hub";
import type { Subsystem } from "../system/ContinuumCore";
import type { Telemetry } from "../system/useTelemetry";

/** A system state, as one of the six tones the pills and feed rows understand. */
const EVENT_TONE: Record<string, "ok" | "warn" | "bad" | "info" | "mute"> = {
  healthy: "ok",
  active: "info",
  warning: "warn",
  degraded: "warn",
  critical: "bad",
  idle: "mute",
  offline: "mute",
};

/** Counter series → per-poll arrivals. A cumulative line is the same shape every time. */
function deltas(series: number[]): number[] {
  return series.slice(1).map((v, i) => Math.max(0, v - series[i]));
}

/**
 * Percentage change between the last quarter of a window and the one before it.
 *
 * <p>Not last-sample-versus-first: a single poll is noise, and a delta drawn
 * from noise is worse than no delta at all. Returns undefined until there is
 * enough of a window to say anything.
 */
function trend(series: number[]): number | undefined {
  if (series.length < 8) return undefined;
  const q = Math.max(2, Math.floor(series.length / 4));
  const mean = (a: number[]) => a.reduce((n, v) => n + v, 0) / (a.length || 1);
  const now = mean(series.slice(-q));
  const before = mean(series.slice(-2 * q, -q));
  if (before === 0) return now === 0 ? 0 : undefined;
  return Math.round(((now - before) / before) * 100);
}

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

  const arrivals = useMemo(() => deltas(t.series.requests), [t.series.requests]);
  const reqTrend = useMemo(() => trend(arrivals), [arrivals]);

  /** Installed subsystems grouped by what they are currently doing. */
  const healthSlices = useMemo<Slice[]>(() => {
    const count = (...keys: string[]) =>
      installed.filter((s) => keys.includes(s.state)).length;
    return [
      { key: "active", label: "Carrying traffic", value: count("active"), tone: "violet" },
      { key: "healthy", label: "Healthy, idle path", value: count("healthy"), tone: "green" },
      { key: "idle", label: "Not in use", value: count("idle"), tone: "mute" },
      {
        key: "attention",
        label: "Needing attention",
        value: count("warning", "degraded", "critical"),
        tone: "red",
      },
    ].filter((s) => s.value > 0) as Slice[];
  }, [installed]);

  const healthyPct = installed.length
    ? Math.round(((installed.length - attention.length) / installed.length) * 100)
    : 100;

  /** Only what is actually moving — a list of thirteen zero-length bars is noise. */
  const activity = useMemo(
    () =>
      installed
        .filter((s) => s.flow > 0.002)
        .sort((a, b) => b.flow - a.flow)
        .slice(0, 6)
        .map((s, i) => ({
          key: s.id,
          label: s.name,
          note: `${Math.round(s.flow * 100)}% of display scale`,
          fraction: s.flow,
          tone: (["violet", "blue", "cyan", "green", "amber", "orange"] as const)[i],
        })),
    [installed]
  );

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
        <div className="flex flex-wrap items-center gap-x-4 gap-y-2">
          <div className="flex items-center gap-2.5">
            <StateDot state={t.coreState} size={10} />
            <span
              className="text-[21px] font-semibold tracking-tight"
              style={{ color: STATE[t.coreState].ink }}
            >
              {STATE[t.coreState].label}
            </span>
          </div>
          <Pill tone={reasons === 0 ? "ok" : "warn"} dot>
            {reasons === 0 ? "All nominal" : `${reasons} needing attention`}
          </Pill>
          <span className="text-xs text-slate-500">
            {installed.length} subsystems · polled every four seconds
          </span>
          <Link
            to="/gateway"
            className="ml-auto rounded-md px-3 py-1.5 text-[12.5px] font-medium transition-opacity hover:opacity-90"
            style={{ background: "var(--accent-strong)", color: "var(--accent-on)" }}
          >
            Open the gateway
          </Link>
        </div>

        {/* ---- the headline band ----------------------------------------
            Eight figures, each with its own mark and — where there is a real
            series behind it — its own shape. The hue is identity: spend is
            always amber, throughput always violet, so the band is scanned by
            colour before it is read. */}
        <div className="mt-5">
          <Stats cols={4}>
            <Stat
              label="Requests"
              glyph="activity"
              tone="violet"
              value={t.headline.requests.toLocaleString()}
              series={arrivals}
              delta={reqTrend}
              deltaNote="vs the previous window"
            />
            <Stat
              label="Success"
              glyph="check"
              unit="%"
              tone="green"
              value={(t.headline.successRate * 100).toFixed(1)}
              series={t.series.success}
            />
            <Stat
              label="Absorbed failures"
              glyph="shield"
              tone="blue"
              value={t.headline.failoversPrevented}
              hint="Provider failures the engine handled before your app saw them"
            />
            <Stat
              label="Spend"
              glyph="coin"
              tone="amber"
              value={`$${t.headline.costUsd.toFixed(5)}`}
            />
            <Stat label="Tokens" glyph="layers" tone="cyan" value={t.headline.tokens.toLocaleString()} />
            <Stat label="Running" glyph="flow" tone="orange" value={t.headline.running} />
            <Stat label="Completed" glyph="check" tone="green" value={t.headline.completed} />
            <Stat
              label="Failed"
              glyph="alert"
              tone={t.headline.failed > 0 ? "red" : "mute"}
              value={t.headline.failed}
            />
          </Stats>
        </div>

        {/* ---- the three panels: what the fleet is made of, what it is
            doing, and what just happened ---- */}
        <div className="mt-4 grid gap-3 lg:grid-cols-3">
          <Card>
            <CardHead
              glyph="layers"
              tone="violet"
              title="Subsystem health"
              sub={`${installed.length} installed of ${t.subsystems.length} in the architecture`}
            />
            <div className="mt-4">
              <Allocation
                slices={healthSlices}
                centre={`${healthyPct}%`}
                centreLabel="nominal"
                unit="subsystems"
              />
            </div>
          </Card>

          <Card>
            <CardHead
              glyph="gauge"
              tone="cyan"
              title="Path activity"
              sub="Traffic on each subsystem's own path, this poll"
            />
            <div className="mt-4">
              {activity.length > 0 ? (
                <BarList items={activity} />
              ) : (
                <p className="text-xs text-slate-500">
                  Nothing is moving. Send a request through the gateway and the bars fill in.
                </p>
              )}
            </div>
          </Card>

          <Card>
            <CardHead
              glyph="alert"
              tone="orange"
              title="System events"
              sub="Newest first"
              right={
                <Pill tone="ok" dot>
                  Live
                </Pill>
              }
            />
            <div className="mt-3">
              {t.events.length > 0 ? (
                <Feed>
                  {t.events.map((e, i) => (
                    <Event
                      key={i}
                      tone={EVENT_TONE[e.kind] ?? "info"}
                      title={e.text}
                      badge={STATE[e.kind]?.label}
                    />
                  ))}
                </Feed>
              ) : (
                <p className="px-1 text-xs text-slate-500">
                  Nothing has happened worth reporting. Degradations, failovers and chaos
                  experiments appear here as they occur.
                </p>
              )}
            </div>
          </Card>
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
                {/* The rail is the topology's index: every node in the field,
                    in a list you can actually read the names in. The runtime
                    counters and the event feed used to live here too and are
                    now in the band above, so this says one thing. */}
                <Card pad={false}>
                  <div className="px-4 pt-4">
                    <CardHead
                      glyph="list"
                      tone="blue"
                      title="Subsystems"
                      sub="Pick one to focus the whole screen on it"
                    />
                  </div>
                  <div className="mt-3 pb-2">
                    {t.subsystems.map((s) => (
                      <button
                        key={s.id}
                        onClick={() => setSelected(s.id)}
                        className="flex w-full items-center gap-2.5 px-4 py-1.5 text-left transition-colors hover:bg-slate-500/[0.055]"
                      >
                        <StateDot state={s.installed ? s.state : "offline"} />
                        <span className={`text-xs ${s.installed ? "text-slate-300" : "text-slate-600"}`}>
                          {s.name}
                        </span>
                        <span className="ml-auto readout text-[10px]"
                          style={{ color: STATE[s.installed ? s.state : "offline"].ink }}>
                          {s.installed ? STATE[s.state].label : "absent"}
                        </span>
                      </button>
                    ))}
                  </div>
                </Card>

                {t.workflows.length > 0 && (
                  <Card pad={false}>
                    <div className="px-4 pt-4">
                      <CardHead
                        glyph="flow"
                        tone="orange"
                        title="Active workflows"
                        sub={`${t.workflows.length} in flight`}
                      />
                    </div>
                    <div className="mt-3 pb-2">
                      {t.workflows.slice(0, 6).map((w: any) => (
                        <Link
                          key={w.workflowId}
                          to={`/workflows/${w.workflowId}`}
                          className="flex items-center gap-2 px-4 py-1.5 transition-colors hover:bg-slate-500/[0.055]"
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
                  </Card>
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
    <Card className="settle space-y-4">
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

      <p className="text-xs leading-relaxed text-slate-400 max-w-2xl">{sel.summary}</p>

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
    </Card>
  );
}

/** The core itself: the aggregate view. */
function CoreInspector({ t, onClose }: { t: Telemetry; onClose: () => void }) {
  return (
    <Card className="settle space-y-4">
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
      <p className="text-xs leading-relaxed text-slate-400 max-w-2xl">
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
    </Card>
  );
}
