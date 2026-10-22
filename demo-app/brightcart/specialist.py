"""The store's own receipt reader: a "specialist" Continuum calls in a pipeline.

A specialist is any model or service that is not the language model — here, a
small parser the store already runs. Continuum calls it with the uploaded file,
turns what it finds into evidence with stated confidence, and only then asks
the language model, which is told it has not seen the file itself.

Continuum authenticates to it with a bearer token stored (encrypted) in the
Continuum account, so the token never appears in the application's requests.
"""
from __future__ import annotations

import base64
import re
import zlib

from . import store

ORDER_RE = re.compile(r"\b(BC-\d{4})\b", re.I)
TOTAL_RE = re.compile(r"(?:grand\s+)?total[^0-9]{0,20}(\d+(?:[.,]\d{2}))", re.I)
DATE_RE = re.compile(r"\b(20\d\d-\d\d-\d\d)\b")


def read(payload: dict) -> tuple[int, dict]:
    """Returns (status, body) for one call from Continuum."""
    if not isinstance(payload, dict):
        return 400, {"error": "expected a JSON object"}
    text = ""
    source = None
    if isinstance(payload.get("text"), str):
        text, source = payload["text"], "text"
    elif isinstance(payload.get("documentBase64"), str):
        text, source = pdf_text(_b64(payload["documentBase64"])), "pdf"
    elif isinstance(payload.get("imageBase64"), str):
        # This reader does text documents only. Saying "nothing found" is the
        # honest answer, and Continuum's confidence policy knows what to do
        # with it: the pipeline declines rather than guessing.
        return 200, {"findings": [], "note": "images are not read by this receipt reader"}
    if not text.strip():
        return 200, {"findings": [], "note": f"no readable text in the {source or 'input'}"}

    fields = {}
    m = ORDER_RE.search(text)
    order = store.order(m.group(1)) if m else None
    if m:
        fields["order_id"] = m.group(1).upper()
    t = TOTAL_RE.search(text)
    if t:
        fields["receipt_total"] = t.group(1).replace(",", ".")
    d = DATE_RE.search(text)
    if d:
        fields["receipt_date"] = d.group(1)
    if order:
        cust = store.customer(order["customer"]) or {}
        fields["order_found"] = "yes"
        fields["customer"] = cust.get("name", "")
        fields["item"] = order["item"]
        fields["order_total"] = f"{order['amount']:.2f}"
        if "receipt_total" in fields:
            fields["totals_match"] = "yes" if abs(float(fields["receipt_total"]) - order["amount"]) < 0.005 else "no"
        fields["order_status"] = order["status"]
        if order.get("delivered_days_ago") is not None:
            fields["days_since_delivery"] = str(order["delivered_days_ago"])
        fields["within_return_window"] = "yes" if order["returnable"] else "no"
        fields["return_window_note"] = order["returnable_reason"]
    elif m:
        fields["order_found"] = "no"
    if not fields:
        return 200, {"findings": [], "note": "no receipt details recognised"}
    return 200, {"fields": fields}


def _b64(s: str) -> bytes:
    try:
        return base64.b64decode(s.split(",", 1)[1] if s.startswith("data:") else s)
    except (ValueError, IndexError):
        return b""


_STREAM_RE = re.compile(rb"stream\r?\n(.*?)\r?\nendstream", re.S)
_TJ_RE = re.compile(rb"\((?:\\.|[^\\)])*\)\s*Tj|\[(?:[^\]]*)\]\s*TJ")
_STR_RE = re.compile(rb"\((?:\\.|[^\\)])*\)")


def pdf_text(data: bytes) -> str:
    """Text from a simple PDF: the strings shown by Tj/TJ, in plain or Flate streams."""
    if not data.startswith(b"%PDF"):
        return ""
    out = []
    for raw in _STREAM_RE.findall(data):
        chunk = raw
        try:
            chunk = zlib.decompress(raw)
        except zlib.error:
            pass
        for op in _TJ_RE.findall(chunk):
            line = b"".join(_unescape(s[1:-1]) for s in _STR_RE.findall(op))
            out.append(line.decode("latin-1", "replace"))
    return "\n".join(out)


def _unescape(s: bytes) -> bytes:
    return re.sub(rb"\\([nrtbf()\\])", lambda m: {b"n": b"\n", b"r": b"\r", b"t": b"\t", b"b": b"\b", b"f": b"\f"}
                  .get(m.group(1), m.group(1)), s)
