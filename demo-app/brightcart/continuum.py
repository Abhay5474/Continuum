"""A small Continuum client, and the record of every call it makes.

Only the standard library: an application does not need an SDK to use
Continuum. Chat goes to the OpenAI-compatible endpoint, so any OpenAI client
library would work just as well; everything else is plain JSON over HTTP.

Every call is recorded (key masked, file contents shortened) so the page can
show what was sent to Continuum and what Continuum did with it.
"""
from __future__ import annotations

import json
import threading
import time
import urllib.error
import urllib.request
import uuid
from collections import deque
from datetime import datetime, timezone

# ---------------------------------------------------------------------------
# What the page shows about Continuum's features. One entry per feature, in
# the order the coverage panel lists them.
# ---------------------------------------------------------------------------
FEATURES = [
    # key, name, how this app triggers it, where it shows in Continuum's console
    ("gateway", "AI gateway", "Any chat message", "Gateway → request feed"),
    ("streaming", "Streaming", "Chat with “Stream” on", "Gateway"),
    ("tools", "Tool calling", "Ask about an order in chat", "Gateway"),
    ("json-mode", "JSON mode", "Triage a ticket", "Gateway"),
    ("vision", "Vision", "Ask about a photo", "Gateway"),
    ("routing-modes", "Routing modes", "Pick a mode in chat, or draft a careful reply", "Routing"),
    ("failover", "Provider failover", "Console Chaos Lab: fail the primary provider, then chat", "Gateway, Chaos Lab"),
    ("model-catalogue", "Model catalogue", "Chat → Models", "Models"),
    ("prompt-firewall", "Prompt firewall", "Send a card number or PIN; triage T-503 or T-504", "Prompt Guard"),
    ("prompt-compression", "Prompt compression", "Console: turn Prompt Compression on, then triage T-505 (a pasted log)", "Prompt Guard"),
    ("context-transformers", "Context transformers", "Data files → Transform, or ask about a file", "Context"),
    ("semantic-cache", "Semantic cache", "Live chat → Help centre: ask a question twice, or reworded", "Semantic Cache"),
    ("context-optimizer", "Context optimizer", "Keep one chat going for a while: older turns get paged out", "Context Optimizer"),
    ("memory", "Long-term memory", "Tell the assistant a preference", "Memory"),
    ("adaptive-policy", "Adaptive policy", "Console: turn Adaptive Policy on, then chat", "Adaptive Policy"),
    ("model-cascade", "Model cascade", "Any chat message (setup turns the cascade on)", "Model Cascade"),
    ("quality-gate", "Quality gate", "Any chat message (setup turns the gate on, monitoring)", "Answer Assurance → Quality gate"),
    ("confidence", "Confidence", "Draft a careful reply", "Answer Assurance → Confidence"),
    ("verification-engine", "Verification engine", "Console: turn the Verification Engine on, then chat", "Verification Engine"),
    ("semantic-breaker", "Semantic breaker", "Watches every answer; trips when a model degrades (try AI Chaos Lab)", "Answer Assurance → Breaker"),
    ("hedging", "Hedging", "Operator console: turn hedging on, then chat", "Routing → Dispatch"),
    ("degradation", "Degradation ladder", "Console Chaos Lab: fail every provider, then chat", "Decision Provenance"),
    ("admission", "Admission control", "Triage all tickets at once (bulk)", "Traffic Control → Admission"),
    ("priority-deadlines", "Priority & deadlines", "Urgent tickets carry a deadline", "Traffic Control → Priority"),
    ("cost-limits", "Cost limits", "Console: set a tight cost limit, then Triage all", "Traffic Control → Cost limits"),
    ("workflows", "Durable workflows", "Refunds → Start refund", "Workflows"),
    ("saga", "Saga compensation", "Refund with the courier down, or cancel during the hold", "Workflows → Compensation"),
    ("webhooks", "Signed webhooks", "Any refund finishing", "Developer portal → Webhooks"),
    ("exactly-once", "Exactly-once steps", "Refund with payments losing responses", "Workflows → run page"),
    ("loop-detection", "Loop detection", "Chat → Loop drill, or an agent repeating itself", "Loop Detection"),
    ("pipelines", "Pipelines", "Receipts → Check a receipt", "Pipelines"),
    ("specialists", "Specialists", "Receipts → Check a receipt (this app's own reader)", "Specialists"),
    ("autopilot", "Autopilot feedback", "Rate an answer 👍 / 👎", "Routing → Optimization"),
    ("ai-chaos", "AI chaos", "Console AI Chaos Lab: arm an experiment, then chat", "AI Chaos Lab"),
    ("provenance", "Decision provenance", "Any answer: quote its request id", "Decision Provenance"),
    ("billing", "Plans & quota", "Every request counts against the plan", "Billing"),
]
FEATURE_NAMES = {k: n for k, n, _, _ in FEATURES}

# Applied-line prefixes (Continuum's X-Continuum-Applied) → feature key.
_APPLIED_KEYS = {
    "prompt-firewall": "prompt-firewall", "context-transformers": "context-transformers",
    "context-optimizer": "context-optimizer", "prompt-compression": "prompt-compression",
    "adaptive-policy": "adaptive-policy", "semantic-cache": "semantic-cache", "model-cascade": "model-cascade",
    "hedging": "hedging", "quality-gate": "quality-gate", "confidence": "confidence",
    "verification-engine": "verification-engine", "semantic-breaker": "semantic-breaker",
    "failover": "failover", "ai-chaos": "ai-chaos", "degradation-ladder": "degradation", "autopilot": "autopilot",
}


class CallLog:
    """The last few hundred calls to Continuum, for the inspector."""

    def __init__(self, size: int = 400):
        self._calls: deque = deque(maxlen=size)
        self._seen: dict = {}
        self._next = 1
        self._lock = threading.Lock()

    def add(self, call: dict) -> dict:
        with self._lock:
            call["id"] = self._next
            self._next += 1
            self._calls.append(call)
            for f in call.get("features", []):
                self._seen[f["key"]] = self._seen.get(f["key"], 0) + 1
            return call

    def mark(self, key: str, detail: str = "") -> None:
        """A feature seen outside a call to Continuum (a verified webhook, a suppressed duplicate)."""
        with self._lock:
            self._seen[key] = self._seen.get(key, 0) + 1

    def since(self, after: int) -> list:
        with self._lock:
            return [c for c in self._calls if c["id"] > after]

    def seen(self) -> dict:
        with self._lock:
            return dict(self._seen)

    def clear(self) -> None:
        with self._lock:
            self._calls.clear()
            self._seen.clear()


LOG = CallLog()

# The calls made while serving one browser request, so that request's answer
# can say which Continuum calls it took.
_collect = threading.local()


def start_collecting() -> None:
    _collect.calls = []


def collected() -> list:
    calls = getattr(_collect, "calls", None) or []
    _collect.calls = []
    return calls


class ContinuumError(Exception):
    """Continuum answered with an error, or could not be reached."""

    def __init__(self, status: int, code: str, message: str, body=None):
        super().__init__(message)
        self.status = status
        self.code = code
        self.message = message
        self.body = body

    def as_dict(self) -> dict:
        return {"status": self.status, "error": self.code, "message": self.message}


class Continuum:
    def __init__(self, base_url: str, api_key: str, timeout: float = 120):
        self.base = base_url.rstrip("/")
        self.key = api_key
        self.timeout = timeout

    # ---- transport ---------------------------------------------------------

    def _request(self, method: str, path: str, body=None, *, action: str, raw: bytes | None = None,
                 content_type: str = "application/json", record_body=None):
        if not self.key:
            raise ContinuumError(0, "no_api_key",
                                 "No Continuum API key is configured. Run setup_continuum.py or add api_key "
                                 "to demo.config.json.")
        data = raw if raw is not None else (None if body is None else json.dumps(body).encode("utf-8"))
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Authorization", "Bearer " + self.key)
        if data is not None:
            req.add_header("Content-Type", content_type)
        req.add_header("Accept", "application/json")
        started = time.monotonic()
        status, headers, payload = 0, {}, None
        try:
            with urllib.request.urlopen(req, timeout=self.timeout) as res:
                status = res.status
                headers = dict(res.headers.items())
                payload = res.read()
        except urllib.error.HTTPError as e:
            status = e.code
            headers = dict(e.headers.items()) if e.headers else {}
            payload = e.read()
        except (urllib.error.URLError, OSError) as e:
            ms = int((time.monotonic() - started) * 1000)
            reason = getattr(e, "reason", e)
            self._record(action, method, path, record_body if record_body is not None else body, 0, ms,
                         None, {}, error=f"Could not reach Continuum at {self.base}: {reason}")
            raise ContinuumError(0, "unreachable", f"Could not reach Continuum at {self.base} ({reason}). "
                                                   "Is it running?")
        ms = int((time.monotonic() - started) * 1000)
        parsed = _parse(payload)
        self._record(action, method, path, record_body if record_body is not None else body, status, ms, parsed,
                     headers)
        if status >= 400:
            raise _error(status, parsed)
        return parsed, headers

    def _record(self, action, method, path, body, status, ms, parsed, headers, error=None):
        applied = _applied(parsed, headers)
        call = {
            "at": datetime.now(timezone.utc).isoformat(timespec="seconds"),
            "action": action,
            "method": method,
            "path": path,
            "status": status,
            "ms": ms,
            "request": _shorten(body),
            "response": _shorten(parsed),
            "applied": applied,
            "requestId": headers.get("X-Continuum-Request-Id") or _request_id(parsed),
            "features": detect(path, body, status, parsed, applied),
        }
        if error:
            call["error"] = error
        LOG.add(call)
        if hasattr(_collect, "calls") and _collect.calls is not None:
            _collect.calls.append(call)
        return call

    # ---- chat --------------------------------------------------------------

    def chat(self, messages: list, *, action: str, tools=None, json_mode=False, routing_mode=None,
             measure=False, criticality=None, deadline_ms=None, max_tokens=None, temperature=None):
        body = {"model": "auto", "messages": messages}
        if tools:
            body["tools"] = tools
            body["tool_choice"] = "auto"
        if json_mode:
            body["response_format"] = {"type": "json_object"}
        if routing_mode:
            body["continuum_routing_mode"] = routing_mode
        if measure:
            body["continuum_measure_uncertainty"] = True
        if criticality:
            body["continuum_criticality"] = criticality
        if deadline_ms:
            body["continuum_deadline_ms"] = int(deadline_ms)
        if max_tokens:
            body["max_tokens"] = int(max_tokens)
        if temperature is not None:
            body["temperature"] = temperature
        parsed, _ = self._request("POST", "/v1/chat/completions", body, action=action)
        return parsed

    def chat_stream(self, messages: list, *, action: str, routing_mode=None, on_delta=None):
        """Streams an answer, handing each piece to ``on_delta``; returns the final metadata."""
        if not self.key:
            raise ContinuumError(0, "no_api_key", "No Continuum API key is configured.")
        body = {"model": "auto", "messages": messages, "stream": True,
                "stream_options": {"include_usage": True}}
        if routing_mode:
            body["continuum_routing_mode"] = routing_mode
        req = urllib.request.Request(self.base + "/v1/chat/completions", data=json.dumps(body).encode(), method="POST")
        req.add_header("Authorization", "Bearer " + self.key)
        req.add_header("Content-Type", "application/json")
        req.add_header("Accept", "text/event-stream")
        started = time.monotonic()
        text, meta, model, first_ms, headers = [], None, None, None, {}
        try:
            res = urllib.request.urlopen(req, timeout=self.timeout)
        except urllib.error.HTTPError as e:
            parsed = _parse(e.read())
            self._record(action, "POST", "/v1/chat/completions", body, e.code,
                         int((time.monotonic() - started) * 1000), parsed, dict(e.headers.items()))
            raise _error(e.code, parsed)
        except (urllib.error.URLError, OSError) as e:
            self._record(action, "POST", "/v1/chat/completions", body, 0, 0, None, {},
                         error=f"Could not reach Continuum: {getattr(e, 'reason', e)}")
            raise ContinuumError(0, "unreachable", f"Could not reach Continuum at {self.base}.")
        stream_error = None
        with res:
            headers = dict(res.headers.items())
            for raw_line in res:
                line = raw_line.decode("utf-8", "replace").strip()
                if not line.startswith("data:"):
                    continue
                data = line[5:].strip()
                if data == "[DONE]":
                    break
                try:
                    chunk = json.loads(data)
                except ValueError:
                    continue
                if "error" in chunk:
                    stream_error = chunk["error"]
                    break
                model = chunk.get("model") or model
                for choice in chunk.get("choices", []):
                    piece = (choice.get("delta") or {}).get("content")
                    if piece:
                        if first_ms is None:
                            first_ms = int((time.monotonic() - started) * 1000)
                        text.append(piece)
                        if on_delta:
                            on_delta(piece)
                if chunk.get("continuum"):
                    meta = chunk["continuum"]
        ms = int((time.monotonic() - started) * 1000)
        summary = {"streamed": True, "model": model, "first_token_ms": first_ms, "content": "".join(text),
                   "continuum": meta}
        if stream_error:
            summary["error"] = stream_error
        self._record(action, "POST", "/v1/chat/completions", body, 200, ms, summary, headers)
        if stream_error:
            raise ContinuumError(200, stream_error.get("code") or stream_error.get("type") or "stream_error",
                                 stream_error.get("message") or "The stream failed.", stream_error)
        return summary

    def models(self, *, action: str):
        parsed, _ = self._request("GET", "/v1/models", action=action)
        return parsed

    # ---- pipelines and context --------------------------------------------

    def pipeline_upload(self, name: str, filename: str, content: bytes, content_type: str, prompt: str, *,
                        action: str):
        raw, ctype = _multipart({"prompt": prompt}, "file", filename, content, content_type)
        parsed, _ = self._request("POST", f"/api/gateway/pipeline/{_q(name)}/upload", action=action, raw=raw,
                                  content_type=ctype,
                                  record_body={"file": f"{filename} ({len(content):,} bytes)", "prompt": prompt})
        return parsed

    def transform(self, filename: str, content: bytes, content_type: str, budget: str, *, action: str):
        raw, ctype = _multipart({"budget": budget}, "file", filename, content, content_type)
        parsed, _ = self._request("POST", "/api/gateway/context/transform?budget=" + _q(budget), action=action,
                                  raw=raw, content_type=ctype,
                                  record_body={"file": f"{filename} ({len(content):,} bytes)", "budget": budget})
        return parsed

    # ---- the program API: workflows, memory, loops, feedback --------------

    def run_workflow(self, name: str, inputs: dict, workflow_id: str | None, *, action: str):
        body = {"input": inputs}
        if workflow_id:
            body["workflowId"] = workflow_id
        parsed, _ = self._request("POST", f"/api/gateway/workflows/{_q(name)}/run", body, action=action)
        return parsed

    def workflow(self, workflow_id: str, *, action: str, quiet: bool = False):
        if quiet:
            # Polling for a timeline would bury every other call in the
            # inspector, so polls are not recorded.
            return self._quiet_get(f"/api/gateway/workflows/{_q(workflow_id)}")
        parsed, _ = self._request("GET", f"/api/gateway/workflows/{_q(workflow_id)}", action=action)
        return parsed

    def cancel_workflow(self, workflow_id: str, reason: str, *, action: str):
        parsed, _ = self._request("POST", f"/api/gateway/workflows/{_q(workflow_id)}/cancel", {"reason": reason},
                                  action=action)
        return parsed

    def remember(self, scope: str, content: str, *, action: str, tier: str = "LONG_TERM", salience: float = 0.8):
        parsed, _ = self._request("POST", "/api/gateway/memory/store",
                                  {"scope": scope, "content": content, "tier": tier, "salience": salience},
                                  action=action)
        return parsed

    def recall(self, scope: str, query: str, *, action: str, top_k: int = 4):
        parsed, _ = self._request("POST", "/api/gateway/memory/retrieve",
                                  {"scope": scope, "query": query, "topK": top_k}, action=action)
        return parsed or []

    def inspect_loop(self, run_id: str, steps: list, progress: list | None, *, action: str):
        """The loop detector's verdict. In HALT mode a loop is a 409, returned here as a verdict."""
        body = {"workflowId": run_id, "steps": steps}
        if progress is not None:
            body["progress"] = progress
        try:
            parsed, _ = self._request("POST", "/api/gateway/loops/inspect", body, action=action)
            return parsed
        except ContinuumError as e:
            if e.status == 409 and isinstance(e.body, dict):
                return e.body
            raise

    def feedback(self, request_ref: str, score: float, comment: str | None, *, action: str):
        parsed, _ = self._request("POST", "/api/gateway/feedback",
                                  {"requestRef": request_ref, "score": score, "comment": comment}, action=action)
        return parsed

    def _quiet_get(self, path: str):
        req = urllib.request.Request(self.base + path, method="GET")
        req.add_header("Authorization", "Bearer " + self.key)
        try:
            with urllib.request.urlopen(req, timeout=20) as res:
                return _parse(res.read())
        except urllib.error.HTTPError as e:
            raise _error(e.code, _parse(e.read()))
        except (urllib.error.URLError, OSError) as e:
            raise ContinuumError(0, "unreachable", f"Could not reach Continuum ({getattr(e, 'reason', e)}).")

    def reachable(self) -> bool:
        try:
            with urllib.request.urlopen(self.base + "/api/meta", timeout=3) as res:
                return res.status == 200
        except (urllib.error.URLError, OSError, ValueError):
            return False


# ---------------------------------------------------------------------------
# Helpers
# ---------------------------------------------------------------------------

def _q(s: str) -> str:
    return urllib.request.quote(str(s), safe="")


def _parse(payload: bytes | None):
    if not payload:
        return None
    try:
        return json.loads(payload.decode("utf-8"))
    except (ValueError, UnicodeDecodeError):
        return {"raw": payload[:2000].decode("utf-8", "replace")}


def _error(status: int, parsed) -> ContinuumError:
    code, message = "error", f"Continuum answered {status}."
    if isinstance(parsed, dict):
        err = parsed.get("error")
        if isinstance(err, dict):  # OpenAI shape
            code = err.get("code") or err.get("type") or code
            message = err.get("message") or message
        else:
            code = err or code
            message = parsed.get("message") or message
    return ContinuumError(status, str(code), str(message), parsed)


def _applied(parsed, headers) -> list:
    if isinstance(parsed, dict):
        meta = parsed.get("continuum")
        if isinstance(meta, dict) and isinstance(meta.get("applied"), list):
            return meta["applied"]
        if isinstance(parsed.get("applied"), list):
            return parsed["applied"]
    header = headers.get("X-Continuum-Applied") if headers else None
    return [p.strip() for p in header.split(";") if p.strip()] if header else []


def _request_id(parsed):
    if isinstance(parsed, dict):
        meta = parsed.get("continuum")
        if isinstance(meta, dict) and meta.get("request_id"):
            return meta["request_id"]
        return parsed.get("requestId")
    return None


def _shorten(v, depth: int = 0):
    """A copy safe to show and store: long strings clipped, base64 summarised."""
    if depth > 8:
        return "…"
    if isinstance(v, dict):
        return {k: _shorten(x, depth + 1) for k, x in v.items()}
    if isinstance(v, list):
        out = [_shorten(x, depth + 1) for x in v[:40]]
        if len(v) > 40:
            out.append(f"… {len(v) - 40} more")
        return out
    if isinstance(v, str):
        if v.startswith("data:") and ";base64," in v[:80]:
            return v[:v.index(",") + 1] + f"<{len(v):,} characters of base64>"
        if len(v) > 4000:
            return v[:4000] + f"… ({len(v):,} characters)"
    return v


def detect(path: str, body, status: int, parsed, applied: list) -> list:
    """Which Continuum features this call shows, as {key, label, detail}."""
    found: dict = {}

    def add(key, detail=""):
        if key not in found:
            found[key] = {"key": key, "label": FEATURE_NAMES.get(key, key), "detail": detail}

    body = body if isinstance(body, dict) else {}
    meta = parsed.get("continuum") if isinstance(parsed, dict) and isinstance(parsed.get("continuum"), dict) else {}
    err = parsed.get("error") if isinstance(parsed, dict) else None
    code = (err.get("code") if isinstance(err, dict) else err) or ""

    if path.startswith("/v1/chat/completions") or path.startswith("/api/gateway/chat"):
        add("gateway", "OpenAI-compatible chat")
        add("billing", "counted against the plan")
        if body.get("stream"):
            add("streaming", "first token after %s ms" % (parsed or {}).get("first_token_ms") if isinstance(parsed, dict) else "")
        if body.get("tools"):
            calls = _tool_calls(parsed)
            add("tools", ("asked to run " + ", ".join(calls)) if calls else "tools offered")
        if body.get("response_format"):
            add("json-mode", "answer constrained to JSON")
        if _has_image(body):
            add("vision", "image sent with the question")
        if body.get("continuum_routing_mode"):
            add("routing-modes", body["continuum_routing_mode"])
        if body.get("continuum_deadline_ms") or body.get("continuum_criticality"):
            add("priority-deadlines", "%s, deadline %s ms" % (body.get("continuum_criticality", "NORMAL"),
                                                             body.get("continuum_deadline_ms", "none")))
        if body.get("continuum_criticality"):
            add("admission", "criticality " + body["continuum_criticality"])
        rid = meta.get("request_id")
        if rid:
            add("provenance", rid)
        reason = str(meta.get("routing_reason") or "")
        if meta.get("failovers"):
            add("failover", f"{meta['failovers']} failover(s) absorbed")
        if meta.get("confidence") is not None:
            add("confidence", "confidence %.0f%%" % (float(meta["confidence"]) * 100))
        model = str((parsed or {}).get("model") or "") if isinstance(parsed, dict) else ""
        if model.startswith("degraded/"):
            add("degradation", model)
        if "semantic cache" in reason:
            add("semantic-cache", reason)
        if reason.startswith("cascade:"):
            add("model-cascade", reason)
        if "consensus DAG" in reason:
            add("verification-engine", reason.split(":")[0])
        if reason.startswith("hedged"):
            add("hedging", reason)
        if " · quality" in reason:
            add("quality-gate", reason[reason.index(" · quality") + 3:])
    elif "/api/gateway/pipeline/" in path:
        add("pipelines", "receipt check pipeline")
        add("specialists", "this app's receipt reader")
    elif path.startswith("/api/gateway/context/transform"):
        add("context-transformers", "file rewritten for a model")
    elif path.startswith("/api/gateway/workflows/"):
        run = parsed if isinstance(parsed, dict) else {}
        if path.endswith("/cancel") and run.get("rollbackWorkflowId"):
            add("saga", "cancelled; a rollback run undoes the completed steps")
        if isinstance(run.get("rollback"), dict):
            add("saga", str(run["rollback"].get("summary") or "completed steps undone"))
        retried = [s.get("step") for s in run.get("steps") or [] if isinstance(s, dict) and (s.get("attempts") or 0) > 1]
        add("workflows", ("retried: " + ", ".join(map(str, retried))) if retried else "durable run")
    elif path.startswith("/api/gateway/memory/"):
        add("memory", "stored" if path.endswith("/store") else "recalled by meaning")
    elif path.startswith("/api/gateway/loops/"):
        looping = isinstance(parsed, dict) and parsed.get("looping")
        add("loop-detection", "loop detected" if looping else "no loop")
    elif path.startswith("/api/gateway/feedback"):
        add("autopilot", "answer rated")
    elif path.startswith("/v1/models"):
        add("model-catalogue", "%d models" % len((parsed or {}).get("data", [])) if isinstance(parsed, dict) else "")

    for line in applied or []:
        prefix = line.split(":", 1)[0].strip()
        key = _APPLIED_KEYS.get(prefix)
        if key:
            found.pop(key, None)  # the applied line is the better description
            add(key, line.split(":", 1)[1].strip() if ":" in line else line)

    if status == 403 and code == "request_blocked":
        add("prompt-firewall", "request blocked before any model saw it")
    elif status == 429 and code == "capacity":
        add("admission", "shed: provider at capacity")
    elif status == 429 and code == "cost_limited":
        add("cost-limits", "over the token/request allowance")
    elif status == 422 and code == "deadline_unreachable":
        add("priority-deadlines", "refused: the deadline could not be met")
    elif status == 402:
        add("billing", "monthly quota exhausted")
    return list(found.values())


def _tool_calls(parsed) -> list:
    try:
        calls = parsed["choices"][0]["message"].get("tool_calls") or []
        return [c["function"]["name"] for c in calls]
    except (KeyError, IndexError, TypeError):
        return []


def _has_image(body: dict) -> bool:
    for m in body.get("messages", []):
        if isinstance(m.get("content"), list) and any(p.get("type") == "image_url" for p in m["content"]):
            return True
    return False


def _multipart(fields: dict, file_field: str, filename: str, content: bytes, content_type: str):
    boundary = "----brightcart" + uuid.uuid4().hex
    out = bytearray()
    for name, value in fields.items():
        if value is None:
            continue
        out += f"--{boundary}\r\nContent-Disposition: form-data; name=\"{name}\"\r\n\r\n".encode()
        out += str(value).encode("utf-8") + b"\r\n"
    safe_name = filename.replace('"', "").replace("\r", "").replace("\n", "") or "upload"
    out += (f"--{boundary}\r\nContent-Disposition: form-data; name=\"{file_field}\"; filename=\"{safe_name}\"\r\n"
            f"Content-Type: {content_type or 'application/octet-stream'}\r\n\r\n").encode()
    out += content + b"\r\n"
    out += f"--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"
