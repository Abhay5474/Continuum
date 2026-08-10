import DataView from "../system/DataView";
import { useEffect, useState } from "react";
import { api } from "../api";
import { Chip } from "../system/hub";
import type { ChaosState } from "../types";

export default function ChaosPanel() {
  const [state, setState] = useState<ChaosState | null>(null);
  const [msg, setMsg] = useState<string>("");

  const refresh = () => api.chaos().then(setState).catch(() => {});
  useEffect(() => {
    refresh();
  }, []);

  async function run(path: string, label: string) {
    setMsg(`Applied: ${label}`);
    setState(await api.chaosPost(path));
  }

  return (
    <div className="max-w-3xl space-y-6">
      <div>
        <div className="flex items-center gap-2.5">
          <Chip glyph="alert" tone="bad" size={28} />
          <h1 className="text-[20px] font-semibold tracking-[-0.011em] text-slate-100">Fault Injection</h1>
        </div>
        <p className="mt-0.5 text-sm text-slate-500 max-w-2xl leading-relaxed">
          Inject faults at runtime, then start workflows and watch the runtime recover. The
          guarantee: workflows still complete, and side effects still fire exactly once.
        </p>
        <p className="mt-1 text-xs text-slate-500 max-w-2xl leading-relaxed">
          Faults you arm here apply to your account's traffic only — your requests, your
          workflows, your deliveries. Nobody else on the engine sees them, so a drill is safe
          to run against production.
        </p>
      </div>

      <div className="grid gap-4 sm:grid-cols-2">
        <Card title="Provider failover" desc="Force the primary LLM provider down to trigger failover.">
          <Btn onClick={() => run("provider-down?down=true", "primary provider DOWN")}>Take primary down</Btn>
          <Btn onClick={() => run("provider-down?down=false", "primary provider UP")} ghost>
            Bring primary up
          </Btn>
        </Card>

        <Card title="Worker crash" desc="Simulate a worker dying during the next activity.">
          <Btn onClick={() => run("crash-after?activities=1", "crash after 1 activity")}>
            Crash after next activity
          </Btn>
        </Card>

        <Card title="Activity failures" desc="Randomly fail activities to exercise retries.">
          <Btn onClick={() => run("activity-failure-rate?rate=0.5", "activity failure 50%")}>50% fail</Btn>
          <Btn onClick={() => run("activity-failure-rate?rate=0", "activity failure 0%")} ghost>
            Disable
          </Btn>
        </Card>

        <Card title="Sink failures" desc="Make email/payment delivery flaky (tests exactly-once).">
          <Btn onClick={() => run("sink-failure-rate?rate=0.7", "sink failure 70%")}>70% fail</Btn>
          <Btn onClick={() => run("sink-failure-rate?rate=0", "sink failure 0%")} ghost>
            Disable
          </Btn>
        </Card>

        <Card title="Latency" desc="Inject latency into every activity.">
          <Btn onClick={() => run("activity-latency?ms=3000", "3s latency")}>+3s</Btn>
          <Btn onClick={() => run("activity-latency?ms=0", "no latency")} ghost>
            Reset
          </Btn>
        </Card>

        <Card title="Reset all" desc="Clear every fault injection.">
          <Btn onClick={() => run("reset", "all chaos reset")}>Reset chaos</Btn>
        </Card>
      </div>

      {msg && <div className="text-sm text-emerald-300">{msg}</div>}

      <div className="rounded-lg border border-edge bg-panel p-4">
        <div className="mb-2 text-sm font-medium">Current chaos state</div>
        <DataView value={state} />
      </div>
    </div>
  );
}

function Card({ title, desc, children }: { title: string; desc: string; children: React.ReactNode }) {
  return (
    <div className="rounded-lg border border-edge bg-panel p-4">
      <div className="font-medium">{title}</div>
      <div className="mb-3 text-xs text-slate-400">{desc}</div>
      <div className="flex flex-wrap gap-2">{children}</div>
    </div>
  );
}

function Btn({
  children,
  onClick,
  ghost,
}: {
  children: React.ReactNode;
  onClick: () => void;
  ghost?: boolean;
}) {
  return (
    <button
      onClick={onClick}
      className={`rounded-md px-3 py-1.5 text-sm font-medium ${
        ghost ? "border border-edge text-slate-300 hover:bg-edge" : "bg-rose-600 text-white hover:bg-rose-500"
      }`}
    >
      {children}
    </button>
  );
}
