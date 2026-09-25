import { useLiquidIndicator } from "./physics";
import { useRef } from "react";

/**
 * Section switcher.
 *
 * Every console page carried several distinct stories on one long scroll, which
 * made them read as content dumped wherever there was room. Tabs give each story
 * its own screen, and the underline slides between them so the change reads as
 * one surface moving rather than a hard cut.
 */
export default function Tabs<T extends string>({
  items,
  tab,
  setTab,
  right,
}: {
  items: readonly (readonly [T, string])[];
  tab: T;
  setTab: (t: T) => void;
  right?: React.ReactNode;
}) {
  const wrap = useRef<HTMLDivElement>(null);
  const bar = useRef<HTMLSpanElement>(null);

  // The underline travels between tabs on two springs — see useLiquidIndicator.
  // Painted straight onto the element: no state, no re-render per frame.
  useLiquidIndicator(wrap, tab, (l, r) => {
    const el = bar.current;
    if (!el) return;
    el.style.transform = `translate3d(${l}px,0,0) scaleX(${Math.max(0, r - l)})`;
  });

  return (
    <div
      className="flex flex-wrap items-end justify-between gap-2 border-b"
      style={{ borderColor: "rgb(var(--card-edge))" }}
      role="tablist"
    >
      <div ref={wrap} className="relative flex flex-wrap">
        {items.map(([k, label]) => (
          <button
            key={k}
            data-indicator-key={k}
            role="tab"
            aria-selected={tab === k}
            onClick={() => setTab(k)}
            // Inactive tabs are muted rather than absent, and lift on hover —
            // a tab strip where only the active item is visible reads as a
            // heading with some grey text after it.
            className={`px-3 pb-2.5 pt-2 text-[12.5px] font-medium ${
              tab === k ? "text-slate-100" : "text-slate-500 hover:text-slate-300"
            }`}
          >
            {label}
          </button>
        ))}
        <span
          ref={bar}
          aria-hidden
          className="pointer-events-none absolute -bottom-px left-0 h-[2px] w-px origin-left rounded-full"
          style={{ background: "var(--accent)", willChange: "transform" }}
        />
      </div>
      {right && <div className="pb-1.5">{right}</div>}
    </div>
  );
}
