"""Settings for the demo, read from demo.config.json and the environment.

The API key never reaches the browser: the page talks to this server, and this
server talks to Continuum. That is the pattern a real application should use.
"""
from __future__ import annotations

import json
import os
import threading
from pathlib import Path

ROOT = Path(__file__).resolve().parent.parent
CONFIG_FILE = ROOT / "demo.config.json"

DEFAULTS = {
    # Where Continuum's API is.
    "continuum_url": "http://localhost:8080",
    # A Continuum API key (cnt_live_...). Written by setup_continuum.py, or
    # pasted into demo.config.json, or given as CONTINUUM_API_KEY.
    "api_key": "",
    # Where this app listens.
    "host": "127.0.0.1",
    "port": 5050,
    # The address Continuum uses to reach this app: workflow steps, the
    # receipt specialist and webhooks all call back here. When Continuum runs
    # in Docker this is usually http://host.docker.internal:5050.
    "public_url": "http://localhost:5050",
    # The webhook signing secret Continuum showed when the endpoint was made.
    "webhook_secret": "",
    # The token Continuum sends to this app's receipt specialist.
    "specialist_token": "",
    # Names of what setup_continuum.py publishes in the Continuum account.
    "workflow_name": "brightcart-refund",
    "pipeline_name": "brightcart-receipt-check",
}

ENV = {
    "continuum_url": "CONTINUUM_URL",
    "api_key": "CONTINUUM_API_KEY",
    "host": "DEMO_HOST",
    "port": "DEMO_PORT",
    "public_url": "DEMO_PUBLIC_URL",
    "webhook_secret": "DEMO_WEBHOOK_SECRET",
    "specialist_token": "DEMO_SPECIALIST_TOKEN",
}

_lock = threading.Lock()


def load() -> dict:
    cfg = dict(DEFAULTS)
    if CONFIG_FILE.exists():
        try:
            cfg.update({k: v for k, v in json.loads(CONFIG_FILE.read_text("utf-8")).items() if v not in (None, "")})
        except (OSError, ValueError) as e:
            raise SystemExit(f"Could not read {CONFIG_FILE.name}: {e}")
    for key, var in ENV.items():
        if os.environ.get(var):
            cfg[key] = os.environ[var]
    cfg["port"] = int(cfg["port"])
    cfg["continuum_url"] = str(cfg["continuum_url"]).rstrip("/")
    cfg["public_url"] = str(cfg["public_url"]).rstrip("/")
    return cfg


def save(updates: dict) -> None:
    """Merges ``updates`` into demo.config.json (used by the setup script)."""
    with _lock:
        current = {}
        if CONFIG_FILE.exists():
            try:
                current = json.loads(CONFIG_FILE.read_text("utf-8"))
            except ValueError:
                current = {}
        current.update(updates)
        CONFIG_FILE.write_text(json.dumps(current, indent=2) + "\n", "utf-8")
        try:
            os.chmod(CONFIG_FILE, 0o600)
        except OSError:
            pass


def key_hint(key: str) -> str:
    """Enough of a key to recognise it, never enough to use it."""
    if not key:
        return ""
    return key[:9] + "…" + key[-4:] if len(key) > 16 else "…"
