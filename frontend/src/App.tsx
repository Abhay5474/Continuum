import { Link, NavLink, Outlet } from "react-router-dom";

export default function App() {
  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `rounded-lg px-3 py-1.5 text-sm font-medium transition-all duration-200 ${
      isActive
        ? "bg-aurora/15 text-violet-200 shadow-glow-sm ring-1 ring-aurora/40"
        : "text-slate-400 hover:bg-edge/60 hover:text-slate-200"
    }`;

  return (
    <div className="min-h-full">
      <header className="sticky top-0 z-20 border-b border-edge/70 bg-ink/80 backdrop-blur-md">
        <div className="mx-auto max-w-7xl px-4 py-3 flex items-center gap-6">
          <Link to="/" className="group flex items-center gap-2.5">
            <span className="relative flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-aurora to-neon text-lg font-bold text-ink shadow-glow-sm transition-shadow group-hover:shadow-glow">
              ⟳
            </span>
            <span>
              <span className="block text-lg font-bold leading-tight tracking-tight text-gradient">
                Continuum
              </span>
              <span className="hidden text-[10px] uppercase tracking-widest text-slate-500 sm:block">
                Durable AI Runtime
              </span>
            </span>
          </Link>
          <nav className="ml-auto flex flex-wrap items-center gap-1">
            <NavLink to="/" end className={linkClass}>
              Dashboard
            </NavLink>
            <NavLink to="/gateway" className={linkClass}>
              Gateway
            </NavLink>
            <NavLink to="/portal" className={linkClass}>
              Developer Portal
            </NavLink>
            <NavLink to="/autopilot" className={linkClass}>
              Autopilot
            </NavLink>
            <NavLink
              to="/godmode"
              className={({ isActive }) =>
                `relative rounded-lg px-3 py-1.5 text-sm font-semibold transition-all duration-200 ${
                  isActive
                    ? "bg-gradient-to-r from-aurora/30 to-neon/25 text-white shadow-glow ring-1 ring-aurora/50"
                    : "text-violet-300 hover:bg-aurora/10 hover:shadow-glow-sm"
                }`
              }
            >
              ⚡ Agentic Autopilot
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
