import { useState } from "react";
import { Micro, Switch } from "../../system/primitives";
import { Pipeline } from "./types";

/* -------------------------------------------------------------------------- *
 * Confidence policy
 * -------------------------------------------------------------------------- */

export function PolicyControls({
  p,
  busy,
  onChange,
}: {
  p: Pipeline;
  busy: boolean;
  onChange: (b: {
    policyEnabled?: boolean; strongThreshold?: number; weakThreshold?: number;
    declineOnNoEvidence?: boolean;
  }) => void;
}) {
  const [strong, setStrong] = useState(p.strongThreshold);
  const [weak, setWeak] = useState(p.weakThreshold);

  // The server refuses weak > strong; mirroring that here means the slider
  // cannot be dragged into a state the save will reject.
  const invalid = weak > strong;

  return (
    <div>
      <Switch
        checked={p.policyEnabled}
        busy={busy}
        onChange={(next) => onChange({ policyEnabled: next })}
        label="Confidence policy"
        hint="Off by default. When on, the strength of the evidence decides what the model is allowed to do with it — answer plainly, hedge, or ask for something better."
      />

      {p.policyEnabled && (
        <div className="mt-5 space-y-5">
          {/* The bands as a single bar, because they are only meaningful
              relative to each other and to the numbers on either side. */}
          <div>
            <div className="flex h-7 w-full overflow-hidden rounded-md" style={{ boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }}>
              <div
                className="flex items-center justify-center text-[10px] transition-[width] duration-200"
                style={{
                  width: `${weak * 100}%`,
                  background: "color-mix(in srgb, var(--state-degraded-ink) 22%, transparent)",
                  color: "var(--state-degraded-ink)",
                }}
                title="Too weak to advise on"
              >
                {weak >= 0.18 && "ask for better"}
              </div>
              <div
                className="flex items-center justify-center text-[10px] transition-[width] duration-200"
                style={{
                  width: `${(strong - weak) * 100}%`,
                  background: "color-mix(in srgb, var(--state-warning-ink) 22%, transparent)",
                  color: "var(--state-warning-ink)",
                }}
                title="Moderate — model told to hedge"
              >
                {strong - weak >= 0.14 && "hedge"}
              </div>
              <div
                className="flex items-center justify-center text-[10px] transition-[width] duration-200"
                style={{
                  width: `${(1 - strong) * 100}%`,
                  background: "color-mix(in srgb, var(--state-healthy-ink) 20%, transparent)",
                  color: "var(--state-healthy-ink)",
                }}
                title="Strong — answered directly"
              >
                {1 - strong >= 0.14 && "answer"}
              </div>
            </div>
            <div className="mt-1 flex justify-between">
              <span className="readout text-[10px] text-slate-600">0.00</span>
              <span className="readout text-[10px] text-slate-600">1.00</span>
            </div>
          </div>

          <div className="grid gap-4 sm:grid-cols-2">
            <label className="block">
              <div className="flex items-baseline justify-between">
                <Micro>Weak below</Micro>
                <span className="readout text-xs text-slate-400">{weak.toFixed(2)}</span>
              </div>
              <input
                type="range"
                min={0}
                max={1}
                step={0.01}
                value={weak}
                disabled={busy}
                onChange={(e) => setWeak(Number(e.target.value))}
                onMouseUp={() => !invalid && onChange({ weakThreshold: weak })}
                onTouchEnd={() => !invalid && onChange({ weakThreshold: weak })}
                className="mt-1 w-full"
                style={{ accentColor: "var(--state-warning-ink)" }}
                aria-label="Weak threshold"
              />
            </label>
            <label className="block">
              <div className="flex items-baseline justify-between">
                <Micro>Strong at or above</Micro>
                <span className="readout text-xs text-slate-400">{strong.toFixed(2)}</span>
              </div>
              <input
                type="range"
                min={0}
                max={1}
                step={0.01}
                value={strong}
                disabled={busy}
                onChange={(e) => setStrong(Number(e.target.value))}
                onMouseUp={() => !invalid && onChange({ strongThreshold: strong })}
                onTouchEnd={() => !invalid && onChange({ strongThreshold: strong })}
                className="mt-1 w-full"
                style={{ accentColor: "var(--state-healthy-ink)" }}
                aria-label="Strong threshold"
              />
            </label>
          </div>

          {invalid && (
            <p className="text-xs" style={{ color: "var(--state-critical-ink)" }}>
              The weak threshold cannot sit above the strong one — there would be no middle band left
              to hedge in, which looks like a working policy and silently isn't.
            </p>
          )}

          <Switch
            checked={p.declineOnNoEvidence}
            busy={busy}
            onChange={(next) => onChange({ declineOnNoEvidence: next })}
            label="Refuse outright when there is no evidence at all"
            hint="Skips the model entirely and returns a fixed message. Cheaper and strictly safer; leaving it off keeps the model able to answer the parts of a question that need no findings."
          />
        </div>
      )}
    </div>
  );
}
