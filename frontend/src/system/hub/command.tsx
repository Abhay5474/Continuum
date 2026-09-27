import { useEffect, useRef, useState } from "react";
import type { ReactNode } from "react";

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
