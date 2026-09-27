import type { ReactNode } from "react";
import { Note } from "../../system/primitives";
import { Pipeline, Verification } from "./types";

/* -------------------------------------------------------------------------- *
 * Output verification
 * -------------------------------------------------------------------------- */

const MODES: { value: string; label: string; hint: string }[] = [
  { value: "OFF", label: "Off", hint: "No checking." },
  {
    value: "MONITOR",
    label: "Monitor",
    hint: "Check and record the verdict. The answer goes out unchanged — start here, so you find out how often your pipeline would have been stopped before you let it stop anything.",
  },
  {
    value: "ENFORCE",
    label: "Enforce",
    hint: "Replace an answer that fails with one that states the findings plainly. This changes what a real user reads.",
  },
];

export function VerificationControls({
  p,
  busy,
  onChange,
}: {
  p: Pipeline;
  busy: boolean;
  onChange: (b: { verificationMode?: string }) => void;
}) {
  const mode = MODES.find((m) => m.value === p.verificationMode) ?? MODES[0];
  return (
    <div>
      <div className="flex flex-wrap items-baseline justify-between gap-2">
        <h3 className="text-[13px] font-semibold tracking-tight text-slate-200">Output verification</h3>
        <span className="micro">{p.verificationMode}</span>
      </div>
      <Note className="mt-1">
        Does the advice match the findings? The confidence policy asks the model to behave and checks
        whether it did — both are about the instruction. This asks whether the answer is anchored to
        what the specialists actually found.
      </Note>

      <div className="mt-3 flex flex-wrap gap-1.5">
        {MODES.map((m) => {
          const on = p.verificationMode === m.value;
          return (
            <button
              key={m.value}
              disabled={busy}
              onClick={() => onChange({ verificationMode: m.value })}
              style={
                on
                  ? { background: "var(--accent-wash)", boxShadow: "inset 0 0 0 1px var(--accent-edge)", color: "var(--accent-ink)" }
                  : { boxShadow: "inset 0 0 0 1px rgb(var(--edge))" }
              }
              className={`rounded-md px-2.5 py-1 text-xs transition-colors disabled:opacity-40 ${
                on ? "" : "text-slate-400 hover:text-slate-200"
              }`}
            >
              {m.label}
            </button>
          );
        })}
      </div>
      <Note className="mt-2">{mode.hint}</Note>

      <Note className="mt-2.5">
        Three checks: certainty beyond the evidence, invented confidence figures, and which findings
        the advice actually addresses. Only the first two can fail an answer — coverage matching is
        lexical, so a model writing "laceration" for a finding labelled "open wound" reads as
        uncovered while having covered it perfectly, and failing that would punish good writing.
      </Note>
    </div>
  );
}

/**
 * A verdict, stated rather than framed.
 *
 * <p>Full-width tinted panels were used for all three of these, so a run with a
 * policy verdict and a verification verdict produced two stacked coloured slabs
 * and the answer underneath them looked like an afterthought. A coloured rule
 * down the left carries the same state at a tenth of the ink.
 */
export function Verdict({
  tone,
  title,
  children,
}: {
  tone: "ok" | "warn" | "bad" | "idle";
  title: ReactNode;
  children?: ReactNode;
}) {
  const colour = {
    ok: "var(--state-healthy-ink)",
    warn: "var(--state-warning-ink)",
    bad: "var(--state-critical-ink)",
    idle: "var(--state-idle-ink)",
  }[tone];
  return (
    <div className="border-l-2 pl-3.5" style={{ borderColor: colour }}>
      <div className="flex flex-wrap items-baseline gap-x-3 gap-y-1 text-[13px] font-medium" style={{ color: colour }}>
        {title}
      </div>
      {children}
    </div>
  );
}

export function VerificationVerdict({ v }: { v: Verification }) {
  const tone = v.verdict === "OK" ? "ok" : v.verdict === "WARN" ? "warn" : "bad";
  return (
    <Verdict
      tone={tone}
      title={
        <>
          <span>
            {v.verdict === "OK"
              ? "The answer matches the findings."
              : v.verdict === "WARN"
                ? "The answer matches, with something worth noting."
                : "The answer does NOT match the findings."}
          </span>
          {v.replaced && <span className="micro">answer replaced</span>}
          <span className="micro opacity-70">measured, {v.method}</span>
        </>
      }
    >
      {v.issues.length > 0 && (
        <ul className="mt-1.5 space-y-1">
          {v.issues.map((i, n) => (
            <li key={n} className="text-xs text-slate-400">
              <span style={{ color: i.severe ? "var(--state-critical-ink)" : "var(--state-warning-ink)" }}>
                {i.severe ? "✕" : "!"}
              </span>{" "}
              {i.detail}
            </li>
          ))}
        </ul>
      )}

      {(v.covered.length > 0 || v.uncovered.length > 0) && (
        <p className="mt-1.5 text-xs leading-relaxed text-slate-500 max-w-2xl">
          {v.covered.length > 0 && <>Addressed: {v.covered.join(", ")}. </>}
          {v.uncovered.length > 0 && <>Not mentioned: {v.uncovered.join(", ")}.</>}
        </p>
      )}

      {v.replaced && (
        <p className="mt-1.5 text-xs leading-relaxed text-slate-600 max-w-2xl">
          The model's answer was discarded and replaced with one that states the findings plainly.
          Your application received the replacement, not the original.
        </p>
      )}
    </Verdict>
  );
}
