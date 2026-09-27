import type { ReactNode } from "react";
import { dateTimeOf } from "../time";
import { Code, Field, SidePanel } from "./detail";

/**
 * What one captured event was, opened from its row.
 *
 * <p>Every feature logs what it caught — a redaction, a trip, a shed request,
 * a cache hit — and the log rows used to be the end of the road: a category
 * and a time, and nothing when clicked. This is the other half: every field
 * the event carries, readable, with anything secret-shaped masked.
 *
 * <p>{@code lead} goes first, for the sentence a person actually wants
 * ("the PIN was replaced before the provider saw it"); the rest is the record
 * itself, so nothing the server knows is hidden from its owner.
 */
export function RecordPanel({
  record,
  title,
  subtitle,
  mark,
  lead,
  hide = [],
  onClose,
}: {
  record: Record<string, unknown> | null | undefined;
  title: ReactNode;
  subtitle?: ReactNode;
  mark?: ReactNode;
  lead?: ReactNode;
  /** Keys not worth showing (internal ids, the owner's own account id). */
  hide?: string[];
  onClose: () => void;
}) {
  const skip = new Set([...hide, "developerId", "tenantId", "ownerId"]);
  const entries = record
    ? Object.entries(record).filter(([k, v]) => !skip.has(k) && v !== null && v !== undefined && v !== "")
    : [];
  return (
    <SidePanel open={!!record} title={title} subtitle={subtitle} mark={mark} onClose={onClose}>
      {lead}
      {entries.length > 0 && (
        <div className={lead ? "mt-5 border-t border-edge/60 pt-4" : ""}>
          <div className="micro">Recorded</div>
          <dl className="mt-2 grid grid-cols-[minmax(7rem,auto)_1fr] gap-x-4 gap-y-1.5 text-[12px]">
            {entries.map(([k, v]) => (
              <Entry key={k} name={k} value={v} />
            ))}
          </dl>
        </div>
      )}
    </SidePanel>
  );
}

function Entry({ name, value }: { name: string; value: unknown }) {
  const shown = masked(name, value);
  const block = typeof shown === "object" || (typeof shown === "string" && shown.length > 60);
  return (
    <>
      <dt className="pt-0.5 text-slate-500">{labelOf(name)}</dt>
      <dd className="min-w-0 break-words text-slate-300">
        {block ? (
          <Code>{typeof shown === "string" ? shown : JSON.stringify(shown, null, 2)}</Code>
        ) : (
          render(name, shown)
        )}
      </dd>
    </>
  );
}

/** A long piece of text caught by a feature, shown as it was logged. */
export function Excerpt({ label, text, empty }: { label: string; text?: string | null; empty?: string }) {
  return (
    <Field label={label}>
      {text ? (
        <Code>
          <span className="whitespace-pre-wrap break-words">{text}</span>
        </Code>
      ) : (
        <span className="text-slate-500">{empty ?? "Not recorded for this event."}</span>
      )}
    </Field>
  );
}

const SECRET = /(secret|password|passwd|apikey|api_key|accesstoken|refreshtoken|bearer|ciphertext|credential(?!s?count))/i;

/** Keys that name a secret are masked, whatever the server sent. */
export function masked(name: string, value: unknown): unknown {
  if (SECRET.test(name) && typeof value === "string" && value.length > 0) {
    return "•".repeat(Math.min(8, value.length));
  }
  if (Array.isArray(value)) return value.map((v) => masked("", v));
  if (value && typeof value === "object") {
    const out: Record<string, unknown> = {};
    for (const [k, v] of Object.entries(value as Record<string, unknown>)) out[k] = masked(k, v);
    return out;
  }
  return value;
}

function render(name: string, v: unknown): ReactNode {
  if (typeof v === "boolean") return v ? "yes" : "no";
  if (/(At|Time|time|_at)$/.test(name) || name === "at" || name === "when") {
    const d = dateTimeOf(v, "");
    if (d) return d;
  }
  if (typeof v === "number") {
    if (/ms$|Ms$|latency/i.test(name)) return `${Math.round(v).toLocaleString()} ms`;
    if (/cost|usd/i.test(name)) return `$${v < 0.01 ? v.toPrecision(2) : v.toFixed(4)}`;
    return Number.isInteger(v) ? v.toLocaleString() : String(Math.round(v * 1000) / 1000);
  }
  return String(v);
}

/** camelCase and snake_case to a label: "matchCount" → "Match count". */
export function labelOf(key: string): string {
  const words = key
    .replace(/_/g, " ")
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .toLowerCase()
    .trim();
  return words.charAt(0).toUpperCase() + words.slice(1);
}

/**
 * Props that make a log line open its record: clickable, reachable by Tab,
 * opened with Enter or Space. For rows with no controls of their own — a row
 * that holds buttons uses {@code Row}'s onClick instead.
 */
export function openable(open: () => void, label?: string) {
  return {
    role: "button" as const,
    tabIndex: 0,
    "aria-label": label,
    "data-surface": "row",
    onClick: open,
    onKeyDown: (e: { key: string; preventDefault: () => void }) => {
      if (e.key === "Enter" || e.key === " ") {
        e.preventDefault();
        open();
      }
    },
  };
}
