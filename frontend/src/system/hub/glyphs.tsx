import type { ReactNode } from "react";
import { Tone, toneInk, toneWash } from "./tone";

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
  | "list"
  | "app"
  | "user"
  | "model"
  | "lock"
  | "filter"
  | "compress"
  | "loop"
  | "undo"
  | "block"
  | "up"
  | "queue"
  | "log"
  | "eye"
  | "scale"
  | "split"
  | "bolt"
  | "doc"
  | "memory"
  | "pause"
  | "target";

export const GLYPHS: Record<GlyphName, ReactNode> = {
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
  // An application window: where requests come from.
  app: (
    <>
      <rect x="1.6" y="2.6" width="12.8" height="10.8" rx="2" />
      <path d="M1.6 5.6h12.8M4 4.1h.01M5.8 4.1h.01" />
    </>
  ),
  user: (
    <>
      <circle cx="8" cy="5.2" r="2.7" />
      <path d="M2.6 14c.6-2.8 2.8-4.3 5.4-4.3s4.8 1.5 5.4 4.3" />
    </>
  ),
  // A model: a node with its connections, not a brain.
  model: (
    <>
      <circle cx="8" cy="8" r="2.3" />
      <circle cx="3" cy="3.4" r="1.3" />
      <circle cx="13" cy="3.4" r="1.3" />
      <circle cx="3" cy="12.6" r="1.3" />
      <circle cx="13" cy="12.6" r="1.3" />
      <path d="m4 4.4 2.3 2.1M12 4.4 9.7 6.5M4 11.6l2.3-2.1M12 11.6 9.7 9.5" />
    </>
  ),
  lock: (
    <>
      <rect x="3" y="7" width="10" height="7.2" rx="1.6" />
      <path d="M5.3 7V5a2.7 2.7 0 0 1 5.4 0v2" />
    </>
  ),
  filter: <path d="M1.8 2.8h12.4L9.4 8.6v4.6l-2.8 1.3V8.6L1.8 2.8Z" />,
  compress: (
    <>
      <path d="M2 5.5h12M2 10.5h12" />
      <path d="m8 1.5 0 3.2m0 0L6.4 3.2M8 4.7l1.6-1.5M8 14.5v-3.2m0 0-1.6 1.5M8 11.3l1.6 1.5" />
    </>
  ),
  loop: (
    <>
      <path d="M13.2 6.2A5.4 5.4 0 0 0 3 5.2M2.8 9.8A5.4 5.4 0 0 0 13 10.8" />
      <path d="M3 2.4v2.8h2.8M13 13.6v-2.8h-2.8" />
    </>
  ),
  undo: (
    <>
      <path d="M5.4 3.4 2.4 6.4l3 3" />
      <path d="M2.6 6.4h7a3.9 3.9 0 0 1 0 7.8H6.5" />
    </>
  ),
  block: (
    <>
      <circle cx="8" cy="8" r="6.3" />
      <path d="m3.6 12.4 8.8-8.8" />
    </>
  ),
  up: <path d="M8 14V2.4M3.4 6.9 8 2.3l4.6 4.6" />,
  queue: (
    <>
      <rect x="1.6" y="5.2" width="3" height="5.6" rx=".8" />
      <rect x="6.5" y="5.2" width="3" height="5.6" rx=".8" />
      <path d="M11.4 8h3.2m-1.4-1.6L14.8 8l-1.6 1.6" />
    </>
  ),
  log: (
    <>
      <rect x="2.4" y="1.8" width="11.2" height="12.4" rx="1.6" />
      <path d="M5 5.2h6M5 8h6M5 10.8h3.6" />
    </>
  ),
  eye: (
    <>
      <path d="M1.4 8S3.8 3.3 8 3.3 14.6 8 14.6 8 12.2 12.7 8 12.7 1.4 8 1.4 8Z" />
      <circle cx="8" cy="8" r="2.1" />
    </>
  ),
  scale: (
    <>
      <path d="M8 2v12M4.2 14h7.6M2.4 4.6h11.2" />
      <path d="m2.4 4.6-1.6 4a2 2 0 0 0 3.2 0l-1.6-4ZM13.6 4.6l-1.6 4a2 2 0 0 0 3.2 0l-1.6-4Z" />
    </>
  ),
  split: (
    <>
      <path d="M1.8 8h4.4" />
      <path d="M6.2 8c2.4 0 3-4.6 6.4-4.6M6.2 8c2.4 0 3 4.6 6.4 4.6" />
      <path d="m11.2 2 1.6 1.4-1.6 1.4M11.2 11.2l1.6 1.4-1.6 1.4" />
    </>
  ),
  bolt: <path d="M8.8 1.5 3 9.2h4L7.2 14.5 13 6.8H9L8.8 1.5Z" />,
  doc: (
    <>
      <path d="M4 1.8h5.4L12.6 5v9.2H4z" />
      <path d="M9.2 1.8V5h3.4M6.2 8.4h4.2M6.2 11h3" />
    </>
  ),
  memory: (
    <>
      <rect x="1.8" y="2.2" width="12.4" height="3.4" rx="1" />
      <rect x="1.8" y="6.3" width="12.4" height="3.4" rx="1" opacity=".7" />
      <rect x="1.8" y="10.4" width="12.4" height="3.4" rx="1" opacity=".45" />
    </>
  ),
  pause: <path d="M5.6 3v10M10.4 3v10" />,
  target: (
    <>
      <circle cx="8" cy="8" r="6.3" />
      <circle cx="8" cy="8" r="3.4" />
      <circle cx="8" cy="8" r=".6" />
    </>
  ),
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
