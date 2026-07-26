import type { ReactNode } from "react";

/**
 * The instrument housing around an interactive explainer.
 *
 * <p>The badge is not decoration. These demos run a local model of how the engine
 * behaves; they are not talking to a backend, and a visitor has no way to tell
 * the difference by looking. Labelling them keeps the page honest — the console
 * is where live data lives, and it says so.
 */
export default function DemoFrame({
  title,
  hint,
  controls,
  readouts,
  children,
}: {
  title: string;
  hint: string;
  controls?: ReactNode;
  readouts?: [string, string][];
  children: ReactNode;
}) {
  return (
    <div className="rounded-xl border border-edge/80 bg-panel/70 backdrop-blur-md">
      <div className="flex flex-wrap items-center gap-x-3 gap-y-2 border-b border-edge/70 px-4 py-2.5">
        <span className="text-[11px] font-medium tracking-wide text-slate-200">{title}</span>
        <span
          className="rounded border border-amber-500/30 px-1.5 py-0.5 text-[9px] uppercase tracking-[0.18em] text-amber-400/90"
          title="Runs a local model of the engine's behaviour. It is not connected to a live Continuum instance."
        >
          Simulation
        </span>
        <span className="ml-auto text-[10px] text-slate-500">{hint}</span>
      </div>

      <div className="relative">{children}</div>

      {(controls || readouts) && (
        <div className="flex flex-wrap items-center gap-x-6 gap-y-3 border-t border-edge/70 px-4 py-3">
          {controls}
          {readouts && (
            <dl className="ml-auto flex flex-wrap gap-x-6 gap-y-2">
              {readouts.map(([k, v]) => (
                <div key={k}>
                  <dt className="text-[9px] uppercase tracking-[0.2em] text-slate-600">{k}</dt>
                  <dd className="readout text-[13px] font-medium text-slate-200">{v}</dd>
                </div>
              ))}
            </dl>
          )}
        </div>
      )}
    </div>
  );
}

/** A control that reads as instrumentation rather than as a website button. */
export function DemoButton({
  children,
  onClick,
  primary,
  disabled,
}: {
  children: ReactNode;
  onClick: () => void;
  primary?: boolean;
  disabled?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      disabled={disabled}
      className={
        primary
          ? "rounded border border-aurora/50 bg-aurora/15 px-3 py-1.5 text-[11px] font-medium text-slate-100 transition-colors hover:bg-aurora/25 disabled:opacity-40"
          : "rounded border border-edge px-3 py-1.5 text-[11px] text-slate-300 transition-colors hover:border-aurora/50 hover:text-slate-100 disabled:opacity-40"
      }
    >
      {children}
    </button>
  );
}

export function DemoToggle({
  label,
  on,
  onChange,
}: {
  label: string;
  on: boolean;
  onChange: (v: boolean) => void;
}) {
  return (
    <button
      onClick={() => onChange(!on)}
      className="flex items-center gap-2 text-[11px] text-slate-300 transition-colors hover:text-slate-100"
    >
      <span
        className={`h-3.5 w-6 rounded-full border transition-colors ${
          on ? "border-aurora/60 bg-aurora/40" : "border-edge bg-edge/40"
        } relative`}
      >
        <span
          className="absolute top-[1px] h-[10px] w-[10px] rounded-full bg-slate-200 transition-all"
          style={{ left: on ? 13 : 2 }}
        />
      </span>
      {label}
    </button>
  );
}
