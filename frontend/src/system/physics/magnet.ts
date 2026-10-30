import { prefersReducedMotion } from "./spring";

/**
 * Magnetic controls, for the whole document.
 *
 * <p>A control marked {@code [data-magnetic]} leans toward the pointer while
 * the pointer is over it — a few pixels at most, a fraction of the distance
 * from its centre — and springs back to rest when the pointer leaves. It says
 * "this is the thing you are about to press" without a colour change or a
 * glow, the way a physical key settles under a hovering finger.
 *
 * <p>The strength is the attribute's value (default 0.2) and the lean is
 * capped (data-magnet-max, default 4px), so a large control never drifts out
 * from under the pointer. One delegated listener; nothing per element.
 */
let installed = false;

export function installMagnet() {
  if (installed || typeof document === "undefined") return;
  installed = true;
  let held: HTMLElement | null = null;
  let raf = 0;
  let last: PointerEvent | null = null;

  const release = (el: HTMLElement | null) => {
    if (!el) return;
    el.removeAttribute("data-magnet-live");
    el.style.setProperty("--mag-x", "0px");
    el.style.setProperty("--mag-y", "0px");
  };

  const paint = () => {
    raf = 0;
    const e = last;
    if (!e) return;
    const el = (e.target as Element | null)?.closest?.<HTMLElement>("[data-magnetic]") ?? null;
    if (el !== held) {
      release(held);
      held = el;
    }
    if (!el || (el as HTMLButtonElement).disabled) return;
    const r = el.getBoundingClientRect();
    const k = Number(el.dataset.magnetic) || 0.2;
    const max = Number(el.dataset.magnetMax) || 4;
    const dx = Math.max(-max, Math.min(max, (e.clientX - (r.left + r.width / 2)) * k));
    const dy = Math.max(-max, Math.min(max, (e.clientY - (r.top + r.height / 2)) * k));
    el.setAttribute("data-magnet-live", "");
    el.style.setProperty("--mag-x", `${dx.toFixed(2)}px`);
    el.style.setProperty("--mag-y", `${dy.toFixed(2)}px`);
  };

  document.addEventListener(
    "pointermove",
    (e) => {
      if (e.pointerType !== "mouse" || prefersReducedMotion()) return;
      last = e;
      if (!raf) raf = requestAnimationFrame(paint);
    },
    { passive: true },
  );
  document.documentElement.addEventListener("pointerleave", () => {
    release(held);
    held = null;
  });
}
