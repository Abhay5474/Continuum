#!/usr/bin/env python3
"""Configures a Continuum account for the Brightcart demo, in one go.

    python3 setup_continuum.py --email you@example.com

It signs in to the Continuum console API (your password is asked for, not
stored) and does what a developer would otherwise click through in the
console:

  1. issues an API key for this app (unless demo.config.json already has one)
  2. registers this app's receipt reader as a specialist, and a pipeline using it
  3. publishes the refund workflow, whose steps call this app's services
  4. registers this app's webhook endpoint and keeps its signing secret
  5. turns on the features the demo shows (use --no-features to skip)

Safe to run again: it finds what it made last time instead of duplicating it.
The key and secrets go into demo.config.json (git-ignored), never on screen.
"""
from __future__ import annotations

import argparse
import getpass
import json
import secrets
import socket
import sys
import threading
import urllib.error
import urllib.request

from brightcart import app, config, services

SPECIALIST_CONNECTION = "Brightcart receipt reader"
SPECIALIST_NAME = "Receipt reader"
PIPELINE_PROMPT = (
    "You check receipts for Brightcart support. Decide whether the receipt supports a refund: the order must "
    "exist, the totals must match and the order must be inside the 30-day return window. Answer in three short "
    "lines: Verdict (eligible / not eligible / cannot tell), Why, Next step for the agent."
)

# What the demo turns on, and why. Settings not listed are left as they are.
FEATURE_SWITCHES = ["prompt-firewall", "semantic-cache", "model-cascade", "chat-context", "context-optimizer"]
SETTINGS = [
    ("saga", "/api/portal/developer/saga/settings", {"enabled": True}),
    ("loop detection (halt)", "/api/portal/developer/loops/settings", {"enabled": True, "mode": "HALT"}),
    ("decision provenance", "/api/portal/developer/provenance/settings", {"enabled": True}),
    ("quality gate (monitor)", "/api/portal/developer/quality/settings", {"mode": "MONITOR"}),
    ("confidence (on demand)", "/api/portal/developer/uncertainty/settings",
     {"mode": "ON_DEMAND", "samples": 3, "temperature": 0.7}),
    ("priority & deadlines", "/api/portal/developer/scheduling/settings", {"enabled": True}),
    ("admission control", "/api/portal/developer/admission/settings", {"enabled": True}),
    ("degradation ladder", "/api/portal/developer/degradation/settings", {"enabled": True}),
    ("semantic breaker", "/api/portal/developer/breaker/settings", {"enabled": True}),
]


class Console:
    def __init__(self, base: str):
        self.base = base.rstrip("/")
        self.token = None

    def call(self, method: str, path: str, body=None):
        data = None if body is None else json.dumps(body).encode()
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("Content-Type", "application/json")
        if self.token:
            req.add_header("Authorization", "Bearer " + self.token)
        try:
            with urllib.request.urlopen(req, timeout=30) as res:
                raw = res.read()
                return json.loads(raw) if raw else None
        except urllib.error.HTTPError as e:
            detail = e.read().decode("utf-8", "replace")
            try:
                parsed = json.loads(detail)
                detail = parsed.get("message") or parsed.get("error") or detail
            except ValueError:
                pass
            raise SystemExit(f"\n  ✗ {method} {path} → {e.code}: {detail}")
        except urllib.error.URLError as e:
            raise SystemExit(f"\n  ✗ Could not reach Continuum at {self.base}: {e.reason}")


def step(text: str) -> None:
    print(f"  • {text}", flush=True)


def port_in_use(host: str, port: int) -> bool:
    with socket.socket(socket.AF_INET, socket.SOCK_STREAM) as s:
        s.settimeout(0.5)
        return s.connect_ex(("127.0.0.1" if host in ("0.0.0.0", "") else host, port)) == 0


SAMPLE_RECEIPT = ("BRIGHTCART Order receipt\nOrder: BC-1042\nCustomer: Priya Deshpande\n"
                  "1 x Jaipur Blue Pottery Mug Set (4)\nTotal: ₹1,199.00\n")


def main() -> None:
    cfg = config.load()
    p = argparse.ArgumentParser(description="Configure a Continuum account for the Brightcart demo.")
    p.add_argument("--url", default=cfg["continuum_url"], help="Continuum API (default %(default)s)")
    p.add_argument("--email", required=True, help="your Continuum console email")
    p.add_argument("--password", help="your console password (asked for if left out)")
    p.add_argument("--public-url", default=cfg["public_url"],
                   help="how Continuum reaches this app (default %(default)s; "
                        "use http://host.docker.internal:5050 if Continuum runs in Docker)")
    p.add_argument("--new-key", action="store_true", help="issue a new API key even if one is configured")
    p.add_argument("--no-features", action="store_true", help="leave feature settings untouched")
    a = p.parse_args()

    public = a.public_url.rstrip("/")
    if port_in_use(cfg["host"], cfg["port"]):
        raise SystemExit(f"\n  ✗ Something is already listening on port {cfg['port']}. Stop server.py first: setup "
                         "renews the tokens it uses, and runs the app briefly so Continuum can check its receipt "
                         "reader.\n")
    console = Console(a.url)
    password = a.password or getpass.getpass("  Continuum password: ")
    print()
    step(f"Signing in to {a.url} as {a.email}")
    login = console.call("POST", "/api/portal/developer/login", {"email": a.email, "password": password})
    console.token = login["sessionToken"]
    updates = {"continuum_url": a.url.rstrip("/"), "public_url": public}

    # 1. API key ------------------------------------------------------------
    if cfg["api_key"] and not a.new_key and cfg["continuum_url"] == a.url.rstrip("/"):
        step(f"Keeping the API key already in demo.config.json ({config.key_hint(cfg['api_key'])})")
    else:
        issued = console.call("POST", "/api/portal/developer/keys", {"label": "Brightcart demo"})
        updates["api_key"] = issued["apiKey"]
        step(f"Issued an API key for this app ({config.key_hint(issued['apiKey'])})")
    # Saved as we go: a later step failing must not lose a key that was issued.
    config.save(updates)

    # 2. Specialist and pipeline -------------------------------------------
    token = secrets.token_urlsafe(24)
    connections = console.call("GET", "/api/portal/developer/specialists/connections") or []
    conn = next((c for c in connections if c.get("name") == SPECIALIST_CONNECTION), None)
    if conn and conn.get("baseUrl", "").rstrip("/") == public + "/specialist":
        console.call("POST", f"/api/portal/developer/specialists/connections/{conn['id']}/secret", {"secret": token})
        step("Receipt reader connection found; its token was renewed")
    else:
        if conn:
            console.call("DELETE", f"/api/portal/developer/specialists/connections/{conn['id']}")
        conn = console.call("POST", "/api/portal/developer/specialists/connections", {
            "name": SPECIALIST_CONNECTION, "provider": "http", "baseUrl": public + "/specialist",
            "authStyle": "BEARER", "secret": token})
        step(f"Registered this app's receipt reader at {public}/specialist")
    updates["specialist_token"] = token
    config.save(updates)

    specs = console.call("GET", "/api/portal/developer/specialists") or []
    spec = next((s for s in specs if s.get("name") == SPECIALIST_NAME and s.get("connectionId") == conn["id"]), None)
    if not spec:
        spec = console.call("POST", "/api/portal/developer/specialists", {
            "connectionId": conn["id"], "name": SPECIALIST_NAME, "modelPath": "receipt-reader",
            "inputKind": "document", "minConfidence": 0.5, "timeoutSeconds": 15})
        step("Added the receipt reader as a specialist")

    # Continuum will not use a specialist it has not seen answer, so the app is
    # started for a moment and Continuum probes its receipt reader.
    server = app.make_server(dict(cfg, **updates))
    threading.Thread(target=server.serve_forever, daemon=True).start()
    try:
        probe = console.call("POST", f"/api/portal/developer/specialists/{spec['id']}/probe", {"text": SAMPLE_RECEIPT})
    finally:
        server.shutdown()
        server.server_close()
    if probe.get("status") != "READY":
        raise SystemExit(f"\n  ✗ Continuum could not use the receipt reader at {public}/specialist "
                         f"(status {probe.get('status')}: {probe.get('lastError') or probe.get('probeError') or 'no detail'}).\n"
                         "    Is Continuum started with CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS=true, and can it "
                         f"reach {public}? (In Docker, use --public-url http://host.docker.internal:{cfg['port']} "
                         "and DEMO_HOST=0.0.0.0.)\n")
    step("Continuum probed the receipt reader: it answers and its output parses")

    pipelines = console.call("GET", "/api/portal/developer/pipelines") or []
    pipe = next((x for x in pipelines if x.get("name") == cfg["pipeline_name"]), None)
    if not pipe:
        pipe = console.call("POST", "/api/portal/developer/pipelines", {
            "name": cfg["pipeline_name"], "description": "Checks a customer's receipt before a refund",
            "inputKind": "document", "systemPrompt": PIPELINE_PROMPT, "steps": [spec["id"]]})
        step(f"Created the “{cfg['pipeline_name']}” pipeline")
    console.call("PUT", f"/api/portal/developer/pipelines/{pipe['id']}", {
        "systemPrompt": PIPELINE_PROMPT, "steps": [spec["id"]], "enabled": True})
    step("Pipeline enabled with the receipt reader as its step")

    # 3. Workflow --------------------------------------------------------------
    published = console.call("POST", f"/api/portal/developer/workflows/definitions/{cfg['workflow_name']}",
                             services.workflow_spec(public))
    step(f"Published the “{cfg['workflow_name']}” workflow (v{published['version']})")

    # 4. Webhook --------------------------------------------------------------
    hook_url = public + "/webhooks/continuum"
    hooks = (console.call("GET", "/api/portal/developer/webhooks") or {}).get("endpoints", [])
    hook = next((h for h in hooks if h.get("url") == hook_url), None)
    events = ["workflow.completed", "workflow.failed", "workflow.cancelled", "workflow.stuck"]
    if hook:
        secret = console.call("POST", f"/api/portal/developer/webhooks/{hook['id']}/rotate-secret")["secret"]
        console.call("PUT", f"/api/portal/developer/webhooks/{hook['id']}", {"enabled": True, "events": events})
        step("Webhook endpoint found; its signing secret was rotated")
    else:
        created = console.call("POST", "/api/portal/developer/webhooks",
                               {"url": hook_url, "description": "Brightcart support desk", "events": events})
        secret = created["secret"]
        step(f"Registered the webhook endpoint {hook_url}")
    updates["webhook_secret"] = secret

    # 5. Features -----------------------------------------------------------
    if not a.no_features:
        for key in FEATURE_SWITCHES:
            console.call("PUT", f"/api/portal/developer/features/{key}", {"enabled": True})
        for name, path, body in SETTINGS:
            console.call("PUT", path, body)
        step("Turned on: " + ", ".join(FEATURE_SWITCHES) + ", " + ", ".join(n for n, _, _ in SETTINGS))
        console.call("PUT", "/api/portal/developer/features/verification-engine", {"enabled": False})
        step("Verification engine left off (it answers on its own path; turn it on for that scene)")

    config.save(updates)
    print(f"\n  ✓ Done. Settings saved to {config.CONFIG_FILE.name}.")
    print("    Continuum must be allowed to call this app on a private address: start it with")
    print("    CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS=true (see README.md).")
    print("\n    Now run:  python3 server.py\n")


if __name__ == "__main__":
    try:
        main()
    except KeyboardInterrupt:
        sys.exit(1)
