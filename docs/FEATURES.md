# Feature reference

Every feature is listed here once, under the name the console uses. For each:
- what it does;
- where it is in the console;
- which API it answers on;
- its switch key in `GET|PUT /api/portal/developer/features`;
- the document that explains it in depth.

The older documents are organised by release (V2 to V8). They are still
accurate, but you no longer need them to find anything. The version-named
switches (`/v6/enable`, `/v7/enable`, `/v8/{feature}/{action}`) still work, and
set the same things as the feature-named ones.

**Labs** marks research features. They are complete and tested, but the
engine's core guarantees do not need them, and they are more likely to change.

## The engine (always on)

| Feature | What it does | Where | Deep dive |
|---|---|---|---|
| Durable workflows | Every step is an event; a crash resumes from the last one; side effects go out exactly once through the outbox | Workflows → Run history, `/api/workflows` | [ARCHITECTURE](ARCHITECTURE.md) |
| One result per step | Outcomes are fenced by claim; an attempt is stopped at its timeout; replay keeps the first result | (engine) | [ARCHITECTURE §5](ARCHITECTURE.md) |
| Stuck workflows | A decision that keeps failing backs off, then is parked and shown as **stuck**, with *Try again* | Run page, `POST /api/workflows/{id}/resume` | [ARCHITECTURE §5](ARCHITECTURE.md) |
| Paradox healing | Old runs keep replaying after the workflow's code changes | Run page ledger | [V5_PARADOX_HEALING](V5_PARADOX_HEALING.md) |
| Webhooks | Your server is told, once and signed, when a run finishes, fails, is cancelled or gets stuck | Developer portal → Webhooks | [WEBHOOKS](WEBHOOKS.md) |
| Data retention | Removes queue bookkeeping and old logs on a schedule; never a running workflow | Accounts (operator) → Data retention | [ARCHITECTURE §3](ARCHITECTURE.md) |
| Model catalogue | Every free Groq and Gemini model, verified on your key and replaced when retired | Traffic → Models, `/api/models` | [MODEL_CATALOGUE](MODEL_CATALOGUE.md) |

## Traffic

| Feature | What it does | Where · API | Switch | Deep dive |
|---|---|---|---|---|
| Gateway | OpenAI-compatible endpoint with failover, streaming and a request log | Gateway · `/v1/chat/completions`, `/api/gateway` | — | [V3_GATEWAY](V3_GATEWAY.md) |
| Routing | Chooses a provider from measured cost, latency and quality; learns | Routing → Dispatch · `/api/routing` | operator | [V2 §2](V2_EXTENSIONS.md), [01](features/01-routing-and-concurrency.md) |
| Optimization (Autopilot) | Tunes the policy per account, canaried and gated by replay | Routing → Optimization | on its page | [V4_AUTOPILOT](V4_AUTOPILOT.md) |
| Counterfactual replay | What another policy would have done on real traffic | Routing → Counterfactual | — | [23](features/23-counterfactual-replay.md) |
| Hedging | Races a second provider when the first is slow | Routing → Dispatch · `/api/hedging` | operator | [V2 §4](V2_EXTENSIONS.md), [V8 §1.1](V8_RESEARCH.md) |
| Admission | Admits what the providers can take, sheds the rest early | Traffic Control → Admission | on its page | [12](features/12-admission-control.md) |
| Priority & deadlines | Serves urgent requests first; drops those that cannot finish in time | Traffic Control → Priority | on its page | [20](features/20-priority-deadlines.md) |
| Cost limits | Reserves spend before a call and settles on what it cost | Traffic Control → Cost limits | on its page | [22](features/22-cost-aware-limits.md) |
| Model Cascade | Cheap model first; escalates only when a judge says so | Model Cascade · `/api/portal/developer/cascade` | `model-cascade` | [02](features/02-model-cascade.md), [24](features/24-speculative-cascade.md) |

## Prompt

| Feature | What it does | Where · API | Switch | Deep dive |
|---|---|---|---|---|
| Pipelines | Input in, answer out: the endpoint an app calls with a file or payload | Pipelines · `/api/gateway/pipeline` | — | [07](features/07-pipelines.md) |
| Specialists | A purpose-built model (vision, speech, OCR) before the language model | Specialists | — | [06](features/06-specialists.md), [10](features/10-specialist-routing.md) |
| Prompt Firewall | Redacts personal data going out and secrets coming back; screens injection | Prompt Guard | `prompt-firewall` | [V8 §2.2](V8_RESEARCH.md) |
| Prompt Compression | Shortens long prompts within a quality budget | Prompt Guard | `prompt-compression` | [V8 §2.1](V8_RESEARCH.md), [21](features/21-compression-budget.md) |
| Context Transformers | Turns a log, spreadsheet or email thread in a message into what a model reads best | Context → Transformers | `chat-context` | [CONTEXT-TRANSFORMERS](CONTEXT-TRANSFORMERS.md) |
| Memory | Long-lived memory a workflow or agent can store and retrieve | Context → Memory · `/api/memory` | — | [V2 §5](V2_EXTENSIONS.md) |
| Context Optimizer **(Labs)** | Pages long histories out of the window and back in on demand | Context Optimizer · `/api/mmu` | `context-optimizer` | [V7_CONTEXT_MMU](V7_CONTEXT_MMU.md), [15](features/15-working-set.md) |
| Semantic Cache | Answers an equivalent question from a previous answer | Semantic Cache | `semantic-cache` | — |

## Reliability

| Feature | What it does | Where · API | Switch | Deep dive |
|---|---|---|---|---|
| Verification Engine **(Labs)** | Solves, verifies and scores an answer as a graph of agents | Verification Engine · `/api/dag` | `verification-engine` | [V6_CONSENSUS_DAG](V6_CONSENSUS_DAG.md) |
| Quality Gate | Scores each answer against its request; monitors or repairs | Answer Assurance → Quality gate | modes, on its page | [04](features/04-quality-gate.md), [13](features/13-answer-repair.md) |
| Confidence | Resamples and measures agreement in meaning, not wording | Answer Assurance → Confidence | modes, on its page | [03](features/03-answer-confidence.md), [08](features/08-confidence-policy.md), [14](features/14-adaptive-consensus.md) |
| Semantic Breaker | Takes a model out of rotation when its answers degrade | Answer Assurance → Semantic breaker | on its page | [05](features/05-semantic-breaker.md) |
| Loop Detection | Spots an agent going round in circles | Loop Detection | on its page | [18](features/18-loop-detection.md) |
| Decision Provenance | Why each answer happened, as data | Decision Provenance | on its page | [16](features/16-decision-provenance.md) |
| Compensation (saga) | Undoes completed steps when a run fails or is cancelled | Workflows → Compensation | on its page | [19](features/19-saga-compensation.md) |
| Replay audit | Re-runs past model steps against today's provider and scores drift | Workflows → Replay audit · `/api/replay` | — | [V2 §1](V2_EXTENSIONS.md) |
| Degradation ladder | Steps down to cheaper answers under pressure instead of failing | (gateway) | — | [17](features/17-degradation-ladder.md) |
| Chaos Lab | Breaks infrastructure and models on purpose, per account | Chaos Lab · `/api/chaos`, `/api/ai-chaos` | — | [V2 §3](V2_EXTENSIONS.md) |

## Intelligence

| Feature | What it does | Where | Switch | Deep dive |
|---|---|---|---|---|
| Adaptive Policy **(Labs)** | Learns memory and routing policy from traffic, promoted after replay proves it | Adaptive Policy | `adaptive-policy` | [V5_GOD_MODE](V5_GOD_MODE.md) |

## Streaming

`"stream": true` on `/v1/chat/completions` sends text as the provider
generates it. Some features judge or rewrite the finished answer: the
enforcing quality gate, confidence sampling, cascade, firewall, verification
engine, hedging, context optimizer and AI chaos. When any of them is on, the
answer is buffered and then sent. See [V3_GATEWAY § Streaming](V3_GATEWAY.md).

## Recommendations (the advisor)

`GET /api/portal/developer/advice` reads the account's whole configuration
against its own recent traffic and returns what is wrong with it: a max latency
no model has met, a Verification Engine that bypasses features the account also
turned on, a repair budget shorter than a model call, a cache that matches too
loosely, a chaos experiment left armed, no provider key at all. Each item says
why, how to fix it, and which page the setting is on. The console shows new
findings in a pop-up and keeps them behind the badge in the bottom-right corner;
it re-checks after every saved setting.

## How features combine

- **Verification Engine** answers on its own path. The Prompt Firewall still
  runs first and on the answer; cache, cascade, quality gate, confidence,
  compression, context optimizer and transformers do not. The advisor says so
  when any of them is on alongside it. It is given the conversation and the
  system prompt, not only the last message.
- **Semantic Cache** is keyed on the whole conversation, not the last message,
  and skips requests with tools or images. It stores the answer the caller
  actually got, after the quality gate and confidence checks, and never one
  that failed the gate, came back with low confidence, or was corrupted by AI
  chaos.
- **Cascade** and **hedging** answer the caller's own request, so tools and JSON
  mode are kept; hedged answers go through the quality gate and confidence
  checks like every other path. Quality-gate repairs and confidence resamples
  keep the caller's JSON mode.
