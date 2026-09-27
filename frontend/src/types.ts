/*
 * The workflow, stats and cost shapes are generated from the backend's own API
 * description (openapi.json → generated/api.d.ts), not written by hand. A
 * field renamed in Java now fails `tsc` here, instead of turning into an
 * `undefined` on a page. Regenerate with `npm run gen:api` after
 * `CONTINUUM_WRITE_OPENAPI=true mvn verify -Dit.test=OpenApiIT` in backend.
 */
import type { components } from "./generated/api";

type Schemas = components["schemas"];

/** A run as the lists show it. `status` is narrowed to the three the engine uses. */
export type WorkflowSummary = Omit<Schemas["WorkflowSummary"], "status"> & {
  status: "RUNNING" | "COMPLETED" | "FAILED";
};

export type EventView = Schemas["EventView"];
export type ActivityTaskView = Schemas["ActivityTaskView"];
export type OutboxView = Schemas["OutboxView"];

/** Why a running workflow is stuck: tries made and the last error. */
export type StuckView = Schemas["StuckView"];

export type WorkflowDetail = Omit<Schemas["WorkflowDetail"], "summary" | "stuck"> & {
  summary: WorkflowSummary;
  stuck?: StuckView | null;
};

export type Stats = Schemas["StatsView"];
export type CostByProvider = Schemas["CostByProvider"];
export type CostReport = Schemas["CostReport"];

export interface Meta {
  workflowTypes: string[];
  providerFailoverChain: string[];
}

export interface ChaosState {
  activityFailureRate: number;
  sinkFailureRate: number;
  primaryProviderDown: boolean;
  activityLatencyMs: number;
  crashAfterActivities: number;
}
