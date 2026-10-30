import { FEATURES } from "../system/features";
import type { GlyphName } from "../system/hub";

/**
 * The console's map: every place you can go, grouped by what it does.
 *
 * <p>The sidebar, the command palette and the breadcrumb all read this one
 * list, so a page cannot be in one and missing from another, or be called one
 * thing in the sidebar and another in the bar above it.
 */
export type Place = {
  to: string;
  label: string;
  desc: string;
  glyph: GlyphName;
  /** Every path that counts as being here: the views of a merged feature. */
  paths: string[];
  /** The views, when there is more than one, named for the palette. */
  views?: { to: string; label: string }[];
  labs?: boolean;
};

export type Section = { label: string; places: Place[] };

const GLYPH: Record<string, GlyphName> = {
  Workflows: "flow",
  Gateway: "route",
  Models: "model",
  Routing: "split",
  "Traffic Control": "queue",
  "Model Cascade": "layers",
  Pipelines: "bolt",
  Specialists: "target",
  "Prompt Guard": "shield",
  Context: "doc",
  "Context Optimizer": "memory",
  "Semantic Cache": "cache",
  "Verification Engine": "check",
  "Answer Assurance": "scale",
  "Loop Detection": "loop",
  "Decision Provenance": "eye",
  "Chaos Lab": "alert",
  "Adaptive Policy": "spark",
};

function places(names: string[]): Place[] {
  return names.flatMap((name) => {
    const f = FEATURES.find((x) => x.name === name);
    if (!f) return [];
    return [{
      to: f.views[0].to,
      label: f.name,
      desc: f.desc,
      glyph: GLYPH[f.name] ?? "app",
      paths: f.views.map((v) => v.to),
      views: f.views.length > 1 ? f.views : undefined,
      labs: f.labs,
    }];
  });
}

export const HOME: Place = {
  to: "/dashboard",
  label: "Command Centre",
  desc: "The whole system at a glance",
  glyph: "gauge",
  paths: ["/dashboard"],
};

export const SECTIONS: Section[] = [
  { label: "Traffic", places: places(["Workflows", "Gateway", "Models", "Routing", "Traffic Control", "Model Cascade"]) },
  { label: "Prompt", places: places(["Pipelines", "Specialists", "Prompt Guard", "Context", "Context Optimizer", "Semantic Cache"]) },
  { label: "Reliability", places: places(["Verification Engine", "Answer Assurance", "Loop Detection", "Decision Provenance", "Chaos Lab"]) },
  { label: "Intelligence", places: places(["Adaptive Policy"]) },
];

export const ACCOUNT: Place[] = [
  { to: "/portal", label: "API Keys & Providers", desc: "Issue keys, connect provider credentials", glyph: "lock", paths: ["/portal"] },
  { to: "/billing", label: "Billing & Usage", desc: "Plan, quota and spend", glyph: "coin", paths: ["/billing"] },
  { to: "/settings", label: "Account Settings", desc: "Profile, team and security", glyph: "user", paths: ["/settings"] },
];

/** Shown to the operator only: every account on the deployment. */
export const OPERATOR_ACCOUNTS: Place = {
  to: "/accounts",
  label: "Accounts",
  desc: "Every account: plans, keys, provider credentials",
  glyph: "app",
  paths: ["/accounts"],
};

const within = (pathname: string, path: string) => pathname === path || pathname.startsWith(path + "/");

/** Whether the page at {@code pathname} is this place, or one of its views. */
export function isAt(place: Place, pathname: string) {
  return place.paths.some((p) => within(pathname, p));
}

/**
 * Where a path is: its section, its place, and the view within it. The most
 * specific path wins, so /workflows/console is "Run history", not "Definitions".
 */
export function locate(pathname: string, operator = false): { section: string; place: Place; view?: string } | null {
  if (within(pathname, HOME.to)) return { section: "Overview", place: HOME };
  const all: { section: string; place: Place }[] = [
    ...SECTIONS.flatMap((s) => s.places.map((place) => ({ section: s.label, place }))),
    ...(operator ? [...ACCOUNT, OPERATOR_ACCOUNTS] : ACCOUNT).map((place) => ({ section: "Account", place })),
  ];
  let found: { section: string; place: Place; view?: string } | null = null;
  let longest = -1;
  for (const { section, place } of all) {
    for (const v of place.views ?? [{ to: place.to, label: place.label }]) {
      if (within(pathname, v.to) && v.to.length > longest) {
        longest = v.to.length;
        found = { section, place, view: place.views ? v.label : undefined };
      }
    }
  }
  return found;
}
