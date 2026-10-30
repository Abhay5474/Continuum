import { AnimatePresence, m, useScroll, useTransform } from "motion/react";
import type { ReactNode } from "react";
import { Link, useLocation } from "react-router-dom";
import { NavProgress } from "../system/NavProgress";
import { transitions } from "../system/physics";
import { locate } from "./nav";

/**
 * The bar above the page: where you are, and the tools for this page.
 *
 * <p>Clear glass over the top of the page that frosts as content scrolls under
 * it — the frosting is tied to the scroll position itself, not switched on at a
 * threshold, so it thickens exactly as fast as there is something under it to
 * blur. The breadcrumb's words roll to the new place on every navigation
 * rather than being replaced, so the change of place is read, not missed.
 */
export function TopBar({
  onMenu,
  operator,
  tools,
  search,
}: {
  /** Opens the drawer, on a phone. */
  onMenu: () => void;
  operator: boolean;
  /** The page's own tools: the guide, the demo. */
  tools: ReactNode;
  /** The palette's folded form. */
  search: ReactNode;
}) {
  const { pathname } = useLocation();
  const where = locate(pathname, operator);
  const { scrollY } = useScroll();
  const frost = useTransform(scrollY, [0, 36], [0, 1]);

  // A detail page under a place (a run, a workflow): its id, shortened.
  const placePath = where ? (where.place.views ?? [{ to: where.place.to }]).map((v) => v.to).find((p) => pathname.startsWith(p + "/")) : undefined;
  const detail = placePath ? decodeURIComponent(pathname.slice(placePath.length + 1)).replace(/^dag-/, "").slice(0, 8) : null;

  const crumbs: { text: string; to?: string; strong?: boolean; mono?: boolean }[] = [];
  if (where) {
    crumbs.push({ text: where.section });
    crumbs.push({ text: where.place.label, to: detail || where.view ? where.place.to : undefined, strong: !detail && !where.view });
    if (where.view && where.view !== where.place.label) crumbs.push({ text: where.view, strong: !detail });
    if (detail) crumbs.push({ text: detail, strong: true, mono: true });
  }

  return (
    <header className="topbar sticky top-0 z-30">
      <m.div aria-hidden className="topbar-frost pointer-events-none absolute inset-0" style={{ opacity: frost }} />
      <div className="relative mx-auto flex h-[60px] max-w-[1240px] items-center gap-3 px-4 sm:px-6">
        <div className="-ml-1 lg:hidden">
          <button type="button" onClick={onMenu} aria-label="Open navigation" className="side-tool">
            <svg width="18" height="18" viewBox="0 0 18 18" fill="none" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" aria-hidden>
              <path d="M3 5.5h12M3 9h12M3 12.5h8" />
            </svg>
          </button>
        </div>

        <nav aria-label="Breadcrumb" className="flex min-w-0 items-center gap-1.5 text-[13px]">
          {crumbs.map((c, i) => (
            <span key={i} className={`flex min-w-0 items-center gap-1.5 ${i < crumbs.length - 2 ? "hidden sm:flex" : ""}`}>
              {i > 0 && <span aria-hidden className="select-none text-slate-500/70">/</span>}
              <span className="relative inline-grid min-w-0 overflow-hidden">
                <AnimatePresence mode="popLayout" initial={false}>
                  <m.span
                    key={c.text}
                    initial={{ y: "70%", opacity: 0, filter: "blur(3px)" }}
                    animate={{ y: "0%", opacity: 1, filter: "blur(0px)" }}
                    exit={{ y: "-70%", opacity: 0, filter: "blur(3px)" }}
                    transition={transitions.standard}
                    className="truncate"
                  >
                    {c.to ? (
                      <Link to={c.to} data-nav="back" className="crumb-link rounded-md text-slate-400 hover:text-slate-100">
                        {c.text}
                      </Link>
                    ) : (
                      <span
                        aria-current={c.strong ? "page" : undefined}
                        className={`${c.strong ? "font-medium text-slate-100" : "text-slate-500"} ${c.mono ? "font-mono text-[12px]" : ""}`}
                      >
                        {c.text}
                      </span>
                    )}
                  </m.span>
                </AnimatePresence>
              </span>
            </span>
          ))}
        </nav>

        <div className="ml-auto flex shrink-0 items-center gap-1.5">
          {search}
          {tools}
        </div>
      </div>
      <NavProgress />
    </header>
  );
}
