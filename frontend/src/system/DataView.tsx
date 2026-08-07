import { useMemo, useState } from "react";
import { STATE } from "./tokens";

/**
 * Structured rendering for arbitrary engine payloads.
 *
 * Nothing in the console should ever show `JSON.stringify` output. Model and
 * engine payloads mix scalars, ratios and long markdown-ish prose (headings,
 * rules, fenced ASCII diagrams), and dumping them raw produces escaped `\n`
 * soup that no one can read.
 *
 * So: scalars become labelled readouts, ratios become a figure plus a bar, and
 * long text is rendered as text — with fenced blocks kept monospaced, because an
 * ASCII diagram is genuinely preformatted content.
 */

/** "avgLatencyMs" → "Avg latency ms" */
function formatKey(key: string): string {
  const spaced = key
    .replace(/([a-z0-9])([A-Z])/g, "$1 $2")
    .replace(/[_-]+/g, " ")
    .trim();
  return spaced.charAt(0).toUpperCase() + spaced.slice(1);
}

const RATIO_KEY = /confidence|validity|score|rate|fraction|utility|probability|similarity|weight/i;
const COST_KEY = /cost|usd|price|spend/i;
const MS_KEY = /ms$|latency|duration|elapsed/i;
const TOKEN_KEY = /token/i;
const ID_KEY = /^id$|id$|key$|hash|uuid/i;

/** A number formatted by what its key says it means. */
function formatNumber(key: string, v: number): { text: string; ratio?: number } {
  if (RATIO_KEY.test(key) && v >= 0 && v <= 1) {
    return { text: `${(v * 100).toFixed(1)}%`, ratio: v };
  }
  if (COST_KEY.test(key)) return { text: `$${v.toFixed(6)}` };
  if (MS_KEY.test(key)) return { text: `${Math.round(v)}ms` };
  if (TOKEN_KEY.test(key)) return { text: v.toLocaleString() };
  if (Number.isInteger(v)) return { text: v.toLocaleString() };
  return { text: String(Number(v.toFixed(4))) };
}

function isLongText(v: string) {
  return v.length > 90 || v.includes("\n");
}

/**
 * Markdown-lite. Handles the shapes engine and model output actually use:
 * fenced blocks, ATX headings, horizontal rules, bullets and paragraphs.
 * Deliberately not a full markdown engine — just enough that nothing renders as
 * escape sequences.
 */
function RichText({ text, clamp = 12 }: { text: string; clamp?: number }) {
  const [open, setOpen] = useState(false);

  const blocks = useMemo(() => {
    const out: { kind: "code" | "head" | "rule" | "list" | "p"; body: string }[] = [];
    const parts = text.split(/```/);
    parts.forEach((part, i) => {
      // Odd indices are inside a fence — keep them verbatim and monospaced.
      if (i % 2 === 1) {
        const body = part.replace(/^[a-zA-Z0-9_-]*\n/, "").replace(/\s+$/, "");
        if (body.trim()) out.push({ kind: "code", body });
        return;
      }
      part.split(/\n{2,}/).forEach((para) => {
        const t = para.trim();
        if (!t) return;
        if (/^-{3,}$|^\*{3,}$|^_{3,}$/.test(t)) {
          out.push({ kind: "rule", body: "" });
        } else if (/^#{1,6}\s/.test(t)) {
          out.push({ kind: "head", body: t.replace(/^#{1,6}\s*/, "") });
        } else if (/^\s*[-*•]\s/m.test(t)) {
          out.push({ kind: "list", body: t });
        } else {
          out.push({ kind: "p", body: t });
        }
      });
    });
    return out;
  }, [text]);

  const lineCount = text.split("\n").length;
  const needsClamp = lineCount > clamp || text.length > 900;

  return (
    <div>
      <div
        className="space-y-2 overflow-hidden transition-[max-height] duration-300"
        style={{ maxHeight: needsClamp && !open ? 168 : undefined }}
      >
        {blocks.map((b, i) => {
          if (b.kind === "rule") return <div key={i} className="border-t border-edge/50" />;
          if (b.kind === "head")
            return (
              <div key={i} className="text-[11px] font-semibold tracking-tight text-slate-200">
                {b.body}
              </div>
            );
          if (b.kind === "code")
            return (
              <pre
                key={i}
                className="overflow-x-auto rounded border border-edge/50 bg-ink/70 p-2 font-mono text-[10px] leading-[1.45] text-slate-400"
              >
                {b.body}
              </pre>
            );
          if (b.kind === "list")
            return (
              <ul key={i} className="space-y-0.5">
                {b.body.split("\n").map((li, j) => (
                  <li key={j} className="flex gap-1.5 text-[11px] leading-relaxed text-slate-400">
                    <span className="text-slate-600">·</span>
                    <span>{li.replace(/^\s*[-*•]\s*/, "")}</span>
                  </li>
                ))}
              </ul>
            );
          return (
            <p key={i} className="text-[11px] leading-relaxed text-slate-400">
              {b.body}
            </p>
          );
        })}
      </div>
      {needsClamp && (
        <button
          onClick={() => setOpen((o) => !o)}
          className="mt-1 text-[10px] uppercase tracking-wider text-slate-500 transition-colors hover:text-slate-300"
        >
          {open ? "Collapse" : `Expand · ${lineCount} lines`}
        </button>
      )}
    </div>
  );
}

/** A scalar rendered as a labelled row, with a bar when it is a ratio. */
function Row({ label, value }: { label: string; value: unknown }) {
  if (value === null || value === undefined || value === "") {
    return (
      <div className="flex items-baseline justify-between gap-3 border-b border-edge/30 py-1">
        <span className="micro">{label}</span>
        <span className="text-xs text-slate-600">—</span>
      </div>
    );
  }

  if (typeof value === "boolean") {
    return (
      <div className="flex items-baseline justify-between gap-3 border-b border-edge/30 py-1">
        <span className="micro">{label}</span>
        <span
          className="rounded px-1.5 py-0.5 text-[10px] font-semibold"
          style={{
            background: value ? `${STATE.healthy.color}22` : "rgb(var(--edge))",
            // A theme token, not a fixed grey. #8593AB was picked against
            // instrument black and measured 2.48:1 on paper.
            // --text-2, not --text-3: this sits on the edge-coloured chip
            // rather than on the page, and the dimmer step measured 3.91:1 there.
            color: value ? STATE.healthy.ink : "var(--text-2)",
          }}
        >
          {value ? "yes" : "no"}
        </span>
      </div>
    );
  }

  if (typeof value === "number") {
    const { text, ratio } = formatNumber(label, value);
    return (
      <div className="flex items-center justify-between gap-3 border-b border-edge/30 py-1">
        <span className="micro">{label}</span>
        <span className="flex items-center gap-2">
          {ratio !== undefined && (
            <span className="relative h-1 w-20 overflow-hidden rounded-full bg-edge">
              <span
                className="absolute inset-y-0 left-0 rounded-full"
                style={{ width: `${ratio * 100}%`, background: STATE.active.color }}
              />
            </span>
          )}
          <span className="readout text-xs text-slate-200">{text}</span>
        </span>
      </div>
    );
  }

  const s = String(value);
  if (isLongText(s)) {
    return (
      <div className="border-b border-edge/30 py-1.5">
        <span className="micro">{label}</span>
        <div className="mt-1">
          <RichText text={s} />
        </div>
      </div>
    );
  }

  return (
    <div className="flex items-baseline justify-between gap-3 border-b border-edge/30 py-1">
      <span className="micro">{label}</span>
      <span className={`text-xs text-slate-200 ${ID_KEY.test(label) ? "font-mono text-[11px]" : ""}`}>{s}</span>
    </div>
  );
}

/**
 * Renders any engine payload as structure. Scalars first so the numbers are
 * scannable, then long prose, then nested collections.
 */
export default function DataView({ value, depth = 0 }: { value: unknown; depth?: number }) {
  if (value === null || value === undefined) {
    return <span className="text-xs text-slate-600">—</span>;
  }

  if (Array.isArray(value)) {
    if (value.length === 0) return <span className="text-xs text-slate-600">none</span>;
    const allScalar = value.every((v) => typeof v !== "object" || v === null);
    if (allScalar) {
      return (
        <div className="flex flex-wrap gap-1">
          {value.map((v, i) => (
            <span key={i} className="rounded bg-edge/60 px-1.5 py-0.5 text-[10px] text-slate-300">
              {String(v)}
            </span>
          ))}
        </div>
      );
    }
    return (
      <div className="space-y-2">
        {value.map((v, i) => (
          <div key={i} className="rounded border border-edge/50 p-2">
            <div className="micro mb-1">#{i + 1}</div>
            <DataView value={v} depth={depth + 1} />
          </div>
        ))}
      </div>
    );
  }

  if (typeof value !== "object") {
    const s = String(value);
    return isLongText(s) ? <RichText text={s} /> : <span className="text-xs text-slate-200">{s}</span>;
  }

  const entries = Object.entries(value as Record<string, unknown>);
  if (entries.length === 0) return <span className="text-xs text-slate-600">empty</span>;

  // Scalars are scannable, so they lead; prose and nested structures follow.
  const scalars = entries.filter(
    ([, v]) => v === null || (typeof v !== "object" && !(typeof v === "string" && isLongText(v)))
  );
  const prose = entries.filter(([, v]) => typeof v === "string" && isLongText(v));
  const nested = entries.filter(([, v]) => v !== null && typeof v === "object");

  return (
    <div className="space-y-2">
      {scalars.length > 0 && (
        <div>
          {scalars.map(([k, v]) => (
            <Row key={k} label={formatKey(k)} value={v} />
          ))}
        </div>
      )}
      {prose.map(([k, v]) => (
        <Row key={k} label={formatKey(k)} value={v} />
      ))}
      {nested.map(([k, v]) => (
        <div key={k}>
          <span className="micro">{formatKey(k)}</span>
          <div className="mt-1 border-l border-edge/50 pl-2">
            <DataView value={v} depth={depth + 1} />
          </div>
        </div>
      ))}
    </div>
  );
}

export { RichText, formatKey };
