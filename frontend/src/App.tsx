import { AnimatePresence, m } from "motion/react";
import { useCallback, useEffect, useLayoutEffect, useMemo, useRef, useState, type CSSProperties } from "react";
import { Outlet, useLocation, useNavigate, useNavigationType } from "react-router-dom";
import { PageStage } from "./system/PageStage";
import { portal, selectApp } from "./api";
import { useApps, useSelectedApp } from "./system/apps";
import { ThemeToggle, toggleTheme } from "./components/ui";
import { useOperator } from "./system/OperatorAccess";
import { GuideButton } from "./system/guide";
import { DemoButton, hasDemo } from "./system/demo";
import { guideFor } from "./system/guides";
import { FEATURES } from "./system/features";
import FeatureTabs from "./system/FeatureTabs";
import { useLiquidSurface } from "./system/primitives";
import { transitions } from "./system/physics";
import Advisor from "./components/Advisor";
import { Sidebar } from "./shell/Sidebar";
import { TopBar } from "./shell/TopBar";
import { Ambient } from "./shell/Ambient";
import { CommandPalette, PaletteTrigger, useCommandPalette } from "./shell/CommandPalette";

/**
 * Console shell.
 *
 * <p>A sidebar holds every section of the product, a bar above the page says
 * where you are and holds the tools for that page, and a palette (⌘K) reaches
 * anything from anywhere. All three read one map of the console (shell/nav.ts).
 *
 * <p>The shell is never part of a page change: when you navigate, the sidebar's
 * marker glides to the new entry and the breadcrumb rolls to the new place
 * while the page itself is replaced beneath them (see system/navigation.ts).
 */
const RAIL_KEY = "continuum.sidebar.rail";

function useMedia(query: string) {
  const [match, setMatch] = useState(() => typeof window !== "undefined" && window.matchMedia(query).matches);
  useEffect(() => {
    const mq = window.matchMedia(query);
    const on = () => setMatch(mq.matches);
    on();
    mq.addEventListener("change", on);
    return () => mq.removeEventListener("change", on);
  }, [query]);
  return match;
}

/**
 * Back and forward return to where you were on the page; anything else starts
 * at the top. Instant either way: a page that smooth-scrolls to its top after
 * arriving looks like it arrived twice.
 */
function useScrollMemory() {
  const location = useLocation();
  const navType = useNavigationType();
  const positions = useRef(new Map<string, number>());
  const at = useRef(location.key);
  useEffect(() => {
    const save = () => positions.current.set(at.current, window.scrollY);
    window.addEventListener("scroll", save, { passive: true });
    return () => window.removeEventListener("scroll", save);
  }, []);
  useLayoutEffect(() => {
    if (at.current === location.key) return;
    at.current = location.key;
    const y = navType === "POP" ? positions.current.get(location.key) ?? 0 : 0;
    window.scrollTo({ top: y, left: 0, behavior: "instant" as ScrollBehavior });
  }, [location.key, navType]);
}

export default function App() {
  // Every console page sits on the ambient light and its cards are glass.
  useLiquidSurface();
  useScrollMemory();
  const location = useLocation();
  const navigate = useNavigate();
  const { operator, request: requestOperator, drop: dropOperator } = useOperator();
  // Which app's data every page shows: one choice for the whole console.
  const { apps } = useApps();
  const app = useSelectedApp();
  const [me, setMe] = useState<{ name?: string; email?: string } | null>(null);
  useEffect(() => {
    portal.me().then((r) => setMe(r ?? null)).catch(() => setMe(null));
  }, []);

  const narrow = useMedia("(max-width: 1279px)");
  const [railChoice, setRailChoice] = useState<boolean | null>(() => {
    try {
      const v = localStorage.getItem(RAIL_KEY);
      return v === null ? null : v === "1";
    } catch {
      return null;
    }
  });
  const rail = railChoice ?? narrow;
  const toggleRail = () => {
    const next = !rail;
    setRailChoice(next);
    try {
      localStorage.setItem(RAIL_KEY, next ? "1" : "0");
    } catch {
      /* the choice lasts for this page only */
    }
  };

  const [drawer, setDrawer] = useState(false);
  const palette = useCommandPalette();

  useEffect(() => {
    setDrawer(false);
  }, [location.pathname]);
  useEffect(() => {
    if (!drawer) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && setDrawer(false);
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [drawer]);

  const signOut = useCallback(() => {
    portal.logout();
    navigate("/");
  }, [navigate]);
  const pickApp = useCallback((next: string) => {
    selectApp(next);
    setDrawer(false);
  }, []);
  const toggleOperator = useCallback(() => (operator ? dropOperator() : requestOperator()), [operator, dropOperator, requestOperator]);
  const actions = useMemo(
    () => ({ toggleTheme, toggleOperator, signOut, pickApp }),
    [toggleOperator, signOut, pickApp],
  );

  const guide = guideFor(location.pathname);

  // Each page names its tab, so ten open tabs are ten different labels and a
  // screen reader announces the page change.
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

  const sidebar = (inDrawer: boolean) => (
    <Sidebar
      rail={rail}
      onToggleRail={inDrawer ? undefined : toggleRail}
      drawer={inDrawer}
      operator={operator}
      apps={apps}
      app={app}
      me={me}
      onPickApp={pickApp}
      onToggleOperator={toggleOperator}
      onSignOut={signOut}
    />
  );

  const tools = (
    <>
      {/* The guide for whatever page you are on, always in the same place. */}
      {guide && <GuideButton guide={guide} guideKey={location.pathname} />}
      {hasDemo(location.pathname) && <DemoButton key={location.pathname} path={location.pathname} />}
      {/* Elevated state stays visible: while it is on, the switches in Routing
          change behaviour for every tenant. */}
      {operator && (
        <button
          type="button"
          onClick={dropOperator}
          data-tip="Operator access is on. Click to drop it."
          className="hidden h-8 items-center gap-1.5 rounded-full border border-amber-500/40 px-2.5 text-[10px] font-medium uppercase tracking-widest hover:border-amber-400/60 sm:inline-flex"
          style={{ background: "var(--wash-warn)", color: "var(--state-warning-ink)" }}
        >
          <span className="h-1.5 w-1.5 rounded-full bg-amber-400" />
          Operator
        </button>
      )}
      <ThemeToggle className="tool-button" />
    </>
  );

  return (
    <div className="shell min-h-full" style={{ "--side-w": rail ? "92px" : "272px" } as CSSProperties}>
      <Ambient />
      <a
        href="#main"
        className="sr-only focus:not-sr-only focus:fixed focus:left-3 focus:top-3 focus:z-[95] focus:rounded-full focus:bg-card focus:px-4 focus:py-2 focus:text-sm focus:font-medium focus:shadow-[var(--ring)]"
      >
        Skip to content
      </a>

      <aside className="sidebar hidden lg:flex" aria-label="Sidebar">
        {sidebar(false)}
      </aside>

      <AnimatePresence>
        {drawer && (
          <div className="fixed inset-0 z-[60] lg:hidden">
            <m.div
              className="drawer-scrim absolute inset-0"
              onClick={() => setDrawer(false)}
              initial={{ opacity: 0 }}
              animate={{ opacity: 1 }}
              exit={{ opacity: 0 }}
              transition={transitions.fade}
            />
            <m.aside
              role="dialog"
              aria-modal="true"
              aria-label="Navigation"
              className="drawer absolute bottom-2 left-2 top-2 flex w-[min(86vw,300px)] flex-col p-3"
              initial={{ x: "-108%" }}
              animate={{ x: 0 }}
              exit={{ x: "-108%" }}
              transition={transitions.modal}
              drag="x"
              dragConstraints={{ left: 0, right: 0 }}
              dragElastic={{ left: 0.7, right: 0.04 }}
              onDragEnd={(_, info) => {
                if (info.offset.x < -72 || info.velocity.x < -480) setDrawer(false);
              }}
            >
              {sidebar(true)}
            </m.aside>
          </div>
        )}
      </AnimatePresence>

      <div className="shell-body">
        <TopBar
          onMenu={() => setDrawer(true)}
          operator={operator}
          tools={tools}
          search={<PaletteTrigger onOpen={() => palette.setOpen(true)} compact={narrow} />}
        />
        <PageStage className="mx-auto w-full max-w-[1240px] px-4 pb-20 pt-2 sm:px-6">
          <FeatureTabs />
          {/* Keyed on the app, so switching app reloads the page's data. */}
          <div data-page>
            <Outlet key={app} />
          </div>
        </PageStage>
      </div>

      <CommandPalette
        open={palette.open}
        onClose={() => palette.setOpen(false)}
        operator={operator}
        apps={apps}
        app={app}
        actions={actions}
      />
      <Advisor />
    </div>
  );
}
