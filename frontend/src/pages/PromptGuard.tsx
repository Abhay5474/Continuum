import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Micro, PageHeader, Plane, Readout, Switch } from "../system/primitives";
import { EmptyState, ErrorState, SkeletonRows, useToast } from "../components/ui";
import { dateTimeOf } from "../system/time";

/**
 * Prompt Guard — the two things that sit between a prompt and the model.
 *
 * <p>Both shipped working: the firewall redacts PII and blocks injection
 * attempts, compression trims a prompt before it is billed. Neither had a
 * control anywhere in the console, so the only way to switch them on was to
 * POST to the API by hand — which is why they looked, from the console, like
 * features that did not exist.
 *
 * <p>They are on one page because they are one decision: what happens to a
 * prompt on its way out. Both are per-account and off by default; while off the
 * prompt is forwarded verbatim.
 */

type Status = { firewallEnabled: boolean; compressionEnabled: boolean };

export default function PromptGuard() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [fw, setFw] = useState<any | null>(null);
  const [cp, setCp] = useState<any | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<"firewall" | "compression" | null>(null);

  const load = useCallback(async () => {
    try {
      const [s, f, c] = await Promise.all([
        portal.v8.status(),
        portal.v8.firewallProfile(),
        portal.v8.compressionProfile(),
      ]);
      setStatus(s);
      setFw(f);
      setCp(c);
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load prompt guard settings.");
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 6000);
    return () => clearInterval(t);
  }, [load]);

  const toggle = async (which: "firewall" | "compression", next: boolean) => {
    setBusy(which);
    // Reflect the intent immediately; the reload below is the source of truth.
    setStatus((s) => (s ? { ...s, [`${which}Enabled`]: next } as Status : s));
    try {
      const s = which === "firewall" ? await portal.v8.setFirewall(next) : await portal.v8.setCompression(next);
      setStatus(s);
      toast(`${which === "firewall" ? "Firewall" : "Compression"} ${next ? "enabled" : "disabled"}`);
    } catch (e: any) {
      await load();
      toast(e?.message ?? "Could not change that setting", "error");
    } finally {
      setBusy(null);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  return (
    <div className="space-y-6">
      <PageHeader
        title="Prompt Guard"
        subtitle="What happens to a prompt between your request and the provider. Both controls are per-account and off by default."
      />

      <Plane className="p-5">
        <div className="grid gap-6 sm:grid-cols-2">
          <Switch
            label="Prompt firewall"
            hint="Redacts personal data and blocks known injection patterns before the prompt leaves the engine. Every action is recorded below."
            checked={status?.firewallEnabled ?? false}
            busy={busy === "firewall" || status === null}
            onChange={(next) => toggle("firewall", next)}
          />
          <Switch
            label="Prompt compression"
            hint="Trims redundant context before the prompt is billed, protecting code, quotes and identifiers from being touched."
            checked={status?.compressionEnabled ?? false}
            busy={busy === "compression" || status === null}
            onChange={(next) => toggle("compression", next)}
          />
        </div>
      </Plane>

      <section className="space-y-3">
        <Micro>Firewall activity</Micro>
        {fw === null ? (
          <SkeletonRows rows={3} />
        ) : (
          <>
            <Plane className="grid gap-6 p-5 sm:grid-cols-4">
              <Readout label="Events" value={fw.events ?? 0} />
              <Readout label="PII redacted" value={fw.piiRedacted ?? 0} state={fw.piiRedacted ? "healthy" : "idle"} />
              <Readout
                label="Injections blocked"
                value={fw.injectionsBlocked ?? 0}
                state={fw.injectionsBlocked ? "critical" : "idle"}
              />
              <Readout label="Flagged" value={fw.injectionsFlagged ?? 0} state={fw.injectionsFlagged ? "degraded" : "idle"} />
            </Plane>
            {fw.recent?.length ? (
              <EventTable rows={fw.recent} />
            ) : (
              <EmptyState
                title="Nothing intercepted yet"
                hint={
                  status?.firewallEnabled
                    ? "The firewall is on and has not had to act on a prompt."
                    : "Turn the firewall on and send a request through the gateway."
                }
              />
            )}
          </>
        )}
      </section>

      <section className="space-y-3">
        <Micro>Compression savings</Micro>
        {cp === null ? (
          <SkeletonRows rows={2} />
        ) : (
          <Plane className="grid gap-6 p-5 sm:grid-cols-4">
            <Readout label="Requests" value={cp.requests ?? 0} />
            <Readout label="Tokens saved" value={cp.tokensSaved ?? 0} state={cp.tokensSaved ? "healthy" : "idle"} />
            <Readout
              label="Reduction"
              value={`${Math.round((cp.reductionPct ?? 0) * 100)}`}
              unit="%"
              state={cp.reductionPct ? "healthy" : "idle"}
            />
            <Readout label="Protected spans" value={cp.protectedSpans ?? 0} hint="Left untouched by design" />
          </Plane>
        )}
      </section>
    </div>
  );
}

function EventTable({ rows }: { rows: any[] }) {
  return (
    <Plane className="overflow-x-auto">
      <table className="w-full min-w-[520px] text-sm">
        <thead>
          <tr className="border-b border-edge/70 text-left">
            {["When", "Direction", "Category", "Action", "Matches"].map((h) => (
              <th key={h} className="px-4 py-2 micro font-medium">
                {h}
              </th>
            ))}
          </tr>
        </thead>
        <tbody>
          {rows.map((r, i) => (
            <tr key={r.id ?? i} className="border-b border-edge/40 last:border-0">
              <td className="whitespace-nowrap px-4 py-2 text-slate-500">{dateTimeOf(r.createdAt)}</td>
              <td className="px-4 py-2 text-slate-400">{r.direction}</td>
              <td className="px-4 py-2 text-slate-300">{r.category}</td>
              <td className="px-4 py-2">
                <span className={r.action === "BLOCKED" ? "text-rose-400" : "text-slate-300"}>{r.action}</span>
              </td>
              <td className="px-4 py-2 readout text-slate-400">{r.matchCount}</td>
            </tr>
          ))}
        </tbody>
      </table>
    </Plane>
  );
}
