"""Receives Continuum's signed webhooks.

Continuum tells this app when a refund run finishes, fails or is cancelled.
Each delivery is signed: ``Continuum-Signature: t=<unix>,v1=<hex>``, where v1 is
HMAC-SHA256 of ``"<t>.<raw body>"`` keyed with the endpoint's secret. A
delivery that is retried carries the same Idempotency-Key, so a repeat is
recognised and not acted on twice.
"""
from __future__ import annotations

import hashlib
import hmac
import json
import threading
import time
from collections import deque
from datetime import datetime, timezone

TOLERANCE_SECONDS = 300

_lock = threading.Lock()
_events: deque = deque(maxlen=200)
_handled: set = set()


def verify(raw: bytes, header: str | None, secret: str) -> tuple[bool, str]:
    if not header:
        return False, "no Continuum-Signature header"
    parts = dict(p.split("=", 1) for p in header.split(",") if "=" in p)
    t, v1 = parts.get("t"), parts.get("v1")
    if not t or not v1:
        return False, "malformed signature header"
    try:
        age = abs(time.time() - int(t))
    except ValueError:
        return False, "bad timestamp"
    if age > TOLERANCE_SECONDS:
        return False, f"signature is {int(age)}s old: refused as a possible replay"
    expected = hmac.new(secret.encode(), f"{t}.".encode() + raw, hashlib.sha256).hexdigest()
    if not hmac.compare_digest(expected, v1):
        return False, "signature does not match"
    return True, "signature verified"


def receive(raw: bytes, headers, secret: str) -> tuple[int, dict]:
    sig = headers.get("Continuum-Signature")
    key = headers.get("Idempotency-Key")
    if secret:
        ok, why = verify(raw, sig, secret)
    else:
        ok, why = True, "not verified: no webhook_secret configured"
    try:
        event = json.loads(raw.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        event = {}
    data = event.get("data") if isinstance(event.get("data"), dict) else {}
    with _lock:
        duplicate = bool(key) and key in _handled
        if ok and key:
            _handled.add(key)
        _events.appendleft({
            "at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "type": event.get("type") or headers.get("Continuum-Event") or "unknown",
            "workflowId": data.get("workflowId"),
            "status": data.get("status"),
            "error": data.get("error"),
            "verified": ok and bool(secret),
            "check": why,
            "duplicate": duplicate,
            "idempotencyKey": key,
        })
    if not ok:
        # 401 tells Continuum the delivery was refused; it will retry, and a
        # forged one will keep being refused.
        return 401, {"error": why}
    return 200, {"received": True, "duplicate": duplicate}


def events() -> list:
    with _lock:
        return list(_events)
