import { useEffect, useState } from "react";
import { portal } from "../api";
import { PageHeader, Plane, Micro } from "../system/primitives";

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
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
  }, []);

  if (!signedIn) {
    return (
      <div className="mx-auto max-w-lg rounded-lg border border-edge bg-panel p-6 text-center">
        <div className="text-2xl">🧭</div>
        <h1 className="mt-2 text-lg font-semibold">Autopilot</h1>
        <p className="mt-1 text-sm text-slate-400">
          Sign in on the <a href="/portal" className="text-indigo-400 underline">Developer Portal</a> to
          set up Autopilot for your application.
        </p>
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
    <div className="space-y-6">
      {/* The nav calls this Optimization; the page called itself Autopilot with
          an emoji in the heading. One name, no emoji. */}
      <PageHeader
        title="Optimization"
        subtitle="Learns the best routing from your own traffic, and only changes what it can prove is better"
        aside={
          <div className="flex items-center gap-3">
          <span className={`rounded-full px-3 py-1 text-sm font-medium transition-colors ${
            enabled ? "bg-emerald-500/20 text-emerald-300" : "bg-slate-500/20 text-slate-300"}`}>
            {enabled ? "● Autopilot ON" : "○ Autopilot OFF"}
          </span>
          {enabled ? (
            <button onClick={() => act(portal.autopilot.disable)} disabled={busy}
              className="rounded-md border border-edge px-4 py-2 text-sm hover:bg-edge">Turn OFF</button>
          ) : (
            <button onClick={() => setShowWizard(true)} disabled={busy}
              className="rounded-md bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-500">
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
            <Card title="Objective">
              <div className="text-2xl font-semibold">{status.mode}</div>
              <div className="text-xs text-slate-400">Optimization goal Autopilot balances for.</div>
            </Card>
            <Card title="Autonomy">
              <label className="flex items-center gap-2 text-sm">
                <input type="checkbox" checked={status.autoApply}
                  onChange={(e) => act(() => portal.autopilot.setAutoApply(e.target.checked))} />
                <span>Auto-apply safe changes (canary + promote automatically)</span>
              </label>
              <div className="mt-1 text-xs text-slate-400">
                Off = Autopilot only suggests; you approve each change.
              </div>
            </Card>
            <Card title="Safety">
              <div className="flex items-center gap-2 text-sm">
                <span className="h-2 w-2 rounded-full bg-emerald-400" />
                Every change is verified, canaried, and auto-rolled-back on regression.
              </div>
              <button onClick={() => act(portal.autopilot.rollback)} disabled={busy}
                className="mt-2 rounded-md border border-edge px-3 py-1 text-xs hover:bg-edge">
                Roll back to previous policy
              </button>
            </Card>
          </div>

          {/* current policy */}
          {status.activePolicy && (
            <Card title="Current policy">
              <div className="grid grid-cols-2 gap-x-6 gap-y-1 text-sm sm:grid-cols-3">
                <Kv k="Routing mode" v={status.activePolicy.routingMode} />
                <Kv k="Provider order" v={(status.activePolicy.providerOrder || []).join(" → ")} />
                <Kv k="Hedge threshold" v={`${status.activePolicy.hedgeThresholdMs} ms`} />
                <Kv k="Max retries" v={status.activePolicy.maxRetries} />
                <Kv k="Cost cap" v={`$${status.activePolicy.costCapUsd}`} />
                <Kv k="Latency cap" v={`${status.activePolicy.latencyCapMs} ms`} />
              </div>
              <button onClick={() => act(portal.autopilot.propose)} disabled={busy}
                className="mt-3 rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white">
                Run optimization now
              </button>
            </Card>
          )}

          {/* recommendations */}
          <Card title="Recommendations">
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
                        className="rounded bg-emerald-600 px-3 py-1 text-xs text-white">Accept → canary</button>
                      <button onClick={() => act(() => portal.autopilot.reject(r.id))} disabled={busy}
                        className="rounded border border-edge px-3 py-1 text-xs">Dismiss</button>
                    </div>
                  </div>
                  <div className="mt-1 text-xs text-slate-400">{r.rationale}</div>
                  {r.impact && <div className="mt-1 text-xs text-indigo-300">Impact: {r.impact}</div>}
                </div>
              ))}
            </div>
          </Card>

          {/* canary status */}
          <Card title="Canary rollouts">
            {canary.length === 0 && <div className="text-xs text-slate-500">No canary runs yet.</div>}
            {canary.slice(0, 5).map((c) => (
              <div key={c.id} className="mb-2">
                <div className="flex items-center gap-2 text-sm">
                  <StatusPill status={c.status} />
                  <span>candidate v-bundle {c.candidateBundleId} @ {c.percentage}%</span>
                </div>
                <div className="mt-1 h-2 w-full overflow-hidden rounded-full bg-ink">
                  <div className="h-full rounded-full bg-indigo-500 transition-all"
                    style={{ width: `${c.percentage}%` }} />
                </div>
              </div>
            ))}
          </Card>

          {/* history timeline + rollbacks */}
          <div className="grid gap-4 lg:grid-cols-2">
            <Card title="Policy history">
              <ol className="space-y-1 text-sm">
                {bundles.map((b) => (
                  <li key={b.id} className="flex items-center gap-2">
                    <StatusPill status={b.status} />
                    <span>v{b.version}</span>
                    <span className="text-xs text-slate-500">{b.source}</span>
                  </li>
                ))}
              </ol>
            </Card>
            <Card title="Decision log">
              <ol className="space-y-1 text-xs">
                {decisions.slice(0, 12).map((d) => (
                  <li key={d.id} className="flex gap-2">
                    <span className="w-24 shrink-0 font-mono text-slate-500">{d.type}</span>
                    <span className="text-slate-300">{d.summary}</span>
                  </li>
                ))}
              </ol>
            </Card>
          </div>

          {rollbacks.length > 0 && (
            <Card title="Rollback events">
              {rollbacks.slice(0, 5).map((r) => (
                <div key={r.id} className="text-xs text-slate-400">
                  {r.automatic ? "⚙️ auto" : "👤 manual"} — {r.reason}
                </div>
              ))}
            </Card>
          )}
        </>
      )}
    </div>
  );
}

function Explainer() {
  const items: [string, string][] = [
    ["Learns from your traffic", "Which provider and model actually performs best for the requests you send, rather than a static preference."],
    ["Proves before it promotes", "A change runs on a slice of traffic first and is kept only if it measures better. Otherwise it is rolled back."],
    ["Spends within your budget", "Hedging and timeouts are tuned against your cost and latency targets, not maximised blindly."],
    ["Reversible at any point", "Turning it off restores standard behaviour immediately, and every decision it made stays on the record."],
  ];
  return (
    <Plane className="p-5">
      <Micro>What optimization does</Micro>
      <dl className="mt-4 grid gap-x-10 gap-y-5 sm:grid-cols-2">
        {items.map(([title, body]) => (
          <div key={title} className="border-l border-edge pl-4">
            <dt className="text-sm font-medium text-slate-200">{title}</dt>
            <dd className="mt-1 text-xs leading-relaxed text-slate-500">{body}</dd>
          </div>
        ))}
      </dl>
      <p className="mt-5 border-t border-edge/70 pt-4 text-xs text-slate-500">
        Off by default. While it is off your app behaves exactly as it does today.
      </p>
    </Plane>
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
    <div className="rounded-lg border border-indigo-500/40 bg-panel p-5">
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
            className="w-full rounded-md border border-edge bg-ink px-3 py-2 text-sm" />
          <label className="block text-xs text-slate-400">What matters most?</label>
          <div className="flex flex-wrap gap-2">
            {["BALANCED", "LOW_COST", "LOW_LATENCY", "HIGH_QUALITY", "SAFETY_FIRST"].map((m) => (
              <button key={m} onClick={() => setMode(m)}
                className={`rounded-md px-3 py-1.5 text-sm ${mode === m ? "bg-indigo-600 text-white" : "border border-edge"}`}>
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
                className={`rounded-md px-3 py-1.5 text-sm capitalize ${providers.includes(p) ? "bg-indigo-600 text-white" : "border border-edge"}`}>
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
        {step < 3 && <button onClick={() => setStep(step + 1)} className="ml-auto rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white">Next</button>}
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

function Card({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-edge bg-panel p-4">
      <div className="mb-2 text-sm font-medium">{title}</div>
      {children}
    </div>
  );
}
function Kv({ k, v }: { k: string; v: any }) {
  return (
    <div><span className="text-slate-400">{k}: </span><span className="font-mono text-xs">{String(v)}</span></div>
  );
}
function ConfidenceBadge({ value }: { value: number }) {
  const pct = Math.round((value ?? 0) * 100);
  const color = pct >= 66 ? "text-emerald-300" : pct >= 33 ? "text-amber-300" : "text-slate-400";
  return <span className={`text-xs ${color}`}>confidence {pct}%</span>;
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
