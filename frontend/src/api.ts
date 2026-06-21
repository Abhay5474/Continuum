import type {
  ChaosState,
  CostReport,
  Meta,
  Stats,
  WorkflowDetail,
  WorkflowSummary,
} from "./types";

const BASE = import.meta.env.VITE_API_BASE ?? "";

async function http<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    headers: { "Content-Type": "application/json" },
    ...init,
  });
  if (!res.ok) {
    const text = await res.text();
    throw new Error(`${res.status}: ${text}`);
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export const api = {
  meta: () => http<Meta>("/api/meta"),
  stats: () => http<Stats>("/api/stats"),
  costs: () => http<CostReport>("/api/costs"),
  workflows: () => http<WorkflowSummary[]>("/api/workflows?limit=100"),
  workflow: (id: string) => http<WorkflowDetail>(`/api/workflows/${id}`),
  start: (workflowType: string, input: unknown) =>
    http<{ workflowId: string; status: string }>("/api/workflows", {
      method: "POST",
      body: JSON.stringify({ workflowType, input }),
    }),
  chaos: () => http<ChaosState>("/api/chaos"),
  chaosPost: (path: string) => http<ChaosState>(`/api/chaos/${path}`, { method: "POST" }),

  // --- V2 extensions ---
  get: <T>(path: string) => http<T>(path),
  post: <T>(path: string, body?: unknown) =>
    http<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }),
};
