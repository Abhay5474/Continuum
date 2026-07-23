import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { portal } from "../api";
import { useToast, Spinner, CopyButton, CodeBlock } from "../components/ui";

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
      toast(mode === "signup" ? "Account created 🎉" : "Welcome back", "success");
      onAuthed();
    } catch (e: any) {
      setErr(e.message ?? String(e));
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="relative mx-auto mt-8 max-w-md animate-fade-up">
      <div className="pointer-events-none absolute -inset-6 -z-10 rounded-3xl bg-aurora/10 blur-3xl" />
      <div className="glass p-7">
        <div className="flex items-center gap-2.5">
          <span className="flex h-9 w-9 items-center justify-center rounded-lg bg-gradient-to-br from-aurora to-neon text-lg font-bold text-ink shadow-glow-sm">
            ⟳
          </span>
          <div>
            <h1 className="text-lg font-bold tracking-tight text-gradient">Developer Portal</h1>
            <p className="text-xs text-slate-400">Your API keys, credentials and analytics.</p>
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
            className="mt-4 w-full rounded-lg border border-edge bg-ink px-3 py-2 text-sm outline-none focus:border-aurora/60" />
        )}
        <input value={email} onChange={(e) => setEmail(e.target.value)} placeholder="Email"
          onKeyDown={(e) => e.key === "Enter" && submit()}
          className="mt-3 w-full rounded-lg border border-edge bg-ink px-3 py-2 text-sm outline-none focus:border-aurora/60" />
        <input value={password} onChange={(e) => setPassword(e.target.value)} type="password" placeholder="Password"
          onKeyDown={(e) => e.key === "Enter" && submit()}
          className="mt-3 w-full rounded-lg border border-edge bg-ink px-3 py-2 text-sm outline-none focus:border-aurora/60" />
        <button
          onClick={submit}
          disabled={busy}
          className="mt-4 flex w-full items-center justify-center gap-2 rounded-lg bg-gradient-to-r from-aurora to-neon px-3 py-2.5 text-sm font-semibold text-ink shadow-glow-sm transition-transform hover:-translate-y-0.5 disabled:opacity-60"
        >
          {busy && <Spinner className="h-4 w-4 border-ink/40 border-t-ink" />}
          {mode === "signup" ? "Create account →" : "Log in →"}
        </button>
        {err && <div className="mt-3 rounded-lg border border-rose-500/30 bg-rose-500/5 px-3 py-2 text-sm text-rose-300">{err}</div>}
        <p className="mt-4 text-center text-xs text-slate-500">
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
        toast("You made your first call! 🎉", "success");
      }
    } catch (e: any) {
      setPlayOut({ error: e.message });
      toast(e.message ?? "Call failed", "error");
    } finally {
      setPlayBusy(false);
    }
  };

  const configured = new Set(creds.map((c) => c.provider));

  // --- V7 Context MMU (opt-in, OFF by default) ---
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
      <div className="flex flex-wrap items-center gap-3">
        <h1 className="text-lg font-semibold">Developer Portal</h1>
        {me && <span className="text-sm text-slate-400">{me.email} · <span className="font-mono">{me.id}</span></span>}
        <div className="ml-auto flex items-center gap-2">
          <Link to="/billing" className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 transition-colors hover:border-neon/50 hover:text-neon">Billing</Link>
          <Link to="/settings" className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 transition-colors hover:border-neon/50 hover:text-neon">Settings</Link>
          <button onClick={() => { portal.logout(); onLogout(); }}
            className="rounded-md border border-edge px-3 py-1.5 text-sm hover:bg-edge">Sign out</button>
        </div>
      </div>

      {/* onboarding — the magic moment: get a key → copy a snippet → first call */}
      {(keys.length === 0 || newKey) && (
        <div className="glass overflow-hidden p-5 animate-fade-up">
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
              <span>Use the sandbox below — no code needed. {madeFirstCall && <span className="text-emerald-300">First call made 🎉</span>}</span>
            </OnboardStep>
          </div>
        </div>
      )}

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

      {/* V7 Context MMU (opt-in, off by default) */}
      <div className={`rounded-lg border bg-panel p-4 transition-all ${v7Enabled ? "border-aurora/50 shadow-glow" : "border-edge"}`}>
        <div className="flex flex-wrap items-center gap-3">
          <div>
            <div className="font-medium">
              V7 Context Virtualization <span className="text-xs text-slate-500">(Paging MMU)</span>
              {v7Enabled && <span className="ml-2 rounded bg-aurora/15 px-2 py-0.5 text-[10px] font-bold text-indigo-300">ACTIVE</span>}
            </div>
            <p className="text-xs text-slate-400">
              Continuum owns the Virtual Context Space: long histories are paged into semantic stubs
              (L2) backed by immutable event streams (L3); relevant pages are prefetched and page
              faults resolved mid-generation. Infinite-context workflows without bigger token limits —
              inspect it live in the <span className="text-slate-300">Context Memory Profiler</span>.
            </p>
          </div>
          <button onClick={toggleV7} disabled={v7Enabled === null}
            className={`ml-auto rounded-md px-4 py-1.5 text-sm font-semibold transition-all ${
              v7Enabled
                ? "bg-aurora/20 text-indigo-300 ring-1 ring-aurora/50"
                : "bg-indigo-600 text-white hover:bg-indigo-500"}`}>
            {v7Enabled === null ? "…" : v7Enabled ? "Enabled — click to disable" : "Enable V7 Context Virtualization"}
          </button>
        </div>
      </div>

      {/* api keys */}
      <div className="rounded-lg border border-edge bg-panel p-4">
        <div className="flex items-center">
          <div className="font-medium">Continuum API keys</div>
          <button onClick={issueKey} className="ml-auto rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white">Issue new key</button>
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
      <div className="rounded-lg border border-edge bg-panel p-4">
        <div className="font-medium">Sandbox playground</div>
        <p className="text-xs text-slate-400">Send a request through your gateway right now — no code required.</p>
        <textarea value={playPrompt} onChange={(e) => setPlayPrompt(e.target.value)}
          className="mt-2 h-16 w-full rounded-md border border-edge bg-ink p-2 text-sm outline-none focus:border-aurora/60" />
        <button
          onClick={runPlay}
          disabled={playBusy}
          className="mt-2 flex items-center gap-2 rounded-md bg-indigo-600 px-3 py-1.5 text-sm text-white transition-colors hover:bg-indigo-500 disabled:opacity-50"
        >
          {playBusy && <Spinner />}
          {playBusy ? "Sending…" : "Send through gateway"}
        </button>
        {playOut ? (
          <pre className="mt-2 overflow-x-auto rounded bg-ink p-2 text-xs text-slate-300 animate-fade-up">{JSON.stringify(playOut, null, 2)}</pre>
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
    <div className={`rounded-xl border p-3 transition-all ${done ? "border-emerald-400/40 bg-emerald-500/5" : "border-edge bg-ink/40"}`}>
      <div className="flex items-center gap-2">
        <span className={`flex h-6 w-6 items-center justify-center rounded-lg text-xs font-bold ${done ? "bg-emerald-500/20 text-emerald-300" : "bg-gradient-to-br from-aurora to-neon text-ink"}`}>
          {done ? "✓" : n}
        </span>
        <span className="text-sm font-semibold">{title}</span>
      </div>
      <div className="mt-2 text-xs text-slate-400">{children}</div>
    </div>
  );
}

function Stat({ label, value, accent }: { label: string; value: any; accent?: string }) {
  return (
    <div className="rounded-lg border border-edge bg-panel p-4 transition-all hover:border-aurora/30">
      <div className="text-xs uppercase text-slate-400">{label}</div>
      <div className={`mt-1 text-2xl font-semibold ${accent ?? ""}`}>{value}</div>
    </div>
  );
}
