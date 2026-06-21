export interface WorkflowSummary {
  workflowId: string;
  workflowType: string;
  status: "RUNNING" | "COMPLETED" | "FAILED";
  currentSequence: number;
  createdAt: string;
  updatedAt: string;
}

export interface EventView {
  sequenceNumber: number;
  eventType: string;
  payload: any;
  createdAt: string;
}

export interface ActivityTaskView {
  sequenceNumber: number;
  activityType: string;
  status: string;
  retryCount: number;
  maxAttempts: number;
  visibleAt: string;
  lockedBy: string | null;
}

export interface OutboxView {
  id: number;
  destination: string;
  eventType: string;
  status: string;
  attempts: number;
  idempotencyKey: string;
  createdAt: string;
  dispatchedAt: string | null;
}

export interface WorkflowDetail {
  summary: WorkflowSummary;
  input: any;
  result: any;
  error: string | null;
  events: EventView[];
  activities: ActivityTaskView[];
  outbox: OutboxView[];
  costUsd: number;
  tokens: number;
}

export interface Stats {
  total: number;
  running: number;
  completed: number;
  failed: number;
  outboxDeliveries: number;
  duplicateDeliveries: string[];
}

export interface CostByProvider {
  provider: string;
  tokens: number;
  costUsd: number;
  calls: number;
}

export interface CostReport {
  totalCostUsd: number;
  totalTokens: number;
  byProvider: CostByProvider[];
}

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
