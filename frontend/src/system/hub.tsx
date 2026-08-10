import { Children, cloneElement, isValidElement, useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";

/**
 * The console's second vocabulary: rows, marks and flows.
 *
 * <p>The first vocabulary — {@code Plane}, {@code Readout}, {@code Meter} — is
 * for <em>instrumentation</em>: a value being watched. It is the right thing for
 * a page that reports numbers, and the wrong thing for a page you use to find
 * and configure a capability. Applied there it produced what it always produces:
 * a wall of identical bordered rectangles, each carrying a row of badges,
 * because a card has nowhere to put structure except more chrome.
 *
 * <p>So this is a separate set, built on three ideas.
 *
 * <p><b>A list is a list.</b> Rows separated by a hairline, not boxes separated
 * by margin. A row can be dense because it is not competing with a border on
 * every side, and thirty of them read as one object rather than thirty.
 *
 * <p><b>Kind is carried by a mark, not by a badge.</b> {@code [OCR]} in a pill
 * is the same shape as {@code [FREE TIER]} and {@code [TEXT IN]}, so the eye
 * cannot rank them and reads none. A drawn glyph for "reads a document" is
 * recognised before it is read, and it does not cost a line of text.
 *
 * <p><b>Show the shape of the thing.</b> What a specialist <em>is</em> is a
 * transformation: something goes in, something structured comes out. A sentence
 * describing that is worse than drawing it, so {@link Flow} draws it.
 */

/* ------------------------------------------------------------------ *
 * Capability marks
 * ------------------------------------------------------------------ */

export type Kind =
  | "detection"
  | "classification"
  | "ocr"
  | "transcription"
  | "extraction"
  | "moderation"
  | "table"
  | "incident"
  | "conversation"
  | "document"
  | "image"
  | "custom";

/** Normalises the several vocabularies the API uses onto one set of marks. */
export function kindOf(raw?: string | null): Kind {
  const k = (raw ?? "").toLowerCase();
  if (k.includes("detect")) return "detection";
  if (k.includes("classif")) return "classification";
  if (k.includes("ocr")) return "ocr";
  if (k.includes("transcri") || k.includes("audio") || k.includes("speech")) return "transcription";
  if (k.includes("extract")) return "extraction";
  if (k.includes("moderat")) return "moderation";
  if (k.includes("table") || k.includes("spreadsheet")) return "table";
  if (k.includes("incident") || k.includes("log")) return "incident";
  if (k.includes("conversation") || k.includes("email")) return "conversation";
  if (k.includes("document") || k.includes("pdf")) return "document";
  // Last, and only as a whole word: "image" appears inside plenty of capability
  // names ("OCR — scanned image to text") that have a better mark than a photo.
  if (/\b(image|photo|picture|vision)\b/.test(k)) return "image";
  return "custom";
}

/** The hue each capability wears. Identity, never magnitude. */
const KIND_HUE: Record<Kind, string> = {
  detection: "var(--series-1)",
  classification: "var(--series-7)",
  ocr: "var(--series-4)",
  transcription: "var(--series-3)",
  extraction: "var(--series-5)",
  moderation: "var(--series-8)",
  table: "var(--series-2)",
  incident: "var(--series-8)",
  conversation: "var(--series-3)",
  document: "var(--series-4)",
  image: "var(--series-6)",
  custom: "var(--series-mute)",
};

export const KIND_LABEL: Record<Kind, string> = {
  detection: "Detection",
  classification: "Classification",
  ocr: "Text recognition",
  transcription: "Transcription",
  extraction: "Extraction",
  moderation: "Moderation",
  table: "Semantic table",
  incident: "Incident context",
  conversation: "Conversation",
  document: "Document",
  image: "Image",
  custom: "Custom",
};

/**
 * A drawn mark for a capability.
 *
 * <p>Each one is a picture of what the tool does to its input — a bounding box
 * for detection, scan lines for text recognition, a waveform for audio. Drawn
 * rather than lettered so a list of twelve is scannable at a glance, which a
 * list of twelve three-letter pills is not.
 */
export function KindMark({ kind, size = 34 }: { kind: Kind; size?: number }) {
  const hue = KIND_HUE[kind];
  const s = size;
  const glyph = () => {
    switch (kind) {
      case "detection":
        return (
          <>
            <rect x="5" y="6" width="14" height="11" rx="1.5" stroke={hue} strokeWidth="1.5" fill="none" strokeDasharray="3 2" />
            <circle cx="9.5" cy="11" r="1.6" fill={hue} />
            <path d="M5 17h14" stroke={hue} strokeWidth="1.5" opacity=".35" />
          </>
        );
      case "classification":
        return (
          <>
            <circle cx="8" cy="8.5" r="2.6" stroke={hue} strokeWidth="1.5" fill="none" />
            <circle cx="15.5" cy="8.5" r="2.6" stroke={hue} strokeWidth="1.5" fill="none" opacity=".45" />
            <path d="M5 15.5h14M5 18.5h9" stroke={hue} strokeWidth="1.5" strokeLinecap="round" />
          </>
        );
      case "ocr":
        return (
          <>
            <path d="M6 4.5h9l3.5 3.5v11.5H6z" stroke={hue} strokeWidth="1.5" fill="none" strokeLinejoin="round" />
            <path d="M9 10.5h6M9 13.5h6M9 16.5h3.5" stroke={hue} strokeWidth="1.5" strokeLinecap="round" />
            <path d="M4 8.5l16 0" stroke={hue} strokeWidth="1" opacity=".3" />
          </>
        );
      case "transcription":
        return (
          <>
            {[6, 9, 12, 15, 18].map((x, i) => (
              <path
                key={x}
                d={`M${x} ${12 - [3, 6.5, 4.5, 7.5, 2.5][i]}v${[6, 13, 9, 15, 5][i]}`}
                stroke={hue}
                strokeWidth="1.7"
                strokeLinecap="round"
                opacity={i === 1 || i === 3 ? 1 : 0.5}
              />
            ))}
          </>
        );
      case "extraction":
        return (
          <>
            <path d="M5.5 5.5h9v13h-9z" stroke={hue} strokeWidth="1.5" fill="none" opacity=".45" />
            <path d="M8 9h4M8 12h4" stroke={hue} strokeWidth="1.4" strokeLinecap="round" opacity=".45" />
            <path d="M14 12h5m0 0-2-2m2 2-2 2" stroke={hue} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
          </>
        );
      case "moderation":
        return (
          <>
            <path d="M12 4.5l6 2.4v5.3c0 3.6-2.5 6.3-6 7.3-3.5-1-6-3.7-6-7.3V6.9z" stroke={hue} strokeWidth="1.5" fill="none" strokeLinejoin="round" />
            <path d="M9.5 12l1.8 1.8 3.4-3.6" stroke={hue} strokeWidth="1.6" strokeLinecap="round" strokeLinejoin="round" />
          </>
        );
      case "table":
        return (
          <>
            <rect x="4.5" y="5.5" width="15" height="13" rx="1.5" stroke={hue} strokeWidth="1.5" fill="none" />
            <path d="M4.5 9.5h15" stroke={hue} strokeWidth="1.5" />
            <path d="M9.5 9.5v9M14.5 9.5v9" stroke={hue} strokeWidth="1.2" opacity=".45" />
            <path d="M4.5 14h15" stroke={hue} strokeWidth="1.2" opacity=".45" />
          </>
        );
      case "incident":
        return (
          <>
            <path d="M4 16.5h4l2.5-9 3 13 2.5-8h4" stroke={hue} strokeWidth="1.7" fill="none" strokeLinecap="round" strokeLinejoin="round" />
          </>
        );
      case "conversation":
        return (
          <>
            <path d="M4.5 6.5h10v7h-6l-4 3z" stroke={hue} strokeWidth="1.5" fill="none" strokeLinejoin="round" />
            <path d="M10.5 10.5h9v6h-3l-3 2.5v-2.5h-3z" stroke={hue} strokeWidth="1.5" fill="none" strokeLinejoin="round" opacity=".5" />
          </>
        );
      case "image":
        return (
          <>
            <rect x="4" y="5.5" width="16" height="13" rx="2" stroke={hue} strokeWidth="1.5" fill="none" />
            <circle cx="9" cy="10" r="1.6" fill={hue} />
            <path d="M4.5 16.5 9 12l3 3 2.5-2.5 4 4" stroke={hue} strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
          </>
        );
      case "document":
        return (
          <>
            <path d="M6.5 4.5h8l3 3v12h-11z" stroke={hue} strokeWidth="1.5" fill="none" strokeLinejoin="round" />
            <path d="M14.5 4.5v3h3" stroke={hue} strokeWidth="1.5" strokeLinejoin="round" />
            <path d="M9 12h6M9 15h4" stroke={hue} strokeWidth="1.4" strokeLinecap="round" />
          </>
        );
      default:
        return (
          <>
            <circle cx="12" cy="12" r="6.5" stroke={hue} strokeWidth="1.5" fill="none" strokeDasharray="2.5 2.5" />
            <circle cx="12" cy="12" r="1.7" fill={hue} />
          </>
        );
    }
  };
  return (
    <span
      className="grid shrink-0 place-items-center rounded-[9px]"
      style={{
        width: s,
        height: s,
        // A tint of the mark's own hue, not a grey chip. It reads as belonging
        // to the glyph rather than as a container it happens to sit in.
        background: `color-mix(in srgb, ${hue} 12%, transparent)`,
      }}
    >
      <svg width={s * 0.66} height={s * 0.66} viewBox="0 0 24 24" fill="none" aria-hidden>
        {glyph()}
      </svg>
    </span>
  );
}

/* ------------------------------------------------------------------ *
 * Tone
 * ------------------------------------------------------------------ */

/**
 * The six meanings a colour is allowed to carry in this console.
 *
 * <p>Everything below takes a tone rather than a colour, so "this is a failure"
 * is written once and rendered consistently — as ink on a label, as a wash
 * behind a pill, as the rail down the side of a notice. The pairs are defined
 * per theme in {@code index.css}: the ink is chosen to be read against the
 * surface, and the wash is the same ink at low alpha so the two stay legible
 * together on both instrument black and paper.
 */
export type State = "ok" | "warn" | "bad" | "info" | "accent" | "mute";

/**
 * The eight identity hues.
 *
 * <p>Separate from state on purpose. A console that colours everything by state
 * ends up monochrome, because most things are fine most of the time — and one
 * that colours state by identity can't tell you anything is wrong. So a card's
 * mark and its chart wear a hue, which says *which* thing this is, and its
 * badges wear a state, which says whether to care.
 */
export type Hue =
  | "violet"
  | "blue"
  | "cyan"
  | "green"
  | "amber"
  | "orange"
  | "red"
  | "pink";

export type Tone = State | Hue;

/** Fixed order, never hashed — a reader who learned "spend is amber" stays right. */
export const HUES: Hue[] = ["violet", "blue", "cyan", "green", "amber", "orange", "red", "pink"];

/** The hue for slot i, cycling. For a run of cards with no meaning to encode. */
export function hueAt(i: number): Hue {
  return HUES[i % HUES.length];
}

const TONE_INK: Record<Tone, string> = {
  ok: "var(--state-healthy-ink)",
  warn: "var(--state-warning-ink)",
  bad: "var(--state-critical-ink)",
  info: "var(--state-active-ink)",
  accent: "var(--accent-ink)",
  mute: "var(--text-3)",
  violet: "var(--hue-violet-ink)",
  blue: "var(--hue-blue-ink)",
  cyan: "var(--hue-cyan-ink)",
  green: "var(--hue-green-ink)",
  amber: "var(--hue-amber-ink)",
  orange: "var(--hue-orange-ink)",
  red: "var(--hue-red-ink)",
  pink: "var(--hue-pink-ink)",
};

const TONE_WASH: Record<Tone, string> = {
  ok: "var(--wash-ok)",
  warn: "var(--wash-warn)",
  bad: "var(--wash-bad)",
  info: "var(--wash-info)",
  accent: "var(--accent-wash)",
  mute: "var(--wash-mute)",
  violet: "var(--hue-violet-wash)",
  blue: "var(--hue-blue-wash)",
  cyan: "var(--hue-cyan-wash)",
  green: "var(--hue-green-wash)",
  amber: "var(--hue-amber-wash)",
  orange: "var(--hue-orange-wash)",
  red: "var(--hue-red-wash)",
  pink: "var(--hue-pink-wash)",
};

export function toneInk(t: Tone = "mute") {
  return TONE_INK[t];
}
export function toneWash(t: Tone = "mute") {
  return TONE_WASH[t];
}

/* ------------------------------------------------------------------ *
 * Glyphs
 * ------------------------------------------------------------------ */

/**
 * The small line icons that head a card.
 *
 * <p>Drawn rather than imported: fourteen shapes at one weight is a smaller
 * thing to own than an icon package, and it guarantees they share a stroke and
 * a grid. They are decoration — every one sits next to the words it stands for,
 * so none of them has to be guessed.
 */
export type GlyphName =
  | "activity"
  | "shield"
  | "cache"
  | "route"
  | "chip"
  | "clock"
  | "coin"
  | "spark"
  | "layers"
  | "flow"
  | "check"
  | "alert"
  | "gauge"
  | "list";

const GLYPHS: Record<GlyphName, ReactNode> = {
  activity: <path d="M1 8h3l2.5-6 3 12L12.5 8H15" />,
  shield: <path d="M8 1.5 2.5 4v4c0 3 2.3 5.4 5.5 6.5C11.2 13.4 13.5 11 13.5 8V4L8 1.5Z" />,
  cache: (
    <>
      <ellipse cx="8" cy="3.5" rx="5.5" ry="2" />
      <path d="M2.5 3.5v9c0 1.1 2.5 2 5.5 2s5.5-.9 5.5-2v-9" />
      <path d="M2.5 8c0 1.1 2.5 2 5.5 2s5.5-.9 5.5-2" />
    </>
  ),
  route: (
    <>
      <circle cx="3" cy="3.5" r="1.8" />
      <circle cx="13" cy="12.5" r="1.8" />
      <path d="M4.8 3.5H9a2.5 2.5 0 0 1 0 5H7a2.5 2.5 0 0 0 0 5h4.2" />
    </>
  ),
  chip: (
    <>
      <rect x="4" y="4" width="8" height="8" rx="1.5" />
      <path d="M6.5 1.5v2.5M9.5 1.5v2.5M6.5 12v2.5M9.5 12v2.5M1.5 6.5H4M1.5 9.5H4M12 6.5h2.5M12 9.5h2.5" />
    </>
  ),
  clock: (
    <>
      <circle cx="8" cy="8" r="6.3" />
      <path d="M8 4.3V8l2.6 1.6" />
    </>
  ),
  coin: (
    <>
      <circle cx="8" cy="8" r="6.3" />
      <path d="M10 5.6H7.2a1.6 1.6 0 0 0 0 3.2h1.6a1.6 1.6 0 0 1 0 3.2H6M8 4.2v1.4M8 10.4v1.4" />
    </>
  ),
  spark: <path d="M8.8 1.5 3 9.2h4L7.2 14.5 13 6.8H9L8.8 1.5Z" />,
  layers: (
    <>
      <path d="M8 1.6 1.7 5 8 8.4 14.3 5 8 1.6Z" />
      <path d="M1.7 8.5 8 11.9l6.3-3.4" />
    </>
  ),
  flow: (
    <>
      <rect x="1.4" y="5.6" width="4" height="4.8" rx="1" />
      <rect x="10.6" y="5.6" width="4" height="4.8" rx="1" />
      <path d="M5.4 8h5.2M9 6.3 10.8 8 9 9.7" />
    </>
  ),
  check: (
    <>
      <circle cx="8" cy="8" r="6.3" />
      <path d="m5.2 8.2 2 2 3.6-4" />
    </>
  ),
  alert: (
    <>
      <path d="M8 2.2 1.6 13.4h12.8L8 2.2Z" />
      <path d="M8 6.4v3M8 11.3v.1" />
    </>
  ),
  gauge: (
    <>
      <path d="M2 11.5a6.5 6.5 0 1 1 12 0" />
      <path d="M8 11.5 11 6.8" />
    </>
  ),
  list: <path d="M2 4h12M2 8h12M2 12h8" />,
};

/** A glyph in a tinted rounded tile — the mark that heads a card. */
export function Chip({
  glyph,
  tone = "info",
  size = 30,
}: {
  glyph: GlyphName;
  tone?: Tone;
  size?: number;
}) {
  return (
    <span
      aria-hidden
      className="grid shrink-0 place-items-center rounded-[9px]"
      style={{ width: size, height: size, background: toneWash(tone), color: toneInk(tone) }}
    >
      <svg
        width={size * 0.55}
        height={size * 0.55}
        viewBox="0 0 16 16"
        fill="none"
        stroke="currentColor"
        strokeWidth="1.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        {GLYPHS[glyph]}
      </svg>
    </span>
  );
}

/* ------------------------------------------------------------------ *
 * Structure
 * ------------------------------------------------------------------ */

/**
 * The card plane.
 *
 * <p>One rounded, bordered surface holding one idea. The rule that keeps a page
 * of these from turning into a marketplace grid is not "avoid cards" — it is
 * that a card must contain something worth finding: a number, a chart, a list.
 * A card whose entire contents is a row of badges is the failure mode; a card
 * with a headline figure and the shape of its history under it is the reason
 * the form exists.
 */
export function Card({
  children,
  className = "",
  pad = true,
  onClick,
  selected = false,
  guide,
}: {
  children: ReactNode;
  className?: string;
  pad?: boolean;
  onClick?: () => void;
  selected?: boolean;
  /**
   * Names this card as a target for the feature guide's spotlight.
   *
   * <p>An explicit prop rather than accepting a spread, because TypeScript does
   * not excess-property-check hyphenated JSX attributes: a `data-guide` written
   * straight onto a component compiles cleanly, is silently dropped, and the
   * guide then highlights nothing with no error reported anywhere.
   */
  guide?: string;
}) {
  const interactive = !!onClick;
  return (
    <div
      role={interactive ? "button" : undefined}
      tabIndex={interactive ? 0 : undefined}
      onClick={onClick}
      onKeyDown={(e) => {
        if (interactive && (e.key === "Enter" || e.key === " ")) {
          e.preventDefault();
          onClick!();
        }
      }}
      data-guide={guide}
      className={`relative border bg-card shadow-card transition-[background-color,border-color] duration-150 ${
        pad ? "p-4" : ""
      } ${interactive ? "cursor-pointer hover:border-slate-500/40 hover:bg-[color:rgb(var(--card-hover))]" : ""} ${className}`}
      style={{
        borderRadius: "var(--r-lg)",
        borderColor: selected ? "var(--accent-edge)" : "rgb(var(--card-edge))",
      }}
    >
      {children}
    </div>
  );
}

/**
 * A card's header: mark, title, one line of purpose, and whatever acts on it.
 *
 * <p>The subtitle is one line by contract. It is where the sentence that used to
 * be a paragraph goes.
 */
export function CardHead({
  glyph,
  tone = "info",
  title,
  sub,
  right,
  /** Draws the rule that separates head from body. On for anything with a list
      or a table under it; off for a single figure, where a rule would be
      dividing a card into two halves that are not two things. */
  divided = false,
}: {
  glyph?: GlyphName;
  tone?: Tone;
  title: ReactNode;
  sub?: ReactNode;
  right?: ReactNode;
  divided?: boolean;
}) {
  return (
    <div
      className={`flex items-start gap-3 ${divided ? "border-b pb-3" : ""}`}
      style={divided ? { borderColor: "rgb(var(--card-rule))" } : undefined}
    >
      {glyph && <Chip glyph={glyph} tone={tone} />}
      <div className="min-w-0 flex-1">
        <h3 className="truncate text-[13px] font-semibold tracking-tight text-slate-100">
          {title}
        </h3>
        {sub && <p className="mt-0.5 truncate text-[11.5px] leading-relaxed text-slate-500">{sub}</p>}
      </div>
      {right && <div className="flex shrink-0 items-center gap-2">{right}</div>}
    </div>
  );
}

/** A responsive run of cards. Two up by default, three or four when asked. */
export function Grid({ cols = 2, children }: { cols?: 2 | 3 | 4; children: ReactNode }) {
  const at = {
    2: "sm:grid-cols-2",
    3: "sm:grid-cols-2 lg:grid-cols-3",
    4: "sm:grid-cols-2 lg:grid-cols-4",
  }[cols];
  return <div className={`grid grid-cols-1 gap-3 ${at}`}>{children}</div>;
}

/**
 * A section, separated by type and space rather than by a box.
 *
 * <p>The heading sits on the page, above the cards, rather than inside a frame
 * of its own — so the page has one level of nesting, not two.
 */
export function Section({
  title,
  count,
  hint,
  action,
  children,
}: {
  title: string;
  count?: number;
  hint?: string;
  action?: ReactNode;
  children: ReactNode;
}) {
  return (
    <section className="mt-9 first:mt-0">
      <div className="flex flex-wrap items-baseline justify-between gap-x-4 gap-y-1">
        <h2 className="flex items-baseline gap-2 text-[13px] font-semibold tracking-tight text-slate-200">
          {title}
          {count !== undefined && (
            <span className="readout text-[11px] font-normal text-slate-600">{count}</span>
          )}
        </h2>
        {action}
      </div>
      {hint && <p className="mt-1 max-w-2xl text-xs leading-relaxed text-slate-500">{hint}</p>}
      <div className="mt-3">{children}</div>
    </section>
  );
}

/** Rows, hairline-separated, on the card plane. */
export function Rail({ children }: { children: ReactNode }) {
  return (
    <div
      className="overflow-hidden border bg-card shadow-card"
      style={{ borderRadius: "var(--r-lg)", borderColor: "rgb(var(--card-edge))" }}
    >
      <div className="divide-y divide-[color:rgb(var(--card-rule))]">{children}</div>
    </div>
  );
}

/**
 * One entry in a list of capabilities.
 *
 * <p>Dense by design: mark, name, one line of purpose, the transformation it
 * performs, and its state — with actions appearing on hover so the resting page
 * is calm and nothing that is not a target looks like one.
 */
export function Row({
  mark,
  title,
  subtitle,
  meta,
  status,
  trailing,
  actions,
  onClick,
  selected = false,
}: {
  mark?: ReactNode;
  title: ReactNode;
  subtitle?: ReactNode;
  meta?: ReactNode;
  /** Sits beside the title. For a word about the row: a state, a verdict. */
  status?: ReactNode;
  /** Sits hard right and always visible. For a measurement: a bar, a count.
      Numbers compare down a column, so they belong on a shared right edge —
      inline after the title they start at a different x on every row. */
  trailing?: ReactNode;
  actions?: ReactNode;
  onClick?: () => void;
  selected?: boolean;
}) {
  const interactive = !!onClick;
  return (
    <div
      role={interactive ? "button" : undefined}
      tabIndex={interactive ? 0 : undefined}
      onClick={onClick}
      onKeyDown={(e) => {
        if (interactive && (e.key === "Enter" || e.key === " ")) {
          e.preventDefault();
          onClick!();
        }
      }}
      className={`group relative flex items-center gap-3.5 px-3 py-2.5 transition-colors duration-150 ${
        interactive ? "cursor-pointer" : ""
      } ${selected ? "bg-accent-wash" : "hover:bg-slate-500/[0.055]"}`}
      style={selected ? { background: "var(--accent-wash)" } : undefined}
    >
      {/* The selected marker is a rule on the leading edge, not a filled block.
          It marks position without repainting the row. */}
      {selected && (
        <span
          className="absolute inset-y-0 left-0 w-[2px]"
          style={{ background: "var(--accent)" }}
          aria-hidden
        />
      )}
      {mark}
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-2">
          <span className="truncate text-[13.5px] font-medium text-slate-100">{title}</span>
          {status}
        </div>
        {subtitle && (
          <p className="mt-0.5 truncate text-xs leading-relaxed text-slate-500">{subtitle}</p>
        )}
        {meta && <div className="mt-1.5">{meta}</div>}
      </div>
      {trailing && <div className="flex shrink-0 items-center gap-2.5">{trailing}</div>}
      {actions && (
        <div className="flex shrink-0 items-center gap-1 opacity-0 transition-opacity duration-150 group-hover:opacity-100 group-focus-within:opacity-100">
          {actions}
        </div>
      )}
    </div>
  );
}

/**
 * Structured metadata as a run of small facts.
 *
 * <p>The alternative — one pill per fact — gives every fact the same weight and
 * the same shape, so a reader scanning for the provider has to read all five.
 * A label in muted type with its value in normal type is read in one pass.
 */
export function Facts({ items }: { items: { k: string; v: ReactNode; title?: string }[] }) {
  return (
    <dl className="flex flex-wrap items-baseline gap-x-4 gap-y-1 text-[11.5px]">
      {items.map((f, i) => (
        <div key={i} className="flex items-baseline gap-1.5" title={f.title}>
          <dt className="text-slate-600">{f.k}</dt>
          <dd className="text-slate-400">{f.v}</dd>
        </div>
      ))}
    </dl>
  );
}

/** A quiet state marker. A dot and a word — never a filled banner. */
export function Dot({
  tone = "idle",
  label,
}: {
  tone?: "ok" | "warn" | "bad" | "idle" | "busy";
  label?: string;
}) {
  const colour = {
    ok: "var(--state-healthy-ink)",
    warn: "var(--state-warning-ink)",
    bad: "var(--state-critical-ink)",
    idle: "var(--state-idle-ink)",
    busy: "var(--accent)",
  }[tone];
  return (
    <span className="inline-flex items-center gap-1.5 whitespace-nowrap text-[11px]" style={{ color: colour }}>
      <span
        className={`inline-block h-1.5 w-1.5 shrink-0 rounded-full ${tone === "busy" ? "animate-pulse" : ""}`}
        style={{ background: colour }}
        aria-hidden
      />
      {label}
    </span>
  );
}

/** A low-emphasis action that only asserts itself on hover. */
export function Ghost({
  children,
  onClick,
  disabled,
  title,
  tone = "default",
}: {
  children: ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  title?: string;
  tone?: "default" | "accent" | "danger";
}) {
  // Geometry from the shared control tokens rather than from padding, so a
  // Ghost standing next to a Primary or a Select is exactly the same height
  // without any call site knowing what that height is.
  return (
    <button
      onClick={(e) => {
        e.stopPropagation();
        onClick?.();
      }}
      disabled={disabled}
      title={title}
      style={{
        height: "var(--h-sm)",
        borderRadius: "var(--r-md)",
        borderColor: tone === "accent" ? "var(--accent-edge)" : "rgb(var(--card-edge))",
        color:
          tone === "accent"
            ? "var(--accent-ink)"
            : tone === "danger"
              ? "var(--state-critical-ink)"
              : undefined,
      }}
      className={`inline-flex shrink-0 items-center justify-center gap-1.5 whitespace-nowrap border px-2 text-[11.5px] font-medium transition-[background-color,border-color,color] duration-150 hover:border-slate-500/60 hover:bg-[color:rgb(var(--card-hover))] disabled:cursor-not-allowed disabled:opacity-45 ${
        tone === "default" ? "text-slate-400 hover:text-slate-100" : ""
      }`}
    >
      {children}
    </button>
  );
}

/** The one solid button on a screen. Coral on paper, aurora on black. */
export function Primary({
  children,
  onClick,
  disabled,
  type = "button",
}: {
  children: ReactNode;
  onClick?: () => void;
  disabled?: boolean;
  type?: "button" | "submit";
}) {
  return (
    <button
      type={type}
      onClick={onClick}
      disabled={disabled}
      // Colour stated here, not left to the utility. The light theme remaps
      // .text-white to near-black so it survives on paper, and exempts filled
      // buttons by matching their background *class* — which this button does
      // not have, because it sets its background inline. It measured 3.92:1.
      style={{
        background: "var(--accent-strong)",
        color: "var(--accent-on)",
        height: "var(--h-md)",
        borderRadius: "var(--r-md)",
      }}
      className="inline-flex shrink-0 items-center justify-center gap-1.5 whitespace-nowrap px-3 text-[12.5px] font-medium transition-[filter,opacity] duration-150 hover:brightness-110 active:brightness-95 disabled:cursor-not-allowed disabled:opacity-45"
    >
      {children}
    </button>
  );
}

/* ------------------------------------------------------------------ *
 * The shape of a transformation
 * ------------------------------------------------------------------ */

/**
 * Input → thing → output, drawn.
 *
 * <p>The single most useful element on any of these pages. What a specialist or
 * a transformer <em>is</em> is a change of representation, and a paragraph
 * describing one is strictly worse than a picture of one: the picture is read in
 * a glance, survives translation, and cannot be vague about what comes out.
 */
export function Flow({
  input,
  node,
  nodeSub,
  output,
  mark,
  vertical = false,
}: {
  input: ReactNode;
  node: ReactNode;
  nodeSub?: ReactNode;
  output: ReactNode;
  mark?: ReactNode;
  vertical?: boolean;
}) {
  const Arrow = () => (
    <svg
      className="shrink-0 text-slate-700"
      width={vertical ? 12 : 22}
      height={vertical ? 22 : 12}
      viewBox={vertical ? "0 0 12 22" : "0 0 22 12"}
      fill="none"
      aria-hidden
    >
      <path
        d={vertical ? "M6 0v17m0 0-4-4m4 4 4-4" : "M0 6h17m0 0-4-4m4 4-4 4"}
        stroke="currentColor"
        strokeWidth="1.3"
        strokeLinecap="round"
        strokeLinejoin="round"
      />
    </svg>
  );
  const Cell = ({ children, accent = false }: { children: ReactNode; accent?: boolean }) => (
    <div
      className="min-w-0 flex-1 rounded-lg px-3 py-2.5"
      style={{
        background: accent ? "var(--accent-wash)" : "rgb(var(--panel))",
        boxShadow: accent ? "inset 0 0 0 1px var(--accent-edge)" : "inset 0 0 0 1px rgb(var(--edge))",
      }}
    >
      {children}
    </div>
  );
  return (
    <div className={`flex ${vertical ? "flex-col" : "flex-wrap"} items-stretch gap-2`}>
      <Cell>
        <div className="micro">in</div>
        <div className="mt-1 text-[12.5px] text-slate-300">{input}</div>
      </Cell>
      <div className={`flex ${vertical ? "justify-center" : "items-center"}`}>
        <Arrow />
      </div>
      <Cell accent>
        <div className="flex items-center gap-2">
          {mark}
          <div className="min-w-0">
            <div className="truncate text-[12.5px] font-medium text-slate-100">{node}</div>
            {nodeSub && <div className="truncate text-[11px] text-slate-500">{nodeSub}</div>}
          </div>
        </div>
      </Cell>
      <div className={`flex ${vertical ? "justify-center" : "items-center"}`}>
        <Arrow />
      </div>
      <Cell>
        <div className="micro">out</div>
        <div className="mt-1 text-[12.5px] text-slate-300">{output}</div>
      </Cell>
    </div>
  );
}

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

/* ------------------------------------------------------------------ *
 * Command area
 * ------------------------------------------------------------------ */

/**
 * The search field, treated as the page's primary control.
 *
 * <p>Sized and placed like the thing you are meant to use first, with example
 * phrasings underneath. The examples are not decoration: they teach that the
 * field takes an intention — "extract text from a scan" — rather than a keyword,
 * which nobody discovers by looking at an empty input.
 */
export function CommandBar({
  value,
  onChange,
  placeholder,
  suggestions = [],
  right,
}: {
  value: string;
  onChange: (v: string) => void;
  placeholder?: string;
  suggestions?: string[];
  right?: ReactNode;
}) {
  const input = useRef<HTMLInputElement | null>(null);
  const [focus, setFocus] = useState(false);

  useEffect(() => {
    const onKey = (e: KeyboardEvent) => {
      if (e.key === "/" && document.activeElement?.tagName !== "INPUT") {
        e.preventDefault();
        input.current?.focus();
      }
    };
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, []);

  return (
    <div>
      <div
        className="flex items-center gap-3 rounded-xl px-4 py-3 transition-shadow duration-200"
        style={{
          background: "rgb(var(--panel))",
          boxShadow: focus
            ? "inset 0 0 0 1px var(--accent-edge), 0 0 0 3px var(--accent-wash)"
            : "inset 0 0 0 1px rgb(var(--edge))",
        }}
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" className="shrink-0 text-slate-500" aria-hidden>
          <circle cx="7" cy="7" r="4.75" stroke="currentColor" strokeWidth="1.4" />
          <path d="M10.5 10.5 14 14" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" />
        </svg>
        <input
          ref={input}
          value={value}
          onChange={(e) => onChange(e.target.value)}
          onFocus={() => setFocus(true)}
          onBlur={() => setFocus(false)}
          placeholder={placeholder}
          className="min-w-0 flex-1 bg-transparent text-[14px] text-slate-100 outline-none placeholder:text-slate-600"
        />
        {value ? (
          <button
            onClick={() => onChange("")}
            className="shrink-0 text-xs text-slate-600 transition-colors hover:text-slate-300"
          >
            clear
          </button>
        ) : (
          <kbd className="hidden shrink-0 rounded border border-edge px-1.5 py-0.5 text-[10px] text-slate-600 sm:block">
            /
          </kbd>
        )}
        {right}
      </div>
      {suggestions.length > 0 && !value && (
        <div className="mt-2 flex flex-wrap items-center gap-1.5">
          <span className="text-[11px] text-slate-600">try</span>
          {suggestions.map((s) => (
            <button
              key={s}
              onClick={() => onChange(s)}
              className="rounded-full px-2.5 py-1 text-[11.5px] text-slate-500 transition-colors hover:text-slate-200"
              style={{ background: "rgb(var(--panel))" }}
            >
              {s}
            </button>
          ))}
        </div>
      )}
    </div>
  );
}

/**
 * Why a result matched.
 *
 * <p>Search that only reorders rows makes the reader re-derive the ranking.
 * Naming the reason — "matches on: reads text from an image" — turns a list into
 * an answer.
 */
export function Because({ reason }: { reason: string }) {
  return (
    <p className="mt-1 text-[11.5px] leading-relaxed" style={{ color: "var(--accent-ink)" }}>
      <span className="text-slate-600">matches · </span>
      {reason}
    </p>
  );
}

/* ------------------------------------------------------------------ *
 * Detail
 * ------------------------------------------------------------------ */

/**
 * A right-hand detail panel.
 *
 * <p>Progressive disclosure: the list stays on screen, so choosing a different
 * specialist is one click rather than a navigation and a scroll back. Slides
 * from the edge it belongs to, which is the motion that says "this came from
 * that" rather than "something appeared".
 */
export function SidePanel({
  open,
  title,
  subtitle,
  mark,
  onClose,
  footer,
  children,
}: {
  open: boolean;
  title: ReactNode;
  subtitle?: ReactNode;
  mark?: ReactNode;
  onClose: () => void;
  footer?: ReactNode;
  children: ReactNode;
}) {
  useEffect(() => {
    if (!open) return;
    const onKey = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", onKey);
    return () => window.removeEventListener("keydown", onKey);
  }, [open, onClose]);

  // Unmounted once the exit animation has run, rather than parked off-screen
  // with a transform. A translated fixed element still contributes to the
  // document's scroll width, and `overflow-x: hidden` on html does not clip it
  // — fixed elements sit in the initial containing block. Every page carrying
  // one had grown a horizontal scrollbar and a band of dead space to its right.
  const [mounted, setMounted] = useState(open);
  useEffect(() => {
    if (open) {
      setMounted(true);
      return;
    }
    const t = setTimeout(() => setMounted(false), 320);
    return () => clearTimeout(t);
  }, [open]);

  if (!mounted) return null;

  return (
    <>
      <div
        onClick={onClose}
        aria-hidden
        className={`fixed inset-0 z-40 bg-ink/50 backdrop-blur-[1px] transition-opacity duration-250 ${
          open ? "opacity-100" : "pointer-events-none opacity-0"
        }`}
      />
      <aside
        role="dialog"
        aria-hidden={!open}
        className={`fixed inset-y-0 right-0 z-50 flex w-full max-w-[30rem] flex-col border-l border-edge transition-transform duration-300 ease-[cubic-bezier(0.22,1,0.36,1)] ${
          open ? "translate-x-0" : "translate-x-full"
        }`}
        style={{ background: "rgb(var(--panel))" }}
      >
        <header className="flex items-start gap-3 border-b border-edge/70 px-5 py-4">
          {mark}
          <div className="min-w-0 flex-1">
            <h2 className="truncate text-[15px] font-semibold tracking-tight text-slate-100">{title}</h2>
            {subtitle && <p className="mt-0.5 text-xs text-slate-500">{subtitle}</p>}
          </div>
          <button
            onClick={onClose}
            aria-label="Close"
            className="-mr-1 shrink-0 rounded p-1 text-slate-500 transition-colors hover:text-slate-200"
          >
            <svg width="16" height="16" viewBox="0 0 16 16" fill="none" aria-hidden>
              <path d="m4 4 8 8M12 4l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            </svg>
          </button>
        </header>
        <div className="min-h-0 flex-1 overflow-y-auto px-5 py-4">{open && children}</div>
        {footer && <footer className="border-t border-edge/70 px-5 py-3">{footer}</footer>}
      </aside>
    </>
  );
}

/** A labelled block inside the panel. Type, not a box. */
export function Field({ label, children }: { label: string; children: ReactNode }) {
  return (
    <div className="mt-4 first:mt-0">
      <div className="micro">{label}</div>
      <div className="mt-1.5 text-[12.5px] leading-relaxed text-slate-300">{children}</div>
    </div>
  );
}

/** Example payloads. Monospace, scrollable, never a wall. */
export function Code({ children }: { children: ReactNode }) {
  return (
    <pre
      className="max-h-56 overflow-auto rounded-lg px-3 py-2.5 font-mono text-[11px] leading-relaxed text-slate-300"
      style={{ background: "rgb(var(--ink))", boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }}
    >
      {children}
    </pre>
  );
}

/**
 * A provider and whether it can be reached.
 *
 * <p>Replaces the full-width amber banner. A provider that is not connected is
 * a fact about that provider, so it is stated on that provider's row, at that
 * provider's size — and the rest of the page stays usable, which a banner
 * implicitly denies by taking the top of the screen.
 */
export function ProviderLine({
  name,
  connected,
  detail,
  action,
}: {
  name: string;
  connected: boolean;
  detail?: string;
  action?: ReactNode;
}) {
  return (
    <div className="flex items-center gap-3 px-3 py-2.5">
      <span
        className="grid h-7 w-7 shrink-0 place-items-center rounded-md text-[11px] font-semibold"
        style={{
          background: connected ? "var(--accent-wash)" : "rgba(120,130,150,0.10)",
          color: connected ? "var(--accent-ink)" : "var(--text-3)",
        }}
        aria-hidden
      >
        {name.slice(0, 2).toUpperCase()}
      </span>
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-2">
          <span className="text-[13px] font-medium text-slate-200">{name}</span>
          <Dot tone={connected ? "ok" : "idle"} label={connected ? "Connected" : "Not connected"} />
        </div>
        {detail && <p className="mt-0.5 truncate text-[11.5px] text-slate-500">{detail}</p>}
      </div>
      {action}
    </div>
  );
}

/**
 * Background detail, available but not shouted.
 *
 * <p>These pages had grown essays. Three hundred words on the cascade page,
 * seventy-word paragraphs on the cost limiter — all of it true, most of it
 * genuinely useful once, and none of it something you need on the fourth visit.
 * A console is read in glances, and a screen that opens with two paragraphs
 * teaches people to skip paragraphs, including the one that mattered.
 *
 * <p>So the reasoning lives behind one line of type. Closed by default, open in
 * one click, and the page above it says only what changes what you would do.
 */
export function Explain({
  title = "How this works",
  children,
}: {
  title?: string;
  children: ReactNode;
}) {
  const [open, setOpen] = useState(false);
  return (
    <div className="mt-5">
      <button
        onClick={() => setOpen(!open)}
        aria-expanded={open}
        className="flex items-center gap-1.5 text-[11.5px] text-slate-500 transition-colors hover:text-slate-300"
      >
        <svg
          width="9"
          height="9"
          viewBox="0 0 9 9"
          fill="none"
          aria-hidden
          className="transition-transform duration-200"
          style={{ transform: open ? "rotate(90deg)" : "none" }}
        >
          <path d="M2.5 1 6.5 4.5 2.5 8" stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        {open ? "Hide" : title}
      </button>
      {open && (
        <div className="mt-2.5 max-w-2xl space-y-2.5 border-l border-edge pl-3.5 text-xs leading-relaxed text-slate-500">
          {children}
        </div>
      )}
    </div>
  );
}

/** Nothing here yet, said quietly. */
export function Empty({
  title,
  hint,
  action,
  glyph = "layers",
}: {
  title: string;
  hint?: string;
  action?: ReactNode;
  glyph?: GlyphName;
}) {
  return (
    <div
      className="flex flex-col items-center border border-dashed px-4 py-12 text-center"
      style={{ borderRadius: "var(--r-lg)", borderColor: "rgb(var(--card-edge))" }}
    >
      {/* A mark first. An empty state that opens with a sentence reads as an
          error message; one that opens with a symbol reads as a state. */}
      <span
        aria-hidden
        className="grid h-9 w-9 place-items-center rounded-[var(--r-lg)]"
        style={{ background: "var(--wash-mute)", color: "var(--text-3)" }}
      >
        <svg width="17" height="17" viewBox="0 0 16 16" fill="none" stroke="currentColor"
             strokeWidth="1.3" strokeLinecap="round" strokeLinejoin="round">
          {GLYPHS[glyph]}
        </svg>
      </span>
      <p className="mt-3 text-[13px] font-medium text-slate-200">{title}</p>
      {hint && <p className="mx-auto mt-1.5 max-w-sm text-xs leading-relaxed text-slate-500">{hint}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
  );
}

/* ------------------------------------------------------------------ *
 * Workspace layout
 * ------------------------------------------------------------------ */

/**
 * List on the left, the thing you picked on the right.
 *
 * <p>Replaces the accordion. An accordion answers "show me this one" by pushing
 * everything below it off the screen, so comparing two pipelines means opening,
 * scrolling, closing, scrolling, opening. Here the list never moves and the
 * detail is always in the same place — which is also why the eye can go straight
 * to it rather than hunting for where the page expanded.
 *
 * <p>Below the breakpoint it stacks: on a phone there is only ever room for one
 * of the two, and a 240px column beside a detail pane is neither.
 */
export function Split({ list, detail }: { list: ReactNode; detail: ReactNode }) {
  return (
    <div className="grid items-start gap-x-10 gap-y-8 lg:grid-cols-[minmax(210px,254px)_minmax(0,1fr)]">
      <div className="min-w-0">{list}</div>
      <div className="min-w-0">{detail}</div>
    </div>
  );
}

/**
 * One control for a set of exclusive views.
 *
 * <p>The pages this replaces stacked every configuration area vertically inside
 * a bordered box inside a card, so the shape of the page said "five equally
 * important things" when in practice you are doing exactly one of them. A
 * segmented control says that: one at a time, and you can see the others exist.
 */
export function Segmented<T extends string>({
  value,
  onChange,
  options,
}: {
  value: T;
  onChange: (v: T) => void;
  options: { value: T; label: string; badge?: ReactNode }[];
}) {
  return (
    <div role="tablist" className="flex flex-wrap items-center gap-x-5 gap-y-1 border-b border-edge/60">
      {options.map((o) => {
        const on = o.value === value;
        return (
          <button
            key={o.value}
            role="tab"
            aria-selected={on}
            onClick={() => onChange(o.value)}
            className={`relative -mb-px flex items-center gap-1.5 py-2 text-[12.5px] transition-colors ${
              on ? "text-slate-100" : "text-slate-500 hover:text-slate-300"
            }`}
          >
            {o.label}
            {o.badge}
            {/* The indicator is a rule under the live tab, not a filled pill.
                A pill is the same shape as a button and invites a second click. */}
            <span
              className="absolute inset-x-0 -bottom-px h-[1.5px] transition-opacity duration-200"
              style={{ background: "var(--accent)", opacity: on ? 1 : 0 }}
              aria-hidden
            />
          </button>
        );
      })}
    </div>
  );
}

/**
 * A word about a state, in its own colour, on its own tint.
 *
 * <p>The rule that keeps pills from becoming badge soup: a pill is for a
 * <em>state</em> — live, failed, degraded, cached — never for a category. A
 * category is what the mark and the words are for.
 */
export function Pill({
  tone = "mute",
  dot = false,
  children,
}: {
  tone?: Tone;
  dot?: boolean;
  children: ReactNode;
}) {
  return (
    <span
      className="inline-flex items-center gap-1.5 whitespace-nowrap rounded-full px-2 py-[3px] text-[10.5px] font-medium"
      style={{ background: toneWash(tone), color: toneInk(tone) }}
    >
      {dot && (
        <span
          className="h-1.5 w-1.5 rounded-full"
          style={{ background: "currentColor" }}
          aria-hidden
        />
      )}
      {children}
    </span>
  );
}

/**
 * The shape of a number's recent history, drawn small.
 *
 * <p>A figure on its own answers "what is it"; the same figure with its last
 * dozen readings under it answers "and is that normal", which is the question
 * anyone opening a console actually has. No axes: at this size a scale would be
 * unreadable, and the sparkline is deliberately making a claim about shape
 * rather than about value.
 */
export function Spark({
  points,
  tone = "info",
  height = 34,
}: {
  points: number[];
  tone?: Tone;
  height?: number;
}) {
  const lo = Math.min(...points);
  const hi = Math.max(...points);
  // A flat series has no shape to show. Drawing it anyway puts a hard rule
  // across the bottom of the card that reads as a broken chart rather than as
  // "nothing has happened", so it draws nothing instead.
  if (points.length < 2 || hi === lo) return null;
  const w = 100;
  const span = hi - lo || 1;
  const y = (v: number) => 2 + (1 - (v - lo) / span) * (height - 4);
  const x = (i: number) => (i / (points.length - 1)) * w;
  const line = points.map((v, i) => `${i === 0 ? "M" : "L"}${x(i).toFixed(2)} ${y(v).toFixed(2)}`).join(" ");
  const id = `sp${tone}${points.length}${Math.round(lo)}${Math.round(hi)}`;
  const colour = toneInk(tone);
  return (
    <svg
      viewBox={`0 0 ${w} ${height}`}
      preserveAspectRatio="none"
      width="100%"
      height={height}
      aria-hidden
      className="block"
    >
      <defs>
        <linearGradient id={id} x1="0" y1="0" x2="0" y2="1">
          <stop offset="0%" stopColor={colour} stopOpacity="0.22" />
          <stop offset="100%" stopColor={colour} stopOpacity="0" />
        </linearGradient>
      </defs>
      <path d={`${line} L${w} ${height} L0 ${height} Z`} fill={`url(#${id})`} />
      <path d={line} fill="none" stroke={colour} strokeWidth="1.5" vectorEffect="non-scaling-stroke" />
    </svg>
  );
}

/**
 * A number, its mark, its history and its direction — one card.
 *
 * <p>This is the console's headline unit. The earlier objection to stat cards
 * stands and is answered here: the reason a band of four bordered figures gets
 * skipped is that it carries a label and a number and nothing else, so there is
 * nothing in it to look at twice. A tinted mark makes it findable, the sparkline
 * gives the figure a shape, and the delta says whether to care.
 */
export function Stat({
  label,
  value,
  unit,
  tone,
  hint,
  glyph,
  series,
  delta,
  deltaNote,
}: {
  label: string;
  value: ReactNode;
  unit?: string;
  tone?: Tone;
  hint?: string;
  glyph?: GlyphName;
  /** Recent readings, oldest first. Drawn as a sparkline across the card foot. */
  series?: number[];
  /** Signed change. Positive draws up, negative down; the colour comes from
      {@code tone} rather than the sign, because up is not always good. */
  delta?: number;
  deltaNote?: string;
}) {
  // Only a *state* colours the figure. A hue is identity — it belongs on the
  // mark and the line, and eight differently-coloured numbers across a band
  // would read as eight different severities.
  const stateTone = tone && !HUES.includes(tone as Hue) ? tone : undefined;
  const colour = stateTone && stateTone !== "mute" ? toneInk(stateTone) : undefined;
  return (
    <Card className="flex min-w-0 flex-col" pad={false}>
      <div className="flex items-start gap-3 p-4 pb-3">
        <div className="min-w-0 flex-1">
          <div className="micro truncate" title={hint}>
            {label}
          </div>
          <div
            className="readout mt-1.5 text-[24px] leading-none tracking-tight text-slate-100"
            style={colour ? { color: colour } : undefined}
          >
            {value}
            {unit && <span className="ml-1 text-[12px] text-slate-500">{unit}</span>}
          </div>
        </div>
        {glyph ? (
          <Chip glyph={glyph} tone={tone ?? "info"} />
        ) : (
          // No glyph chosen: a plain swatch still gives the card a mark to be
          // found by, without inventing an icon that means the wrong thing.
          <span
            aria-hidden
            className="mt-0.5 h-[18px] w-[18px] shrink-0 rounded-[6px]"
            style={{ background: toneWash(tone ?? "mute"), boxShadow: `inset 0 0 0 2px ${toneInk(tone ?? "mute")}` }}
          />
        )}
      </div>
      {series && series.length > 1 && (
        <div className="-mt-1">
          <Spark points={series} tone={tone ?? "info"} />
        </div>
      )}
      {delta !== undefined && (
        <div className="flex items-baseline gap-1.5 px-4 pb-3 pt-2 text-[11px]">
          <span style={{ color: toneInk(delta >= 0 ? "ok" : "bad") }}>
            {delta >= 0 ? "↗" : "↘"} {delta >= 0 ? "+" : ""}
            {delta}%
          </span>
          {deltaNote && <span className="truncate text-slate-500">{deltaNote}</span>}
        </div>
      )}
    </Card>
  );
}

/**
 * A run of {@link Stat} cards.
 *
 * <p>Any child that has not chosen a tone is given one from the hue order, by
 * position. That is what stops a page of figures coming out monochrome without
 * making every caller think about colour: a card that means something specific
 * says so, and the rest are simply told apart.
 */
export function Stats({ children, cols = 4 }: { children: ReactNode; cols?: 2 | 3 | 4 }) {
  let i = 0;
  const painted = Children.map(children, (child) => {
    if (!isValidElement(child)) return child;
    const props = child.props as { tone?: Tone };
    const hue = hueAt(i++);
    return props.tone === undefined ? cloneElement(child, { tone: hue } as never) : child;
  });
  return <Grid cols={cols}>{painted}</Grid>;
}

/**
 * A labelled bar with its own caption — the rollout form.
 *
 * <p>Name on the left, state on the right, the bar under both, and the figure
 * at the end of the bar rather than in a column somewhere else.
 */
export function Progress({
  label,
  sub,
  fraction,
  tone = "info",
  status,
  caption,
}: {
  label: ReactNode;
  sub?: ReactNode;
  fraction: number;
  tone?: Tone;
  status?: ReactNode;
  caption?: ReactNode;
}) {
  const pct = Math.min(1, Math.max(0, fraction)) * 100;
  return (
    <Card>
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="truncate text-[12.5px] font-medium text-slate-100">{label}</div>
          {sub && <div className="mt-0.5 truncate text-[11px] text-slate-500">{sub}</div>}
        </div>
        {status}
      </div>
      <div className="mt-3 h-1.5 overflow-hidden rounded-full bg-edge">
        <div
          className="h-full rounded-full transition-[width] duration-500 ease-out"
          style={{ width: pct === 0 ? 0 : `max(3px, ${pct}%)`, background: toneInk(tone) }}
        />
      </div>
      <div className="mt-1.5 text-right text-[10.5px] text-slate-500">
        {caption ?? `${Math.round(pct)}% complete`}
      </div>
    </Card>
  );
}

/**
 * Something that happened and may need attention.
 *
 * <p>A rail down the leading edge in the tone's colour, and the faintest wash
 * behind it. The rail is what lets a column of these be triaged without reading
 * any of them — the shape of the column tells you how bad the day is.
 */
export function Notice({
  tone = "info",
  title,
  meta,
  body,
  right,
}: {
  tone?: Tone;
  title: ReactNode;
  meta?: ReactNode;
  body?: ReactNode;
  right?: ReactNode;
}) {
  return (
    <div
      className="rounded-xl border border-l-[3px] p-3.5 shadow-card"
      style={{
        background: toneWash(tone),
        borderColor: "rgb(var(--card-edge))",
        borderLeftColor: toneInk(tone),
      }}
    >
      <div className="flex items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span style={{ color: toneInk(tone) }} aria-hidden className="grid place-items-center">
              <svg width="13" height="13" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
                {GLYPHS[tone === "ok" ? "check" : tone === "bad" || tone === "warn" ? "alert" : "activity"]}
              </svg>
            </span>
            <span className="truncate text-[12.5px] font-semibold text-slate-100">{title}</span>
          </div>
          {meta && <div className="mt-1 text-[11px] leading-relaxed text-slate-500">{meta}</div>}
        </div>
        {right && <div className="shrink-0 text-[11px]">{right}</div>}
      </div>
      {body && <p className="mt-2 text-[12px] leading-relaxed text-slate-400">{body}</p>}
    </div>
  );
}

/* ------------------------------------------------------------------ *
 * Panels with something in them
 * ------------------------------------------------------------------ */

export type Slice = { key: string; label: string; value: number; tone?: Tone; note?: string };

/**
 * A ring with the headline in its hole, and the parts named beside it.
 *
 * <p>The ring answers "roughly what proportion", which is all a ring is good
 * for; the bars beside it answer "which one and how much", which a ring is bad
 * at. Together they are the allocation panel from the reference — and neither
 * half is decorative, because removing either loses a real question.
 */
export function Allocation({
  slices,
  centre,
  centreLabel,
  unit,
}: {
  slices: Slice[];
  centre: string;
  centreLabel: string;
  unit?: string;
}) {
  const total = slices.reduce((n, s) => n + s.value, 0) || 1;
  const R = 46;
  const C = 2 * Math.PI * R;
  let at = 0;
  return (
    <div className="flex flex-wrap items-center gap-x-6 gap-y-5">
      <div className="relative shrink-0" style={{ width: 132, height: 132 }}>
        <svg viewBox="0 0 120 120" width="132" height="132" aria-hidden>
          <circle cx="60" cy="60" r={R} fill="none" stroke="rgb(var(--edge))" strokeWidth="13" />
          {slices.map((s, i) => {
            const frac = s.value / total;
            const dash = `${Math.max(0, frac * C - 2)} ${C}`;
            const el = (
              <circle
                key={s.key}
                cx="60"
                cy="60"
                r={R}
                fill="none"
                stroke={toneInk(s.tone ?? hueAt(i))}
                strokeWidth="13"
                strokeLinecap="round"
                strokeDasharray={dash}
                strokeDashoffset={-at * C}
                transform="rotate(-90 60 60)"
                className="transition-all duration-500 ease-out"
              />
            );
            at += frac;
            return el;
          })}
        </svg>
        <div className="pointer-events-none absolute inset-0 flex flex-col items-center justify-center">
          <span className="readout text-[19px] leading-none tracking-tight text-slate-100">
            {centre}
          </span>
          <span className="mt-1 text-[10px] text-slate-500">{centreLabel}</span>
        </div>
      </div>

      <ul className="min-w-[190px] flex-1 space-y-2.5">
        {slices.map((s, i) => {
          const colour = toneInk(s.tone ?? hueAt(i));
          return (
            <li key={s.key}>
              <div className="flex items-baseline justify-between gap-3 text-[11.5px]">
                <span className="truncate text-slate-300">{s.label}</span>
                <span className="readout shrink-0 text-slate-400">
                  {s.note ?? `${s.value.toLocaleString()}${unit ? ` ${unit}` : ""}`}
                </span>
              </div>
              <div className="mt-1 h-1.5 overflow-hidden rounded-full bg-edge">
                <div
                  className="h-full rounded-full transition-[width] duration-500 ease-out"
                  style={{
                    width: `${Math.max(2, (s.value / total) * 100)}%`,
                    background: colour,
                  }}
                />
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

/** Named rows with a bar each — the "distributed training nodes" panel. */
export function BarList({
  items,
}: {
  items: { key: string; label: string; note?: string; fraction: number; tone?: Tone }[];
}) {
  return (
    <ul className="space-y-3">
      {items.map((it, i) => (
        <li key={it.key}>
          <div className="flex items-baseline justify-between gap-3">
            <span className="truncate text-[12px] text-slate-200">{it.label}</span>
            {it.note && <span className="readout shrink-0 text-[11px] text-slate-500">{it.note}</span>}
          </div>
          <div className="mt-1.5 h-1.5 overflow-hidden rounded-full bg-edge">
            <div
              className="h-full rounded-full transition-[width] duration-500 ease-out"
              style={{
                width: `${Math.max(2, Math.min(1, it.fraction) * 100)}%`,
                background: toneInk(it.tone ?? hueAt(i)),
              }}
            />
          </div>
        </li>
      ))}
    </ul>
  );
}

/**
 * One line in a live feed: what happened, to what, and how bad.
 *
 * <p>The severity word is a pill because it is the one part that gets scanned
 * rather than read — a column of them is triage without reading a sentence.
 */
export function Event({
  tone = "info",
  title,
  meta,
  when,
  badge,
}: {
  tone?: Tone;
  title: ReactNode;
  meta?: ReactNode;
  when?: ReactNode;
  badge?: string;
}) {
  return (
    <li
      className="flex items-start gap-3 rounded-lg px-2.5 py-2.5 transition-colors hover:bg-slate-500/[0.05]"
    >
      <span
        aria-hidden
        className="mt-[3px] h-1.5 w-1.5 shrink-0 rounded-full"
        style={{ background: toneInk(tone) }}
      />
      <div className="min-w-0 flex-1">
        <div className="truncate text-[12px] text-slate-200">{title}</div>
        {meta && <div className="mt-0.5 truncate text-[11px] text-slate-500">{meta}</div>}
      </div>
      <div className="flex shrink-0 items-center gap-2">
        {when && <span className="readout text-[10.5px] text-slate-600">{when}</span>}
        {badge && <Pill tone={tone}>{badge}</Pill>}
      </div>
    </li>
  );
}

/** The container for {@link Event}s, with the live marker in its head. */
export function Feed({ children }: { children: ReactNode }) {
  return <ul className="-mx-1 max-h-[300px] space-y-0.5 overflow-y-auto">{children}</ul>;
}

/**
 * A grid of cells shaded by value.
 *
 * <p>Sequential, one hue: the cell's job is "more or less than its neighbour",
 * and giving each row its own colour would spend the identity channel on
 * something position already encodes.
 */
export function Heat({
  rows,
  cols,
  tone = "violet",
}: {
  rows: { label: string; values: number[] }[];
  cols?: string[];
  tone?: Tone;
}) {
  const max = Math.max(1, ...rows.flatMap((r) => r.values));
  const colour = toneInk(tone);
  return (
    <div className="overflow-x-auto">
      <div className="min-w-[260px]">
        {rows.map((r) => (
          <div key={r.label} className="mb-1 flex items-center gap-2">
            <span className="w-16 shrink-0 truncate text-[10.5px] text-slate-500">{r.label}</span>
            <div className="flex flex-1 gap-1">
              {r.values.map((v, i) => (
                <span
                  key={i}
                  title={`${r.label} · ${v}`}
                  className="h-5 flex-1 rounded-[3px] transition-colors duration-300"
                  style={{
                    background: v === 0 ? "rgb(var(--edge))" : colour,
                    opacity: v === 0 ? 1 : 0.22 + (v / max) * 0.78,
                  }}
                />
              ))}
            </div>
          </div>
        ))}
        {cols && (
          <div className="mt-1 flex items-center gap-2">
            <span className="w-16 shrink-0" />
            <div className="flex flex-1 gap-1">
              {cols.map((c) => (
                <span key={c} className="flex-1 text-center text-[9.5px] text-slate-600">
                  {c}
                </span>
              ))}
            </div>
          </div>
        )}
      </div>
    </div>
  );
}

/**
 * A proportional bar.
 *
 * <p>Non-zero always draws at least three pixels. A bar that rounds a real value
 * down to nothing says "none" when the answer is "a little", and that is the one
 * error a bar chart must not make.
 */
export function Bar({
  fraction,
  tone = "accent",
  width = 84,
}: {
  fraction: number;
  tone?: "accent" | "ok" | "warn" | "bad" | "mute";
  width?: number;
}) {
  const pct = Math.min(1, Math.max(0, fraction)) * 100;
  const colour = {
    accent: "var(--accent)",
    ok: "var(--state-healthy-ink)",
    warn: "var(--state-warning-ink)",
    bad: "var(--state-critical-ink)",
    mute: "var(--series-mute)",
  }[tone];
  return (
    <span
      className="inline-block shrink-0 overflow-hidden rounded-full align-middle"
      style={{ width, height: 5, background: "rgb(var(--edge))" }}
      aria-hidden
    >
      <span
        className="block h-full rounded-full transition-[width] duration-500 ease-out"
        style={{ width: pct === 0 ? 0 : `max(3px, ${pct}%)`, background: colour }}
      />
    </span>
  );
}

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
export function Spine({ children }: { children: ReactNode }) {
  return <ol className="relative ml-[7px] border-l border-edge pl-6">{children}</ol>;
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
}: {
  tone?: "ok" | "warn" | "bad" | "idle" | "accent" | "skipped";
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
      className="relative py-1.5 transition-all duration-300"
      style={{
        opacity: revealed ? 1 : 0,
        transform: revealed ? "none" : "translateY(4px)",
        transitionDelay: `${index * 20}ms`,
      }}
    >
      {/* The node sits on the rail, half outside the padding box. Hollow when
          the step was skipped: an outline reads as "this position exists and
          nothing happened in it", which is exactly what a skip is. */}
      <span
        className="absolute -left-[29px] top-[13px] h-[9px] w-[9px] rounded-full"
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
