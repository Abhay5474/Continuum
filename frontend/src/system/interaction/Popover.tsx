import { AnimatePresence, m } from "motion/react";
import { useEffect, useLayoutEffect, useRef, useState, type ReactNode, type RefObject } from "react";
import { createPortal } from "react-dom";
import { transitions } from "../physics";
import { useHoverHighlight } from "./HoverHighlight";

/**
 * A menu that opens from the control that summoned it.
 *
 * <p>It grows out of its anchor — the scale origin is the edge it opens from —
 * and settles on a fast spring; closing runs the same spring back toward the
 * anchor, so the menu visibly returns to where it came from. Reopened mid-close,
 * it turns around from where it is.
 *
 * <p>Behaves like a menu button's menu: opened from the keyboard, focus goes to
 * the first item; arrows, Home and End move; Escape closes and puts focus back
 * on the anchor; Tab or a click outside closes it.
 */
export type Placement = "bottom-start" | "bottom-end" | "top-start" | "right-start" | "right-end";

const ITEMS = '[role="menuitem"], [role="menuitemradio"], [role="menuitemcheckbox"]';

export function Popover({
  open,
  onClose,
  anchor,
  placement = "bottom-start",
  width = 280,
  label,
  focusOnOpen = false,
  children,
}: {
  open: boolean;
  onClose: () => void;
  anchor: RefObject<HTMLElement>;
  placement?: Placement;
  width?: number;
  label: string;
  /** Opened from the keyboard: move focus into the menu. */
  focusOnOpen?: boolean;
  children: ReactNode;
}) {
  const panel = useRef<HTMLDivElement>(null);
  const [pos, setPos] = useState<{ left: number; top?: number; bottom?: number } | null>(null);
  const { bind, highlight } = useHoverHighlight(panel, ITEMS);

  // Placed from the anchor's rectangle, and kept there if the page scrolls or
  // the window changes size while it is open.
  useLayoutEffect(() => {
    if (!open) return;
    const place = () => {
      const a = anchor.current?.getBoundingClientRect();
      if (!a) return;
      const gap = 8;
      const vw = window.innerWidth;
      const vh = window.innerHeight;
      const clampX = (x: number) => Math.max(8, Math.min(vw - width - 8, x));
      if (placement === "bottom-start") setPos({ left: clampX(a.left), top: a.bottom + gap });
      else if (placement === "bottom-end") setPos({ left: clampX(a.right - width), top: a.bottom + gap });
      else if (placement === "top-start") setPos({ left: clampX(a.left), bottom: vh - a.top + gap });
      else if (placement === "right-start") setPos({ left: clampX(a.right + gap), top: Math.max(8, a.top) });
      else setPos({ left: clampX(a.right + gap), bottom: Math.max(8, vh - a.bottom) });
    };
    place();
    window.addEventListener("resize", place);
    window.addEventListener("scroll", place, true);
    return () => {
      window.removeEventListener("resize", place);
      window.removeEventListener("scroll", place, true);
    };
  }, [open, anchor, placement, width]);

  useEffect(() => {
    if (!open) return;
    const outside = (e: PointerEvent) => {
      const t = e.target as Node;
      if (panel.current?.contains(t) || anchor.current?.contains(t)) return;
      onClose();
    };
    document.addEventListener("pointerdown", outside, true);
    return () => document.removeEventListener("pointerdown", outside, true);
  }, [open, onClose, anchor]);

  useEffect(() => {
    if (!open || !focusOnOpen) return;
    const id = requestAnimationFrame(() => panel.current?.querySelector<HTMLElement>(ITEMS)?.focus());
    return () => cancelAnimationFrame(id);
  }, [open, focusOnOpen]);

  const onKey = (e: React.KeyboardEvent) => {
    const items = [...(panel.current?.querySelectorAll<HTMLElement>(ITEMS) ?? [])];
    const i = items.indexOf(document.activeElement as HTMLElement);
    const go = (n: number) => {
      e.preventDefault();
      items[(n + items.length) % items.length]?.focus();
    };
    if (e.key === "ArrowDown") go(i + 1);
    else if (e.key === "ArrowUp") go(i < 0 ? items.length - 1 : i - 1);
    else if (e.key === "Home") go(0);
    else if (e.key === "End") go(items.length - 1);
    else if (e.key === "Escape") {
      e.preventDefault();
      e.stopPropagation();
      onClose();
      anchor.current?.focus();
    } else if (e.key === "Tab") onClose();
  };

  const origin =
    placement === "bottom-end" ? "100% 0%" :
    placement.startsWith("top") ? "0% 100%" :
    placement === "right-end" ? "0% 100%" :
    placement.startsWith("right") ? "0% 0%" : "0% 0%";
  const from = placement.startsWith("top") ? 6 : placement.startsWith("right") ? 0 : -6;

  return createPortal(
    <AnimatePresence>
      {open && pos && (
        <m.div
          ref={panel}
          role="menu"
          aria-label={label}
          onKeyDown={onKey}
          {...bind}
          className="popover fixed z-[70] p-1.5"
          style={{ left: pos.left, top: pos.top, bottom: pos.bottom, width, transformOrigin: origin }}
          initial={{ opacity: 0, scale: 0.94, y: from, x: placement.startsWith("right") ? -6 : 0, filter: "blur(4px)" }}
          animate={{ opacity: 1, scale: 1, y: 0, x: 0, filter: "blur(0px)" }}
          exit={{ opacity: 0, scale: 0.96, y: from * 0.6, x: placement.startsWith("right") ? -4 : 0, filter: "blur(2px)", transition: transitions.fade }}
          transition={transitions.fast}
        >
          {highlight}
          <div className="relative">{children}</div>
        </m.div>
      )}
    </AnimatePresence>,
    document.body,
  );
}
