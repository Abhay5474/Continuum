import { useEffect, useRef, useState } from "react";
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
 * Structure
 * ------------------------------------------------------------------ */

/**
 * A section, separated by type and space rather than by a box.
 *
 * <p>Every section having its own bordered panel is what makes a page read as a
 * database dump: the border says "these things are the same kind of thing" and
 * when every section has one, nothing is ranked.
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

/** Rows, hairline-separated. The container has no border of its own. */
export function Rail({ children }: { children: ReactNode }) {
  return <div className="divide-y divide-edge/50 border-y border-edge/50">{children}</div>;
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
  actions,
  onClick,
  selected = false,
}: {
  mark?: ReactNode;
  title: ReactNode;
  subtitle?: ReactNode;
  meta?: ReactNode;
  status?: ReactNode;
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
  const tint =
    tone === "accent"
      ? { color: "var(--accent-ink)", borderColor: "var(--accent-edge)" }
      : tone === "danger"
        ? { color: "var(--state-critical-ink)" }
        : undefined;
  return (
    <button
      onClick={(e) => {
        e.stopPropagation();
        onClick?.();
      }}
      disabled={disabled}
      title={title}
      style={tint}
      className="rounded-md border border-edge/70 px-2 py-1 text-[11.5px] text-slate-400 transition-colors hover:border-slate-500/50 hover:text-slate-100 disabled:opacity-40"
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
      style={{ background: "var(--accent-strong)" }}
      className="rounded-md px-3 py-1.5 text-[12.5px] font-medium text-white transition-opacity hover:opacity-90 disabled:opacity-40"
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
          color: connected ? "var(--accent-ink)" : "rgb(100 116 139)",
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

/** Nothing here yet, said quietly. */
export function Empty({ title, hint, action }: { title: string; hint?: string; action?: ReactNode }) {
  return (
    <div className="px-3 py-10 text-center">
      <p className="text-[13px] text-slate-400">{title}</p>
      {hint && <p className="mx-auto mt-1 max-w-md text-xs leading-relaxed text-slate-600">{hint}</p>}
      {action && <div className="mt-4">{action}</div>}
    </div>
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
