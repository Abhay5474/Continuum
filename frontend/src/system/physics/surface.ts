import { prefersReducedMotion } from "./spring";

/**
 * Pointer light and press origin, for the whole document.
 *
 * <p>Two listeners on the document instead of two per element. A console page
 * carries a hundred-odd buttons and cards; binding each one would cost memory
 * and a React effect apiece, and delegating costs nothing when the pointer is
 * still.
 *
 * <ul>
 *   <li><b>Press origin.</b> On pointerdown, the point inside the pressed
 *       element is written to {@code --px}/{@code --py}, so the highlight that
 *       blooms on press starts under the finger rather than in the centre — the
 *       surface acknowledges <em>where</em> it was touched.</li>
 *   <li><b>Surface light.</b> While the pointer is over an interactive surface
 *       ({@code [data-surface]}), its position is written to {@code --mx}/
 *       {@code --my}, and a faint light follows it. Only interactive ones: a
 *       lit, lifting card is a card claiming it can be clicked.</li>
 * </ul>
 */
export function installSurfaceLight(doc: Document = document) {
  let raf = 0;
  let lit: HTMLElement | null = null;
  let lastEvent: PointerEvent | null = null;

  const down = (e: PointerEvent) => {
    const el = (e.target as Element | null)?.closest?.<HTMLElement>(
      ".press, button:not([data-no-press]), [data-surface]"
    );
    if (!el) return;
    const r = el.getBoundingClientRect();
    el.style.setProperty("--px", `${e.clientX - r.left}px`);
    el.style.setProperty("--py", `${e.clientY - r.top}px`);
  };

  const paint = () => {
    raf = 0;
    const e = lastEvent;
    if (!e) return;
    const el = (e.target as Element | null)?.closest?.<HTMLElement>("[data-surface]") ?? null;
    if (el !== lit) {
      lit?.style.setProperty("--ma", "0");
      lit = el;
    }
    if (!el) return;
    const r = el.getBoundingClientRect();
    el.style.setProperty("--mx", `${e.clientX - r.left}px`);
    el.style.setProperty("--my", `${e.clientY - r.top}px`);
    el.style.setProperty("--ma", "1");
  };

  const move = (e: PointerEvent) => {
    if (e.pointerType === "touch" || prefersReducedMotion()) return;
    lastEvent = e;
    if (!raf) raf = requestAnimationFrame(paint);
  };

  const leave = () => {
    lit?.style.setProperty("--ma", "0");
    lit = null;
  };

  doc.addEventListener("pointerdown", down, { passive: true, capture: true });
  doc.addEventListener("pointermove", move, { passive: true });
  doc.documentElement.addEventListener("pointerleave", leave, { passive: true });
  return () => {
    cancelAnimationFrame(raf);
    doc.removeEventListener("pointerdown", down, { capture: true });
    doc.removeEventListener("pointermove", move);
    doc.documentElement.removeEventListener("pointerleave", leave);
  };
}
