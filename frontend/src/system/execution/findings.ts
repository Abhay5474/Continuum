import { duration, parseJson, type ExecutionModel, type Trace, type Work } from "./model";

/**
 * What a person should know about a run, said in a sentence each, most
 * important first, each pointing at the part of the run it is about.
 *
 * Every finding is computed from the run's own record: nothing here is a
 * guess. A run with nothing unusual about it gets one finding, its outcome.
 */
export type Finding = {
  id: string;
  tone: "ok" | "warn" | "bad" | "info";
  title: string;
  detail?: string;
  /** What selecting the finding selects: a step, a claim, a contradiction. */
  target?: string;
};

const RANK: Record<Finding["tone"], number> = { bad: 0, warn: 1, info: 2, ok: 3 };

const ordinal = (n: number) => (n === 1 ? "once" : n === 2 ? "twice" : `${n} times`);

export function findingsOf(model: ExecutionModel, trace: Trace): Finding[] {
  const out: Finding[] = [];
  const lane = (w: Work) => model.lanes[w.lane];
  const who = (w: Work) => (w.lane === 0 ? `The ${w.title.toLowerCase()} step` : `${lane(w)?.code}'s ${w.title.toLowerCase()}`);
  const nodes = new Map((trace.nodes ?? []).map((n) => [n.nodeKey, n]));
  const conclusion = (claimLane: number) => {
    const id = model.lanes[claimLane]?.claimId;
    const out = parseJson(nodes.get(`solver-${id}`)?.outputJson);
    return typeof out?.conclusion === "string" && out.conclusion.trim() ? out.conclusion.trim() : null;
  };

  // ---- the outcome
  if (model.live) {
    const running = model.work.filter((w) => w.status === "running").length;
    out.push({
      id: "live",
      tone: "info",
      title: `Running for ${duration(model.span)}`,
      detail: running ? `${running} step${running === 1 ? "" : "s"} in progress across ${model.lanes.length - 1} claims.` : "Waiting for the next step to be picked up.",
    });
  } else if (model.status === "FAILED" || model.status === "CANCELLED") {
    out.push({ id: "failed", tone: "bad", title: model.status === "CANCELLED" ? "The run was cancelled" : "The run failed", detail: trace.execution?.error ?? undefined });
  } else if (!model.gate && model.status === "COMPLETED") {
    const retried = model.work.filter((w) => w.attempts.length > 1).length;
    out.push({
      id: "outcome",
      tone: "ok",
      title: `Completed in ${duration(model.span)}`,
      detail: `${model.work.length} step${model.work.length === 1 ? "" : "s"}${retried ? `; ${retried} needed more than one attempt` : ", each on its first attempt"}.`,
    });
  } else if (model.gate?.confidence != null) {
    const claims = model.lanes.slice(1);
    const survived = claims.filter((l) => l.survived !== false).length;
    const c = model.gate.confidence;
    out.push({
      id: "outcome",
      tone: c >= 0.85 ? "ok" : c >= 0.6 ? "warn" : "bad",
      title: `${(c * 100).toFixed(1)}% confidence${model.gate.uncertainty ? `, ${model.gate.uncertainty.toLowerCase()} uncertainty` : ""}`,
      detail: `${survived} of ${claims.length} claim${claims.length === 1 ? "" : "s"} survived verification and make up the answer. The run took ${duration(model.span)}.`,
      target: "aggregator",
    });
  }

  // ---- claims the evidence rejected
  for (const l of model.lanes.slice(1)) {
    if (l.survived !== false || l.posterior == null) continue;
    out.push({
      id: `rejected-${l.index}`,
      tone: "bad",
      title: `${l.code} was rejected`,
      detail: `Its posterior fell to ${l.posterior.toFixed(2)}${l.prior != null ? ` from a prior of ${l.prior.toFixed(2)}` : ""}, below the 0.5 a claim needs to survive. It is left out of the answer.`,
      target: `solver-${l.claimId}`,
    });
  }

  // ---- contradictions, and what each side concluded
  for (const r of model.relations.filter((x) => x.kind === "contradicts")) {
    const a = model.lanes[r.from], b = model.lanes[r.to];
    const ca = conclusion(r.from), cb = conclusion(r.to);
    const fate = [a, b].filter((l) => l.survived === false).map((l) => l.code);
    out.push({
      id: r.id,
      tone: fate.length ? "bad" : "warn",
      title: `${a.code} and ${b.code} contradict`,
      detail: [
        ca && cb ? `${a.code} concluded “${ca}” ${b.code} concluded “${cb}”` : null,
        `Both were penalised in proportion to the other's belief (weight ${r.weight.toFixed(2)}); ${fate.length ? `${fate.join(" and ")} did not survive.` : "both still survived."}`,
      ].filter(Boolean).join(" "),
      target: r.node ?? undefined,
    });
  }

  // ---- checks that failed, with what they found
  for (const w of model.work.filter((x) => x.phase === "verify" && x.status === "fail")) {
    out.push({
      id: `fail-${w.id}`,
      tone: "bad",
      title: `${lane(w)?.code} failed the ${w.title.toLowerCase()} check${w.value != null ? ` (${w.value.toFixed(2)})` : ""}`,
      detail: w.detail ? `${w.detail.charAt(0).toUpperCase()}${w.detail.slice(1)}.` : undefined,
      target: w.id,
    });
  }

  // ---- retries, and what they cost the run
  for (const w of model.work.filter((x) => x.attempts.length > 1 || x.status === "failed")) {
    const failed = w.attempts.filter((a) => a.outcome === "failed");
    if (!failed.length) continue;
    const backoff = w.attempts.slice(1).reduce((t, a, i) => {
      const prior = w.attempts[i];
      const due = Math.min(prior.retryAt ?? a.start, a.start);
      return t + Math.max(0, due - (prior.end ?? due));
    }, 0);
    let cost = "";
    if (w.critical && w.end != null) {
      // Run beside others (a fan-out), it held them all up by however much later
      // it finished; run alone, every second its retries took was the run's.
      const peers = model.work.filter((x) => x !== w && x.phase === w.phase && x.end != null && Math.abs(x.scheduled - w.scheduled) < 50);
      const ready = peers.length ? Math.max(...peers.map((x) => x.end!)) : null;
      const last = w.attempts[w.attempts.length - 1];
      if (ready != null && w.end > ready) cost = ` Everything after it waited ${duration(w.end - ready)} for it.`;
      else if (ready == null && w.attempts.length > 1) cost = ` The retries added ${duration(last.start - w.attempts[0].start)} to the run.`;
    }
    const error = failed[failed.length - 1].error;
    out.push({
      id: `retry-${w.id}`,
      tone: w.status === "failed" ? "bad" : "warn",
      title: w.status === "failed" ? `${who(w)} failed after ${w.attempts.length} attempts` : `${who(w)} failed ${ordinal(failed.length)} and was retried`,
      detail: `${error ? `${error}. ` : ""}${backoff > 0 ? `The engine backed off ${duration(backoff)} before trying again.` : ""}${cost}`.trim() || undefined,
      target: w.id,
    });
  }

  // ---- time spent waiting for a worker
  const b = model.breakdown;
  if (!model.live && b.queued > Math.max(500, model.span * 0.2)) {
    out.push({
      id: "queued",
      tone: "info",
      title: `${duration(b.queued)} waiting for a free worker`,
      detail: `${Math.round((b.queued / model.span) * 100)}% of the run was spent with work queued before a worker picked it up. Workers poll for tasks every ENGINE_POLL_INTERVAL_MS (500 ms by default); a shorter interval shortens these waits, at the cost of more polling.`,
    });
  }

  // The outcome first, then what went wrong, worst first.
  const head = (f: Finding) => (f.id === "outcome" || f.id === "live" || f.id === "failed" ? 0 : 1);
  return out.sort((x, y) => head(x) - head(y) || RANK[x.tone] - RANK[y.tone]);
}
