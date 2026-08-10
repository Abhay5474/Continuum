import type { Guide } from "./guide";

/**
 * What each feature is, keyed by route.
 *
 * <p>Written to the same standard as the pages themselves: say what the thing
 * does, say what it costs, and name the failure mode. A guide that only lists
 * the controls is a worse version of the screen it is sitting on top of.
 *
 * <p>Targets point at {@code [data-guide="…"]} attributes rather than classes,
 * so restyling a page cannot silently break the walkthrough.
 */
export const GUIDES: Record<string, Guide> = {
  "/gateway": {
    title: "the Gateway",
    summary:
      "One OpenAI-compatible endpoint that routes, retries and fails over before your application sees a problem.",
    steps: [
      {
        title: "What this is",
        body: "Point your app at Continuum instead of at a provider. It speaks the OpenAI chat-completions API, so for most SDKs this is a base-URL change and nothing else. Everything the rest of this console configures happens on requests that come through here.",
      },
      {
        title: "The numbers at the top",
        target: '[data-guide="gateway-stats"]',
        body: "Live totals for your account. The two worth watching are Absorbed failures — provider errors handled before your app saw them, which is the product doing its job — and Reached your app, which is the count that got through to you anyway. The second one should stay at zero.",
      },
      {
        title: "The request flow",
        target: '[data-guide="gateway-flow"]',
        body: "Every request, newest first: what your app asked for, which provider actually served it, and how long it took. A row marked with a failover is one where a provider died mid-request and the engine re-routed without telling your app.",
      },
      {
        title: "Try it without writing code",
        target: '[data-guide="gateway-tabs"]',
        body: "Send a request opens a form where you can paste one of your API keys and fire a real call through the whole pipeline. Health shows each provider's recent error rate, and Models is the catalogue the router picks from.",
      },
      {
        title: "What it costs",
        body: "Nothing on its own — the gateway adds a few milliseconds of routing and passes provider pricing straight through. Spend on this page is what your providers charged, not what Continuum charged.",
      },
    ],
  },

  "/cache": {
    title: "the Semantic Cache",
    summary:
      "Answers a repeated question from a previous answer instead of paying a provider for it again.",
    steps: [
      {
        title: "What this is",
        body: "A cache keyed by meaning rather than by string. “Summarise the refund policy” and “summarize the refund policy for us” are the same question, and an exact-match cache would miss both times. Scoped to your account — a hit never crosses accounts or models.",
      },
      {
        title: "Turn it on",
        target: '[data-guide="cache-toggle"]',
        body: "Off by default. While it is off, every request goes to a provider exactly as it does today, so turning it on is the only change in behaviour.",
      },
      {
        title: "The match threshold is the whole product",
        target: '[data-guide="cache-threshold"]',
        body: "How close an incoming prompt must be before a stored answer is served. Balanced is the recommended setting. The scale is a real trade: move it left and you catch more rewordings but some answers will be near misses.",
        caution:
          "A false hit is worse than a miss, because the caller cannot tell it happened — they get a confident answer to a question they did not ask.",
      },
      {
        title: "How long answers live",
        target: '[data-guide="cache-lifetime"]',
        body: "Entry lifetime decides when a stored answer expires. Short lifetimes suit anything that changes — prices, availability, policy. Long ones suit stable reference material.",
      },
      {
        title: "What it saves",
        target: '[data-guide="cache-stats"]',
        body: "Tokens saved and Cost avoided are what the served-from-cache calls would have cost at your provider's rates. The ring shows the split between answers served here and requests that carried on to a provider.",
      },
    ],
  },

  "/guard": {
    title: "Prompt Guard",
    summary:
      "What happens to a prompt between your request and the provider: redaction, injection screening, and compression.",
    steps: [
      {
        title: "What this is",
        body: "Two independent controls that sit on the outbound path. Both are per-account and both are off by default — while off, the prompt is forwarded to the provider verbatim.",
      },
      {
        title: "The path, drawn",
        target: '[data-guide="guard-route"]',
        body: "Read left to right: your prompt, then whichever of the two stages you have enabled, then the provider. Each stage shows what it is currently doing to traffic.",
      },
      {
        title: "The firewall",
        target: '[data-guide="guard-firewall"]',
        body: "Redacts personal data and blocks known prompt-injection patterns before the prompt leaves the engine. Every action it takes is recorded underneath, so you can see exactly what was changed and why.",
      },
      {
        title: "Compression",
        target: '[data-guide="guard-tabs"]',
        body: "The second tab. Shortens prompts before they are billed. It is a separate decision from the firewall and has its own budget page under Compression Budget.",
        caution:
          "Compression changes the text the model reasons over. Start by watching what it would remove before letting it act.",
      },
    ],
  },

  "/specialists": {
    title: "Specialists",
    summary:
      "A purpose-built model runs before the language model and hands it evidence instead of a raw file.",
    steps: [
      {
        title: "What this is",
        body: "A language model asked to read a scan, transcribe audio or find objects in an image will try, and will be confidently wrong some of the time. A specialist is a model that does exactly one of those jobs properly. Continuum calls it first and gives the language model its findings.",
      },
      {
        title: "Find one by what you need done",
        target: '[data-guide="specialists-search"]',
        body: "Search in plain words — “read text from a scan”, “transcribe a recording”. You are searching capabilities, not vendor names, because which vendor provides it is the part you should not have to care about.",
      },
      {
        title: "Every entry names its shape",
        target: '[data-guide="specialists-catalogue"]',
        body: "Each row shows the transformation it performs — an image becomes recovered text, a recording becomes a transcript — plus who hosts it and what it needs from you. Continuum normalises whatever comes back into one shape, so swapping vendors later does not change your code.",
      },
      {
        title: "Connect a provider",
        target: '[data-guide="specialists-providers"]',
        body: "These call third-party models with your own key. Keys are encrypted at rest and decrypted only at call time; no endpoint ever returns one.",
      },
    ],
  },

  "/pipelines": {
    title: "Pipelines",
    summary:
      "The endpoint your application actually calls: raw input in, an answer out, with specialists running in between.",
    steps: [
      {
        title: "What this is",
        body: "Your app sends an input and a question and gets an answer back. It never learns that a specialist was involved, which one, or who hosts it. That indirection is the point — you can change the chain without touching the caller.",
      },
      {
        title: "You need a probed specialist first",
        body: "A pipeline whose specialist has never answered successfully moves the failure from here, where you are looking at it, to a customer's request, where you are not. Register one under Specialists and probe it before wiring it in.",
      },
      {
        title: "The chain",
        target: '[data-guide="pipelines-list"]',
        body: "Each pipeline names its input, the specialists that run over it, and what the language model finally receives. Selecting one opens it for editing without moving anything else on the page.",
      },
      {
        title: "Evidence policy",
        body: "A pipeline can require that the strength of the evidence decides what the model is allowed to do with it — so a low-confidence reading cannot quietly become a confident assertion in the answer.",
      },
    ],
  },

  "/context": {
    title: "Context Transformers",
    summary:
      "Turns application data into a canonical form a model can reason over — deterministic, in-process, no model involved.",
    steps: [
      {
        title: "What this is",
        body: "A Specialist calls somebody else's model with your key. A transformer is Continuum doing the work itself: no key, no model, no network. The same bytes produce the same output forever, which is what makes it safe to put in a cached, replayed, audited prompt.",
      },
      {
        title: "What it recognises",
        target: '[data-guide="context-kinds"]',
        body: "Three kinds today: a spreadsheet becomes a semantic table with its headers and units recovered, logs become an incident context with repetition collapsed, and an email thread becomes a conversation with quoted history and signatures removed.",
      },
      {
        title: "Where it reaches a model",
        target: '[data-guide="context-reach"]',
        body: "On pipelines it is always on. For chat completions it is a separate switch, because that path rewrites a message your app wrote — a bigger promise than transforming a file you handed over deliberately.",
        caution:
          "Prose is never restructured. A transform that turned four sentences into a two-column table would be actively worse than doing nothing, so the spreadsheet transformer declines anything that reads as prose.",
      },
      {
        title: "Try it on your own data",
        target: '[data-guide="context-playground"]',
        body: "Paste or upload a file and see exactly what the model would receive, along with the token cost before and after. Recognition is by the bytes, not the filename.",
      },
    ],
  },

  "/quality": {
    title: "the Quality Gate",
    summary:
      "Checks a finished answer against the request that asked for it, and can repair it before your app sees it.",
    steps: [
      {
        title: "What this is",
        body: "A second pass over the model's answer, scored on four dimensions: does it follow the explicit contract, does it answer all of the question, are its figures grounded in the supplied context, and is it about the question at all.",
      },
      {
        title: "Start in Monitor",
        target: '[data-guide="quality-mode"]',
        body: "Monitor records what the gate would have done without changing a single answer. Enforce lets it act. Off is the default.",
        caution:
          "The gate can rewrite an answer your customer is about to read. It has to earn that, and Monitor is how it earns it — run it for a while and look at what it wanted to change.",
      },
      {
        title: "The dimensions",
        target: '[data-guide="quality-dimensions"]',
        body: "Scored separately on purpose. One dimension failing almost everything is usually a threshold problem, not a fleet of bad answers — and you can only see that when they are not averaged together.",
      },
      {
        title: "Limits",
        target: '[data-guide="quality-limits"]',
        body: "Act below sets the score at which the gate intervenes. The latency budget caps how long repair may take; past it the original answer is returned unchanged, because a slow correct answer is worse than a fast flawed one for anything interactive.",
      },
    ],
  },

  "/cascade": {
    title: "the Model Cascade",
    summary:
      "Answer with the cheap model, check the answer, and pay for the expensive one only when the check fails.",
    steps: [
      {
        title: "What this is",
        body: "Most requests do not need your best model. The cascade sends everything to the cheap tier first, judges the answer, and escalates only what fails. The saving is real but it is not free — you pay for a judgement on every request.",
      },
      {
        title: "The tiers",
        target: '[data-guide="cascade-tiers"]',
        body: "Taken from the model registry by price. The cheap tier answers everything; the strong tier is only reached on a failed judgement.",
      },
      {
        title: "The escalation threshold",
        target: '[data-guide="cascade-threshold"]',
        body: "How sure the judge must be to accept a cheap answer. Frugal accepts more and risks weak answers slipping through; Careful escalates on the slightest doubt and is close to just always using the strong model.",
      },
      {
        title: "Did it actually work",
        target: '[data-guide="cascade-verdict"]',
        body: "The two numbers that would disprove the feature: wasted escalations, where the strong model agreed with the cheap answer, and missed escalations found by auditing a sample of accepted answers against the strong tier.",
      },
    ],
  },

  "/confidence": {
    title: "Answer Confidence",
    summary:
      "Asks the same question several times and measures whether the model agrees with itself.",
    steps: [
      {
        title: "What this is",
        body: "Disagreement about meaning — not wording — is what a hallucination looks like from the outside. Sampling the same question repeatedly and comparing the answers gives you a confidence number you can act on.",
      },
      {
        title: "When to measure",
        target: '[data-guide="confidence-mode"]',
        body: "On demand measures only requests that ask for it. Adaptive stops sampling once the answer is decided. Always measures everything, which is the most expensive option by a wide margin.",
        caution:
          "Every extra sample is another billable call. Three samples means roughly three times the tokens for the requests being measured.",
      },
      {
        title: "Adaptive consensus",
        target: '[data-guide="confidence-adaptive"]',
        body: "Stops as soon as further samples could not reasonably change the answer, so an easy question costs two samples and a contested one still costs the budget. It never stops below two samples and never exceeds the budget, so it can only ever cost less.",
      },
      {
        title: "Sampling settings",
        target: '[data-guide="confidence-sampling"]',
        body: "Temperature cannot be set to zero: every sample would be identical and the measurement would report certainty about everything, which is the one answer it must never give.",
      },
    ],
  },

  "/router": {
    title: "Routing",
    summary:
      "Scores every request and picks the provider and model to run it on, with tail-latency hedging.",
    steps: [
      {
        title: "What this is",
        body: "Rather than pinning one model, the router scores each request on cost, latency and quality and dispatches accordingly. It can also hedge — firing a second provider when the first is running long — so a slow tail does not become a slow request.",
      },
      {
        title: "The dispatch network",
        target: '[data-guide="router-network"]',
        body: "Edge weight is measured call share, not configuration. This is where traffic actually went, which is usually the fastest way to notice that one provider is quietly taking everything.",
      },
      {
        title: "The provider table",
        target: '[data-guide="router-providers"]',
        body: "Per-provider calls, success rate, average latency, spend and current health. Health is scored from recent failures and is what the router uses to fail over.",
      },
      {
        title: "This one is engine-wide",
        body: "The router and hedging apply to every tenant on this deployment and change what it spends, so changing them needs operator access rather than an account login.",
      },
    ],
  },

  "/mmu": {
    title: "the Context Optimizer",
    summary:
      "Keeps the working set resident in the model's window and pages everything else out to a semantic stub.",
    steps: [
      {
        title: "What this is",
        body: "A long conversation eventually exceeds the model's context window. The usual answer is to drop the oldest messages, which loses whatever was decided early on. This pages them out to a short semantic stub instead, and faults them back in when they are referenced.",
      },
      {
        title: "Turn it on",
        target: '[data-guide="mmu-toggle"]',
        body: "Off by default. Nothing is paged until there is more context than the window can hold, so on short conversations enabling it changes nothing.",
      },
      {
        title: "Working-set assembly",
        target: '[data-guide="mmu-assembly"]',
        body: "With it off, eviction is positional: oldest out first. With it on, what stays resident is scored against the request being answered now — so an order number stated in message three survives a question asked in message forty.",
      },
      {
        title: "Watching it work",
        body: "The address space fills in once a long conversation runs through the gateway, showing what is resident, what has been paged out, and what faulted back in.",
      },
    ],
  },

  "/compression": {
    title: "the Compression Budget",
    summary:
      "Allocates how hard to compress each part of a prompt, rather than applying one ratio to all of it.",
    steps: [
      {
        title: "What this is",
        body: "One ratio for the whole prompt is the wrong shape. Instructions, examples and the question do not carry information at the same density, and compressing them equally throws away the wrong things first.",
      },
      {
        title: "It depends on the compressor",
        body: "This page allocates work that the compressor in Prompt Guard performs. With that turned off there is nothing to allocate, and this page has no effect.",
      },
      {
        title: "The budget",
        target: '[data-guide="compression-budget"]',
        body: "Set how aggressively each segment may be shortened. The question itself should almost always be left alone; examples are usually where the slack is.",
      },
    ],
  },

  "/loops": {
    title: "Loop Detection",
    summary:
      "Notices when an agent has lost the thread and is repeating itself, before the invoice does.",
    steps: [
      {
        title: "What this is",
        body: "An agent that has gone wrong does not crash. It keeps working — retrying the same step, alternating between two plans — and every step is billable. This watches the step sequence and says so.",
      },
      {
        title: "Start in Monitor",
        target: '[data-guide="loops-mode"]',
        body: "Monitor records what it would have stopped without stopping anything. Halt actually intervenes.",
        caution: "A false positive halts an agent that was working. Watch it in Monitor first.",
      },
      {
        title: "Try it against a sequence",
        target: '[data-guide="loops-playground"]',
        body: "Paste a list of steps, oldest first, and the detector will tell you what it sees. Nothing is executed. The sample buttons cover the three cases worth understanding: stuck repeating, stuck alternating, and genuinely working.",
      },
      {
        title: "What it will not do",
        body: "Repetition alone never trips it — the signal is repetition without progress. An agent legitimately iterating over two hundred files is repetitive and is not looping.",
      },
    ],
  },

  "/provenance": {
    title: "Decision Provenance",
    summary: "Why Continuum did what it did, as data rather than as a sentence.",
    steps: [
      {
        title: "What this is",
        body: "Every decision the engine makes about a request — which model, whether to cache, whether to compress, whether to escalate — recorded as one row you can aggregate and alert on.",
      },
      {
        title: "Recording is off by default",
        target: '[data-guide="provenance-toggle"]',
        body: "Recording is cheap but not free, and a request path is the wrong place to add writes nobody asked for.",
      },
      {
        title: "Graceful degradation",
        target: '[data-guide="provenance-degrade"]',
        body: "A separate switch. With it off, when every model fails the gateway returns a 502. With it on it steps down instead — a previous answer to an equivalent question, or an honest message saying none could be produced. Every degraded response names its rung, so the caller can tell.",
      },
      {
        title: "Reading a request",
        body: "Pick a request to see every decision made about it, in order. This is the screen to open when someone asks why a particular answer cost what it did.",
      },
    ],
  },

  "/breaker": {
    title: "the Semantic Breaker",
    summary:
      "Trips a model out of rotation when its answers get worse — not when it starts erroring.",
    steps: [
      {
        title: "What this is",
        body: "An ordinary circuit breaker trips on errors and timeouts. The failure that actually hurts is a model that keeps returning 200s while its answers quietly degrade. This watches answer quality against a learned baseline instead.",
      },
      {
        title: "It needs a baseline first",
        target: '[data-guide="breaker-baseline"]',
        body: "Observations before a baseline is trusted. Until it has that many, nothing trips — there is no “normal” to be worse than yet.",
      },
      {
        title: "Turning it on",
        target: '[data-guide="breaker-toggle"]',
        body: "Off by default. When it trips, traffic fails over to the next provider in the chain exactly as it would on a hard error.",
      },
    ],
  },

  "/admission": {
    title: "Admission Control",
    summary:
      "Measures how much a provider will actually take right now, instead of guessing a rate limit.",
    steps: [
      {
        title: "What this is",
        body: "Static rate limits are a guess that is wrong in both directions: too low and you leave capacity unused, too high and you collect 429s. This measures capacity from observed latency and admits accordingly.",
      },
      {
        title: "The measured ceiling",
        target: '[data-guide="admission-stats"]',
        body: "Concurrency in flight against what the provider has been absorbing without slowing down. When latency starts climbing, the ceiling comes down before the errors arrive.",
      },
      {
        title: "What a rejection looks like",
        body: "Requests turned away get a 429 with a retry hint, rather than being queued indefinitely — a queue that never drains is a timeout with extra steps.",
      },
    ],
  },

  "/cost-limits": {
    title: "Cost-Aware Limits",
    summary: "Limits by what a request actually costs, rather than counting requests.",
    steps: [
      {
        title: "What this is",
        body: "A fifty-step agent carrying twenty thousand tokens is not one request in the way that “hello” is one request. Counting requests treats them identically, which is why request-count limits either throttle trivial traffic or fail to stop expensive traffic.",
      },
      {
        title: "The budget",
        target: '[data-guide="cost-limits-controls"]',
        body: "Set the spend a caller may consume in a window. Admission is decided against the estimated cost of the request in front of it, so one expensive call can be refused while cheap ones keep flowing.",
      },
      {
        title: "Set criticality on the request",
        body: "Anything unmarked is treated as normal priority. Marking your genuinely interactive traffic is what lets the limiter shed batch work first.",
      },
    ],
  },

  "/scheduling": {
    title: "Priority & Deadlines",
    summary: "Admission control answers whether there is room; this answers who gets it.",
    steps: [
      {
        title: "What this is",
        body: "When more work arrives than capacity allows, something has to wait. Left alone that is whoever asked last. With priorities and deadlines it is whoever can most afford to.",
      },
      {
        title: "Try the ordering",
        target: '[data-guide="scheduling-table"]',
        body: "Edit the rows — priority, how long each has waited, its deadline and its estimated duration — and the resulting order is computed live. Nothing here is executed; it is a model of the scheduler you can poke.",
      },
      {
        title: "A caveat worth knowing",
        body: "Each instance orders its own waiters. Across a fleet this is per-instance fairness, not global fairness.",
      },
    ],
  },

  "/saga": {
    title: "Saga Compensation",
    summary:
      "Durable execution guarantees each step runs once. It does not guarantee the set of them is all-or-nothing.",
    steps: [
      {
        title: "What this is",
        body: "If step four fails after steps one to three have already charged a card and booked a room, you do not want a retry — you want the first three undone. A saga pairs each step with the compensating action that reverses it.",
      },
      {
        title: "Turning it on",
        target: '[data-guide="saga-toggle"]',
        body: "Off by default. With it on, a failed workflow runs its compensations in reverse order rather than simply stopping.",
      },
      {
        title: "Compensations must be idempotent",
        body: "A compensation can itself be retried after a crash, so “refund this payment” has to be safe to run twice.",
        caution: "A compensation with a side effect that cannot be repeated will double it.",
      },
    ],
  },

  "/counterfactual": {
    title: "Counterfactual Replay",
    summary:
      "What last week's traffic would have cost under a different routing policy — answered before you switch.",
    steps: [
      {
        title: "What this is",
        body: "Changing a routing policy in production and watching the bill is an expensive way to find out you were wrong. This replays traffic you have already served against a policy you are considering.",
      },
      {
        title: "Running one",
        target: '[data-guide="counterfactual-controls"]',
        body: "Pick the policy and the window. Nothing is re-sent to any provider — the replay scores recorded decisions, it does not re-run them.",
      },
      {
        title: "Read the modelled share carefully",
        body: "The measured share is what happened. The modelled share is an estimate under assumptions that are stated on the page. They are deliberately reported separately rather than blended into one reassuring number.",
      },
    ],
  },

  "/autopilot": {
    title: "Optimization",
    summary:
      "Learns the best routing from your own traffic, and only changes what it can prove is better.",
    steps: [
      {
        title: "What this is",
        body: "Rather than you tuning the router by hand, it learns which provider and model actually performs best for the requests you send, within the cost and latency targets you set.",
      },
      {
        title: "It proves before it promotes",
        body: "A candidate change runs on a slice of traffic first and is kept only if it measures better. Otherwise it is rolled back automatically.",
      },
      {
        title: "Turning it on",
        target: '[data-guide="autopilot-toggle"]',
        body: "Off by default; while off your app behaves exactly as it does today. Turning it off again restores standard behaviour immediately, and every decision it made stays on the record.",
      },
    ],
  },

  "/billing": {
    title: "Billing & Usage",
    summary: "Your plan, this month's token usage, and what is left of your quota.",
    steps: [
      {
        title: "What this is",
        body: "Usage is counted in tokens across everything that went through the gateway this month, including calls the cache served — those are shown as saved rather than spent.",
      },
      {
        title: "Your quota",
        target: '[data-guide="billing-usage"]',
        body: "The bar is this month's consumption against your plan's allowance. It resets on the first of the month.",
      },
      {
        title: "Changing plan",
        target: '[data-guide="billing-plans"]',
        body: "Downgrades apply immediately. Whether paid plans can be self-served depends on whether a payment processor is configured on this deployment; if not, the operator applies them.",
      },
    ],
  },

  "/settings": {
    title: "Account Settings",
    summary: "API keys, password, email, team members, and account deletion.",
    steps: [
      {
        title: "API keys",
        target: '[data-guide="settings-keys"]',
        body: "Name a key so you remember what it is for. The full key is shown once, at creation, and never again — only its prefix is stored in a form that can be displayed.",
        caution: "Revoking a key takes effect immediately. Anything using it starts failing at once.",
      },
      {
        title: "Team",
        target: '[data-guide="settings-team"]',
        body: "Anyone who accepts an invite signs in with their own password and works inside this account — the same keys, workflows and billing.",
      },
      {
        title: "Deleting the account",
        target: '[data-guide="settings-danger"]',
        body: "Removes keys, credentials, memory, billing records and all data, as required for a GDPR erasure request.",
        caution: "This cannot be undone, and there is no export step first.",
      },
    ],
  },

  "/workflows": {
    title: "Workflows",
    summary: "Author a durable graph: crash-safe, retried, and exactly-once.",
    steps: [
      {
        title: "What this is",
        body: "A workflow is a sequence of steps whose progress is written down as it happens. If the process dies halfway through, it resumes from the last completed step rather than starting over — which matters when the steps cost money or have side effects.",
      },
      {
        title: "Definitions and runs",
        body: "A definition is the graph you authored; a run is one execution of it. Definitions are versioned, so a run always replays against the version it started on rather than whatever is current.",
      },
      {
        title: "Exactly-once is about your steps",
        body: "The engine guarantees each step is applied once. It cannot make a step that is not idempotent safe to retry — a step that charges a card needs its own idempotency key.",
        caution: "If you need the whole set to be all-or-nothing rather than each step once, that is Saga Compensation, not this.",
      },
    ],
  },

  "/replay": {
    title: "Replay Audit",
    summary:
      "Replays a run's decisions against its own recorded history to prove it would execute identically.",
    steps: [
      {
        title: "What this is",
        body: "Durable execution only works if replaying a run's history produces the same decisions it produced the first time. This checks that property directly rather than assuming it.",
      },
      {
        title: "Nothing is re-sent",
        body: "Your services are not called again. The replay walks the recorded event log and compares the decisions the code makes now against the decisions recorded then.",
      },
      {
        title: "Model outputs are handled separately",
        body: "A model is not deterministic, so its outputs cannot be compared for equality. They are re-scored against today's provider instead, and reported apart from the deterministic checks.",
      },
    ],
  },

  "/memory": {
    title: "Memory",
    summary:
      "Hierarchical memory stored outside the context window: working, episodic, long-term, archived.",
    steps: [
      {
        title: "What this is",
        body: "Anything an agent must remember beyond a single conversation lives here rather than in the prompt. The prompt is a scarce, per-request resource; memory is not.",
      },
      {
        title: "The tiers",
        body: "Memories move down the tiers as they cool. Retrieval ranks by relevance, recency and salience together — recency alone loses the important thing said last week.",
      },
      {
        title: "Compression of cold memories",
        body: "Old memories are summarised rather than deleted, so the fact survives even when the wording does not.",
      },
    ],
  },

  "/portal": {
    title: "API Keys & Providers",
    summary: "Your Continuum API keys, and the upstream provider credentials it calls with.",
    steps: [
      {
        title: "Two different kinds of key",
        body: "A Continuum key is what your application uses to call this gateway. A provider credential is what Continuum uses to call OpenAI, Gemini or Groq on your behalf. They are unrelated and stored separately.",
      },
      {
        title: "How credentials are stored",
        body: "Encrypted with AES-256-GCM at rest and decrypted only in memory at call time. They are write-only: no endpoint returns one after saving, including to you.",
      },
      {
        title: "Bring your own keys",
        body: "With your own provider keys attached, traffic is billed to you by the provider directly and Continuum only routes it.",
      },
    ],
  },

  "/godmode": {
    title: "Adaptive Policy",
    summary:
      "Self-managing memory, with policy proposals gated by digital-twin replay before they take effect.",
    steps: [
      {
        title: "What this is",
        body: "The engine proposes changes to its own memory and retention policy based on what it observes. Nothing is applied on the strength of the proposal alone.",
      },
      {
        title: "The gate",
        body: "Each proposal is replayed against a digital twin of recent traffic first. It is adopted only if the replay shows it is better, and the comparison is kept.",
      },
      {
        title: "It is off by default",
        body: "While off, memory behaves exactly as configured by hand.",
        caution: "This changes retention. Read what a proposal would drop before adopting it.",
      },
    ],
  },

  "/dag": {
    title: "Verification",
    summary:
      "Claims solved in parallel, verified independently, and resolved by Bayesian aggregation.",
    steps: [
      {
        title: "What this is",
        body: "For questions where being wrong is expensive, one answer from one model is a single point of failure. This decomposes the question into claims, solves them in parallel, and verifies each one separately.",
      },
      {
        title: "Why the verifier is separate",
        body: "A model asked to check its own answer agrees with itself far more often than it should. Verification runs as its own step against its own prompt.",
      },
      {
        title: "Aggregation",
        body: "Verdicts are combined by Bayesian aggregation rather than a majority vote, so a confident verifier and an uncertain one do not carry the same weight.",
      },
    ],
  },

  "/ai-chaos": {
    title: "Model Failures",
    summary:
      "Injects AI-native failures — hallucination, schema corruption, injection, truncation — to see if your workflows survive.",
    steps: [
      {
        title: "What this is",
        body: "Ordinary chaos testing kills processes and drops packets. The failures that actually break an AI system are different: a model returns fluent nonsense, or valid JSON with the wrong shape, or silently truncates a long context.",
      },
      {
        title: "Choosing a failure",
        body: "Each injection targets one realistic failure mode. Run them against a workflow and watch whether the guards downstream — the quality gate, confidence, loop detection — actually catch it.",
      },
      {
        title: "Scope",
        caution:
          "Injections apply to your account's traffic. Do not leave one running against production and forget about it.",
        body: "An active experiment is reported on the Command Centre as a reason the system is not nominal, so it cannot run unnoticed.",
      },
    ],
  },

  "/chaos": {
    title: "Infrastructure Chaos",
    summary: "Takes providers and activities down deliberately, to prove the failover works.",
    steps: [
      {
        title: "What this is",
        body: "Failover that has never been exercised is a claim, not a feature. This makes the failure happen on purpose while you are watching.",
      },
      {
        title: "What you can break",
        body: "Take the primary provider down, or set a failure rate on activities and sinks. Then watch the Gateway: absorbed failures should climb while failures reaching your app stay at zero.",
      },
      {
        title: "Turning it off",
        body: "Experiments stay on until you stop them. The Command Centre reports an active experiment as a reason the system is not nominal so it is visible from anywhere.",
        caution: "This deliberately degrades real traffic for your account.",
      },
    ],
  },

  "/dashboard": {
    title: "the Command Centre",
    summary: "One screen answering what Continuum is doing right now.",
    steps: [
      {
        title: "The system state",
        target: '[data-guide="cc-state"]',
        body: "The worst state among installed subsystems, not an average — it reflects what your traffic would actually experience. Anything other than nominal names how many subsystems need attention.",
      },
      {
        title: "The headline band",
        target: '[data-guide="cc-stats"]',
        body: "Each figure carries the shape of its own recent history where there is one. Arrivals are drawn per poll rather than as a running total, because a cumulative line is the same shape every time and tells you nothing.",
      },
      {
        title: "The three panels",
        target: '[data-guide="cc-panels"]',
        body: "What the fleet is made of, what is moving through it right now, and what just happened. The events feed is live and newest-first.",
      },
      {
        title: "The topology",
        target: '[data-guide="cc-topology"]',
        body: "Every subsystem on the request path. Select one — in the map or in the list beside it — to focus the whole screen on it, including its own metrics and a link into its page.",
      },
    ],
  },
};

/** The guide for a route, if there is one. */
export function guideFor(pathname: string): Guide | undefined {
  return GUIDES[pathname];
}
