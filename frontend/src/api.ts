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

// --- V3 developer portal: session-token auth (stored client-side) ---
const SESSION_KEY = "continuum.portal.session";

function authHeaders(): Record<string, string> {
  const t = localStorage.getItem(SESSION_KEY);
  return t ? { Authorization: `Bearer ${t}` } : {};
}

async function portalHttp<T>(path: string, method: string, body?: unknown): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method,
    headers: { "Content-Type": "application/json", ...authHeaders() },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  if (!res.ok) {
    let message = `${res.status}`;
    try {
      message = (await res.json()).message ?? message;
    } catch {
      /* ignore */
    }
    throw new Error(message);
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

export const portal = {
  session: () => localStorage.getItem(SESSION_KEY),
  setSession: (t: string | null) =>
    t ? localStorage.setItem(SESSION_KEY, t) : localStorage.removeItem(SESSION_KEY),

  async signup(name: string, email: string, password: string) {
    const r = await portalHttp<any>("/api/portal/developer/signup", "POST", { name, email, password });
    portal.setSession(r.sessionToken);
    return r;
  },
  async login(email: string, password: string) {
    const r = await portalHttp<any>("/api/portal/developer/login", "POST", { email, password });
    portal.setSession(r.sessionToken);
    return r;
  },
  logout: () => portal.setSession(null),

  me: () => portalHttp<any>("/api/portal/developer/me", "GET"),
  keys: () => portalHttp<any[]>("/api/portal/developer/keys", "GET"),
  issueKey: (label?: string) => portalHttp<any>("/api/portal/developer/keys", "POST", { label }),
  revokeKey: (id: number) => portalHttp<any>(`/api/portal/developer/keys/${id}`, "DELETE"),
  credentials: () => portalHttp<any[]>("/api/portal/developer/credentials", "GET"),
  storeCredential: (provider: string, secret: string) =>
    portalHttp<any>("/api/portal/developer/credentials", "POST", { provider, secret }),
  deleteCredential: (provider: string) =>
    portalHttp<any>(`/api/portal/developer/credentials/${provider}`, "DELETE"),
  verifyCredential: (provider: string) =>
    portalHttp<any>(`/api/portal/developer/credentials/${provider}/verify`, "POST"),
  setRoutingPreference: (useOwnKeysPrimary: boolean) =>
    portalHttp<any>("/api/portal/developer/routing-preference", "PUT", { useOwnKeysPrimary }),
  playground: (body: unknown) => portalHttp<any>("/api/portal/developer/playground", "POST", body),
  stats: () => portalHttp<any>("/api/portal/developer/stats", "GET"),

  // --- V4 Autopilot ---
  autopilot: {
    status: () => portalHttp<any>("/api/portal/developer/autopilot/status", "GET"),
    enable: (body: unknown) => portalHttp<any>("/api/portal/developer/autopilot/enable", "POST", body),
    disable: () => portalHttp<any>("/api/portal/developer/autopilot/disable", "POST"),
    setAutoApply: (value: boolean) =>
      portalHttp<any>(`/api/portal/developer/autopilot/auto-apply?value=${value}`, "PUT"),
    propose: () => portalHttp<any>("/api/portal/developer/autopilot/propose", "POST"),
    bundles: () => portalHttp<any[]>("/api/portal/developer/autopilot/bundles", "GET"),
    recommendations: () => portalHttp<any[]>("/api/portal/developer/autopilot/recommendations", "GET"),
    accept: (id: number) => portalHttp<any>(`/api/portal/developer/autopilot/recommendations/${id}/accept`, "POST"),
    reject: (id: number) => portalHttp<any>(`/api/portal/developer/autopilot/recommendations/${id}/reject`, "POST"),
    canary: () => portalHttp<any[]>("/api/portal/developer/autopilot/canary", "GET"),
    rollbacks: () => portalHttp<any[]>("/api/portal/developer/autopilot/rollbacks", "GET"),
    decisions: () => portalHttp<any[]>("/api/portal/developer/autopilot/decisions?limit=30", "GET"),
    rollback: () => portalHttp<any>("/api/portal/developer/autopilot/rollback", "POST"),
  },

  // --- V5 God Mode ---
  godmode: {
    status: () => portalHttp<any>("/api/portal/developer/godmode/status", "GET"),
    enable: () => portalHttp<any>("/api/portal/developer/godmode/enable", "POST"),
    disable: () => portalHttp<any>("/api/portal/developer/godmode/disable", "POST"),
    settings: (body: unknown) => portalHttp<any>("/api/portal/developer/godmode/settings", "PUT", body),
    ingest: (sessionId: string, role: string, content: string) =>
      portalHttp<any>("/api/portal/developer/godmode/memory/ingest", "POST", { sessionId, role, content }),
    consolidate: () => portalHttp<any>("/api/portal/developer/godmode/memory/consolidate", "POST"),
    retrieve: (query: string, limit = 5) =>
      portalHttp<any[]>("/api/portal/developer/godmode/memory/retrieve", "POST", { query, limit }),
    graph: () => portalHttp<any>("/api/portal/developer/godmode/memory/graph", "GET"),
    wipe: () => portalHttp<any>("/api/portal/developer/godmode/memory", "DELETE"),
    actions: () => portalHttp<any[]>("/api/portal/developer/godmode/actions", "GET"),
    simulate: (scenario: string, candidateBundleId?: number, baselineBundleId?: number) =>
      portalHttp<any>("/api/portal/developer/godmode/twin/simulate", "POST",
        { scenario, candidateBundleId, baselineBundleId }),
    simulations: () => portalHttp<any[]>("/api/portal/developer/godmode/twin/simulations", "GET"),
  },

  // --- V6 Consensus DAG Engine ---
  v6: {
    status: () => portalHttp<any>("/api/portal/developer/v6/status", "GET"),
    enable: () => portalHttp<any>("/api/portal/developer/v6/enable", "POST"),
    disable: () => portalHttp<any>("/api/portal/developer/v6/disable", "POST"),
  },

  // --- V7 Context MMU ---
  v7: {
    status: () => portalHttp<any>("/api/portal/developer/v7/status", "GET"),
    enable: () => portalHttp<any>("/api/portal/developer/v7/enable", "POST"),
    disable: () => portalHttp<any>("/api/portal/developer/v7/disable", "POST"),
  },

  // --- Billing & usage ---
  billing: () => portalHttp<any>("/api/portal/developer/billing", "GET"),
  setPlan: (plan: string) => portalHttp<any>("/api/portal/developer/billing/plan", "PUT", { plan }),

  // --- Account & settings ---
  changePassword: (currentPassword: string, newPassword: string) =>
    portalHttp<any>("/api/portal/developer/account/password", "POST", { currentPassword, newPassword }),
  changeEmail: (email: string) => portalHttp<any>("/api/portal/developer/account/email", "PUT", { email }),
  deleteAccount: () => portalHttp<any>("/api/portal/developer/account", "DELETE"),
  invites: () => portalHttp<any[]>("/api/portal/developer/account/invites", "GET"),
  invite: (email: string) => portalHttp<any>("/api/portal/developer/account/invites", "POST", { email }),
};
