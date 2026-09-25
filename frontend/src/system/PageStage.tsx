import { useLayoutEffect, useRef, type ReactNode } from "react";
import { useLocation, useNavigationType } from "react-router-dom";
import { motion, prefersReducedMotion, useSpring } from "./physics";

/**
 * How one page becomes the next.
 *
 * <p>A navigation is a movement through a space, so a new page should come
 * from somewhere. Which somewhere depends on what was clicked:
 *
 * <ul>
 *   <li><b>A card or a menu item</b> — the page grows out of it. A sheet starts
 *       at the exact rectangle that was clicked and expands to fill the
 *       workspace, and the new content resolves out of it, scaling from the
 *       clicked point.</li>
 *   <li><b>Back, to a page holding the card you came from</b> — the reverse.
 *       The sheet starts as the whole workspace you were in and contracts onto
 *       the card that opened it, so you see where it went back to.</li>
 *   <li><b>A sibling tab</b> — sideways. The views of one feature sit side by
 *       side, and the content moves in the direction of the tab you chose.</li>
 *   <li><b>Anything else</b> (a typed URL, a redirect) — no origin to honour,
 *       so the page settles forward out of slight depth rather than inventing a
 *       direction it did not come from.</li>
 * </ul>
 *
 * <p>All four are one spring and one paint function; a navigation that
 * arrives mid-transition simply starts the next one from the current frame.
 */

type Origin = { rect: DOMRect; radius: string; lateral: number | null; at: number };
let pending: Origin | null = null;
let installed = false;

/** Records where a navigation was clicked from, before the router acts on it. */
function installOriginCapture() {
  if (installed) return;
  installed = true;
  document.addEventListener(
    "click",
    (e) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const a = (e.target as Element | null)?.closest?.<HTMLAnchorElement>("a[href]");
      if (!a || a.target === "_blank" || a.origin !== location.origin) return;
      if (a.pathname === location.pathname) return;
      const surface = a.closest<HTMLElement>("[data-morph]") ?? a;
      // A tab among siblings: which way is the chosen one from the current one?
      const strip = a.closest('nav[aria-label$=" views"]');
      let lateral: number | null = null;
      if (strip) {
        const current = strip.querySelector<HTMLElement>('[aria-current="page"]');
        lateral = current ? Math.sign(a.getBoundingClientRect().left - current.getBoundingClientRect().left) || 1 : 1;
      }
      pending = {
        rect: surface.getBoundingClientRect(),
        radius: getComputedStyle(surface).borderRadius || "8px",
        lateral,
        at: performance.now(),
      };
    },
    true
  );
}

type Mode = "expand" | "contract" | "lateral" | "settle";

const lerp = (a: number, b: number, t: number) => a + (b - a) * t;
const clamp01 = (v: number) => Math.max(0, Math.min(1, v));

export function PageStage({ className, children }: { className: string; children: ReactNode }) {
  const location = useLocation();
  const navType = useNavigationType();
  const main = useRef<HTMLElement>(null);
  const sheet = useRef<HTMLDivElement>(null);
  const prev = useRef<string | null>(null);
  const t = useSpring(1, { config: motion.page, kind: "fade", precision: 0.001 });
  const scene = useRef<{ mode: Mode; from: DOMRect | null; to: DOMRect | null; radius: string; dir: number }>({
    mode: "settle",
    from: null,
    to: null,
    radius: "8px",
    dir: 0,
  });

  useLayoutEffect(() => {
    installOriginCapture();
  }, []);

  useLayoutEffect(() => {
    const paint = () => {
      const s = scene.current;
      const p = t.value;
      const c = clamp01(p);
      const m = main.current;
      const sh = sheet.current;
      if (!m || !sh) return;
      const reduced = prefersReducedMotion();
      if (p === 1) {
        // Arrived (the spring lands exactly on its target). Leave no transform
        // behind: see onRest below for why even an identity one matters.
        sh.style.opacity = "0";
        m.style.transform = "";
        m.style.opacity = "";
        return;
      }

      if (reduced || s.mode === "settle") {
        sh.style.opacity = "0";
        m.style.opacity = String(clamp01(p * 1.4));
        m.style.transform = reduced ? "" : `scale(${(0.988 + 0.012 * p).toFixed(4)})`;
        m.style.transformOrigin = "50% 0";
        return;
      }
      if (s.mode === "lateral") {
        sh.style.opacity = "0";
        m.style.opacity = String(clamp01(p * 1.3));
        m.style.transform = `translate3d(${(s.dir * 28 * (1 - p)).toFixed(2)}px,0,0)`;
        return;
      }
      const a = s.from!;
      const b = s.to!;
      // In both directions the sheet travels from `from` to `to`; what differs
      // is whether the page is revealed under it or resolves on top of it.
      const x = lerp(a.left, b.left, p);
      const y = lerp(a.top, b.top, p);
      const w = lerp(a.width, b.width, p);
      const h = lerp(a.height, b.height, p);
      sh.style.transform = `translate3d(${x.toFixed(1)}px,${y.toFixed(1)}px,0)`;
      sh.style.width = `${Math.max(0, w).toFixed(1)}px`;
      sh.style.height = `${Math.max(0, h).toFixed(1)}px`;
      if (s.mode === "expand") {
        // The sheet is the card becoming the page: solid while it grows, then
        // dissolving as the content it became takes its place.
        sh.style.zIndex = "0";
        sh.style.borderRadius = c < 0.98 ? s.radius : "0px";
        sh.style.opacity = String(clamp01((1 - c) * 3.2));
        m.style.opacity = String(clamp01((c - 0.28) / 0.55));
        m.style.transform = `scale(${(0.965 + 0.035 * p).toFixed(4)})`;
      } else {
        // Back: the workspace you left shrinks onto the card that opened it,
        // uncovering the page you are returning to, and becomes that card.
        sh.style.zIndex = "35";
        sh.style.borderRadius = s.radius;
        sh.style.opacity = String(clamp01((1 - c) * 2.4));
        m.style.opacity = "1";
        m.style.transform = "";
      }
    };
    return t.subscribe(paint);
  }, [t]);

  useLayoutEffect(() => {
    const from = prev.current;
    prev.current = location.pathname;
    if (from === null) return; // the first page has nothing to arrive from
    const m = main.current;
    if (!m) return;

    const view = viewportRectOf(m);
    const origin = pending && performance.now() - pending.at < 1200 ? pending : null;
    pending = null;
    const s = scene.current;

    if (origin?.lateral != null) {
      Object.assign(s, { mode: "lateral", dir: origin.lateral });
    } else if (origin) {
      Object.assign(s, { mode: "expand", from: origin.rect, to: view, radius: origin.radius });
      // The content scales out of the point that was clicked.
      const r = m.getBoundingClientRect();
      m.style.transformOrigin = `${origin.rect.left + origin.rect.width / 2 - r.left}px ${Math.max(
        0,
        origin.rect.top + origin.rect.height / 2 - r.top
      )}px`;
    } else {
      const back = navType === "POP" ? returnTarget(m, from) : null;
      if (back) {
        Object.assign(s, {
          mode: "contract",
          from: view,
          to: back.getBoundingClientRect(),
          radius: getComputedStyle(back).borderRadius || "8px",
        });
      } else {
        Object.assign(s, { mode: "settle" });
      }
    }
    // Promoted to its own compositor layer for exactly as long as it moves.
    // Scaling the whole page from script is otherwise a full-page repaint on
    // every frame; left on permanently, the layer would cost memory and blur
    // text rendering on some GPUs.
    m.style.willChange = "transform, opacity";
    t.onRest = () => {
      const el = main.current;
      if (!el) return;
      // At rest the page must carry no transform at all. Even scale(1) makes
      // <main> the containing block for every position:fixed descendant, which
      // clipped side panels, scrims and overlays to the page column instead
      // of the viewport.
      el.style.willChange = "";
      el.style.transform = "";
      el.style.transformOrigin = "";
      el.style.opacity = "";
    };
    t.jump(0);
    t.set(1);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [location.pathname]);

  return (
    <>
      <div
        ref={sheet}
        aria-hidden
        className="pointer-events-none fixed left-0 top-0"
        style={{
          background: "rgb(var(--card))",
          boxShadow: "var(--shadow-float)",
          border: "1px solid rgb(var(--card-edge))",
          opacity: 0,
          willChange: "transform, width, height, opacity",
        }}
      />
      <main key={location.pathname} ref={main} className={`relative z-[1] ${className}`}>
        {children}
      </main>
    </>
  );
}

/** The part of the workspace actually on screen: below the bar, to the fold. */
function viewportRectOf(m: HTMLElement): DOMRect {
  const r = m.getBoundingClientRect();
  const header = document.querySelector("header");
  const top = Math.max(r.top, header ? header.getBoundingClientRect().bottom : 0);
  const bottom = Math.min(r.bottom, window.innerHeight);
  return new DOMRect(r.left, top, r.width, Math.max(0, bottom - top));
}

/**
 * The element on the restored page that led to where we were: the link to
 * the page just left, or the card that link sits in. Only if it is on screen —
 * contracting toward something scrolled out of view would point at nothing.
 */
function returnTarget(root: HTMLElement, leftPath: string): HTMLElement | null {
  const link = [...root.querySelectorAll<HTMLAnchorElement>("a[href]")].find((a) => a.pathname === leftPath);
  if (!link) return null;
  const el = link.closest<HTMLElement>("[data-morph]") ?? link;
  const r = el.getBoundingClientRect();
  return r.bottom > 0 && r.top < window.innerHeight && r.width > 0 ? el : null;
}
