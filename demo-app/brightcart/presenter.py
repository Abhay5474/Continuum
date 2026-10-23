"""The presenter's controls: Continuum console actions the feature tour needs.

The store itself only ever holds an API key, as a real application would. A
key cannot (and must not be able to) switch Continuum's features or arm a
chaos drill: that is the console's job, behind a person's sign-in. So the
tour asks whoever is presenting to sign in once; the session token is kept in
this process's memory, the password is used for that sign-in and dropped, and
nothing is written to disk.

Only the actions listed in ``ACTIONS`` can be run, so this is not a general
proxy to the console.
"""
from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request
from datetime import datetime, timezone
from urllib.parse import quote

from .continuum import LOG

FEATURE_NAMES = {
    "semantic-cache": "Semantic cache", "model-cascade": "Model cascade", "prompt-firewall": "Prompt firewall",
    "prompt-compression": "Prompt compression", "chat-context": "Context transformers",
    "quality-gate": "Quality gate", "semantic-breaker": "Semantic breaker",
    "verification-engine": "Verification engine", "context-optimizer": "Context optimizer",
    "adaptive-policy": "Adaptive policy",
}
AI_FAILURES = {"HALLUCINATION", "SCHEMA_CORRUPTION", "TOOL_CORRUPTION", "PROMPT_INJECTION", "CONTEXT_TRUNCATION",
               "MEMORY_CORRUPTION", "PROVIDER_DRIFT", "DEGRADED_ANSWER"}


class PresenterError(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


class Presenter:
    def __init__(self, base_url: str, api_key: str):
        self.base = base_url.rstrip("/")
        self.api_key = api_key
        self._lock = threading.Lock()
        self.token = None
        self.email = None
        self.operator_token = None
        self.operator_until = 0.0
        self.key_matches = None
        self.hedge_before = None

    # ---- session -----------------------------------------------------------

    def login(self, email: str, password: str) -> dict:
        email = (email or "").strip()
        if not email or not password:
            raise PresenterError(400, "Enter the email and password of your Continuum account.")
        login = self._call("POST", "/api/portal/developer/login", {"email": email, "password": password}, token=None)
        token = login.get("sessionToken")
        if not token:
            raise PresenterError(401, "Continuum did not accept that sign-in.")
        # The switches only matter if they belong to the account this store's
        # key was issued from; otherwise the tour would flip someone else's.
        keys = self._call("GET", "/api/portal/developer/keys", token=token) or []
        matches = any(isinstance(k, dict) and k.get("prefix") and self.api_key.startswith(k["prefix"])
                      for k in keys)
        operator_token, until = None, 0.0
        try:
            status = self._call("GET", "/api/portal/developer/operator/status", token=token) or {}
            if status.get("operator"):
                op = self._call("POST", "/api/portal/developer/operator/elevate", {"password": password}, token=token)
                operator_token = op.get("sessionToken")
                until = time.time() + 29 * 60
        except PresenterError:
            operator_token = None
        with self._lock:
            self.token, self.email, self.key_matches = token, email, matches
            self.operator_token, self.operator_until = operator_token, until
        self._note("Presenter signed in to the Continuum console")
        return self.status()

    def logout(self) -> dict:
        with self._lock:
            self.token = self.email = self.operator_token = None
            self.operator_until = 0.0
            self.key_matches = None
        return self.status()

    def status(self) -> dict:
        with self._lock:
            return {"signedIn": bool(self.token), "email": self.email, "keyMatches": self.key_matches,
                    "operator": bool(self.operator_token and time.time() < self.operator_until),
                    "operatorMinutesLeft": max(0, int((self.operator_until - time.time()) / 60))
                    if self.operator_token else 0}

    # ---- actions -----------------------------------------------------------

    def run(self, action: str, args: dict) -> dict:
        fn = ACTIONS.get(action)
        if fn is None:
            raise PresenterError(400, f"Unknown presenter action {action!r}.")
        if not self.token:
            raise PresenterError(401, "Sign in as the presenter first (Feature tour → Presenter sign-in).")
        return fn(self, args or {})

    def console(self, method: str, path: str, body=None, *, operator: bool = False, note: str | None = None):
        if operator:
            if not (self.operator_token and time.time() < self.operator_until):
                raise PresenterError(403, "This control is engine-wide, so only a Continuum operator can use it. "
                                          "Sign in with an operator account.")
            token = self.operator_token
        else:
            token = self.token
        out = self._call(method, path, body, token=token)
        if note:
            self._note(note)
        return out

    def _call(self, method: str, path: str, body=None, *, token):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "application/json")
        if token:
            req.add_header("Authorization", "Bearer " + token)
        try:
            with urllib.request.urlopen(req, timeout=60) as res:
                raw = res.read()
                return json.loads(raw) if raw else {}
        except urllib.error.HTTPError as e:
            raw = e.read().decode("utf-8", "replace")
            try:
                parsed = json.loads(raw)
                msg = parsed.get("message") or parsed.get("error") or raw
            except ValueError:
                msg = raw or f"HTTP {e.code}"
            if e.code == 401 and token == self.token and self.token:
                with self._lock:
                    self.token = None
                msg = "The presenter session ended. Sign in again."
            raise PresenterError(e.code, str(msg)[:300])
        except (urllib.error.URLError, OSError) as e:
            raise PresenterError(502, f"Could not reach Continuum at {self.base}: {getattr(e, 'reason', e)}")

    @staticmethod
    def _note(text: str) -> None:
        """A console action, shown in the X-ray's timeline but not counted as a feature."""
        LOG.add({"at": datetime.now(timezone.utc).isoformat(timespec="seconds"), "action": text,
                 "method": "CONSOLE", "path": "", "status": 200, "ms": 0, "request": None, "response": None,
                 "applied": [], "requestId": None, "features": [], "console": True})


# ---- the actions the tour may run --------------------------------------------

def _feature(p: Presenter, a: dict):
    key, on = str(a.get("key")), bool(a.get("on"))
    if key not in FEATURE_NAMES:
        raise PresenterError(400, f"Unknown feature switch {key!r}.")
    p.console("PUT", f"/api/portal/developer/features/{key}", {"enabled": on},
              note=f"{FEATURE_NAMES[key]} switched {'on' if on else 'off'}")
    return {"key": key, "on": on}


def _features(p: Presenter, a: dict):
    rows = p.console("GET", "/api/portal/developer/features") or []
    return {"features": {r["key"]: bool(r.get("on")) for r in rows if isinstance(r, dict) and r.get("switchable")}}


def _provider_down(p: Presenter, a: dict):
    on = bool(a.get("on", True))
    return p.console("POST", f"/api/chaos/provider-down?down={'true' if on else 'false'}",
                     note="Chaos: the primary AI provider is " + ("down" if on else "back"))


def _failure_rate(p: Presenter, a: dict):
    rate = max(0.0, min(1.0, float(a.get("rate", 1.0))))
    return p.console("POST", f"/api/chaos/provider-failure-rate?rate={rate}",
                     note=f"Chaos: {int(rate * 100)}% of provider calls fail")


def _chaos_reset(p: Presenter, a: dict):
    return p.console("POST", "/api/chaos/reset", note="Chaos switched off")


def _ai_chaos(p: Presenter, a: dict):
    kind = str(a.get("type", "")).upper()
    if kind not in AI_FAILURES:
        raise PresenterError(400, f"Unknown AI failure {kind!r}.")
    rate = max(0.0, min(1.0, float(a.get("rate", 1.0))))
    return p.console("POST", f"/api/ai-chaos/rate?type={kind}&rate={rate}",
                     note=f"AI chaos: {kind.replace('_', ' ').lower()} armed at {int(rate * 100)}%")


def _ai_chaos_reset(p: Presenter, a: dict):
    return p.console("POST", "/api/ai-chaos/reset", note="AI chaos switched off")


def _breaker(p: Presenter, a: dict):
    body = {k: a[k] for k in ("enabled", "warmup", "slack", "threshold", "cooldownSeconds") if k in a}
    return p.console("PUT", "/api/portal/developer/breaker/settings", body,
                     note="Semantic breaker settings changed")


def _breaker_clear(p: Presenter, a: dict):
    return p.console("DELETE", "/api/portal/developer/breaker", note="Semantic breaker reset")


def _breaker_status(p: Presenter, a: dict):
    return p.console("GET", "/api/portal/developer/breaker/status")


def _cost_limits(p: Presenter, a: dict):
    body = {k: a[k] for k in ("enabled", "requestsPerMin", "tokensPerMin") if k in a}
    out = p.console("PUT", "/api/portal/developer/cost-admission/settings", body,
                    note="Cost limits " + ("set to %s requests a minute" % body.get("requestsPerMin")
                                           if body.get("enabled") else "switched off"))
    if body.get("enabled") is False:
        p.console("POST", "/api/portal/developer/cost-admission/reset")
    return out


def _cost_status(p: Presenter, a: dict):
    return p.console("GET", "/api/portal/developer/cost-admission/status")


def _adaptive(p: Presenter, a: dict):
    on = bool(a.get("on"))
    return p.console("POST", f"/api/portal/developer/godmode/{'enable' if on else 'disable'}",
                     note="Adaptive policy " + ("on" if on else "off"))


def _adaptive_learn(p: Presenter, a: dict):
    content = str(a.get("content") or "").strip()[:500]
    if not content:
        raise PresenterError(400, "Nothing to teach.")
    return p.console("POST", "/api/portal/developer/godmode/memory/ingest",
                     {"sessionId": str(a.get("sessionId") or "brightcart-policy"), "role": "user",
                      "content": content}, note="Adaptive policy learned a rule")


def _provenance(p: Presenter, a: dict):
    rid = str(a.get("requestId") or "").strip()
    if not rid:
        raise PresenterError(400, "No request id.")
    out = p.console("GET", f"/api/portal/developer/provenance/requests/{quote(rid, safe='')}")
    if out.get("found"):
        LOG.mark("provenance", f"decision trail of {rid}")
    return out


def _billing(p: Presenter, a: dict):
    out = p.console("GET", "/api/portal/developer/billing")
    LOG.mark("billing", "plan and usage read")
    return out


def _traffic(p: Presenter, a: dict):
    admission = p.console("GET", "/api/portal/developer/admission/status")
    scheduling = p.console("GET", "/api/portal/developer/scheduling/status")
    if a.get("mark"):
        if admission.get("enabled") and any(x.get("admitted") for x in admission.get("providers") or []):
            LOG.mark("admission", "requests admitted under a concurrency limit")
        if scheduling.get("enabled") and any(x.get("ordered") for x in scheduling.get("providers") or []):
            LOG.mark("priority-deadlines", "requests ordered by priority and deadline")
    return {"admission": admission, "scheduling": scheduling}


def _hedging(p: Presenter, a: dict):
    on = bool(a.get("on"))
    if on:
        # A low trigger, so the race is visible on a fast provider. What was
        # set before is kept and put back when the tour switches it off.
        before = p.console("GET", "/api/hedging")
        if not getattr(p, "hedge_before", None):
            p.hedge_before = before
        p.console("POST", "/api/hedging/policy/adaptive?adaptive=false&thresholdMs=%d&minThresholdMs=1&maxHedges=1"
                  "&hedgeRateCap=1" % int(a.get("thresholdMs", 1)), operator=True)
        return p.console("POST", "/api/hedging/enable?enabled=true", operator=True, note="Hedging on (engine-wide)")
    before = getattr(p, "hedge_before", None) or {"adaptive": True, "thresholdMs": 800, "minThresholdMs": 50,
                                                   "maxHedges": 1, "hedgeRateCap": 0.05, "enabled": False}
    budget = before.get("perRequestBudgetUsd")
    p.console("POST", "/api/hedging/policy/adaptive?adaptive=%s&thresholdMs=%d&minThresholdMs=%d&maxHedges=%d"
                      "&hedgeRateCap=%s%s" % (str(bool(before.get("adaptive"))).lower(), int(before["thresholdMs"]),
                                              int(before["minThresholdMs"]), int(before["maxHedges"]),
                                              before["hedgeRateCap"],
                                              f"&budgetUsd={budget}" if isinstance(budget, (int, float)) else ""),
              operator=True)
    out = p.console("POST", f"/api/hedging/enable?enabled={'true' if before.get('enabled') else 'false'}",
                    operator=True, note="Hedging put back as it was")
    p.hedge_before = None
    return out


def _hedging_state(p: Presenter, a: dict):
    return p.console("GET", "/api/hedging")


ACTIONS = {
    "feature": _feature, "features": _features,
    "provider-down": _provider_down, "failure-rate": _failure_rate, "chaos-reset": _chaos_reset,
    "ai-chaos": _ai_chaos, "ai-chaos-reset": _ai_chaos_reset,
    "breaker": _breaker, "breaker-clear": _breaker_clear, "breaker-status": _breaker_status,
    "cost-limits": _cost_limits, "cost-status": _cost_status,
    "adaptive": _adaptive, "adaptive-learn": _adaptive_learn,
    "provenance": _provenance, "billing": _billing, "traffic": _traffic,
    "hedging": _hedging, "hedging-state": _hedging_state,
}
