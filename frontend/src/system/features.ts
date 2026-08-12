/**
 * Which screens are the same feature.
 *
 * <p>The console had grown thirty top-level entries, several of which answer the
 * same question by different means. Admission Control, Priority & Deadlines and
 * Cost-Aware Limits are all "should this request be served, and when". Fault
 * Injection and Model Failures are both "break it on purpose". Presenting those
 * as unrelated products makes a reader hunt through three menu items to find the
 * one control they want, and makes the whole thing look like a pile of features
 * rather than a system.
 *
 * <p><b>Nothing is removed.</b> Every route still exists and still does exactly
 * what it did; they are grouped under one name and given a shared tab strip, so
 * moving between them is a tab rather than a trip back to the menu.
 *
 * <p>The grouping rule is deliberately strict: two screens merge only when they
 * answer the same question. Related is not the same — the Semantic Breaker
 * watches answer quality and the Quality Gate scores it, but one takes a model
 * out of rotation and the other rewrites a reply, and a reader who conflates
 * them will be surprised by what happens.
 */

export type FeatureView = { to: string; label: string };

export type Feature = {
  /** The one name this feature has in the navigation. */
  name: string;
  /** One line, for the menu. */
  desc: string;
  /** The screens it is made of. The first is where the menu entry points. */
  views: FeatureView[];
};

export const FEATURES: Feature[] = [
  /* ---- Traffic ---------------------------------------------------- */
  {
    name: "Gateway",
    desc: "Live requests, providers and failover",
    views: [{ to: "/gateway", label: "Gateway" }],
  },
  {
    name: "Routing",
    desc: "How a provider is chosen, learned, and proven",
    views: [
      { to: "/router", label: "Dispatch" },
      { to: "/autopilot", label: "Optimization" },
      { to: "/counterfactual", label: "Counterfactual replay" },
    ],
  },
  {
    name: "Traffic Control",
    desc: "Whether there is room, who gets it, and what it may cost",
    views: [
      { to: "/admission", label: "Admission" },
      { to: "/scheduling", label: "Priority & deadlines" },
      { to: "/cost-limits", label: "Cost limits" },
    ],
  },
  {
    name: "Model Cascade",
    desc: "Cheap model first, escalate only when needed",
    views: [{ to: "/cascade", label: "Model Cascade" }],
  },
  {
    name: "Workflows",
    desc: "Durable graphs, their runs, and the guarantees around them",
    views: [
      { to: "/workflows", label: "Definitions" },
      { to: "/workflows/console", label: "Run history" },
      { to: "/saga", label: "Compensation" },
      { to: "/replay", label: "Replay audit" },
      { to: "/dag", label: "Verification" },
    ],
  },

  /* ---- Prompt ----------------------------------------------------- */
  {
    name: "Pipelines",
    desc: "Input in, answer out — the endpoint your app calls",
    views: [{ to: "/pipelines", label: "Pipelines" }],
  },
  {
    name: "Specialists",
    desc: "Call a purpose-built model before the language model",
    views: [{ to: "/specialists", label: "Specialists" }],
  },
  {
    name: "Prompt Guard",
    desc: "What happens to a prompt on its way out",
    views: [
      { to: "/guard", label: "Firewall & compression" },
      { to: "/compression", label: "Compression budget" },
    ],
  },
  {
    name: "Context",
    desc: "What the model is given to reason over, and what it remembers",
    views: [
      { to: "/context", label: "Transformers" },
      { to: "/mmu", label: "Optimizer" },
      { to: "/memory", label: "Memory" },
    ],
  },
  {
    name: "Semantic Cache",
    desc: "Reuse answers to equivalent questions",
    views: [{ to: "/cache", label: "Semantic Cache" }],
  },

  /* ---- Reliability ------------------------------------------------ */
  {
    name: "Answer Assurance",
    desc: "Judging a finished answer, and acting on the verdict",
    views: [
      { to: "/quality", label: "Quality gate" },
      { to: "/confidence", label: "Confidence" },
      { to: "/breaker", label: "Semantic breaker" },
    ],
  },
  {
    name: "Loop Detection",
    desc: "Spot an agent going round in circles",
    views: [{ to: "/loops", label: "Loop Detection" }],
  },
  {
    name: "Decision Provenance",
    desc: "Why each answer happened, as data",
    views: [{ to: "/provenance", label: "Decision Provenance" }],
  },
  {
    name: "Chaos Lab",
    desc: "Break it on purpose, before it breaks itself",
    views: [
      { to: "/chaos", label: "Infrastructure" },
      { to: "/ai-chaos", label: "Model failures" },
    ],
  },

  /* ---- Intelligence ----------------------------------------------- */
  {
    name: "Adaptive Policy",
    desc: "Autonomous memory and policy, gated by replay",
    views: [{ to: "/godmode", label: "Adaptive Policy" }],
  },
];

/** Every route that belongs to a multi-view feature, mapped to that feature. */
const BY_ROUTE = new Map<string, Feature>();
for (const f of FEATURES) {
  for (const v of f.views) {
    BY_ROUTE.set(v.to, f);
  }
}

/**
 * The feature a path belongs to, if it is one of several views of it.
 *
 * <p>Returns nothing for single-view features: a tab strip with one tab on it
 * is chrome that says "there is more here" when there is not.
 */
export function featureFor(pathname: string): Feature | undefined {
  const f = BY_ROUTE.get(pathname);
  return f && f.views.length > 1 ? f : undefined;
}
