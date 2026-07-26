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
              ? "radial-gradient(62% 52% at 50% 50%, rgb(var(--ink) / 0.75), transparent 76%)"
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
          <span className="text-[11px] uppercase tracking-[0.28em] text-slate-500">{label}</span>
        </div>
        <h2 className="mt-4 text-3xl font-semibold leading-[1.15] tracking-tight text-slate-100 sm:text-[2.6rem]">
          {title}
        </h2>
        <p className="mt-5 text-[15px] leading-relaxed text-slate-400">{body}</p>
        {children && <div className="mt-7">{children}</div>}
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
    <dl className="flex flex-wrap gap-x-10 gap-y-4">
      {items.map(([k, v]) => (
        <div key={k}>
          <dt className="text-[10px] uppercase tracking-[0.22em] text-slate-600">{k}</dt>
          <dd className="readout mt-1 text-lg font-medium text-slate-200">{v}</dd>
        </div>
      ))}
    </dl>
  );
}
