#!/usr/bin/env python3
"""PawCare: a pet first-aid assistant built on Continuum.

    python server.py            then open http://localhost:5070

An example of an application a developer builds once they have a Continuum
API key. It is deliberately small: one page, and this server, which holds the
key so the browser never sees it. Everything clever happens in Continuum:

  * Photo check   the photo goes to your pipeline (/api/gateway/pipeline/...):
                  your Roboflow model looks at it, Continuum turns the detections
                  into evidence with stated confidence, the language model answers
                  from that evidence, and the answer is checked against it.
  * Chat          the whole conversation goes through the gateway
                  (/v1/chat/completions): routing and failover, the firewall,
                  the semantic cache, the cascade, the context optimizer for long
                  chats, whatever the account has switched on.
  * Memory        each pet's facts and past photo checks are remembered
                  (/api/gateway/memory/...) and recalled into later answers.
  * Confidence    on request, the model is asked several times and its agreement
                  with itself is reported.
  * Emergency     marked urgent, a request is scheduled ahead of the queue.
  * Ratings       thumbs up / down on an answer (/api/gateway/feedback) tune the
                  account's routing.

Python 3.8+, standard library only. The key, Continuum's address and the
pipeline name are asked for on first start and kept in pawcare.local
(git-ignored), or read from CONTINUUM_URL, CONTINUUM_API_KEY, PAWCARE_PIPELINE.
"""
from __future__ import annotations

import base64
import getpass
import json
import os
import pathlib
import sys
import threading
import urllib.error
import urllib.parse
import urllib.request
import uuid
import webbrowser
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer

HERE = pathlib.Path(__file__).resolve().parent
CONFIG_FILE = HERE / "pawcare.local"
PORT = int(os.environ.get("PAWCARE_PORT", "5070"))
MAX_IMAGE = 8 * 1024 * 1024

try:
    sys.stdout.reconfigure(encoding="utf-8", errors="replace")
except Exception:
    pass

SYSTEM = (
    "You are PawCare, a careful first-aid assistant for pet owners. Give clear, practical, safe guidance in short "
    "paragraphs or steps. Never diagnose with certainty from a description alone. Say plainly when something needs a "
    "vet, and say it first when it is urgent (heavy bleeding, breathing trouble, poisoning, seizures, collapse, a "
    "broken bone). Never recommend human medicines for animals."
)



# --------------------------------------------------------------------------- #
# Configuration
# --------------------------------------------------------------------------- #

def load_config() -> dict:
    cfg = {"continuum_url": "http://localhost:8080", "api_key": "", "pipeline": ""}
    if CONFIG_FILE.exists():
        try:
            cfg.update(json.loads(CONFIG_FILE.read_text(encoding="utf-8")))
        except ValueError:
            pass
    cfg["continuum_url"] = os.environ.get("CONTINUUM_URL", cfg["continuum_url"]).rstrip("/")
    cfg["api_key"] = os.environ.get("CONTINUUM_API_KEY", cfg["api_key"])
    cfg["pipeline"] = os.environ.get("PAWCARE_PIPELINE", cfg["pipeline"])
    if not cfg["api_key"] and sys.stdin.isatty():
        print("\nFirst start: PawCare needs a Continuum API key.")
        print("Issue one in the Continuum console under API Keys & Providers › Apps & API keys.\n")
        url = input(f"  Continuum address [{cfg['continuum_url']}]: ").strip()
        if url:
            cfg["continuum_url"] = url.rstrip("/")
        cfg["api_key"] = getpass.getpass("  API key (cnt_live_…, input hidden): ").strip()
        cfg["pipeline"] = input("  Pipeline name for photo checks (as named on the Pipelines page; blank to skip): ").strip()
        CONFIG_FILE.write_text(json.dumps(cfg, indent=1), encoding="utf-8")
        print(f"\n  Saved to {CONFIG_FILE.name} (git-ignored). Delete it to enter new details.\n")
    if not cfg["api_key"]:
        raise SystemExit("No API key. Run this in a terminal to enter one, or set CONTINUUM_API_KEY.")
    return cfg


CFG: dict = {}


# --------------------------------------------------------------------------- #
# Continuum
# --------------------------------------------------------------------------- #

class Failed(Exception):
    def __init__(self, status: int, message: str):
        super().__init__(message)
        self.status = status
        self.message = message


def continuum(method: str, path: str, body=None, *, raw: bytes | None = None, content_type: str | None = None,
              timeout: float = 180):
    """Calls Continuum with the app's key. Returns (parsed body, headers)."""
    data = raw if raw is not None else (None if body is None else json.dumps(body).encode())
    req = urllib.request.Request(CFG["continuum_url"] + path, data=data, method=method)
    req.add_header("Authorization", "Bearer " + CFG["api_key"])
    req.add_header("Accept", "application/json")
    if data is not None:
        req.add_header("Content-Type", content_type or "application/json")
    try:
        with urllib.request.urlopen(req, timeout=timeout) as res:
            payload = res.read()
            return (json.loads(payload) if payload else None), dict(res.headers.items())
    except urllib.error.HTTPError as e:
        detail = e.read().decode("utf-8", "replace")
        try:
            parsed = json.loads(detail)
            detail = parsed.get("message") or parsed.get("error") or detail
        except ValueError:
            pass
        raise Failed(e.code, str(detail)[:400])
    except urllib.error.URLError as e:
        raise Failed(502, f"Continuum is not reachable at {CFG['continuum_url']} ({e.reason}). Is the backend running?")


def applied_of(parsed, headers) -> list:
    meta = parsed.get("continuum") if isinstance(parsed, dict) else None
    if isinstance(meta, dict) and isinstance(meta.get("applied"), list):
        return meta["applied"]
    if isinstance(parsed, dict) and isinstance(parsed.get("applied"), list):
        return parsed["applied"]
    header = headers.get("X-Continuum-Applied") or headers.get("x-continuum-applied") or ""
    return [p.strip() for p in header.split(";") if p.strip()]


def scope_of(pet: dict) -> str:
    pid = "".join(ch for ch in str(pet.get("id") or "pet") if ch.isalnum() or ch in "-_")[:40]
    return f"pawcare/pet-{pid}"


def describe_pet(pet: dict) -> str:
    bits = [pet.get("name") or "the pet"]
    if pet.get("species"):
        bits.append(f"a {pet['species']}")
    if pet.get("breed"):
        bits.append(pet["breed"])
    if pet.get("age"):
        bits.append(f"aged {pet['age']}")
    if pet.get("weight"):
        bits.append(f"weighing {pet['weight']}")
    return ", ".join(bits)


def recall(pet: dict, query: str, k: int = 5) -> list:
    """What Continuum remembers about this pet.

    With a query, only memories that share something with it come back; with an
    empty query, everything in the pet's scope, most salient and recent first.
    """
    try:
        found, _ = continuum("POST", "/api/gateway/memory/retrieve",
                             {"scope": scope_of(pet), "query": query or "", "topK": k}, timeout=20)
    except Failed:
        return []
    return [(m.get("content") or "").strip() for m in found or [] if (m.get("content") or "").strip()]


def remember(pet: dict, text: str, tier: str = "LONG_TERM", salience: float = 0.8):
    return continuum("POST", "/api/gateway/memory/store",
                     {"scope": scope_of(pet), "content": text[:4000], "tier": tier, "salience": salience}, timeout=20)[0]


def multipart(fields: dict, filename: str, content: bytes, ctype: str):
    boundary = "----pawcare" + uuid.uuid4().hex
    out = bytearray()
    for name, value in fields.items():
        if value is None:
            continue
        out += f'--{boundary}\r\nContent-Disposition: form-data; name="{name}"\r\n\r\n'.encode()
        out += str(value).encode("utf-8") + b"\r\n"
    safe = "".join(ch for ch in filename if ch not in '"\r\n') or "photo.jpg"
    out += (f'--{boundary}\r\nContent-Disposition: form-data; name="file"; filename="{safe}"\r\n'
            f"Content-Type: {ctype or 'application/octet-stream'}\r\n\r\n").encode()
    out += content + b"\r\n" + f"--{boundary}--\r\n".encode()
    return bytes(out), f"multipart/form-data; boundary={boundary}"


# --------------------------------------------------------------------------- #
# What the page asks for
# --------------------------------------------------------------------------- #

def do_chat(body: dict) -> dict:
    pet = body.get("pet") or {}
    history = [m for m in (body.get("messages") or []) if m.get("role") in ("user", "assistant") and m.get("content")]
    if not history or history[-1]["role"] != "user":
        raise Failed(400, "Ask something first.")
    question = history[-1]["content"]
    # What bears on the question, plus the pet's standing facts (allergies,
    # conditions, past photo checks), which matter whether or not the question
    # mentions them: "what can he eat?" must still be answered knowing he is
    # allergic to chicken.
    memories = []
    for m in recall(pet, question) + recall(pet, "", 6):
        if m not in memories:
            memories.append(m)
    memories = memories[:8]
    system = SYSTEM + f"\n\nThe pet: {describe_pet(pet)}."
    if pet.get("notes"):
        system += f" Owner's notes: {pet['notes']}."
    if memories:
        system += "\n\nWhat is known about this pet from earlier (use it where relevant):\n- " + "\n- ".join(memories)
    request = {"model": "auto", "messages": [{"role": "system", "content": system}] + history[-40:]}
    if body.get("measure"):
        request["continuum_measure_uncertainty"] = True
    if body.get("urgent"):
        request["continuum_criticality"] = "CRITICAL"
        request["continuum_deadline_ms"] = 15000
    parsed, headers = continuum("POST", "/v1/chat/completions", request)
    meta = parsed.get("continuum") or {}
    answer = parsed.get("response") or ((parsed.get("choices") or [{}])[0].get("message") or {}).get("content") or ""
    return {
        "answer": answer,
        "requestId": meta.get("request_id") or parsed.get("requestId") or headers.get("X-Continuum-Request-Id"),
        "model": parsed.get("model"),
        "provider": meta.get("provider") or parsed.get("provider"),
        "latencyMs": meta.get("latency_ms") or parsed.get("latency"),
        "cost": meta.get("cost_usd") if meta.get("cost_usd") is not None else parsed.get("cost"),
        "failovers": meta.get("failovers") or parsed.get("failovers") or 0,
        "cached": bool(meta.get("cached")),
        "confidence": meta.get("confidence") if meta.get("confidence") is not None else parsed.get("confidence"),
        "lowConfidence": meta.get("low_confidence") if meta.get("low_confidence") is not None else parsed.get("lowConfidence"),
        "applied": applied_of(parsed, headers),
        "memories": memories,
    }


def do_photo(body: dict) -> dict:
    if not CFG.get("pipeline"):
        raise Failed(400, "No pipeline is set for photo checks. Stop the server, delete pawcare.local and start it "
                          "again to enter your pipeline's name (or set PAWCARE_PIPELINE).")
    pet = body.get("pet") or {}
    try:
        content = base64.b64decode(body.get("imageBase64") or "", validate=False)
    except ValueError:
        raise Failed(400, "That photo could not be read.")
    if not content:
        raise Failed(400, "Choose a photo first.")
    if len(content) > MAX_IMAGE:
        raise Failed(413, "That photo is over 8 MB; please choose a smaller one.")
    question = (body.get("question") or "").strip() or "What do you see, and what should I do?"
    prompt = (f"The owner of {describe_pet(pet)} sent a photo and asks: {question}\n"
              "Answer for the owner: what was seen, how sure that is, what to do now, and when to see a vet.")
    raw, ctype = multipart({"prompt": prompt}, body.get("filename") or "photo.jpg", content,
                           body.get("contentType") or "image/jpeg")
    parsed, headers = continuum("POST", f"/api/gateway/pipeline/{urllib.parse.quote(CFG['pipeline'])}/upload",
                                raw=raw, content_type=ctype, timeout=240)
    # What each detector saw, from its step in the trace: every label with its
    # confidence, and any detector that failed or was skipped, said so.
    findings, detectors = [], []
    for step in parsed.get("trace") or []:
        if str(step.get("kind") or "").upper() != "SPECIALIST":
            continue
        detail = step.get("detail") if isinstance(step.get("detail"), dict) else {}
        name = detail.get("specialist") or step.get("label")
        detectors.append({"name": name, "status": step.get("status"), "error": detail.get("error"),
                          "belowThreshold": detail.get("belowThreshold") or 0})
        for f in detail.get("findings") or []:
            if f.get("label") is not None and f.get("confidence") is not None:
                findings.append({"label": f["label"], "confidence": float(f["confidence"]), "source": name})
    findings.sort(key=lambda f: -f["confidence"])
    result = {
        "answer": parsed.get("response") or "",
        "traceId": parsed.get("traceId"),
        "findings": findings,
        "detectors": detectors,
        "findingCount": parsed.get("findings"),
        "evidenceConfidence": parsed.get("evidenceConfidence"),
        "anythingFound": parsed.get("anythingFound"),
        "analysisRan": parsed.get("analysisRan"),
        "policy": parsed.get("policy"),
        "verification": parsed.get("verification"),
        "compliance": parsed.get("compliance"),
        "model": parsed.get("model"),
        "latencyMs": parsed.get("latencyMs"),
        "cost": parsed.get("cost"),
        "trace": parsed.get("trace") or [],
        "applied": applied_of(parsed, headers),
    }
    # The check becomes part of the pet's history, so a later question
    # ("is it healing?") is answered knowing what the photo showed.
    try:
        seen = ", ".join(f"{f['label']} ({round(float(f['confidence']) * 100)}%)" for f in findings[:6]) or "nothing detected"
        remember(pet, f"Photo check: {seen}. Owner asked: {question}", tier="EPISODIC", salience=0.7)
        result["remembered"] = True
    except Failed:
        result["remembered"] = False
    return result


def do_remember(body: dict) -> dict:
    fact = (body.get("fact") or "").strip()
    if not fact:
        raise Failed(400, "Write something to remember.")
    saved = remember(body.get("pet") or {}, fact)
    return {"saved": True, "reinforced": bool((saved or {}).get("reinforced"))}


def do_memories(body: dict) -> dict:
    pet = body.get("pet") or {}
    return {"memories": recall(pet, body.get("query") or "", 20)}


def do_feedback(body: dict) -> dict:
    if not body.get("requestId"):
        raise Failed(400, "That answer has no request id to rate.")
    continuum("POST", "/api/gateway/feedback", {"requestRef": body["requestId"], "score": float(body.get("score", 0)),
                                                "comment": body.get("comment")}, timeout=20)
    return {"recorded": True}


def do_vet_summary(body: dict) -> dict:
    pet = body.get("pet") or {}
    history = [m for m in (body.get("messages") or []) if m.get("role") in ("user", "assistant") and m.get("content")]
    memories = recall(pet, "", 12)
    transcript = "\n".join(f"{m['role'].upper()}: {m['content'][:1500]}" for m in history[-24:])
    prompt = (f"Write a short handover note for a veterinarian about {describe_pet(pet)}. Use headings: Animal, "
              "What the owner reported, What photo checks found (with confidence), Advice already given, Questions "
              "for the vet. Only include facts present below; write 'not recorded' where something is missing.\n\n"
              f"Known history:\n- " + ("\n- ".join(memories) if memories else "none") +
              f"\n\nConversation:\n{transcript or 'none'}")
    parsed, headers = continuum("POST", "/v1/chat/completions",
                                {"model": "auto", "continuum_routing_mode": "QUALITY",
                                 "messages": [{"role": "system", "content": "You write concise, factual veterinary handover notes."},
                                              {"role": "user", "content": prompt}]})
    meta = parsed.get("continuum") or {}
    return {"summary": parsed.get("response") or "", "requestId": meta.get("request_id"),
            "applied": applied_of(parsed, headers), "model": parsed.get("model")}


def do_status(_body: dict) -> dict:
    try:
        urllib.request.urlopen(CFG["continuum_url"] + "/api/meta", timeout=4).read()
        up = True
    except Exception:
        up = False
    return {"continuum": up, "url": CFG["continuum_url"], "pipeline": CFG.get("pipeline") or None}


ROUTES = {"/api/chat": do_chat, "/api/photo": do_photo, "/api/remember": do_remember, "/api/memories": do_memories,
          "/api/feedback": do_feedback, "/api/vet-summary": do_vet_summary, "/api/status": do_status}


class Handler(BaseHTTPRequestHandler):
    server_version = "PawCare"

    def log_message(self, fmt, *args):
        if "/api/" in (self.path or ""):
            sys.stdout.write("  %s %s\n" % (self.command, self.path))

    def send_json(self, status: int, body: dict):
        data = json.dumps(body).encode()
        self.send_response(status)
        self.send_header("Content-Type", "application/json")
        self.send_header("Content-Length", str(len(data)))
        self.end_headers()
        self.wfile.write(data)

    def do_GET(self):
        if self.path in ("/", "/index.html"):
            page = (HERE / "index.html").read_bytes()
            self.send_response(200)
            self.send_header("Content-Type", "text/html; charset=utf-8")
            self.send_header("Content-Length", str(len(page)))
            self.end_headers()
            self.wfile.write(page)
        elif self.path == "/api/status":
            self.send_json(200, do_status({}))
        else:
            self.send_json(404, {"error": "not found"})

    def do_POST(self):
        handler = ROUTES.get(self.path)
        if not handler:
            return self.send_json(404, {"error": "not found"})
        length = int(self.headers.get("Content-Length") or 0)
        if length > MAX_IMAGE * 2:
            return self.send_json(413, {"error": "That upload is too large."})
        try:
            body = json.loads(self.rfile.read(length) or b"{}")
            self.send_json(200, handler(body))
        except Failed as e:
            self.send_json(e.status if 400 <= e.status < 600 else 502, {"error": e.message})
        except ValueError:
            self.send_json(400, {"error": "The request was not valid JSON."})
        except Exception as e:  # keep the demo standing whatever happens
            self.send_json(500, {"error": f"PawCare hit an error: {e}"})


def main():
    global CFG
    CFG = load_config()
    try:
        server = ThreadingHTTPServer(("127.0.0.1", PORT), Handler)
    except OSError:
        raise SystemExit(f"Port {PORT} is in use. Is PawCare already running? (Or set PAWCARE_PORT to another port.)")
    print(f"PawCare is running: http://localhost:{PORT}")
    print(f"  Continuum: {CFG['continuum_url']}   pipeline: {CFG.get('pipeline') or '(none: photo checks off)'}")
    print("  Ctrl+C to stop.\n")
    if os.environ.get("PAWCARE_NO_BROWSER") != "1":
        threading.Timer(0.6, lambda: webbrowser.open(f"http://localhost:{PORT}")).start()
    try:
        server.serve_forever()
    except KeyboardInterrupt:
        print("\nStopped.")


if __name__ == "__main__":
    main()
