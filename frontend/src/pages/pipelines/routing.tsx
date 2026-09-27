import { Micro, Switch } from "../../system/primitives";
import { Spine, SpineNode } from "../../system/hub";
import { Select } from "../../system/controls";
import { CONDITIONS, Condition, Pipeline, RoutingStep, Specialist } from "./types";

/* -------------------------------------------------------------------------- *
 * Specialist routing
 * -------------------------------------------------------------------------- */

/**
 * Which specialists run, rather than all of them, always.
 *
 * <p>Conditions can be edited while routing is off, so a developer can set one
 * up and watch it take effect the moment they flip the switch — rather than
 * having to turn on a behaviour change before they can configure it.
 */
export function RoutingControls({
  p,
  specialists,
  busy,
  onChange,
}: {
  p: Pipeline;
  specialists: Specialist[];
  busy: boolean;
  onChange: (b: { routingEnabled?: boolean; routing?: RoutingStep[] }) => void;
}) {
  const steps: RoutingStep[] =
    p.routing?.length > 0
      ? p.routing
      : p.steps.map((id) => ({ specialistId: id, when: "ALWAYS" as Condition, pattern: null }));

  const name = (id: number) => specialists.find((s) => s.id === id)?.name ?? `#${id}`;

  const setStep = (i: number, patch: Partial<RoutingStep>) => {
    const next = steps.map((s, j) => (j === i ? { ...s, ...patch } : s));
    onChange({ routing: next });
  };

  return (
    <div>
      <Switch
        checked={p.routingEnabled}
        busy={busy}
        onChange={(next) => onChange({ routingEnabled: next })}
        label="Specialist routing"
        hint="Off by default — every step runs, which is what this pipeline did before. When on, each step's condition decides whether it runs at all."
      />

      {/* On the spine, because these are steps in an order and the condition on
          step three is about what step two found. */}
      <div className="mt-6">
        <Spine>
          {steps.map((s, i) => {
            const meta = CONDITIONS.find((c) => c.value === s.when) ?? CONDITIONS[0];
            const first = i === 0;
            const impossible = first && (s.when === "IF_PREVIOUS_FOUND" || s.when === "IF_PREVIOUS_EMPTY");
            const conditional = s.when !== "ALWAYS";
            return (
              <SpineNode
                key={`${s.specialistId}-${i}`}
                index={i}
                tone={impossible ? "bad" : conditional ? "accent" : "idle"}
                head={name(s.specialistId)}
                aside={meta.hint}
                open
              >
                <div className="grid gap-3 pb-2 sm:grid-cols-2">
                  <label className="block">
                    <Micro>Runs when</Micro>
                    <Select
                      value={s.when}
                      disabled={busy}
                      onChange={(e) =>
                        setStep(i, {
                          when: e.target.value as Condition,
                          pattern:
                            CONDITIONS.find((c) => c.value === e.target.value)?.needsPattern ?? null,
                        })
                      }
                    >
                      {CONDITIONS.map((c) => (
                        <option
                          key={c.value}
                          value={c.value}
                          // Offered but not selectable in first position: it is a
                          // condition with nothing to refer to.
                          disabled={first && (c.value === "IF_PREVIOUS_FOUND" || c.value === "IF_PREVIOUS_EMPTY")}
                        >
                          {c.label}
                          {first && (c.value === "IF_PREVIOUS_FOUND" || c.value === "IF_PREVIOUS_EMPTY")
                            ? " — needs a step before it"
                            : ""}
                        </option>
                      ))}
                    </Select>
                  </label>

                  {meta.needsPattern && (
                    <label className="block">
                      <Micro>{s.when === "IF_INPUT_IS" ? "Input kind" : "Pattern"}</Micro>
                      <input
                        value={s.pattern ?? ""}
                        disabled={busy}
                        placeholder={meta.needsPattern}
                        onChange={(e) => setStep(i, { pattern: e.target.value })}
                        className="mt-1 w-full font-mono text-[12.5px] focus:border-[color:var(--accent-edge)] field"
                      />
                    </label>
                  )}
                </div>

                {impossible && (
                  <p className="pb-2 text-xs" style={{ color: "var(--state-critical-ink)" }}>
                    Nothing runs before this one, so there is no previous step for the condition to
                    look at.
                  </p>
                )}
              </SpineNode>
            );
          })}
        </Spine>
      </div>

      {!p.routingEnabled && steps.some((s) => s.when !== "ALWAYS") && (
        <p className="mt-4 text-xs" style={{ color: "var(--state-warning-ink)" }}>
          These conditions are saved but not in force — routing is off, so every step still runs.
        </p>
      )}
    </div>
  );
}
