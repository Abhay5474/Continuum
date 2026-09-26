# Model catalogue

Continuum keeps its list of Groq and Gemini models current by itself. When a
provider retires a model — Groq retired `llama-3.3-70b-versatile` and
`llama-3.1-8b-instant` for free accounts on 16 Aug 2026 — requests move to a
replacement instead of failing, and the console says what happened.

## Where the facts come from

Only the providers' own APIs. Nothing is scraped from web pages.

| Fact | Source |
|---|---|
| Which models exist | `GET /openai/v1/models` (Groq), `GET /v1beta/models` (Gemini) |
| What a model is for | Groq: the model id. Gemini: `supportedGenerationMethods` and the id |
| Whether it is free | A test call on the configured key. Keys are declared free-tier (`GROQ_FREE_TIER`, `GEMINI_FREE_TIER`, default `true`), so a model that answers is free; Gemini answers a model with no free quota with `429 … limit: 0` |
| Its limits on this key | Groq's `x-ratelimit-limit-requests` / `x-ratelimit-limit-tokens` response headers |
| Provider terms (data use, retirements) | Short notes written from the providers' published pages, dated, each linking to its source (`ProviderPolicies`) |

Speech, text-to-speech, safety, embedding, image and alias models are catalogued
but never tested or routed.

## When it checks, and what it costs

- **Scheduled:** a provider is due 10 days after its last successful check
  (6 hours after a failed one). Measured from the last success rather than on
  fixed dates, so a server that was off on a check day catches up when it next
  runs. The hourly tick that decides this reads only the database.
- **Check now:** any signed-in user. One check at a time across all instances
  (a database lease), and at most one every 10 minutes (`409` while running,
  `429` during the cooldown).
- **Per check:** one list call per provider (Gemini: one per page, at most 3),
  and at most 12 test calls per provider, spaced 2.5 s (Groq) / 6 s (Gemini)
  apart. Testing stops after two rate-limited or failed test calls in a row, or
  at once if the key is refused. Models already verified are not re-tested for
  30 days.
- **Startup:** database only. No provider is called on the startup path.

## What can retire a model

- A failed or refused list changes nothing.
- A list that is empty, or much shorter than the last, retires nothing.
- A model that was listed before stops being routed the first time a successful
  list leaves it out, and is retired the second time.
- A model never seen in a list (an out-of-date seed, or a name kept from before
  the catalogue) is retired by the first successful list without it.
- A live request told the model is gone (Groq `404 model_not_found` or
  `model_decommissioned`, Gemini `404 … is not found`) sets it aside at once and
  triggers one confirming list call (at most one per provider per 30 minutes).
  Absent from that list, the model is retired; still listed, it is marked not
  usable on this key. Reports from a developer's own key are ignored: their key
  may simply lack access.

## Which model runs

- A request naming no model runs on the provider's default.
- The default is, in order: a model pinned by the operator, the model named in
  `GROQ_MODEL` / `GEMINI_MODEL` (a preference, not a requirement), the current
  default while it still works, or — when it stops working — the best usable
  model: same family first (`gemini-3.5-flash` → `gemini-3.8-flash`), stable
  before preview, stronger, newer. A newer model appearing never moves the
  default by itself.
- A request naming a retired or unusable model runs on its replacement: the
  default when it is the same family, else the newest usable model of that
  family, else the default.
- When a live request finds its model gone, it is retried once on the same
  provider's default. Never more than once.
- The gateway tries at most 6 models per request, and at most 3 per provider.

## Where to see it

**Traffic → Models** in the console: every model by its real name, whether it
is in use, verified free, not usable on this key, missing or retired (and what
replaced it), the limits the provider reported, each provider's terms with
links, a timeline of every change, and the Check now button.

API: `GET /api/models/catalog`, `GET|POST /api/models/check`,
`GET /api/models/events`, `GET /api/models/runs`, `POST /api/models/pin`
(operator).

## Settings

| Property | Default |
|---|---|
| `continuum.models.check-interval-days` | 10 |
| `continuum.models.retry-after-failure-hours` | 6 |
| `continuum.models.manual-cooldown-minutes` | 10 |
| `continuum.models.max-probes-per-provider` | 12 |
| `continuum.models.probe-spacing-ms.groq` / `.gemini` | 2500 / 6000 |
| `continuum.models.confirm-cooldown-minutes` | 30 |
| `continuum.models.reprobe-after-days` | 30 |
| `continuum.models.quarantine-hours` | 6 |
