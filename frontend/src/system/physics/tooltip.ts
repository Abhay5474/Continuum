import { prefersReducedMotion, Spring } from "./spring";
import { blur, motion } from "./tokens";

/**
 * One tooltip for the whole document.
 *
 * <p>Any element with {@code data-tip} gets it — no wrapper component, no
 * per-element listeners, no React. A single node is positioned and painted from
 * springs, which gives it a property separate tooltips cannot have: when one
 * is showing and the pointer moves to the next control, the same tooltip
 * glides to the new anchor and changes its words, rather than one label
 * vanishing and another appearing somewhere else.
 *
 * <p>Deliberately small motion: a few pixels of travel, a slight sharpening out
 * of blur, opacity. A tooltip is a label, and it should settle into place like
 * one — not arrive like a notification.
 *
 * <p>Shown on keyboard focus as well as hover, because a label that only a
 * mouse can reveal is not a label for everyone. The element keeps its
 * aria-label; the tooltip itself is aria-hidden so it is not read twice.
 */
export function installTooltips(doc: Document = document) {
  const tip = doc.createElement("div");
  tip.setAttribute("aria-hidden", "true");
  tip.className = "physics-tip";
  Object.assign(tip.style, {
    position: "fixed",
    left: "0",
    top: "0",
    zIndex: "70",
    pointerEvents: "none",
    opacity: "0",
    willChange: "transform, opacity",
  } satisfies Partial<CSSStyleDeclaration>);
  doc.body.appendChild(tip);

  let anchor: HTMLElement | null = null;
  let below = true;
  let openTimer: ReturnType<typeof setTimeout> | undefined;
  let closeTimer: ReturnType<typeof setTimeout> | undefined;
  /** Recently visible: the next anchor opens at once and the tooltip travels. */
  let warmUntil = 0;

  const show = new Spring(0, { config: motion.fast, kind: "fade", precision: 0.002 });
  const x = new Spring(0, { config: motion.fast, precision: 0.1 });
  const y = new Spring(0, { config: motion.fast, precision: 0.1 });

  const paint = () => {
    const p = Math.max(0, Math.min(1, show.value));
    const reduced = prefersReducedMotion();
    const nudge = reduced ? 0 : (1 - p) * (below ? -4 : 4);
    tip.style.transform = `translate3d(${x.value.toFixed(1)}px,${(y.value + nudge).toFixed(1)}px,0) scale(${
      reduced ? 1 : 0.97 + 0.03 * p
    })`;
    tip.style.opacity = String(p);
    const b = reduced ? 0 : (1 - p) * blur.tooltip;
    tip.style.filter = b > 0.15 ? `blur(${b.toFixed(2)}px)` : "";
  };
  show.subscribe(paint);
  x.subscribe(paint);
  y.subscribe(paint);

  const place = (el: HTMLElement, travel: boolean) => {
    tip.textContent = el.dataset.tip ?? "";
    const a = el.getBoundingClientRect();
    const w = tip.offsetWidth;
    const h = tip.offsetHeight;
    below = a.bottom + 8 + h < window.innerHeight;
    const tx = Math.max(8, Math.min(window.innerWidth - w - 8, a.left + a.width / 2 - w / 2));
    const ty = below ? a.bottom + 7 : a.top - h - 7;
    if (travel) {
      x.set(tx);
      y.set(ty);
    } else {
      x.jump(tx);
      y.jump(ty);
    }
  };

  const open = (el: HTMLElement) => {
    clearTimeout(closeTimer);
    clearTimeout(openTimer);
    const warm = performance.now() < warmUntil || show.value > 0.05;
    anchor = el;
    if (warm) {
      // Already visible, or only just hidden: go straight there, travelling.
      place(el, show.value > 0.05);
      show.set(1);
      return;
    }
    openTimer = setTimeout(() => {
      if (anchor !== el) return;
      place(el, false);
      show.set(1);
    }, 420);
  };

  const close = (immediate = false) => {
    clearTimeout(openTimer);
    clearTimeout(closeTimer);
    const hide = () => {
      if (show.value > 0.05) warmUntil = performance.now() + 450;
      anchor = null;
      show.set(0);
    };
    // A short grace period so moving between adjacent controls does not flash.
    if (immediate) hide();
    else closeTimer = setTimeout(hide, 90);
  };

  const target = (e: Event) => (e.target as Element | null)?.closest?.<HTMLElement>("[data-tip]") ?? null;

  const over = (e: PointerEvent) => {
    if (e.pointerType === "touch") return;
    const el = target(e);
    if (el && el !== anchor) open(el);
  };
  const out = (e: PointerEvent) => {
    const el = target(e);
    const to = (e.relatedTarget as Element | null)?.closest?.("[data-tip]");
    if (el && el === anchor && to !== el) close();
  };
  const focusIn = (e: FocusEvent) => {
    const el = target(e);
    if (el && (el as HTMLElement).matches(":focus-visible")) open(el);
  };
  const focusOut = () => close();
  const key = (e: KeyboardEvent) => e.key === "Escape" && close(true);
  // A press is a decision; the label has done its job.
  const down = () => close(true);
  const scroll = () => anchor && close(true);

  doc.addEventListener("pointerover", over, { passive: true });
  doc.addEventListener("pointerout", out, { passive: true });
  doc.addEventListener("focusin", focusIn);
  doc.addEventListener("focusout", focusOut);
  doc.addEventListener("keydown", key);
  doc.addEventListener("pointerdown", down, { passive: true, capture: true });
  window.addEventListener("scroll", scroll, { passive: true, capture: true });
  return () => {
    doc.removeEventListener("pointerover", over);
    doc.removeEventListener("pointerout", out);
    doc.removeEventListener("focusin", focusIn);
    doc.removeEventListener("focusout", focusOut);
    doc.removeEventListener("keydown", key);
    doc.removeEventListener("pointerdown", down, { capture: true });
    window.removeEventListener("scroll", scroll, { capture: true });
    tip.remove();
  };
}
