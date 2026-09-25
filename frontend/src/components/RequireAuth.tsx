import { useSyncExternalStore } from "react";
import { Navigate, Outlet, useLocation } from "react-router-dom";
import { portal, sessionExpiry, SESSION_ENDED } from "../api";

/**
 * Gate for the console routes.
 *
 * Console data is tenant-scoped server-side, so an anonymous visitor would only
 * ever get 401s and empty panels. Redirecting to sign-in keeps that honest, and
 * carries the intended path so the user lands where they were going.
 *
 * <p>The gate is live, not a check made once on arrival. A session ends while
 * the console is open — it lapses after twelve hours, the server stops
 * accepting it, or the user signs out in another tab — and each of those now
 * takes the user to sign in, rather than leaving them on a page that can no
 * longer load anything.
 */
function subscribe(onChange: () => void) {
  window.addEventListener(SESSION_ENDED, onChange);
  window.addEventListener("storage", onChange); // another tab signed in or out
  return () => {
    window.removeEventListener(SESSION_ENDED, onChange);
    window.removeEventListener("storage", onChange);
  };
}

export default function RequireAuth() {
  const location = useLocation();
  const session = useSyncExternalStore(subscribe, portal.session);
  const exp = sessionExpiry(session);
  const lapsed = exp !== null && exp * 1000 <= Date.now();
  if (!session || lapsed) {
    const next = encodeURIComponent(location.pathname + location.search);
    // "ended" distinguishes a session that stopped from a first visit, so the
    // sign-in page can say why the user is there.
    const why = session || portal.sessionEnded() ? "&ended=1" : "";
    return <Navigate to={`/signin?next=${next}${why}`} replace />;
  }
  return <Outlet />;
}
