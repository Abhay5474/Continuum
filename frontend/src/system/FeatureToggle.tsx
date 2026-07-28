import { useEffect, useState } from "react";
import { STATE } from "./tokens";
import { StateDot } from "./primitives";

/**
 * The on/off control for an opt-in subsystem, placed on that subsystem's own
 * page.
 *
 * These toggles used to live only in API Keys & Providers, so the switch for a
 * feature sat on a different screen from the feature. You should be able to turn
 * something on from where you are looking at it.
 */
export default function FeatureToggle({
  status,
  enable,
  disable,
  onChange,
}: {
  status: () => Promise<any>;
  enable: () => Promise<any>;
  disable: () => Promise<any>;
  onChange?: (enabled: boolean) => void;
}) {
  const [on, setOn] = useState<boolean | null>(null);
  const [busy, setBusy] = useState(false);

  useEffect(() => {
    status()
      .then((s) => {
        setOn(!!s?.enabled);
        onChange?.(!!s?.enabled);
      })
      .catch(() => setOn(false));
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const flip = async () => {
    setBusy(true);
    try {
      const r = on ? await disable() : await enable();
      const next = !!r?.enabled;
      setOn(next);
      onChange?.(next);
    } catch {
      /* leave the previous state visible rather than lying about it */
    } finally {
      setBusy(false);
    }
  };

  const known = on !== null;
  return (
    <button
      onClick={flip}
      disabled={busy || !known}
      title={on ? "Turn this subsystem off" : "Turn this subsystem on"}
      className="flex shrink-0 items-center gap-2 rounded border px-3 py-1.5 text-xs transition-colors disabled:opacity-50"
      style={{
        borderColor: on ? `${STATE.healthy.color}55` : "rgb(var(--edge))",
        color: on ? STATE.healthy.ink : undefined,
      }}
    >
      <StateDot state={on ? "healthy" : "idle"} size={7} />
      {!known ? "…" : on ? "On" : "Off"}
    </button>
  );
}
