import type {
  ChaosState,
  CostReport,
  Meta,
  Stats,
  WorkflowDetail,
  WorkflowSummary,
} from "./types";

const BASE = import.meta.env.VITE_API_BASE ?? "";
const SESSION_KEY = "continuum.portal.session";
const OPERATOR_KEY = "continuum.portal.operator";

/** Raised when the server rejects the session, so pages can prompt a sign-in. */
export class UnauthorizedError extends Error {
  constructor(message = "Sign in to view this data.") {
    super(message);
    this.name = "UnauthorizedError";
  }
}

/**
 * Raised on a 403. Engine-wide settings are the operator's, so a developer
 * hitting one of those switches used to get a bare "belongs to another account",
 * which is both wrong and unactionable. Pages check {@link needsOperator} and
 * offer to elevate instead.
 */
export class ForbiddenError extends Error {
  readonly needsOperator: boolean;
  constructor(message = "This resource belongs to another account.", needsOperator = false) {
    super(message);
    this.name = "ForbiddenError";
    this.needsOperator = needsOperator;
  }
}

function sessionToken(): string | null {
  return localStorage.getItem(SESSION_KEY);
}

/**
 * Operator access is *additive*, not a different login.
 *
 * <p>An operator session has no developer id, so signing in as one would break
 * every tenant-scoped page in the console — you would gain the routing switches
 * and lose your keys, workflows and billing. Instead the operator token is held
 * alongside the developer session and sent only on the engine-wide endpoints, so
 * one person operating their own deployment can do both without switching
 * accounts.
 */
function operatorToken(): string | null {
  return localStorage.getItem(OPERATOR_KEY);
}

export const hasOperator = () => operatorToken() !== null;

/**
 * Console API calls. These are now authenticated: the backend scopes every
 * response to the signed-in developer, so the token must travel with each request.
 */
async function http<T>(path: string, init?: RequestInit, asOperator = false): Promise<T> {
  // On an operator-only route, prefer the elevated token when one is held; fall
  // back to the developer session so the request still reaches the server and
  // comes back as a 403 the UI can explain.
  const token = (asOperator && operatorToken()) || sessionToken();
  const res = await fetch(`${BASE}${path}`, {
    ...init,
    headers: {
      "Content-Type": "application/json",
      ...(token ? { Authorization: `Bearer ${token}` } : {}),
      ...(init?.headers ?? {}),
    },
  });
  if (res.status === 401) throw new UnauthorizedError();
  if (res.status === 403) {
    throw asOperator
      ? new ForbiddenError("This control changes engine-wide behaviour, so it needs operator access.", true)
      : new ForbiddenError();
  }
  if (!res.ok) {
    const text = await res.text();
    const err = new Error(`${res.status}: ${text}`) as Error & {
      status?: number;
      body?: unknown;
    };
    err.status = res.status;
    // Some endpoints answer a question with a non-2xx status — a detected loop
    // is a 409 carrying its verdict, a missed deadline a 422 carrying why. The
    // parsed body is attached so a page can show the answer instead of a
    // stringified status line.
    try {
      err.body = JSON.parse(text);
    } catch {
      /* not JSON; the message already carries the text */
    }
    throw err;
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

  /**
   * Same as {@link post}, for endpoints the backend gates to the operator —
   * routing, hedging and the model catalogue. Sends the elevated token when one
   * is held.
   */
  opPost: <T>(path: string, body?: unknown) =>
    http<T>(path, { method: "POST", body: body === undefined ? undefined : JSON.stringify(body) }, true),
};

// --- V3 developer portal: session-token auth (stored client-side) ---

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
      const body = await res.json();
      // The API reports failures as {error}; some handlers use {message}. Reading
      // only one of them left the user staring at a bare status code.
      message = body.message ?? body.error ?? message;
    } catch {
      /* a non-JSON body leaves the status code as the best available message */
    }
    if (res.status === 401) throw new UnauthorizedError(message);
    throw new Error(message);
  }
  return res.status === 204 ? (undefined as T) : ((await res.json()) as T);
}

/**
 * A multipart POST.
 *
 * <p>Separate from {@link portalHttp} because the Content-Type must be left
 * unset: the browser has to add its own boundary, and setting the header by
 * hand produces a body the server cannot parse.
 */
async function portalUpload<T>(path: string, form: FormData): Promise<T> {
  const res = await fetch(`${BASE}${path}`, {
    method: "POST",
    headers: { ...authHeaders() },
    body: form,
  });
  if (!res.ok) {
    let message = `${res.status}`;
    try {
      const body = await res.json();
      message = body.message ?? body.error ?? message;
    } catch {
      /* a non-JSON body leaves the status code as the best available message */
    }
    if (res.status === 401) throw new UnauthorizedError(message);
    throw new Error(message);
  }
  return (await res.json()) as T;
}

/**
 * The signed-in role, read from the session token's own payload.
 *
 * <p>Purely to decide what to render: engine-wide settings are the operator's,
 * so offering a developer a switch that will come back 403 is a worse experience
 * than showing it disabled and saying why. The server enforces this regardless —
 * nothing here is a security decision.
 */
export function sessionRole(): "DEVELOPER" | "OPERATOR" | null {
  const t = localStorage.getItem(SESSION_KEY);
  if (!t || !t.includes(".")) return null;
  try {
    const payload = atob(t.split(".")[0].replace(/-/g, "+").replace(/_/g, "/"));
    const role = payload.split(":")[0];
    return role === "OPERATOR" || role === "DEVELOPER" ? role : null;
  } catch {
    return null;
  }
}

/**
 * Whether engine-wide controls should be live.
 *
 * <p>True either because you signed in as the operator outright, or because you
 * elevated on top of a developer session. Without the second case every
 * operator-gated switch in the console was permanently disabled with no route to
 * enabling it — the control existed but could never be used.
 */
export const isOperator = () => sessionRole() === "OPERATOR" || hasOperator();

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
  logout: () => {
    portal.setSession(null);
    portal.dropOperator();
  },

  // --- Operator elevation -------------------------------------------------
  // Held alongside the developer session rather than replacing it; see
  // operatorToken() above for why.
  hasOperator,
  async elevate(token: string) {
    const r = await portalHttp<any>("/api/portal/operator/login", "POST", { token });
    localStorage.setItem(OPERATOR_KEY, r.sessionToken);
    return r;
  },
  dropOperator: () => localStorage.removeItem(OPERATOR_KEY),

  /** Escape hatch for portal-scoped reads that have no dedicated helper. */
  get: <T>(path: string) => portalHttp<T>(path, "GET"),

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

  // --- V8 Prompt firewall & compression ---
  v8: {
    status: () => portalHttp<any>("/api/portal/developer/v8/status", "GET"),
    setFirewall: (on: boolean) =>
      portalHttp<any>(`/api/portal/developer/v8/firewall/${on ? "enable" : "disable"}`, "POST"),
    setCompression: (on: boolean) =>
      portalHttp<any>(`/api/portal/developer/v8/compression/${on ? "enable" : "disable"}`, "POST"),
    firewallProfile: () => portalHttp<any>("/api/portal/developer/v8/firewall/profile", "GET"),
    compressionProfile: () => portalHttp<any>("/api/portal/developer/v8/compression/profile", "GET"),
  },

  // --- Semantic cache ---
  cache: {
    status: () => portalHttp<any>("/api/portal/developer/cache/status", "GET"),
    setEnabled: (on: boolean) =>
      portalHttp<any>(`/api/portal/developer/cache/${on ? "enable" : "disable"}`, "POST"),
    configure: (body: { similarityThreshold?: number; ttlSeconds?: number }) =>
      portalHttp<any>("/api/portal/developer/cache/settings", "PUT", body),
    entries: (limit = 25) => portalHttp<any[]>(`/api/portal/developer/cache/entries?limit=${limit}`, "GET"),
    clear: () => portalHttp<any>("/api/portal/developer/cache", "DELETE"),
  },

  // --- Verify-then-escalate cascade ---
  cascade: {
    status: () => portalHttp<any>("/api/portal/developer/cascade/status", "GET"),
    setEnabled: (on: boolean) =>
      portalHttp<any>(`/api/portal/developer/cascade/${on ? "enable" : "disable"}`, "POST"),
    configure: (body: { threshold?: number; auditRate?: number; escalationCap?: number }) =>
      portalHttp<any>("/api/portal/developer/cascade/settings", "PUT", body),
    tiers: () => portalHttp<any[]>("/api/portal/developer/cascade/tiers", "GET"),
    decisions: (limit = 40) =>
      portalHttp<any[]>(`/api/portal/developer/cascade/decisions?limit=${limit}`, "GET"),
    reset: () => portalHttp<any>("/api/portal/developer/cascade", "DELETE"),
  },

  // --- Semantic uncertainty ---
  uncertainty: {
    status: () => portalHttp<any>("/api/portal/developer/uncertainty/status", "GET"),
    configure: (body: { mode?: string; samples?: number; temperature?: number; lowConfidence?: number; adaptiveEnabled?: boolean; overturnThreshold?: number }) =>
      portalHttp<any>("/api/portal/developer/uncertainty/settings", "PUT", body),
    measurements: (limit = 20) =>
      portalHttp<any[]>(`/api/portal/developer/uncertainty/measurements?limit=${limit}`, "GET"),
    clear: () => portalHttp<any>("/api/portal/developer/uncertainty", "DELETE"),
  },

  // --- Response quality gate ---
  quality: {
    status: () => portalHttp<any>("/api/portal/developer/quality/status", "GET"),
    configure: (body: {
      mode?: string; threshold?: number; maxRepairs?: number; budgetMs?: number;
      repairEngineEnabled?: boolean;
    }) => portalHttp<any>("/api/portal/developer/quality/settings", "PUT", body),
    checks: (limit = 30) => portalHttp<any[]>(`/api/portal/developer/quality/checks?limit=${limit}`, "GET"),
    clear: () => portalHttp<any>("/api/portal/developer/quality", "DELETE"),

    /** Attempt-by-attempt repair ledger, including the attempts that were discarded. */
    repairs: (limit = 30) =>
      portalHttp<any[]>(`/api/portal/developer/quality/repairs?limit=${limit}`, "GET"),
    repairSummary: () => portalHttp<any>("/api/portal/developer/quality/repairs/summary", "GET"),
    clearRepairs: () => portalHttp<any>("/api/portal/developer/quality/repairs", "DELETE"),
  },

  // --- Semantic circuit breaker ---
  degradation: {
    status: () => portalHttp<any>("/api/portal/developer/degradation/status", "GET"),
    configure: (body: { enabled?: boolean }) =>
      portalHttp<any>("/api/portal/developer/degradation/settings", "PUT", body),
    clear: () => portalHttp<any>("/api/portal/developer/degradation", "DELETE"),
  },

  provenance: {
    status: () => portalHttp<any>("/api/portal/developer/provenance/status", "GET"),
    configure: (body: { enabled?: boolean }) =>
      portalHttp<any>("/api/portal/developer/provenance/settings", "PUT", body),
    requests: (limit = 30) =>
      portalHttp<string[]>(`/api/portal/developer/provenance/requests?limit=${limit}`, "GET"),
    graph: (id: string) =>
      portalHttp<any>(`/api/portal/developer/provenance/requests/${id}`, "GET"),
    otel: (id: string) =>
      portalHttp<any>(`/api/portal/developer/provenance/requests/${id}/otel`, "GET"),
    clear: () => portalHttp<any>("/api/portal/developer/provenance", "DELETE"),
  },

  admission: {
    status: () => portalHttp<any>("/api/portal/developer/admission/status", "GET"),
    configure: (body: { enabled?: boolean }) =>
      portalHttp<any>("/api/portal/developer/admission/settings", "PUT", body),
    /** Forgets learned limits, so a changed provider quota is re-inferred. */
    reset: () => portalHttp<any>("/api/portal/developer/admission", "DELETE"),
  },

  compressionPolicy: {
    status: () => portalHttp<any>("/api/portal/developer/compression-policy/status", "GET"),
    configure: (body: { enabled?: boolean }) =>
      portalHttp<any>("/api/portal/developer/compression-policy/settings", "PUT", body),
    reset: () => portalHttp<any>("/api/portal/developer/compression-policy/reset", "POST"),
  },

  costAdmission: {
    status: () => portalHttp<any>("/api/portal/developer/cost-admission/status", "GET"),
    configure: (body: { enabled?: boolean; requestsPerMin?: number; tokensPerMin?: number }) =>
      portalHttp<any>("/api/portal/developer/cost-admission/settings", "PUT", body),
    reset: () => portalHttp<any>("/api/portal/developer/cost-admission/reset", "POST"),
  },

  counterfactual: {
    status: () => portalHttp<any>("/api/portal/developer/counterfactual/status", "GET"),
    arms: () => portalHttp<string[]>("/api/portal/developer/counterfactual/arms", "GET"),
    configure: (body: { enabled?: boolean }) =>
      portalHttp<any>("/api/portal/developer/counterfactual/settings", "PUT", body),
    /** Replays logged traffic against a candidate policy. Reads only; runs nothing. */
    evaluate: (body: {
      alwaysArm?: string; at?: number; below?: string; above?: string; limit?: number;
    }) => portalHttp<any>("/api/portal/developer/counterfactual/evaluate", "POST", body),
  },

  loops: {
    status: () => portalHttp<any>("/api/portal/developer/loops/status", "GET"),
    configure: (body: { enabled?: boolean; mode?: string }) =>
      portalHttp<any>("/api/portal/developer/loops/settings", "PUT", body),
    /**
     * Runs the detector over a sequence of steps without an agent behind it.
     * Returns 409 in HALT mode when a loop is found, which is the answer rather
     * than an error — the caller asked whether to continue and it said no.
     */
    inspect: (body: { workflowId?: string; steps: string[]; progress?: boolean[] }) =>
      portalHttp<any>("/api/portal/developer/loops/inspect", "POST", body),
    clear: () => portalHttp<any>("/api/portal/developer/loops", "DELETE"),
  },

  saga: {
    status: () => portalHttp<any>("/api/portal/developer/saga/status", "GET"),
    configure: (body: { enabled?: boolean }) =>
      portalHttp<any>("/api/portal/developer/saga/settings", "PUT", body),
    clear: () => portalHttp<any>("/api/portal/developer/saga", "DELETE"),
  },

  scheduling: {
    status: () => portalHttp<any>("/api/portal/developer/scheduling/status", "GET"),
    configure: (body: { enabled?: boolean }) =>
      portalHttp<any>("/api/portal/developer/scheduling/settings", "PUT", body),
    /** Orders a hypothetical queue without running anything. */
    order: (body: {
      tasks: {
        id: string;
        priority?: string;
        waitedSeconds?: number;
        deadlineSeconds?: number;
        estimateSeconds?: number;
      }[];
    }) => portalHttp<any>("/api/portal/developer/scheduling/order", "POST", body),
    reset: () => portalHttp<any>("/api/portal/developer/scheduling/reset", "POST"),
  },

  breaker: {
    status: () => portalHttp<any>("/api/portal/developer/breaker/status", "GET"),
    configure: (body: {
      enabled?: boolean; warmup?: number; slack?: number; threshold?: number; cooldownSeconds?: number;
    }) => portalHttp<any>("/api/portal/developer/breaker/settings", "PUT", body),
    events: (limit = 30) => portalHttp<any[]>(`/api/portal/developer/breaker/events?limit=${limit}`, "GET"),
    reset: (provider: string, model: string) =>
      portalHttp<any>(
        `/api/portal/developer/breaker/reset?provider=${encodeURIComponent(provider)}&model=${encodeURIComponent(model)}`,
        "POST"
      ),
    clear: () => portalHttp<any>("/api/portal/developer/breaker", "DELETE"),
  },

  // --- Specialist models ---
  specialists: {
    providers: () => portalHttp<any[]>("/api/portal/developer/specialists/providers", "GET"),
    connections: () => portalHttp<any[]>("/api/portal/developer/specialists/connections", "GET"),
    addConnection: (body: {
      name: string; provider: string; baseUrl?: string; authStyle?: string;
      authParam?: string; secret?: string;
    }) => portalHttp<any>("/api/portal/developer/specialists/connections", "POST", body),
    rotateSecret: (id: number, secret: string) =>
      portalHttp<any>(`/api/portal/developer/specialists/connections/${id}/secret`, "POST", { secret }),
    deleteConnection: (id: number) =>
      portalHttp<any>(`/api/portal/developer/specialists/connections/${id}`, "DELETE"),

    /** The Hub: search for something to plug in. */
    catalogue: (q?: string, limit = 25) =>
      portalHttp<any>(
        `/api/portal/developer/specialists/catalogue?limit=${limit}` +
          (q ? `&q=${encodeURIComponent(q)}` : ""),
        "GET"
      ),
    /** Drops every live directory's cache for this tenant, then searches again. */
    refreshCatalogue: (q?: string, limit = 25) =>
      portalHttp<any>(
        `/api/portal/developer/specialists/catalogue/refresh?limit=${limit}` +
          (q ? `&q=${encodeURIComponent(q)}` : ""),
        "POST"
      ),
    reusableConnections: (entryId: string) =>
      portalHttp<any[]>(
        `/api/portal/developer/specialists/catalogue/${entryId}/connections`,
        "GET"
      ),
    install: (entryId: string, body: {
      name: string; baseUrl?: string; modelPath?: string; minConfidence?: number;
      connectionId?: number; secret?: string;
    }) =>
      portalHttp<any>(
        `/api/portal/developer/specialists/catalogue/${entryId}/install`,
        "POST",
        body
      ),

    list: () => portalHttp<any[]>("/api/portal/developer/specialists", "GET"),
    add: (body: {
      connectionId: number; name: string; modelPath: string; inputKind?: string;
      minConfidence?: number; timeoutSeconds?: number;
    }) => portalHttp<any>("/api/portal/developer/specialists", "POST", body),
    configure: (id: number, body: { minConfidence?: number; timeoutSeconds?: number }) =>
      portalHttp<any>(`/api/portal/developer/specialists/${id}`, "PUT", body),
    probe: (id: number, sample?: unknown) =>
      portalHttp<any>(`/api/portal/developer/specialists/${id}/probe`, "POST", sample ?? {}),
    remove: (id: number) => portalHttp<any>(`/api/portal/developer/specialists/${id}`, "DELETE"),

    trace: (traceId: string) =>
      portalHttp<any>(`/api/portal/developer/specialists/traces/${traceId}`, "GET"),
    recentTraces: (limit = 20) =>
      portalHttp<string[]>(`/api/portal/developer/specialists/traces?limit=${limit}`, "GET"),
  },

  // --- Pipelines: input -> specialist -> context -> model ---
  pipelines: {
    list: () => portalHttp<any[]>("/api/portal/developer/pipelines", "GET"),
    add: (body: {
      name: string; description?: string; inputKind?: string;
      systemPrompt?: string; steps?: number[];
    }) => portalHttp<any>("/api/portal/developer/pipelines", "POST", body),
    update: (id: number, body: {
      description?: string; systemPrompt?: string; steps?: number[]; enabled?: boolean;
      policyEnabled?: boolean; strongThreshold?: number; weakThreshold?: number;
      declineOnNoEvidence?: boolean; routingEnabled?: boolean; verificationMode?: string;
      routing?: { specialistId: number; when: string; pattern: string | null }[];
    }) => portalHttp<any>(`/api/portal/developer/pipelines/${id}`, "PUT", body),
    remove: (id: number) => portalHttp<any>(`/api/portal/developer/pipelines/${id}`, "DELETE"),
    /** Runs it exactly as an application would, so the chain can be seen before going live. */
    run: (name: string, input: Record<string, unknown>, prompt?: string) =>
      portalHttp<any>(`/api/portal/developer/pipelines/${name}/run`, "POST", { input, prompt }),

    /**
     * The same run, with a file instead of base64 JSON.
     *
     * <p>The server picks the input key from the file's bytes, so a PDF becomes
     * a document and a JPEG an image without anything being declared here.
     */
    runFile: (name: string, file: File, prompt?: string) => {
      const form = new FormData();
      form.append("file", file);
      if (prompt) form.append("prompt", prompt);
      return portalUpload<any>(`/api/portal/developer/pipelines/${name}/run/upload`, form);
    },
  },

  // --- Context transformers: application data -> canonical LLM context ---
  context: {
    capabilities: () =>
      portalHttp<any[]>("/api/portal/developer/context/capabilities", "GET"),

    /**
     * Which request paths actually hand a transformed context to a model.
     *
     * <p>Pipelines always have. The chat endpoint does so only when switched
     * on, and this page used to draw one arrow to "the prompt" regardless.
     */
    status: () => portalHttp<any>("/api/portal/developer/context/status", "GET"),
    setGateway: (on: boolean) =>
      portalHttp<any>(
        `/api/portal/developer/context/gateway/${on ? "enable" : "disable"}`,
        "POST"
      ),

    /** Transforms an uploaded file. The server detects what it is from bytes. */
    transformFile: (file: File, budget = "standard") => {
      const form = new FormData();
      form.append("file", file);
      return portalUpload<any>(
        `/api/portal/developer/context/transform?budget=${budget}`,
        form
      );
    },

    /**
     * Transforms pasted text.
     *
     * <p>Sent as text/plain rather than JSON so a log containing quotes and
     * backslashes does not have to survive being escaped into a JSON string
     * first — which is exactly the kind of input this feature exists for.
     */
    transformText: async (text: string, filename = "pasted-input", budget = "standard") => {
      const res = await fetch(
        `${BASE}/api/portal/developer/context/transform` +
          `?budget=${budget}&filename=${encodeURIComponent(filename)}`,
        {
          method: "POST",
          headers: { "Content-Type": "text/plain", ...authHeaders() },
          body: text,
        }
      );
      if (!res.ok) {
        let message = `${res.status}`;
        try {
          const body = await res.json();
          message = body.message ?? body.error ?? message;
        } catch {
          /* status code is the best available message */
        }
        if (res.status === 401) throw new UnauthorizedError(message);
        throw new Error(message);
      }
      return res.json();
    },

    recent: (limit = 25) =>
      portalHttp<any[]>(`/api/portal/developer/context/transforms?limit=${limit}`, "GET"),
  },

  // --- Customer-defined workflows ---
  defs: {
    list: () => portalHttp<any[]>("/api/portal/developer/workflows/definitions", "GET"),
    get: (name: string, version?: number) =>
      portalHttp<any>(
        `/api/portal/developer/workflows/definitions/${name}${version ? `?version=${version}` : ""}`,
        "GET"
      ),
    publish: (name: string, spec: unknown) =>
      portalHttp<any>(`/api/portal/developer/workflows/definitions/${name}`, "POST", spec),
    remove: (name: string) =>
      portalHttp<any>(`/api/portal/developer/workflows/definitions/${name}`, "DELETE"),
    run: (name: string, input: unknown, version?: number) =>
      portalHttp<any>(
        `/api/portal/developer/workflows/definitions/${name}/run${version ? `?version=${version}` : ""}`,
        "POST",
        { input }
      ),
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
  revokeInvite: (id: number) => portalHttp<any>(`/api/portal/developer/account/invites/${id}`, "DELETE"),
  members: () => portalHttp<any[]>("/api/portal/developer/account/members", "GET"),

  // --- Joining someone else's account (no session yet, by definition) ---
  previewInvite: (token: string) =>
    portalHttp<any>(`/api/portal/developer/account/invites/preview?token=${encodeURIComponent(token)}`, "GET"),
  async acceptInvite(token: string, name: string, password: string) {
    const r = await portalHttp<any>("/api/portal/developer/account/invites/accept", "POST", {
      token,
      name,
      password,
    });
    portal.setSession(r.sessionToken);
    return r;
  },
};
