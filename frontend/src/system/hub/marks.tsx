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
