# Brightcart × Continuum: a live demo store

This folder is **not part of Continuum**. It is the kind of application an
outside company builds once it has a Continuum API key: **Brightcart**, an
Indian online store (₹ prices, UPI and cash on delivery, customers in Pune,
Mumbai, Bengaluru and Delhi) whose AI features all run through Continuum.

The page has two halves:

* **The store** (left): a real-looking shop with a chat assistant, *My orders*
  with returns and refunds, a staff **Support desk**, and a staff **Back
  office**.
* **The Continuum X-ray** (right): for every AI call the store makes, the path
  it took through Continuum (Guard → Context → Cache → Traffic → Routing → AI
  model → Checks), with each step that acted lit up and explained in one line.

The **Feature tour** button at the top lists all 36 Continuum features. Press
**Show me** on any of them and the store does it live: it types into the chat,
clicks through the desk, starts a refund, and the X-ray shows what Continuum
did. **▶ Play all** runs every feature in order, about 5 minutes in total.

## What it is made of

```
 browser ──► Brightcart server (python3 server.py, port 5050) ──► Continuum (port 8080)
               holds the API key; the browser never sees it          │
                    ▲                                                │
                    └──── Continuum calls back ◄─────────────────────┘
                          /svc/...              refund steps (payments, stock, email, courier, CRM)
                          /specialist/...       the store's own receipt reader, used by a pipeline
                          /webhooks/continuum   signed notifications when a refund ends
```

* Python 3.8+ standard library only. Nothing to install.
* `brightcart/continuum.py` is the store's Continuum client, plain HTTP with an
  API key. It is the best place to see how each API is called.
* `brightcart/presenter.py` holds the presenter's console sign-in (see below).
* `web/` is the page: plain HTML, CSS and ES modules, no build step.

## Run it

You need Continuum running with at least one AI provider key (Gemini or Groq,
set on the server or saved in the console under *API Keys & Providers*).

**1. Start Continuum so it may call this app back.** The store runs on your
machine, and Continuum refuses to call `localhost` unless told it may:

```bash
# Linux / macOS, from the repository root
cd backend && CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS=true java -jar target/continuum.jar
```

```powershell
# Windows PowerShell, from the repository root
cd backend
$env:CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS="true"
java -jar target\continuum.jar
```

**2. Have a console account** (sign up in the Continuum console).

**3. Set the account up for the store, once.** In a second terminal:

```bash
cd demo-app
python setup_continuum.py --email you@example.com
```

It asks for your password (it is not stored) and then does what you would
otherwise click through in the console: issues an API key for the store,
registers the store's receipt reader and a pipeline that uses it, publishes the
refund workflow, registers the webhook, and switches on the features the store
uses. It is safe to run again. The key and secrets are saved in
`demo.config.json`, which is git-ignored.

**4. Start the store** and open <http://localhost:5050>:

```bash
python server.py
```

## Presenting it

1. Open <http://localhost:5050> and click **Presenter** (top right). Sign in
   with the same Continuum account you used in step 3.
2. Click **Feature tour** and either press **Show me** on any card, or
   **▶ Play all**.

**Why the presenter signs in.** The store holds only an API key, like any real
application, and an API key cannot change Continuum's settings. That is the
console's job, behind a person's sign-in. About a dozen tour steps need a
setting changed for a moment (turning on prompt compression, taking a provider
down for the failover step, arming an AI chaos drill, setting a cost limit).
With the presenter signed in, the tour makes the change, runs the step, and
puts the setting back exactly as it was. The password is used once to sign in
and is never stored; the session lives only in the store's memory.

**Hedging needs an operator.** Hedging changes the whole engine, not one
account, so only a Continuum operator can switch it on. Sign in as the
presenter with an operator account (Continuum console → *Operator access*) to
include that step; otherwise the tour explains it and moves on.

Everything else works without signing in: the shop, chat, returns, the desk and
the back office are the store's own features and only use the API key.

## The 36 features, and where they show

| Group | Features | Where in the store |
|---|---|---|
| Talk to any AI | gateway, streaming, tool calling, JSON mode, vision, model catalogue | chat assistant, support desk triage, back office → AI models |
| Protect the store | prompt firewall, loop detection, cost limits | chat (card numbers), desk (injection ticket, agent sandbox, triage all) |
| Spend less | semantic cache, model cascade, prompt compression, context optimizer, context transformers, routing modes | help centre, chat, 62-message chat, back office → data for the AI, desk drafts |
| Trust the answers | quality gate, confidence, verification engine, semantic breaker | every answer, desk → careful draft, help centre |
| Never go down | failover, degradation ladder, hedging, AI chaos drills | chat with a provider taken down |
| Handle the rush | admission control, priority & deadlines, plans & quota | desk → AI triage all, urgent ticket, back office → plan & usage |
| Remember and learn | long-term memory, adaptive policy, autopilot | chat (preferences, house rules, 👍 / 👎) |
| Run real processes | durable workflows, saga compensation, exactly-once steps, signed webhooks | My orders → Return & refund, back office → store systems |
| Bring your own models | pipelines, specialists | back office → receipt check |
| See why | decision provenance | X-ray → *Why this answer?* |

## When Continuum runs in Docker

Inside a container, `localhost` is the container. Tell Continuum to call the
host, and let the store listen on every interface:

```bash
python setup_continuum.py --email you@example.com --public-url http://host.docker.internal:5050
DEMO_HOST=0.0.0.0 python server.py
```

## Settings

`demo.config.json` holds everything; environment variables override it:
`CONTINUUM_URL`, `CONTINUUM_API_KEY`, `DEMO_HOST`, `DEMO_PORT`,
`DEMO_PUBLIC_URL`, `DEMO_WEBHOOK_SECRET`, `DEMO_SPECIALIST_TOKEN`.

## Sample files

`samples/` holds what the back office works with: three receipts (one valid,
one with an edited total, one outside the 30-day window), an order export
(CSV), a checkout error log, an email thread and a photo of a cracked mug.
The receipt check also takes your own PDF or text receipt.
