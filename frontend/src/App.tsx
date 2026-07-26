import { useEffect, useRef, useState } from "react";
import { Link, NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { portal } from "./api";
import { ThemeToggle } from "./components/ui";
import AmbientField from "./world/AmbientField";
import { useOperator } from "./system/OperatorAccess";

/**
 * Console shell.
 *
 * Navigation is grouped rather than a flat list of every feature: fourteen
 * top-level links overflowed onto a second row and gave no sense of hierarchy.
 * Features are now organised by what they do, with account actions in a menu.
 */
type Item = { to: string; label: string; desc: string };
type Group = { label: string; items: Item[] };

const GROUPS: Group[] = [
  {
    label: "Traffic",
    items: [
      { to: "/workflows", label: "Workflows", desc: "Author, publish and run durable graphs" },
      { to: "/workflows/console", label: "Run History", desc: "Every run, its event log and replay" },
      { to: "/gateway", label: "Gateway", desc: "Live requests, providers and failover" },
      { to: "/router", label: "Routing", desc: "Model selection and tail-latency hedging" },
    ],
  },
  {
    // Everything that acts on a prompt on its way to a model: what is stripped
    // from it, whether it needs to be sent at all, and what context travels with
    // it. Memory and the context optimizer used to sit under "Intelligence"
    // beside the learning features, which put four unrelated things in one menu.
    label: "Prompt",
    items: [
      { to: "/guard", label: "Prompt Guard", desc: "PII redaction, injection blocking, compression" },
      { to: "/cache", label: "Semantic Cache", desc: "Reuse answers to equivalent questions" },
      { to: "/mmu", label: "Context Optimizer", desc: "Context virtualization and paging" },
      { to: "/memory", label: "Memory", desc: "Long-context memory tiers" },
    ],
  },
  {
    label: "Reliability",
    items: [
      { to: "/dag", label: "Verification", desc: "Consensus traces and evidence" },
      { to: "/replay", label: "Replay Audit", desc: "Deterministic replay and divergence healing" },
      { to: "/chaos", label: "Fault Injection", desc: "Infrastructure failure drills" },
      { to: "/ai-chaos", label: "Model Failures", desc: "Hallucination and degradation drills" },
    ],
  },
  {
    label: "Intelligence",
    items: [
      { to: "/autopilot", label: "Optimization", desc: "Adaptive routing, canary and rollback" },
      { to: "/godmode", label: "Adaptive Policy", desc: "Autonomous memory and policy engine" },
    ],
  },
];

const ACCOUNT: Item[] = [
  { to: "/portal", label: "API Keys & Providers", desc: "Issue keys, connect provider credentials" },
  { to: "/billing", label: "Billing & Usage", desc: "Plan, quota and spend" },
  { to: "/settings", label: "Account Settings", desc: "Profile, team and security" },
];

export default function App() {
  const [openMenu, setOpenMenu] = useState<string | null>(null);
  const [mobileOpen, setMobileOpen] = useState(false);
  const navRef = useRef<HTMLDivElement>(null);
  const location = useLocation();
  const navigate = useNavigate();
  const { operator, request: requestOperator, drop: dropOperator } = useOperator();

  // Close any open menu on outside click, Escape, or navigation.
  useEffect(() => {
    const onClick = (e: MouseEvent) => {
      if (navRef.current && !navRef.current.contains(e.target as Node)) setOpenMenu(null);
    };
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "Escape") {
        setOpenMenu(null);
        setMobileOpen(false);
      }
    };
    document.addEventListener("mousedown", onClick);
    document.addEventListener("keydown", onKey);
    return () => {
      document.removeEventListener("mousedown", onClick);
      document.removeEventListener("keydown", onKey);
    };
  }, []);
  useEffect(() => {
    setOpenMenu(null);
    setMobileOpen(false);
  }, [location.pathname]);

  const signOut = () => {
    portal.logout();
    navigate("/");
  };

  const groupActive = (g: Group) => g.items.some((i) => location.pathname.startsWith(i.to));

  return (
    <div className="min-h-full">
      {/* One insertion point for the whole console: the field picks its
          arrangement from the route, so every feature page gets a signature
          without any page having to know about it. */}
      <AmbientField />
      <header className="sticky top-0 z-30 border-b border-edge/70 bg-ink/85 backdrop-blur-md">
        <div ref={navRef} className="mx-auto flex max-w-7xl items-center gap-2 px-4 py-2.5">
          <Link to="/dashboard" className="mr-2 flex shrink-0 items-center gap-2.5">
            <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-aurora to-neon text-lg font-bold text-ink">
              ⟳
            </span>
            <span className="hidden text-base font-bold tracking-tight sm:block">Continuum</span>
          </Link>

          {/* primary nav */}
          <nav className="hidden items-center gap-0.5 lg:flex">
            <NavLink to="/dashboard" className={({ isActive }) => topLink(isActive)}>
              Command Centre
            </NavLink>

            {GROUPS.map((g) => (
              <div key={g.label} className="relative">
                <button
                  onClick={() => setOpenMenu(openMenu === g.label ? null : g.label)}
                  className={`${topLink(groupActive(g))} inline-flex items-center gap-1`}
                  aria-expanded={openMenu === g.label}
                  aria-haspopup="true"
                >
                  {g.label}
                  <Chevron open={openMenu === g.label} />
                </button>
                {openMenu === g.label && <Menu items={g.items} />}
              </div>
            ))}
          </nav>

          {/* right side */}
          <div className="ml-auto flex items-center gap-1.5">
            <Link
              to="/docs"
              className="hidden rounded-lg px-3 py-1.5 text-sm text-slate-400 transition-colors hover:text-slate-200 lg:block"
            >
              Docs
            </Link>
            {/* Elevated state is worth showing continuously: while it is on, the
                switches in Routing change behaviour for every tenant. */}
            {operator && (
              <button
                onClick={dropOperator}
                title="Operator access is on. Click to drop it."
                className="hidden items-center gap-1.5 rounded-full border border-amber-500/40 bg-amber-500/10 px-2.5 py-1 text-[10px] font-medium uppercase tracking-widest text-amber-300 hover:border-amber-400/60 sm:inline-flex"
              >
                <span className="h-1.5 w-1.5 rounded-full bg-amber-400" />
                Operator
              </button>
            )}
            <ThemeToggle />

            <div className="relative hidden lg:block">
              <button
                onClick={() => setOpenMenu(openMenu === "account" ? null : "account")}
                className="flex h-8 w-8 items-center justify-center rounded-full border border-edge bg-panel text-xs font-semibold text-slate-300 transition-colors hover:border-aurora/50"
                aria-label="Account menu"
                aria-haspopup="true"
              >
                ●
              </button>
              {openMenu === "account" && (
                <Menu
                  items={ACCOUNT}
                  align="right"
                  footer={
                    <>
                      <button
                        onClick={operator ? dropOperator : requestOperator}
                        className="w-full rounded-lg px-3 py-2 text-left transition-colors hover:bg-edge/50"
                      >
                        <div className="text-sm font-medium text-slate-200">
                          {operator ? "Drop operator access" : "Operator access"}
                        </div>
                        <div className="text-xs text-slate-500">
                          {operator
                            ? "Return to your own permissions"
                            : "Unlock routing, hedging and the model catalogue"}
                        </div>
                      </button>
                      <button
                        onClick={signOut}
                        className="w-full rounded-lg px-3 py-2 text-left text-sm text-slate-400 transition-colors hover:bg-edge/50 hover:text-slate-200"
                      >
                        Sign out
                      </button>
                    </>
                  }
                />
              )}
            </div>

            <button
              onClick={() => setMobileOpen((o) => !o)}
              className="rounded-lg border border-edge p-2 text-slate-300 transition-colors hover:border-aurora/50 lg:hidden"
              aria-label="Toggle navigation"
            >
              <span className="block h-0.5 w-5 bg-current" />
              <span className="mt-1 block h-0.5 w-5 bg-current" />
              <span className="mt-1 block h-0.5 w-5 bg-current" />
            </button>
          </div>
        </div>

        {/* mobile menu */}
        {mobileOpen && (
          <nav className="max-h-[70vh] overflow-y-auto border-t border-edge/60 bg-panel/95 px-4 py-3 lg:hidden">
            <MobileLink to="/dashboard" label="Command Centre" />
            {GROUPS.map((g) => (
              <div key={g.label} className="mt-3">
                <div className="px-1 pb-1 text-[10px] font-semibold uppercase tracking-widest text-slate-500">
                  {g.label}
                </div>
                {g.items.map((i) => (
                  <MobileLink key={i.to} to={i.to} label={i.label} />
                ))}
              </div>
            ))}
            <div className="mt-3 border-t border-edge/60 pt-3">
              <div className="px-1 pb-1 text-[10px] font-semibold uppercase tracking-widest text-slate-500">
                Account
              </div>
              {ACCOUNT.map((i) => (
                <MobileLink key={i.to} to={i.to} label={i.label} />
              ))}
              <MobileLink to="/docs" label="Docs" />
              <button
                onClick={operator ? dropOperator : requestOperator}
                className="mt-1 block w-full rounded-lg px-3 py-2 text-left text-sm text-slate-400 hover:bg-edge/50 hover:text-slate-200"
              >
                {operator ? "Drop operator access" : "Operator access"}
              </button>
              <button
                onClick={signOut}
                className="mt-1 block w-full rounded-lg px-3 py-2 text-left text-sm text-slate-400 hover:bg-edge/50 hover:text-slate-200"
              >
                Sign out
              </button>
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

function topLink(isActive: boolean) {
  return `rounded-lg px-3 py-1.5 text-sm font-medium transition-colors ${
    isActive ? "bg-edge/60 text-slate-100" : "text-slate-400 hover:bg-edge/40 hover:text-slate-200"
  }`;
}

function Chevron({ open }: { open: boolean }) {
  return (
    <svg
      viewBox="0 0 12 12"
      className={`h-2.5 w-2.5 transition-transform ${open ? "rotate-180" : ""}`}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
    >
      <path d="M2.5 4.5 6 8l3.5-3.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function Menu({
  items,
  align = "left",
  footer,
}: {
  items: Item[];
  align?: "left" | "right";
  footer?: import("react").ReactNode;
}) {
  return (
    <div
      className={`absolute top-full z-40 mt-1.5 w-72 rounded-xl border border-edge bg-panel p-1.5 shadow-xl ${
        align === "right" ? "right-0" : "left-0"
      }`}
    >
      {items.map((i) => (
        <NavLink
          key={i.to}
          to={i.to}
          className={({ isActive }) =>
            `block rounded-lg px-3 py-2 transition-colors ${isActive ? "bg-edge/70" : "hover:bg-edge/50"}`
          }
        >
          <div className="text-sm font-medium text-slate-200">{i.label}</div>
          <div className="text-xs text-slate-500">{i.desc}</div>
        </NavLink>
      ))}
      {footer && <div className="mt-1 border-t border-edge/60 pt-1">{footer}</div>}
    </div>
  );
}

function MobileLink({ to, label }: { to: string; label: string }) {
  return (
    <NavLink
      to={to}
      className={({ isActive }) =>
        `block rounded-lg px-3 py-2 text-sm transition-colors ${
          isActive ? "bg-edge/60 font-medium text-slate-100" : "text-slate-400 hover:bg-edge/40 hover:text-slate-200"
        }`
      }
    >
      {label}
    </NavLink>
  );
}
