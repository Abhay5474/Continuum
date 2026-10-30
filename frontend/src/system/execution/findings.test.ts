import { describe, expect, it } from "vitest";
import { buildExecution, type Trace } from "./model";
import { findingsOf } from "./findings";
import retried from "./__fixtures__/retry-and-contradiction.json";
import clean from "./__fixtures__/clean-run.json";
import workflow from "./__fixtures__/workflow-retries.json";

const RETRIED = retried as unknown as Trace;
const CLEAN = clean as unknown as Trace;
const of = (t: Trace) => findingsOf(buildExecution(t)!, t);

describe("findingsOf", () => {
  it("leads with the outcome, then what went wrong, worst first", () => {
    const f = of(RETRIED);
    expect(f[0]).toMatchObject({ id: "outcome", tone: "ok", title: "93.8% confidence, low uncertainty", target: "aggregator" });
    expect(f[0].detail).toContain("4 of 4 claims survived");
    expect(f.slice(1).map((x) => x.tone)).toEqual([...f.slice(1).map((x) => x.tone)].sort((a, b) => ["bad", "warn", "info", "ok"].indexOf(a) - ["bad", "warn", "info", "ok"].indexOf(b)));
  });

  it("says which claims contradict, and what each concluded", () => {
    const c = of(RETRIED).find((x) => x.id === "conflict-1-3")!;
    expect(c.title).toBe("C1 and C3 contradict");
    expect(c.detail).toContain("Saline is safe for cleaning a dog's paw cut.");
    expect(c.detail).toContain("Hydrogen peroxide is dangerous");
    expect(c.detail).toContain("both still survived");
    expect(c.target).toBe("conflict-1-3");
  });

  it("names a failed check and what it found", () => {
    const c = of(RETRIED).find((x) => x.id === "fail-verify-4-constraint_check")!;
    expect(c).toMatchObject({ tone: "bad", title: "C4 failed the constraints check (0.10)", target: "verify-4-constraint_check" });
    expect(c.detail).toMatch(/^Numeric\/identifier constraints/);
  });

  it("says what a retry cost the run", () => {
    const r = of(RETRIED).find((x) => x.id === "retry-solver-2")!;
    expect(r.title).toBe("C2's solver failed once and was retried");
    expect(r.detail).toContain("All providers in failover chain failed");
    expect(r.detail).toMatch(/backed off 2(\.\d+)? s/);
    expect(r.detail).toMatch(/Everything after it waited 2\.\d+ s for it/);
  });

  it("has only good news to report about a clean run", () => {
    const f = of(CLEAN);
    expect(f[0].id).toBe("outcome");
    expect(f.filter((x) => x.tone === "bad" || x.tone === "warn")).toEqual([]);
  });

  it("reports a run in progress as running", () => {
    const steps = RETRIED.execution!.steps.slice(0, 3).map((s, i) =>
      i === 2 ? { ...s, status: "RUNNING", attempts: [{ ...s.attempts[0], endedAt: null, outcome: "RUNNING" }] } : s,
    );
    const live: Trace = { claims: RETRIED.claims, execution: { status: "RUNNING", startedAt: RETRIED.execution!.startedAt, steps } };
    const t0 = Number(RETRIED.execution!.startedAt) * 1000;
    const f = findingsOf(buildExecution(live, t0 + 5000)!, live);
    expect(f[0]).toMatchObject({ id: "live", tone: "info", title: "Running for 5 s" });
  });
});

describe("findingsOf, for an ordinary workflow", () => {
  // A real CustomerAnalysis run, with the account's fault injection failing
  // three of its five steps once each.
  const run = workflow as unknown as Trace;

  it("says it completed, and how many steps needed another attempt", () => {
    const f = findingsOf(buildExecution(run)!, run);
    expect(f[0]).toMatchObject({ id: "outcome", tone: "ok" });
    expect(f[0].title).toMatch(/^Completed in \d/);
    expect(f[0].detail).toBe("5 steps; 3 needed more than one attempt.");
  });

  it("says what each retry cost a run whose steps run one after another", () => {
    const f = findingsOf(buildExecution(run)!, run);
    const retries = f.filter((x) => x.id.startsWith("retry-"));
    expect(retries).toHaveLength(3);
    for (const r of retries) {
      expect(r.tone).toBe("warn");
      expect(r.detail).toContain("CHAOS: injected activity failure");
      expect(r.detail).toMatch(/The retries added \d+(\.\d+)? s to the run\./);
    }
  });
});
