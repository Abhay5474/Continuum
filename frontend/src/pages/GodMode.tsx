import { useEffect, useMemo, useRef, useState } from "react";
import { Link } from "react-router-dom";
import { portal } from "../api";

/**
 * Adaptive Policy: the autonomous memory & policy engine.
 * Opt-in, reversible, OFF by default. Visualizes the invisible: memory tiers,
 * context weight, MemAct decisions and the counterfactual multiverse simulator.
 */
export default function GodMode() {
  const loggedIn = !!portal.session();
  const [status, setStatus] = useState<any | null>(null);
  const [graph, setGraph] = useState<any | null>(null);
  const [actions, setActions] = useState<any[]>([]);
  const [sims, setSims] = useState<any[]>([]);
  const [err, setErr] = useState("");
  const [pinch, setPinch] = useState(0); // increments trigger the gauge pinch animation
  const [simRunning, setSimRunning] = useState<string | null>(null);
  const [lastSim, setLastSim] = useState<any | null>(null);
  const [wizardStep, setWizardStep] = useState(0);
  const [ingestText, setIngestText] = useState("");
  const [retrieveQ, setRetrieveQ] = useState("");
  const [retrieved, setRetrieved] = useState<any[]>([]);
  const prevWorking = useRef<number>(0);

  const refresh = () => {
    if (!loggedIn) return;
    portal.godmode.status().then((s) => {
      const w = s?.memory?.working?.items ?? 0;
      if (prevWorking.current > w && w >= 0) setPinch((p) => p + 1); // memory compressed
      prevWorking.current = w;
      setStatus(s);
    }).catch((e) => setErr(String(e.message ?? e)));
    portal.godmode.actions().then(setActions).catch(() => {});
    portal.godmode.simulations().then(setSims).catch(() => {});
    portal.godmode.graph().then(setGraph).catch(() => {});
  };
  useEffect(() => {
    refresh();
    const t = setInterval(refresh, 4000);
    return () => clearInterval(t);
  }, [loggedIn]);

  const enabled = !!status?.enabled;
  const fill = status?.memory?.contextFillFraction ?? 0;

  const toggle = async () => {
    setErr("");
    try {
      setStatus(enabled ? await portal.godmode.disable() : await portal.godmode.enable());
      setWizardStep(0);
    } catch (e: any) {
      setErr(e.message ?? String(e));
    }
  };

  const runSim = async (scenario: string) => {
    setSimRunning(scenario);
    setLastSim(null);
    try {
      const sim = await portal.godmode.simulate(scenario);
      // Let the multiverse branches animate before revealing the collapse.
      setTimeout(() => {
        setLastSim(sim);
        setSimRunning(null);
        refresh();
      }, 1700);
    } catch (e: any) {
      setErr(e.message ?? String(e));
      setSimRunning(null);
    }
  };

  const streaming = useMemo(
    () => actions.some((a) => Date.now() - new Date(a.createdAt).getTime() < 30000),
    [actions]
  );

  if (!loggedIn) {
    return (
      <div className="glass mx-auto mt-16 max-w-lg p-8 text-center animate-fade-up">
        
        <h1 className="mt-2 text-xl font-semibold text-gradient">Adaptive Policy</h1>
        <p className="mt-2 text-sm text-slate-400">
          The autonomous memory &amp; policy engine is scoped to your developer account.
          Sign in through the Developer Portal to continue.
        </p>
        <Link to="/portal"
          className="mt-4 inline-block rounded-lg bg-gradient-to-r from-aurora to-neon px-4 py-2 text-sm font-medium text-ink">
          Open Developer Portal →
        </Link>
      </div>
    );
  }

  return (
    <div className="space-y-6">
      {/* ---- hero: the Adaptive Policy toggle ---- */}
      <div className={`glass relative overflow-hidden p-6 animate-fade-up ${enabled ? "animate-glow-pulse" : ""}`}>
        <div className="flex flex-wrap items-center gap-6">
          <div className="min-w-[260px] flex-1">
            <div className="flex items-center gap-3">
              <h1 className="text-2xl font-bold tracking-tight text-gradient">Adaptive Policy</h1>
              {enabled && (
                <span className="rounded-full bg-aurora/20 px-2.5 py-0.5 text-xs font-semibold text-indigo-300">
                  AUTONOMOUS
                </span>
              )}
            </div>
            <p className="mt-1 max-w-xl text-sm text-slate-400">
              A self-tuning intelligence layer: 4-tier learned memory, Memory-as-Action policies,
              and a digital twin that proves every policy offline before it touches live traffic.
              Opt-in. Reversible. Off means <span className="text-slate-300">bit-identical</span> to
              the classic gateway.
            </p>
          </div>
          {/* the powerful toggle */}
          <button onClick={toggle} aria-label="Toggle Adaptive Policy"
            className={`relative h-16 w-32 shrink-0 rounded-full border transition-all duration-500 ${
              enabled
                ? "border-transparent bg-gradient-to-r from-aurora to-neon shadow-glow"
                : "border-edge bg-ink"
            }`}>
            <span className={`absolute top-1.5 flex h-[52px] w-[52px] items-center justify-center rounded-full
              text-xl transition-all duration-500 ${
                enabled ? "left-[72px] bg-ink text-neon" : "left-1.5 bg-panel text-slate-500"
              }`}>
              ⚡
            </span>
            <span className={`absolute inset-y-0 flex items-center text-xs font-bold tracking-widest ${
              enabled ? "left-4 text-ink" : "right-4 text-slate-500"
            }`}>
              {enabled ? "ON" : "OFF"}
            </span>
          </button>
        </div>
        {err && <div className="mt-3 text-xs text-rose-400">{err}</div>}
      </div>

      {/* ---- onboarding wizard when off ---- */}
      {!enabled && (
        <div className="glass p-6 animate-fade-up">
          <div className="text-sm font-medium">What happens when you switch it on?</div>
          <div className="mt-4 grid gap-4 sm:grid-cols-3">
            {[
              ["\u25C6", "It remembers", "Gateway exchanges flow into a 4-tier memory: working context is summarized into episodes, distilled into an experience graph, and archived — bounded by TTLs and quotas you control."],
              ["🌌", "It simulates", "Before any policy change reaches production, a digital twin replays it against your real historical traffic and vetoes confident regressions — the same Bayesian rules as a live canary."],
              ["🛡️", "It stays safe", "Every autonomous action is audited. Nothing is shared across tenants. One click turns it off and restores the exact pre-God-Mode path."],
            ].map(([icon, title, text], i) => (
              <button key={title as string} onClick={() => setWizardStep(i)}
                className={`rounded-xl border p-4 text-left transition-all ${
                  wizardStep === i ? "border-aurora/60 bg-aurora/5 shadow-glow-sm" : "border-edge bg-ink/40 hover:border-aurora/30"
                }`}>
                <div className="text-2xl">{icon}</div>
                <div className="mt-2 text-sm font-semibold">{title}</div>
                <div className="mt-1 text-xs leading-relaxed text-slate-400">{text}</div>
              </button>
            ))}
          </div>
          <div className="mt-4 flex items-center gap-3">
            <div className="flex gap-1.5">
              {[0, 1, 2].map((i) => (
                <span key={i} className={`h-1.5 w-6 rounded-full transition-colors ${wizardStep >= i ? "bg-aurora" : "bg-edge"}`} />
              ))}
            </div>
            {wizardStep < 2 ? (
              <button onClick={() => setWizardStep(wizardStep + 1)}
                className="ml-auto rounded-lg border border-edge px-4 py-1.5 text-sm hover:border-aurora/50">
                Next
              </button>
            ) : (
              <button onClick={toggle}
                className="ml-auto rounded-lg bg-gradient-to-r from-aurora to-neon px-5 py-1.5 text-sm font-semibold text-ink shadow-glow">
                Enable Adaptive Policy
              </button>
            )}
          </div>
        </div>
      )}

      {enabled && (
        <>
          {/* ---- live row: context gauge + memory tiers ---- */}
          <div className="grid gap-6 lg:grid-cols-3 animate-fade-up">
            <div className="glass p-5">
              <div className="flex items-center justify-between">
                <div className="text-sm font-medium">Context Weight</div>
                <StreamIndicator active={streaming} />
              </div>
              <ContextGauge fill={fill} pinchKey={pinch} />
              <div className="mt-1 text-center text-xs text-slate-500">
                {status?.memory?.working?.tokens ?? 0} / {status?.contextBudgetTokens ?? 0} working tokens
                {pinch > 0 && <span className="ml-1 text-neon">· compressed ×{pinch}</span>}
              </div>
              <div className="mt-3 flex gap-2">
                <input value={ingestText} onChange={(e) => setIngestText(e.target.value)}
                  placeholder="Feed the working memory…"
                  className="min-w-0 flex-1 rounded-lg border border-edge bg-ink px-3 py-1.5 text-xs" />
                <button
                  onClick={() => portal.godmode.ingest("manual", "user", ingestText).then(() => { setIngestText(""); refresh(); })}
                  disabled={!ingestText}
                  className="rounded-lg bg-aurora/20 px-3 py-1.5 text-xs text-indigo-300 hover:bg-aurora/30 disabled:opacity-40">
                  Ingest
                </button>
              </div>
            </div>

            <div className="glass p-5 lg:col-span-2">
              <div className="flex items-center justify-between">
                <div className="text-sm font-medium">Memory Hierarchy</div>
                <button onClick={() => portal.godmode.consolidate().then(refresh)}
                  className="rounded-lg border border-edge px-3 py-1 text-xs hover:border-neon/50 hover:text-neon">
                  Consolidate now ⟳
                </button>
              </div>
              <div className="mt-4 flex items-stretch gap-2">
                <Tier icon="\u25CF" name="Working" value={status?.memory?.working?.items ?? 0}
                  sub={`${status?.memory?.working?.tokens ?? 0} tok`} hue="text-neon" />
                <FlowArrow label="summarize" />
                <Tier icon="📼" name="Episodic" value={status?.memory?.episodic?.items ?? 0} sub="summaries" hue="text-indigo-300" />
                <FlowArrow label="distill" />
                <Tier icon="🕸" name="Semantic" value={status?.memory?.semantic?.nodes ?? 0} sub="experiences" hue="text-emerald-300" />
                <FlowArrow label="decay" />
                <Tier icon="🧊" name="Archive" value={status?.memory?.archive?.spans ?? 0} sub="cold spans" hue="text-slate-400" />
              </div>
              <div className="mt-4 flex gap-2">
                <input value={retrieveQ} onChange={(e) => setRetrieveQ(e.target.value)}
                  placeholder="Query long-term memory…"
                  className="min-w-0 flex-1 rounded-lg border border-edge bg-ink px-3 py-1.5 text-xs" />
                <button onClick={() => portal.godmode.retrieve(retrieveQ).then(setRetrieved)}
                  disabled={!retrieveQ}
                  className="rounded-lg bg-neon/15 px-3 py-1.5 text-xs text-neon hover:bg-neon/25 disabled:opacity-40">
                  Retrieve
                </button>
                <button onClick={() => { if (confirm("Wipe all memory tiers for your account?")) portal.godmode.wipe().then(refresh); }}
                  className="rounded-lg border border-rose-500/30 px-3 py-1.5 text-xs text-rose-300 hover:bg-rose-500/10">
                  Wipe
                </button>
              </div>
              {retrieved.length > 0 && (
                <div className="mt-2 space-y-1">
                  {retrieved.map((r, i) => (
                    <div key={i} className="rounded-lg bg-ink/60 px-3 py-1.5 text-xs animate-fade-up">
                      <span className={`mr-2 rounded px-1.5 py-0.5 text-[10px] font-semibold ${
                        r.tier === "SEMANTIC" ? "bg-emerald-500/20 text-emerald-300" : "bg-indigo-500/20 text-indigo-300"}`}>
                        {r.tier}
                      </span>
                      <span className="text-slate-300">{r.text}</span>
                      <span className="ml-2 text-slate-500">{(r.score * 100).toFixed(0)}%</span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          </div>

          {/* ---- the multiverse simulator ---- */}
          <div className="glass p-5 animate-fade-up">
            <div className="flex flex-wrap items-center gap-3">
              <div>
                <div className="text-sm font-medium">Counterfactual Multiverse — Digital Twin</div>
                <div className="text-xs text-slate-500">
                  Replays candidate policies against your real historical traffic. Verdicts use the exact
                  Bayesian canary rules — offline, before live traffic.
                </div>
              </div>
              <div className="ml-auto flex gap-2">
                {["HISTORICAL_REPLAY", "PROVIDER_OUTAGE", "HALLUCINATION_STORM"].map((s) => (
                  <button key={s} onClick={() => runSim(s)} disabled={!!simRunning}
                    className="rounded-lg border border-edge px-3 py-1.5 text-xs hover:border-aurora/60 hover:shadow-glow-sm disabled:opacity-40">
                    {s === "HISTORICAL_REPLAY" ? "▶ Replay history" : s === "PROVIDER_OUTAGE" ? "⚠ Outage stress" : "👻 Hallucination storm"}
                  </button>
                ))}
              </div>
            </div>
            <Multiverse running={!!simRunning} verdict={lastSim?.verdict ?? null} />
            {lastSim && (
              <div className="mt-2 flex flex-wrap items-center gap-2 text-xs animate-fade-up">
                <VerdictBadge verdict={lastSim.verdict} />
                <span className="text-slate-400">{lastSim.reason}</span>
                <span className="ml-auto text-slate-500">
                  {lastSim.replayedRequests} simulated requests · {lastSim.scenario}
                </span>
              </div>
            )}
            {sims.length > 0 && (
              <div className="mt-3 max-h-36 space-y-1 overflow-y-auto">
                {sims.map((s) => (
                  <div key={s.id} className="flex items-center gap-2 rounded-lg bg-ink/50 px-3 py-1.5 text-xs">
                    <VerdictBadge verdict={s.verdict} />
                    <span className="text-slate-500">{s.scenario}</span>
                    <span className="truncate text-slate-400">{s.reason}</span>
                    <span className="ml-auto whitespace-nowrap text-slate-600">
                      {new Date(s.createdAt).toLocaleTimeString()}
                    </span>
                  </div>
                ))}
              </div>
            )}
          </div>

          {/* ---- experience graph + MemAct ---- */}
          <div className="grid gap-6 lg:grid-cols-2 animate-fade-up">
            <div className="glass p-5">
              <div className="text-sm font-medium">Experience Graph</div>
              <ExperienceGraph graph={graph} />
            </div>
            <div className="glass p-5">
              <div className="text-sm font-medium">Memory-as-Action policy (Thompson sampling)</div>
              <div className="mt-3 space-y-2">
                {status?.memAct && Object.entries(status.memAct).map(([name, v]: any) => (
                  <div key={name} className="text-xs">
                    <div className="flex justify-between text-slate-400">
                      <span>{name.replace(/_/g, " ").toLowerCase()}</span>
                      <span>{(v.posteriorMean * 100).toFixed(0)}% · {v.successes}✓ {v.failures}✗</span>
                    </div>
                    <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-ink">
                      <div className="h-full rounded-full bg-gradient-to-r from-aurora to-neon transition-all duration-700"
                        style={{ width: `${v.posteriorMean * 100}%` }} />
                    </div>
                  </div>
                ))}
              </div>
              <div className="mt-4 text-xs font-medium text-slate-400">Autonomous action feed</div>
              <div className="mt-1 max-h-40 space-y-1 overflow-y-auto">
                {actions.map((a) => (
                  <div key={a.id} className="flex items-center gap-2 text-xs">
                    <span className={`rounded px-1.5 py-0.5 text-[10px] font-semibold ${actionColor(a.action)}`}>{a.action}</span>
                    {a.tier && <span className="text-slate-500">{a.tier}</span>}
                    <span className="truncate text-slate-400">{a.detail}</span>
                    <span className="ml-auto whitespace-nowrap text-slate-600">
                      {new Date(a.createdAt).toLocaleTimeString()}
                    </span>
                  </div>
                ))}
                {actions.length === 0 && <div className="text-xs text-slate-600">no autonomous actions yet</div>}
              </div>
            </div>
          </div>
        </>
      )}
    </div>
  );
}

/* ---------- visual components ---------- */

function ContextGauge({ fill, pinchKey }: { fill: number; pinchKey: number }) {
  const pct = Math.max(0, Math.min(1, fill));
  const hue = 140 - pct * 140; // green → red
  const R = 70, C = Math.PI * R; // half circle
  return (
    <div key={pinchKey} className={`mx-auto mt-2 w-44 ${pinchKey > 0 ? "animate-pinch" : ""}`}>
      <svg viewBox="0 0 180 100" className="w-full">
        <path d="M 20 95 A 70 70 0 0 1 160 95" fill="none" stroke="#1e2739" strokeWidth="12" strokeLinecap="round" />
        <path d="M 20 95 A 70 70 0 0 1 160 95" fill="none"
          stroke={`hsl(${hue} 85% 55%)`} strokeWidth="12" strokeLinecap="round"
          strokeDasharray={`${C}`} strokeDashoffset={`${C * (1 - pct)}`}
          style={{ transition: "stroke-dashoffset 900ms cubic-bezier(0.22,1,0.36,1), stroke 900ms ease",
            filter: `drop-shadow(0 0 6px hsl(${hue} 85% 55% / 0.7))` }} />
        <text x="90" y="78" textAnchor="middle" fontSize="24" fontWeight="700" fill={`hsl(${hue} 85% 65%)`}>
          {(pct * 100).toFixed(0)}%
        </text>
        <text x="90" y="94" textAnchor="middle" fontSize="9" fill="#64748b">context window</text>
      </svg>
    </div>
  );
}

function StreamIndicator({ active }: { active: boolean }) {
  return (
    <div className="flex items-center gap-1.5" title={active ? "tokens streaming" : "idle"}>
      {[0, 1, 2].map((i) => (
        <span key={i}
          className={`h-1.5 w-1.5 rounded-full ${active ? "bg-neon animate-stream-dot shadow-glow-cyan" : "bg-edge"}`}
          style={{ animationDelay: `${i * 0.2}s` }} />
      ))}
      <span className={`text-[10px] ${active ? "text-neon" : "text-slate-600"}`}>{active ? "LIVE" : "IDLE"}</span>
    </div>
  );
}

function Tier({ icon, name, value, sub, hue }: { icon: string; name: string; value: number; sub: string; hue: string }) {
  return (
    <div className="flex-1 rounded-xl border border-edge bg-ink/50 p-3 text-center transition-all hover:border-aurora/40 hover:shadow-glow-sm">
      <div className="text-lg">{icon}</div>
      <div className={`text-xl font-bold ${hue}`}>{value}</div>
      <div className="text-xs font-medium">{name}</div>
      <div className="text-[10px] text-slate-500">{sub}</div>
    </div>
  );
}

function FlowArrow({ label }: { label: string }) {
  return (
    <div className="flex w-12 shrink-0 flex-col items-center justify-center gap-1">
      <div className="shimmer-line h-0.5 w-full rounded-full" />
      <span className="text-[9px] text-slate-500">{label}</span>
    </div>
  );
}

function Multiverse({ running, verdict }: { running: boolean; verdict: string | null }) {
  const branches = [
    { d: "M 10 60 C 150 60, 250 18, 590 14", cls: "stroke-aurora/70" },
    { d: "M 10 60 C 150 60, 250 40, 590 38", cls: "stroke-neon/70" },
    { d: "M 10 60 C 150 60, 250 84, 590 86", cls: "stroke-indigo-400/50" },
    { d: "M 10 60 C 150 60, 250 104, 590 108", cls: "stroke-cyan-400/40" },
  ];
  const survivor = verdict === "PROMOTE" ? 0 : 1; // promoted branch or the baseline
  return (
    <div className="mt-4 overflow-x-auto">
      <svg viewBox="0 0 600 120" className="h-32 w-full min-w-[480px]">
        {/* baseline trunk */}
        <line x1="10" y1="60" x2="590" y2="60" stroke="#334155" strokeWidth="2" strokeDasharray="4 4" />
        <circle cx="10" cy="60" r="5" fill="#6366f1">
          {running && <animate attributeName="r" values="4;7;4" dur="1s" repeatCount="indefinite" />}
        </circle>
        {(running || verdict) &&
          branches.map((b, i) => (
            <path key={`${b.d}-${running}-${verdict}`} d={b.d} fill="none" strokeWidth="2"
              className={`${b.cls} ${running ? "animate-branch-grow" : verdict && i !== survivor ? "animate-collapse" : ""}`}
              strokeDasharray="600" strokeDashoffset={running ? undefined : "0"}
              style={{ animationDelay: `${i * 0.15}s` }} />
          ))}
        {verdict && !running && (
          <circle cx="590" cy={survivor === 0 ? 14 : 38} r="5"
            fill={verdict === "PROMOTE" ? "#34d399" : verdict === "ROLLBACK" ? "#fb7185" : "#facc15"}>
            <animate attributeName="opacity" values="0.4;1;0.4" dur="1.6s" repeatCount="indefinite" />
          </circle>
        )}
        <text x="14" y="50" fontSize="9" fill="#64748b">now</text>
        <text x="540" y="58" fontSize="9" fill="#64748b">{running ? "simulating…" : "futures"}</text>
      </svg>
    </div>
  );
}

function ExperienceGraph({ graph }: { graph: any }) {
  const nodes: any[] = (graph?.nodes ?? []).slice(0, 12);
  const edges: any[] = graph?.edges ?? [];
  if (nodes.length === 0) {
    return (
      <div className="mt-6 rounded-lg border border-dashed border-edge py-8 text-center text-xs text-slate-600">
        No experiences distilled yet — memories become graph nodes as episodes are consolidated.
      </div>
    );
  }
  const pos = new Map<number, { x: number; y: number }>();
  nodes.forEach((n, i) => {
    const a = (2 * Math.PI * i) / nodes.length - Math.PI / 2;
    pos.set(n.id, { x: 150 + 105 * Math.cos(a), y: 120 + 88 * Math.sin(a) });
  });
  return (
    <svg viewBox="0 0 300 240" className="mt-2 w-full">
      {edges.map((e, i) => {
        const a = pos.get(e.from), b = pos.get(e.to);
        return a && b ? (
          <line key={i} x1={a.x} y1={a.y} x2={b.x} y2={b.y}
            stroke="#6366f1" strokeOpacity={0.15 + e.weight * 0.4} strokeWidth={1 + e.weight} />
        ) : null;
      })}
      {nodes.map((n) => {
        const p = pos.get(n.id)!;
        return (
          <g key={n.id} className="cursor-pointer">
            <circle cx={p.x} cy={p.y} r={5 + n.utility * 6} fill="#11141b" stroke="#3b82f6"
              strokeOpacity={0.4 + n.utility * 0.6} strokeWidth="1.5"
              style={{ filter: "drop-shadow(0 0 4px rgba(59,130,246,0.4))" }}>
              <title>{n.text} (utility {(n.utility * 100).toFixed(0)}%, used {n.uses}×)</title>
            </circle>
          </g>
        );
      })}
    </svg>
  );
}

function VerdictBadge({ verdict }: { verdict: string }) {
  const cls = verdict === "PROMOTE" ? "bg-emerald-500/20 text-emerald-300"
    : verdict === "ROLLBACK" ? "bg-rose-500/20 text-rose-300" : "bg-amber-500/20 text-amber-300";
  return <span className={`rounded px-2 py-0.5 text-[10px] font-bold ${cls}`}>{verdict}</span>;
}

function actionColor(action: string) {
  switch (action) {
    case "SUMMARIZE": return "bg-indigo-500/20 text-indigo-300";
    case "PROMOTE": return "bg-emerald-500/20 text-emerald-300";
    case "PRUNE": return "bg-amber-500/20 text-amber-300";
    case "ARCHIVE": return "bg-slate-500/20 text-slate-300";
    case "RETRIEVE": return "bg-blue-500/20 text-sky-300";
    case "STORE": return "bg-indigo-500/20 text-indigo-300";
    default: return "bg-slate-600/20 text-slate-400";
  }
}
