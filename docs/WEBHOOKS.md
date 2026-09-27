# Webhooks

Your server is told when one of your runs finishes. Each event is sent once
per endpoint and signed, so your server can trust it and ignore a repeat.

## Setting one up

**Developer portal → Webhooks**: paste a URL, choose the events, press *Add
endpoint*. The signing secret is shown once. Copy it into your server's
configuration. *Send test* queues a `ping` through the same path as a real
event, and *Deliveries* shows every attempt with the status code your server
returned.

API (developer session): `GET|POST /api/portal/developer/webhooks`,
`PUT|DELETE /api/portal/developer/webhooks/{id}`, `POST …/{id}/test`,
`POST …/{id}/rotate-secret`, `GET …/{id}/deliveries`.

## Events

| Event | When |
|---|---|
| `workflow.completed` | A run finished; `data.result` is its result |
| `workflow.failed` | A run failed; `data.error` says why |
| `workflow.cancelled` | Someone stopped a run |
| `workflow.stuck` | A run's decision kept failing and the engine stopped retrying it (see *Try again* on the run) |
| `ping` | You pressed *Send test* |

```json
{
  "id": "whk_1_workflow.completed_7f3c…",
  "type": "workflow.completed",
  "createdAt": "2026-09-27T06:43:00.339Z",
  "data": { "workflowId": "7f3c…", "workflowType": "DurableDemo", "status": "COMPLETED", "result": { … } }
}
```

## Why it is exactly once

The event is written to the transactional outbox **in the same database
transaction** as the change it reports. A run cannot complete without its
event being queued, and an event cannot be queued for a run that never
completed. The dispatcher then sends it:

- A 2xx answer is delivery.
- Anything else, or no answer, is retried with backoff, up to 8 attempts.
- Every retry of the same event carries the same `Idempotency-Key` (also the
  body's `id`). If your server remembers the keys it has handled, a repeat is
  harmless.

## Verifying a delivery

Headers: `Continuum-Signature: t=<unix seconds>,v1=<hex>`, `Idempotency-Key`,
and `Continuum-Event`. `v1` is HMAC-SHA256 of `"<t>.<raw body>"` keyed with the
endpoint's secret.

```js
import crypto from "node:crypto";

function verify(rawBody, header, secret) {
  const { t, v1 } = Object.fromEntries(header.split(",").map((p) => p.split("=")));
  if (Math.abs(Date.now() / 1000 - Number(t)) > 300) return false; // too old: a replay
  const expected = crypto.createHmac("sha256", secret).update(`${t}.${rawBody}`).digest("hex");
  return crypto.timingSafeEqual(Buffer.from(v1), Buffer.from(expected));
}
```

*New secret* replaces the secret, and the old one stops working at once.

## Limits and safety

- At most 10 endpoints per account.
- Endpoint URLs go through the same guard as every other address a customer
  chooses: http or https only, and no private or metadata addresses (unless
  `continuum.declarative.allow-private-targets` is set for local development).
  Redirects are not followed.
- An endpoint that is switched off or removed after an event was queued is not
  called. The event is settled, not retried.
- Delivery logs are kept 30 days (`continuum.retention.webhook-delivery-days`).

`WebhookIT` checks each of these rules on a real database with a real receiver.
