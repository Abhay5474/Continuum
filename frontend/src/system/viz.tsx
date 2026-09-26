import type { ReactNode } from "react";
import { toneInk, type Tone } from "./hub";

/* ------------------------------------------------------------------ *
 * Gauge — how full something is, against its limit
 * ------------------------------------------------------------------ */

/**
 * A half-dial: used against limit.
 *
 * <p>For every "how close to the edge" quantity — requests in flight against
 * the concurrency limit, tokens left in a caller's bucket, spend against a
 * budget. A number alone says 37; the dial says 37 of 40, which is the thing
 * you needed to know. Colour is by zone, so the dial turns amber before it is
 * full rather than when it is.
 */
export function Gauge({
  value,
  max,
  label,
  display,
  sub,
  warnAt = 0.7,
  badAt = 0.9,
  invert = false,
  size = 150,
}: {
  value: number;
  max: number;
  label: ReactNode;
  /** Text in the middle; defaults to the value. */
  display?: ReactNode;
  sub?: ReactNode;
  warnAt?: number;
  badAt?: number;
  /** For quantities where full is good (tokens left): the zones read from the other end. */
  invert?: boolean;
  size?: number;
}) {
  const f = max > 0 ? Math.max(0, Math.min(1, value / max)) : 0;
  const risk = invert ? 1 - f : f;
  const tone: Tone = max <= 0 ? "mute" : risk >= badAt ? "bad" : risk >= warnAt ? "warn" : "ok";
  const r = 52;
  const len = Math.PI * r;
  return (
    <div className="flex min-w-0 flex-col items-center" role="meter" aria-valuemin={0} aria-valuemax={max} aria-valuenow={value}
         aria-label={typeof label === "string" ? label : undefined}>
      <svg width={size} height={size * 0.6} viewBox="0 0 128 76" aria-hidden className="overflow-visible">
        <path d="M12 68a52 52 0 0 1 104 0" fill="none" stroke="rgb(var(--card-rule))" strokeWidth="10" strokeLinecap="round" />
        {f > 0 && <path
          d="M12 68a52 52 0 0 1 104 0"
          fill="none"
          stroke={toneInk(tone)}
          strokeWidth="10"
          strokeLinecap="round"
          strokeDasharray={`${len * f} ${len}`}
          style={{ transition: "stroke-dasharray var(--dur-standard, 400ms) var(--ease-standard, ease)" }}
        />}
        {/* the warning mark on the track */}
        {max > 0 && (
          <path
            d={tick(invert ? 1 - warnAt : warnAt)}
            stroke="rgb(var(--card-edge))"
            strokeWidth="2"
            strokeLinecap="round"
          />
        )}
      </svg>
      <div className="-mt-9 text-center">
        <div className="readout text-[20px] font-medium leading-none tracking-tight" style={{ color: toneInk(tone) }}>
          {display ?? value}
        </div>
      </div>
      <div className="mt-2 text-center text-[11.5px] font-medium text-slate-300">{label}</div>
      {sub && <div className="mt-0.5 text-center text-[10.5px] text-slate-500">{sub}</div>}
    </div>
  );
}

function tick(f: number) {
  const a = Math.PI * (1 - f);
  const cx = 64, cy = 68;
  const x1 = cx + Math.cos(a) * 44, y1 = cy - Math.sin(a) * 44;
  const x2 = cx + Math.cos(a) * 60, y2 = cy - Math.sin(a) * 60;
  return `M${x1.toFixed(1)} ${y1.toFixed(1)}L${x2.toFixed(1)} ${y2.toFixed(1)}`;
}
