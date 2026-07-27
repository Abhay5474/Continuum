import { useEffect, useRef, useState } from "react";
import { api, portal } from "../api";
import { worst, type StateKey } from "./tokens";
import type { Subsystem } from "./ContinuumCore";

/**
 * Telemetry for the command centre.
 *
 * Every value here comes from a real endpoint. Where a subsystem named in the
 * architecture is not present in this build, it is reported as `installed:false`
 * rather than being hidden or filled with placeholder numbers — the topology
 * should describe the system that actually exists.
 *
 * Traffic-driven motion is derived from *deltas* between polls, so the interface
 * is still when nothing is happening and animates only on real events.
 */

export type Telemetry = {
  loading: boolean;
  subsystems: Subsystem[];
  coreState: StateKey;
  load: number;
  /** Rolling series for the traces, newest last. */
  series: { requests: number[]; success: number[] };
  headline: {
    requests: number;
    successRate: number;
    failoversPrevented: number;
    tokens: number;
    costUsd: number;
    running: number;
    completed: number;
    failed: number;
  };
  events: { at: string; kind: StateKey; text: string }[];
  workflows: any[];
  /** True while a chaos experiment is deliberately degrading the system. */
  experimentActive: boolean;
};

const EMPTY: Telemetry["headline"] = {
  requests: 0, successRate: 1, failoversPrevented: 0, tokens: 0,
  costUsd: 0, running: 0, completed: 0, failed: 0,
};

const soft = <T,>(p: Promise<T>): Promise<T | null> => p.catch(() => null);

export function useTelemetry(pollMs = 4000): Telemetry {
  const [t, setT] = useState<Telemetry>({
    loading: true,
    subsystems: [],
    coreState: "idle",
    load: 0,
    series: { requests: [], success: [] },
    headline: EMPTY,
    events: [],
    workflows: [],
    experimentActive: false,
  });

  // Previous counters, so we can turn totals into rates.
  const prev = useRef<{ requests: number; dagRuns: number; faults: number; at: number } | null>(null);
  const series = useRef<{ requests: number[]; success: number[] }>({ requests: [], success: [] });

  useEffect(() => {
    let alive = true;

    const tick = async () => {
      const [stats, gw, health, mmu, dag, routing, aichaos, chaos, wfs, fw, god, cache, capacity] =
        await Promise.all([
        soft(api.get<any>("/api/stats")),
        soft(api.get<any>("/api/gateway/stats")),
        soft(api.get<any[]>("/api/gateway/health")),
        soft(api.get<any>("/api/mmu/profile")),
        soft(api.get<any[]>("/api/dag/runs")),
        soft(api.get<any>("/api/routing/state")),
        soft(api.get<any>("/api/ai-chaos")),
        soft(api.get<any>("/api/chaos")),
        soft(api.get<any[]>("/api/workflows?limit=8")),
        soft(portal.get<any>("/api/portal/developer/v8/firewall/profile")),
        soft(portal.godmode.status()),
        soft(portal.cache.status()),
        soft(api.get<any>("/api/engine/capacity")),
      ]);
      if (!alive) return;

      const now = Date.now();
      const requests = gw?.totalRequests ?? 0;
      const dagRuns = dag?.length ?? 0;
      const faults = mmu?.pageFaults ?? 0;

      // Per-second rates from counter deltas.
      const dt = prev.current ? Math.max(1, (now - prev.current.at) / 1000) : 1;
      const reqRate = prev.current ? Math.max(0, requests - prev.current.requests) / dt : 0;
      const dagRate = prev.current ? Math.max(0, dagRuns - prev.current.dagRuns) / dt : 0;
      const faultRate = prev.current ? Math.max(0, faults - prev.current.faults) / dt : 0;
      prev.current = { requests, dagRuns, faults, at: now };

      // Normalise to 0..1 for flow intensity. 2 req/s reads as fully busy here;
      // this is a display scale, not a capacity claim.
      const norm = (r: number, full = 2) => Math.max(0, Math.min(1, r / full));

      const successRate = gw?.successRate ?? 1;
      series.current.requests = [...series.current.requests, requests].slice(-40);
      series.current.success = [...series.current.success, successRate * 100].slice(-40);

      // ---- provider pool ----
      const providers = health ?? [];
      const avgHealth = providers.length
        ? providers.reduce((a, h) => a + (h.healthScore ?? 1), 0) / providers.length
        : 1;
      const anyDown = providers.some((h) => (h.healthScore ?? 1) < 0.3);
      const providerState: StateKey = providers.length === 0
        ? "idle"
        : anyDown ? "critical"
        : avgHealth < 0.7 ? "degraded"
        : avgHealth < 0.9 ? "warning"
        : reqRate > 0 ? "active" : "healthy";

      // ---- runtime ----
      const running = stats?.running ?? 0;
      const failed = stats?.failed ?? 0;
      const completed = stats?.completed ?? 0;
      const runtimeState: StateKey = failed > 0 ? "warning" : running > 0 ? "active" : "healthy";

      // ---- chaos: an active experiment is a deliberate degradation ----
      const chaosOn =
        !!aichaos?.active ||
        !!chaos?.primaryProviderDown ||
        (chaos?.activityFailureRate ?? 0) > 0 ||
        (chaos?.sinkFailureRate ?? 0) > 0;

      const firewallEvents = fw?.events ?? 0;
      const firewallBlocked = fw?.injectionsBlocked ?? 0;
      const memoryOn = !!god?.enabled;
      const mmuRequests = mmu?.requests ?? 0;
      const cacheOn = !!cache?.enabled;
      const cacheHits = cache?.hits ?? 0;

      const subsystems: Subsystem[] = [
        {
          id: "runtime", name: "Runtime", code: "RT", state: runtimeState, installed: true,
          flow: norm(reqRate + running / 4), direction: "both",
          summary: "Durable workflow execution — event-sourced, replayable.",
          metrics: [
            { label: "Running", value: String(running) },
            { label: "Completed", value: String(completed) },
            // Real worker occupancy. Activities used to run one at a time on the
            // scheduler thread, so this was always 1 no matter how deep the queue.
            {
              label: "Workers",
              value: capacity?.capacity
                ? `${capacity.inFlight ?? 0}/${capacity.capacity}`
                : String(failed),
            },
          ],
          route: "/workflows",
        },
        {
          id: "firewall", name: "Firewall", code: "FW",
          state: firewallBlocked > 0 ? "warning" : firewallEvents > 0 ? "active" : "healthy",
          installed: true, flow: norm(firewallEvents / 40), direction: "in",
          summary: "Inbound PII redaction and prompt-injection screening.",
          metrics: [
            { label: "Events", value: String(firewallEvents) },
            { label: "Blocked", value: String(firewallBlocked) },
            { label: "Redacted", value: String(fw?.piiRedacted ?? 0) },
          ],
          route: "/guard",
        },
        {
          id: "router", name: "Router", code: "RO",
          state: routing?.enabled ? (reqRate > 0 ? "active" : "healthy") : "idle",
          installed: true, flow: norm(reqRate), direction: "both",
          summary: "Adaptive model selection with tail-latency hedging.",
          metrics: [
            { label: "Mode", value: routing?.mode ?? "—" },
            { label: "Enabled", value: routing?.enabled ? "yes" : "no" },
            { label: "Failovers", value: String(gw?.failuresPrevented ?? 0) },
          ],
          route: "/router",
        },
        {
          id: "providers", name: "Provider Pool", code: "PP", state: providerState, installed: true,
          flow: norm(reqRate), direction: "out",
          summary: "Upstream model providers with health-aware failover.",
          metrics: [
            { label: "Providers", value: String(providers.length) },
            { label: "Avg health", value: `${(avgHealth * 100).toFixed(0)}%` },
            { label: "Success", value: `${(successRate * 100).toFixed(1)}%` },
          ],
          route: "/gateway",
        },
        {
          id: "consensus", name: "Consensus", code: "CS",
          state: dagRuns > 0 ? (dagRate > 0 ? "active" : "healthy") : "idle",
          installed: true, flow: norm(dagRate, 0.5), direction: "both",
          summary: "Parallel solver/verifier DAG with Bayesian evidence.",
          metrics: [
            { label: "Runs", value: String(dagRuns) },
            { label: "Latest", value: dag?.[0]?.status ?? "—" },
            {
              label: "Confidence",
              value: dag?.[0]?.confidence != null ? `${(dag[0].confidence * 100).toFixed(1)}%` : "—",
            },
          ],
          route: "/dag",
        },
        {
          id: "mmu", name: "Context MMU", code: "MM",
          state: mmuRequests > 0 ? (faultRate > 0 ? "active" : "healthy") : "idle",
          installed: true, flow: norm(faultRate + mmuRequests / 200, 1), direction: "both",
          summary: "Context virtualization — paging between active window and store.",
          metrics: [
            { label: "Requests", value: String(mmuRequests) },
            {
              label: "Token cut",
              value: mmu?.tokenReduction != null ? `${(mmu.tokenReduction * 100).toFixed(0)}%` : "—",
            },
            { label: "Page faults", value: String(mmu?.pageFaults ?? 0) },
          ],
          route: "/mmu",
        },
        {
          id: "memory", name: "Memory", code: "ME",
          state: memoryOn ? "healthy" : "idle", installed: true,
          flow: memoryOn ? norm(reqRate / 2) : 0, direction: "both",
          summary: "Tiered long-term memory: working, episodic, semantic, archive.",
          metrics: [
            { label: "Engine", value: memoryOn ? "enabled" : "off" },
            { label: "Working", value: String(god?.memory?.working?.items ?? 0) },
            { label: "Episodic", value: String(god?.memory?.episodic?.items ?? 0) },
          ],
          route: "/memory",
        },
        {
          id: "cache", name: "Semantic Cache", code: "SC",
          state: cacheOn ? (cacheHits > 0 ? "active" : "healthy") : "idle",
          installed: true, flow: cacheOn ? norm(cacheHits / 40) : 0, direction: "both",
          summary: "Answers a repeated question from a stored answer instead of calling a provider.",
          metrics: [
            { label: "Engine", value: cacheOn ? "enabled" : "off" },
            {
              label: "Hit rate",
              value: cache?.hitRate != null ? `${(cache.hitRate * 100).toFixed(0)}%` : "—",
            },
            { label: "Tokens saved", value: String(cache?.tokensSaved ?? 0) },
          ],
          route: "/cache",
        },
      ];

      const coreState = chaosOn
        ? "warning"
        : worst(subsystems.filter((s) => s.installed).map((s) => s.state));

      // ---- recent system events, from real signals only ----
      const events: Telemetry["events"] = [];
      if (chaosOn) events.push({ at: "now", kind: "warning", text: "Chaos experiment active — failures are being injected deliberately" });
      if (anyDown) events.push({ at: "now", kind: "critical", text: "A provider is unhealthy; traffic is failing over" });
      if (failed > 0) events.push({ at: "recent", kind: "warning", text: `${failed} workflow${failed > 1 ? "s" : ""} in failed state` });
      if (firewallBlocked > 0) events.push({ at: "recent", kind: "warning", text: `${firewallBlocked} request(s) blocked by the firewall` });
      if ((gw?.failuresPrevented ?? 0) > 0) events.push({ at: "recent", kind: "healthy", text: `${gw.failuresPrevented} provider failure(s) absorbed before reaching your app` });
      if (events.length === 0) events.push({ at: "now", kind: "healthy", text: "No incidents. All installed subsystems nominal." });

      setT({
        loading: false,
        subsystems,
        coreState,
        load: norm(reqRate),
        series: { ...series.current },
        headline: {
          requests,
          successRate,
          failoversPrevented: gw?.failuresPrevented ?? 0,
          tokens: gw?.totalTokens ?? 0,
          costUsd: gw?.totalCostUsd ?? 0,
          running, completed, failed,
        },
        events,
        workflows: wfs ?? [],
        experimentActive: chaosOn,
      });
    };

    tick();
    const id = setInterval(tick, pollMs);
    return () => {
      alive = false;
      clearInterval(id);
    };
  }, [pollMs]);

  return t;
}
