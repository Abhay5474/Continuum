import { useEffect, useState } from "react";
import { InfoTip, Switch } from "../system/primitives";
import { Link } from "react-router-dom";
import { portal } from "../api";
import { Chip, Spark, type Tone } from "../system/hub";
import { useRecentRequests } from "../system/traffic";
import { useToast, Spinner, CopyButton, CodeBlock } from "../components/ui";
import GatewayResult from "../components/GatewayResult";

const PROVIDERS = ["gemini", "groq", "openai"];

export default function DeveloperPortal() {
  const [authed, setAuthed] = useState(!!portal.session());
  if (!authed) {
    return <AuthGate onAuthed={() => setAuthed(true)} />;
  }
  return <Portal onLogout={() => setAuthed(false)} />;
}

function AuthGate({ onAuthed }: { onAuthed: () => void }) {
  const [mode, setMode] = useState<"login" | "signup">("signup");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState("");
  const [busy, setBusy] = useState(false);
  const toast = useToast();

  const submit = async () => {
    setErr("");
    setBusy(true);
    try {
      if (mode === "signup") await portal.signup(name, email, password);
      else await portal.login(email, password);
      toast(mode === "signup" ? "Account created" : "Welcome back", "success");
      onAuthed();
    } catch (e: any) {
      setErr(e.message ?? String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="relative mx-auto mt-8 max-w-md animate-fade-up">
      <div className="pointer-events-none absolute inset-0 -z-10 rounded-3xl bg-aurora/10 blur-3xl" />
      <div className="plane p-7">
        <div className="flex items-center gap-2.5">
          <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br from-aurora to-neon text-lg font-bold text-ink shadow-glow-sm">
            ⟳
          </span>
          <div>
            <h1 className="text-[20px] font-semibold tracking-[-0.011em] text-slate-100">Developer Portal</h1>
            <p className="mt-1 text-[13px] text-slate-500">Your API keys, credentials and analytics</p>
          </div>
        </div>

        <div className="mt-5 grid grid-cols-2 gap-1 rounded-lg border border-edge p-1 text-sm">
          <button
            onClick={() => setMode("signup")}
            className={`rounded-md px-3 py-1.5 transition-all ${mode === "signup" ? "bg-gradient-to-r from-aurora to-neon font-semibold text-ink" : "text-slate-400 hover:text-slate-200"}`}
          >
            Sign up
          </button>
          <button
            onClick={() => setMode("login")}
            className={`rounded-md px-3 py-1.5 transition-all ${mode === "login" ? "bg-gradient-to-r from-aurora to-neon font-semibold text-ink" : "text-slate-400 hover:text-slate-200"}`}
          >
            Log in
          </button>
        </div>

        {mode === "signup" && (
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Name"
            className="mt-4 w-full field" />
        )}
        <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="Email"
          onKeyDown={(e) => e.key === "Enter" && submit()}
          className="mt-3 w-full field" />
        <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" placeholder="Password"
          onKeyDown={(e) => e.key === "Enter" && submit()}
          className="mt-3 w-full field" />
        <button
          onClick={submit}
          disabled={busy}
          className="mt-4 flex w-full items-center justify-center gap-2 rounded-lg bg-gradient-to-r from-aurora to-neon px-3 py-2.5 text-sm font-semibold text-ink shadow-glow-sm transition-transform hover:-translate-y-0.5 disabled:opacity-60"
        >
          {busy && <Spinner className="h-4 w-4 border-ink/40 border-t-ink" />}
          {mode === "signup" ? "Create account →" : "Log in →"}
        </button>
        {err && <div className="mt-3 rounded-lg border border-rose-500/30 bg-rose-500/5 px-3 py-2 text-sm text-rose-300">{err}</div>}
        <p className="mt-4 text-center text-xs text-slate-500 max-w-2xl leading-relaxed">
          New here? <Link to="/docs" className="text-neon hover:underline">Read the quickstart</Link>
        </p>
      </div>
    </div>
  );
}

function Portal({ onLogout }: { onLogout: () => void }) {
  const [me, setMe] = useState<any>(null);
  const [keys, setKeys] = useState<any[]>([]);
  const [creds, setCreds] = useState<any[]>([]);
  const [stats, setStats] = useState<any>(null);
  const { series: recent } = useRecentRequests(60, 8000);
  const [newKey, setNewKey] = useState("");
  const [verifyStatus, setVerifyStatus] = useState<Record<string, any>>({});
  const [secretInputs, setSecretInputs] = useState<Record<string, string>>({});
  const [playPrompt, setPlayPrompt] = useState("Provide first aid for a dog leg injury");
  const [playOut, setPlayOut] = useState<any>(null);
  const [playBusy, setPlayBusy] = useState(false);
  const [madeFirstCall, setMadeFirstCall] = useState(false);
  const toast = useToast();

  const refresh = () => {
    portal.me().then(setMe).catch(handleAuthErr);
    portal.keys().then(setKeys).catch(() => {});
    portal.credentials().then(setCreds).catch(() => {});
    portal.stats().then(setStats).catch(() => {});
  };
  const handleAuthErr = () => {
    portal.logout();
    onLogout();
  };
  useEffect(() => {
    refresh();
  }, []);

  const issueKey = async () => {
    try {
      const r = await portal.issueKey();
      setNewKey(r.apiKey);
      toast("API key created ✓", "success");
      refresh();
    } catch (e: any) {
      toast(e.message ?? "Could not issue key", "error");
    }
  };
  const revokeKey = async (id: number) => {
    if (!confirm("Revoke this key? Apps using it will stop working immediately.")) return;
    try {
      await portal.revokeKey(id);
      toast("Key revoked", "success");
      refresh();
    } catch (e: any) {
      toast(e.message ?? "Could not revoke key", "error");
    }
  };
  const storeCred = async (provider: string) => {
    const secret = secretInputs[provider];
    if (!secret) return;
    try {
      await portal.storeCredential(provider, secret);
      setSecretInputs({ ...secretInputs, [provider]: "" });
      toast(`${provider} key saved (encrypted)`, "success");
      refresh();
    } catch (e: any) {
      toast(e.message ?? "Could not save credential", "error");
    }
  };
  const verify = async (provider: string) => {
    setVerifyStatus({ ...verifyStatus, [provider]: { checking: true } });
    try {
      const r = await portal.verifyCredential(provider);
      setVerifyStatus({ ...verifyStatus, [provider]: r });
      toast(r.valid ? `${provider} key verified ✓` : `${provider} key invalid`, r.valid ? "success" : "error");
    } catch (e: any) {
      setVerifyStatus({ ...verifyStatus, [provider]: { valid: false, message: e.message } });
    }
  };
  const toggle = async (v: boolean) => {
    await portal.setRoutingPreference(v);
    setMe({ ...me, useOwnKeysPrimary: v });
    toast(v ? "Using your keys as primary" : "Using platform keys", "info");
  };
  const runPlay = async () => {
    setPlayOut(null);
    setPlayBusy(true);
    try {
      const r = await portal.playground({ model: "auto", messages: [{ role: "user", content: playPrompt }], maxTokens: 200 });
      setPlayOut(r);
      if (!madeFirstCall) {
        setMadeFirstCall(true);
        toast("You made your first call", "success");
      }
    } catch (e: any) {
      setPlayOut({ error: e.message });
      toast(e.message ?? "Call failed", "error");
    } finally {
      setPlayBusy(false);
    }
  };

  const configured = new Set(creds.map((c) => c.provider));

  // --- Context Optimizer (opt-in, OFF by default) ---
  const [v7Enabled, setV7Enabled] = useState<boolean | null>(null);
  useEffect(() => {
    portal.v7.status().then((s) => setV7Enabled(!!s.enabled)).catch(() => setV7Enabled(false));
  }, []);
  const toggleV7 = async () => {
    try {
      const r = v7Enabled ? await portal.v7.disable() : await portal.v7.enable();
      setV7Enabled(!!r.enabled);
    } catch {
      /* keep previous state */
    }
  };

  // --- Consensus Verification (opt-in, OFF by default) ---
  const [v6Enabled, setV6Enabled] = useState<boolean | null>(null);
  const [v6Guide, setV6Guide] = useState(false);
  useEffect(() => {
    portal.v6.status().then((s) => setV6Enabled(!!s.enabled)).catch(() => setV6Enabled(false));
  }, []);
  const toggleV6 = async () => {
    try {
      const r = v6Enabled ? await portal.v6.disable() : await portal.v6.enable();
      setV6Enabled(!!r.enabled);
    } catch {
      /* keep previous state */
    }
  };

  return (
    <div className="space-y-8">
      {/* Billing / Settings / Sign out live in the account menu in the header. */}
      <div className="flex flex-wrap items-center gap-3">
        <Chip glyph="chip" tone="accent" size={28} />
        <h1 className="text-[20px] font-semibold tracking-[-0.011em] text-slate-100">API Keys &amp; Providers</h1>
        {me && <span className="text-sm text-slate-400">{me.email} · <span className="font-mono">{me.id}</span></span>}
      </div>

      {/* onboarding — the magic moment: get a key → copy a snippet → first call */}
      {(keys.length === 0 || newKey) && (
        <div className="plane overflow-hidden p-5 animate-fade-up">
          <div className="flex flex-wrap items-center gap-2">
            <span className="rounded-full bg-aurora/15 px-2.5 py-0.5 text-xs font-semibold text-indigo-300">
              Get started
            </span>
            <div className="text-sm font-semibold">Make your first call in under 2 minutes</div>
          </div>
          <div className="mt-4 grid gap-4 lg:grid-cols-3">
            <OnboardStep n={1} title="Create a key" done={keys.length > 0 || !!newKey}>
              {newKey ? (
                <div className="mt-1 flex items-center gap-2">
                  <code className="min-w-0 flex-1 truncate rounded bg-ink px-2 py-1 font-mono text-xs text-emerald-300">{newKey}</code>
                  <CopyButton text={newKey} />
                </div>
              ) : keys.length > 0 ? (
                <span className="text-emerald-300">You already have a key ✓</span>
              ) : (
                <button onClick={issueKey} className="mt-1 rounded-lg bg-gradient-to-r from-aurora to-neon px-3 py-1.5 text-xs font-semibold text-ink">
                  Issue API key
                </button>
              )}
            </OnboardStep>
            <OnboardStep n={2} title="Copy this snippet" done={false}>
              <div className="mt-1">
                <CodeBlock
                  language="bash"
                  code={`curl $ORIGIN/api/gateway/chat \\
  -H "Authorization: Bearer ${newKey || "cnt_live_…"}" \\
  -d '{"model":"auto","messages":[{"role":"user","content":"Hi!"}]}'`}
                />
              </div>
            </OnboardStep>
            <OnboardStep n={3} title="Or try it right here" done={madeFirstCall}>
              <span>Use the sandbox below — no code needed. {madeFirstCall && <span className="text-emerald-300">First call made</span>}</span>
            </OnboardStep>
          </div>
        </div>
      )}

      {/* analytics */}
      <div className="plane grid grid-cols-2 gap-x-8 gap-y-5 p-4 sm:grid-cols-3 lg:grid-cols-5">
        <Stat label="Requests" value={stats?.totalRequests ?? "—"} series={recent.arrivals} tone="violet" />
        <Stat label="Success rate" value={stats ? `${Math.round(stats.successRate * 100)}%` : "—"} accent="text-emerald-300" series={recent.health} tone="ok" />
        <Stat label="Failures prevented" value={stats?.failuresPrevented ?? "—"} accent="text-indigo-300" />
        <Stat label="Tokens" value={stats?.totalTokens ?? "—"} series={recent.tokens} tone="amber" />
        <Stat label="Cost" value={stats ? `$${(stats.totalCostUsd ?? 0).toFixed(5)}` : "—"} series={recent.cumulativeCost} tone="orange" />
      </div>

      {/* credential vault */}
      <div>
        <h2 className="flex items-center gap-1.5 text-[13px] font-semibold tracking-tight text-slate-200">
          Provider keys
          <InfoTip text="Your own LLM provider keys. Encrypted with AES-256-GCM, decrypted only in memory at call time, and never shown again after saving." />
        </h2>
        <div className="card mt-3 overflow-x-auto rounded-xl border border-card-edge bg-card p-3 shadow-card">
        <table className="w-full min-w-[420px] text-sm">
          <thead className="text-xs text-slate-400">
            <tr className="text-left"><th className="py-2">Provider</th><th>API Key (write-only)</th><th>Status</th><th>Actions</th></tr>
          </thead>
          <tbody>
            {PROVIDERS.map((p) => {
              const isSet = configured.has(p);
              const vs = verifyStatus[p];
              return (
                <tr key={p} className="border-t border-edge/50">
                  <td className="py-2 capitalize">{p}</td>
                  <td className="py-2">
                    <input type="password" value={secretInputs[p] ?? ""}
                      onChange={(e) => setSecretInputs({ ...secretInputs, [p]: e.target.value })}
                      placeholder={isSet ? "•••••••••• (saved)" : "(unconfigured)"}
                      className="w-56 field" />
                  </td>
                  <td className="py-2 text-xs">
                    {vs?.checking ? <span className="text-slate-400">checking…</span>
                      : vs ? (vs.valid ? <span className="text-emerald-300">✓ verified</span> : <span className="text-rose-300" title={vs.message}>✗ invalid</span>)
                      : isSet ? <span className="text-slate-400">saved</span> : <span className="text-slate-600">—</span>}
                  </td>
                  <td className="py-2">
                    <div className="flex gap-2 text-xs">
                      <button onClick={() => storeCred(p)} className="rounded bg-[color:var(--accent-strong)] px-2 py-1 text-white">
                        {isSet ? "Update" : "Configure"}
                      </button>
                      {isSet && <button onClick={() => verify(p)} className="rounded border border-edge px-2 py-1">Verify</button>}
                      {isSet && <button onClick={() => portal.deleteCredential(p).then(refresh)} className="rounded border border-edge px-2 py-1 text-rose-300">Delete</button>}
                    </div>
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
        </div>

        <div className="mt-3">
          <Switch
            checked={me?.useOwnKeysPrimary ?? true}
            onChange={(next) => toggle(next)}
            label="Use my provider keys first"
            hint="On: requests go through your keys and fall back to the platform if they fail or are rate-limited. Off: requests use the platform's keys."
          />
        </div>
      </div>

      {/* Opt-in engines: each is one switch. What it does is behind the tip;
          when to use it is behind a disclosure, drawn as two lists. */}
      <div className="grid items-start gap-3 lg:grid-cols-2">
        <div className="space-y-2">
          <Switch
            checked={!!v6Enabled}
            busy={v6Enabled === null}
            onChange={() => void toggleV6()}
            label="Verification Engine"
            hint="Runs each gateway request through a DAG of solver and verifier nodes with Bayesian conflict resolution. Every answer is checked, scored and auditable in the Execution Command Center. Response format unchanged."
          />
          <button onClick={() => setV6Guide(!v6Guide)} aria-expanded={v6Guide}
            className="ml-1 text-[11.5px] text-slate-500 hover:text-slate-300">
            {v6Guide ? "Hide" : "When to use it"}
          </button>
          {v6Guide && (
            <div className="grid gap-3 rounded-[var(--r-lg)] border border-edge p-3 text-xs sm:grid-cols-2 animate-fade-up">
              <div>
                <div className="font-semibold text-emerald-300">✓ On for</div>
                <ul className="mt-1 space-y-1 text-slate-400">
                  <li>High-stakes outputs: SQL, infra config, payments</li>
                  <li>Audit trails: finance, legal, healthcare</li>
                  <li>Correctness over latency (seconds, not ms)</li>
                  <li>Catching contradictions before execution</li>
                </ul>
              </div>
              <div>
                <div className="font-semibold text-rose-300">✕ Off for</div>
                <ul className="mt-1 space-y-1 text-slate-400">
                  <li>Latency-sensitive chat</li>
                  <li>Creative, open-ended generation</li>
                  <li>High-volume, low-risk traffic</li>
                  <li>Already validated downstream</li>
                </ul>
              </div>
            </div>
          )}
        </div>
        <Switch
          checked={!!v7Enabled}
          busy={v7Enabled === null}
          onChange={() => void toggleV7()}
          label="Context Optimizer"
          hint="Pages long histories into semantic stubs backed by immutable event streams, prefetches what is relevant and resolves page faults mid-generation. Inspect it in the Context Memory Profiler."
        />
      </div>

      {/* api keys */}
      <div>
        <div className="flex items-center">
          <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Continuum API keys</h2>
          <button onClick={issueKey} style={{ height: "var(--h-md)", borderRadius: "var(--r-md)", borderColor: "rgb(var(--card-edge))" }}
            className="ml-auto inline-flex items-center border px-3 text-[12.5px] font-medium text-slate-200 transition-colors hover:border-slate-500/60 hover:bg-[color:rgb(var(--card-hover))]">Issue new key</button>
        </div>
        {newKey && (
          <div className="mt-2 flex items-center gap-2 rounded bg-ink p-2">
            <code className="min-w-0 flex-1 break-all font-mono text-xs text-emerald-300">{newKey}</code>
            <span className="whitespace-nowrap text-[10px] text-slate-500">shown once</span>
            <CopyButton text={newKey} />
          </div>
        )}
        <div className="mt-2 space-y-1 text-sm">
          {keys.map((k) => (
            <div key={k.id} className="flex items-center gap-2 text-xs">
              <span className="font-mono">{k.prefix}…</span>
              <span className={k.active ? "text-emerald-300" : "text-rose-300"}>{k.active ? "active" : "revoked"}</span>
              {k.active && (
                <button onClick={() => revokeKey(k.id)} className="ml-auto rounded border border-edge px-2 py-0.5 text-rose-300 transition-colors hover:bg-rose-500/10">
                  Revoke
                </button>
              )}
            </div>
          ))}
          {keys.length === 0 && <div className="text-xs text-slate-500">no keys yet — issue one above ↑</div>}
        </div>
      </div>

      {/* playground */}
      <div>
        <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Sandbox playground
          <InfoTip text="Send a request through your gateway right now — no code required." />
        </h2>
        <textarea aria-label="Prompt" value={playPrompt} onChange={(e) => setPlayPrompt(e.target.value)}
          className="mt-2 h-16 w-full field" />
        <button
          onClick={runPlay}
          disabled={playBusy}
          className="mt-2 flex items-center gap-2 rounded-md bg-[color:var(--accent-strong)] px-3 py-1.5 text-sm text-white transition-colors hover:opacity-90 disabled:opacity-50"
        >
          {playBusy && <Spinner />}
          {playBusy ? "Sending…" : "Send through gateway"}
        </button>
        {playOut ? (
          <div className="mt-2 max-h-[640px] overflow-y-auto rounded border border-edge/60 bg-ink p-3 animate-fade-up">
            <GatewayResult out={playOut} />
          </div>
        ) : (
          !playBusy && (
            <div className="mt-2 text-xs text-slate-600">The response will appear here.</div>
          )
        )}
      </div>
    </div>
  );
}

function OnboardStep({ n, title, done, children }: { n: number; title: string; done: boolean; children: import("react").ReactNode }) {
  return (
    <div className={`min-w-0 rounded-xl border p-3 transition-all ${done ? "border-emerald-400/40 bg-emerald-500/5" : "border-edge bg-ink/40"}`}>
      <div className="flex items-center gap-2">
        <span className={`flex h-6 w-6 items-center justify-center rounded-lg text-xs font-bold ${done ? "bg-emerald-500/20 text-emerald-300" : "bg-gradient-to-br from-aurora to-neon text-ink"}`}>
          {done ? "✓" : n}
        </span>
        <span className="text-sm font-semibold">{title}</span>
      </div>
      <div className="mt-2 min-w-0 text-xs text-slate-400">{children}</div>
    </div>
  );
}

/**
 * A number in the page's own type.
 *
 * <p>These were five bordered boxes across the top, which is the most reliably
 * ignored element in any console: the same furniture on every screen, so the
 * eye learns to skip the band. The label carries the meaning and the value
 * only has to be findable.
 */
function Stat({ label, value, accent, series, tone }: { label: string; value: any; accent?: string; series?: number[]; tone?: Tone }) {
  return (
    <div className="min-w-0">
      <div className="micro truncate">{label}</div>
      <div className={`readout mt-1 text-[21px] leading-none tracking-tight ${accent ?? ""}`}>
        {value}
      </div>
      {series && series.length > 1 && (
        <div className="mt-2 opacity-90">
          <Spark points={series} tone={tone} height={26} />
        </div>
      )}
    </div>
  );
}
