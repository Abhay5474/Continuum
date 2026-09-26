import { prefersReducedMotion } from "./spring";

/**
 * Touch ripple on primary actions.
 *
 * <p>A drop of light spreads from the exact point pressed — the water-drop
 * feedback OriginOS uses — so a press is acknowledged where the finger is,
 * before anything it triggers has had time to happen. Only on filled, primary
 * buttons: on every control it would be noise.
 *
 * <p>One delegated listener for the whole document, so no component has to
 * opt in and a button rendered later is covered too.
 */
const PRIMARY = '.demo-button, [data-ripple], button[class*="accent-strong"], a[class*="from-aurora"], button[class*="from-aurora"], button[class*="bg-emerald-7"], button[class*="bg-rose-6"]';

let installed = false;

export function installRipple() {
  if (installed || typeof document === "undefined") return;
  installed = true;
  document.addEventListener(
    "pointerdown",
    (e) => {
      if (e.button !== 0 || prefersReducedMotion()) return;
      const el = (e.target as Element | null)?.closest?.(PRIMARY) as HTMLElement | null;
      if (!el || (el as HTMLButtonElement).disabled) return;
      const r = el.getBoundingClientRect();
      const size = Math.hypot(Math.max(e.clientX - r.left, r.right - e.clientX), Math.max(e.clientY - r.top, r.bottom - e.clientY)) * 2;
      const dot = document.createElement("span");
      dot.className = "ripple";
      dot.setAttribute("aria-hidden", "true");
      dot.style.width = dot.style.height = `${size}px`;
      dot.style.left = `${e.clientX - r.left}px`;
      dot.style.top = `${e.clientY - r.top}px`;
      const cs = getComputedStyle(el);
      if (cs.position === "static") el.style.position = "relative";
      if (cs.overflow !== "hidden") el.style.overflow = "hidden";
      el.appendChild(dot);
      dot.addEventListener("animationend", () => dot.remove(), { once: true });
      setTimeout(() => dot.remove(), 1000);
    },
    { passive: true },
  );
}
