import { toMillis } from "../time";

/*
 * A verification run as it executed: lanes of parallel work, attempts on a
 * clock, waits between them, the relations between claims, and the point
 * where the evidence was resolved.
 *
 * Built from what the trace API returns (ConsensusDagService#trace): the
 * execution timeline read from the event log, which exists from the run's
 * first event, and the projection written when it finished. Pure and free of
 * any drawing library, so every view of a run reads from the same model.
 */

// ---------------------------------------------------------------- the trace

export type TraceNode = {
  nodeKey: string;
  nodeType: string;
  claimId?: number | null;
  label?: string | null;
  status?: string | null;
  validity?: number | null;
  outputJson?: string | null;
  startedAt?: unknown;
  completedAt?: unknown;
};
export type TraceEdge = { fromKey: string; toKey: string; edgeType?: string | null; weight?: number | null };
export type TraceAttempt = {
  attempt: number;
  startedAt?: unknown;
  endedAt?: unknown;
  outcome: string;
  error?: string | null;
  workerId?: string | null;
  retryAt?: unknown;
};
export type TraceStep = {
  seq: number;
  activityType?: string | null;
  claimId?: number | null;
  check?: string | null;
  name?: string | null;
  scheduledAt?: unknown;
  status: string;
  maxAttempts?: number;
  attempts: TraceAttempt[];
};
export type Execution = { status: string; startedAt?: unknown; endedAt?: unknown; error?: string | null; steps: TraceStep[] };
export type Trace = {
  run?: { status?: string | null; finalConfidence?: number | null; uncertainty?: string | null } | null;
  nodes?: TraceNode[];
  edges?: TraceEdge[];
  execution?: Execution | null;
  claims?: { id: number; statement: string; dependsOn?: number[]; checks?: string[] }[];
};

// ---------------------------------------------------------------- the model

export type Phase = "plan" | "solve" | "verify" | "answer" | "step";

/** Milliseconds from the run's first event. */
export type Attempt = {
  n: number;
  start: number;
  /** Null while it is still running. */
  end: number | null;
  outcome: "ok" | "failed" | "running";
  error: string | null;
  /** When the retry after this attempt was due. */
  retryAt: number | null;
};

export type WorkStatus = "pass" | "fail" | "uncertain" | "done" | "running" | "waiting" | "retrying" | "failed";

export type Work = {
  /** The trace node key where there is one (planner, solver-2, verify-2-logic_consistency, synthesis). */
  id: string;
  phase: Phase;
  lane: number;
  sub: number;
  subs: number;
  /** A check's short name ("Logic"), or the phase ("Solver"). */
  title: string;
  scheduled: number;
  attempts: Attempt[];
  end: number | null;
  status: WorkStatus;
  /** A check's validity, or a solver's own confidence. */
  value: number | null;
  critical: boolean;
  /** What a check found, or why a step failed, for the tooltip. */
  detail: string | null;
};

export type Lane = {
  index: number;
  claimId: number | null;
  code: string;
  title: string;
  prior: number | null;
  posterior: number | null;
  survived: boolean | null;
};

export type Relation = {
  id: string;
  kind: "contradicts" | "supports" | "depends";
  from: number;
  to: number;
  weight: number;
  /** The conflict node a contradiction was recorded as. */
  node: string | null;
};

export type StageSpan = { phase: Phase | "resolve"; label: string; start: number; end: number };

export type Breakdown = { work: number; queued: number; backoff: number; orchestration: number };

export type ExecutionModel = {
  /** Epoch ms of the run's first event. */
  t0: number;
  /** Milliseconds the run took, or has taken so far. */
  span: number;
  live: boolean;
  /** When this model was made (epoch ms), so a live view can extrapolate "now". */
  madeAt: number;
  /** The last moment the trace knows of, from t0. */
  lastKnown: number;
  /** False when nothing in the trace was timed: positions are then by order alone. */
  timed: boolean;
  lanes: Lane[];
  work: Work[];
  relations: Relation[];
  gate: { at: number; confidence: number | null; uncertainty: string | null; resolved: boolean } | null;
  stages: StageSpan[];
  critical: string[];
  /** How the critical path spent the run's time; the four add up to the span. */
  breakdown: Breakdown;
  status: string;
};

const PHASE: Record<string, Phase> = { "dag.plan": "plan", "dag.solve": "solve", "dag.verify": "verify", "dag.synthesize": "answer" };
const NODE_PHASE: Record<string, Phase> = { PLANNER: "plan", SOLVER: "solve", VERIFIER: "verify", SYNTHESIS: "answer" };

export const CHECK_NAME: Record<string, string> = {
  LOGIC_CONSISTENCY: "Logic",
  EVIDENCE_GROUNDING: "Grounding",
  CONSTRAINT_CHECK: "Constraints",
  SCHEMA_ALIGNMENT: "Schema",
};

export const checkName = (check: string | null | undefined) =>
  check ? CHECK_NAME[check.toUpperCase()] ?? check.toLowerCase().replace(/_/g, " ") : "Check";

const TITLE: Record<Phase, string> = { plan: "Plan", solve: "Solver", verify: "Check", answer: "Answer", step: "Step" };

function keyOf(phase: Phase, claimId: number | null | undefined, check: string | null | undefined, seq: number) {
  if (phase === "plan") return "planner";
  if (phase === "answer") return "synthesis";
  if (phase === "solve" && claimId != null) return `solver-${claimId}`;
  if (phase === "verify" && claimId != null && check) return `verify-${claimId}-${check.toLowerCase()}`;
  return `step-${seq}`;
}

export function parseJson(json: string | null | undefined): any {
  if (!json) return null;
  try {
    return JSON.parse(json);
  } catch {
    return null;
  }
}

/** How long each kind of wait was on the way to `end`, along a chain of work. */
function breakdownOf(path: Work[], span: number, now: number): Breakdown {
  const b: Breakdown = { work: 0, queued: 0, backoff: 0, orchestration: 0 };
  let prev = 0;
  for (const w of path) {
    b.orchestration += Math.max(0, w.scheduled - prev);
    let ready = w.scheduled;
    w.attempts.forEach((a, i) => {
      if (i > 0) {
        const prior = w.attempts[i - 1];
        const due = prior.retryAt ?? prior.end ?? a.start;
        b.backoff += Math.max(0, Math.min(due, a.start) - (prior.end ?? a.start));
        ready = Math.min(due, a.start);
      }
      b.queued += Math.max(0, a.start - ready);
      b.work += Math.max(0, (a.end ?? now) - a.start);
    });
    // Still waiting to try again: the backoff so far, then the wait once due.
    const last = w.attempts[w.attempts.length - 1];
    if (w.end == null && last?.outcome === "failed" && last.end != null) {
      const due = last.retryAt ?? now;
      b.backoff += Math.max(0, Math.min(due, now) - last.end);
      b.queued += Math.max(0, now - due);
    }
    prev = Math.max(prev, w.end ?? now);
  }
  b.orchestration += Math.max(0, span - prev);
  return b;
}

/**
 * The chain of work that decided how long the run took. The engine decides
 * again each time something finishes, so whatever finished last before a step
 * was scheduled is what that step waited for; walking that back from the last
 * thing to finish gives the path.
 */
function criticalPath(work: Work[], now: number): Work[] {
  const endOf = (w: Work) => w.end ?? now;
  const path: Work[] = [];
  let cur = work.reduce<Work | null>((best, w) => (best === null || endOf(w) > endOf(best) ? w : best), null);
  while (cur && !path.includes(cur)) {
    path.unshift(cur);
    const t = cur.scheduled;
    cur = work.reduce<Work | null>((best, w) => {
      if (path.includes(w) || endOf(w) > t + 1) return best;
      return best === null || endOf(w) > endOf(best) ? w : best;
    }, null);
  }
  return path;
}

/** One step's attempts, from the event log's timeline, in ms from t0. */
export function attemptsOf(s: TraceStep, scheduled: number, rel: (v: unknown) => number | null): Attempt[] {
  return s.attempts.map((a) => {
    const start = rel(a.startedAt) ?? scheduled;
    const end = a.outcome === "RUNNING" ? null : rel(a.endedAt) ?? start;
    return {
      n: a.attempt,
      start,
      end,
      outcome: a.outcome === "COMPLETED" ? "ok" : a.outcome === "FAILED" ? "failed" : "running",
      error: a.error ?? null,
      retryAt: rel(a.retryAt),
    };
  });
}

/**
 * The model of one run. Null when there is nothing to draw yet. `now` is the
 * client's clock; a live run is drawn up to the later of it and the last event.
 */
export function buildExecution(trace: Trace | null | undefined, now = Date.now()): ExecutionModel | null {
  if (!trace) return null;
  const nodes = trace.nodes ?? [];
  const edges = trace.edges ?? [];
  const steps = trace.execution?.steps ?? [];
  if (steps.length === 0 && nodes.length === 0) return null;
  const nodeByKey = new Map(nodes.map((n) => [n.nodeKey, n]));

  // ---- the clock
  const stamps: number[] = [];
  const stamp = (v: unknown) => {
    const t = toMillis(v);
    if (t != null) stamps.push(t);
    return t;
  };
  stamp(trace.execution?.startedAt);
  for (const s of steps) {
    stamp(s.scheduledAt);
    // Not retryAt: that is when a retry is due, a time that may not have come yet.
    s.attempts.forEach((a) => (stamp(a.startedAt), stamp(a.endedAt)));
  }
  if (steps.length === 0) nodes.forEach((n) => (stamp(n.startedAt), stamp(n.completedAt)));
  stamp(trace.execution?.endedAt);
  const timed = stamps.length > 0;
  const t0 = toMillis(trace.execution?.startedAt) ?? (timed ? Math.min(...stamps) : now);
  const rel = (v: unknown): number | null => {
    const t = toMillis(v);
    return t == null ? null : Math.max(0, t - t0);
  };
  const live = (trace.execution?.status ?? trace.run?.status) === "RUNNING";
  const lastKnown = timed ? Math.max(...stamps) - t0 : 0;
  // A live run is drawn up to now; a client clock far ahead of the server's
  // would otherwise stretch it into the future.
  const nowRel = live ? Math.min(Math.max(lastKnown, now - t0), lastKnown + 60_000) : lastKnown;

  // ---- lanes: the orchestration lane, then one per claim
  const claimIds = new Map<number, string>();
  for (const c of trace.claims ?? []) claimIds.set(c.id, c.statement);
  for (const n of nodes) if (n.nodeType === "SOLVER" && n.claimId != null && !claimIds.has(n.claimId)) claimIds.set(n.claimId, n.label ?? "");
  for (const s of steps) if (s.claimId != null && !claimIds.has(s.claimId)) claimIds.set(s.claimId, "");
  const order = [...claimIds.keys()].sort((a, b) => a - b);
  const laneOf = new Map(order.map((id, i) => [id, i + 1]));

  const aggregator = nodes.find((n) => n.nodeType === "AGGREGATOR") ?? null;
  const scores = new Map<number, { prior: number; posterior: number; survived: boolean }>();
  for (const s of parseJson(aggregator?.outputJson)?.scores ?? []) {
    if (typeof s?.claimId === "number") scores.set(s.claimId, { prior: Number(s.prior), posterior: Number(s.posterior), survived: !!s.survived });
  }
  const lanes: Lane[] = [
    { index: 0, claimId: null, code: "", title: "Orchestration", prior: null, posterior: null, survived: null },
    ...order.map((id, i): Lane => {
      const sc = scores.get(id);
      const solver = nodeByKey.get(`solver-${id}`);
      return {
        index: i + 1,
        claimId: id,
        code: `C${id}`,
        title: claimIds.get(id) || `Claim ${id}`,
        prior: sc?.prior ?? solver?.validity ?? null,
        posterior: sc?.posterior ?? null,
        survived: sc ? sc.survived : solver?.status === "FAIL" ? false : solver?.status === "PASS" && aggregator ? true : null,
      };
    }),
  ];

  // ---- work, from the event log where there is one
  const work: Work[] = [];
  const add = (w: Omit<Work, "sub" | "subs" | "critical">) => work.push({ ...w, sub: 0, subs: 1, critical: false });
  const statusOf = (phase: Phase, stepStatus: string | null, node: TraceNode | undefined): WorkStatus => {
    if (stepStatus === "RUNNING") return "running";
    if (stepStatus === "SCHEDULED") return "waiting";
    if (stepStatus === "RETRYING") return "retrying";
    if (stepStatus === "FAILED") return "failed";
    if (phase === "verify" && node) return node.status === "PASS" ? "pass" : node.status === "FAIL" ? "fail" : "uncertain";
    return "done";
  };
  const detailOf = (phase: Phase, node: TraceNode | undefined): string | null => {
    const out = parseJson(node?.outputJson);
    if (phase === "verify" && out) {
      const fails: string[] = out.failureModes ?? [];
      const ev: string[] = out.evidence ?? [];
      return (fails.length ? fails : ev).slice(0, 2).join("; ") || null;
    }
    if (phase === "solve" && out?.model) return `${out.provider ?? ""} ${out.model}`.trim();
    return null;
  };

  if (steps.length > 0) {
    for (const s of steps) {
      const phase = PHASE[s.activityType ?? ""] ?? "step";
      const id = keyOf(phase, s.claimId, s.check, s.seq);
      const node = nodeByKey.get(id);
      const scheduled = rel(s.scheduledAt) ?? rel(s.attempts[0]?.startedAt) ?? 0;
      const attempts = attemptsOf(s, scheduled, rel);
      const last = attempts[attempts.length - 1];
      const failure = [...attempts].reverse().find((a) => a.error)?.error ?? null;
      add({
        id,
        phase,
        lane: phase === "solve" || phase === "verify" ? laneOf.get(s.claimId ?? -1) ?? 0 : 0,
        title: phase === "verify" ? checkName(s.check) : phase === "step" ? s.name || s.activityType || "Step" : TITLE[phase],
        scheduled,
        attempts,
        end: last && last.outcome !== "running" && s.status !== "RETRYING" ? last.end : null,
        status: statusOf(phase, s.status === "COMPLETED" ? null : s.status, node),
        value: node?.validity ?? null,
        detail: s.status === "FAILED" || s.status === "RETRYING" ? failure : detailOf(phase, node) ?? (failure ? `recovered from: ${failure}` : null),
      });
    }
  } else {
    // A run recorded before its events were kept, or whose events were trimmed:
    // the projection's own timings, or the order of the pipeline when untimed.
    const ORDER: Record<Phase, [number, number]> = { plan: [0, 900], solve: [1100, 2300], verify: [2500, 2800], answer: [3200, 3600], step: [0, 0] };
    for (const n of nodes) {
      const phase = NODE_PHASE[n.nodeType];
      if (!phase) continue;
      const start = timed ? rel(n.startedAt) : ORDER[phase][0];
      const end = timed ? rel(n.completedAt) : ORDER[phase][1];
      if (start == null) continue;
      const check = phase === "verify" ? (n.label ?? "").split("·")[0].trim() : null;
      add({
        id: n.nodeKey,
        phase,
        lane: phase === "solve" || phase === "verify" ? laneOf.get(n.claimId ?? -1) ?? 0 : 0,
        title: phase === "verify" ? checkName(check) : TITLE[phase],
        scheduled: start,
        attempts: [{ n: 1, start, end: end ?? start, outcome: "ok", error: null, retryAt: null }],
        end: end ?? start,
        status: statusOf(phase, null, n),
        value: n.validity ?? null,
        detail: detailOf(phase, n),
      });
    }
  }

  // ---- sub-lanes: a claim's checks side by side, in the planner's order
  for (const lane of lanes.slice(1)) {
    const checks = work.filter((w) => w.lane === lane.index && w.phase === "verify");
    const planned = (trace.claims ?? []).find((c) => c.id === lane.claimId)?.checks ?? [];
    const rank = (w: Work) => {
      const i = planned.findIndex((c) => w.id.endsWith(`-${c.toLowerCase()}`));
      return i < 0 ? 99 : i;
    };
    checks.sort((a, b) => rank(a) - rank(b) || a.id.localeCompare(b.id)).forEach((w, i) => {
      w.sub = i;
      w.subs = checks.length;
    });
  }

  // ---- span
  const ended = rel(trace.execution?.endedAt);
  const lastEnd = Math.max(0, ...work.map((w) => w.end ?? nowRel), ...work.flatMap((w) => w.attempts.map((a) => a.retryAt ?? 0)));
  const span = Math.max(1, live ? nowRel : ended ?? (timed ? Math.max(lastKnown, lastEnd) : 3800));

  // ---- relations between claims
  const relations: Relation[] = [];
  const claimOfKey = (k: string) => {
    const m = /^solver-(\d+)$/.exec(k);
    return m ? Number(m[1]) : null;
  };
  const conflicts = new Map<string, { claims: number[]; weight: number }>();
  for (const e of edges) {
    if (e.edgeType === "CONTRADICTS") {
      const c = claimOfKey(e.fromKey);
      if (c == null) continue;
      const entry = conflicts.get(e.toKey) ?? { claims: [], weight: e.weight ?? 0.5 };
      if (!entry.claims.includes(c)) entry.claims.push(c);
      conflicts.set(e.toKey, entry);
    } else if (e.edgeType === "SUPPORTS" || e.edgeType === "DEPENDS") {
      const a = claimOfKey(e.fromKey), b = claimOfKey(e.toKey);
      const la = a == null ? undefined : laneOf.get(a), lb = b == null ? undefined : laneOf.get(b);
      if (la == null || lb == null) continue;
      const kind = e.edgeType === "SUPPORTS" ? "supports" : "depends";
      relations.push({ id: `${kind}-${a}-${b}`, kind, from: la, to: lb, weight: e.weight ?? 0.5, node: null });
    }
  }
  conflicts.forEach((v, key) => {
    const [a, b] = v.claims.sort((x, y) => x - y);
    const la = laneOf.get(a), lb = laneOf.get(b);
    if (la != null && lb != null) relations.push({ id: key, kind: "contradicts", from: la, to: lb, weight: v.weight, node: key });
  });

  // ---- the resolution: when the evidence was weighed, and what it came to
  const verifyEnds = work.filter((w) => w.phase === "verify").map((w) => w.end);
  const synthesis = work.find((w) => w.phase === "answer");
  const resolved = !!aggregator || (synthesis != null && verifyEnds.length > 0);
  const gateAt = synthesis?.scheduled ?? (verifyEnds.length && verifyEnds.every((e) => e != null) ? Math.max(...(verifyEnds as number[])) : null);
  const gate = gateAt == null && !resolved ? null : {
    at: gateAt ?? span,
    confidence: trace.run?.finalConfidence ?? aggregator?.validity ?? null,
    uncertainty: trace.run?.uncertainty ?? null,
    resolved,
  };

  // ---- stages, for the axis
  const stages: StageSpan[] = [];
  const stageOf = (phase: Phase, label: string) => {
    const items = work.filter((w) => w.phase === phase);
    if (!items.length) return;
    stages.push({ phase, label, start: Math.min(...items.map((w) => w.scheduled)), end: Math.max(...items.map((w) => w.end ?? nowRel)) });
  };
  stageOf("plan", "Plan");
  stageOf("solve", "Solve");
  stageOf("verify", "Verify");
  if (gate?.resolved) stages.push({ phase: "resolve", label: "Resolve", start: gate.at, end: gate.at });
  stageOf("answer", "Answer");
  stages.sort((a, b) => a.start - b.start);

  // ---- the critical path
  const path = criticalPath(work, nowRel);
  path.forEach((w) => (w.critical = true));

  return {
    t0,
    span,
    live,
    madeAt: now,
    lastKnown,
    timed,
    lanes,
    work,
    relations,
    gate,
    stages,
    critical: path.map((w) => w.id),
    breakdown: breakdownOf(path, span, nowRel),
    status: trace.execution?.status ?? trace.run?.status ?? "COMPLETED",
  };
}

/** A duration as people say it: 840 ms, 2.4 s, 1 m 12 s. */
export function duration(ms: number): string {
  if (ms < 1) return "0 ms";
  if (ms < 1000) return `${Math.round(ms)} ms`;
  if (ms < 10_000) return `${+(ms / 1000).toFixed(2)} s`;
  if (ms < 60_000) return `${+(ms / 1000).toFixed(1)} s`;
  return `${Math.floor(ms / 60_000)} m ${Math.round((ms % 60_000) / 1000)} s`;
}
