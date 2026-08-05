import { createContext, useContext, useEffect, useState, type ReactNode } from "react";
import { portal, hasOperator } from "../api";
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
 * present the admin token; the operator token is stored beside your session and
 * sent only on engine-wide endpoints. Dropping it leaves you signed in.
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

function ElevateDialog({ onClose, onDone }: { onClose: () => void; onDone: () => void }) {
  const [token, setToken] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState<string | null>(null);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    document.addEventListener("keydown", onKey);
    return () => document.removeEventListener("keydown", onKey);
  }, [onClose]);

  const submit = async (e: React.FormEvent) => {
    e.preventDefault();
    setBusy(true);
    setError(null);
    try {
      await portal.elevate(token.trim());
      onDone();
    } catch (err: any) {
      setError(err?.message ?? "Could not verify that token.");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div
      className="fixed inset-0 z-50 flex items-center justify-center bg-ink/80 p-4 backdrop-blur-sm"
      onMouseDown={(e) => e.target === e.currentTarget && onClose()}
    >
      <form
        onSubmit={submit}
        className="plane w-full max-w-md p-5"
        role="dialog"
        aria-modal="true"
        aria-label="Operator access"
      >
        <Micro>Operator access</Micro>
        <h2 className="mt-2 text-base font-semibold text-slate-100">Unlock engine-wide settings</h2>
        <p className="mt-2 text-sm text-slate-400">
          Routing, tail-latency hedging and the model catalogue apply to every tenant on this
          deployment and change what it spends, so they need the operator token. You stay signed in
          as yourself — this only adds the extra permission.
        </p>

        <label className="mt-4 block">
          <span className="micro">Admin token</span>
          <input
            autoFocus
            type="password"
            value={token}
            onChange={(e) => setToken(e.target.value)}
            placeholder="CONTINUUM_ADMIN_TOKEN"
            className="mt-1 w-full rounded-md border border-edge bg-ink/60 px-3 py-2 text-sm text-slate-200 outline-none focus:border-aurora/60"
          />
        </label>
        <p className="mt-1.5 text-xs text-slate-500">
          The value of <code className="text-slate-400">CONTINUUM_ADMIN_TOKEN</code> on the server.
          If it was never set, operator access is unavailable on this deployment.
        </p>

        {error && <p className="mt-3 text-sm text-rose-400">{error}</p>}

        <div className="mt-5 flex justify-end gap-2">
          <button
            type="button"
            onClick={onClose}
            className="rounded-md border border-edge px-3 py-1.5 text-sm text-slate-300 hover:bg-edge/50"
          >
            Cancel
          </button>
          <button
            type="submit"
            disabled={busy || token.trim() === ""}
            className="rounded-md bg-[color:var(--accent-strong)] px-3 py-1.5 text-sm font-medium text-white hover:opacity-90 disabled:opacity-50"
          >
            {busy ? "Verifying…" : "Unlock"}
          </button>
        </div>
      </form>
    </div>
  );
}
