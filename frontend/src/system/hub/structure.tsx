import { InfoTip } from "../primitives";
import type { ReactNode } from "react";
import { Tone } from "./tone";
import { Chip, GlyphName } from "./glyphs";

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
      // Only a card you can open behaves like one: lifts toward the cursor,
      // catches its light, gives under a press. A read-only card doing the same
      // would be claiming to be clickable.
      data-surface={interactive ? "" : undefined}
      data-selected={selected ? "" : undefined}
      className={`card relative border bg-card shadow-card ${selected ? "" : "border-card-edge"} ${
        pad ? "p-4" : ""
      } ${interactive ? "cursor-pointer hover:border-slate-500/40 hover:bg-[color:rgb(var(--card-hover))]" : ""} ${className}`}
      style={{
        borderRadius: "var(--r-lg)",
        borderColor: selected ? "var(--accent-edge)" : undefined,
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
        <h2 className="truncate text-[13px] font-semibold tracking-tight text-slate-100">
          {title}
        </h2>
        {sub && <p className="mt-0.5 truncate text-[11.5px] leading-relaxed text-slate-500">{sub}</p>}
      </div>
      {right && <div className="flex shrink-0 items-center gap-2">{right}</div>}
    </div>
  );
}

/** A responsive run of cards. Two up by default, three or four when asked. */
export function Grid({ cols = 2, children }: { cols?: 2 | 3 | 4 | 5; children: ReactNode }) {
  const at = {
    2: "sm:grid-cols-2",
    3: "sm:grid-cols-2 lg:grid-cols-3",
    4: "sm:grid-cols-2 lg:grid-cols-4",
    5: "sm:grid-cols-3 lg:grid-cols-5",
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
          {/* The section's explanation sits behind the title, not under it. */}
          {hint && <InfoTip text={hint} className="self-center" />}
        </h2>
        {action}
      </div>
      <div className="mt-3">{children}</div>
    </section>
  );
}

/** Rows, hairline-separated, on the card plane. */
export function Rail({ children }: { children: ReactNode }) {
  return (
    <div
      className="card overflow-hidden border border-card-edge bg-card shadow-card"
      style={{ borderRadius: "var(--r-lg)" }}
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
  // The row's title is the button — a real one, reached by Tab and announced
  // as such — and the rest of the row is a larger mouse target for it. The row
  // itself used to be role="button", which cannot contain the row's own action
  // buttons: a screen reader flattened them into one control, or skipped them.
  return (
    <div
      onClick={onClick}
      // A row you can open is a surface: it catches the cursor's light and gives
      // under a press. It does not lift — rows sit edge to edge in a list, and
      // one rising a pixel would jostle its neighbours.
      data-surface={interactive ? "row" : undefined}
      className={`group relative flex items-center gap-3.5 px-3 py-2.5 ${
        interactive ? "cursor-pointer" : ""
      } ${selected ? "bg-accent-wash" : "hover:bg-slate-500/[0.055]"}`}
      style={selected ? { background: "var(--accent-wash)" } : undefined}
    >
      {/* The selected marker is a rule on the leading edge, not a filled block.
          It marks position without repainting the row, and grows from the
          row's middle on the elastic spring — the selection lands. */}
      {selected && (
        <span
          className="grow-y absolute inset-y-0 left-0 w-[2px]"
          style={{ background: "var(--accent)" }}
          aria-hidden
        />
      )}
      {mark}
      <div className="min-w-0 flex-1">
        <div className="flex items-baseline gap-2">
          {interactive ? (
            <button
              type="button"
              aria-current={selected ? "true" : undefined}
              onClick={(e) => {
                e.stopPropagation();
                onClick!();
              }}
              className="truncate rounded-[4px] text-left text-[13.5px] font-medium text-slate-100 focus:outline-none focus-visible:shadow-[var(--ring)]"
              data-no-press
            >
              {title}
            </button>
          ) : (
            <span className="truncate text-[13.5px] font-medium text-slate-100">{title}</span>
          )}
          {status}
        </div>
        {subtitle && (
          // One step brighter on a selected row: the accent wash under it lifts
          // the background, and slate-500 drops to 4.1:1 there.
          <p className={`mt-0.5 truncate text-xs leading-relaxed ${selected ? "text-slate-400" : "text-slate-500"}`}>
            {subtitle}
          </p>
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
