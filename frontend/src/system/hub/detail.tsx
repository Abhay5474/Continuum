import { useEffect, useId, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import { firstSentence } from "../primitives";
import type { ReactNode } from "react";
import { motion, prefersReducedMotion, projectedRest, rubberband, useDrag, usePresence } from "../physics";
import { GLYPHS, GlyphName } from "./glyphs";
import { Dot } from "./structure";

/* ------------------------------------------------------------------ *
 * Detail
 * ------------------------------------------------------------------ */

/**
 * A right-hand detail panel.
 *
 * <p>Progressive disclosure: the list stays on screen, so choosing a different
 * specialist is one click rather than a navigation and a scroll back. Slides
 * from the edge it belongs to, which is the motion that says "this came from
 * that" rather than "something appeared".
 */
export function SidePanel({
  open,
  title,
  subtitle,
  mark,
  onClose,
  footer,
  children,
}: {
  open: boolean;
  title: ReactNode;
  subtitle?: ReactNode;
  mark?: ReactNode;
  onClose: () => void;
  footer?: ReactNode;
  children: ReactNode;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // Mounted for exactly as long as the spring says it is visible — unmounted
  // when it comes to rest closed, not after a guessed timeout. Unmounting still
  // matters: a translated fixed element contributes to the document's scroll
  // width, and every page carrying one had grown a horizontal scrollbar.
  // "fade": under reduced motion the panel does not travel, but it still fades
  // in briefly — appearing from nothing in one frame is its own kind of jolt.
  const { mounted, progress } = usePresence(open, motion.modal, "fade");
  const panel = useRef<HTMLElement>(null);
  const titleId = useId();

  // Focus goes into the panel when it opens and back to whatever opened it
  // when it closes, so a keyboard user is neither left behind on the page nor
  // dropped at the top of the document afterwards.
  useEffect(() => {
    if (!open) return;
    const opener = document.activeElement as HTMLElement | null;
    const id = requestAnimationFrame(() => {
      const el = panel.current;
      if (el && !el.contains(document.activeElement)) {
        const target = el.querySelector<HTMLElement>("[data-autofocus]")
          ?? el.querySelector<HTMLElement>('button[aria-label="Close"]');
        target?.focus();
      }
    });
    return () => {
      cancelAnimationFrame(id);
      if (opener && document.contains(opener)) opener.focus({ preventScroll: true });
    };
  }, [open]);
  const scrim = useRef<HTMLDivElement>(null);
  const veil = useRef<HTMLDivElement>(null);
  const handle = useRef<HTMLElement>(null);
  const drag = useRef(0);
  const repaint = useRef<() => void>(() => {});

  // What leaves is what you were looking at. The parent usually clears its
  // selection on close, so the panel keeps the last content it showed while
  // open and carries that out with it, instead of sliding away blank.
  const shown = useRef({ title, subtitle, mark, footer, children });
  if (open) shown.current = { title, subtitle, mark, footer, children };

  // One paint for the whole scene, driven by the panel's progress: the panel's
  // position, and the page behind it dimming and losing focus in proportion.
  // The blur is a fixed backdrop-filter whose opacity follows progress, which
  // looks like a progressive blur and costs a fraction of animating the
  // filter's radius every frame.
  useLayoutEffect(() => {
    if (!mounted) return;
    const paint = () => {
      const p = progress.value;
      const el = panel.current;
      if (!el) return;
      const w = el.offsetWidth || 480;
      const reduced = prefersReducedMotion();
      // The panel floats 8px in from the edge, so it travels that much further
      // to leave the screen entirely.
      const x = reduced ? 0 : (1 - p) * (w + 16) + Math.max(0, drag.current);
      el.style.transform = `translate3d(${x}px,0,0)`;
      el.style.opacity = reduced ? String(Math.max(0, Math.min(1, p))) : "";
      const seen = Math.max(0, Math.min(1, p - Math.max(0, drag.current) / w));
      if (scrim.current) scrim.current.style.opacity = String(seen * 0.55);
      if (veil.current) veil.current.style.opacity = String(seen);
    };
    repaint.current = paint;
    return progress.subscribe(paint);
  }, [mounted, progress]);

  // Drag the header to the right to dismiss. The panel follows the finger;
  // dragging it the wrong way meets resistance. On release, its momentum
  // decides: a flick closes it from anywhere, a slow drag that stops short
  // springs back, carrying whatever velocity the finger had.
  useDrag(handle, {
    axis: "x",
    enabled: mounted,
    onMove: ({ offset }) => {
      const w = panel.current?.offsetWidth ?? 480;
      drag.current = offset >= 0 ? offset : rubberband(offset, w * 0.25);
      progress.jump(1);
      // The spring is already at 1, so it will not repaint by itself.
      repaint.current();
    },
    onEnd: ({ offset, velocity }) => {
      const w = panel.current?.offsetWidth ?? 480;
      const landing = projectedRest(offset, velocity);
      drag.current = 0;
      // Hand the drag over to the spring without a jump: the panel's current
      // offset becomes the progress it starts from.
      const p = 1 - Math.max(0, offset) / w;
      progress.jump(p);
      if (landing > w * 0.4 || velocity > 900) {
        progress.set(0, { velocity: -velocity / w, config: motion.gesture });
        onClose();
      } else {
        progress.set(1, { velocity: -velocity / w, config: motion.gesture });
      }
    },
  });

  if (!mounted) return null;

  // Portalled to <body>: a fixed element is positioned against its nearest
  // transformed ancestor, and page transitions transform <main>, which once
  // left the panel and its scrim clipped to the page column.
  return createPortal(
    <>
      <div ref={veil} aria-hidden className="pointer-events-none fixed inset-0 z-40"
        style={{ backdropFilter: "blur(var(--blur-modal))", WebkitBackdropFilter: "blur(var(--blur-modal))", opacity: 0 }} />
      <div
        ref={scrim}
        onClick={onClose}
        aria-hidden
        className={`fixed inset-0 z-40 ${open ? "" : "pointer-events-none"}`}
        style={{ background: "rgb(var(--scrim))", opacity: 0 }}
      />
      <aside
        ref={panel}
        role="dialog"
        aria-hidden={!open}
        aria-modal={open ? true : undefined}
        aria-labelledby={titleId}
        data-glass
        className="glass-strong fixed bottom-2 right-2 top-2 z-50 flex w-[calc(100%-1rem)] max-w-[30rem] flex-col overflow-hidden"
        style={{ borderRadius: "var(--r-glass)", transform: "translate3d(100%,0,0)", willChange: "transform" }}
      >
        <header
          ref={handle}
          className="flex cursor-grab touch-pan-y items-start gap-3 border-b border-edge/70 px-5 py-4 active:cursor-grabbing"
        >
          {shown.current.mark}
          <div className="min-w-0 flex-1 select-none">
            <h2 id={titleId} className="truncate text-[15px] font-semibold tracking-tight text-slate-100">
              {shown.current.title}
            </h2>
            {shown.current.subtitle && <p className="mt-0.5 text-xs text-slate-500">{shown.current.subtitle}</p>}
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            className="-mr-1 shrink-0 rounded p-1 text-slate-500 hover:text-slate-200"
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
              <path d="m4 4 8 8M12 4l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4" data-scroll>{shown.current.children}</div>
        {shown.current.footer && (
          <footer className="border-t border-edge/70 px-5 py-3">{shown.current.footer}</footer>
        )}
      </aside>
    </>,
    document.body
  );
}

/** A labelled block inside the panel. Type, not a box. */
export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mt-4 first:mt-0">
      <div className="micro">{label}</div>
      <div className="mt-1.5 text-[12.5px] leading-relaxed text-slate-300">{children}</div>
    </div>
  );
}

/** Example payloads. Monospace, scrollable, never a wall. */
export function Code({ children }: { children: ReactNode }) {
  return (
    <pre
      className="max-h-56 overflow-auto rounded-lg px-3 py-2.5 font-mono text-[11px] leading-relaxed text-slate-300"
      style={{ background: "rgb(var(--ink))", boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }}
    >
      {children}
    </pre>
  );
}

/**
 * A provider and whether it can be reached.
 *
 * <p>Replaces the full-width amber banner. A provider that is not connected is
 * a fact about that provider, so it is stated on that provider's row, at that
 * provider's size — and the rest of the page stays usable, which a banner
 * implicitly denies by taking the top of the screen.
 */
export function ProviderLine({
  name,
  connected,
  detail,
  action,
}: {
  name: string;
  connected: boolean;
  detail?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex items-center gap-3 px-3 py-2.5">
      <span
        className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-[11px] font-semibold"
        style={{
          background: connected ? "var(--accent-wash)" : "rgba(120,130,150,0.10)",
          color: connected ? "var(--accent-ink)" : "var(--text-3)",
        }}
        aria-hidden
      >
        {name.slice(0, 2).toUpperCase()}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-2">
          <span className="text-[13px] font-medium text-slate-200">{name}</span>
          <Dot tone={connected ? "ok" : "idle"} label={connected ? "Connected" : "Not connected"} />
        </div>
        {detail && <p className="mt-0.5 truncate text-[11.5px] text-slate-500">{detail}</p>}
      </div>
      {action}
    </div>
  );
}

/**
 * Background detail, available but not shouted.
 *
 * <p>These pages had grown essays. Three hundred words on the cascade page,
 * seventy-word paragraphs on the cost limiter — all of it true, most of it
 * genuinely useful once, and none of it something you need on the fourth visit.
 * A console is read in glances, and a screen that opens with two paragraphs
 * teaches people to skip paragraphs, including the one that mattered.
 *
 * <p>So the reasoning lives behind one line of type. Closed by default, open in
 * one click, and the page above it says only what changes what you would do.
 */
export function Explain({
  title = "How this works",
  children,
}: {
  title?: string;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className="mt-5">
      <button
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        className="flex items-center gap-1.5 text-[11.5px] text-slate-500 transition-colors hover:text-slate-300"
      >
        <svg
          width="9"
          height="9"
          viewBox="0 0 9 9"
          fill="none"
          aria-hidden
          className="transition-transform duration-200"
          style={{ transform: open ? "rotate(90deg)" : "none" }}
        >
          <path d="M2.5 1 6.5 4.5 2.5 8" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        {open ? "Hide" : title}
      </button>
      {open && (
        <div className="mt-2.5 max-w-2xl space-y-2.5 border-l border-edge pl-3.5 text-xs leading-relaxed text-slate-500">
          {children}
        </div>
      )}
    </div>
  );
}

/** Nothing here yet, said quietly. */
export function Empty({
  title,
  hint,
  action,
  glyph = "layers",
}: {
  title: string;
  /** Prose, or a fragment when it needs a code span. */
  hint?: ReactNode;
  action?: ReactNode;
  glyph?: GlyphName;
}) {
  return (
    <div
      className="flex flex-col items-center border border-dashed px-4 py-12 text-center"
      style={{ borderRadius: "var(--r-lg)", borderColor: "rgb(var(--card-edge))" }}
    >
      {/* A mark first. An empty state that opens with a sentence reads as an
          error message; one that opens with a symbol reads as a state. */}
      <span
        aria-hidden
        className="grid h-9 w-9 place-items-center rounded-[var(--r-lg)]"
        style={{ background: "var(--wash-mute)", color: "var(--text-3)" }}
      >
        <svg width="17" height="17" viewBox="0 0 16 16" fill="none" stroke="currentColor"
             strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
          {GLYPHS[glyph]}
        </svg>
      </span>
      <p className="mt-3 text-[13px] font-medium text-slate-200">{title}</p>
      {/* The next step, in one sentence. */}
      {hint && (
        <p className="mx-auto mt-1.5 max-w-sm text-xs leading-relaxed text-slate-500">
          {typeof hint === "string" ? firstSentence(hint) : hint}
        </p>
      )}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}
