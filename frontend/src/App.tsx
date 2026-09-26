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
import { useLiquidSurface } from "./system/primitives";
import { BrandMark } from "./system/brand";

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
  // Every console page sits on the wallpaper and its cards are liquid glass.
  useLiquidSurface();
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
        // Focus inside the menu goes back to the button that opened it, not
        // to the top of the document when the menu unmounts under it.
        const inMenu = (document.activeElement as HTMLElement | null)?.closest('[role="menu"]');
        setOpenMenu((open) => {
          if (open && inMenu) {
            navRef.current?.querySelector<HTMLElement>(`[data-menu-trigger="${open}"]`)?.focus();
          }
          return null;
        });
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

  // Menu buttons, the way assistive tech expects them to behave: opening from
  // the keyboard puts focus on the first item; arrows move through the items;
  // Tab leaves the menu and closes it. Before, Tab from a trigger skipped the
  // whole menu to the next trigger, so no menu item was reachable by keyboard.
  const focusFirst = useRef(false);
  const menuItems = () =>
    Array.from(navRef.current?.querySelectorAll<HTMLElement>('[role="menu"] a[href], [role="menu"] button') ?? [])
      .filter((el) => !el.closest("[aria-hidden='true'], .pointer-events-none"));
  useEffect(() => {
    if (!openMenu || !focusFirst.current) return;
    focusFirst.current = false;
    const id = requestAnimationFrame(() => menuItems()[0]?.focus());
    return () => cancelAnimationFrame(id);
  }, [openMenu]);
  const onTriggerKey = (label: string) => (e: React.KeyboardEvent) => {
    if (e.key === "ArrowDown" || ((e.key === "Enter" || e.key === " ") && openMenu !== label)) {
      e.preventDefault();
      focusFirst.current = true;
      if (openMenu === label) menuItems()[0]?.focus();
      else setOpenMenu(label);
    }
  };
  const onMenuKey = (e: React.KeyboardEvent) => {
    if (!(e.target as HTMLElement).closest('[role="menu"]')) return;
    const items = menuItems();
    const i = items.indexOf(document.activeElement as HTMLElement);
    const go = (n: number) => {
      e.preventDefault();
      items[(n + items.length) % items.length]?.focus();
    };
    if (e.key === "ArrowDown") go(i + 1);
    else if (e.key === "ArrowUp") go(i - 1);
    else if (e.key === "Home") go(0);
    else if (e.key === "End") go(items.length - 1);
    else if (e.key === "Tab") {
      // Leave from the trigger, so Tab carries on to the bar's next control
      // instead of falling to the top of the page as the menu unmounts.
      navRef.current?.querySelector<HTMLElement>(`[data-menu-trigger="${openMenu}"]`)?.focus();
      setOpenMenu(null);
    }
  };

  const signOut = () => {
    portal.logout();
    navigate("/");
  };

  const groupActive = (g: Group) => g.items.some((i) => location.pathname.startsWith(i.to));
  const guide = guideFor(location.pathname);

  // Each page names its tab. Every console tab used to read "Continuum —
  // Durable AI Workflow Runtime", so ten open tabs were ten identical labels,
  // and a screen reader announced no page change at all.
  useEffect(() => {
    const view = FEATURES.flatMap((f) => f.views.map((v) => ({ ...v, feature: f.name })))
      .filter((v) => location.pathname === v.to || location.pathname.startsWith(v.to + "/"))
      .sort((a, b) => b.to.length - a.to.length)[0];
    const name = location.pathname.startsWith("/dashboard")
      ? "Command Centre"
      : view
        ? view.feature === view.label ? view.label : `${view.label} · ${view.feature}`
        : null;
    document.title = name ? `${name} — Continuum` : "Continuum";
  }, [location.pathname]);

  return (
    <div className="min-h-full">
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-[80] focus:rounded-full focus:bg-card focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:shadow-[var(--ring)]"
      >
        Skip to content
      </a>
      <header className="glass-bar sticky top-0 z-30">
        <div ref={navRef} onKeyDown={onMenuKey} className="relative mx-auto flex max-w-[1200px] items-center gap-2 px-5 py-2.5">
          <Link to="/dashboard" className="mr-2 flex shrink-0 items-center gap-2.5">
            <BrandMark />
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
                  onKeyDown={onTriggerKey(g.label)}
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
                onKeyDown={onTriggerKey("account")}
                aria-haspopup="true"
                aria-expanded={openMenu === "account"}
                className="flex h-8 w-8 items-center justify-center rounded-full border border-edge bg-panel text-xs font-semibold text-slate-300 transition-colors hover:border-aurora/50"
                aria-label="Account menu"
                data-tip="Account"
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
