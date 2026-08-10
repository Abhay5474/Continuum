import type { ReactNode, SelectHTMLAttributes, InputHTMLAttributes, TextareaHTMLAttributes } from "react";
import { toneInk, toneWash, type Tone } from "./hub";

/**
 * Controls.
 *
 * <p>Everything a person can click, type into or choose from. Split out from the
 * display vocabulary because the failure modes are different: a card that is a
 * few pixels off is untidy, a control that is a few pixels off is the single
 * loudest signal that an interface was assembled rather than designed.
 *
 * <p>The rules these enforce, which the ad-hoc versions across the app did not:
 *
 * <ul>
 *   <li><b>One geometry.</b> Every control at a given size is exactly the same
 *       height and radius, from the same tokens, so a button beside a select
 *       beside an input lines up on both edges.</li>
 *   <li><b>One focus ring.</b> Drawn outside the control, identical everywhere,
 *       and only for keyboard focus. Ad-hoc buttons had no focus style at all,
 *       which is a keyboard user losing their place forty times a page.</li>
 *   <li><b>Emphasis is a variant, not a colour.</b> There is one primary action
 *       per view. Making that a named variant is what stops a screen growing
 *       four equally loud buttons.</li>
 *   <li><b>Disabled says why.</b> Not just dimmed — {@code title} carries the
 *       reason, because a control that is dead with no explanation reads as a
 *       broken page.</li>
 * </ul>
 */

/* ------------------------------------------------------------------ *
 * Button
 * ------------------------------------------------------------------ */

export type ButtonVariant = "primary" | "secondary" | "ghost" | "danger";
export type ControlSize = "sm" | "md" | "lg";

const HEIGHT: Record<ControlSize, string> = {
  sm: "var(--h-sm)",
  md: "var(--h-md)",
  lg: "var(--h-lg)",
};

const PAD: Record<ControlSize, string> = {
  sm: "px-2 text-[11.5px]",
  md: "px-3 text-[12.5px]",
  lg: "px-4 text-[13px]",
};

export function Button({
  children,
  onClick,
  variant = "secondary",
  size = "md",
  disabled,
  busy = false,
  title,
  type = "button",
  icon,
  full = false,
  className = "",
}: {
  children?: ReactNode;
  onClick?: () => void;
  variant?: ButtonVariant;
  size?: ControlSize;
  disabled?: boolean;
  /** Shows a spinner and blocks the click, without changing the button's width. */
  busy?: boolean;
  /** Also the place to say *why* a disabled button is disabled. */
  title?: string;
  type?: "button" | "submit";
  icon?: ReactNode;
  full?: boolean;
  className?: string;
}) {
  const off = disabled || busy;

  // Colour is stated inline for the filled variants. The light theme remaps
  // .text-white to near-black so labels survive on paper, and it can only
  // exempt filled buttons by matching a background *class* — which these do not
  // have, because their background is a variable.
  const style: Record<string, string> = {
    height: HEIGHT[size],
    borderRadius: "var(--r-md)",
  };
  let tone = "";
  if (variant === "primary") {
    style.background = "var(--accent-strong)";
    style.color = "var(--accent-on)";
    tone = "font-medium hover:brightness-110 active:brightness-95";
  } else if (variant === "secondary") {
    style.borderColor = "rgb(var(--card-edge))";
    style.background = "rgb(var(--card))";
    tone =
      "border font-medium text-slate-200 hover:border-slate-500/60 hover:bg-[color:rgb(var(--card-hover))] active:brightness-95";
  } else if (variant === "danger") {
    style.color = "var(--state-critical-ink)";
    style.borderColor = "var(--state-critical-ink)";
    style.background = "var(--wash-bad)";
    tone = "border font-medium hover:brightness-110";
  } else {
    tone = "text-slate-400 hover:bg-slate-500/10 hover:text-slate-100";
  }

  return (
    <button
      type={type}
      onClick={(e) => {
        e.stopPropagation();
        if (!off) onClick?.();
      }}
      disabled={off}
      title={title}
      aria-busy={busy || undefined}
      style={style}
      className={`inline-flex shrink-0 items-center justify-center gap-1.5 whitespace-nowrap transition-[background-color,border-color,filter,opacity] duration-150 disabled:cursor-not-allowed disabled:opacity-45 ${
        PAD[size]
      } ${tone} ${full ? "w-full" : ""} ${className}`}
    >
      {busy ? <Spinner /> : icon}
      {children}
    </button>
  );
}

/** A one-glyph button. Square, so a row of them is a row rather than a ladder. */
export function IconButton({
  children,
  onClick,
  label,
  size = "md",
  disabled,
  variant = "ghost",
}: {
  children: ReactNode;
  onClick?: () => void;
  /** Required: an icon with no accessible name is invisible to a screen reader. */
  label: string;
  size?: ControlSize;
  disabled?: boolean;
  variant?: ButtonVariant;
}) {
  const px = { sm: 26, md: 30, lg: 36 }[size];
  return (
    <button
      type="button"
      onClick={(e) => {
        e.stopPropagation();
        onClick?.();
      }}
      disabled={disabled}
      title={label}
      aria-label={label}
      style={{ width: px, height: px, borderRadius: "var(--r-md)" }}
      className={`inline-flex shrink-0 items-center justify-center transition-colors duration-150 disabled:cursor-not-allowed disabled:opacity-45 ${
        variant === "secondary"
          ? "border border-card-edge bg-card text-slate-300 hover:border-slate-500/60"
          : "text-slate-400 hover:bg-slate-500/10 hover:text-slate-100"
      }`}
    >
      {children}
    </button>
  );
}

function Spinner() {
  return (
    <svg width="12" height="12" viewBox="0 0 12 12" fill="none" aria-hidden className="animate-spin">
      <circle cx="6" cy="6" r="4.5" stroke="currentColor" strokeWidth="1.6" opacity="0.25" />
      <path d="M10.5 6A4.5 4.5 0 0 0 6 1.5" stroke="currentColor" strokeWidth="1.6" strokeLinecap="round" />
    </svg>
  );
}

/* ------------------------------------------------------------------ *
 * Fields
 * ------------------------------------------------------------------ */

/**
 * The label-over-control wrapper.
 *
 * <p>A real {@code <label>}, so the hit target includes the words. Optional
 * {@code hint} sits under the control rather than over it — a hint above pushes
 * the control off the line its neighbours share.
 */
export function Labelled({
  label,
  hint,
  htmlFor,
  children,
  className = "",
}: {
  label: string;
  hint?: ReactNode;
  htmlFor?: string;
  children: ReactNode;
  className?: string;
}) {
  return (
    <div className={`min-w-0 ${className}`}>
      <label htmlFor={htmlFor} className="micro block truncate">
        {label}
      </label>
      <div className="mt-1.5">{children}</div>
      {hint && <p className="mt-1.5 max-w-xs text-[11px] leading-relaxed text-slate-500">{hint}</p>}
    </div>
  );
}

const FIELD_BASE =
  "w-full border bg-transparent text-slate-100 outline-none transition-colors duration-150 " +
  "placeholder:text-slate-500 disabled:cursor-not-allowed disabled:opacity-50 " +
  "hover:border-slate-500/50 focus:border-[color:var(--accent-edge)]";

/**
 * A styled native select.
 *
 * <p>Native on purpose. A listbox rebuilt out of divs loses keyboard type-ahead,
 * the platform picker on a phone, and every screen-reader affordance — and the
 * only thing wrong with the real element is its chevron, which is replaced with
 * a drawn one in CSS. Twenty-four of these across the console were the single
 * loudest unfinished detail in the interface.
 */
export function Select({
  size = "md",
  className = "",
  ...rest
}: Omit<SelectHTMLAttributes<HTMLSelectElement>, "size"> & { size?: ControlSize }) {
  return (
    <select
      {...rest}
      style={{
        height: HEIGHT[size],
        borderRadius: "var(--r-md)",
        borderColor: "rgb(var(--card-edge))",
        // `background` (the shorthand) would reset the drawn chevron that
        // .control-select paints as a background-image. Only the colour.
        backgroundColor: "rgb(var(--card))",
      }}
      className={`control-select ${FIELD_BASE} ${PAD[size]} ${className}`}
    />
  );
}

export function Input({
  size = "md",
  className = "",
  ...rest
}: Omit<InputHTMLAttributes<HTMLInputElement>, "size"> & { size?: ControlSize }) {
  return (
    <input
      {...rest}
      style={{ height: HEIGHT[size], borderRadius: "var(--r-md)", borderColor: "rgb(var(--card-edge))", background: "rgb(var(--card))" }}
      className={`${FIELD_BASE} ${PAD[size]} ${className}`}
    />
  );
}

export function Textarea({
  className = "",
  ...rest
}: TextareaHTMLAttributes<HTMLTextAreaElement>) {
  return (
    <textarea
      {...rest}
      style={{ borderRadius: "var(--r-md)", borderColor: "rgb(var(--card-edge))", background: "rgb(var(--card))" }}
      className={`${FIELD_BASE} px-3 py-2 font-mono text-[12px] leading-relaxed ${className}`}
    />
  );
}

/* ------------------------------------------------------------------ *
 * Table
 * ------------------------------------------------------------------ */

/**
 * The data table.
 *
 * <p>Three rules, all of which the hand-rolled tables broke somewhere:
 * numbers are right-aligned and tabular so a column can be compared down its
 * own edge; the header is sticky, because a table you have to scroll is a table
 * whose headings you have lost; and rows are separated by a hairline lighter
 * than the card's own border, so the table reads as contents rather than as a
 * grid of boxes.
 */
export function Table({
  head,
  children,
  minWidth,
  maxHeight,
}: {
  head: ReactNode;
  children: ReactNode;
  minWidth?: number;
  maxHeight?: number;
}) {
  return (
    <div
      className="overflow-auto rounded-[var(--r-lg)] border"
      style={{ borderColor: "rgb(var(--card-edge))", background: "rgb(var(--card))", maxHeight }}
    >
      <table className="w-full border-collapse text-left" style={{ minWidth }}>
        <thead
          className="sticky top-0 z-10"
          style={{ background: "rgb(var(--card))", boxShadow: "inset 0 -1px 0 rgb(var(--card-rule))" }}
        >
          {head}
        </thead>
        <tbody>{children}</tbody>
      </table>
    </div>
  );
}

/* Written out, because Tailwind extracts class names from source text and a
   template-built `text-${align}` is never compiled into the stylesheet. */
const ALIGN = { left: "text-left", right: "text-right", center: "text-center" } as const;

export function TH({
  children,
  align = "left",
  width,
}: {
  children?: ReactNode;
  align?: "left" | "right" | "center";
  width?: number | string;
}) {
  return (
    <th
      scope="col"
      style={{ width }}
      className={`whitespace-nowrap px-3 py-2 text-[10px] font-medium uppercase tracking-[0.12em] text-slate-500 ${ALIGN[align]}`}
    >
      {children}
    </th>
  );
}

export function TR({
  children,
  onClick,
  selected = false,
}: {
  children: ReactNode;
  onClick?: () => void;
  selected?: boolean;
}) {
  return (
    <tr
      onClick={onClick}
      className={`border-t transition-colors duration-100 ${
        onClick ? "cursor-pointer" : ""
      } ${selected ? "bg-accent-wash" : "hover:bg-slate-500/[0.045]"}`}
      style={{ borderColor: "rgb(var(--card-rule))" }}
    >
      {children}
    </tr>
  );
}

export function TD({
  children,
  align = "left",
  /** Tabular numerals and a tighter tracking. Use for anything countable. */
  numeric = false,
  muted = false,
  className = "",
}: {
  children?: ReactNode;
  align?: "left" | "right" | "center";
  numeric?: boolean;
  muted?: boolean;
  className?: string;
}) {
  return (
    <td
      className={`px-3 py-2 text-[12px] ${ALIGN[numeric ? "right" : align]} ${
        numeric ? "readout" : ""
      } ${muted ? "text-slate-500" : "text-slate-300"} ${className}`}
    >
      {children}
    </td>
  );
}

/* ------------------------------------------------------------------ *
 * Arrangement
 * ------------------------------------------------------------------ */

/**
 * The row of filters and actions above a table or list.
 *
 * <p>Actions right, filters left, one baseline. The pattern it replaces —
 * controls scattered through the page wherever they were written — is why the
 * same button appeared at three different heights on three different screens.
 */
export function Toolbar({ children, right }: { children?: ReactNode; right?: ReactNode }) {
  return (
    <div className="flex flex-wrap items-center gap-2">
      {children}
      {right && <div className="ml-auto flex flex-wrap items-center gap-2">{right}</div>}
    </div>
  );
}

/** A small status word that is not a state: a count, a mode, a unit. */
export function Tag({ children, tone }: { children: ReactNode; tone?: Tone }) {
  return (
    <span
      className="inline-flex items-center whitespace-nowrap rounded-[var(--r-sm)] px-1.5 py-0.5 text-[10.5px] font-medium"
      style={
        tone
          ? { background: toneWash(tone), color: toneInk(tone) }
          : { background: "var(--wash-mute)", color: "var(--text-3)" }
      }
    >
      {children}
    </span>
  );
}
