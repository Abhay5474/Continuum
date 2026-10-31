import { useEffect, useState } from "react";
import { Link, useNavigate, useSearchParams } from "react-router-dom";
import { AnimatePresence, m, useAnimate } from "motion/react";
import { portal } from "../api";
import { Spinner, ThemeToggle, useToast } from "../components/ui";
import { BrandMark } from "../system/brand";
import { useLiquidSurface } from "../system/primitives";
import { prefersReducedMotion, transitions } from "../system/physics";
import { Ambient } from "../shell/Ambient";

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
  const [button, animate] = useAnimate<HTMLButtonElement>();

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
      // The button that was pressed says no, once, the way a lock does.
      if (button.current && !prefersReducedMotion()) {
        animate(button.current, { x: [0, -6, 6, -4, 4, 0] }, { duration: 0.42, ease: "easeOut" });
      }
    } finally {
      setBusy(false);
    }
  };

  // One pane slides between the two modes rather than two tabs swapping fills.
  const tab = (value: "login" | "signup", label: string) => (
    <button
      type="button"
      role="tab"
      aria-selected={mode === value}
      onClick={() => { setMode(value); setError(null); }}
      data-no-press
      className={`relative flex-1 rounded-[9px] px-3 py-1.5 text-sm transition-colors ${
        mode === value ? "font-semibold text-slate-100" : "text-slate-400 hover:text-slate-200"
      }`}
    >
      {mode === value && (
        <m.span layoutId="signin-mode" className="side-active" style={{ borderRadius: 9 }} transition={transitions.standard} />
      )}
      <span className="relative">{label}</span>
    </button>
  );

  return (
    <div className="flex min-h-full flex-col">
      <Ambient />
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
        <m.div
          className="w-full max-w-sm"
          initial={{ opacity: 0, y: 18 }}
          animate={{ opacity: 1, y: 0 }}
          transition={transitions.slow}
        >
          <h1 className="text-center text-[28px] font-semibold tracking-[-0.03em]">
            <AnimatePresence mode="popLayout" initial={false}>
              <m.span
                key={mode}
                className="inline-block"
                initial={{ opacity: 0, y: 12, filter: "blur(4px)" }}
                animate={{ opacity: 1, y: 0, filter: "blur(0px)" }}
                exit={{ opacity: 0, y: -12, filter: "blur(4px)" }}
                transition={transitions.standard}
              >
                {mode === "signup" ? "Create your account" : "Sign in to Continuum"}
              </m.span>
            </AnimatePresence>
          </h1>
          <p className="mt-2 text-center text-sm text-slate-400">
            Your workspace, keys and usage are private to your account.
          </p>

          <m.div layout className="plane mt-7 p-6" transition={transitions.standard} style={{ borderRadius: 18 }}>
            <div className="mb-5 flex gap-1 rounded-[12px] p-1" style={{ background: "rgb(var(--hl) / 0.06)" }} role="tablist" aria-label="Sign in or create an account">
              {tab("login", "Sign in")}
              {tab("signup", "Create account")}
            </div>

            <form onSubmit={submit} className="space-y-3">
              <AnimatePresence initial={false}>
                {mode === "signup" && (
                  <m.div
                    key="name"
                    initial={{ opacity: 0, height: 0 }}
                    animate={{ opacity: 1, height: "auto" }}
                    exit={{ opacity: 0, height: 0 }}
                    transition={transitions.standard}
                    style={{ overflow: "hidden" }}
                  >
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
                  </m.div>
                )}
              </AnimatePresence>
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
                ref={button}
                type="submit"
                disabled={busy}
                aria-busy={busy || undefined}
                data-magnetic="0.12"
                className="btn-primary press press-on-accent mt-1 flex h-[42px] w-full items-center justify-center gap-2 overflow-hidden rounded-[11px] px-4 text-sm font-semibold disabled:opacity-80"
                style={{ backgroundColor: "var(--accent-strong)", color: "var(--accent-on)" }}
              >
                <AnimatePresence mode="popLayout" initial={false}>
                  <m.span
                    key={busy ? "busy" : mode}
                    className="inline-flex items-center gap-2"
                    initial={{ opacity: 0, y: 12 }}
                    animate={{ opacity: 1, y: 0 }}
                    exit={{ opacity: 0, y: -12 }}
                    transition={transitions.standard}
                  >
                    {busy && <Spinner className="h-4 w-4 border-white/40 border-t-white" />}
                    {busy ? (mode === "signup" ? "Creating your account…" : "Signing in…") : mode === "signup" ? "Create account" : "Sign in"}
                  </m.span>
                </AnimatePresence>
              </button>
            </form>
          </m.div>

          <p className="mt-5 text-center text-xs text-slate-500">
            <Link to="/" className="hover:text-slate-300">← Back to home</Link>
          </p>
        </m.div>
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
