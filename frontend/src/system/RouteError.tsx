import { useEffect, useState } from "react";
import { Link, isRouteErrorResponse, useLocation, useNavigate, useRouteError } from "react-router-dom";
import { FEATURES } from "./features";
import { Button } from "./controls";

/**
 * What a console route shows when it cannot show itself.
 *
 * <p>Before this, an unknown URL — or any exception thrown while rendering a
 * page — replaced the entire console with React Router's developer screen
 * ("Hey developer 👋"), navigation bar included, so the only way out was the
 * browser's back button. Rendered inside the console layout now, so the bar
 * stays and every other page remains one click away.
 *
 * <p>Three cases, because they need different answers:
 * <ul>
 *   <li><b>No such page</b> — say so, and offer the pages whose names are
 *       closest to what was typed.</li>
 *   <li><b>A page's code could not be downloaded</b> — almost always a deploy
 *       that renamed the files while this tab was open. Reloading fetches the
 *       new ones, so that happens once, automatically.</li>
 *   <li><b>A page crashed</b> — say which page, offer to try again, and keep the
 *       technical message one click away for whoever reports it.</li>
 * </ul>
 */
export function RouteError() {
  const error = useRouteError();
  const location = useLocation();
  const nav = useNavigate();

  const message = error instanceof Error ? error.message : String(error ?? "Unknown error");
  const stale = isStaleChunk(message);
  // Once per half minute per page: a reload that did not help must not become
  // a reload loop, but a later deploy deserves its own reload.
  const key = `continuum.reloaded:${location.pathname}`;
  const [reloading] = useState(() => {
    if (!stale) return false;
    try {
      return Date.now() - Number(sessionStorage.getItem(key) ?? 0) >= 30_000;
    } catch {
      return false;
    }
  });
  useEffect(() => {
    if (!reloading) return;
    try {
      sessionStorage.setItem(key, String(Date.now()));
    } catch {
      /* the button below still works */
    }
    window.location.reload();
  }, [reloading, key]);

  if (isRouteErrorResponse(error) && error.status === 404) return <NotFound />;
  if (reloading) return null;

  return (
    <div role="alert" className="mx-auto max-w-xl py-16 text-center">
      <p className="text-[15px] font-semibold text-slate-100">This page ran into a problem</p>
      <p className="mt-2 text-[13px] leading-relaxed text-slate-400">
        {stale
          ? "Continuum was updated while this tab was open, and this page's code could not be fetched. Reloading picks up the new version."
          : "Nothing you entered was lost from the server — this is the page failing to draw, not your data failing to save. Trying again usually works; if it keeps happening, the details below help us find it."}
      </p>
      <div className="mt-5 flex justify-center gap-2">
        <Button variant="primary" onClick={() => (stale ? window.location.reload() : nav(0))}>
          {stale ? "Reload" : "Try again"}
        </Button>
        <Button onClick={() => nav("/dashboard")}>Command Centre</Button>
      </div>
      <details className="mx-auto mt-6 max-w-md text-left">
        <summary className="cursor-pointer text-xs text-slate-500 hover:text-slate-300">Technical details</summary>
        <pre className="mt-2 max-h-48 overflow-auto whitespace-pre-wrap rounded-lg border border-edge p-3 font-mono text-[11px] text-slate-400">
          {location.pathname}
          {"\n"}
          {message}
        </pre>
      </details>
    </div>
  );
}

function isStaleChunk(message: string) {
  return /dynamically imported module|Importing a module script failed|error loading dynamically|ChunkLoadError/i.test(
    message
  );
}

/** Every page a person can reach from the menus, for suggestions. */
const PAGES = FEATURES.flatMap((f) => f.views.map((v) => ({ to: v.to, label: v.label, feature: f.name })));

export function NotFound() {
  const location = useLocation();
  const typed = location.pathname.replace(/^\/+|\/+$/g, "").toLowerCase();
  const suggestions = PAGES.map((p) => ({ ...p, d: distance(typed, p.to.slice(1).toLowerCase()) }))
    .filter((p) => p.d <= Math.max(3, Math.floor(typed.length / 2)))
    .sort((a, b) => a.d - b.d)
    .slice(0, 3);
  return (
    <div className="mx-auto max-w-xl py-16 text-center">
      <p className="text-[15px] font-semibold text-slate-100">There is no page at {location.pathname}</p>
      <p className="mt-2 text-[13px] text-slate-400">
        The link may be mistyped, or the page may have moved.
        {suggestions.length > 0 ? " Perhaps one of these:" : ""}
      </p>
      {suggestions.length > 0 && (
        <ul className="mx-auto mt-4 flex max-w-sm flex-col gap-1.5">
          {suggestions.map((s) => (
            <li key={s.to}>
              <Link
                to={s.to}
                className="press block rounded-[12px] border border-card-edge bg-card px-3 py-2 text-left text-[13px] hover:border-slate-500/50"
              >
                <span className="font-medium text-slate-200">{s.label}</span>
                <span className="ml-2 text-xs text-slate-500">
                  {s.feature !== s.label ? `${s.feature} · ` : ""}
                  {s.to}
                </span>
              </Link>
            </li>
          ))}
        </ul>
      )}
      <div className="mt-5">
        <Link to="/dashboard" className="text-[13px] font-medium text-[color:var(--accent-ink)] hover:underline">
          Go to the Command Centre
        </Link>
      </div>
    </div>
  );
}

/** Edit distance, for "did you mean". Paths are short, so the table is tiny. */
function distance(a: string, b: string): number {
  const row = Array.from({ length: b.length + 1 }, (_, i) => i);
  for (let i = 1; i <= a.length; i++) {
    let prev = row[0];
    row[0] = i;
    for (let j = 1; j <= b.length; j++) {
      const tmp = row[j];
      row[j] = Math.min(row[j] + 1, row[j - 1] + 1, prev + (a[i - 1] === b[j - 1] ? 0 : 1));
      prev = tmp;
    }
  }
  return row[b.length];
}
