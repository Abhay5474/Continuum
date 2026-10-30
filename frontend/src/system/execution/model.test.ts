import { describe, expect, it } from "vitest";
import { buildExecution, duration, type Trace } from "./model";
import retried from "./__fixtures__/retry-and-contradiction.json";
import clean from "./__fixtures__/clean-run.json";

// Two real runs of the verification engine, recorded against a scripted provider.
const RETRIED = retried as unknown as Trace;
const CLEAN = clean as unknown as Trace;

describe("buildExecution", () => {
  it("lays a run out as one lane per claim behind the orchestration lane", () => {
    const m = buildExecution(RETRIED)!;
    expect(m.lanes.map((l) => l.code)).toEqual(["", "C1", "C2", "C3", "C4"]);
    expect(m.lanes[1].title).toBe("Saline is safe for cleaning a dog's paw cut");
    expect(m.work.find((w) => w.id === "planner")!.lane).toBe(0);
    expect(m.work.find((w) => w.id === "solver-3")!.lane).toBe(3);
    expect(m.work.find((w) => w.id === "synthesis")!.lane).toBe(0);
  });

  it("measures time from the run's first event", () => {
    const m = buildExecution(RETRIED)!;
    expect(m.timed).toBe(true);
    expect(m.span).toBeCloseTo(8941.6, -1);
    const plan = m.work.find((w) => w.id === "planner")!;
    expect(plan.scheduled).toBeCloseTo(291.9, -1);
    expect(plan.attempts[0].start).toBeCloseTo(772.4, -1);
    expect(plan.end).toBeCloseTo(1623.5, -1);
  });

  it("keeps every attempt of a retried step, with the backoff it waited", () => {
    const solver = buildExecution(RETRIED)!.work.find((w) => w.id === "solver-2")!;
    expect(solver.attempts).toHaveLength(2);
    expect(solver.attempts[0].outcome).toBe("failed");
    expect(solver.attempts[0].error).toContain("failover chain failed");
    expect(solver.attempts[0].retryAt! - solver.attempts[0].end!).toBeCloseTo(2002, -1);
    expect(solver.attempts[1].outcome).toBe("ok");
    expect(solver.status).toBe("done");
    // Which model answered; why the first attempt failed is on the attempt.
    expect(solver.detail).toBe("groq openai/gpt-oss-120b");
  });

  it("puts a claim's checks side by side in the planner's order, with what each found", () => {
    const m = buildExecution(RETRIED)!;
    const checks = m.work.filter((w) => w.lane === 4 && w.phase === "verify");
    expect(checks.map((w) => [w.title, w.sub, w.subs])).toEqual([
      ["Logic", 0, 3],
      ["Grounding", 1, 3],
      ["Constraints", 2, 3],
    ]);
    const constraints = checks[2];
    expect(constraints.status).toBe("fail");
    expect(constraints.value).toBeCloseTo(0.1);
    expect(constraints.detail).toContain("dropped");
  });

  it("finds the chain that decided how long the run took", () => {
    const m = buildExecution(RETRIED)!;
    // The retry on claim 2 held every check back until it finished.
    expect(m.critical).toEqual(["planner", "solver-2", "verify-4-constraint_check", "synthesis"]);
    const b = m.breakdown;
    expect(b.work + b.queued + b.backoff + b.orchestration).toBeCloseTo(m.span, -1);
    expect(b.backoff).toBeCloseTo(2002, -1);
    expect(b.queued).toBeGreaterThan(1000);
  });

  it("records the contradiction and the dependency between claims", () => {
    const m = buildExecution(RETRIED)!;
    expect(m.relations).toEqual(
      expect.arrayContaining([
        expect.objectContaining({ kind: "contradicts", from: 1, to: 3, node: "conflict-1-3" }),
        expect.objectContaining({ kind: "depends", from: 1, to: 4 }),
      ]),
    );
    expect(m.relations.find((r) => r.kind === "contradicts")!.weight).toBeCloseTo(0.79, 2);
  });

  it("resolves at the moment the answer was scheduled, with each claim's prior and posterior", () => {
    const m = buildExecution(RETRIED)!;
    expect(m.gate!.resolved).toBe(true);
    expect(m.gate!.at).toBeCloseTo(m.work.find((w) => w.id === "synthesis")!.scheduled);
    expect(m.gate!.confidence).toBeCloseTo(0.9378, 3);
    const c3 = m.lanes[3];
    expect(c3.prior).toBeCloseTo(0.58);
    expect(c3.posterior).toBeCloseTo(0.8925, 3);
    expect(c3.survived).toBe(true);
    expect(m.stages.map((s) => s.label)).toEqual(["Plan", "Solve", "Verify", "Resolve", "Answer"]);
  });

  it("draws a run that is still going up to now, with nothing resolved yet", () => {
    const steps = RETRIED.execution!.steps.slice(0, 5).map((s, i) =>
      i === 2 ? { ...s, status: "RUNNING", attempts: [{ ...s.attempts[0], endedAt: null, outcome: "RUNNING" }] } : s,
    );
    const t0 = Number(RETRIED.execution!.startedAt) * 1000;
    const live: Trace = { claims: RETRIED.claims, execution: { status: "RUNNING", startedAt: RETRIED.execution!.startedAt, steps } };
    const m = buildExecution(live, t0 + 5000)!;
    expect(m.live).toBe(true);
    expect(m.span).toBeCloseTo(5000, -1);
    expect(m.gate).toBeNull();
    const running = m.work.find((w) => w.id === "solver-2")!;
    expect(running.status).toBe("running");
    expect(running.end).toBeNull();
    expect(m.critical.at(-1)).toBe("solver-2");
  });

  it("counts a retry still backing off in where the time went", () => {
    const steps = RETRIED.execution!.steps.slice(0, 5).map((s) =>
      s.claimId === 2 && s.activityType === "dag.solve" ? { ...s, status: "RETRYING", attempts: [s.attempts[0]] } : s,
    );
    const t0 = Number(RETRIED.execution!.startedAt) * 1000;
    const failedAt = Number(steps.find((s) => s.claimId === 2)!.attempts[0].endedAt) * 1000 - t0;
    const live: Trace = { claims: RETRIED.claims, execution: { status: "RUNNING", startedAt: RETRIED.execution!.startedAt, steps } };
    const m = buildExecution(live, t0 + failedAt + 1500)!;
    const w = m.work.find((x) => x.id === "solver-2")!;
    expect(w.status).toBe("retrying");
    expect(m.critical.at(-1)).toBe("solver-2");
    expect(m.breakdown.backoff).toBeCloseTo(1500, -1);
    const b = m.breakdown;
    expect(b.work + b.queued + b.backoff + b.orchestration).toBeCloseTo(m.span, -1);
  });

  it("still draws a run recorded without timings, in pipeline order", () => {
    const untimed: Trace = {
      run: { status: "COMPLETED", finalConfidence: 0.82 },
      nodes: (CLEAN.nodes ?? []).map((n) => ({ ...n, startedAt: null, completedAt: null })),
      edges: CLEAN.edges,
    };
    const m = buildExecution(untimed)!;
    expect(m.timed).toBe(false);
    expect(m.lanes).toHaveLength(4);
    const plan = m.work.find((w) => w.id === "planner")!;
    const solver = m.work.find((w) => w.id === "solver-1")!;
    expect(plan.end!).toBeLessThan(solver.scheduled);
    expect(m.gate!.confidence).toBeCloseTo(0.82);
  });

  it("has nothing to draw before the first event", () => {
    expect(buildExecution({ nodes: [], edges: [], execution: { status: "RUNNING", steps: [] } })).toBeNull();
    expect(buildExecution(null)).toBeNull();
  });
});

describe("duration", () => {
  it("says a duration the way people do", () => {
    expect(duration(0.4)).toBe("0 ms");
    expect(duration(840)).toBe("840 ms");
    expect(duration(2400)).toBe("2.4 s");
    expect(duration(2060)).toBe("2.06 s");
    expect(duration(1000)).toBe("1 s");
    expect(duration(12_400)).toBe("12.4 s");
    expect(duration(10_000)).toBe("10 s");
    expect(duration(72_000)).toBe("1 m 12 s");
  });
});
