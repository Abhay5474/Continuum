import { useRef } from "react";
import { NavLink, useLocation } from "react-router-dom";
import { useLiquidIndicator } from "./physics";
import { featureFor } from "./features";

/**
 * The sibling views of the feature you are looking at.
 *
 * <p>Rendered by the shell rather than by each page, so merging two screens into
 * one feature costs a line in {@code features.ts} and nothing else — and so a
 * page cannot forget to show its own siblings.
 *
 * <p>It sits above the page heading on purpose. Below it, the strip reads as
 * belonging to whatever section it lands next to; above it, it reads as "this is
 * where you are", which is what it is.
 */
export default function FeatureTabs() {
  const { pathname } = useLocation();
  const feature = featureFor(pathname);
  const strip = useRef<HTMLDivElement>(null);
  const pill = useRef<HTMLSpanElement>(null);

  // The pill travels from the view you were on to the one you chose. This
  // strip is remounted by the very navigation it performs, so the indicator is
  // remembered per feature — otherwise it would appear rather than move, and
  // the tab strip would be the one place the console loses track of where you
  // came from. Width, not scale: the pill is rounded, and scaling it would
  // squash its corners into ellipses mid-flight.
  useLiquidIndicator(
    strip,
    feature ? pathname : null,
    (l, r) => {
      const el = pill.current;
      if (!el) return;
      el.style.transform = `translate3d(${l}px,0,0)`;
      el.style.width = `${Math.max(0, r - l)}px`;
    },
    { memoryKey: feature ? `feature:${feature.name}` : undefined }
  );

  if (!feature) {
    return null;
  }
  return (
    <nav aria-label={`${feature.name} views`} className="mb-5">
      <div className="flex items-center gap-2">
        <span className="text-[12.5px] font-semibold tracking-tight text-slate-300">
          {feature.name}
        </span>
        <span aria-hidden className="text-slate-600">
          /
        </span>
        <div
          ref={strip}
          className="relative flex flex-wrap items-center gap-0.5 rounded-[var(--r-md)] p-0.5"
          style={{ background: "var(--wash-mute)" }}
        >
          <span
            ref={pill}
            aria-hidden
            className="pointer-events-none absolute bottom-0.5 left-0 top-0.5 rounded-[5px]"
            style={{ background: "rgb(var(--card))", boxShadow: "var(--card-shadow)", willChange: "transform" }}
          />
          {feature.views.map((v) => (
            <NavLink
              key={v.to}
              to={v.to}
              end
              data-indicator-key={v.to}
              className={({ isActive }) =>
                `press relative z-[1] rounded-[5px] px-2.5 py-1 text-[12px] font-medium ${
                  isActive ? "text-slate-100" : "text-slate-400 hover:text-slate-200"
                }`
              }
            >
              {v.label}
            </NavLink>
          ))}
        </div>
      </div>
    </nav>
  );
}
