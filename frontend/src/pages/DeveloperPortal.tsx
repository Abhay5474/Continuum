import { useEffect, useState } from "react";
import { portal } from "../api";

const PROVIDERS = ["gemini", "groq", "openai"];

export default function DeveloperPortal() {
  const [authed, setAuthed] = useState(!!portal.session());
  if (!authed) {
    return <AuthGate onAuthed={() => setAuthed(true)} />;
  }
  return <Portal onLogout={() => setAuthed(false)} />;
}

function AuthGate({ onAuthed }: { onAuthed: () => void }) {
  const [mode, setMode] = useState<"login" | "signup">("login");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [err, setErr] = useState("");

  const submit = async () => {
    setErr("");
    try {
      if (mode === "signup") await portal.signup(name, email, password);
      else await portal.login(email, password);
      onAuthed();
    } catch (e: any) {
      setErr(e.message ?? String(e));
    }
  };

  return (
    <div className="mx-auto max-w-md">
      <div className="rounded-lg border border-edge bg-panel p-6">
        <h1 className="text-lg font-semibold">Developer Portal</h1>
        <p className="mt-1 text-sm text-slate-400">
          Sign in to manage your API keys, provider credentials and analytics.
        </p>
        <div className="mt-4 flex gap-2 text-sm">
          <button onClick={() => setMode("login")}
            className={`rounded-md px-3 py-1.5 ${mode === "login" ? "bg-indigo-600 text-white" : "border border-edge"}`}>
            Log in
          </button>
          <button onClick={() => setMode("signup")}
            className={`rounded-md px-3 py-1.5 ${mode === "signup" ? "bg-indigo-600 text-white" : "border border-edge"}`}>
            Sign up
          </button>
        </div>
        {mode === "signup" && (
          <input value={name} onChange={(e) => setName(e.target.value)} placeholder="Name"
            className="mt-3 w-full rounded-md border border-edge bg-ink px-3 py-2 text-sm" />
        )}
        <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="Email"
          className="mt-3 w-full rounded-md border border-edge bg-ink px-3 py-2 text-sm" />
        <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" placeholder="Password"
          className="mt-3 w-full rounded-md border border-edge bg-ink px-3 py-2 text-sm" />
        <button onClick={submit} className="mt-4 w-full rounded-md bg-indigo-600 px-3 py-2 text-sm font-medium text-white">
          {mode === "signup" ? "Create account" : "Log in"}
        </button>
        {err && <div className="mt-2 text-sm text-rose-400">{err}</div>}
      </div>
    </div>
  );
}

function Portal({ onLogout }: { onLogout: () => void }) {
  const [me, setMe] = useState<any>(null);
  const [keys, setKeys] = useState<any[]>([]);
  const [creds, setCreds] = useState<any[]>([]);
  const [stats, setStats] = useState<any>(null);
  const [newKey, setNewKey] = useState("");
  const [verifyStatus, setVerifyStatus] = useState<Record<string, any>>({});
  const [secretInputs, setSecretInputs] = useState<Record<string, string>>({});
  const [playPrompt, setPlayPrompt] = useState("Provide first aid for a dog leg injury");
  const [playOut, setPlayOut] = useState<any>(null);

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
    const r = await portal.issueKey();
    setNewKey(r.apiKey);
    refresh();
  };
  const storeCred = async (provider: string) => {
    const secret = secretInputs[provider];
    if (!secret) return;
    await portal.storeCredential(provider, secret);
    setSecretInputs({ ...secretInputs, [provider]: "" });
    refresh();
  };
  const verify = async (provider: string) => {
    setVerifyStatus({ ...verifyStatus, [provider]: { checking: true } });
    try {
      const r = await portal.verifyCredential(provider);
      setVerifyStatus({ ...verifyStatus, [provider]: r });
    } catch (e: any) {
      setVerifyStatus({ ...verifyStatus, [provider]: { valid: false, message: e.message } });
    }
  };
  const toggle = async (v: boolean) => {
    await portal.setRoutingPreference(v);
    setMe({ ...me, useOwnKeysPrimary: v });
  };
  const runPlay = async () => {
    setPlayOut(null);
    try {
      setPlayOut(await portal.playground({ model: "auto", messages: [{ role: "user", content: playPrompt }], maxTokens: 200 }));
    } catch (e: any) {
      setPlayOut({ error: e.message });
    }
  };

  const configured = new Set(creds.map((c) => c.provider));

  // --- V6 Consensus DAG Engine (opt-in, OFF by default) ---
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
    <div className="space-y-6">
      <div className="flex items-center gap-3">
        <h1 className="text-lg font-semibold">Developer Portal</h1>
        {me && <span className="text-sm text-slate-400">{me.email} · <span className="font-mono">{me.id}</span></span>}
        <button onClick={() => { portal.logout(); onLogout(); }}
          className="ml-auto rounded-md border border-edge px-3 py-1.5 text-sm hover:bg-edge">Sign out</button>
      </div>

      {/* analytics */}
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-5">
        <Stat label="Requests" value={stats?.totalRequests ?? "—"} />
        <Stat label="Success rate" value={stats ? `${Math.round(stats.successRate * 100)}%` : "—"} accent="text-emerald-300" />
        <Stat label="Failures prevented" value={stats?.failuresPrevented ?? "—"} accent="text-indigo-300" />
        <Stat label="Tokens" value={stats?.totalTokens ?? "—"} />
        <Stat label="Cost" value={stats ? `$${(stats.totalCostUsd ?? 0).toFixed(5)}` : "—"} />
      </div>

      {/* credential vault */}
      <div className="rounded-lg border border-edge bg-panel p-4">
        <div className="font-medium">Configure Upstream Keys</div>
        <p className="text-xs text-slate-400">
          Store your own LLM provider API keys. They are encrypted with AES-256-GCM and decrypted only
          in-memory at request execution. Secrets are write-only — never displayed after saving.
        </p>
        <table className="mt-3 w-full text-sm">
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
                      className="w-56 rounded-md border border-edge bg-ink px-2 py-1 text-xs" />
                  </td>
                  <td className="py-2 text-xs">
                    {vs?.checking ? <span className="text-slate-400">checking…</span>
                      : vs ? (vs.valid ? <span className="text-emerald-300">✓ verified</span> : <span className="text-rose-300" title={vs.message}>✗ invalid</span>)
                      : isSet ? <span className="text-slate-400">saved</span> : <span className="text-slate-600">—</span>}
                  </td>
                  <td className="py-2">
                    <div className="flex gap-2 text-xs">
                      <button onClick={() => storeCred(p)} className="rounded bg-indigo-600 px-2 py-1 text-white">
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

        <div className="mt-4 rounded-md border border-edge bg-ink p-3">
          <label className="flex items-start gap-2 text-sm">
            <input type="checkbox" checked={me?.useOwnKeysPrimary ?? true} onChange={(e) => toggle(e.target.checked)} className="mt-1" />
            <span>
              <span className="font-medium">Use my provider API keys as primary</span>
              <span className="block text-xs text-slate-400">
                When checked, the gateway routes through your own keys and gracefully falls back to the
                platform (or mock) if they fail or are rate-limited. When unchecked, requests run on the
                platform's global keys.
              </span>
            </span>
          </label>
        </div>
      </div>

      {/* V6 Consensus DAG Engine (opt-in, off by default) */}
      <div className={`rounded-lg border bg-panel p-4 transition-all ${v6Enabled ? "border-neon/50 shadow-glow-cyan" : "border-edge"}`}>
        <div className="flex flex-wrap items-center gap-3">
          <div>
            <div className="font-medium">
              V6 Verification Engine <span className="text-xs text-slate-500">(Consensus DAG)</span>
              {v6Enabled && <span className="ml-2 rounded bg-neon/15 px-2 py-0.5 text-[10px] font-bold text-neon">ACTIVE</span>}
            </div>
            <p className="text-xs text-slate-400">
              Routes your gateway requests through a parallel DAG of solver and verifier nodes with
              Bayesian conflict resolution — every answer is checked, scored, and fully auditable in
              the <span className="text-slate-300">Execution Command Center</span>. Response format is unchanged.
            </p>
          </div>
          <div className="ml-auto flex items-center gap-2">
            <button onClick={() => setV6Guide(!v6Guide)}
              className="rounded-md border border-edge px-3 py-1.5 text-xs hover:border-neon/50">
              {v6Guide ? "Hide guide" : "When should I use this?"}
            </button>
            <button onClick={toggleV6} disabled={v6Enabled === null}
              className={`rounded-md px-4 py-1.5 text-sm font-semibold transition-all ${
                v6Enabled
                  ? "bg-neon/20 text-neon ring-1 ring-neon/50"
                  : "bg-indigo-600 text-white hover:bg-indigo-500"}`}>
              {v6Enabled === null ? "…" : v6Enabled ? "Enabled — click to disable" : "Enable V6 Verification Engine"}
            </button>
          </div>
        </div>

        {v6Guide && (
          <div className="mt-3 grid gap-3 rounded-md border border-edge bg-ink p-3 text-xs sm:grid-cols-2 animate-fade-up">
            <div>
              <div className="font-semibold text-emerald-300">✔ Turn it ON when…</div>
              <ul className="mt-1 list-disc space-y-1 pl-4 text-slate-400">
                <li>Outputs feed <span className="text-slate-300">high-stakes actions</span>: generated SQL, Terraform/K8s configs, payment or approval logic.</li>
                <li>You need an <span className="text-slate-300">audit trail</span> — compliance, finance, legal, healthcare ("why did the AI say this?").</li>
                <li>Correctness matters more than latency: each request runs a multi-node verification DAG (expect seconds, not milliseconds).</li>
                <li>You want hallucinations and contradictions <span className="text-slate-300">caught before execution</span>, with a confidence score per answer.</li>
              </ul>
            </div>
            <div>
              <div className="font-semibold text-rose-300">✘ Keep it OFF when…</div>
              <ul className="mt-1 list-disc space-y-1 pl-4 text-slate-400">
                <li>Latency-sensitive chat/UX flows — the legacy path answers in one hop.</li>
                <li>Creative or open-ended generation, where "verification" has no ground truth.</li>
                <li>High-volume, low-risk traffic where per-request verification cost isn't justified.</li>
                <li>Anything already covered by your own downstream validation.</li>
              </ul>
              <div className="mt-2 text-slate-500">
                Off = the exact legacy gateway path, bit for bit. Your clients never see a difference
                in response format either way.
              </div>
            </div>
          </div>
        )}
      </div>

      {/* api keys */}
      <div className="rounded-lg border border-edge bg-panel p-4">
        <div className="flex items-center">
          <div className="font-medium">Continuum API keys</div>
          <button onClick={issueKey} className="ml-auto rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white">Issue new key</button>
        </div>
        {newKey && (
          <div className="mt-2 break-all rounded bg-ink p-2 font-mono text-xs text-emerald-300">
            {newKey} <span className="text-slate-500">(shown once)</span>
          </div>
        )}
        <div className="mt-2 space-y-1 text-sm">
          {keys.map((k) => (
            <div key={k.id} className="flex items-center gap-2 text-xs">
              <span className="font-mono">{k.prefix}…</span>
              <span className={k.active ? "text-emerald-300" : "text-rose-300"}>{k.active ? "active" : "revoked"}</span>
              {k.active && <button onClick={() => portal.revokeKey(k.id).then(refresh)} className="ml-auto rounded border border-edge px-2 py-0.5 text-rose-300">Revoke</button>}
            </div>
          ))}
          {keys.length === 0 && <div className="text-xs text-slate-500">no keys yet</div>}
        </div>
      </div>

      {/* playground */}
      <div className="rounded-lg border border-edge bg-panel p-4">
        <div className="font-medium">Sandbox playground</div>
        <textarea value={playPrompt} onChange={(e) => setPlayPrompt(e.target.value)}
          className="mt-2 h-16 w-full rounded-md border border-edge bg-ink p-2 text-sm" />
        <button onClick={runPlay} className="mt-2 rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white">Send through gateway</button>
        {playOut && <pre className="mt-2 overflow-x-auto rounded bg-ink p-2 text-xs text-slate-300">{JSON.stringify(playOut, null, 2)}</pre>}
      </div>
    </div>
  );
}

function Stat({ label, value, accent }: { label: string; value: any; accent?: string }) {
  return (
    <div className="rounded-lg border border-edge bg-panel p-4">
      <div className="text-xs uppercase text-slate-400">{label}</div>
      <div className={`mt-1 text-2xl font-semibold ${accent ?? ""}`}>{value}</div>
    </div>
  );
}
