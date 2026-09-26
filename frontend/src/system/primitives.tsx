import { useEffect, useRef, useState, type ReactNode } from "react";
import { STATE, type StateKey } from "./tokens";
import { motion, projectedRest, rubberband, useDrag, useSpring, useSpringPaint } from "./physics";
import { CountUp } from "./motion";
import { Chip, type GlyphName, type Tone } from "./hub";

/**
 * Instrument primitives.
 *
 * These are the shared vocabulary of the interface: labels, readouts, meters and
 * state indicators. They are intentionally *not* cards — a Plane is a surface you
 * compose into a layout, not a box that isolates a metric.
 */

/** A wide-tracked uppercase system label. */
export function Micro({ children, className = "" }: { children: ReactNode; className?: string }) {
  return <div className={`micro ${className}`}>{children}</div>;
}

/** A recessed instrument surface. */
/**
 * The heading every console page wears.
 *
 * <p>Seventeen pages had grown seventeen slightly different headers — some with
 * tracking, some without, a few with an emoji, three with no heading element at
 * all. None of that was a decision; it was drift. One component means the
 * console reads as one instrument, and means a page with no {@code h1} cannot
 * happen by omission.
 *
 * <p>{@code aside} holds whatever the page needs at the top right: a toggle, a
 * row of readouts, a control.
 *
 * <p>The title is larger and the subtitle capped at a readable measure. A page
 * title set at the same size as the section headings under it does not open the
 * page, and a one-line subtitle running the full width of a 1400px console is a
 * line nobody reaches the end of.
 */
/**
 * An explanation, one hover or focus away.
 *
 * <p>The console used to print these as paragraphs under every control — "Off
 * by default. Rollback issues real calls to real systems, so…" — which put a
 * wall of grey sentences between the reader and the numbers. The sentence is
 * still there for whoever wants it; it no longer stands in the way of whoever
 * does not. The guide carries the longer story.
 */
export function InfoTip({ text, className = "" }: { text: string; className?: string }) {
  return (
    <button
      type="button"
      data-tip={text}
      aria-label={text}
      data-no-press
      onClick={(e) => e.stopPropagation()}
      className={`ml-0.5 inline-grid h-4 w-4 shrink-0 place-items-center rounded-full align-middle text-slate-500 transition-colors hover:bg-slate-500/10 hover:text-slate-300 focus:outline-none focus-visible:shadow-[var(--ring)] ${className}`}
    >
      <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"
           strokeLinecap="round" aria-hidden>
        <circle cx="8" cy="8" r="6.2" />
        <path d="M8 7.3v3.6M8 5.2v.1" />
      </svg>
    </button>
  );
}

/**
 * A side remark: one quiet line, the rest a click away.
 *
 * <p>What used to be a grey paragraph under a control. Collapsed it is a single
 * truncated line behind an ⓘ — enough to know a remark is there and what it is
 * about; the full text is on hover, and a click unfolds it in place. So a page
 * scans as controls and figures, and the explanation is still one gesture off
 * for whoever needs it.
 */
export function Note({ children, className = "" }: { children: ReactNode; className?: string }) {
  const [open, setOpen] = useState(false);
  const ref = useRef<HTMLSpanElement>(null);
  const [full, setFull] = useState<string | undefined>(undefined);
  useEffect(() => setFull(ref.current?.textContent ?? undefined), [children]);
  return (
    <p className={`flex max-w-md items-start gap-1.5 text-[11.5px] text-slate-500 ${className}`}>
      <button
        type="button"
        aria-expanded={open}
        aria-label={open ? "Hide note" : "Show note"}
        onClick={() => setOpen((o) => !o)}
        data-no-press
        className="mt-[1px] grid h-4 w-4 shrink-0 place-items-center rounded-full text-slate-500 hover:bg-slate-500/10 hover:text-slate-300 focus:outline-none focus-visible:shadow-[var(--ring)]"
      >
        <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"
             strokeLinecap="round" aria-hidden>
          <circle cx="8" cy="8" r="6.2" />
          <path d="M8 7.3v3.6M8 5.2v.1" />
        </svg>
      </button>
      <span
        ref={ref}
        onClick={() => setOpen(true)}
        title={open ? undefined : full}
        className={open ? "leading-relaxed" : "min-w-0 cursor-pointer truncate"}
      >
        {children}
      </span>
    </p>
  );
}

/** The first sentence of a string: what a subtitle can afford to say. */
export function firstSentence(text: string): string {
  const m = text.match(/^.+?[.!?](?=\s|$)/);
  return (m ? m[0] : text).trim();
}

/**
 * Turns the current page's cards into liquid glass while it is mounted.
 *
 * <p>Sets {@code data-liquid} on the document root; the stylesheet gives the
 * page a wallpaper to refract and every {@code Card} the glass material. A
 * counter rather than a flag, so two mounted users (a page and its guide
 * preview, say) do not switch it off under each other.
 */
let liquidUsers = 0;
export function useLiquidSurface() {
  useEffect(() => {
    liquidUsers += 1;
    document.documentElement.setAttribute("data-liquid", "");
    return () => {
      liquidUsers -= 1;
      if (liquidUsers === 0) document.documentElement.removeAttribute("data-liquid");
    };
  }, []);
}

export function PageHeader({
  title,
  subtitle,
  aside,
  glyph,
  tone = "accent",
  badge,
}: {
  title: ReactNode;
  subtitle?: ReactNode;
  aside?: ReactNode;
  /** The page's mark, in a tinted tile — the same one its cards use. */
  glyph?: GlyphName;
  tone?: Tone;
  /** A count or state that belongs to the whole page, beside the title. */
  badge?: ReactNode;
}) {
  // The mark sits on the title's own line rather than beside the whole block,
  // so the subtitle starts at the page's left margin like every other line of
  // body text. Indenting it under the icon put one paragraph per page on a
  // different measure from all the others.
  return (
    <header className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3 pb-1">
      <div className="min-w-0 flex-1">
        <div className="flex flex-wrap items-center gap-2.5">
          {glyph && <Chip glyph={glyph} tone={tone} size={28} />}
          <h1 className="text-[20px] font-semibold tracking-[-0.011em] text-slate-100">{title}</h1>
          {badge}
        </div>
        {/* One line: what the page is for. Anything longer is the guide's. */}
        {subtitle && (
          <p
            className="mt-1 max-w-2xl truncate text-[13px] text-slate-500"
            title={typeof subtitle === "string" ? subtitle : undefined}
          >
            {typeof subtitle === "string" ? firstSentence(subtitle) : subtitle}
          </p>
        )}
      </div>
      {aside && <div className="flex flex-wrap items-center gap-x-5 gap-y-3">{aside}</div>}
    </header>
  );
}

export function Plane({
  children,
  className = "",
  inset = false,
}: {
  children: ReactNode;
  className?: string;
  inset?: boolean;
}) {
  return <div className={`${inset ? "well" : "plane"} ${className}`}>{children}</div>;
}

/**
 * A telemetry readout: a value with its unit and label. Values are tabular so a
 * changing number doesn't reflow, and re-render is signalled by a settle, not a
 * flash.
 *
 * <p>Retuned to sit in the page rather than shout over it. These were set bold
 * at 24px and appeared six-across at the top of nearly every screen, which made
 * the loudest thing on any page the band of numbers you had not come for. The
 * label carries the meaning; the value only has to be findable, and a normal
 * weight at 21px is findable. Colour is still reserved for state — a number is
 * tinted when it is telling you something, and plain when it is not.
 */
export function Readout({
  label,
  value,
  unit,
  state = "idle",
  hint,
  size = "md",
}: {
  label: string;
  value: ReactNode;
  unit?: string;
  state?: StateKey;
  hint?: string;
  size?: "sm" | "md" | "lg";
}) {
  const sizes = { sm: "text-[17px]", md: "text-[21px]", lg: "text-[30px]" };
  // A plain number rolls to its new value so a change is visible; anything
  // already formatted (strings, elements) is rendered as given.
  const body = typeof value === "number" ? <CountUp value={value} /> : value;
  return (
    <div title={hint} className="min-w-0">
      <Micro className="truncate">{label}</Micro>
      <div className="mt-1 flex items-baseline gap-1">
        <span
          className={`readout leading-none tracking-tight ${sizes[size]}`}
          style={{ color: state === "idle" ? undefined : STATE[state].ink }}
        >
          {body}
        </span>
        {unit && <span className="text-[11px] text-slate-500">{unit}</span>}
      </div>
    </div>
  );
}

/** A state indicator — the dot only animates when the state warrants attention. */
export function StateDot({ state, size = 8 }: { state: StateKey; size?: number }) {
  const s = STATE[state];
  const attention = state === "critical" || state === "degraded";
  return (
    <span
      className={`inline-block shrink-0 rounded-full ${attention ? "degrading" : ""}`}
      style={{
        width: size,
        height: size,
        background: s.color,
        boxShadow: state === "offline" ? "none" : `0 0 ${size}px ${s.glow}`,
      }}
      title={s.label}
    />
  );
}

/** A horizontal load/saturation meter. Colour is the state, not decoration. */
export function Meter({
  value,
  state = "active",
  height = 4,
  label,
}: {
  value: number; // 0..1
  state?: StateKey;
  height?: number;
  label?: string;
}) {
  const pct = Math.max(0, Math.min(1, value)) * 100;
  return (
    <div>
      {label && (
        <div className="mb-1 flex items-baseline justify-between">
          <Micro>{label}</Micro>
          <span className="readout text-[10px] text-slate-400">{pct.toFixed(0)}%</span>
        </div>
      )}
      <div className="w-full overflow-hidden rounded-full bg-edge" style={{ height }}>
        <div
          className="h-full rounded-full transition-[width] duration-700 ease-out"
          style={{ width: `${pct}%`, background: STATE[state].color }}
        />
      </div>
    </div>
  );
}

/**
 * A compact history trace. Renders nothing but a line — no axes, no legend — so
 * a row of them reads as instrumentation rather than a set of charts.
 */
export function Trace({
  points,
  state = "active",
  width = 120,
  height = 28,
}: {
  points: number[];
  state?: StateKey;
  width?: number;
  height?: number;
}) {
  const color = STATE[state].color;
  const max = Math.max(...points);
  const min = Math.min(...points);
  const flat = points.length < 2 || max === min;

  // Not enough history, or a value that hasn't moved, is drawn as a quiet
  // baseline. Filling the area under a flat series would render a solid block
  // and read as a bug rather than as "nothing has changed yet".
  if (flat) {
    return (
      <svg width={width} height={height} className="overflow-visible" aria-label="no change">
        <line
          x1={0}
          y1={height / 2}
          x2={width}
          y2={height / 2}
          stroke={color}
          strokeOpacity={0.35}
          strokeWidth={1}
          strokeDasharray="2 4"
        />
      </svg>
    );
  }

  const span = max - min;
  const step = width / (points.length - 1);
  const d = points
    .map((p, i) => `${i === 0 ? "M" : "L"} ${(i * step).toFixed(1)} ${(height - ((p - min) / span) * height).toFixed(1)}`)
    .join(" ");
  return (
    <svg width={width} height={height} className="overflow-visible">
      <path d={`${d} L ${width} ${height} L 0 ${height} Z`} fill={color} opacity={0.1} />
      <path d={d} fill="none" stroke={color} strokeWidth={1.25} strokeLinejoin="round" strokeLinecap="round" />
    </svg>
  );
}

/**
 * A subsystem that exists in the topology but is not present in this build.
 * Shown rather than hidden so the system's real shape stays legible.
 */
export function NotInstalled({ name, note }: { name: string; note?: string }) {
  return (
    <div className="flex items-center gap-2 rounded border border-dashed border-edge/70 px-3 py-2">
      <StateDot state="offline" />
      <span className="text-xs text-slate-500">{name}</span>
      <span className="ml-auto micro">Not installed</span>
      {note && <span className="sr-only">{note}</span>}
    </div>
  );
}

/**
 * The console's one on/off control.
 *
 * <p>Every feature that can be turned on used to draw its own switch, so they
 * drifted — and two subsystems (the prompt firewall and prompt compression)
 * shipped a working backend toggle with no control at all, which meant the only
 * way to enable them was curl.
 *
 * <p>{@code locked} is the important part. Some settings change engine-wide
 * behaviour and belong to the operator, so a developer's click would come back
 * 403. Rather than hide the control or let it fail, it renders visibly locked
 * and says what would unlock it.
 */
export function Switch({
  checked,
  onChange,
  label,
  hint,
  busy = false,
  locked,
  onUnlock,
}: {
  checked: boolean;
  onChange: (next: boolean) => void;
  label: string;
  hint?: string;
  busy?: boolean;
  /** Why this control cannot be used right now; omit when it is usable. */
  locked?: string;
  /** Offered alongside {@code locked} as the way out. */
  onUnlock?: () => void;
}) {
  const disabled = busy || locked !== undefined;
  // On the card plane rather than loose on the page. A toggle that turns a
  // feature on for the whole account is the most consequential control on most
  // of these screens, and floating it in the margin made it read like a caption.
  return (
    <div className="plane flex min-w-0 items-center gap-3 px-4 py-3">
      <SwitchThumb checked={checked} disabled={disabled} label={label} onChange={onChange} />
      <div className="min-w-0">
        <div className="flex items-center gap-1.5 text-sm font-medium text-slate-200">
          {label}
          {/* The switch says whether; the tip says why. */}
          {hint && <InfoTip text={hint} />}
        </div>
        {locked && (
          <p className="mt-1 text-xs text-amber-400/90">
            {locked}
            {onUnlock && (
              <>
                {" "}
                <button onClick={onUnlock} className="underline underline-offset-2 hover:text-amber-300">
                  Unlock
                </button>
              </>
            )}
          </p>
        )}
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ *
 * The toggle, as an object
 * ------------------------------------------------------------------ */

/** Track 36px, thumb 14px, 3px inset each side. */
const TRAVEL = 36 - 14 - 3 * 2 - 2;

/**
 * The switch itself: a thumb with mass on a track.
 *
 * <ul>
 *   <li><b>Anticipation.</b> Held down, the thumb stretches toward where it is
 *       about to go, the way a finger leaning on a real switch loads it.</li>
 *   <li><b>Momentum.</b> It travels on the elastic spring and lands with a
 *       small overshoot; in flight it elongates with its speed and relaxes as
 *       it stops, so the motion reads as mass rather than a slide.</li>
 *   <li><b>Gesture.</b> Drag the thumb and it follows the finger, with
 *       resistance past either end. Released, its momentum decides the outcome
 *       — a short flick commits, a slow drag that stops halfway goes back.</li>
 *   <li><b>Truth.</b> The thumb always returns to {@code checked}. If the change
 *       was refused — locked, or the request failed — it springs back rather
 *       than staying where the finger left it and lying about the state.</li>
 * </ul>
 */
function SwitchThumb({
  checked,
  disabled,
  label,
  onChange,
}: {
  checked: boolean;
  disabled: boolean;
  label: string;
  onChange: (next: boolean) => void;
}) {
  const track = useRef<HTMLButtonElement>(null);
  const thumb = useRef<HTMLSpanElement>(null);
  const pos = useSpring(checked ? 1 : 0, { config: motion.elastic, precision: 0.0005 });
  const press = useSpring(0, { config: motion.micro });
  const dragged = useRef(false);
  const from = useRef(0);

  // The spring follows the prop. Also re-asserted when a request settles, so a
  // refused change springs back instead of sticking where it was dropped.
  useEffect(() => {
    pos.set(checked ? 1 : 0);
  }, [checked, disabled, pos]);

  const paint = () => {
    const el = thumb.current;
    const tr = track.current;
    if (!el || !tr) return;
    const p = pos.value;
    const speed = Math.abs(pos.velocity) * TRAVEL; // px/s
    // Stretch with speed, capped so a hard flick is a smear, not a stripe.
    const stretch = Math.min(0.32, speed / 2600);
    const held = press.value * 0.28;
    const sx = 1 + stretch + held;
    const sy = 1 - stretch * 0.35;
    // Held, it grows toward the side it is heading for, anchored on the other.
    const lean = held * 7 * (p < 0.5 ? 1 : -1);
    el.style.transform = `translate3d(${p * TRAVEL + lean}px,0,0) scale(${sx},${sy})`;
    tr.style.setProperty("--p", Math.max(0, Math.min(1, p)).toFixed(3));
  };
  useSpringPaint(pos, thumb, paint);
  useSpringPaint(press, thumb, paint);

  useDrag(track, {
    axis: "x",
    ignore: "[data-none]",
    enabled: !disabled,
    onStart: () => {
      dragged.current = true;
      from.current = pos.value;
      press.set(0);
    },
    onMove: ({ offset }) => {
      const raw = from.current + offset / TRAVEL;
      // Past either end the thumb resists rather than stopping dead.
      const over = raw < 0 ? raw : raw > 1 ? raw - 1 : 0;
      pos.jump((raw < 0 ? 0 : raw > 1 ? 1 : raw) + rubberband(over * TRAVEL, TRAVEL) / TRAVEL);
    },
    onEnd: ({ velocity }) => {
      const v = velocity / TRAVEL;
      const next = projectedRest(pos.value, v * 0.35) > 0.5;
      pos.set(next ? 1 : 0, { velocity: v, config: motion.gesture });
      if (next !== checked) onChange(next);
    },
  });

  return (
    <button
      ref={track}
      type="button"
      role="switch"
      aria-checked={checked}
      aria-label={label}
      disabled={disabled}
      data-no-press
      onPointerDown={() => {
        if (!disabled) press.set(1);
      }}
      onPointerUp={() => press.set(0)}
      onPointerLeave={() => press.set(0)}
      onClick={() => {
        // A drag already decided; the click that ends it must not toggle again.
        if (dragged.current) {
          dragged.current = false;
          return;
        }
        onChange(!checked);
      }}
      // The track's colour follows the thumb's position, not the prop, so
      // mid-drag the track is part-lit — it shows where the gesture would land.
      style={{
        background:
          "color-mix(in srgb, var(--accent-strong) calc(var(--p, 0) * 100%), rgb(var(--edge) / 0.4))",
        borderColor:
          "color-mix(in srgb, var(--accent-strong) calc(var(--p, 0) * 100%), rgb(var(--edge)))",
        touchAction: "pan-y",
      }}
      className={`relative mt-0.5 inline-flex h-5 w-9 shrink-0 items-center rounded-full border ${
        disabled ? "cursor-not-allowed opacity-50" : "cursor-grab active:cursor-grabbing"
      }`}
    >
      <span
        ref={thumb}
        aria-hidden
        className="absolute left-[3px] top-1/2 -mt-[7px] h-3.5 w-3.5 rounded-full"
        style={{
          // A bead of glass rather than a flat disc: lit from above, a little
          // shade where it curves away underneath.
          background: "radial-gradient(circle at 50% 28%, #fff 0 42%, #eef2f7 100%)",
          boxShadow:
            "inset 0 -1px 1px rgba(15,23,42,.10), 0 1px 3px rgba(0,0,0,.26), 0 0 0 0.5px rgba(0,0,0,.06)",
          transformOrigin: "center",
          willChange: "transform",
        }}
      />
    </button>
  );
}
