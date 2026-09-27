import { useEffect, useLayoutEffect, useRef } from "react";
import type { ReactNode } from "react";
import { motion, useSpring } from "../physics";
import { Rail } from "./structure";

/* ------------------------------------------------------------------ *
 * Sequence
 * ------------------------------------------------------------------ */

/**
 * A vertical run of events on a spine.
 *
 * <p>For anything that happened in order: a request through the layer, the
 * stages of a transform. The rail is what makes it a sequence — a stack of
 * bordered rows is a list, and a list does not say that the third thing happened
 * because of the second.
 */
/**
 * A vertical chain of steps on a rail.
 *
 * <p>The rail is also the channel work travels along. {@code flowing} sends a
 * packet of light down it — a request in flight, shown as movement through the
 * actual stages rather than a spinner beside them — and {@code progress} charges
 * the rail up to the last step that has completed, on a spring, so completion
 * reads as the chain filling rather than rows appearing.
 */
export function Spine({
  children,
  flowing = false,
  progress,
}: {
  children: ReactNode;
  /** A request is in flight through this chain. */
  flowing?: boolean;
  /** 0–1: how much of the chain has completed. Omit for a static chain. */
  progress?: number;
}) {
  const charge = useRef<HTMLSpanElement>(null);
  const rail = useRef<HTMLOListElement>(null);
  const p = useSpring(progress ?? 0, { config: motion.standard, precision: 0.001 });
  // The packet travels the rail's real length, so its speed through each
  // stage is the same whether the chain has three steps or eight.
  useLayoutEffect(() => {
    const el = rail.current;
    if (!el || !flowing || typeof ResizeObserver === "undefined") return;
    const sync = () => {
      const h = el.offsetHeight;
      el.style.setProperty("--rail-h", `${h}px`);
      // Each node lights when the packet's centre reaches it: timed from the
      // node's measured position, not its index, because rows are not evenly
      // spaced. The packet runs from -44px to the rail's end at constant speed,
      // and the node's flash peaks 5% into its own cycle.
      const dur = parseFloat(getComputedStyle(el).getPropertyValue("--packet-dur")) || 1500;
      const top = el.getBoundingClientRect().top;
      el.querySelectorAll<HTMLElement>(".node-sense").forEach((n) => {
        const y = n.getBoundingClientRect().top + n.offsetHeight / 2 - top;
        n.style.animationDelay = `${(dur * (y + 22)) / (h + 44) - 0.05 * dur}ms`;
      });
    };
    sync();
    const ro = new ResizeObserver(sync);
    ro.observe(el);
    return () => ro.disconnect();
  }, [flowing]);
  useEffect(() => {
    if (progress != null) p.set(progress);
  }, [progress, p]);
  useLayoutEffect(() => {
    if (progress == null) return;
    return p.subscribe((v) => {
      if (charge.current) charge.current.style.transform = `scaleY(${Math.max(0, Math.min(1, v)).toFixed(4)})`;
    });
  }, [p, progress == null]);
  return (
    <ol ref={rail} className="relative ml-[7px] pl-6">
      <span aria-hidden className="absolute bottom-0 left-0 top-0 w-px" style={{ background: "rgb(var(--edge))" }} />
      {progress != null && (
        <span
          ref={charge}
          aria-hidden
          className="absolute bottom-0 left-0 top-0 w-px origin-top"
          style={{
            background: "linear-gradient(var(--accent), color-mix(in srgb, var(--accent) 55%, transparent))",
            boxShadow: "0 0 6px var(--accent-wash)",
            transform: "scaleY(0)",
          }}
        />
      )}
      {flowing && <span aria-hidden className="rail-packet absolute left-[-1px] top-0 w-[3px]" />}
      {children}
    </ol>
  );
}

export function SpineNode({
  tone = "idle",
  head,
  aside,
  trailing,
  onClick,
  open = false,
  children,
  index = 0,
  revealed = true,
  sensing,
}: {
  tone?: "ok" | "warn" | "bad" | "idle" | "accent" | "skipped";
  /**
   * A request is flowing down the rail: this node lights as the packet passes
   * it. The Spine times it from the node's measured position.
   */
  sensing?: boolean;
  head: ReactNode;
  aside?: ReactNode;
  trailing?: ReactNode;
  onClick?: () => void;
  open?: boolean;
  children?: ReactNode;
  index?: number;
  revealed?: boolean;
}) {
  const colour = {
    ok: "var(--state-healthy-ink)",
    warn: "var(--state-warning-ink)",
    bad: "var(--state-critical-ink)",
    idle: "var(--state-idle-ink)",
    accent: "var(--accent)",
    skipped: "var(--series-mute)",
  }[tone];
  return (
    <li
      className={`spine-step relative py-1.5 ${revealed ? "is-in" : ""}`}
      style={{ transitionDelay: `${index * 20}ms` }}
    >
      {/* The node sits on the rail, half outside the padding box. Hollow when
          the step was skipped: an outline reads as "this position exists and
          nothing happened in it", which is exactly what a skip is. A node that
          has just completed lands — scales in on the elastic spring — and the
          model's node flashes once as the answer arrives at it. */}
      <span
        className={`absolute -left-[29px] top-[13px] h-[9px] w-[9px] rounded-full ${
          // Waiting for the packet and landing are exclusive: both are the
          // node's one animation, and giving it both lets the later rule win.
          sensing
            ? "node-sense"
            : revealed
              ? tone === "accent"
                ? "node-land node-arrive"
                : "node-land"
              : ""
        }`}
        style={{
          background: tone === "skipped" ? "rgb(var(--panel))" : colour,
          boxShadow: `0 0 0 2px rgb(var(--ink)), inset 0 0 0 ${tone === "skipped" ? 1.5 : 0}px ${colour}`,

        }}
        aria-hidden
      />
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
        className={`-mx-2 flex items-center gap-3 rounded-md px-2 py-1 ${
          onClick ? "cursor-pointer hover:bg-slate-500/[0.055]" : ""
        }`}
      >
        <div className="min-w-0 flex-1">
          <div className="truncate text-[13px] text-slate-200">{head}</div>
          {aside && <div className="mt-0.5 truncate text-[11.5px] text-slate-500">{aside}</div>}
        </div>
        {trailing}
      </div>
      {open && children && <div className="mt-2">{children}</div>}
    </li>
  );
}

/** A loading list that keeps the page's shape instead of collapsing it. */
export function RowSkeleton({ rows = 4 }: { rows?: number }) {
  return (
    <Rail>
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="flex items-center gap-3.5 px-3 py-3.5">
          <div className="h-[34px] w-[34px] shrink-0 rounded-[9px] bg-slate-500/10" />
          <div className="min-w-0 flex-1 space-y-2">
            <div className="h-2.5 rounded bg-slate-500/10" style={{ width: `${38 - i * 4}%` }} />
            <div className="h-2 rounded bg-slate-500/[0.07]" style={{ width: `${62 - i * 6}%` }} />
          </div>
        </div>
      ))}
    </Rail>
  );
}
