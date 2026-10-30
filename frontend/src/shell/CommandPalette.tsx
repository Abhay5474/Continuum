import { AnimatePresence, m } from "motion/react";
import { useEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { useNavigate } from "react-router-dom";
import type { AppSummary } from "../api";
import { transitions } from "../system/physics";
import { GLYPHS, type GlyphName } from "../system/hub";
import { intendNavigation } from "../system/navigation";
import { ACCOUNT, HOME, OPERATOR_ACCOUNTS, SECTIONS } from "./nav";

/**
 * Go anywhere in one gesture.
 *
 * <p>⌘K (Ctrl+K) or the search field in the bar. The field itself becomes the
 * palette — one surface growing into a larger one, so the eye never loses the
 * thing it clicked — and closing folds it back into the field.
 *
 * <p>Every page and every view of every feature is here, with the actions the
 * sidebar's menus hold (theme, operator access, the app in view, signing out),
 * so nothing in the console is more than a few keystrokes away.
 */
type Entry = {
  id: string;
  group: "Pages" | "Actions" | "Apps";
  label: string;
  hint?: string;
  glyph: GlyphName;
  keywords: string;
  run: () => void;
};

export function useCommandPalette() {
  const [open, setOpen] = useState(false);
  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if ((e.metaKey || e.ctrlKey) && e.key.toLowerCase() === "k") {
        e.preventDefault();
        setOpen((o) => !o);
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);
  return { open, setOpen };
}

const isMac = typeof navigator !== "undefined" && /Mac|iPhone|iPad/.test(navigator.platform || navigator.userAgent);
export const PALETTE_KEY = isMac ? "⌘K" : "Ctrl K";

/** The search field in the bar: the palette, folded. */
export function PaletteTrigger({ onOpen, compact = false }: { onOpen: () => void; compact?: boolean }) {
  return (
    <m.button
      layoutId="palette"
      type="button"
      onClick={onOpen}
      aria-label={`Search or jump to a page (${PALETTE_KEY})`}
      aria-haspopup="dialog"
      data-no-press
      className={`palette-trigger flex items-center gap-2 text-left ${compact ? "w-9 justify-center px-0" : "w-[240px] px-3"}`}
      style={{ borderRadius: 12 }}
      transition={transitions.modal}
    >
      <m.span layout="position" className="flex min-w-0 flex-1 items-center gap-2">
        <SearchGlyph />
        {!compact && <span className="truncate text-[12.5px] text-slate-500">Search or jump to…</span>}
      </m.span>
      {!compact && (
        <m.kbd layout="position" className="kbd">{PALETTE_KEY}</m.kbd>
      )}
    </m.button>
  );
}

function SearchGlyph() {
  return (
    <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.6"
         strokeLinecap="round" aria-hidden className="shrink-0 text-slate-500">
      <circle cx="7" cy="7" r="4.6" />
      <path d="m10.5 10.5 3.2 3.2" />
    </svg>
  );
}

function Glyph({ name }: { name: GlyphName }) {
  return (
    <svg width="15" height="15" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4"
         strokeLinecap="round" strokeLinejoin="round" aria-hidden>
      {GLYPHS[name]}
    </svg>
  );
}

/** Every word of the query, somewhere in the entry; the label counts most. */
function score(e: Entry, words: string[]): number {
  if (!words.length) return 1;
  const label = e.label.toLowerCase();
  let total = 0;
  for (const w of words) {
    if (label.startsWith(w)) total += 4;
    else if (label.includes(` ${w}`)) total += 3;
    else if (label.includes(w)) total += 2;
    else if (e.keywords.includes(w)) total += 0.6;
    else return 0;
  }
  return total;
}

export function CommandPalette({
  open,
  onClose,
  operator,
  apps,
  app,
  actions,
}: {
  open: boolean;
  onClose: () => void;
  operator: boolean;
  apps: AppSummary[] | null;
  app: string;
  actions: { toggleTheme: () => void; toggleOperator: () => void; signOut: () => void; pickApp: (id: string) => void };
}) {
  const navigate = useNavigate();
  const [query, setQuery] = useState("");
  const [active, setActive] = useState(0);
  const input = useRef<HTMLInputElement>(null);
  const list = useRef<HTMLDivElement>(null);
  const returnTo = useRef<HTMLElement | null>(null);

  const entries = useMemo<Entry[]>(() => {
    const go = (to: string) => () => {
      intendNavigation("section");
      navigate(to);
    };
    const pages: Entry[] = [
      { id: HOME.to, group: "Pages", label: HOME.label, hint: "Overview", glyph: HOME.glyph, keywords: HOME.desc.toLowerCase(), run: go(HOME.to) },
    ];
    for (const s of SECTIONS) {
      for (const p of s.places) {
        pages.push({
          id: p.to, group: "Pages", label: p.label, hint: s.label, glyph: p.glyph,
          keywords: `${p.desc} ${s.label} ${(p.views ?? []).map((v) => v.label).join(" ")}`.toLowerCase(),
          run: go(p.to),
        });
        for (const v of (p.views ?? []).slice(1)) {
          pages.push({
            id: v.to, group: "Pages", label: v.label, hint: p.label, glyph: p.glyph,
            keywords: `${p.label} ${p.desc} ${s.label}`.toLowerCase(), run: go(v.to),
          });
        }
      }
    }
    for (const p of operator ? [...ACCOUNT, OPERATOR_ACCOUNTS] : ACCOUNT) {
      pages.push({ id: p.to, group: "Pages", label: p.label, hint: "Account", glyph: p.glyph, keywords: p.desc.toLowerCase(), run: go(p.to) });
    }
    pages.push({ id: "/docs", group: "Pages", label: "Documentation", hint: "Docs", glyph: "doc", keywords: "docs api reference help", run: () => navigate("/docs") });

    const acts: Entry[] = [
      { id: "theme", group: "Actions", label: "Switch theme", hint: "Light or dark", glyph: "eye", keywords: "theme dark light mode appearance", run: actions.toggleTheme },
      { id: "operator", group: "Actions", label: operator ? "Drop operator access" : "Operator access", hint: operator ? "Return to your own permissions" : "Unlock routing, hedging and the model catalogue", glyph: "lock", keywords: "operator admin elevate permissions", run: actions.toggleOperator },
      { id: "signout", group: "Actions", label: "Sign out", glyph: "user", keywords: "sign out log out logout leave", run: actions.signOut },
    ];
    const appEntries: Entry[] = [
      { id: "app:all", group: "Apps", label: "All apps", hint: app === "all" ? "In view" : "Everything this account has captured", glyph: "app", keywords: "app all account", run: () => actions.pickApp("all") },
      ...(apps ?? []).map((a) => ({
        id: `app:${a.id}`, group: "Apps" as const, label: a.name, hint: app === String(a.id) ? "In view" : "Show this app's data", glyph: "app" as GlyphName,
        keywords: `app ${a.name}`.toLowerCase(), run: () => actions.pickApp(String(a.id)),
      })),
      { id: "app:untagged", group: "Apps", label: "Console & earlier", hint: app === "untagged" ? "In view" : "Made in this console, or before apps were told apart", glyph: "app", keywords: "app console earlier untagged", run: () => actions.pickApp("untagged") },
    ];
    return [...pages, ...acts, ...appEntries];
  }, [operator, apps, app, actions, navigate]);

  const words = query.toLowerCase().split(/\s+/).filter(Boolean);
  const results = useMemo(() => {
    const scored = entries.map((e, i) => ({ e, s: score(e, words), i })).filter((x) => x.s > 0);
    if (words.length) scored.sort((a, b) => b.s - a.s || a.i - b.i);
    return scored.map((x) => x.e).slice(0, words.length ? 12 : 40);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entries, query]);

  useEffect(() => setActive(0), [query]);
  useEffect(() => {
    if (!open) return;
    returnTo.current = document.activeElement as HTMLElement | null;
    setQuery("");
    setActive(0);
    const id = requestAnimationFrame(() => input.current?.focus());
    return () => {
      cancelAnimationFrame(id);
      returnTo.current?.focus?.();
    };
  }, [open]);

  // Keep the active row in view as the arrows walk past the edge.
  useEffect(() => {
    list.current?.querySelector<HTMLElement>(`[data-index="${active}"]`)?.scrollIntoView({ block: "nearest" });
  }, [active]);

  const choose = (e: Entry | undefined) => {
    if (!e) return;
    onClose();
    // After the palette starts folding away, so the page change is not
    // captured with the palette still in front of it.
    requestAnimationFrame(() => e.run());
  };

  const onKey = (ev: React.KeyboardEvent) => {
    if (ev.key === "ArrowDown") {
      ev.preventDefault();
      setActive((a) => Math.min(results.length - 1, a + 1));
    } else if (ev.key === "ArrowUp") {
      ev.preventDefault();
      setActive((a) => Math.max(0, a - 1));
    } else if (ev.key === "Enter") {
      ev.preventDefault();
      choose(results[active]);
    } else if (ev.key === "Escape") {
      ev.preventDefault();
      onClose();
    } else if (ev.key === "Tab") {
      ev.preventDefault();
    }
  };

  let lastGroup = "";
  const rows: ReactNode[] = results.map((e, i) => {
    const head = e.group !== lastGroup ? e.group : null;
    lastGroup = e.group;
    const on = i === active;
    return (
      <div key={e.id}>
        {head && <div className="px-3 pb-1 pt-3 text-[10.5px] font-medium uppercase tracking-[0.08em] text-slate-500">{head}</div>}
        <div
          id={`palette-${i}`}
          role="option"
          aria-selected={on}
          data-index={i}
          onPointerMove={() => !on && setActive(i)}
          onClick={() => choose(e)}
          className="relative flex cursor-pointer items-center gap-3 rounded-[10px] px-3 py-2"
        >
          {on && <m.span layoutId="palette-active" className="palette-active" transition={transitions.fast} />}
          <span className={`relative grid h-7 w-7 shrink-0 place-items-center rounded-[8px] ${on ? "palette-glyph-on" : "palette-glyph"}`}>
            <Glyph name={e.glyph} />
          </span>
          <span className="relative min-w-0 flex-1">
            <span className="block truncate text-[13px] font-medium text-slate-100">{e.label}</span>
          </span>
          {e.hint && <span className="relative hidden max-w-[45%] truncate text-[11.5px] text-slate-500 sm:block">{e.hint}</span>}
          {on && (
            <m.span layoutId="palette-enter" className="relative text-[11px] text-slate-500" transition={transitions.fast} aria-hidden>
              ↵
            </m.span>
          )}
        </div>
      </div>
    );
  });

  return (
    <AnimatePresence>
      {open && (
        <div className="fixed inset-0 z-[90]" role="presentation">
          <m.div
            className="palette-scrim absolute inset-0"
            onClick={onClose}
            initial={{ opacity: 0 }}
            animate={{ opacity: 1 }}
            exit={{ opacity: 0 }}
            transition={transitions.fade}
          />
          <div className="pointer-events-none absolute inset-x-0 top-[12vh] flex justify-center px-4">
            <m.div
              layoutId="palette"
              role="dialog"
              aria-modal="true"
              aria-label="Search or jump to"
              className="palette pointer-events-auto w-full max-w-[620px] overflow-hidden"
              style={{ borderRadius: 16 }}
              transition={transitions.modal}
            >
              <m.div
                initial={{ opacity: 0 }}
                animate={{ opacity: 1, transition: { delay: 0.07, duration: 0.18 } }}
                exit={{ opacity: 0, transition: { duration: 0.08 } }}
              >
                <div className="flex items-center gap-3 border-b px-4" style={{ borderColor: "rgb(var(--card-rule))" }}>
                  <SearchGlyph />
                  <input
                    ref={input}
                    value={query}
                    onChange={(e) => setQuery(e.target.value)}
                    onKeyDown={onKey}
                    role="combobox"
                    aria-expanded="true"
                    aria-controls="palette-list"
                    aria-activedescendant={results.length ? `palette-${active}` : undefined}
                    aria-label="Search pages and actions"
                    placeholder="Jump to a page, an action or an app…"
                    className="h-[52px] min-w-0 flex-1 bg-transparent text-[15px] text-slate-100 outline-none placeholder:text-slate-500"
                    spellCheck={false}
                    autoComplete="off"
                  />
                  <kbd className="kbd">esc</kbd>
                </div>
                <div ref={list} id="palette-list" role="listbox" aria-label="Results" className="max-h-[min(56vh,460px)] overflow-y-auto p-1.5">
                  {rows.length ? rows : (
                    <div className="px-4 py-10 text-center text-[13px] text-slate-500">Nothing matches “{query}”.</div>
                  )}
                </div>
                <div className="flex items-center gap-4 border-t px-4 py-2 text-[11px] text-slate-500" style={{ borderColor: "rgb(var(--card-rule))" }}>
                  <span><kbd className="kbd">↑</kbd> <kbd className="kbd">↓</kbd> to move</span>
                  <span><kbd className="kbd">↵</kbd> to open</span>
                  <span className="ml-auto">{results.length} {results.length === 1 ? "result" : "results"}</span>
                </div>
              </m.div>
            </m.div>
          </div>
        </div>
      )}
    </AnimatePresence>
  );
}
