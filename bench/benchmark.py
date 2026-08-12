#!/usr/bin/env python3
"""
The reliability benchmark.

Continuum's claims are that it absorbs provider failures before your application
sees them, and that it costs less to run traffic through it than around it. Both
are testable, and until this existed neither had been tested — the numbers on the
console are what happened, not what would have happened otherwise, and a figure
with no counterfactual is a claim.

So this runs the same workload twice against a real gateway over HTTP, with a
deterministic provider failure armed, and reports both arms side by side:

    without the cache          with the cache
    ------------------------   ------------------------
    tokens billed, spend       tokens billed, spend
    latency p50/p95/p99        latency p50/p95/p99
    failures reaching you      failures reaching you
    failovers absorbed         failovers absorbed

Everything it reports is measured. The one derived figure is labelled as such:
a request Continuum had to fail over is a request a direct caller would have
seen fail, because the direct caller has no second provider to try.

    python3 bench/benchmark.py --api-key <key> --session <token>

Reproducibility: the workload is seeded, and the injected failures are evenly
spaced by construction rather than drawn at random, so two runs of the same
command produce the same counts. A benchmark that reports a different number
every time cannot be used to argue that anything improved.

Exits non-zero if a claim fails, so it can run in CI as a regression test rather
than only as a demo.
"""

from __future__ import annotations

import argparse
import json
import random
import statistics
import sys
import time
import urllib.error
import urllib.request

# A workload with deliberate repetition. Without repeats the cache has nothing
# to hit and the comparison is vacuous; with everything repeated it is a lie.
PROMPTS = [
    "Summarise the refund policy",
    "What is the escalation path for a Sev-1?",
    "Explain the cancellation terms",
    "How long does a refund take to clear?",
    "Draft a shipping-delay apology",
    "What is covered by the warranty?",
    "Summarise this incident for the status page",
    "What is our SLA for enterprise support?",
    "Explain the data-retention policy",
    "How do I rotate an API key?",
]


class Client:
    def __init__(self, base: str, api_key: str, session: str | None):
        self.base = base.rstrip("/")
        self.api_key = api_key
        self.session = session

    def _call(self, method: str, path: str, body=None, auth: str | None = None, timeout=60):
        data = json.dumps(body).encode() if body is not None else None
        req = urllib.request.Request(self.base + path, data=data, method=method)
        req.add_header("content-type", "application/json")
        req.add_header("authorization", "Bearer " + (auth or self.api_key))
        try:
            with urllib.request.urlopen(req, timeout=timeout) as r:
                raw = r.read().decode()
                return r.status, (json.loads(raw) if raw else {})
        except urllib.error.HTTPError as e:
            raw = e.read().decode()
            try:
                return e.code, json.loads(raw)
            except json.JSONDecodeError:
                return e.code, {"raw": raw}
        except Exception as e:  # noqa: BLE001 - a transport failure is a data point
            return 0, {"error": str(e)}

    # ---- control plane -------------------------------------------------
    def set_provider_failure_rate(self, rate: float):
        return self._call("POST", f"/api/chaos/provider-failure-rate?rate={rate}",
                          body={}, auth=self.session)

    def set_cache(self, on: bool):
        return self._call("POST",
                          f"/api/portal/developer/cache/{'enable' if on else 'disable'}",
                          body={}, auth=self.session)

    def clear_cache(self):
        # DELETE, not POST /clear. POST /cache/{action} is the on/off switch, and
        # the first version of this called it with "clear" — which the server read
        # as "not enable", disabled the cache, and returned 200. Both ends of that
        # are now fixed; this one stays as the correct call.
        return self._call("DELETE", "/api/portal/developer/cache", auth=self.session)

    def gateway_stats(self):
        return self._call("GET", "/api/gateway/stats", auth=self.session)[1]

    # ---- the workload --------------------------------------------------
    def ask(self, prompt: str, max_retries: int = 6):
        """
        One request, honouring a deliberate refusal.

        A 429 with Retry-After is the product working: admission control saying
        "not now" rather than failing. A benchmark that counts it as a failure
        measures its own impatience — the first version of this did exactly that
        and reported 54 failures that were all the rate limiter doing its job.

        So it backs off and retries, and reports the waiting separately. Latency
        is measured on the attempt that was actually served, not including the
        time spent deliberately waiting.
        """
        refusals = 0
        for _ in range(max_retries):
            started = time.perf_counter()
            status, body = self._call("POST", "/v1/chat/completions", {
                "model": "auto",
                "messages": [{"role": "user", "content": prompt}],
                "max_tokens": 60,
            })
            elapsed_ms = (time.perf_counter() - started) * 1000
            if status != 429:
                return status, body, elapsed_ms, refusals
            refusals += 1
            time.sleep(self._retry_after(body))
        return 429, body, 0.0, refusals

    @staticmethod
    def _retry_after(body) -> float:
        """Whatever the server asked for, floored so a 0 does not spin."""
        hint = (body or {}).get("error", {})
        if isinstance(hint, dict):
            for key in ("retryAfterSeconds", "retry_after"):
                if key in hint:
                    try:
                        return max(0.25, float(hint[key]))
                    except (TypeError, ValueError):
                        pass
        return 1.0


def workload(n: int, repeat_ratio: float, seed: int) -> list[str]:
    """A seeded request stream with a known fraction of repeats."""
    rng = random.Random(seed)
    stream, seen = [], []
    for _ in range(n):
        if seen and rng.random() < repeat_ratio:
            stream.append(rng.choice(seen))
        else:
            p = rng.choice(PROMPTS)
            seen.append(p)
            stream.append(p)
    return stream


def run_arm(client: Client, name: str, stream: list[str]) -> dict:
    latencies, failovers, visible_failures, tokens, spend, served = [], 0, 0, 0, 0.0, 0
    refused = 0
    for prompt in stream:
        status, body, elapsed, refusals = client.ask(prompt)
        refused += refusals
        if status == 429:
            # Still refused after backing off. Deliberate, and reported as such:
            # it is not the same event as the gateway being unable to answer.
            continue
        if status != 200 or "choices" not in body:
            visible_failures += 1
            continue
        served += 1
        latencies.append(elapsed)
        usage = body.get("usage") or {}
        tokens += usage.get("total_tokens", 0)
        meta = body.get("continuum") or {}
        spend += meta.get("cost_usd", 0.0) or 0.0
        failovers += meta.get("failovers", 0) or 0
    latencies.sort()

    def pct(p: float) -> float:
        if not latencies:
            return 0.0
        return latencies[min(len(latencies) - 1, int(len(latencies) * p))]

    return {
        "arm": name,
        "requests": len(stream),
        "served": served,
        "rate_limited": refused,
        "visible_failures": visible_failures,
        "failovers_absorbed": failovers,
        "tokens": tokens,
        "spend_usd": round(spend, 8),
        "p50_ms": round(statistics.median(latencies), 1) if latencies else 0,
        "p95_ms": round(pct(0.95), 1),
        "p99_ms": round(pct(0.99), 1),
    }


def main() -> int:
    ap = argparse.ArgumentParser(description=__doc__,
                                 formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--base-url", default="http://localhost:8080")
    ap.add_argument("--api-key", required=True, help="A Continuum API key (cnt_live_…)")
    ap.add_argument("--session", required=True, help="A portal session token, to arm chaos and toggle the cache")
    ap.add_argument("--requests", type=int, default=120)
    ap.add_argument("--repeat-ratio", type=float, default=0.45)
    ap.add_argument("--fail-rate", type=float, default=0.25,
                    help="Injected provider failure rate, evenly spaced")
    ap.add_argument("--seed", type=int, default=7)
    ap.add_argument("--json", help="Also write the result here")
    args = ap.parse_args()

    c = Client(args.base_url, args.api_key, args.session)
    stream = workload(args.requests, args.repeat_ratio, args.seed)
    distinct = len(set(stream))

    print(f"Workload   {args.requests} requests, {distinct} distinct "
          f"({100 - round(distinct / args.requests * 100)}% repeats), seed {args.seed}")
    print(f"Injection  provider failure rate {args.fail_rate:.0%}, evenly spaced\n")

    status, _ = c.set_provider_failure_rate(args.fail_rate)
    if status != 200:
        print(f"could not arm the failure injector (HTTP {status}) — is the session token valid?",
              file=sys.stderr)
        return 2

    try:
        c.set_cache(False)
        c.clear_cache()
        without = run_arm(c, "cache off", stream)

        c.set_cache(True)
        c.clear_cache()
        with_cache = run_arm(c, "cache on", stream)
    finally:
        # Leave nothing armed. A benchmark that silently degrades the deployment
        # it measured is worse than no benchmark.
        c.set_provider_failure_rate(0)

    rows = [
        ("Requests sent", without["requests"], with_cache["requests"]),
        ("Answered", without["served"], with_cache["served"]),
        ("Failures reaching the caller", without["visible_failures"], with_cache["visible_failures"]),
        ("Deliberately rate-limited", without["rate_limited"], with_cache["rate_limited"]),
        ("Provider failures absorbed", without["failovers_absorbed"], with_cache["failovers_absorbed"]),
        ("Tokens billed", f"{without['tokens']:,}", f"{with_cache['tokens']:,}"),
        ("Spend (USD)", f"${without['spend_usd']:.6f}", f"${with_cache['spend_usd']:.6f}"),
        ("Latency p50", f"{without['p50_ms']} ms", f"{with_cache['p50_ms']} ms"),
        ("Latency p95", f"{without['p95_ms']} ms", f"{with_cache['p95_ms']} ms"),
        ("Latency p99", f"{without['p99_ms']} ms", f"{with_cache['p99_ms']} ms"),
    ]
    w = max(len(r[0]) for r in rows)
    print(f"{'':<{w}}   {'cache off':>14}   {'cache on':>14}")
    print(f"{'':-<{w}}   {'':->14}   {'':->14}")
    for label, a, b in rows:
        print(f"{label:<{w}}   {str(a):>14}   {str(b):>14}")

    saved = without["tokens"] - with_cache["tokens"]
    pct_saved = (saved / without["tokens"] * 100) if without["tokens"] else 0
    print()
    print(f"Cache saved {saved:,} tokens ({pct_saved:.1f}%) on identical traffic.")
    print(f"Continuum absorbed {with_cache['failovers_absorbed']} provider failures; "
          f"{with_cache['visible_failures']} reached the caller.")
    print("A direct caller would have seen every absorbed failure as an error — "
          "there is no second provider to try when you call one directly.")

    result = {"workload": {"requests": args.requests, "distinct": distinct,
                           "repeat_ratio": args.repeat_ratio, "seed": args.seed,
                           "injected_failure_rate": args.fail_rate},
              "arms": [without, with_cache],
              "tokens_saved": saved, "tokens_saved_pct": round(pct_saved, 1)}
    if args.json:
        with open(args.json, "w") as f:
            json.dump(result, f, indent=2)
        print(f"\nWrote {args.json}")

    # ---- the claims, asserted ------------------------------------------
    failures = []
    if with_cache["visible_failures"] > 0:
        failures.append(f"{with_cache['visible_failures']} failures reached the caller; "
                        f"the whole point is that none do")
    if with_cache["failovers_absorbed"] == 0 and args.fail_rate > 0:
        failures.append("no failovers were absorbed, so the injector never fired — "
                        "the run proves nothing")
    if saved <= 0:
        failures.append("the cache billed no fewer tokens than running without it")
    if failures:
        print("\nFAILED:")
        for f in failures:
            print("  - " + f)
        return 1
    print("\nPASS: every claim held.")
    return 0


if __name__ == "__main__":
    sys.exit(main())
