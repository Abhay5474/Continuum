import { useEffect, useState } from "react";
import { STATE } from "./tokens";
import { SwitchThumb } from "./primitives";
import { useToast } from "../components/ui";

/**
 * The on/off control for an opt-in engine, placed on that engine's own page.
 *
 * These switches used to live in API Keys & Providers, so the control for a
 * feature sat on a different screen from the feature, next to credentials it
 * has nothing to do with. They live here now, and only here.
 *
 * <p>It is a labelled switch with the consequence spelled out — what the
 * gateway does with it on, and with it off — rather than a bare "On" chip, so
 * the most consequential control on the page reads as one.
 */
export default function FeatureToggle({
  label,
  onText,
  offText,
  status,
  enable,
  disable,
  onChange,
  useFor,
  skipFor,
  guide,
}: {
  /** The engine's name, which is also the switch's accessible name. */
  label: string;
  /** What happens while it is on, in a few words. */
  onText: string;
  /** What happens while it is off. */
  offText: string;
  status: () => Promise<any>;
  enable: () => Promise<any>;
  disable: () => Promise<any>;
  onChange?: (enabled: boolean) => void;
  /** When it is worth turning on, and when it is not: shown behind a disclosure. */
  useFor?: string[];
  skipFor?: string[];
  /** Anchor for the page's guided tour. */
  guide?: string;
}) {
  const toast = useToast();
  const [on, setOn] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);
  const [why, setWhy] = useState(false);

  useEffect(() => {
    status()
      .then((s) => {
        setOn(!!s?.enabled);
        onChange?.(!!s?.enabled);
      })
      .catch(() => setOn(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const flip = async (next: boolean) => {
    if (next === on) return;
    setBusy(true);
    try {
      const r = next ? await enable() : await disable();
      const now = !!r?.enabled;
      setOn(now);
      onChange?.(now);
      toast(`${label} ${now ? "on" : "off"}`, "success");
    } catch (e: any) {
      // The previous state stays visible rather than lying about it.
      toast(`${label} was not changed: ${e?.message ?? "request failed"}`, "error");
    } finally {
      setBusy(false);
    }
  };

  const known = on !== null;
  const state = on ? STATE.healthy : STATE.idle;
  return (
    <div className="plane w-full max-w-md px-4 py-3 sm:w-auto sm:min-w-[300px]" data-guide={guide}>
      <div className="flex items-center gap-3">
        <SwitchThumb checked={!!on} disabled={busy || !known} label={label} onChange={(next) => void flip(next)} />
        <div className="min-w-0 flex-1">
          <div className="text-sm font-medium text-slate-200">{label}</div>
          <div className="mt-0.5 text-[12px] leading-snug" style={{ color: known && on ? state.ink : "var(--text-2)" }} aria-live="polite">
            {!known ? "Checking…" : on ? onText : offText}
          </div>
        </div>
        {(useFor || skipFor) && (
          <button
            onClick={() => setWhy(!why)}
            aria-expanded={why}
            className="shrink-0 self-start rounded-full border border-edge px-2.5 py-0.5 text-[11.5px] text-slate-400 hover:border-slate-500/60 hover:text-slate-200"
          >
            {why ? "Hide" : "When to use"}
          </button>
        )}
      </div>
      {why && (
        <div className="rise-in mt-3 grid gap-3 border-t border-edge/60 pt-3 text-xs sm:grid-cols-2">
          {useFor && (
            <div>
              <div className="font-semibold" style={{ color: STATE.healthy.ink }}>✓ On for</div>
              <ul className="mt-1 space-y-1 text-slate-400">
                {useFor.map((u) => (
                  <li key={u}>{u}</li>
                ))}
              </ul>
            </div>
          )}
          {skipFor && (
            <div>
              <div className="font-semibold" style={{ color: STATE.critical.ink }}>✕ Off for</div>
              <ul className="mt-1 space-y-1 text-slate-400">
                {skipFor.map((u) => (
                  <li key={u}>{u}</li>
                ))}
              </ul>
            </div>
          )}
        </div>
      )}
    </div>
  );
}
