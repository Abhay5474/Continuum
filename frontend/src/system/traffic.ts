import { useEffect, useMemo, useState } from "react";
import { api } from "../api";
import { visibleInterval } from "./poll";

/**
 * Shapes derived from the recent request log, for the headline tiles.
 *
 * <p>A tile that says "84 requests" answers how many; the line under it answers
 * how they arrived — steady, a burst, or trailing off. Every series here comes
 * from the same log the request feed shows, oldest first so it reads left to
 * right.
 */
export type GatewayRequest = {
  id: number;
  createdAt: number | string;
  latencyMs?: number;
  tokens?: number;
  costUsd?: number;
  success?: boolean;
  failoverCount?: number;
  chosenProvider?: string;
  chosenModel?: string;
  complexity?: number;
};

const epochMs = (t: number | string) => (typeof t === "number" ? (t < 1e12 ? t * 1000 : t) : Date.parse(t));

export function trafficSeries(requests: GatewayRequest[], buckets = 16) {
  const oldestFirst = [...requests].sort((a, b) => epochMs(a.createdAt) - epochMs(b.createdAt));
  const times = oldestFirst.map((r) => epochMs(r.createdAt)).filter(Number.isFinite);
  let arrivals: number[] = [];
  if (times.length >= 2) {
    const lo = times[0];
    const span = Math.max(1, times[times.length - 1] - lo);
    arrivals = Array(buckets).fill(0);
    for (const t of times) arrivals[Math.min(buckets - 1, Math.floor(((t - lo) / span) * buckets))] += 1;
  }
  let run = 0;
  return {
    arrivals,
    latencies: oldestFirst.map((r) => r.latencyMs ?? 0),
    tokens: oldestFirst.map((r) => r.tokens ?? 0),
    cumulativeCost: oldestFirst.map((r) => (run += r.costUsd ?? 0)),
    /** 1 for a clean success, 0.5 for one that needed failover, 0 for a failure; rolling over five. */
    health: oldestFirst.map((_, i, all) => {
      const w = all.slice(Math.max(0, i - 4), i + 1);
      return w.reduce((n, r) => n + (r.success === false ? 0 : r.failoverCount ? 0.5 : 1), 0) / w.length;
    }),
  };
}

/** The caller's recent requests, polled while the tab is visible. */
export function useRecentRequests(limit = 100, everyMs = 5000) {
  const [requests, setRequests] = useState<GatewayRequest[]>([]);
  useEffect(() => {
    const load = () => api.get<GatewayRequest[]>(`/api/gateway/requests?limit=${limit}`).then(setRequests).catch(() => {});
    load();
    return visibleInterval(load, everyMs);
  }, [limit, everyMs]);
  const series = useMemo(() => trafficSeries(requests), [requests]);
  return { requests, series };
}
