import type { ReactNode } from "react";

/* ------------------------------------------------------------------ *
 * The path a request takes
 * ------------------------------------------------------------------ */

/**
 * A horizontal run of stages, with arrows between them.
 *
 * <p>Most of this console's features are one stage on a single path: a prompt
 * leaves an application, passes a firewall, a cache, a compressor, a router, and
 * reaches a provider. Every one of those pages used to open with its own stat
 * band, which said nothing about where the feature sits. Drawing the path, with
 * the current page's stage lit, answers "what is this and when does it happen"
 * before a word is read — and it is the same picture on every page, so it is
 * learned once.
 *
 * <p>Scrolls horizontally inside itself rather than wrapping. A path that wraps
 * to a second line stops looking like a path.
 */
export function Route({ children }: { children: ReactNode }) {
  return (
    <div className="-mx-1 overflow-x-auto px-1 pb-1">
      <div className="flex min-w-max items-stretch gap-1">{children}</div>
    </div>
  );
}

export function Stage({
  label,
  sub,
  state = "plain",
  mark,
  selected = false,
  onClick,
}: {
  label: ReactNode;
  sub?: ReactNode;
  /** {@code on} and {@code off} are for a stage you can switch; {@code plain}
      is for an endpoint of the path, which is not a control. */
  state?: "on" | "off" | "bad" | "plain";
  mark?: ReactNode;
  selected?: boolean;
  onClick?: () => void;
}) {
  const tone =
    state === "bad"
      ? "var(--state-critical-ink)"
      : state === "on"
        ? "var(--state-healthy-ink)"
        : state === "off"
          ? "var(--state-idle-ink)"
          : undefined;
  return (
    <div
      role={onClick ? "button" : undefined}
      tabIndex={onClick ? 0 : undefined}
      onClick={onClick}
      onKeyDown={(e) => {
        if (onClick && (e.key === "Enter" || e.key === " ")) {
          e.preventDefault();
          onClick();
        }
      }}
      className={`flex min-w-0 shrink-0 items-center gap-2 rounded-lg px-3 py-2 transition-shadow duration-200 ${
        onClick ? "cursor-pointer" : ""
      }`}
      style={{
        background: selected ? "var(--accent-wash)" : "rgb(var(--panel))",
        boxShadow: selected
          ? "inset 0 0 0 1px var(--accent-edge)"
          : "inset 0 0 0 1px rgb(var(--edge))",
      }}
    >
      {mark}
      <div className="min-w-0">
        <div className="truncate text-[12.5px] text-slate-200">{label}</div>
        <div className="flex items-center gap-1.5">
          {state !== "plain" && (
            <span
              className="inline-block h-[5px] w-[5px] shrink-0 rounded-full"
              style={{ background: tone }}
              aria-hidden
            />
          )}
          {sub && (
            <span className="truncate text-[10.5px]" style={{ color: tone ?? "var(--text-3)" }}>
              {sub}
            </span>
          )}
        </div>
      </div>
    </div>
  );
}

export function Hop({ label }: { label?: string }) {
  return (
    <div className="flex shrink-0 flex-col items-center justify-center gap-0.5 px-0.5">
      <svg width="20" height="8" viewBox="0 0 20 8" fill="none" className="text-slate-700" aria-hidden>
        <path
          d="M0 4h15m0 0-3.5-3M15 4l-3.5 3"
          stroke="currentColor"
          strokeWidth="1.2"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>
      {label && <span className="text-[9.5px] leading-none text-slate-600">{label}</span>}
    </div>
  );
}
