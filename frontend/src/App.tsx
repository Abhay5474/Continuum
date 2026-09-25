import { useEffect, useRef, useState } from "react";
import { MenuSurface } from "./system/MenuSurface";
import { PageStage } from "./system/PageStage";
import { NavProgress } from "./system/NavProgress";
import { Link, NavLink, Outlet, useLocation, useNavigate } from "react-router-dom";
import { portal } from "./api";
import { ThemeToggle } from "./components/ui";
import { useOperator } from "./system/OperatorAccess";
import { GuideButton } from "./system/guide";
import { guideFor } from "./system/guides";
import { FEATURES } from "./system/features";
import FeatureTabs from "./system/FeatureTabs";

/**
 * Console shell.
 *
 * Navigation is grouped rather than a flat list of every feature: fourteen
 * top-level links overflowed onto a second row and gave no sense of hierarchy.
 * Features are now organised by what they do, with account actions in a menu.
 */
type Item = { to: string; label: string; desc: string; views?: string[] };
type Group = { label: string; items: Item[] };

const GROUPS: Group[] = [
  { label: "Traffic", items: featureItems(["Workflows", "Gateway", "Routing", "Traffic Control", "Model Cascade"]) },
  { label: "Prompt", items: featureItems(["Pipelines", "Specialists", "Prompt Guard", "Context", "Semantic Cache"]) },
  { label: "Reliability", items: featureItems(["Answer Assurance", "Loop Detection", "Decision Provenance", "Chaos Lab"]) },
  { label: "Intelligence", items: featureItems(["Adaptive Policy"]) },
];

/**
 * The menu entries for a set of features, in the order named.
 *
 * <p>Built from the same registry the tab strip uses, so a feature cannot appear
 * in the navigation under one name and in its own tabs under another — which is
 * exactly what happened while thirty routes each maintained their own label.
 */
function featureItems(names: string[]): Item[] {
  return names.flatMap((name) => {
    const f = FEATURES.find((x) => x.name === name);
    if (!f) {
      return [];
    }
    return [{
      to: f.views[0].to,
      label: f.name,
      desc: f.desc,
      // Named so the menu can show what is inside a merged feature without
      // making the reader open it to find out.
      views: f.views.length > 1 ? f.views.map((v) => v.label) : undefined,
    }];
  });
}

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
  const guide = guideFor(location.pathname);

  return (
    <div className="min-h-full">
      <header className="glass-bar sticky top-0 z-30">
        <div ref={navRef} className="relative mx-auto flex max-w-[1200px] items-center gap-2 px-5 py-2.5">
          <Link to="/dashboard" className="mr-2 flex shrink-0 items-center gap-2.5">
            <span
              className="flex h-[26px] w-[26px] items-center justify-center rounded-[7px]"
              style={{ background: "var(--accent-strong)", color: "var(--accent-on)" }}
              aria-hidden
            >
              <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor"
                   strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
                <path d="M13.5 8a5.5 5.5 0 1 1-1.9-4.15" />
                <path d="M13.7 1.9v3.4h-3.4" />
              </svg>
            </span>
            <span className="hidden text-[14.5px] font-semibold tracking-tight text-slate-100 sm:block">
              Continuum
            </span>
          </Link>

          {/* primary nav */}
          <nav className="hidden items-center gap-0.5 lg:flex">
            <NavLink to="/dashboard" className={({ isActive }) => topLink(isActive)}>
              Command Centre
            </NavLink>

            {GROUPS.map((g) => (
              <div key={g.label} className="relative">
                <button
                  data-menu-trigger={g.label}
                  onClick={() => setOpenMenu(openMenu === g.label ? null : g.label)}
                  // With a menu already open, pointing at a neighbour switches
                  // to it — the shared surface then slides across rather than
                  // closing and reopening.
                  onPointerEnter={(e) => {
                    if (e.pointerType === "mouse" && openMenu && openMenu !== g.label) setOpenMenu(g.label);
                  }}
                  className={`${topLink(groupActive(g))} inline-flex items-center gap-1`}
                  aria-expanded={openMenu === g.label}
                  aria-haspopup="true"
                >
                  {g.label}
                  <Chevron open={openMenu === g.label} />
                </button>
              </div>
            ))}
          </nav>

          {/* Every menu in the bar is this one surface. See MenuSurface. */}
          <MenuSurface
            open={openMenu}
            root={navRef}
            align={(k) => (k === "account" ? "right" : "left")}
            render={(k) =>
              k === "account" ? (
                <MenuItems
                  items={ACCOUNT}
                  footer={
                    <>
                      <button
                        onClick={operator ? dropOperator : requestOperator}
                        className="w-full rounded-[12px] px-3 py-2 text-left transition-colors hover:bg-slate-500/[0.09]"
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
                        className="w-full rounded-[12px] px-3 py-2 text-left text-sm text-slate-400 transition-colors hover:bg-slate-500/[0.09] hover:text-slate-200"
                      >
                        Sign out
                      </button>
                    </>
                  }
                />
              ) : (
                <MenuItems items={GROUPS.find((g) => g.label === k)?.items ?? []} />
              )
            }
          />

          {/* right side */}
          <div className="ml-auto flex items-center gap-1.5">
            {/* The guide for whatever page you are on. Living in the bar rather
                than on each page means a feature cannot ship without one being
                noticed as missing, and it is always in the same place. */}
            {guide && <GuideButton guide={guide} guideKey={location.pathname} />}
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
                data-menu-trigger="account"
                onClick={() => setOpenMenu(openMenu === "account" ? null : "account")}
                className="flex h-8 w-8 items-center justify-center rounded-full border border-edge bg-panel text-xs font-semibold text-slate-300 transition-colors hover:border-aurora/50"
                aria-label="Account menu"
                data-tip="Account"
                aria-haspopup="true"
              >
                ●
              </button>
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
          <nav className="mx-auto max-h-[70vh] max-w-[1200px] overflow-y-auto border-t border-edge/60 px-5 py-3 lg:hidden">
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
        <NavProgress />
      </header>

      {/* One entrance for every page, keyed on the route, applied in the shell
          so no page can forget it. The entrance depends on where the page
          came from — see PageStage. */}
      <PageStage className="mx-auto max-w-[1200px] px-5 pb-16 pt-6">
        <FeatureTabs />
        <Outlet />
      </PageStage>
    </div>
  );
}

function topLink(isActive: boolean) {
  return `press rounded-full px-3 py-1.5 text-[13px] font-medium ${
    isActive
      ? "bg-slate-500/[0.13] text-slate-100"
      : "text-slate-400 hover:bg-slate-500/[0.08] hover:text-slate-200"
  }`;
}

function Chevron({ open }: { open: boolean }) {
  return (
    <svg
      viewBox="0 0 12 12"
      // Turns on the elastic spring, landing with the same small settle as the
      // menu it belongs to.
      className={`spin-to h-2.5 w-2.5 ${open ? "rotate-180" : ""}`}
      fill="none"
      stroke="currentColor"
      strokeWidth="1.8"
    >
      <path d="M2.5 4.5 6 8l3.5-3.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function MenuItems({
  items,
  footer,
}: {
  items: Item[];
  footer?: import("react").ReactNode;
}) {
  return (
    <>
      {items.map((i) => (
        <NavLink
          key={i.to}
          to={i.to}
          role="menuitem"
          className={({ isActive }) =>
            `press block rounded-[12px] px-3 py-2 ${
              isActive ? "bg-slate-500/[0.13]" : "hover:bg-slate-500/[0.08]"
            }`
          }
        >
          <div className="text-[13px] font-medium text-slate-200">{i.label}</div>
          <div className="text-[11.5px] leading-relaxed text-slate-500">{i.desc}</div>
          {/* What a merged feature is made of. Naming the parts here is the
              difference between "we combined three things" and "we hid two". */}
          {i.views && (
            <div className="mt-1 text-[11px] text-slate-600">{i.views.join(" · ")}</div>
          )}
        </NavLink>
      ))}
      {footer && <div className="mt-1 border-t border-edge/60 pt-1">{footer}</div>}
    </>
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
