import { useEffect, useState } from "react";
import { visibleInterval } from "../system/poll";
import { portal } from "../api";
import { PageHeader, Note } from "../system/primitives";
import { Card, CardHead, Chip, Grid, Pill, type GlyphName, type Tone } from "../system/hub";

/**
 * Autopilot — beginner-friendly control plane UI. Reuses the developer session
 * token (same auth as the Developer Portal). If the developer isn't signed in,
 * we nudge them to the portal.
 */
export default function Autopilot() {
  const [status, setStatus] = useState<any>(null);
  const [recs, setRecs] = useState<any[]>([]);
  const [canary, setCanary] = useState<any[]>([]);
  const [decisions, setDecisions] = useState<any[]>([]);
  const [bundles, setBundles] = useState<any[]>([]);
  const [rollbacks, setRollbacks] = useState<any[]>([]);
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const [showWizard, setShowWizard] = useState(false);

  const signedIn = !!portal.session();

  const refresh = () => {
    if (!signedIn) return;
    portal.autopilot.status().then(setStatus).catch((e) => setErr(e.message));
    portal.autopilot.recommendations().then(setRecs).catch(() => {});
    portal.autopilot.canary().then(setCanary).catch(() => {});
    portal.autopilot.decisions().then(setDecisions).catch(() => {});
    portal.autopilot.bundles().then(setBundles).catch(() => {});
    portal.autopilot.rollbacks().then(setRollbacks).catch(() => {});
  };
  useEffect(() => {
    refresh();
    return visibleInterval(refresh, 4000);
  }, []);

  if (!signedIn) {
    return (
      <div className="mx-auto max-w-lg card rounded-lg border border-edge bg-panel p-6 text-center">
        <div className="flex justify-center"><Chip glyph="spark" tone="accent" size={40} /></div>
        <h1 className="mt-2 text-[22px] font-semibold tracking-tight">Autopilot</h1>
        <Note className="mt-1">
          Sign in on the <a href="/portal" className="text-indigo-400 underline">Developer Portal</a> to
          set up Autopilot for your application.
        </Note>
      </div>
    );
  }

  const enabled = status?.enabled;

  const act = async (fn: () => Promise<any>) => {
    setBusy(true);
    setErr("");
    try {
      await fn();
      refresh();
    } catch (e: any) {
      setErr(e.message ?? String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-8">
      {/* The nav calls this Optimization; the page called itself Autopilot with
          an emoji in the heading. One name, no emoji. */}
      <PageHeader
        glyph="spark"
        tone="accent"
        title="Optimization"
        subtitle="Routing tuned from your traffic, proven before kept"
        aside={
          <div className="flex items-center gap-3">
          <Pill tone={enabled ? "ok" : "mute"} dot>{enabled ? "Autopilot on" : "Autopilot off"}</Pill>
          {enabled ? (
            <button onClick={() => act(portal.autopilot.disable)} disabled={busy}
              className="rounded-md border border-edge px-4 py-2 text-sm hover:bg-edge">Turn OFF</button>
          ) : (
            <button onClick={() => setShowWizard(true)} disabled={busy}
              className="rounded-md bg-[color:var(--accent-strong)] px-4 py-2 text-sm font-medium text-white hover:opacity-90">
              Turn ON…
            </button>
          )}
          </div>
        }
      />
      {err && <div className="text-sm text-rose-400">{err}</div>}

      {/* what does it do — explainer cards */}
      {!enabled && !showWizard && <Explainer />}

      {showWizard && <Wizard busy={busy}
        onCancel={() => setShowWizard(false)}
        onEnable={(body) => act(() => portal.autopilot.enable(body)).then(() => setShowWizard(false))} />}

      {enabled && status && (
        <>
          {/* mode + autonomy */}
          <div className="grid gap-4 lg:grid-cols-3">
            <Panel title="Objective">
              <div className="text-2xl font-semibold">{status.mode}</div>
              <div className="text-xs text-slate-400">Optimization goal Autopilot balances for.</div>
            </Panel>
            <Panel title="Autonomy">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={status.autoApply}
                  onChange={(e) => act(() => portal.autopilot.setAutoApply(e.target.checked))} />
                <span>Auto-apply safe changes (canary + promote automatically)</span>
              </label>
              <div className="mt-1 text-xs text-slate-400">
                Off = Autopilot only suggests; you approve each change.
              </div>
            </Panel>
            <Panel title="Safety">
              <div className="flex items-center gap-2 text-sm">
                <span className="h-2 w-2 rounded-full bg-emerald-400" />
                Every change is verified, canaried, and auto-rolled-back on regression.
              </div>
              <button onClick={() => act(portal.autopilot.rollback)} disabled={busy}
                className="mt-2 rounded-md border border-edge px-3 py-1 text-xs hover:bg-edge">
                Roll back to previous policy
              </button>
            </Panel>
          </div>

          {/* current policy */}
          {status.activePolicy && (
            <Panel title="Current policy">
              {/* Provider order is a ranking, so it is drawn as one: first
                  choice first, each fallback after it. */}
              <div className="micro mb-2">Provider order</div>
              <ol className="flex flex-wrap items-center gap-1.5">
                {(status.activePolicy.providerOrder || []).map((p: string, i: number) => (
                  <li key={p + i} className="flex items-center gap-1.5">
                    <span className="flex items-center gap-1.5 rounded-full py-1 pl-1 pr-3 text-[12.5px] font-medium"
                          style={{ background: i === 0 ? "var(--wash-ok)" : "rgb(var(--card-rule) / .6)", color: i === 0 ? "var(--state-healthy-ink)" : "var(--text-2)" }}>
                      <span className="grid h-5 w-5 place-items-center rounded-full text-[10px] font-bold"
                            style={{ background: i === 0 ? "var(--state-healthy-ink)" : "rgb(var(--card-edge))", color: i === 0 ? "rgb(var(--ink))" : "var(--text-2)" }}>
                        {i + 1}
                      </span>
                      {p}
                    </span>
                    {i < (status.activePolicy.providerOrder || []).length - 1 && <span className="text-[11px] text-slate-500" aria-hidden>then</span>}
                  </li>
                ))}
              </ol>
              <div className="mt-4 flex flex-wrap gap-2">
                <Fact k="mode" v={String(status.activePolicy.routingMode).toLowerCase().replace("_", " ")} />
                <Fact k="hedge after" v={`${status.activePolicy.hedgeThresholdMs} ms`} />
                <Fact k="retries" v={status.activePolicy.maxRetries} />
                <Fact k="cost cap" v={`$${status.activePolicy.costCapUsd}`} />
                <Fact k="latency cap" v={`${status.activePolicy.latencyCapMs} ms`} />
              </div>
              <button onClick={() => act(portal.autopilot.propose)} disabled={busy}
                className="mt-3 rounded-md bg-[color:var(--accent-strong)] px-3 py-1.5 text-sm text-white">
                Run optimization now
              </button>
            </Panel>
          )}

          {/* recommendations */}
          <Panel title="Recommendations">
            {recs.filter((r) => r.status === "PENDING").length === 0 && (
              <div className="text-xs text-slate-500">No pending recommendations. Autopilot is watching your traffic.</div>
            )}
            <div className="space-y-2">
              {recs.filter((r) => r.status === "PENDING").map((r) => (
                <div key={r.id} className="rounded-md border border-edge bg-ink p-3 transition-all">
                  <div className="flex items-center gap-2">
                    <span className="font-medium">{r.title}</span>
                    <ConfidenceBadge value={r.confidence} />
                    <div className="ml-auto flex gap-2">
                      <button onClick={() => act(() => portal.autopilot.accept(r.id))} disabled={busy}
                        className="rounded bg-emerald-700 px-3 py-1 text-xs text-white">Accept → canary</button>
                      <button onClick={() => act(() => portal.autopilot.reject(r.id))} disabled={busy}
                        className="rounded border border-edge px-3 py-1 text-xs">Dismiss</button>
                    </div>
                  </div>
                  <Changes text={r.rationale} />
                  <div className="mt-1 text-xs text-slate-400">{r.rationale}</div>
                  {r.impact && <div className="mt-1 text-xs text-indigo-300">Impact: {r.impact}</div>}
                </div>
              ))}
            </div>
          </Panel>

          {/* canary status */}
          <Panel title="Canary rollouts">
            {canary.length === 0 && <div className="text-xs text-slate-500">No canary runs yet.</div>}
            {canary.slice(0, 5).map((c) => (
              <div key={c.id} className="mb-2">
                <div className="flex items-center gap-2 text-sm">
                  <StatusPill status={c.status} />
                  <span>candidate v-bundle {c.candidateBundleId} @ {c.percentage}%</span>
                </div>
                <div className="mt-1 h-2 w-full overflow-hidden rounded-full bg-edge">
                  <div className="h-full rounded-full bg-indigo-500 transition-all"
                    style={{ width: `${c.percentage}%` }} />
                </div>
              </div>
            ))}
          </Panel>

          {/* history timeline + rollbacks */}
          <div className="grid gap-4 lg:grid-cols-2">
            <Panel title="Policy history">
              {/* Versions as a line of dots, newest last: which were kept,
                  which were tried and rolled back, at a glance. */}
              {bundles.length > 0 && (
                <div className="mb-3 flex flex-wrap items-center gap-1" role="img"
                     aria-label={bundles.map((b) => `v${b.version} ${b.status}`).join(", ")}>
                  {[...bundles].reverse().map((b, i, all) => (
                    <span key={b.id} className="flex items-center gap-1">
                      <span className="h-3 w-3 rounded-full" title={`v${b.version} · ${b.status}`} style={{ background: bundleInk(b.status) }} />
                      {i < all.length - 1 && <span className="h-px w-3 bg-slate-400/50" aria-hidden />}
                    </span>
                  ))}
                </div>
              )}
              <ol className="space-y-1 text-sm">
                {bundles.map((b) => (
                  <li key={b.id} className="flex items-center gap-2">
                    <StatusPill status={b.status} />
                    <span>v{b.version}</span>
                    <span className="text-xs text-slate-500">{b.source}</span>
                  </li>
                ))}
              </ol>
            </Panel>
            <Panel title="Decision log">
              <ol className="space-y-1 text-xs">
                {decisions.slice(0, 12).map((d) => (
                  <li key={d.id} className="flex gap-2">
                    <span className="w-24 shrink-0 font-mono text-slate-500">{d.type}</span>
                    <span className="text-slate-300">{d.summary}</span>
                  </li>
                ))}
              </ol>
            </Panel>
          </div>

          {rollbacks.length > 0 && (
            <Panel title="Rollback events">
              {rollbacks.slice(0, 5).map((r) => (
                <div key={r.id} className="flex flex-wrap items-center gap-2 text-xs text-slate-400">
                  <Pill tone={r.automatic ? "warn" : "info"}>{r.automatic ? "automatic" : "manual"}</Pill>
                  {r.reason}
                </div>
              ))}
            </Panel>
          )}
        </>
      )}
    </div>
  );
}

function Explainer() {
  const items: [GlyphName, Tone, string, string, string][] = [
    ["spark", "accent", "Learns from your traffic", "Which provider and model actually performs best for the requests you send, rather than a static preference.", "best model per request"],
    ["check", "ok", "Proves before it promotes", "A change runs on a slice of traffic first and is kept only if it measures better. Otherwise it is rolled back.", "canary first, kept if better"],
    ["coin", "info", "Spends within your budget", "Hedging and timeouts are tuned against your cost and latency targets, not maximised blindly.", "within cost and latency targets"],
    ["route", "mute", "Reversible at any point", "Turning it off restores standard behaviour immediately, and every decision it made stays on the record.", "one switch back, full record"],
  ];
  return (
    <div>
      <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">What optimization does</h2>
      <div className="mt-4">
        <Grid cols={2}>
          {/* Four tiles, a phrase each. The sentence behind each is on hover. */}
          {items.map(([glyph, tone, title, body, short]) => (
            <div key={title} data-tip={body}>
              <Card>
                <CardHead glyph={glyph} tone={tone} title={title} sub={short} />
              </Card>
            </div>
          ))}
        </Grid>
      </div>
      <Note className="mt-5">
        Off by default. While it is off your app behaves exactly as it does today.
      </Note>
    </div>
  );
}

function Wizard({ onEnable, onCancel, busy }: {
  onEnable: (body: any) => void; onCancel: () => void; busy: boolean;
}) {
  const [step, setStep] = useState(1);
  const [appName, setAppName] = useState("My AI App");
  const [mode, setMode] = useState("BALANCED");
  const [maxCost, setMaxCost] = useState(0.02);
  const [maxLatency, setMaxLatency] = useState(5000);
  const [providers, setProviders] = useState<string[]>(["gemini", "groq", "mock"]);

  const toggleProvider = (p: string) =>
    setProviders((cur) => (cur.includes(p) ? cur.filter((x) => x !== p) : [...cur, p]));

  return (
    <div className="card rounded-lg border border-indigo-500/40 bg-panel p-5">
      <div className="flex items-center gap-2">
        <div className="font-medium">Set up Autopilot</div>
        <span className="text-xs text-slate-400">Step {step} of 3</span>
        <button onClick={onCancel} className="ml-auto text-sm text-slate-400 hover:text-slate-200">Cancel</button>
      </div>
      <div className="mt-2 h-1 w-full overflow-hidden rounded bg-ink">
        <div className="h-full bg-indigo-500 transition-all" style={{ width: `${(step / 3) * 100}%` }} />
      </div>

      {step === 1 && (
        <div className="mt-4 space-y-3">
          <div className="text-sm text-slate-300">Tell us about your app. No code needed.</div>
          <label className="block text-xs text-slate-400">Application name</label>
          <input value={appName} onChange={(e) => setAppName(e.target.value)}
            className="w-full field" />
          <label className="block text-xs text-slate-400">What matters most?</label>
          <div className="flex flex-wrap gap-2">
            {["BALANCED", "LOW_COST", "LOW_LATENCY", "HIGH_QUALITY", "SAFETY_FIRST"].map((m) => (
              <button key={m} onClick={() => setMode(m)}
                className={`rounded-md px-3 py-1.5 text-sm ${mode === m ? "bg-[color:var(--accent-strong)] text-white" : "border border-edge"}`}>
                {m.replace("_", " ").toLowerCase()}
              </button>
            ))}
          </div>
        </div>
      )}
      {step === 2 && (
        <div className="mt-4 space-y-3">
          <div className="text-sm text-slate-300">Set your budgets (Autopilot never exceeds these).</div>
          <label className="block text-xs text-slate-400">Max cost per request (USD): {maxCost}</label>
          <input type="range" min={0.001} max={0.1} step={0.001} value={maxCost}
            onChange={(e) => setMaxCost(parseFloat(e.target.value))} className="w-full" />
          <label className="block text-xs text-slate-400">Max latency (ms): {maxLatency}</label>
          <input type="range" min={500} max={15000} step={250} value={maxLatency}
            onChange={(e) => setMaxLatency(parseInt(e.target.value))} className="w-full" />
        </div>
      )}
      {step === 3 && (
        <div className="mt-4 space-y-3">
          <div className="text-sm text-slate-300">Which providers may Autopilot use?</div>
          <div className="flex flex-wrap gap-2">
            {["gemini", "groq", "mock"].map((p) => (
              <button key={p} onClick={() => toggleProvider(p)}
                className={`rounded-md px-3 py-1.5 text-sm capitalize ${providers.includes(p) ? "bg-[color:var(--accent-strong)] text-white" : "border border-edge"}`}>
                {p}
              </button>
            ))}
          </div>
          <div className="rounded-md border border-edge bg-ink p-3 text-xs text-slate-400">
            Review: <b>{appName}</b> · {mode.toLowerCase()} · ≤${maxCost}/req · ≤{maxLatency}ms · [{providers.join(", ")}].
            Autopilot starts in <b>suggest-only</b> mode — it won't change anything until you accept a recommendation.
          </div>
        </div>
      )}

      <div className="mt-4 flex gap-2">
        {step > 1 && <button onClick={() => setStep(step - 1)} className="rounded-md border border-edge px-3 py-1.5 text-sm">Back</button>}
        {step < 3 && <button onClick={() => setStep(step + 1)} className="ml-auto rounded-md bg-[color:var(--accent-strong)] px-3 py-1.5 text-sm text-white">Next</button>}
        {step === 3 && (
          <button disabled={busy || providers.length === 0}
            onClick={() => onEnable({ applicationName: appName, mode, maxCostPerRequest: maxCost, maxLatencyMs: maxLatency, allowedProviders: providers })}
            className="ml-auto rounded-md bg-emerald-600 px-4 py-1.5 text-sm font-medium text-white disabled:opacity-50">
            Enable Autopilot
          </button>
        )}
      </div>
    </div>
  );
}

/** A titled panel, on the shared card plane. */
function Panel({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <Card>
      <div className="mb-2 text-sm font-medium">{title}</div>
      {children}
    </Card>
  );
}
function ConfidenceBadge({ value }: { value: number }) {
  const pct = Math.round((value ?? 0) * 100);
  const ink = pct >= 66 ? "var(--state-healthy-ink)" : pct >= 33 ? "var(--state-warning-ink)" : "var(--text-3)";
  return (
    <span className="inline-flex items-center gap-1.5 text-[11px]" title={`confidence ${pct}%`}>
      <span className="relative h-1.5 w-12 overflow-hidden rounded-full" style={{ background: "rgb(var(--card-rule))" }}>
        <span className="absolute inset-y-0 left-0 rounded-full" style={{ width: `${pct}%`, background: ink }} />
      </span>
      <span className="readout" style={{ color: ink }}>{pct}%</span>
    </span>
  );
}
function Fact({ k, v }: { k: string; v: any }) {
  return (
    <span className="rounded-full px-2.5 py-1 text-[11.5px]" style={{ boxShadow: "inset 0 0 0 1px rgb(var(--card-edge))" }}>
      <span className="text-slate-500">{k}</span> <span className="readout text-slate-200">{String(v)}</span>
    </span>
  );
}
function bundleInk(status: string) {
  return status === "ACTIVE" || status === "PROMOTED"
    ? "var(--state-healthy-ink)"
    : status === "ROLLED_BACK"
      ? "var(--state-critical-ink)"
      : status === "CANARY" || status === "RUNNING"
        ? "var(--state-active-ink)"
        : status === "CANDIDATE"
          ? "var(--state-warning-ink)"
          : "rgb(var(--card-edge))";
}
function StatusPill({ status }: { status: string }) {
  const map: Record<string, string> = {
    ACTIVE: "bg-emerald-500/20 text-emerald-300", CANARY: "bg-indigo-500/20 text-indigo-300",
    RUNNING: "bg-indigo-500/20 text-indigo-300", PROMOTED: "bg-emerald-500/20 text-emerald-300",
    ROLLED_BACK: "bg-rose-500/20 text-rose-300", ARCHIVED: "bg-slate-500/20 text-slate-400",
    CANDIDATE: "bg-amber-500/20 text-amber-300",
  };
  return <span className={`rounded px-2 py-0.5 text-xs ${map[status] ?? "bg-slate-500/20 text-slate-300"}`}>{status}</span>;
}

/**
 * The changes a recommendation makes, lifted out of its sentence: each
 * "hedge threshold 900ms → 1350ms" becomes the old value struck through
 * beside the new one, so what would move is read before why.
 */
function Changes({ text }: { text?: string }) {
  const found = [...String(text ?? "").matchAll(/([A-Za-z][A-Za-z ]*?)\s+([^\s→]+)\s*→\s*([^\s(,.]+)/g)].map((m) => ({
    what: m[1].replace(/^(Adjust|Set|Change|Move)\s+/i, "").trim(),
    from: m[2],
    to: m[3],
  }));
  if (found.length === 0) return null;
  return (
    <div className="mt-2 flex flex-wrap gap-2">
      {found.map((c, i) => (
        <span key={i} className="inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[11.5px]"
              style={{ background: "var(--wash-info)" }}>
          <span className="text-slate-500">{c.what}</span>
          <span className="readout text-slate-500 line-through">{c.from}</span>
          <span aria-hidden className="text-slate-500">→</span>
          <span className="readout font-semibold" style={{ color: "var(--state-active-ink)" }}>{c.to}</span>
        </span>
      ))}
    </div>
  );
}
