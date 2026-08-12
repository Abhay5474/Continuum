import { NavLink, useLocation } from "react-router-dom";
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
          className="flex flex-wrap items-center gap-0.5 rounded-[var(--r-md)] p-0.5"
          style={{ background: "var(--wash-mute)" }}
        >
          {feature.views.map((v) => (
            <NavLink
              key={v.to}
              to={v.to}
              end
              className={({ isActive }) =>
                `rounded-[5px] px-2.5 py-1 text-[12px] font-medium transition-colors duration-150 ${
                  isActive ? "text-slate-100" : "text-slate-400 hover:text-slate-200"
                }`
              }
              style={({ isActive }) =>
                isActive ? { background: "rgb(var(--card))", boxShadow: "var(--card-shadow)" } : undefined
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
