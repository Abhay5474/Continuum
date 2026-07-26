import { useEffect, useState } from "react";
import { useNavigate, useSearchParams } from "react-router-dom";
import { portal } from "../api";

/**
 * Joining an account you were invited to.
 *
 * The invite link used to point at a page that did not exist. Accepting now
 * creates the invitee's own credentials and puts them in the inviting account,
 * so they sign in as themselves and work in the team's data.
 */
export default function AcceptInvite() {
  const [params] = useSearchParams();
  const token = params.get("token") ?? "";
  const navigate = useNavigate();

  const [invite, setInvite] = useState<any | null>(null);
  const [loadError, setLoadError] = useState<string | null>(null);
  const [name, setName] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    if (!token) {
      setLoadError("This link is missing its invite token.");
      return;
    }
    portal
      .previewInvite(token)
      .then(setInvite)
      .catch((e) => setLoadError(e?.message ?? "This invite link is not valid or has expired."));
  }, [token]);

  const accept = async () => {
    setBusy(true);
    setError(null);
    try {
      await portal.acceptInvite(token, name, password);
      navigate("/dashboard");
    } catch (e: any) {
      setError(e?.message ?? "Could not accept this invite");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="mx-auto flex min-h-screen max-w-md flex-col justify-center px-6">
      <div className="rounded-xl border border-edge bg-panel p-6">
        <h1 className="text-lg font-semibold tracking-tight">Join on Continuum</h1>

        {loadError && (
          <>
            <p className="mt-2 text-sm text-rose-400">{loadError}</p>
            <p className="mt-3 text-xs text-slate-500">
              Invites expire after seven days and can be withdrawn. Ask whoever invited you to send a
              new one.
            </p>
          </>
        )}

        {invite && (
          <>
            <p className="mt-2 text-sm text-slate-400">
              <span className="text-slate-200">{invite.invitedBy}</span> invited{" "}
              <span className="font-mono text-slate-300">{invite.email}</span> to their account.
              Choose a password and you will sign in as yourself, working in their workspace.
            </p>

            <label className="mt-5 block">
              <span className="text-[10px] uppercase tracking-widest text-slate-500">Your name</span>
              <input
                value={name}
                onChange={(e) => setName(e.target.value)}
                placeholder={invite.email}
                className="mt-1 w-full rounded-lg border border-edge bg-ink px-3 py-2 text-sm outline-none focus:border-aurora/60"
              />
            </label>

            <label className="mt-3 block">
              <span className="text-[10px] uppercase tracking-widest text-slate-500">Password</span>
              <input
                type="password"
                value={password}
                onChange={(e) => setPassword(e.target.value)}
                onKeyDown={(e) => e.key === "Enter" && password.length >= 6 && accept()}
                className="mt-1 w-full rounded-lg border border-edge bg-ink px-3 py-2 text-sm outline-none focus:border-aurora/60"
              />
              <span className="mt-1 block text-[10px] text-slate-600">At least 6 characters.</span>
            </label>

            {error && <p className="mt-3 text-sm text-rose-400">{error}</p>}

            <button
              onClick={accept}
              disabled={busy || password.length < 6}
              className="mt-5 w-full rounded-lg bg-indigo-600 px-4 py-2 text-sm font-medium text-white hover:bg-indigo-500 disabled:opacity-50"
            >
              {busy ? "Joining…" : "Accept invite"}
            </button>
          </>
        )}

        {!invite && !loadError && <p className="mt-3 text-sm text-slate-500">Checking this invite…</p>}
      </div>
    </div>
  );
}
