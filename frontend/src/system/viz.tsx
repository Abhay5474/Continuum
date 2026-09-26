import { useEffect, useLayoutEffect, useMemo, useRef, useState, type ReactNode } from "react";
import { Chip, toneInk, toneWash, type GlyphName, type Tone } from "./hub";

/* ------------------------------------------------------------------ *
 * Mechanism — what a feature does to a request, drawn
 * ------------------------------------------------------------------ */

/**
 * One box in a mechanism diagram.
 *
 * <p>Placed on a small grid rather than at coordinates: {@code col} is the step
 * along the path (left to right), {@code row} separates the branches a step can
 * fork into. The diagram lays itself out from that, so a page states the shape
 * of its feature — "a request, the cache, then either an answer or the
 * provider" — and never a pixel.
 */
export type MechNode = {
  id: string;
  col: number;
  row?: number;
  /** Rows this box spans — a step that feeds two branches sits across both. */
  span?: number;
  label: ReactNode;
  sub?: ReactNode;
  /** The live figure for this box: how many went this way, what it holds. */
  value?: ReactNode;
  glyph?: GlyphName;
  tone?: Tone;
  /** end: where traffic comes from or goes; core: the feature itself; out: a result. */
  role?: "end" | "core" | "out";
  /** Switched off, or a branch nothing has taken. Drawn, but quietly. */
  off?: boolean;
  onClick?: () => void;
  selected?: boolean;
};

export type MechLink = {
  from: string;
  to: string;
  /** Relative volume. Line thickness follows it, so the busy path is the thick one. */
  weight?: number;
  tone?: Tone;
  /** A short tag on the line: a count, a share, the condition that sends traffic this way. */
  label?: ReactNode;
  off?: boolean;
};

type Box = { x: number; y: number; w: number; h: number; col: number; row: number };

const NARROW = 560;
/** Where the spine runs, from the left edge of a step on it. */
const SPINE = 16;
/** How far a branch hangs off the spine. */
const BRANCH_INDENT = 30;

/**
 * Input → mechanism → outcomes, with the traffic drawn on it.
 *
 * <p>The answer to "what does this do" for every feature in the console. Each
 * feature is one transformation of a request, and the fastest way to understand
 * a transformation is to see it: where a request enters, what decides its fate,
 * and which ways it can leave — with the lines drawn as thick as the traffic
 * that actually took them, so the diagram is also a readout.
 *
 * <p>Lines are laid out from measured boxes (offset geometry, which ignores
 * page transforms, so a page mid-transition still draws true), redrawn when the
 * container resizes, and turned on their side below {@value NARROW}px so a phone
 * reads the same mechanism top to bottom. The moving dots pause off screen and
 * stop entirely under reduced motion; everything the dots say is also in the
 * numbers.
 */
export function Mechanism({
  nodes,
  links,
  summary,
  className = "",
  minNodeWidth = 132,
  maxNodeWidth = 250,
  narrowAt = NARROW,
}: {
  nodes: MechNode[];
  links: MechLink[];
  /** One sentence a screen reader hears in place of the picture. */
  summary: string;
  className?: string;
  minNodeWidth?: number;
  /** Boxes stay card-sized on a wide screen; the extra width goes to the lines. */
  maxNodeWidth?: number;
  /** Width below which the diagram becomes a tree. */
  narrowAt?: number;
}) {
  const host = useRef<HTMLDivElement>(null);
  const refs = useRef(new Map<string, HTMLDivElement>());
  const [boxes, setBoxes] = useState<Record<string, Box>>({});
  const [size, setSize] = useState({ w: 0, h: 0 });
  const [narrow, setNarrow] = useState(false);
  const [live, setLive] = useState(false);

  const cols = Math.max(1, ...nodes.map((n) => n.col + 1));
  const rows = Math.max(1, ...nodes.map((n) => (n.row ?? 0) + (n.span ?? 1)));
  const hasBack = links.some((l) => {
    const a = nodes.find((n) => n.id === l.from);
    const b = nodes.find((n) => n.id === l.to);
    return !!a && !!b && b.col < a.col;
  });

  // On a phone the diagram becomes a tree: steps that stand alone in their
  // column form a spine down the left, and the branches a step forks into hang
  // off it, indented. Three outcomes side by side at phone width were three
  // truncated words; stacked, each keeps its label.
  const colCount = new Map<number, number>();
  for (const n of nodes) colCount.set(n.col, (colCount.get(n.col) ?? 0) + 1);
  const trunk = (id: string) => {
    const n = nodes.find((m) => m.id === id);
    return !!n && (colCount.get(n.col) ?? 0) === 1;
  };
  const hasMerge = links.some((l) => !trunk(l.from) && trunk(l.to));
  // In the tree a link's tag rides on the box it leads into, since there is no
  // long horizontal run to hang it on.
  const badgeFor = new Map<string, MechLink>();
  for (const l of links) if (l.label !== undefined && l.label !== null && l.label !== "" && !badgeFor.has(l.to)) badgeFor.set(l.to, l);

  // A content key, so values changing redraw the lines (a box can grow when a
  // number gains a digit) without re-running for every parent render.
  const shape = nodes.map((n) => `${n.id}:${n.col}:${n.row ?? 0}:${n.span ?? 1}`).join("|");

  // Measured after every render, so it must only set state when the geometry
  // really moved — otherwise the set re-renders, which measures, which sets.
  const last = useRef("");
  const measure = () => {
    const el = host.current;
    if (!el) return;
    const next: Record<string, Box> = {};
    for (const n of nodes) {
      const box = refs.current.get(n.id);
      if (!box) continue;
      next[n.id] = {
        x: box.offsetLeft,
        y: box.offsetTop,
        w: box.offsetWidth,
        h: box.offsetHeight,
        col: n.col,
        row: n.row ?? 0,
      };
    }
    const sig = JSON.stringify(next) + `${el.offsetWidth}x${el.offsetHeight}`;
    if (sig === last.current) return;
    last.current = sig;
    setBoxes(next);
    setSize({ w: el.offsetWidth, h: el.offsetHeight });
  };

  useLayoutEffect(() => {
    const el = host.current;
    if (!el) return;
    const decide = () => {
      const w = el.parentElement?.clientWidth ?? el.offsetWidth;
      setNarrow(w < Math.max(narrowAt, cols * minNodeWidth + (cols - 1) * 40));
    };
    decide();
    const ro = new ResizeObserver(() => {
      decide();
      measure();
    });
    ro.observe(el);
    if (el.parentElement) ro.observe(el.parentElement);
    return () => ro.disconnect();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [cols, minNodeWidth, narrowAt]);

  useLayoutEffect(() => {
    measure();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  });

  // The dots only move while the diagram is on screen and the tab is visible.
  useEffect(() => {
    const el = host.current;
    if (!el || typeof IntersectionObserver === "undefined") return;
    const io = new IntersectionObserver(([e]) => setLive(e.isIntersecting), { rootMargin: "80px" });
    io.observe(el);
    return () => io.disconnect();
  }, []);

  const maxW = Math.max(0, ...links.map((l) => l.weight ?? 0));
  const width = (l: MechLink) => {
    if (l.off) return 1.25;
    if (l.weight === undefined) return 2;
    if (maxW <= 0) return 1.5;
    return 1.6 + 6.4 * Math.sqrt(Math.max(0, l.weight) / maxW);
  };

  const paths = useMemo(() => {
    return links
      .map((l, i) => {
        const a = boxes[l.from];
        const b = boxes[l.to];
        if (!a || !b) return null;
        const back = b.col < a.col;
        let d: string;
        let mid: { x: number; y: number };
        if (!narrow) {
          if (!back) {
            const x0 = a.x + a.w, y0 = a.y + a.h / 2;
            const x3 = b.x, y3 = b.y + b.h / 2;
            const dx = Math.max(14, (x3 - x0) / 2);
            d = `M${x0} ${y0} C${x0 + dx} ${y0}, ${x3 - dx} ${y3}, ${x3} ${y3}`;
            mid = bezierMid(x0, y0, x0 + dx, y0, x3 - dx, y3, x3, y3);
          } else {
            // A return path runs under the boxes and comes back up.
            const x0 = a.x + a.w / 2, y0 = a.y + a.h;
            const x3 = b.x + b.w / 2, y3 = b.y + b.h;
            const drop = 30;
            d = `M${x0} ${y0} C${x0} ${y0 + drop}, ${x3} ${y3 + drop}, ${x3} ${y3}`;
            mid = bezierMid(x0, y0, x0, y0 + drop, x3, y3 + drop, x3, y3);
          }
        } else if (!back) {
          const aT = trunk(l.from), bT = trunk(l.to);
          const r = 7;
          if (!aT && bT) {
            // A branch rejoining the spine runs down the right-hand gutter.
            const xr = size.w - 11, y0 = a.y + a.h / 2;
            d = `M${a.x + a.w} ${y0} H${xr - r} Q${xr} ${y0} ${xr} ${y0 + r} V${b.y}`;
            mid = { x: xr, y: (y0 + b.y) / 2 };
          } else if (aT && !bT) {
            // Down the spine, then a short elbow into the branch.
            const x = a.x + SPINE, y = b.y + b.h / 2;
            d = `M${x} ${a.y + a.h} V${y - r} Q${x} ${y} ${x + r} ${y} H${b.x}`;
            mid = { x, y: (a.y + a.h + y) / 2 };
          } else {
            const x = a.x + SPINE;
            d = `M${x} ${a.y + a.h} V${b.y}`;
            mid = { x, y: (a.y + a.h + b.y) / 2 };
          }
        } else {
          // Back up the left-hand gutter.
          const xl = 6, y0 = a.y + a.h / 2, y3 = b.y + b.h / 2, r = 7;
          d = `M${a.x} ${y0} H${xl + r} Q${xl} ${y0} ${xl} ${y0 - r} V${y3 + r} Q${xl} ${y3} ${xl + r} ${y3} H${b.x}`;
          mid = { x: xl, y: (y0 + y3) / 2 };
        }
        return { key: `${l.from}-${l.to}-${i}`, d, mid, link: l, w: width(l), back };
      })
      .filter(Boolean) as { key: string; d: string; mid: { x: number; y: number }; link: MechLink; w: number; back: boolean }[];
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [boxes, narrow, links, maxW, size.w]);

  const gridStyle: React.CSSProperties = narrow
    ? { display: "flex", flexDirection: "column", alignItems: "stretch", rowGap: 14 }
    : {
        gridTemplateColumns: `repeat(${cols}, minmax(0, 1fr))`,
        gridTemplateRows: `repeat(${rows}, auto)`,
        columnGap: 44,
        rowGap: 12,
        paddingBottom: hasBack ? 36 : 0,
      };

  return (
    <div
      role="figure"
      aria-label={summary}
      className={`mech ${className}`}
      data-live={live ? "" : undefined}
      data-shape={shape}
    >
      <div ref={host} className="relative grid items-center" style={gridStyle}>
        <svg
          className="pointer-events-none absolute left-0 top-0 overflow-visible"
          width={size.w}
          height={size.h}
          aria-hidden
        >
          {paths.map((p) => {
            const ink = p.link.off ? "var(--mech-line-off)" : p.link.tone ? toneInk(p.link.tone) : "var(--mech-line)";
            return (
              <g key={p.key}>
                <path
                  d={p.d}
                  fill="none"
                  stroke={ink}
                  strokeOpacity={p.link.off ? 1 : 0.28}
                  strokeWidth={p.w}
                  strokeLinecap="round"
                  strokeDasharray={p.link.off ? "3 5" : undefined}
                />
                {!p.link.off && (
                  <path
                    className="mech-flow"
                    d={p.d}
                    fill="none"
                    stroke={ink}
                    strokeWidth={Math.max(2.2, p.w * 0.72)}
                    strokeLinecap="round"
                    strokeDasharray="0.1 16"
                  />
                )}
              </g>
            );
          })}
        </svg>

        {(narrow ? [...nodes].sort((x, y) => x.col - y.col || (x.row ?? 0) - (y.row ?? 0)) : nodes).map((n) => {
          const r = n.row ?? 0;
          const span = n.span ?? 1;
          const lead = hasBack ? 18 : 0;
          const place: React.CSSProperties = narrow
            ? trunk(n.id)
              ? { marginLeft: lead }
              : { marginLeft: lead + BRANCH_INDENT, marginRight: hasMerge ? 22 : 0 }
            : { gridColumn: n.col + 1, gridRow: `${r + 1} / span ${span}`, justifySelf: "center", width: `min(100%, ${maxNodeWidth}px)` };
          return (
            <div
              key={n.id}
              ref={(el) => {
                if (el) refs.current.set(n.id, el);
                else refs.current.delete(n.id);
              }}
              style={place}
              className="relative z-[1] min-w-0"
            >
              <MechBox node={n} badge={narrow ? badgeFor.get(n.id) : undefined} />
            </div>
          );
        })}

        {!narrow && paths.map((p) =>
          p.link.label !== undefined && p.link.label !== null && p.link.label !== "" ? (
            <span
              key={`l-${p.key}`}
              className="mech-tag pointer-events-none absolute z-[2] -translate-x-1/2 -translate-y-1/2 whitespace-nowrap"
              style={{
                left: p.mid.x,
                top: p.mid.y,
                color: p.link.off ? "var(--text-3)" : p.link.tone ? toneInk(p.link.tone) : undefined,
              }}
            >
              {p.link.label}
            </span>
          ) : null,
        )}
      </div>
    </div>
  );
}

function bezierMid(x0: number, y0: number, x1: number, y1: number, x2: number, y2: number, x3: number, y3: number) {
  return { x: (x0 + 3 * x1 + 3 * x2 + x3) / 8, y: (y0 + 3 * y1 + 3 * y2 + y3) / 8 };
}

function MechBox({ node: n, badge }: { node: MechNode; badge?: MechLink }) {
  const role = n.role ?? "out";
  const tone: Tone = n.tone ?? (role === "core" ? "accent" : role === "end" ? "mute" : "info");
  const interactive = !!n.onClick;
  const body = (
    <>
      <div className="flex min-w-0 items-start gap-2.5">
        {n.glyph && <Chip glyph={n.glyph} tone={n.off ? "mute" : tone} size={26} />}
        <div className="min-w-0 flex-1">
          <div className="flex items-start gap-2">
            <div className="line-clamp-2 min-w-0 flex-1 text-[12.5px] font-medium leading-snug text-slate-100">{n.label}</div>
            {badge && (
              <span className="mech-tag shrink-0" style={{ color: badge.off ? "var(--text-3)" : badge.tone ? toneInk(badge.tone) : undefined }}>
                {badge.label}
              </span>
            )}
          </div>
          {n.sub && <div className="mt-0.5 line-clamp-2 text-[11px] leading-snug text-slate-500">{n.sub}</div>}
        </div>
      </div>
      {n.value !== undefined && n.value !== null && n.value !== "" && (
        <div
          className="readout mt-1.5 text-[19px] font-medium leading-none tracking-tight"
          style={{ color: n.off ? "var(--text-3)" : role === "out" && n.tone ? toneInk(n.tone) : undefined }}
        >
          {n.value}
        </div>
      )}
    </>
  );
  const cls = `mech-node ${role === "core" ? "mech-core" : ""} ${n.off ? "mech-off" : ""} ${
    n.selected ? "mech-selected" : ""
  } block w-full text-left`;
  const style: React.CSSProperties =
    role === "core" && !n.off ? { ["--mech-core" as string]: toneWash(tone), ["--mech-core-ink" as string]: toneInk(tone) } : {};
  return interactive ? (
    <button type="button" className={`${cls} press`} style={style} onClick={n.onClick} aria-pressed={n.selected}>
      {body}
    </button>
  ) : (
    <div className={cls} style={style}>
      {body}
    </div>
  );
}

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
