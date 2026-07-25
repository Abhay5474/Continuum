import { useRef, useLayoutEffect, useState } from "react";
import { STATE } from "./tokens";

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
  const [bar, setBar] = useState({ left: 0, width: 0 });

  // Measure the active tab so the indicator can travel to it.
  useLayoutEffect(() => {
    const el = wrap.current?.querySelector<HTMLElement>(`[data-tab="${tab}"]`);
    if (el) setBar({ left: el.offsetLeft, width: el.offsetWidth });
  }, [tab, items]);

  return (
    <div className="flex flex-wrap items-end justify-between gap-2 border-b border-edge/60">
      <div ref={wrap} className="relative flex flex-wrap">
        {items.map(([k, label]) => (
          <button
            key={k}
            data-tab={k}
            onClick={() => setTab(k)}
            className="px-3 py-2 text-xs font-medium transition-colors"
            style={{ color: tab === k ? "rgb(var(--topo-text))" : undefined }}
          >
            {label}
          </button>
        ))}
        <span
          aria-hidden
          className="absolute -bottom-px h-0.5 rounded-full"
          style={{
            left: bar.left,
            width: bar.width,
            background: STATE.active.color,
            transition: "left 320ms cubic-bezier(0.22,1,0.36,1), width 320ms cubic-bezier(0.22,1,0.36,1)",
          }}
        />
      </div>
      {right && <div className="pb-1.5">{right}</div>}
    </div>
  );
}
