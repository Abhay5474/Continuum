import { useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import { blur, motion, prefersReducedMotion, useSpring, usePresence } from "./physics";

/**
 * One surface for every menu in the bar.
 *
 * <p>Menus used to be separate elements that mounted and unmounted, so moving
 * from Traffic to Prompt was one box vanishing and an unrelated box appearing.
 * Here there is a single surface. It grows out of the trigger that opened it —
 * scaling from the trigger's position, sharpening out of a slight blur — and
 * when you move to a neighbouring menu it does not close: it slides to the new
 * trigger and resizes to the new content, and the old content hands over to
 * the new in the direction of travel. The menu is one object that moves, so
 * the eye never loses it.
 *
 * <p>Everything per-frame is painted from springs onto the DOM. React renders
 * when the menu opens, closes or changes — never while it is moving.
 */
export function MenuSurface({
  open,
  root,
  width = 288,
  align,
  render,
}: {
  /** The open menu's key, or null. */
  open: string | null;
  /** The element the triggers live in; the surface positions relative to it. */
  root: RefObject<HTMLElement>;
  width?: number;
  align: (key: string) => "left" | "right";
  render: (key: string) => ReactNode;
}) {
  const { mounted, progress } = usePresence(open !== null, motion.fast, "fade");
  const x = useSpring(0, { config: motion.standard, precision: 0.1 });
  const h = useSpring(0, { config: motion.standard, precision: 0.1 });
  const surface = useRef<HTMLDivElement>(null);
  const [layers, setLayers] = useState<{ key: string; leaving: boolean; dir: number }[]>([]);
  const last = useRef<string | null>(null);

  // Track which menu's content is showing, keeping the outgoing one briefly so
  // the two can hand over rather than cut.
  useLayoutEffect(() => {
    if (open === null) return;
    const prev = last.current;
    last.current = open;
    if (prev === null || prev === open || !mounted) {
      setLayers([{ key: open, leaving: false, dir: 0 }]);
      return;
    }
    const a = anchorX(prev);
    const b = anchorX(open);
    const dir = b > a ? 1 : -1;
    setLayers([
      { key: prev, leaving: true, dir },
      { key: open, leaving: false, dir },
    ]);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open]);

  function anchorX(key: string) {
    const r = root.current;
    const t = r?.querySelector<HTMLElement>(`[data-menu-trigger="${CSS.escape(key)}"]`);
    if (!r || !t) return 0;
    const a = r.getBoundingClientRect();
    const b = t.getBoundingClientRect();
    return align(key) === "right" ? b.right - a.left - width : b.left - a.left;
  }

  // Position and size: slide to the new anchor, grow to the new content.
  useLayoutEffect(() => {
    if (open === null || !surface.current) return;
    const tx = anchorX(open);
    const content = surface.current.querySelector<HTMLElement>(`[data-menu-layer="${CSS.escape(open)}"]`);
    const th = content?.offsetHeight ?? 0;
    const fresh = progress.value < 0.02;
    if (fresh) {
      x.jump(tx);
      h.jump(th);
      // Grow from the trigger's centre, the point the menu came out of.
      const t = root.current?.querySelector<HTMLElement>(`[data-menu-trigger="${CSS.escape(open)}"]`);
      if (t) {
        const tr = t.getBoundingClientRect();
        const sr = root.current!.getBoundingClientRect();
        surface.current.style.transformOrigin = `${tr.left + tr.width / 2 - sr.left - tx}px 0px`;
      }
    } else {
      x.set(tx);
      h.set(th);
    }
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [open, layers]);

  useLayoutEffect(() => {
    const el = surface.current;
    if (!el) return;
    const paint = () => {
      const p = progress.value;
      const reduced = prefersReducedMotion();
      el.style.opacity = String(Math.max(0, Math.min(1, p)));
      el.style.transform = reduced
        ? `translate3d(${x.value}px,0,0)`
        : `translate3d(${x.value}px,${(1 - p) * -6}px,0) scale(${0.955 + 0.045 * p})`;
      // Out of focus while it forms, sharp once it has. The filter is removed
      // entirely at rest so a settled menu costs nothing to scroll past.
      const b = reduced ? 0 : (1 - p) * blur.menu;
      el.style.filter = b > 0.2 ? `blur(${b.toFixed(2)}px)` : "";
      el.style.height = `${h.value}px`;
      el.style.pointerEvents = p > 0.6 ? "auto" : "none";
    };
    const offs = [progress.subscribe(paint), x.subscribe(paint), h.subscribe(paint)];
    return () => offs.forEach((f) => f());
  }, [mounted, progress, x, h]);

  if (!mounted) return null;

  return (
    <div
      ref={surface}
      role="menu"
      data-glass
      className="glass-strong absolute left-0 top-full z-40 mt-2 overflow-hidden rounded-[var(--r-glass)]"
      style={{ width, willChange: "transform, opacity" }}
    >
      {layers.map((l) => (
        <Layer
          key={l.key}
          id={l.key}
          leaving={l.leaving}
          dir={l.dir}
          onGone={() => setLayers((ls) => ls.filter((x) => x.key !== l.key || !x.leaving))}
        >
          {render(l.key)}
        </Layer>
      ))}
    </div>
  );
}

/**
 * One menu's content inside the shared surface. The incoming one arrives from
 * the side the surface is travelling toward; the outgoing one leaves the other
 * way. Both over the fade spring, so the handover never flashes.
 */
function Layer({
  id,
  leaving,
  dir,
  onGone,
  children,
}: {
  id: string;
  leaving: boolean;
  dir: number;
  onGone: () => void;
  children: ReactNode;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const p = useSpring(dir === 0 ? 1 : 0, { config: motion.standard, kind: "fade", precision: 0.002 });

  useLayoutEffect(() => {
    const el = ref.current;
    if (!el) return;
    const off = p.subscribe((v) => {
      const shift = (leaving ? -dir : dir) * 18 * (1 - v);
      el.style.opacity = String(Math.max(0, Math.min(1, v)));
      el.style.transform = prefersReducedMotion() ? "" : `translate3d(${shift}px,0,0)`;
    });
    p.onRest = (v) => {
      if (leaving && v === 0) onGone();
    };
    p.set(leaving ? 0 : 1);
    return off;
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [leaving]);

  return (
    <div
      ref={ref}
      data-menu-layer={id}
      className={`p-1.5 ${leaving ? "pointer-events-none absolute inset-x-0 top-0" : ""}`}
    >
      {children}
    </div>
  );
}
