import { AnimatePresence, LayoutGroup, m } from "motion/react";
import { useRef, useState, type ReactNode } from "react";
import { Link, useLocation } from "react-router-dom";
import type { AppSummary } from "../api";
import { appLabel } from "../system/apps";
import { BrandMark } from "../system/brand";
import { GLYPHS, type GlyphName } from "../system/hub";
import { useHoverHighlight } from "../system/interaction/HoverHighlight";
import { Popover } from "../system/interaction/Popover";
import { transitions } from "../system/physics";
import { ACCOUNT, HOME, OPERATOR_ACCOUNTS, SECTIONS, isAt, type Place } from "./nav";

/**
 * The console's spine.
 *
 * <p>Every section of the product, always in view, in the order of a request's
 * life: the traffic that arrives, the prompt it carries, the checks on the
 * answer, the policy that learns from all of it. The page you are on is marked
 * by a pane of glass that glides to whichever entry you choose, so a
 * navigation is seen as a movement from one place to another; a lighter pane
 * follows the pointer down the list.
 *
 * <p>Folds to a rail of icons (each named on hover) when the screen is narrow
 * or the reader wants the room; on a phone it is a drawer.
 */
const CLOSED_KEY = "continuum.sidebar.closed";

function readClosed(): string[] {
  try {
    return JSON.parse(localStorage.getItem(CLOSED_KEY) ?? "[]");
  } catch {
    return [];
  }
}

export function Glyph({ name, size = 16 }: { name: GlyphName; size?: number }) {
  return (
    <svg width={size} height={size} viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.45"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      {GLYPHS[name]}
    </svg>
  );
}

export function Chevron({ open, className = "" }: { open: boolean; className?: string }) {
  return (
    <svg viewBox="0 0 12 12" className={`spin-to h-2.5 w-2.5 shrink-0 ${open ? "rotate-180" : ""} ${className}`}
         fill="none" stroke="currentColor" strokeWidth="1.8" aria-hidden>
      <path d="M2.5 4.5 6 8l3.5-3.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

export function Sidebar({
  rail,
  onToggleRail,
  drawer = false,
  operator,
  apps,
  app,
  me,
  onPickApp,
  onToggleOperator,
  onSignOut,
}: {
  rail: boolean;
  onToggleRail?: () => void;
  /** Shown as the phone's drawer: always full width, no rail toggle. */
  drawer?: boolean;
  operator: boolean;
  apps: AppSummary[] | null;
  app: string;
  me: { name?: string; email?: string } | null;
  onPickApp: (app: string) => void;
  onToggleOperator: () => void;
  onSignOut: () => void;
}) {
  const { pathname } = useLocation();
  const list = useRef<HTMLElement>(null);
  const { bind, highlight } = useHoverHighlight(list);
  const [closed, setClosed] = useState<string[]>(readClosed);
  const toggleSection = (label: string) =>
    setClosed((c) => {
      const next = c.includes(label) ? c.filter((x) => x !== label) : [...c, label];
      try {
        localStorage.setItem(CLOSED_KEY, JSON.stringify(next));
      } catch {
        /* the choice lasts for this page only */
      }
      return next;
    });
  const compact = rail && !drawer;

  return (
    <div className="flex h-full flex-col" data-rail={compact ? "" : undefined}>
      <div className={`flex items-center gap-2.5 pb-3 pt-1 ${compact ? "justify-center px-0" : "px-2"}`}>
        <Link to="/dashboard" data-nav="section" className="flex min-w-0 items-center gap-2.5 rounded-[10px]" aria-label="Continuum, Command Centre">
          <BrandMark size={28} />
          {!compact && <span className="truncate text-[15px] font-semibold tracking-[-0.02em] text-slate-100">Continuum</span>}
        </Link>
        {!drawer && !compact && onToggleRail && (
          <button
            type="button"
            onClick={onToggleRail}
            aria-label="Collapse the sidebar"
            data-tip="Collapse"
            className="side-tool ml-auto"
          >
            <RailGlyph />
          </button>
        )}
      </div>

      <AppSwitcher compact={compact} apps={apps} app={app} onPick={onPickApp} />

      <LayoutGroup id={drawer ? "drawer" : "side"}>
        <nav
          ref={list}
          aria-label="Console"
          data-nav="section"
          className="side-scroll relative -mx-1 mt-3 min-h-0 flex-1 overflow-y-auto overflow-x-hidden px-1 pb-2"
          {...bind}
        >
          {highlight}
          <SideLink place={HOME} active={isAt(HOME, pathname)} compact={compact} />
          {SECTIONS.map((s) => {
            const open = compact || !closed.includes(s.label) || s.places.some((p) => isAt(p, pathname));
            return (
              <div key={s.label} className="mt-3">
                {compact ? (
                  <div className="mx-auto my-2 h-px w-6" style={{ background: "rgb(var(--card-rule))" }} aria-hidden />
                ) : (
                  <button
                    type="button"
                    onClick={() => toggleSection(s.label)}
                    aria-expanded={open}
                    className="side-section"
                    data-no-press
                  >
                    {s.label}
                    <Chevron open={open} className="ml-auto opacity-60" />
                  </button>
                )}
                <AnimatePresence initial={false}>
                  {open && (
                    <m.div
                      key="items"
                      initial={{ height: 0, opacity: 0 }}
                      animate={{ height: "auto", opacity: 1 }}
                      exit={{ height: 0, opacity: 0 }}
                      transition={transitions.standard}
                      style={{ overflow: "hidden" }}
                    >
                      {s.places.map((p) => (
                        <SideLink key={p.to} place={p} active={isAt(p, pathname)} compact={compact} />
                      ))}
                    </m.div>
                  )}
                </AnimatePresence>
              </div>
            );
          })}
        </nav>
      </LayoutGroup>

      <div className="pt-2" style={{ borderTop: "1px solid rgb(var(--card-rule))" }}>
        {compact && onToggleRail && (
          <button type="button" onClick={onToggleRail} aria-label="Expand the sidebar" data-tip="Expand" className="side-tool mx-auto mb-1.5 flex">
            <RailGlyph flip />
          </button>
        )}
        <AccountMenu
          compact={compact}
          operator={operator}
          me={me}
          onToggleOperator={onToggleOperator}
          onSignOut={onSignOut}
        />
      </div>
    </div>
  );
}

function SideLink({ place, active, compact }: { place: Place; active: boolean; compact: boolean }) {
  return (
    <Link
      to={place.to}
      data-hl
      aria-current={active ? "page" : undefined}
      aria-label={compact ? place.label : undefined}
      data-tip={compact ? place.label : undefined}
      className={`side-link ${active ? "is-active" : ""} ${compact ? "justify-center px-0" : ""}`}
    >
      {active && <m.span layoutId="side-active" className="side-active" transition={transitions.standard} />}
      <span className="side-icon relative">
        <Glyph name={place.glyph} />
      </span>
      {!compact && <span className="relative min-w-0 flex-1 truncate">{place.label}</span>}
      {!compact && place.labs && (
        <span className="side-labs relative" title="Research feature: complete and tested, but more likely to change">Labs</span>
      )}
    </Link>
  );
}

function RailGlyph({ flip = false }: { flip?: boolean }) {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden style={flip ? { transform: "scaleX(-1)" } : undefined}>
      <rect x="2" y="2.5" width="12" height="11" rx="2.5" />
      <path d="M6 2.5v11" />
      <path d="m10.5 6.5-1.5 1.5 1.5 1.5" />
    </svg>
  );
}

function AppSwitcher({
  compact,
  apps,
  app,
  onPick,
}: {
  compact: boolean;
  apps: AppSummary[] | null;
  app: string;
  onPick: (app: string) => void;
}) {
  const [open, setOpen] = useState(false);
  const [keyboard, setKeyboard] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const label = appLabel(app, apps);
  const dot = (
    <span aria-hidden className="h-2 w-2 shrink-0 rounded-full"
          style={{ background: app === "all" ? "rgb(148 163 184 / 0.75)" : "var(--accent)" }} />
  );
  const pick = (v: string) => {
    onPick(v);
    setOpen(false);
  };
  const option = (value: string, name: string, desc: string) => (
    <button
      key={value}
      type="button"
      role="menuitemradio"
      aria-checked={app === value}
      onClick={() => pick(value)}
      className="menu-row flex w-full items-start gap-2.5 text-left"
    >
      <span aria-hidden className="mt-[6px] h-2 w-2 shrink-0 rounded-full border"
            style={app === value ? { background: "var(--accent)", borderColor: "var(--accent)" } : { borderColor: "rgb(148 163 184 / 0.55)" }} />
      <span className="min-w-0">
        <span className="block truncate text-[13px] font-medium text-slate-100">{name}</span>
        <span className="block text-[11.5px] leading-relaxed text-slate-500">{desc}</span>
      </span>
    </button>
  );
  return (
    <>
      <button
        ref={anchor}
        type="button"
        onClick={() => setOpen((o) => !o)}
        onKeyDown={(e) => {
          if (e.key === "ArrowDown" || e.key === "Enter" || e.key === " ") setKeyboard(true);
          if (e.key === "ArrowDown") {
            e.preventDefault();
            setOpen(true);
          }
        }}
        onPointerDown={() => setKeyboard(false)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label={`App in view: ${label}. Change app`}
        data-tip={compact ? `App in view: ${label}` : undefined}
        className={`side-app ${compact ? "mx-auto h-9 w-9 justify-center px-0" : ""}`}
      >
        {dot}
        {!compact && (
          <>
            <span className="min-w-0 flex-1 text-left">
              <span className="block text-[10px] font-medium uppercase tracking-[0.08em] text-slate-500">App in view</span>
              <span className="block truncate text-[13px] font-medium text-slate-100">{label}</span>
            </span>
            <Chevron open={open} className="text-slate-500" />
          </>
        )}
      </button>
      <Popover
        open={open}
        onClose={() => setOpen(false)}
        anchor={anchor}
        placement={compact ? "right-start" : "bottom-start"}
        width={272}
        label="App in view"
        focusOnOpen={keyboard}
      >
        {option("all", "All apps", "Everything this account has captured")}
        {(apps ?? []).map((a) =>
          option(
            String(a.id),
            a.name,
            `${a.activeKeys === 1 ? "1 active key" : `${a.activeKeys} active keys`}${a.lastUsedAt ? ` · used ${since(a.lastUsedAt)}` : " · not used yet"}`,
          ),
        )}
        {option("untagged", "Console & earlier", "Made in this console, or before apps were told apart")}
        <div className="menu-rule" />
        <Link to="/portal#keys" role="menuitem" onClick={() => setOpen(false)} className="menu-row block text-[12.5px] text-slate-400">
          Name apps or add one…
        </Link>
      </Popover>
    </>
  );
}

function AccountMenu({
  compact,
  operator,
  me,
  onToggleOperator,
  onSignOut,
}: {
  compact: boolean;
  operator: boolean;
  me: { name?: string; email?: string } | null;
  onToggleOperator: () => void;
  onSignOut: () => void;
}) {
  const [open, setOpen] = useState(false);
  const [keyboard, setKeyboard] = useState(false);
  const anchor = useRef<HTMLButtonElement>(null);
  const name = me?.name || me?.email?.split("@")[0] || "Account";
  const initials = name
    .split(/[\s._-]+/)
    .filter(Boolean)
    .slice(0, 2)
    .map((w) => w[0]?.toUpperCase())
    .join("") || "·";
  const close = () => setOpen(false);
  const item = (to: string, label: string, desc: string, glyph: GlyphName): ReactNode => (
    <Link key={to} to={to} role="menuitem" data-nav="section" onClick={close} className="menu-row flex items-start gap-2.5">
      <span className="mt-[1px] text-slate-500"><Glyph name={glyph} size={15} /></span>
      <span className="min-w-0">
        <span className="block text-[13px] font-medium text-slate-100">{label}</span>
        <span className="block text-[11.5px] leading-relaxed text-slate-500">{desc}</span>
      </span>
    </Link>
  );
  return (
    <>
      <button
        ref={anchor}
        type="button"
        onClick={() => setOpen((o) => !o)}
        onKeyDown={(e) => {
          if (e.key === "ArrowUp" || e.key === "Enter" || e.key === " ") setKeyboard(true);
          if (e.key === "ArrowUp") {
            e.preventDefault();
            setOpen(true);
          }
        }}
        onPointerDown={() => setKeyboard(false)}
        aria-haspopup="menu"
        aria-expanded={open}
        aria-label="Account menu"
        data-tip={compact ? "Account" : undefined}
        className={`side-account ${compact ? "mx-auto justify-center px-0" : ""}`}
      >
        <span className="avatar" aria-hidden>{initials}</span>
        {!compact && (
          <>
            <span className="min-w-0 flex-1 text-left">
              <span className="block truncate text-[13px] font-medium text-slate-100">{name}</span>
              <span className="block truncate text-[11px] text-slate-500">{operator ? "Operator access on" : me?.email ?? "Signed in"}</span>
            </span>
            <Chevron open={!open} className="text-slate-500" />
          </>
        )}
      </button>
      <Popover
        open={open}
        onClose={close}
        anchor={anchor}
        placement={compact ? "right-end" : "top-start"}
        width={compact ? 272 : 252}
        label="Account"
        focusOnOpen={keyboard}
      >
        {(operator ? [...ACCOUNT, OPERATOR_ACCOUNTS] : ACCOUNT).map((p) => item(p.to, p.label, p.desc, p.glyph))}
        {item("/docs", "Documentation", "Guides and the API reference", "doc")}
        <div className="menu-rule" />
        <button type="button" role="menuitem" onClick={() => { close(); onToggleOperator(); }} className="menu-row block w-full text-left">
          <span className="block text-[13px] font-medium text-slate-100">{operator ? "Drop operator access" : "Operator access"}</span>
          <span className="block text-[11.5px] text-slate-500">
            {operator ? "Return to your own permissions" : "Unlock routing, hedging and the model catalogue"}
          </span>
        </button>
        <button type="button" role="menuitem" onClick={() => { close(); onSignOut(); }} className="menu-row block w-full text-left text-[13px] text-slate-400">
          Sign out
        </button>
      </Popover>
    </>
  );
}

/** "3h ago", for the app picker. */
function since(iso: string): string {
  const s = Math.max(0, (Date.now() - new Date(iso).getTime()) / 1000);
  if (s < 90) return "just now";
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  if (s < 86400) return `${Math.round(s / 3600)}h ago`;
  return `${Math.round(s / 86400)}d ago`;
}
