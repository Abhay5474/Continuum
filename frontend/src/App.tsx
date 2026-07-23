import { useState } from "react";
import { Link, NavLink, Outlet } from "react-router-dom";
import { ThemeToggle } from "./components/ui";

const NAV: [string, string, boolean?][] = [
  ["/dashboard", "Dashboard"],
  ["/gateway", "Gateway"],
  ["/portal", "Developer Portal"],
  ["/billing", "Billing"],
  ["/settings", "Settings"],
  ["/autopilot", "Autopilot"],
  ["/godmode", "⚡ Agentic Autopilot", true],
  ["/dag", "V6 Trace"],
  ["/mmu", "Context MMU"],
  ["/replay", "Replay Verify"],
  ["/router", "Model Router"],
  ["/memory", "Memory"],
  ["/chaos", "Chaos"],
  ["/ai-chaos", "AI Chaos"],
];

export default function App() {
  const [open, setOpen] = useState(false);

  const linkClass = ({ isActive }: { isActive: boolean }) =>
    `rounded-lg px-3 py-1.5 text-sm font-medium transition-all duration-200 ${
      isActive
        ? "bg-aurora/15 text-indigo-200 shadow-glow-sm ring-1 ring-aurora/40"
        : "text-slate-400 hover:bg-edge/60 hover:text-slate-200"
    }`;

  const featuredClass = ({ isActive }: { isActive: boolean }) =>
    `rounded-lg px-3 py-1.5 text-sm font-semibold transition-all duration-200 ${
      isActive
        ? "bg-gradient-to-r from-aurora/30 to-neon/25 text-white shadow-glow ring-1 ring-aurora/50"
        : "text-indigo-300 hover:bg-aurora/10 hover:shadow-glow-sm"
    }`;

  return (
    <div className="min-h-full">
      <header className="sticky top-0 z-30 border-b border-edge/70 bg-ink/80 backdrop-blur-md">
        <div className="mx-auto flex max-w-7xl items-center gap-4 px-4 py-3">
          <Link to="/" className="group flex shrink-0 items-center gap-2.5">
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

          {/* desktop nav */}
          <nav className="ml-auto hidden flex-wrap items-center justify-end gap-1 xl:flex">
            {NAV.map(([to, label, featured]) => (
              <NavLink key={to} to={to} className={featured ? featuredClass : linkClass}>
                {label}
              </NavLink>
            ))}
            <Link to="/docs" className="rounded-lg px-3 py-1.5 text-sm text-slate-400 transition-colors hover:text-neon">
              Docs
            </Link>
            <ThemeToggle className="ml-1" />
          </nav>

          {/* mobile actions */}
          <div className="ml-auto flex items-center gap-2 xl:hidden">
            <ThemeToggle />
            <button
              onClick={() => setOpen((o) => !o)}
              className="rounded-lg border border-edge p-2 text-slate-300 transition-colors hover:border-neon/50"
              aria-label="Toggle navigation"
            >
              <span className="block h-0.5 w-5 bg-current" />
              <span className="mt-1 block h-0.5 w-5 bg-current" />
              <span className="mt-1 block h-0.5 w-5 bg-current" />
            </button>
          </div>
        </div>

        {/* mobile menu */}
        {open && (
          <nav className="border-t border-edge/60 bg-panel/95 px-4 py-3 xl:hidden animate-fade-up">
            <div className="grid grid-cols-2 gap-1.5">
              {NAV.map(([to, label, featured]) => (
                <NavLink
                  key={to}
                  to={to}
                  onClick={() => setOpen(false)}
                  className={featured ? featuredClass : linkClass}
                >
                  {label}
                </NavLink>
              ))}
              <Link
                to="/docs"
                onClick={() => setOpen(false)}
                className="rounded-lg px-3 py-1.5 text-sm text-slate-400 hover:text-neon"
              >
                Docs
              </Link>
            </div>
          </nav>
        )}
      </header>
      <main className="mx-auto max-w-7xl px-4 py-6">
        <Outlet />
      </main>
    </div>
  );
}
