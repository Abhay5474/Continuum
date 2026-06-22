# Continuum V3 — Developer AI Infrastructure Gateway

V3 turns Continuum into a **developer-facing AI infrastructure control plane**.
External applications integrate once against the Continuum gateway and get
reliability, intelligent routing, model failover, model-lifecycle management,
secure key management and observability — without touching provider SDKs.

**Strictly additive.** No existing component is modified or removed; the V1
durable engine and the V2 reliability extensions are unchanged. Authentication
is added only on the developer chat endpoints; every pre-existing endpoint stays
open and backward compatible. Only new DB tables are added (migration `V3`).

```
External app ──Bearer cnt_live_xxx──► /api/gateway/chat
                                        │  (ApiKeyAuthenticationFilter — gateway routes only)
                                        ▼
        ┌───────────────────────  GatewayService  ───────────────────────┐
        │ RequestNormalizer → TaskComplexityEstimator(reused) →           │
        │ ProviderSelectionEngine(reused, measured stats) →               │
        │ ModelRegistry(capability filter, ACTIVE only) →                 │
        │ ModelFallbackPolicy(provider+model chain) →                     │
        │ CredentialVault(decrypt developer's own key) →                  │
        │ ProviderRouter(request, [provider], devKey)  (V1 failover)      │
        │ ProviderHealthTracker(record) → gateway_requests(log)           │
        └─────────────────────────────────────────────────────────────────┘
                                        ▼
                          Gemini · Groq · Mock adapters
```

## Reuse, not rebuild

Features 4 (intelligent routing) and the scoring behind 5 already existed in
`io.continuum.routing` (V2). V3 **consumes** them:

- `TaskComplexityEstimator`, `ProviderSelectionEngine`, `ProviderScorer`,
  `ProviderMetrics` → provider preference from real measured stats.
- `ProviderRouter` → unchanged failover; V3 only added an additive
  `complete(request, chain, apiKeysByProvider)` overload for BYO keys.

New in V3: the gateway, developer accounts/API keys, the credential vault, the
model registry/lifecycle/discovery, model-level fallback + health, and the
developer observability dashboard.

## Features

### F1 — Developer API Gateway
`POST /api/gateway/chat` (and OpenAI-shaped alias `POST /v1/chat/completions`).
```json
// request
{ "model": "auto", "messages": [{"role":"user","content":"..."}], "maxTokens": 500, "routingMode": "BALANCED" }
// response
{ "response": "...", "provider": "gemini", "model": "gemini-3.5-flash",
  "latency": 1200, "tokens": 340, "cost": 0.002, "failovers": 0, "routingReason": "..." }
```

### F2 — Continuum API keys
Issued as `cnt_live_…`, used via `Authorization: Bearer …`. Stored **hashed**
(SHA-256, peppered with the master key) plus a non-secret prefix for lookup.
Plaintext is returned once at creation and never again. Invalid/absent keys get
a generic `401`.

### F3 — Secure provider-key vault
Developers store their own Gemini/Groq keys; Continuum encrypts them with
**AES-256-GCM** (authenticated) using `CONTINUUM_MASTER_KEY`. Secrets are
decrypted only in-memory at call time, never logged, and never returned by any
endpoint (only provider-name metadata). Verified by test and at runtime: the
`developer_provider_credentials` table contains only ciphertext.

### F4 — Intelligent (deterministic) routing
Per request: `complexity = f(prompt length, context, reasoning keywords)`;
providers ordered by measured latency/cost/quality (reused V2 scorer); the
developer's own configured providers are preferred (their keys). No LLM in the
routing loop.

### F5 — Hierarchical model failover + health
`ModelFallbackPolicy` builds a `(provider, model)` chain; the gateway walks it
until one succeeds (e.g. `gemini-3.5-flash → gemini-2.5-flash → … → mock`).
`ProviderHealthTracker` records per-model availability/latency/error-rate
(`provider_model_health`) to deprioritize unhealthy targets.

### F6 — Model registry & lifecycle
`models` table with lifecycle `DISCOVERED → TESTING → ACTIVE → DEPRECATED →
REMOVED`. `ModelDiscoveryProvider`s (Gemini/Groq/Mock) seed a vetted catalog at
startup and a monthly `ModelDiscoveryScheduler` reconciles changes — net-new
live models enter as `DISCOVERED` (never auto-activated); **disappeared models
are auto-marked `DEPRECATED`**. Only `ACTIVE` models are routable.

### F7 — Capability-aware routing
Each model stores capabilities (context window, vision, tools, json) + pricing.
A request needing vision (`requireVision: true`) only routes to `vision=true`
models. Complex tasks prefer stronger models; simple tasks prefer cheaper ones.

### F8 — Developer observability
`GET /api/gateway/stats` — total requests, success rate, **failures prevented**
(silent failovers the developer never saw), developer-visible failures, provider
usage, tokens, cost. `GET /api/gateway/requests`, `GET /api/gateway/health`.
Surfaced in the **Gateway** dashboard tab (existing dashboards untouched).

## API summary

```
# developer (Bearer cnt_live_…)
POST /api/gateway/chat | POST /v1/chat/completions

# admin / onboarding (X-Admin-Token)
POST   /api/admin/developers                         {name,email}
GET    /api/admin/developers
POST   /api/admin/developers/{id}/keys               -> apiKey (once)
GET    /api/admin/developers/{id}/keys
DELETE /api/admin/keys/{keyId}
POST   /api/admin/developers/{id}/credentials        {provider,secret}   (encrypted; never echoed)
GET    /api/admin/developers/{id}/credentials        (metadata only)
DELETE /api/admin/developers/{id}/credentials/{provider}

# model registry (open / operator)
GET  /api/models | GET /api/models/active | POST /api/models/discover | POST /api/models/{id}/status?status=

# observability (open / operator)
GET  /api/gateway/stats | GET /api/gateway/requests | GET /api/gateway/health
```

## Database (migration `V3__developer_gateway.sql`, additive)

`developers`, `developer_api_keys`, `developer_provider_credentials`, `models`,
`provider_model_health`, `gateway_requests`.

## Configuration

| Env var | Purpose |
| --- | --- |
| `CONTINUUM_MASTER_KEY` | AES-256-GCM key for the credential vault (+ API-key pepper). **Required in prod.** Dev fallback = ephemeral key with a loud warning. |
| `CONTINUUM_ADMIN_TOKEN` | Required `X-Admin-Token` for `/api/admin/**`. If unset (dev), admin routes are open with a warning. |
| `continuum.registry.discovery-cron` | Model discovery schedule (default monthly). |

## Security considerations

- Provider secrets: AES-256-GCM at rest, decrypt-on-use, never logged/returned.
- API keys: hash + prefix only; constant-time verify; shown once.
- Auth scope: only `/api/gateway/chat` + `/v1/chat/completions` are key-protected
  — V1/V2 endpoints unchanged. Admin routes gated by a separate token filter.
- Upstream failures surface as a single generic `502` (no provider internals).

## Tests (mvn test — 26 total, all green)

| Test | Proves |
| --- | --- |
| `AesGcmCipherTest` | round-trip, non-deterministic IV, wrong-key/tamper rejection (no plaintext at rest) |
| `ApiKeyHasherTest` | key format, hash ≠ plaintext, verify match/mismatch, pepper effect |
| `ModelFallbackPolicyTest` | simple→cheap, complex→strong, vision filter, requested-model pinning |

Verified at runtime against PostgreSQL: developer onboarding + key issuance;
`auto` routing → mock; invalid key → 401; credential stored as ciphertext only
(DB grep finds no plaintext, metadata API hides the secret); invalid Gemini key
→ 4 silent model failovers → mock success with `failuresPrevented=4`,
`developerVisibleFailures=0`; registry seeded with 7 models across providers.
