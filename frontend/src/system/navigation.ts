import type { createBrowserRouter } from "react-router-dom";
import { prefersReducedMotion } from "./physics";

/**
 * Navigation as one continuous space.
 *
 * <p>Every in-app navigation runs inside a view transition. The shell (the
 * sidebar, the bar) is never captured: it stays live, so its indicator glides
 * while the page changes under it. The page itself leaves and arrives according
 * to how it was reached:
 *
 * <ul>
 *   <li><b>section</b> — a sidebar or palette entry: the old page lifts away,
 *       the new one rises in.</li>
 *   <li><b>lateral</b> — a sibling tab: the page slides toward the tab chosen.</li>
 *   <li><b>morph</b> — a card or row that opens what it summarises: the card
 *       becomes the page's header ({@code [data-morph]} → {@code
 *       [data-morph-target]}), its title flying to the new title
 *       ({@code [data-morph-title]} in both).</li>
 *   <li><b>back</b> — history back: the reverse. The header shrinks back onto
 *       the card that opened it, if that card is on screen.</li>
 * </ul>
 *
 * <p>Nothing here is needed for a page to work. Without view transitions (or
 * with reduced motion) navigation is instant, and the entrance below still
 * runs where motion is allowed.
 */

type AppRouter = ReturnType<typeof createBrowserRouter>;
export type NavKind = "forward" | "back" | "lateral" | "section" | "morph";

type Intent = { kind: NavKind; dir: number; source: HTMLElement | null; at: number };

/** How long a click stays the explanation for the transition that follows it. */
const FRESH_MS = 1500;

let intent: Intent | null = null;
let current: ViewTransition | null = null;
let installed = false;

const supported = () => typeof document !== "undefined" && typeof document.startViewTransition === "function";

function classify(a: HTMLAnchorElement): Omit<Intent, "at"> {
  if (a.closest('[data-nav="section"]')) return { kind: "section", dir: 0, source: null };
  const strip = a.closest('nav[aria-label$=" views"]');
  if (strip) {
    const at = strip.querySelector<HTMLElement>('[aria-current="page"]');
    const dir = at ? Math.sign(a.getBoundingClientRect().left - at.getBoundingClientRect().left) || 1 : 1;
    return { kind: "lateral", dir, source: null };
  }
  if (a.closest('[data-nav="back"]')) return { kind: "back", dir: 0, source: null };
  const source = a.closest<HTMLElement>("[data-morph]");
  if (source) return { kind: "morph", dir: 0, source };
  return { kind: "forward", dir: 0, source: null };
}

/**
 * What a card grows into on the page it opens: the page's own morph target if
 * it names one, otherwise its header — every page has a title, and the title's
 * block is where the eye goes on arrival.
 */
function headerOf(): HTMLElement | null {
  return (
    document.querySelector<HTMLElement>("#main [data-morph-target]") ??
    document.querySelector<HTMLElement>("#main [data-page] header") ??
    document.querySelector<HTMLElement>("#main [data-page] h1:not(.sr-only)")?.parentElement ??
    null
  );
}

function onScreen(el: Element) {
  const r = el.getBoundingClientRect();
  return r.width > 0 && r.bottom > 0 && r.top < window.innerHeight;
}

/**
 * Routes every navigation through a view transition and records what each
 * one was, so the stylesheet can move the page the right way.
 */
export function installViewTransitions(router: AppRouter) {
  if (installed || !supported()) return;
  installed = true;

  const navigate = router.navigate.bind(router);
  router.navigate = ((to: Parameters<AppRouter["navigate"]>[0], opts?: Parameters<AppRouter["navigate"]>[1]) => {
    if (typeof to === "number") return navigate(to);
    return navigate(to, { ...opts, viewTransition: opts?.viewTransition ?? !prefersReducedMotion() });
  }) as AppRouter["navigate"];

  document.addEventListener(
    "click",
    (e) => {
      if (e.defaultPrevented || e.button !== 0 || e.metaKey || e.ctrlKey || e.shiftKey || e.altKey) return;
      const a = (e.target as Element | null)?.closest?.<HTMLAnchorElement>("a[href]");
      if (!a || a.target === "_blank" || a.origin !== location.origin || a.pathname === location.pathname) return;
      intent = { ...classify(a), at: performance.now() };
    },
    true,
  );
  window.addEventListener("popstate", () => {
    intent = { kind: "back", dir: 0, source: null, at: performance.now() };
  });

  const start = document.startViewTransition.bind(document);
  document.startViewTransition = ((arg?: ViewTransitionUpdateCallback | StartViewTransitionOptions) => {
    const update = typeof arg === "function" ? arg : arg?.update;
    const fresh = intent && performance.now() - intent.at < FRESH_MS ? intent : null;
    intent = null;
    const kind: NavKind = fresh?.kind ?? "forward";
    const html = document.documentElement;
    const leftPath = document.getElementById("main")?.dataset.path ?? "";
    const named: HTMLElement[] = [];
    const name = (el: Element | null | undefined, n: string) => {
      if (el instanceof HTMLElement) {
        el.style.viewTransitionName = n;
        named.push(el);
      }
    };
    const pair = (el: HTMLElement) => {
      name(el, "morph");
      name(el.querySelector("[data-morph-title]"), "morph-title");
    };

    // The old frame: what the eye should follow out of the page being left.
    if (kind === "morph" && fresh?.source?.isConnected && onScreen(fresh.source)) {
      pair(fresh.source);
    } else if (kind === "back") {
      const header = headerOf();
      if (header && onScreen(header)) pair(header);
    }
    const morphing = named.length > 0;
    html.dataset.vt = kind;
    html.style.setProperty("--vt-dir", String(fresh?.dir || 1));
    if (morphing) html.dataset.vtMorph = "";
    else delete html.dataset.vtMorph;

    const run = async () => {
      await update?.();
      if (!morphing) return;
      // The new frame: where it lands.
      if (kind === "morph") {
        const header = headerOf();
        if (header) pair(header);
      } else {
        const link = [...document.querySelectorAll<HTMLAnchorElement>("#main a[href]")].find((a) => a.pathname === leftPath);
        const card = link?.closest<HTMLElement>("[data-morph]");
        if (card && onScreen(card)) pair(card);
      }
    };

    const t = start(typeof arg === "function" || arg == null ? run : { ...arg, update: run });
    current = t;
    t.finished.finally(() => {
      for (const el of named) el.style.viewTransitionName = "";
      if (current === t) {
        current = null;
        delete html.dataset.vt;
        delete html.dataset.vtMorph;
      }
    });
    return t;
  }) as typeof document.startViewTransition;
}

/** Explains the next navigation when it does not start from a link (the palette). */
export function intendNavigation(kind: NavKind, dir = 0) {
  intent = { kind, dir, source: null, at: performance.now() };
}

/** Whether a transition is drawing the page right now. */
export const transitioning = () => current !== null;

/* ------------------------------------------------------------------ *
 * Entrance
 * ------------------------------------------------------------------ */

const EASE = "cubic-bezier(0.22, 1, 0.36, 1)";
const ease = (token: string) =>
  getComputedStyle(document.documentElement).getPropertyValue(token).trim() || EASE;

/**
 * The blocks a page is made of, in reading order: the page's direct sections,
 * and the cards of any grid among them, so a row of cards arrives card by card.
 */
function blocksOf(page: Element): HTMLElement[] {
  const out: HTMLElement[] = [];
  const shown = (el: Element): el is HTMLElement =>
    el instanceof HTMLElement && el.offsetParent !== null && el.getBoundingClientRect().height > 0;
  let node: Element = page;
  for (let depth = 0; depth < 3; depth++) {
    const kids = [...node.children].filter(shown);
    if (kids.length !== 1) break;
    node = kids[0];
  }
  for (const k of [...node.children].filter(shown)) {
    const grid = getComputedStyle(k).display === "grid";
    const cells = [...k.children].filter(shown);
    if (grid && cells.length > 1 && cells.length <= 12) out.push(...cells);
    else out.push(k);
  }
  return out;
}

/**
 * Brings a page in: its blocks rise into place in reading order, a few tens
 * of milliseconds apart, and the ones below the fold rise as they are scrolled
 * to. The page's title is revealed through a mask, the way a line of type is
 * set rather than switched on.
 *
 * <p>Runs on the live page, so inside a view transition it plays through the
 * new frame. Returns a function that stops everything it started.
 */
export function enterPage(main: HTMLElement): () => void {
  if (prefersReducedMotion() || typeof main.animate !== "function") return () => {};
  const running: Animation[] = [];
  const page = main.querySelector<HTMLElement>("[data-page]") ?? main;
  const tabs = main.querySelector<HTMLElement>('nav[aria-label$=" views"]');
  const target = main.querySelector("[data-morph-target]") ?? main.querySelector("[data-page] header");
  const morphing = document.documentElement.dataset.vtMorph !== undefined;
  const blocks = [...(tabs ? [tabs] : []), ...blocksOf(page)]
    // The header a card is morphing into is already on its way.
    .filter((b) => !(morphing && target && (b === target || b.contains(target))));

  const rise = ease("--ease-slow");
  const fold = window.innerHeight;
  let i = 0;
  const later: HTMLElement[] = [];
  for (const b of blocks) {
    const top = b.getBoundingClientRect().top;
    if (top > fold - 24) {
      later.push(b);
      continue;
    }
    running.push(
      b.animate(
        [
          { opacity: 0, transform: "translate3d(0, 16px, 0)" },
          { opacity: 1, transform: "none" },
        ],
        { duration: 640, delay: 30 + Math.min(i, 9) * 38, easing: rise, fill: "backwards" },
      ),
    );
    i++;
  }

  const title = page.querySelector<HTMLElement>("h1");
  if (title && !(morphing && target?.contains(title))) {
    running.push(
      title.animate(
        [
          { clipPath: "inset(100% -10% -25% -10%)", transform: "translate3d(0, 0.5em, 0)" },
          { clipPath: "inset(-25% -10% -25% -10%)", transform: "none" },
        ],
        { duration: 720, delay: 40, easing: rise, fill: "backwards" },
      ),
    );
  }

  // Below the fold: each block rises once, as it comes into view.
  let io: IntersectionObserver | null = null;
  if (later.length && "IntersectionObserver" in window) {
    io = new IntersectionObserver(
      (entries) => {
        for (const e of entries) {
          if (!e.isIntersecting) continue;
          io?.unobserve(e.target);
          running.push(
            (e.target as HTMLElement).animate(
              [
                { opacity: 0, transform: "translate3d(0, 12px, 0)" },
                { opacity: 1, transform: "none" },
              ],
              { duration: 520, easing: rise, fill: "backwards" },
            ),
          );
        }
      },
      // Starts a little before the block shows, so its first frame is never
      // a visible block blinking out.
      { rootMargin: "0px 0px 16px 0px" },
    );
    for (const b of later) io.observe(b);
  }

  return () => {
    io?.disconnect();
    for (const a of running) a.cancel();
  };
}
