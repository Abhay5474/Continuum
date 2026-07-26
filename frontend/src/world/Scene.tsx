import { useEffect, useRef, useState, type ReactNode } from "react";

/**
 * One beat of the scroll narrative.
 *
 * <p>A scene occupies a full viewport of scroll and holds text against one side,
 * leaving the other side of the frame to the world behind it. It fades in as it
 * arrives and out as it leaves, so the copy behaves like the camera passing a
 * label rather than a section that starts and stops.
 *
 * <p>The opacity curve is driven from the element's own position, not from a
 * global scroll listener, which keeps each scene independent and means adding
 * or reordering scenes needs no bookkeeping anywhere else.
 */

const reduced = () =>
  typeof window !== "undefined" &&
  window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

export function Scene({
  index,
  label,
  title,
  body,
  side = "left",
  children,
}: {
  index: string;
  label: string;
  title: ReactNode;
  body: ReactNode;
  side?: "left" | "right" | "center";
  children?: ReactNode;
}) {
  const ref = useRef<HTMLElement>(null);
  const [t, setT] = useState(reduced() ? 1 : 0);

  useEffect(() => {
    if (reduced()) return;
    const el = ref.current;
    if (!el) return;

    let frame = 0;
    const update = () => {
      frame = 0;
      const rect = el.getBoundingClientRect();
      const vh = window.innerHeight;
      // Centre of the element relative to the centre of the viewport, in
      // viewport heights. 0 means dead centre.
      const d = (rect.top + rect.height / 2 - vh / 2) / vh;
      // Full strength within a third of a viewport of centre, gone by one.
      setT(Math.max(0, Math.min(1, 1 - (Math.abs(d) - 0.28) / 0.5)));
    };
    const onScroll = () => {
      if (!frame) frame = requestAnimationFrame(update);
    };

    update();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("resize", onScroll, { passive: true });
    return () => {
      cancelAnimationFrame(frame);
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("resize", onScroll);
    };
  }, []);

  const align =
    side === "right"
      ? "ml-auto text-left"
      : side === "center"
        ? "mx-auto text-center"
        : "mr-auto text-left";

  return (
    <section
      ref={ref}
      className="relative flex min-h-screen items-center px-6 py-24 sm:px-10"
    >
      {/* A soft scrim on the text side only. The field stays fully visible
          where there is nothing to read, and the copy never has to compete
          with a moving node for contrast. */}
      <div
        className="pointer-events-none absolute inset-y-0 left-0 w-full sm:max-w-3xl"
        style={{
          [side === "right" ? "right" : "left"]: 0,
          background:
            side === "center"
              ? "radial-gradient(58% 54% at 50% 50%, rgb(var(--ink) / 0.9), rgb(var(--ink) / 0.6) 62%, transparent 82%)"
              : `linear-gradient(to ${side === "right" ? "left" : "right"}, rgb(var(--ink) / 0.8), rgb(var(--ink) / 0.45) 55%, transparent 88%)`,
          opacity: t,
        }}
      />
      <div
        className={`relative w-full max-w-xl ${align}`}
        style={{
          opacity: t,
          // A small rise, scaled by the same curve — enough to feel like arrival,
          // not enough to make the text move while you are reading it.
          transform: `translate3d(0, ${(1 - t) * 26}px, 0)`,
          willChange: "opacity, transform",
        }}
      >
        <div className="flex items-baseline gap-3">
          <span className="readout text-[11px] font-medium tracking-[0.3em] text-aurora">{index}</span>
          <span className="h-px w-8 bg-edge" />
          <span className="text-[10px] uppercase tracking-[0.34em] text-slate-500">{label}</span>
        </div>
        {/* Display scale. The references set their headline as the loudest
            object in the frame; a 2.6rem heading reads as a section header on a
            website, not as a statement in a space. */}
        <h2 className="mt-5 text-[2.4rem] font-semibold leading-[0.98] tracking-[-0.02em] text-slate-50 sm:text-[4.25rem]">
          {title}
        </h2>
        <p className="mt-6 max-w-[46ch] text-[15px] leading-relaxed text-slate-400 sm:text-base">
          {body}
        </p>
        {children && <div className="mt-9">{children}</div>}
      </div>
    </section>
  );
}

/**
 * A line of hard numbers under a scene.
 *
 * <p>Deliberately plain. The world behind is doing the expressive work; the
 * measurements should read as instrument output, and a claim in a glowing box
 * is less believable than the same claim set quietly.
 */
export function Facts({ items }: { items: [string, string][] }) {
  return (
    <dl className="flex flex-wrap gap-x-12 gap-y-5 border-t border-edge/70 pt-5">
      {items.map(([k, v]) => (
        <div key={k}>
          <dt className="text-[9px] uppercase tracking-[0.3em] text-slate-600">{k}</dt>
          <dd className="readout mt-1.5 text-[15px] font-medium text-slate-200">{v}</dd>
        </div>
      ))}
    </dl>
  );
}
