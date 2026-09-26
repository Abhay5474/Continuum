import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { portal } from "../api";
import { Spinner, ThemeToggle, useToast } from "../components/ui";
import { BrandMark } from "../system/brand";
import { useLiquidSurface } from "../system/primitives";
import { Mechanism } from "../system/viz";

/**
 * Standalone sign-in / sign-up page. Console data is tenant-scoped, so every
 * console route requires an account; unauthenticated visits are redirected here
 * with a {@code next} parameter so people land back where they were headed.
 */
export default function SignIn() {
  useLiquidSurface();
  const [params] = useSearchParams();
  const next = params.get("next") || "/dashboard";
  const ended = params.get("ended") === "1";
  // A lapsed token left in storage would make the rest of the app believe the
  // user is still signed in.
  useEffect(() => {
    if (ended) portal.setSession(null);
  }, [ended]);
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
      role="tab"
      aria-selected={mode === m}
      onClick={() => { setMode(m); setError(null); }}
      className={`flex-1 rounded-md px-3 py-1.5 text-sm transition-colors ${
        mode === m ? "glass-pill font-semibold text-slate-100" : "text-slate-400 hover:text-slate-200"
      }`}
    >
      {label}
    </button>
  );

  return (
    <div className="flex min-h-full flex-col">
      <header className="glass-bar sticky top-0 z-30">
        <div className="mx-auto flex max-w-7xl items-center justify-between px-4 py-3">
          <Link to="/" className="flex items-center gap-2.5">
            <BrandMark size={30} />
            <span className="text-lg font-bold tracking-tight">Continuum</span>
          </Link>
          <div className="flex items-center gap-3">
            <Link to="/docs" className="text-sm text-slate-400 hover:text-slate-200">Docs</Link>
            <ThemeToggle />
          </div>
        </div>
      </header>

      <main className="flex flex-1 items-center justify-center px-4 py-12">
        <div className="grid w-full max-w-5xl items-center gap-10 lg:grid-cols-[minmax(0,1fr)_384px]">
        <div className="w-full max-w-sm justify-self-center lg:order-2">
          <h1 className="text-center text-2xl font-bold tracking-tight">
            {mode === "signup" ? "Create your account" : "Sign in to Continuum"}
          </h1>
          <p className="mt-1.5 text-center text-sm text-slate-400">
            Your workspace, keys and usage are private to your account.
          </p>

          <div className="plane mt-6 p-6">
            <div className="mb-4 flex gap-1 rounded-lg bg-slate-500/10 p-1" role="tablist" aria-label="Sign in or create an account">
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
                    className="w-full field"
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
                  className="w-full field"
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
                  className="w-full field"
                />
              </Field>

              {ended && !error && (
                <div role="status" className="rounded-lg border border-amber-500/30 bg-amber-500/10 px-3 py-2 text-xs text-amber-200">
                  Your session ended, so you have been signed out. Sign in again to carry on where you were.
                </div>
              )}

              {error && (
                <div role="alert" className="rounded-lg border border-rose-500/30 bg-rose-500/10 px-3 py-2 text-xs text-rose-300">
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

        {/* What is behind the door, drawn: one endpoint in front of every
            model, and the three things it does to a request. Seen once here,
            the console's own diagrams read as more of the same picture. */}
        <section className="plane p-5 sm:p-6 lg:order-1" aria-labelledby="signin-what">
          <h2 id="signin-what" className="text-[15px] font-semibold tracking-tight text-slate-100">
            One endpoint in front of every model
          </h2>
          <p className="mt-1 text-[12.5px] text-slate-500">Send the request you already send. Continuum decides what happens next.</p>
          <div className="mt-5">
            <Mechanism
              summary="Your app sends one request to Continuum, which answers it from cache, routes it to the best provider with failover, or blocks it if it is unsafe."
              nodes={[
                { id: "app", col: 0, span: 3, role: "end", glyph: "app", label: "Your app", sub: "one OpenAI-style call" },
                { id: "c", col: 1, span: 3, role: "core", tone: "accent", glyph: "loop", label: "Continuum", sub: "guard · cache · route · verify" },
                { id: "cache", col: 2, row: 0, tone: "green", glyph: "cache", label: "Answered from cache", sub: "no provider call" },
                { id: "best", col: 2, row: 1, tone: "blue", glyph: "model", label: "Best provider", sub: "fails over if one is down" },
                { id: "block", col: 2, row: 2, tone: "red", glyph: "shield", label: "Blocked if unsafe", sub: "PII and injections stopped" },
              ]}
              links={[
                { from: "app", to: "c", weight: 10 },
                { from: "c", to: "cache", weight: 3, tone: "green" },
                { from: "c", to: "best", weight: 6, tone: "blue" },
                { from: "c", to: "block", weight: 1, tone: "red" },
              ]}
              minNodeWidth={120}
              maxNodeWidth={200}
              narrowAt={440}
            />
          </div>
        </section>
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
