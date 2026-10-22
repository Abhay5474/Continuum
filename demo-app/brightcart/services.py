"""The store's back-office services, which Continuum's refund workflow calls.

These are what "your own systems" look like to Continuum: plain HTTP endpoints.
Continuum calls them as workflow steps, retries them when they fail, undoes
them when a later step fails, and sends every call with an Idempotency-Key —
which these services honour, so a retried call never refunds twice.

Each service has a fault switch so the demo can show what Continuum does when
a real dependency misbehaves.
"""
from __future__ import annotations

import threading
import uuid
from collections import deque
from datetime import datetime, timezone

from . import store

# service → mode. Changed from the page (Refunds → back-office faults).
FAULT_MODES = {
    "payments": ["ok", "lost-response"],
    "courier": ["ok", "flaky", "down"],
    "email": ["ok", "down"],
}
FAULT_HELP = {
    ("payments", "lost-response"): "The refund goes through but the reply is lost (HTTP 500). Continuum retries "
                                   "with the same Idempotency-Key, so the card is refunded once.",
    ("courier", "flaky"): "The first two pickup calls fail (HTTP 503), then it works. Continuum retries with backoff.",
    ("courier", "down"): "Every pickup call fails. Continuum gives up after its retries and, with saga rollback on, "
                         "undoes the refund and the restock in reverse order.",
    ("email", "down"): "The email service fails every call. The refund stops at the email step and, with saga "
                       "rollback on, the refund and restock are undone.",
}

_lock = threading.Lock()
_faults = {name: "ok" for name in FAULT_MODES}
_ledger: deque = deque(maxlen=500)
_seen: dict = {}          # (endpoint, idempotency key) → stored response
_attempts: dict = {}      # (endpoint, idempotency key) → attempts so far


def faults() -> dict:
    with _lock:
        return dict(_faults)


def set_fault(service: str, mode: str) -> dict:
    if service not in FAULT_MODES or mode not in FAULT_MODES[service]:
        raise ValueError(f"Unknown fault {service}={mode}")
    with _lock:
        _faults[service] = mode
        return dict(_faults)


def ledger(order_id: str | None = None) -> list:
    with _lock:
        rows = list(_ledger)
    return [r for r in rows if order_id is None or r.get("orderId") == order_id]


def _log(endpoint: str, key: str | None, status: int, order_id: str | None, note: str, duplicate=False):
    with _lock:
        _ledger.append({
            "at": datetime.now(timezone.utc).isoformat(timespec="milliseconds"),
            "endpoint": endpoint, "idempotencyKey": key, "status": status, "orderId": order_id,
            "note": note, "duplicate": duplicate,
        })


def handle(endpoint: str, body: dict, idem_key: str | None) -> tuple[int, dict]:
    """Serves one back-office call; returns (status, body)."""
    body = body if isinstance(body, dict) else {}
    order_id = str(body.get("orderId") or "").upper() or None
    handler = _HANDLERS.get(endpoint)
    if handler is None:
        return 404, {"error": "no such service"}

    # Exactly-once on our side: a repeated key gets the first answer back and
    # nothing happens twice. Continuum sends the same key on every retry.
    if idem_key:
        with _lock:
            remembered = _seen.get((endpoint, idem_key))
        if remembered is not None:
            _log(endpoint, idem_key, 200, order_id, "repeat of an earlier call: answered from the first result, "
                                                   "nothing done twice", duplicate=True)
            return 200, remembered

    with _lock:
        attempt = _attempts.get((endpoint, idem_key), 0) + 1
        _attempts[(endpoint, idem_key)] = attempt
    status, out, note, commit = handler(body, order_id, attempt)
    if status < 400 and idem_key:
        with _lock:
            _seen[(endpoint, idem_key)] = out
    if commit is not None and idem_key:
        # The work happened even though the reply will be an error: remember it.
        with _lock:
            _seen[(endpoint, idem_key)] = commit
    _log(endpoint, idem_key, status, order_id, note)
    return status, out


# ---- handlers: (body, order id, attempt) → (status, body, note, committed) --

def _verify(body, oid, attempt):
    o = store.order(oid) if oid else None
    if not o:
        return 404, {"error": f"No order {oid}"}, f"order {oid} not found", None
    if not o["returnable"]:
        return 422, {"error": "not_returnable", "reason": o["returnable_reason"]}, \
            f"refused: {o['returnable_reason']}", None
    cust = store.customer(o["customer"]) or {}
    out = {"orderId": o["id"], "sku": o["sku"], "qty": o["qty"], "amount": o["amount"], "item": o["item"],
           "customerEmail": cust.get("email"), "customerName": cust.get("name"), "tier": cust.get("tier"),
           "card": o["card"]}
    return 200, out, f"order verified: {o['item']}, {o['amount']:.2f}", None


def _refund(body, oid, attempt):
    amount = float(body.get("amount") or 0)
    refund = {"refundId": "rf_" + uuid.uuid4().hex[:10], "orderId": oid, "amount": amount, "status": "refunded"}
    store.set_refund(oid, refund)
    if faults()["payments"] == "lost-response" and attempt == 1:
        return 500, {"error": "gateway timeout"}, \
            f"refunded {amount:.2f} to the card, then the reply was lost (HTTP 500)", refund
    return 200, refund, f"refunded {amount:.2f} to the card ({refund['refundId']})", None


def _reverse(body, oid, attempt):
    o = store.order(oid)
    if o and o.get("refund"):
        r = dict(o["refund"], status="reversed")
        store.set_refund(oid, r)
        return 200, {"reversed": True, "refundId": r["refundId"]}, "compensation: refund reversed", None
    return 200, {"reversed": False}, "compensation: nothing to reverse", None


def _restock(body, oid, attempt):
    sku, qty = str(body.get("sku")), int(body.get("qty") or 1)
    left = store.adjust_stock(sku, qty)
    return 200, {"sku": sku, "stock": left}, f"restocked {qty} × {sku} (now {left})", None


def _release(body, oid, attempt):
    sku, qty = str(body.get("sku")), int(body.get("qty") or 1)
    left = store.adjust_stock(sku, -qty)
    return 200, {"sku": sku, "stock": left}, f"compensation: restock of {sku} undone (now {left})", None


def _email(body, oid, attempt):
    if faults()["email"] == "down":
        return 503, {"error": "mail service unavailable"}, "email service down (HTTP 503)", None
    to = body.get("to") or "customer"
    return 200, {"sent": True, "to": to, "template": body.get("template")}, \
        f"emailed {to}: “{body.get('template', 'refund-update')}”", None


def _pickup(body, oid, attempt):
    mode = faults()["courier"]
    if mode == "down" or (mode == "flaky" and attempt <= 2):
        return 503, {"error": "courier API unavailable"}, f"courier unavailable (HTTP 503, attempt {attempt})", None
    ref = "pk_" + uuid.uuid4().hex[:8]
    return 200, {"pickupId": ref, "window": "tomorrow 10:00-14:00"}, f"courier pickup booked ({ref})", None


def _crm(body, oid, attempt):
    return 200, {"noted": True}, f"CRM note added: {str(body.get('note', ''))[:80]}", None


def _complete(body, oid, attempt):
    return 200, {"ok": True}, "workflow onComplete callback received", None


_HANDLERS = {
    "orders/verify": _verify,
    "payments/refund": _refund,
    "payments/reverse": _reverse,
    "inventory/restock": _restock,
    "inventory/release": _release,
    "email/send": _email,
    "courier/pickup": _pickup,
    "crm/note": _crm,
    "hooks/refund-complete": _complete,
}


def workflow_spec(public_url: str) -> dict:
    """The refund workflow, as published to Continuum by setup_continuum.py."""
    svc = public_url.rstrip("/") + "/svc/"

    def call(path, body):
        return {"method": "POST", "url": svc + path, "body": body}

    return {
        "description": "Brightcart refund: verify the order, hold briefly for fraud checks, refund the card and "
                       "restock in parallel, email the customer, book a courier pickup, note it in the CRM.",
        "steps": [
            # A lookup: a GET, so a rollback knows it left nothing behind to undo.
            {"id": "verify", "call": {"method": "GET", "url": svc + "orders/verify?orderId=${input.orderId}"},
             "retries": 2, "timeoutSeconds": 10},
            {"id": "fraud-hold", "type": "WAIT", "waitSeconds": 6, "dependsOn": ["verify"]},
            {"id": "refund", "dependsOn": ["fraud-hold"],
             "call": call("payments/refund", {"orderId": "${input.orderId}", "amount": "${steps.verify.amount}",
                                              "reason": "${input.reason}"}),
             "compensate": call("payments/reverse", {"orderId": "${input.orderId}"}),
             "retries": 3, "timeoutSeconds": 10},
            {"id": "restock", "dependsOn": ["fraud-hold"],
             "call": call("inventory/restock", {"orderId": "${input.orderId}", "sku": "${steps.verify.sku}",
                                                "qty": "${steps.verify.qty}"}),
             "compensate": call("inventory/release", {"orderId": "${input.orderId}", "sku": "${steps.verify.sku}",
                                                      "qty": "${steps.verify.qty}"}),
             "retries": 3, "timeoutSeconds": 10},
            {"id": "notify", "dependsOn": ["refund", "restock"],
             "call": call("email/send", {"orderId": "${input.orderId}", "to": "${steps.verify.customerEmail}",
                                         "template": "Your refund is on its way"}),
             "retries": 2, "timeoutSeconds": 10},
            {"id": "pickup", "dependsOn": ["notify"], "condition": "${input.collectItem} == true",
             "call": call("courier/pickup", {"orderId": "${input.orderId}"}),
             "retries": 2, "timeoutSeconds": 10},
            {"id": "crm", "dependsOn": ["pickup"],
             "call": call("crm/note", {"orderId": "${input.orderId}",
                                       "note": "Refund processed by the Brightcart assistant: ${input.reason}"}),
             "retries": 3, "timeoutSeconds": 10},
        ],
        "onComplete": call("hooks/refund-complete", {"orderId": "${input.orderId}"}),
    }
