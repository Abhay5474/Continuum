export type Pipeline = {
  id: number;
  name: string;
  description: string | null;
  inputKind: string;
  systemPrompt: string | null;
  steps: number[];
  routing: RoutingStep[];
  routingEnabled: boolean;
  verificationMode: "OFF" | "MONITOR" | "ENFORCE";
  enabled: boolean;
  policyEnabled: boolean;
  strongThreshold: number;
  weakThreshold: number;
  declineOnNoEvidence: boolean;
  runs: number;
};

export type Condition =
  | "ALWAYS"
  | "IF_PREVIOUS_FOUND"
  | "IF_PREVIOUS_EMPTY"
  | "IF_PROMPT_MATCHES"
  | "IF_INPUT_IS";

export type RoutingStep = { specialistId: number; when: Condition; pattern: string | null };

/** Written for the person choosing, not for the person who wrote the enum. */
export const CONDITIONS: { value: Condition; label: string; hint: string; needsPattern?: string }[] = [
  { value: "ALWAYS", label: "Always", hint: "Runs on every request." },
  {
    value: "IF_PREVIOUS_EMPTY",
    label: "Only if nothing was found yet",
    hint: "Escalation — screen with something cheap, and only reach for the expensive model when the cheap one saw nothing.",
  },
  {
    value: "IF_PREVIOUS_FOUND",
    label: "Only if something was found",
    hint: "Drill-down — a general detector first, then a specific classifier once there is something to classify.",
  },
  {
    value: "IF_PROMPT_MATCHES",
    label: "Only if the question matches",
    hint: "Runs when the user's own question matches a pattern.",
    needsPattern: "bleed|wound|hurt",
  },
  {
    value: "IF_INPUT_IS",
    label: "Only for one kind of input",
    hint: "For a pipeline that accepts more than one kind.",
    needsPattern: "audio",
  },
];

type Band = "STRONG" | "MEDIUM" | "WEAK" | "NONE" | "UNAVAILABLE";

export type Policy = {
  band: Band;
  action: "PASS" | "HEDGE" | "ASK_FOR_BETTER_INPUT" | "DECLINE";
  evidence: number;
  declined: boolean;
  reason: string;
};

export type Compliance = {
  action: string;
  checked: boolean;
  complied: boolean;
  markers: string[];
  flatAssertions: string[];
  method: string;
};

export type Specialist = {
  id: number;
  name: string;
  status: "DRAFT" | "READY" | "UNPARSEABLE" | "FAILED";
  inputKind: string;
  minConfidence: number;
};

export type Step = {
  ordinal: number;
  kind: "INPUT" | "SPECIALIST" | "ENRICHMENT" | "MODEL" | "OUTPUT" | string;
  label: string;
  status: string;
  confidence: number | null;
  cost: number;
  latencyMs: number;
  detail: any;
};

export type Run = {
  response: string;
  traceId: string;
  findings: number;
  evidenceConfidence: number;
  anythingFound: boolean;
  analysisRan: boolean;
  model: string;
  cost: number;
  latencyMs: number;
  policy: Policy | null;
  compliance: Compliance | null;
  verification: Verification | null;
  trace: Step[];
};

export type Verification = {
  verdict: "OK" | "WARN" | "FAIL";
  issues: { kind: string; detail: string; severe: boolean }[];
  covered: string[];
  uncovered: string[];
  replaced: boolean;
  method: string;
};

export const BAND_COPY: Record<Band, { title: string; note: string; tone: "ok" | "warn" | "bad" | "idle" }> = {
  STRONG: {
    title: "Strong evidence",
    note: "Answered directly. Nothing was added to the prompt — a strong finding needs no instruction, and adding one would make every answer read as unsure.",
    tone: "ok",
  },
  MEDIUM: {
    title: "Moderate evidence",
    note: "The model was told to state its uncertainty out loud rather than leave it implied.",
    tone: "warn",
  },
  WEAK: {
    title: "Too weak to advise on",
    note: "The model was forbidden from naming a condition and told to ask for better input instead. This is what stops a 0.31 detection becoming confident advice.",
    tone: "warn",
  },
  NONE: {
    title: "Nothing found",
    note: "The analysis ran and found nothing above the reporting threshold. That is a real result — the model may still answer parts of the question that need no findings.",
    tone: "idle",
  },
  UNAVAILABLE: {
    title: "No analysis available",
    note: "No specialist answered, so nothing was examined. Not the same as finding nothing, and the model was explicitly forbidden from presenting it as an all-clear.",
    tone: "bad",
  },
};

/** A 1×1 PNG. Enough to exercise the whole chain without asking for a file. */
export const SAMPLE_IMAGE =
  "iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAYAAAAfFcSJAAAADUlEQVR42mP8z8BQDwAEhQGAhKmMIQAAAABJRU5ErkJggg==";

export const INPUT_LABEL: Record<string, string> = {
  image: "an image",
  text: "text",
  json: "structured data",
  audio: "a recording",
};

export type Area = "run" | "chain" | "guards" | "endpoint";
