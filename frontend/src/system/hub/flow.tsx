import type { ReactNode } from "react";

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
