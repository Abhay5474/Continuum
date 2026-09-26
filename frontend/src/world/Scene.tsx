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
  aside,
}: {
  index: string;
  label: string;
  title: ReactNode;
  body: ReactNode;
  side?: "left" | "right" | "center";
  children?: ReactNode;
  /** An instrument placed opposite the copy — read the claim, then drive it. */
  aside?: ReactNode;
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

  const arrive: React.CSSProperties = {
    opacity: t,
    transform: `translate3d(0, ${(1 - t) * 22}px, 0)`,
    willChange: "opacity, transform",
  };

  const align =
    side === "right"
      ? "ml-auto text-left"
      : side === "center"
        ? "mx-auto text-center"
        : "mr-auto text-left";

  // With an instrument alongside, the scene becomes two columns and the scrim
  // covers the full width — there is no longer an empty side to leave open.
  if (aside) {
    return (
      <section ref={ref} className="relative flex min-h-screen items-center px-6 py-24 sm:px-10">
        {/* Above the near field (z-20), so drifting particles pass behind the
            glass instead of across the words. */}
        {/* The fade is applied to each pane, not to this grid: an ancestor with
            opacity becomes the pane's backdrop root, and the glass would then
            frost nothing — the field would show through it sharp. */}
        <div className="relative z-30 mx-auto grid w-full max-w-6xl items-center gap-10 lg:grid-cols-2">
          <div className={`scene-pane glass ${side === "right" ? "lg:order-2" : ""}`} data-glass="" style={arrive}>
            <div className="flex items-baseline gap-3">
              <span className="readout text-[11px] font-medium tracking-[0.3em] text-aurora">{index}</span>
              <span className="h-px w-8 bg-edge" />
              <span className="text-[10px] uppercase tracking-[0.34em] text-slate-500">{label}</span>
            </div>
            <h2 className="mt-5 text-[2rem] font-semibold leading-[1.02] tracking-[-0.02em] text-slate-50 sm:text-[3.1rem]">
              {title}
            </h2>
            <p className="mt-5 max-w-[46ch] text-[15px] leading-relaxed text-slate-400">{body}</p>
            {children && <div className="mt-7">{children}</div>}
          </div>
          <div className={side === "right" ? "lg:order-1" : ""} style={arrive}>{aside}</div>
        </div>
      </section>
    );
  }

  return (
    <section
      ref={ref}
      className="relative flex min-h-screen items-center px-6 py-24 sm:px-10"
    >
      {/* The copy floats on a pane of glass above the field. The field stays
          fully visible around it, and the words never compete with a moving
          node for contrast — the near particles pass behind the pane. */}
      <div
        className={`scene-pane glass relative z-30 w-full max-w-xl ${align}`}
        data-glass=""
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
    <dl className="grid gap-2 sm:grid-cols-3">
      {items.map(([k, v]) => (
        <div key={k} className="scene-fact">
          <dt className="text-[9px] uppercase tracking-[0.3em] text-slate-500">{k}</dt>
          <dd className="readout mt-1.5 text-[14px] font-medium leading-snug text-slate-200">{v}</dd>
        </div>
      ))}
    </dl>
  );
}
