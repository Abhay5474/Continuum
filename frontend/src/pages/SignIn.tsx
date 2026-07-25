import { useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { portal } from "../api";
import { Spinner, ThemeToggle, useToast } from "../components/ui";

/**
 * Standalone sign-in / sign-up page. Console data is tenant-scoped, so every
 * console route requires an account; unauthenticated visits are redirected here
 * with a {@code next} parameter so people land back where they were headed.
 */
export default function SignIn() {
  const [params] = useSearchParams();
  const next = params.get("next") || "/dashboard";
  const nav = useNavigate();
  const toast = useToast();

  const [mode, setMode] = useState<"login" | "signup">("login");
  const [name, setName] = useState("");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === "signup") await portal.signup(name, email, password);
      else await portal.login(email, password);
      toast(mode === "signup" ? "Account created" : "Welcome back", "success");
      nav(next, { replace: true });
    } catch (err: any) {
      setError(err?.message ?? "Could not sign in");
    } finally {
      setBusy(false);
    }
  };

  const tab = (m: "login" | "signup", label: string) => (
    <button
      type="button"
      onClick={() => { setMode(m); setError(null); }}
      className={`flex-1 rounded-md px-3 py-1.5 text-sm transition-colors ${
        mode === m ? "bg-panel font-semibold text-slate-100 shadow-sm" : "text-slate-400 hover:text-slate-200"
      }`}
    >
      {label}
    </button>
  );

  return (
    <div className="flex min-h-full flex-col">
      <header className="border-b border-edge/70">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3">
          <Link to="/" className="flex items-center gap-2.5">
            <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-aurora to-neon text-lg font-bold text-ink">
              ⟳
            </span>
            <span className="text-lg font-bold tracking-tight">Continuum</span>
          </Link>
          <div className="flex items-center gap-3">
            <Link to="/docs" className="text-sm text-slate-400 hover:text-slate-200">Docs</Link>
            <ThemeToggle />
          </div>
        </div>
      </header>

      <main className="flex flex-1 items-center justify-center px-4 py-12">
        <div className="w-full max-w-sm">
          <h1 className="text-center text-2xl font-bold tracking-tight">
            {mode === "signup" ? "Create your account" : "Sign in to Continuum"}
          </h1>
          <p className="mt-1.5 text-center text-sm text-slate-400">
            Your workspace, keys and usage are private to your account.
          </p>

          <div className="glass mt-6 p-6">
            <div className="mb-4 flex gap-1 rounded-lg bg-ink p-1">
              {tab("login", "Sign in")}
              {tab("signup", "Create account")}
            </div>

            <form onSubmit={submit} className="space-y-3">
              {mode === "signup" && (
                <Field label="Name">
                  <input
                    value={name}
                    onChange={(e) => setName(e.target.value)}
                    required
                    autoComplete="name"
                    placeholder="Ada Lovelace"
                    className="w-full rounded-lg border border-edge bg-ink px-3 py-2 text-sm text-slate-100 outline-none placeholder:text-slate-500 focus:border-aurora/60"
                  />
                </Field>
              )}
              <Field label="Email">
                <input
                  type="email"
                  value={email}
                  onChange={(e) => setEmail(e.target.value)}
                  required
                  autoComplete="email"
                  placeholder="you@company.com"
                  className="w-full rounded-lg border border-edge bg-ink px-3 py-2 text-sm text-slate-100 outline-none placeholder:text-slate-500 focus:border-aurora/60"
                />
              </Field>
              <Field label="Password">
                <input
                  type="password"
                  value={password}
                  onChange={(e) => setPassword(e.target.value)}
                  required
                  autoComplete={mode === "signup" ? "new-password" : "current-password"}
                  placeholder={mode === "signup" ? "At least 6 characters" : "••••••••"}
                  className="w-full rounded-lg border border-edge bg-ink px-3 py-2 text-sm text-slate-100 outline-none placeholder:text-slate-500 focus:border-aurora/60"
                />
              </Field>

              {error && (
                <div className="rounded-lg border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
                  {error}
                </div>
              )}

              <button
                type="submit"
                disabled={busy}
                className="flex w-full items-center justify-center gap-2 rounded-lg bg-gradient-to-r from-aurora to-neon px-4 py-2.5 text-sm font-semibold text-ink transition-transform hover:-translate-y-0.5 disabled:opacity-60"
              >
                {busy && <Spinner className="h-4 w-4 border-ink/40 border-t-ink" />}
                {mode === "signup" ? "Create account" : "Sign in"}
              </button>
            </form>
          </div>

          <p className="mt-4 text-center text-xs text-slate-500">
            <Link to="/" className="hover:text-slate-300">← Back to home</Link>
          </p>
        </div>
      </main>
    </div>
  );
}

function Field({ label, children }: { label: string; children: import("react").ReactNode }) {
  return (
    <label className="block">
      <span className="mb-1 block text-xs font-medium text-slate-400">{label}</span>
      {children}
    </label>
  );
}
