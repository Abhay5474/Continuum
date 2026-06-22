import { Link, NavLink, Outlet } from "react-router-dom";

export default function App() {
  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `px-3 py-2 rounded-md text-sm font-medium ${
      isActive ? "bg-indigo-600 text-white" : "text-slate-300 hover:bg-edge"
    }`;

  return (
    <div className="min-h-full">
      <header className="border-b border-edge bg-panel">
        <div className="mx-auto max-w-7xl px-4 py-3 flex items-center gap-6">
          <Link to="/" className="flex items-center gap-2">
            <span className="text-xl font-bold tracking-tight">⟳ Continuum</span>
            <span className="text-xs text-slate-400 hidden sm:inline">
              Durable AI Workflow Runtime
            </span>
          </Link>
          <nav className="flex flex-wrap gap-1 ml-auto">
            <NavLink to="/" end className={linkClass}>
              Dashboard
            </NavLink>
            <NavLink to="/gateway" className={linkClass}>
              Gateway
            </NavLink>
            <NavLink to="/replay" className={linkClass}>
              Replay Verify
            </NavLink>
            <NavLink to="/router" className={linkClass}>
              Model Router
            </NavLink>
            <NavLink to="/memory" className={linkClass}>
              Memory
            </NavLink>
            <NavLink to="/chaos" className={linkClass}>
              Chaos
            </NavLink>
            <NavLink to="/ai-chaos" className={linkClass}>
              AI Chaos
            </NavLink>
          </nav>
        </div>
      </header>
      <main className="mx-auto max-w-7xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
