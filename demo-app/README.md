# Brightcart Support Desk: an example application built on Continuum

This folder is **not part of Continuum**. It is the kind of application an
outside developer builds once they have a Continuum API key: a support desk for
a fictional online store, Brightcart. It uses Continuum only the way any
customer would, over HTTP with a `cnt_live_…` key, and it exercises every
feature Continuum offers along the way.

Each place in the page where Continuum did something is marked in indigo, and
the **Continuum inspector** on the right lists every call the app made, with
what was sent and what Continuum did with it.

## What it is made of

```
 browser ──► Brightcart server (python3 server.py, port 5050) ──► Continuum (port 8080)
               holds the API key; the browser never sees it          │
                    ▲                                                │
                    └──── Continuum calls back ◄─────────────────────┘
                          /svc/...              refund workflow steps (payments, stock, email, courier, CRM)
                          /specialist/...       this store's receipt reader, used by a pipeline
                          /webhooks/continuum   signed notifications when a refund run ends
```

* Python 3.8+ standard library only. Nothing to install.
* `brightcart/continuum.py` is the Continuum client (about 600 lines, no SDK).
  It is the best place to look for how each API is called.
* `brightcart/services.py` is the store's back office: the HTTP services the
  refund workflow calls, with switches to make them fail.
* `web/` is the page: plain HTML, CSS and ES modules.

## Run it

**1. Start Continuum so it may call this app.** Continuum refuses to call
private addresses such as `localhost` unless told otherwise, and this app runs
on your machine. Set `CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS=true`:

```bash
# from the repository root, running the jar
cd backend && CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS=true java -jar target/continuum.jar
# or with Maven
cd backend && CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS=true mvn spring-boot:run
# or with Docker
CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS=true docker compose up --build
```

Continuum also needs a model provider: set `GEMINI_API_KEY` and/or
`GROQ_API_KEY`, or add a key in the console under *API Keys & Providers*.

**2. Have a console account.** Sign up in the Continuum console (for example
`http://localhost:5173` or `http://localhost:8081` with Docker).

**3. Configure the account for this app**, once:

```bash
cd demo-app
python3 setup_continuum.py --email you@example.com      # asks for your password
```

It signs in and does what you would otherwise click through in the console:
issues an API key, registers the receipt reader as a specialist and builds a
pipeline on it, publishes the `brightcart-refund` workflow, registers the
webhook endpoint, and turns on the features the demo shows. It is safe to run
again. The key and secrets go into `demo.config.json`, which is git-ignored and
readable only by you. Use `--no-features` to leave your feature settings alone.

**4. Start the app** and open <http://localhost:5050>:

```bash
python3 server.py
```

### When Continuum runs in Docker

Inside a container, `localhost` is the container. Tell Continuum to call the
host, and let this app listen on every interface:

```bash
python3 setup_continuum.py --email you@example.com --public-url http://host.docker.internal:5050
DEMO_HOST=0.0.0.0 python3 server.py
```

### Settings

`demo.config.json` holds everything; environment variables override it:
`CONTINUUM_URL`, `CONTINUUM_API_KEY`, `DEMO_HOST`, `DEMO_PORT`,
`DEMO_PUBLIC_URL`, `DEMO_WEBHOOK_SECRET`, `DEMO_SPECIALIST_TOKEN`.

## A ten-minute walkthrough

Keep the inspector open. Clicking a call shows the request, the response and
Continuum's `X-Continuum-Applied` header, which lists what acted on it.

1. **Live chat** (signed in as Aisha Khan). Click the suggestions in turn:
   * *Where is my order …?* The model calls the `lookup_order` tool. The chips
     show the model, the cascade and the quality gate, plus a request id you
     can look up in the console's Decision Provenance.
   * *…arrived damaged, I'd like a refund…* The model calls `start_refund`,
     which starts a durable Continuum workflow (see step 3).
   * *Please remember: I prefer updates on WhatsApp…* stores a fact in
     Continuum's long-term memory. It appears under *Memory* and is recalled
     into later chats. Saying it again strengthens it instead of duplicating it.
   * *My card is 4111…* The prompt firewall masks the card number and PIN
     before any model sees them. *Ignore all previous instructions…* is blocked
     outright (HTTP 403), and later messages in the chat still work.
   * Rate an answer 👍/👎 to send feedback to Continuum's Autopilot.
     **Loop drill** shows the loop detector stopping an agent that repeats one
     tool call. **Models** lists what Continuum can route to.
   * **Help centre**: ask *What is your return policy?*, then *Your return
     policy, what is it?*. The second is served from the semantic cache with no
     model called. A different question is not.
   * Tick **Stream** for token-by-token answers. Pick a routing mode to steer
     the choice of model.
2. **Ticket inbox.** **Triage** sends a ticket in JSON mode. Platinum and
   live-chat tickets go as CRITICAL with a 15-second deadline. **Triage all**
   sends every ticket at once as BACKGROUND, which is the burst admission
   control exists for. T-503 shows card data being redacted and T-504 an
   injection being blocked. **Careful draft** routes for HIGH_QUALITY and asks
   for a confidence measurement.
3. **Refunds.** **Start refund** on BC-1042 and watch the steps run: verify,
   a durable 6-second fraud hold, refund and restock in parallel, email,
   courier pickup, then the CRM note. A signed webhook arrives at the end, and
   the ledger lists every call Continuum made to the store, each with its
   Idempotency-Key. Then break the back office with the switches on the right:
   * courier **flaky**: the pickup fails twice and Continuum retries it with
     backoff;
   * payments **lost-response**: the refund happens but its reply is lost.
     Continuum retries with the same key, and the card is refunded once (look
     for *duplicate suppressed*);
   * courier **down** (or email **down**): the run fails and the saga undoes
     the refund and the restock, newest first. The email cannot be unsent, so
     it is listed as not undone;
   * **Cancel** during the fraud hold: the run stops, and anything it had done
     is rolled back.
4. **Receipts & photos.** **Check receipt** runs the `brightcart-receipt-check`
   pipeline. Continuum calls this app's own receipt reader (a custom
   specialist), turns its findings into evidence and asks a model. Try the
   wrong-total and too-old samples. **Ask about a photo** sends an image to a
   vision model.
5. **Data files.** **Transform** shows what Continuum's context transformers
   make of a CSV, a log or an email thread, and how many tokens that saves.
   **Ask** pastes the file into a chat inside a fenced block, and Continuum
   rewrites it on the way to the model.
6. **Feature coverage** ticks off each feature as the inspector sees it, and
   says how to show the rest.

## Feature map

| Continuum feature | Where in this app | Continuum API | Console page |
|---|---|---|---|
| AI gateway | every chat, triage and draft | `POST /v1/chat/completions` | Gateway → request feed |
| Streaming | Live chat → Stream | same, `"stream": true` (SSE) | Gateway |
| Tool calling | Live chat (order lookup, refunds, preferences) | same, `tools` | Gateway |
| JSON mode | Ticket inbox → Triage | same, `response_format` | Gateway |
| Vision | Receipts & photos → Ask about a photo | same, `image_url` content part | Gateway |
| Routing modes | Live chat mode picker; Careful draft | `continuum_routing_mode` | Routing |
| Model catalogue | Live chat → Models | `GET /v1/models` | Models |
| Prompt firewall | card number / PIN in chat; T-503, T-504 | automatic on chat | Prompt Guard |
| Context transformers | Data files | `POST /api/gateway/context/transform`; fenced blocks in chat | Context |
| Semantic cache | Live chat → Help centre | automatic on chat | Semantic Cache |
| Long-term memory | Live chat memory panel; `save_preference` tool | `POST /api/gateway/memory/store`, `/retrieve` | Memory |
| Model cascade | any chat (on after setup) | automatic | Model Cascade |
| Quality gate | any chat (monitoring after setup) | automatic | Answer Assurance |
| Confidence | Ticket inbox → Careful draft | `continuum_measure_uncertainty` | Answer Assurance |
| Priority & deadlines | urgent tickets | `continuum_criticality`, `continuum_deadline_ms` | Traffic Control |
| Admission control | Ticket inbox → Triage all | BACKGROUND criticality | Traffic Control |
| Durable workflows | Refunds | `POST /api/gateway/workflows/{name}/run`, `GET …/{id}` | Workflows |
| Saga compensation | Refunds with a service down, or Cancel | `POST /api/gateway/workflows/{id}/cancel` | Workflows → Compensation |
| Exactly-once steps | Refunds with payments losing responses | Idempotency-Key on every step | Workflows |
| Signed webhooks | every refund that ends | Continuum → `/webhooks/continuum` | Developer portal → Webhooks |
| Loop detection | Live chat → Loop drill; the assistant's own tool loop | `POST /api/gateway/loops/inspect` | Loop Detection |
| Pipelines and specialists | Receipts & photos → Check receipt | `POST /api/gateway/pipeline/{name}` (multipart) | Pipelines, Specialists |
| Autopilot feedback | 👍/👎 on an answer | `POST /api/gateway/feedback` | Routing → Optimization |
| Decision provenance | the request id under every answer | `continuum.request_id` | Decision Provenance |
| Plans & quota | every request | — | Billing |
| Context optimizer, adaptive policy, prompt compression, verification engine, hedging, cost limits, provider failover, degradation, AI chaos, semantic breaker | turn on in the console (the coverage page says how), then use the app as usual | automatic | the page of the same name |

What every answer was put through comes back in the `X-Continuum-Applied`
response header and in `continuum.applied` in the body.

## Troubleshooting

* **"No Continuum API key"**: run `setup_continuum.py`, or put `api_key` in
  `demo.config.json`.
* **`invalid_api_key` (401)**: the key was revoked, or belongs to another
  Continuum. Run `setup_continuum.py --new-key`.
* **`no_provider` (503)**: Continuum has no model provider. See step 1.
* **Setup says the receipt reader could not be probed, or refund steps fail
  with a private-address error**: Continuum was started without
  `CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS=true`, or cannot reach
  `--public-url` (see the Docker section).
* **No webhooks arrive**: the run page says so. Check that setup registered the
  endpoint and that Continuum can reach this app.
* **Port 5050 in use**: stop the other `server.py`, or set `DEMO_PORT` (and
  rerun setup with the matching `--public-url`).
* **Start over**: *Refunds → Reset store* puts stock, refunds and the fault
  switches back; the store's data also resets when the server restarts. Clear
  the inspector with its *Clear* button.
