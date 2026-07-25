import { Navigate, Outlet, useLocation } from "react-router-dom";
import { portal } from "../api";

/**
 * Gate for the console routes.
 *
 * Console data is tenant-scoped server-side, so an anonymous visitor would only
 * ever get 401s and empty panels. Redirecting to sign-in keeps that honest, and
 * carries the intended path so the user lands where they were going.
 */
export default function RequireAuth() {
  const location = useLocation();
  if (!portal.session()) {
    const next = encodeURIComponent(location.pathname + location.search);
    return <Navigate to={`/signin?next=${next}`} replace />;
  }
  return <Outlet />;
}
