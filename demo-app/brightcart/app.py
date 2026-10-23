"""The Brightcart server: the page, its API, and the endpoints Continuum calls back.

    /                     the support desk page (web/)
    /app/...              the page's API; this is where the Continuum key is used
    /svc/...              the store's back-office services (refund workflow steps)
    /specialist/...       the store's receipt reader (a pipeline specialist)
    /webhooks/continuum   signed run notifications from Continuum

Standard library only, so ``python3 server.py`` is all it takes to run.
"""
from __future__ import annotations

import base64
import json
import mimetypes
import threading
import traceback
import uuid
from datetime import datetime, timezone
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from urllib.parse import parse_qs, urlparse

from . import config, services, specialist, store, webhooks
from .continuum import FEATURES, LOG, Continuum, ContinuumError, collected, start_collecting
from .presenter import Presenter, PresenterError
from .support import Assistant, memory_scope

WEB = Path(__file__).resolve().parent.parent / "web"
SAMPLES = Path(__file__).resolve().parent.parent / "samples"
MAX_BODY = 30 * 1024 * 1024


class Desk:
    """Everything the handlers share."""

    def __init__(self, cfg: dict):
        self.cfg = cfg
        self.continuum = Continuum(cfg["continuum_url"], cfg["api_key"])
        self.assistant = Assistant(self.continuum, self.start_refund)
        self.presenter = Presenter(cfg["continuum_url"], cfg["api_key"])
        self._refunds: list = []
        self._lock = threading.Lock()

    def start_refund(self, order_id: str, reason: str, collect_item: bool, source: str = "agent") -> dict:
        o = store.order(order_id)
        if not o:
            raise ValueError(f"No order {order_id}")
        # Our own id for the run: retrying this request joins the same run
        # instead of refunding twice.
        wf_id = f"refund-{o['id'].lower()}-{uuid.uuid4().hex[:6]}"
        run = self.continuum.run_workflow(self.cfg["workflow_name"],
                                          {"orderId": o["id"], "reason": reason, "collectItem": bool(collect_item)},
                                          wf_id, action=f"Start refund of {o['id']}")
        entry = {"workflowId": run.get("workflowId", wf_id), "orderId": o["id"], "item": o["item"],
                 "amount": o["amount"], "reason": reason, "collectItem": bool(collect_item), "source": source,
                 "startedAt": datetime.now(timezone.utc).isoformat(timespec="seconds"), "status": "RUNNING"}
        with self._lock:
            self._refunds.insert(0, entry)
        return entry

    def refunds(self) -> list:
        with self._lock:
            return [dict(r) for r in self._refunds]

    def refund(self, wf_id: str) -> dict | None:
        with self._lock:
            for r in self._refunds:
                if r["workflowId"] == wf_id:
                    return r
        return None

    def first_report(self, wf_id: str) -> bool:
        """True the first time a finished run is reported, so its outcome is logged once."""
        with self._lock:
            for r in self._refunds:
                if r["workflowId"] == wf_id and not r.get("reported"):
                    r["reported"] = True
                    return True
        return False

    def note_status(self, wf_id: str, status: str, rollback_id: str | None = None) -> None:
        with self._lock:
            for r in self._refunds:
                if r["workflowId"] == wf_id:
                    r["status"] = status
                    if rollback_id:
                        r["rollbackWorkflowId"] = rollback_id


DESK: Desk | None = None


class Handler(BaseHTTPRequestHandler):
    server_version = "BrightcartDesk/1.0"
    protocol_version = "HTTP/1.1"

    def log_message(self, fmt, *args):  # quieter console: errors only
        pass

    # ---- plumbing ----------------------------------------------------------

    def _send(self, status: int, body, content_type: str = "application/json", extra=None):
        data = body if isinstance(body, (bytes, bytearray)) else json.dumps(body, default=str).encode("utf-8")
        self.send_response(status)
        self.send_header("Content-Type", content_type)
        self.send_header("Content-Length", str(len(data)))
        self.send_header("Cache-Control", "no-store")
        self.send_header("X-Content-Type-Options", "nosniff")
        for k, v in (extra or {}).items():
            self.send_header(k, v)
        self.end_headers()
        if self.command != "HEAD":
            self.wfile.write(data)

    def _raw_body(self) -> bytes:
        # Read once and kept: a handler that ignores its body must still take
        # it off the connection, or the next request on this keep-alive
        # connection is read starting with it ("{}GET /app/...").
        if getattr(self, "_body", None) is None:
            length = int(self.headers.get("Content-Length") or 0)
            if length > MAX_BODY:
                self.close_connection = True
                raise ValueError("That upload is too large for the demo (30 MB limit).")
            self._body = self.rfile.read(length) if length else b""
        return self._body

    def _json(self, raw: bytes | None = None):
        raw = self._raw_body() if raw is None else raw
        if not raw:
            return {}
        try:
            return json.loads(raw.decode("utf-8"))
        except (ValueError, UnicodeDecodeError):
            raise ValueError("The request body is not valid JSON.")

    def do_HEAD(self):
        self.do_GET()

    def do_GET(self):
        self._route("GET")

    def do_POST(self):
        self._route("POST")

    def do_DELETE(self):
        self._route("DELETE")

    def _route(self, method: str):
        self._body = None
        url = urlparse(self.path)
        path = url.path
        query = {k: v[-1] for k, v in parse_qs(url.query).items()}
        try:
            if method == "POST":
                self._raw_body()
            if path.startswith("/app/"):
                return self._app(method, path, query)
            if path.startswith("/svc/") and method in ("GET", "POST"):
                # A lookup is a GET with its arguments in the query; the rest are POSTs.
                body = query if method == "GET" else self._json()
                status, out = services.handle(path[5:], body, self.headers.get("Idempotency-Key"))
                return self._send(status, out)
            if path == "/specialist/receipt-reader" and method == "POST":
                return self._specialist()
            if path == "/webhooks/continuum" and method == "POST":
                status, out = webhooks.receive(self._raw_body(), self.headers, DESK.cfg["webhook_secret"])
                if status == 200 and DESK.cfg["webhook_secret"]:
                    LOG.mark("webhooks", "signed delivery verified: " + str(self.headers.get("Continuum-Event") or ""))
                return self._send(status, out)
            if method in ("GET", "HEAD"):
                return self._static(path)
            return self._send(404, {"error": "not found"})
        except ValueError as e:
            return self._send(400, {"ok": False, "error": {"code": "bad_request", "message": str(e)}})
        except Exception as e:  # never a stack trace to the page
            traceback.print_exc()
            return self._send(500, {"ok": False, "error": {"code": "server_error", "message": str(e)}})

    def _static(self, path: str):
        if path in ("", "/"):
            path = "/index.html"
        base = SAMPLES if path.startswith("/samples/") else WEB
        rel = path[len("/samples/"):] if base is SAMPLES else path.lstrip("/")
        target = (base / rel).resolve()
        if base.resolve() not in target.parents or not target.is_file():
            return self._send(404, b"Not found", "text/plain; charset=utf-8")
        ctype = mimetypes.guess_type(target.name)[0] or "application/octet-stream"
        if ctype.startswith("text/") or ctype in ("application/javascript", "application/json"):
            ctype += "; charset=utf-8"
        extra = {"Content-Disposition": f'attachment; filename="{target.name}"'} if base is SAMPLES else None
        return self._send(200, target.read_bytes(), ctype, extra)

    def _specialist(self):
        token = DESK.cfg["specialist_token"]
        auth = self.headers.get("Authorization") or ""
        if token and auth != "Bearer " + token:
            return self._send(401, {"error": "bad token"})
        status, out = specialist.read(self._json())
        return self._send(status, out)

    # ---- the page's API ----------------------------------------------------

    def _app(self, method: str, path: str, q: dict):
        start_collecting()
        try:
            out = self._app_route(method, path, q)
            if isinstance(out, tuple):  # (status, body) for app-level errors
                status, body = out
                return self._send(status, body)
            if out is None:  # already streamed
                return None
            out.setdefault("ok", True)
            # The Continuum calls this request made; /app/calls brings its own list.
            out.setdefault("calls", collected())
            return self._send(200, out)
        except ContinuumError as e:
            # Continuum's answer, shown as it is: a firewall refusal, a missing
            # key, a deadline that could not be met.
            return self._send(200, {"ok": False, "error": e.as_dict(), "calls": collected()})
        except PresenterError as e:
            return self._send(200, {"ok": False, "error": {"status": e.status, "error": "presenter",
                                                           "message": e.message},
                                    "presenter": DESK.presenter.status(), "calls": collected()})

    def _app_route(self, method: str, path: str, q: dict):
        d = DESK
        parts = [p for p in path.split("/") if p][1:]  # after "app"
        head = parts[0] if parts else ""

        if head == "status" and method == "GET":
            return self._status()
        if head == "customers" and method == "GET":
            return {"customers": store.customers()}
        if head == "products" and method == "GET":
            return {"products": store.products()}
        if head == "presenter":
            return self._presenter(method, parts[1] if len(parts) > 1 else "")
        if head == "long-chat" and method == "POST":
            body = self._json()
            return d.assistant.long_conversation(str(body.get("customerId") or "C-1002"),
                                                 str(body.get("question") or "").strip()[:500])
        if head == "orders" and method == "GET":
            return {"orders": store.orders_for(q["customer"]) if q.get("customer") else store.all_orders(),
                    "inventory": store.inventory()}
        if head == "tickets" and method == "GET":
            return {"tickets": store.tickets(), "customers": {c["id"]: c for c in store.customers()}}
        if head == "calls":
            if method == "DELETE":
                LOG.clear()
                return {"cleared": True}
            return {"calls": LOG.since(int(q.get("after") or 0)), "seen": LOG.seen()}
        if head == "features" and method == "GET":
            return {"features": [{"key": k, "name": n, "how": h, "console": c} for k, n, h, c in FEATURES],
                    "seen": LOG.seen()}
        if head == "reset" and method == "POST":
            store.reset()
            for svc in services.FAULT_MODES:
                services.set_fault(svc, "ok")
            return {"reset": True}

        if head == "chat" and method == "POST":
            if len(parts) > 1 and parts[1] == "stream":
                return self._chat_stream()
            body = self._json()
            history = _history(body.get("messages"))
            return d.assistant.reply(str(body.get("customerId") or ""), history,
                                     mode=_mode(body.get("mode")), use_tools=bool(body.get("tools", True)))
        if head == "help" and method == "POST":
            question = str(self._json().get("question") or "").strip()
            if not question:
                return 400, {"ok": False, "error": {"code": "bad_request", "message": "question needed"}}
            return d.assistant.help_centre(question[:500])
        if head == "loop-drill" and method == "POST":
            return d.assistant.loop_drill()
        if head == "memory":
            if method == "POST":
                body = self._json()
                cid, content = str(body.get("customerId") or ""), str(body.get("content") or "").strip()
                if not store.customer(cid) or not content:
                    return 400, {"ok": False, "error": {"code": "bad_request", "message": "customer and content needed"}}
                return {"stored": d.continuum.remember(memory_scope(cid), content,
                                                       action="Remember a customer preference")}
            cid = q.get("customer") or ""
            return {"memories": d.continuum.recall(memory_scope(cid), q.get("q") or "preferences",
                                                   action="Recall what we know about the customer", top_k=8)}
        if head == "models" and method == "GET":
            return {"models": d.continuum.models(action="List the models Continuum can route to")}
        if head == "feedback" and method == "POST":
            body = self._json()
            if not body.get("requestId"):
                return 400, {"ok": False, "error": {"code": "bad_request", "message": "requestId needed"}}
            return {"feedback": d.continuum.feedback(str(body["requestId"]), float(body.get("score", 1)),
                                                     body.get("comment"), action="Customer rated an answer")}

        if head == "triage" and method == "POST":
            body = self._json()
            t = store.ticket(str(body.get("ticketId") or ""))
            if not t:
                return 404, {"ok": False, "error": {"code": "not_found", "message": "No such ticket"}}
            deadline = body.get("deadlineMs")
            deadline = int(deadline) if isinstance(deadline, (int, float)) and 1 <= deadline <= 60000 else None
            return d.assistant.triage(t, bulk=bool(body.get("bulk")), deadline_ms=deadline)
        if head == "draft" and method == "POST":
            body = self._json()
            t = store.ticket(str(body.get("ticketId") or ""))
            if not t:
                return 404, {"ok": False, "error": {"code": "not_found", "message": "No such ticket"}}
            return d.assistant.draft_reply(t, careful=bool(body.get("careful")), quick=bool(body.get("quick")))

        if head == "refunds":
            return self._refunds(method, parts[1:])
        if head == "faults":
            if method == "POST":
                body = self._json()
                services.set_fault(str(body.get("service")), str(body.get("mode")))
            return {"faults": services.faults(), "modes": services.FAULT_MODES,
                    "help": {f"{s}:{m}": h for (s, m), h in services.FAULT_HELP.items()}}
        if head == "ledger" and method == "GET":
            return {"ledger": services.ledger(q.get("order")), "inventory": store.inventory()}
        if head == "webhooks" and method == "GET":
            return {"events": webhooks.events(), "secretConfigured": bool(d.cfg["webhook_secret"])}

        if head == "receipt" and method == "POST":
            body = self._json()
            name, content, ctype = _file(body)
            return {"result": d.continuum.pipeline_upload(d.cfg["pipeline_name"], name, content, ctype,
                                                          str(body.get("prompt") or "Is this receipt valid for "
                                                                                    "a refund?"),
                                                          action=f"Check receipt {name}")}
        if head == "photo" and method == "POST":
            body = self._json()
            url = str(body.get("image") or "")
            if not url.startswith("data:image/"):
                return 400, {"ok": False, "error": {"code": "bad_request", "message": "Send an image."}}
            return d.assistant.photo(url, str(body.get("question") or ""), body.get("customerId"))
        if head == "transform" and method == "POST":
            body = self._json()
            name, content, ctype = _file(body)
            budget = body.get("budget") if body.get("budget") in ("compact", "standard", "full") else "standard"
            return {"result": d.continuum.transform(name, content, ctype, budget,
                                                    action=f"Transform {name} for a model")}
        if head == "ask-file" and method == "POST":
            body = self._json()
            name, content, _ = _file(body)
            text = content.decode("utf-8", "replace")
            if len(text) > 60000:
                text = text[:60000]
            return d.assistant.ask_file(name, text, str(body.get("question") or "Summarise this file."))
        return 404, {"ok": False, "error": {"code": "not_found", "message": f"No route {method} {path}"}}

    def _presenter(self, method: str, what: str):
        p = DESK.presenter
        if what == "login" and method == "POST":
            body = self._json()
            return {"presenter": p.login(str(body.get("email") or ""), str(body.get("password") or ""))}
        if what == "logout" and method == "POST":
            return {"presenter": p.logout()}
        if what == "status" and method == "GET":
            return {"presenter": p.status()}
        if what == "do" and method == "POST":
            body = self._json()
            return {"result": p.run(str(body.get("action") or ""), body.get("args") or {}),
                    "presenter": p.status()}
        return 404, {"ok": False, "error": {"code": "not_found", "message": "No such presenter route"}}

    def _status(self):
        d = DESK
        cfg = d.cfg
        return {
            "presenter": d.presenter.status(),
            "continuum": {"url": cfg["continuum_url"], "reachable": d.continuum.reachable(),
                          "keyConfigured": bool(cfg["api_key"]), "keyHint": config.key_hint(cfg["api_key"])},
            "publicUrl": cfg["public_url"],
            "webhookSecret": bool(cfg["webhook_secret"]),
            "specialistToken": bool(cfg["specialist_token"]),
            "workflow": cfg["workflow_name"],
            "pipeline": cfg["pipeline_name"],
            "customers": store.customers(),
        }

    def _refunds(self, method: str, rest: list):
        d = DESK
        if not rest:
            if method == "POST":
                body = self._json()
                entry = d.start_refund(str(body.get("orderId") or ""), str(body.get("reason") or "customer request"),
                                       bool(body.get("collectItem")), source="agent")
                return {"refund": entry}
            # A run whose page was never opened still says RUNNING here; ask
            # Continuum (quietly) how the unfinished ones are doing.
            for r in d.refunds():
                if r.get("status") in (None, "RUNNING"):
                    try:
                        latest = d.continuum.workflow(r["workflowId"], action="Refund status", quiet=True)
                        d.note_status(r["workflowId"], latest.get("status", "RUNNING"))
                    except ContinuumError:
                        pass
            return {"refunds": d.refunds()}
        wf_id = rest[0]
        mine = d.refund(wf_id)
        if not mine:
            return 404, {"ok": False, "error": {"code": "not_found", "message": "Not a refund started here"}}
        if len(rest) > 1 and rest[1] == "cancel" and method == "POST":
            out = d.continuum.cancel_workflow(wf_id, "Cancelled by the support agent", action=f"Cancel refund "
                                                                                              f"{mine['orderId']}")
            d.note_status(wf_id, out.get("status", "CANCELLED"), out.get("rollbackWorkflowId"))
            return {"cancel": out}
        run = d.continuum.workflow(wf_id, action="Refund status", quiet=True)
        rollback = None
        if mine.get("rollbackWorkflowId"):
            try:
                rollback = d.continuum.workflow(mine["rollbackWorkflowId"], action="Rollback status", quiet=True)
            except ContinuumError:
                rollback = None
        status = run.get("status", "RUNNING")
        d.note_status(wf_id, status)
        # This run's calls only: Continuum's Idempotency-Key starts with the run id.
        ledger = [l for l in services.ledger(mine["orderId"])
                  if str(l.get("idempotencyKey") or "").split(":")[0] in (wf_id, mine.get("rollbackWorkflowId"))]
        if status not in ("RUNNING", "STUCK") and d.first_report(wf_id):
            # Polls are not logged; how the run ended is, once, so the inspector
            # shows the outcome (retries, a saga rollback) with the rest.
            run = d.continuum.workflow(wf_id, action=f"Refund of {mine['orderId']} ended: {status.lower()}")
            dupes = [l for l in ledger if l.get("duplicate")]
            if dupes:
                LOG.mark("exactly-once", f"{len(dupes)} repeated call(s) answered from the first result")
        return {"refund": d.refund(wf_id), "run": run, "rollbackRun": rollback, "ledger": ledger,
                "webhooks": [e for e in webhooks.events() if e.get("workflowId") in
                             (wf_id, mine.get("rollbackWorkflowId"))],
                "order": store.order(mine["orderId"])}

    def _chat_stream(self):
        """Server-sent events: each piece of the answer as Continuum streams it."""
        body = self._json()
        cust = store.customer(str(body.get("customerId") or ""))
        from .support import system_prompt
        messages = [{"role": "system", "content": system_prompt(cust, [])}] + _history(body.get("messages"))
        self.send_response(200)
        self.send_header("Content-Type", "text/event-stream")
        self.send_header("Cache-Control", "no-store")
        self.send_header("Connection", "close")
        self.end_headers()
        self.close_connection = True

        def emit(obj):
            self.wfile.write(b"data: " + json.dumps(obj, default=str).encode("utf-8") + b"\n\n")
            self.wfile.flush()

        try:
            summary = DESK.continuum.chat_stream(
                messages, action=f"Assistant streams to {cust['name'] if cust else 'a visitor'}",
                routing_mode=_mode(body.get("mode")), on_delta=lambda piece: emit({"delta": piece}))
            emit({"done": True, "meta": summary.get("continuum") or {}, "model": summary.get("model"),
                  "firstTokenMs": summary.get("first_token_ms"), "calls": collected()})
        except ContinuumError as e:
            emit({"done": True, "error": e.as_dict(), "calls": collected()})
        except (BrokenPipeError, ConnectionResetError):
            pass
        return None


def _history(raw) -> list:
    out = []
    for m in (raw or [])[-30:]:
        if isinstance(m, dict) and m.get("role") in ("user", "assistant") and isinstance(m.get("content"), str):
            out.append({"role": m["role"], "content": m["content"][:8000]})
    if not out or out[-1]["role"] != "user":
        raise ValueError("Send at least one user message.")
    return out


def _mode(v):
    return v if v in ("BALANCED", "LOW_COST", "LOW_LATENCY", "HIGH_QUALITY") else None


def _file(body: dict):
    name = str(body.get("filename") or "upload").replace("/", "_").replace("\\", "_")[:120]
    if isinstance(body.get("text"), str):
        return name, body["text"].encode("utf-8"), "text/plain"
    data = body.get("data")
    if not isinstance(data, str) or not data:
        raise ValueError("No file was sent.")
    if data.startswith("data:"):
        header, _, data = data.partition(",")
        ctype = header[5:].split(";")[0] or "application/octet-stream"
    else:
        ctype = str(body.get("contentType") or "application/octet-stream")
    try:
        content = base64.b64decode(data)
    except ValueError:
        raise ValueError("The file could not be decoded.")
    if not content:
        raise ValueError("The file is empty.")
    return name, content, ctype


def make_server(cfg: dict) -> ThreadingHTTPServer:
    """The app's HTTP server, not yet serving (setup_continuum.py runs it briefly)."""
    global DESK
    DESK = Desk(cfg)
    server = ThreadingHTTPServer((cfg["host"], cfg["port"]), Handler)
    server.daemon_threads = True
    return server


def serve(cfg: dict) -> None:
    try:
        server = make_server(cfg)
    except OSError as e:
        raise SystemExit(f"  Could not listen on {cfg['host']}:{cfg['port']} ({e.strerror}). Is server.py already "
                         f"running? Set DEMO_PORT to use another port.")
    print(f"\n  Brightcart Support Desk  →  http://{'localhost' if cfg['host'] in ('127.0.0.1', '0.0.0.0') else cfg['host']}:{cfg['port']}")
    print(f"  Continuum                →  {cfg['continuum_url']}  (key {config.key_hint(cfg['api_key']) or 'NOT SET'})")
    print(f"  Continuum calls back at  →  {cfg['public_url']}\n")
    if not cfg["api_key"]:
        print("  ! No API key yet: run  python3 setup_continuum.py  first (see README.md).\n")
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\n  Stopped.")
    finally:
        server.server_close()
