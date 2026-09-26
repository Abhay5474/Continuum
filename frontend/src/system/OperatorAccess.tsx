import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { portal, hasOperator } from "../api";
import { useRef } from "react";
import { useGrowFrom } from "./demo";
import { Micro } from "./primitives";

/**
 * Operator elevation.
 *
 * <p>Continuum has two roles. A developer owns their own traffic; the operator
 * owns the engine. Some settings — the routing objective, tail-latency hedging,
 * the shared model catalogue — change behaviour and spend for *everyone* on the
 * deployment, so the backend gates them behind an operator session.
 *
 * <p>That was correct and completely unusable: the console only had a developer
 * login, so those switches were permanently dead. Signing in as the operator
 * instead is not the answer either — an operator session carries no developer
 * id, so it would take your keys, workflows and billing away with it.
 *
 * <p>So elevation is additive. You stay signed in as yourself and additionally
 * prove you are an operator; the operator token is stored beside your session
 * and sent only on engine-wide endpoints. Dropping it leaves you signed in.
 *
 * <p>Proof no longer means a shared secret. The operator role lives on your
 * account: you re-enter your password and get a 30-minute operator session. The
 * very first operator claims the role with a one-time setup code the server
 * prints to its own log. The old admin-token path remains for break-glass use.
 */

type Ctx = {
  /** Whether engine-wide controls are currently live. */
  operator: boolean;
  /** Opens the elevation dialog. Pass to a locked control's onUnlock. */
  request: () => void;
  drop: () => void;
};

const OperatorContext = createContext<Ctx>({ operator: false, request: () => {}, drop: () => {} });

export const useOperator = () => useContext(OperatorContext);

export function OperatorProvider({ children }: { children: ReactNode }) {
  const [operator, setOperator] = useState(hasOperator());
  const [open, setOpen] = useState(false);

  // Operator sessions are short. Drop the elevation the moment it lapses, so no
  // switch looks live that the server would refuse.
  useEffect(() => {
    if (!operator) return;
    const at = portal.operatorExpiresAt();
    if (!at) return;
    const t = window.setTimeout(() => {
      portal.dropOperator();
      setOperator(false);
    }, Math.max(0, at - Date.now()));
    return () => window.clearTimeout(t);
  }, [operator]);

  return (
    <OperatorContext.Provider
      value={{
        operator,
        request: () => setOpen(true),
        drop: () => {
          portal.dropOperator();
          setOperator(false);
        },
      }}
    >
      {children}
      {open && (
        <ElevateDialog
          onClose={() => setOpen(false)}
          onDone={() => {
            setOperator(true);
            setOpen(false);
          }}
        />
      )}
    </OperatorContext.Provider>
  );
}

type Mode = "loading" | "password" | "claim" | "token" | "none";

function ElevateDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [mode, setMode] = useState<Mode>("loading");
  const [minutes, setMinutes] = useState(30);
  const [value, setValue] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);
  const input = useRef<HTMLInputElement>(null);
  const form = useRef<HTMLFormElement>(null);
  // Whatever had focus when the dialog was asked for is what opened it.
  const [opener] = useState(() => document.activeElement);
  useGrowFrom(form, opener && opener !== document.body ? opener : null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  // Which door to show: step-up for operators, the setup code before anyone
  // operates, otherwise who to ask. The admin token is always one click away.
  const load = () => {
    setMode("loading");
    portal
      .operatorStatus()
      .then((s) => {
        setMinutes(s.sessionMinutes);
        setMode(s.operator ? "password" : !s.anyOperator && s.setupOpen ? "claim" : "none");
      })
      .catch(() => setMode("token"));
  };
  useEffect(load, []);

  useEffect(() => {
    setValue("");
    setError(null);
    input.current?.focus();
  }, [mode]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      if (mode === "password") await portal.operatorElevate(value);
      else if (mode === "claim") await portal.operatorClaim(value.trim());
      else await portal.elevate(value.trim());
      onDone();
    } catch (err: any) {
      setError(err?.message ?? "That did not work.");
    } finally {
      setBusy(false);
    }
  };

  const copy: Record<Exclude<Mode, "loading">, { title: string; label: string; hint: ReactNode; cta: string }> = {
    password: {
      title: "Confirm it's you",
      label: "Your password",
      hint: `Opens a ${minutes}-minute operator session. It ends early if your access is removed or you change your password.`,
      cta: "Unlock",
    },
    claim: {
      title: "Become the first operator",
      label: "Setup code",
      hint: (
        <>
          Nobody operates this deployment yet. The server printed a one-time code to its log at start-up
          (look for <span className="font-medium text-slate-300">No one operates this Continuum deployment</span>).
          It works once, for 24 hours.
        </>
      ),
      cta: "Claim",
    },
    none: {
      title: "Operator access",
      label: "",
      hint: "Your account isn't an operator. Ask an operator to add your email under Settings → Operators.",
      cta: "",
    },
    token: {
      title: "Break-glass access",
      label: "Admin token",
      hint: (
        <>
          The value of <code className="text-slate-400">CONTINUUM_ADMIN_TOKEN</code> on the server, if one is set.
        </>
      ),
      cta: "Unlock",
    },
  };
  const c = mode === "loading" ? null : copy[mode];

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/80 p-4 backdrop-blur-sm"
      onMouseDown={(e) => e.target === e.currentTarget && onClose()}
    >
      <form
        ref={form}
        onSubmit={submit}
        className="plane w-full max-w-md p-5"
        role="dialog"
        aria-modal="true"
        aria-label="Operator access"
      >
        <Micro>Operator access</Micro>
        {!c ? (
          <div className="mt-4 h-24 animate-pulse rounded-xl bg-slate-500/10" aria-busy="true" />
        ) : (
          <>
            <h2 className="mt-2 text-base font-semibold text-slate-100">{c.title}</h2>
            <Scope />
            {c.label && (
              <label className="mt-4 block">
                <span className="micro">{c.label}</span>
                <input
                  ref={input}
                  autoFocus
                  type={mode === "claim" ? "text" : "password"}
                  autoComplete={mode === "password" ? "current-password" : "off"}
                  spellCheck={false}
                  value={value}
                  onChange={(e) => setValue(e.target.value)}
                  placeholder={mode === "claim" ? "XXXXX-XXXXX-XXXXX-XXXXX-XXXXX-X" : undefined}
                  className={`mt-1 w-full rounded-md border border-edge bg-ink/60 px-3 py-2 text-sm text-slate-200 outline-none focus:border-aurora/60 ${mode === "claim" ? "font-mono tracking-wider" : ""}`}
                />
              </label>
            )}
            <p className="mt-2 text-xs text-slate-500">{c.hint}</p>

            {error && (
              <p role="alert" className="mt-3 text-sm text-rose-400">
                {error}
              </p>
            )}

            <div className="mt-5 flex flex-wrap items-center justify-end gap-2">
              {mode !== "token" ? (
                <button
                  type="button"
                  onClick={() => setMode("token")}
                  className="mr-auto text-xs text-slate-500 underline-offset-2 hover:text-slate-300 hover:underline"
                >
                  Use admin token
                </button>
              ) : (
                <button
                  type="button"
                  onClick={load}
                  className="mr-auto text-xs text-slate-500 underline-offset-2 hover:text-slate-300 hover:underline"
                >
                  Back
                </button>
              )}
              <button
                type="button"
                onClick={onClose}
                className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:bg-edge/50"
              >
                {mode === "none" ? "Close" : "Cancel"}
              </button>
              {c.cta && (
                <button
                  type="submit"
                  disabled={busy || value.trim() === ""}
                  className="rounded-md bg-[color:var(--accent-strong)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
                >
                  {busy ? "Checking…" : c.cta}
                </button>
              )}
            </div>
          </>
        )}
      </form>
    </div>
  );
}

/** What unlocking reaches, as chips rather than a paragraph. */
function Scope() {
  return (
    <div className="mt-3 flex flex-wrap gap-1.5" aria-label="Unlocks">
      {["Routing objective", "Tail-latency hedging", "Model catalogue", "All tenants"].map((t) => (
        <span
          key={t}
          className="rounded-full border border-amber-500/30 bg-amber-500/10 px-2 py-0.5 text-[11px] font-medium text-amber-300"
        >
          {t}
        </span>
      ))}
    </div>
  );
}
