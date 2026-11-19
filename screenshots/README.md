# Continuum — screenshots and visuals

Taken from the console running locally with demonstration traffic (seed script),
plus the project's diagrams, measured results, the PawCare sample app and the
chalkboard/whiteboard presentation.

## Console — the most interesting features (`console/`)

| File | What it shows |
|---|---|
| [01-landing](console/01-landing.jpg) | Public landing page |
| [02-command-centre](console/02-command-centre.jpg) | Command Centre: health of every subsystem, live counters, system events |
| [03-gateway](console/03-gateway.jpg) | Gateway: requests, absorbed failures, latency spread, which models answered, live request flow |
| [04-routing-dispatch](console/04-routing-dispatch.jpg) | Routing: traffic split across providers by measured share, per-provider stats |
| [05-model-cascade](console/05-model-cascade.jpg) | Model Cascade: cheap model first, escalation only when needed, money saved |
| [06-semantic-cache](console/06-semantic-cache.jpg) | Semantic Cache: hit rate, tokens and cost avoided, match threshold |
| [07-prompt-guard](console/07-prompt-guard.jpg) | Prompt Guard: personal data redacted and injections blocked before the provider sees them |
| [08-context-transformers-spreadsheet](console/08-context-transformers-spreadsheet.jpg) | Context Transformers: an Excel sheet turned into a semantic table (headers, units, aggregates) |
| [09-memory](console/09-memory.jpg) | Memory: tiers (working, episodic, long-term, archived) for a scope |
| [11-specialists](console/11-specialists.jpg) | Specialists: catalogue of vision, speech and OCR models to add before the LLM |
| [12-verification-engine](console/12-verification-engine.jpg) | Verification Engine: questions solved and verified as a graph, with confidence |
| [13-verification-run](console/13-verification-run.jpg) | A verification run: the agent graph, findings and execution timeline |
| [14-quality-gate](console/14-quality-gate.jpg) | Quality Gate: answers checked against the request, what it would have repaired |
| [15-answer-confidence](console/15-answer-confidence.jpg) | Answer Confidence: sampling the model several times and measuring agreement |
| [16-loop-detection](console/16-loop-detection.jpg) | Loop Detection: spotting an agent going round in circles |
| [17-decision-provenance](console/17-decision-provenance.jpg) | Decision Provenance: every decision behind a request, as data |
| [18-workflow-run-history](console/18-workflow-run-history.jpg) | Durable workflows: run history, exactly-once deliveries (0 duplicates) |
| [19-workflow-run-timeline](console/19-workflow-run-timeline.jpg) | A workflow run: retries after injected failures, findings, event log |
| [20-saga-compensation](console/20-saga-compensation.jpg) | Saga: a cancelled run whose completed steps were undone |
| [21-replay-audit](console/21-replay-audit.jpg) | Replay Audit: past runs re-checked against today's model for drift |
| [22-chaos-lab-model-failures](console/22-chaos-lab-model-failures.jpg) | Chaos Lab: hallucinations and schema corruption injected, 100% survived |
| [23-autopilot-optimization](console/23-autopilot-optimization.jpg) | Autopilot: policy proposed, canaried on 10% of traffic, promoted or rolled back |
| [24-counterfactual-replay](console/24-counterfactual-replay.jpg) | Counterfactual Replay: what another routing policy would have done |
| [25-priority-deadlines](console/25-priority-deadlines.jpg) | Priority & Deadlines: urgent work first, impossible deadlines refused |
| [26-api-keys-providers](console/26-api-keys-providers.jpg) | API Keys & Providers: per-app keys, provider keys, webhooks |

## Diagrams (`diagrams/`)

| File | What it shows |
|---|---|
| [01-system-architecture](diagrams/01-system-architecture.png) | System architecture |
| [02-gateway-request-pipeline](diagrams/02-gateway-request-pipeline.png) | Gateway request pipeline |
| [03-evidence-pipeline-specialists](diagrams/03-evidence-pipeline-specialists.png) | Evidence pipeline specialists |
| [04-sequence-crash-and-recovery](diagrams/04-sequence-crash-and-recovery.png) | Sequence crash and recovery |
| [05-event-log-and-replay](diagrams/05-event-log-and-replay.png) | Event log and replay |
| [06-flowchart-gateway-request](diagrams/06-flowchart-gateway-request.png) | Flowchart gateway request |
| [07-use-case-diagram](diagrams/07-use-case-diagram.png) | Use case diagram |
| [08-entity-relationship-diagram](diagrams/08-entity-relationship-diagram.png) | Entity relationship diagram |

## Measured results (`results/`)

Benchmark: 100 requests per arm, provider failures injected at 25% and 50%; paradox healing: 300 code changes deployed under running workflows.

| File | What it shows |
|---|---|
| [01-failures-absorbed-vs-reaching-app](results/01-failures-absorbed-vs-reaching-app.png) | Failures absorbed vs reaching app |
| [02-tokens-cache-off-vs-on](results/02-tokens-cache-off-vs-on.png) | Tokens cache off vs on |
| [03-latency-cache-off-vs-on](results/03-latency-cache-off-vs-on.png) | Latency cache off vs on |
| [04-paradox-healing-300-trials](results/04-paradox-healing-300-trials.png) | Paradox healing 300 trials |

## PawCare sample app (`pawcare/`)

| File | What it shows |
|---|---|
| [01-chat-and-photo-check](pawcare/01-chat-and-photo-check.jpg) | Chat and photo check |
| [02-vet-summary](pawcare/02-vet-summary.jpg) | Vet summary |
| [03-welcome](pawcare/03-welcome.jpg) | Welcome |
| [04-mobile-photo-check](pawcare/04-mobile-photo-check.jpg) | Mobile photo check |
| [05-new-pipeline-name-check](pawcare/05-new-pipeline-name-check.jpg) | New pipeline name check |

## Presentation (`presentation/`)

From `presentation/continuum-explained.html`.

| File | What it shows |
|---|---|
| [01-title](presentation/01-title.jpg) | Title |
| [02-the-ai-intern](presentation/02-the-ai-intern.jpg) | The ai intern |
| [03-continuum-the-manager](presentation/03-continuum-the-manager.jpg) | Continuum the manager |
| [04-six-jobs](presentation/04-six-jobs.jpg) | Six jobs |
| [05-never-lose-work](presentation/05-never-lose-work.jpg) | Never lose work |
| [06-never-stuck](presentation/06-never-stuck.jpg) | Never stuck |
| [07-give-ai-eyes](presentation/07-give-ai-eyes.jpg) | Give ai eyes |
| [08-before-after](presentation/08-before-after.jpg) | Before after |
| [09-drawing-in-progress](presentation/09-drawing-in-progress.jpg) | Drawing in progress |
| [10-presenter-window](presentation/10-presenter-window.jpg) | Presenter window |
