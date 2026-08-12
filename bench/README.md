# The reliability benchmark

Continuum claims it absorbs provider failures before your application sees them,
and that running traffic through it costs less than running it around. The
console shows what happened; it cannot show what *would* have happened, and a
figure with no counterfactual is a claim rather than evidence.

This runs the same seeded workload twice against a real gateway over HTTP, with
a deterministic provider failure armed, and prints both arms side by side.

## Running it

```bash
./dev.sh                                   # postgres, backend, console
python3 bench/benchmark.py \
    --api-key   cnt_live_…                 # a Continuum API key
    --session   …                          # a portal session token
    --requests  100 \
    --fail-rate 0.25 \
    --json      bench/last-run.json
```

It exits non-zero when a claim fails, so it can run in CI as a regression test
rather than only as a demo.

## A run

```
Workload   100 requests, 10 distinct (90% repeats), seed 7
Injection  provider failure rate 25%, evenly spaced

                                    cache off         cache on
----------------------------   --------------   --------------
Requests sent                             100              100
Answered                                  100              100
Failures reaching the caller                0                0
Deliberately rate-limited                   0                3
Provider failures absorbed                 33                3
Tokens billed                           3,266              328
Spend (USD)                         $0.000115        $0.000012
Latency p50                           41.8 ms          11.3 ms
Latency p95                           66.0 ms          34.0 ms
Latency p99                          203.4 ms          46.1 ms

Cache saved 2,938 tokens (90.0%) on identical traffic.
```

## Reading it honestly

**"Failures reaching the caller: 0" is the headline.** A quarter of all provider
calls were made to fail, and the application saw none of it.

**"Provider failures absorbed" falls when the cache is on, and that is not a
regression.** A cache hit never reaches a provider, so there are fewer provider
calls for the injector to fail. Both arms served every request.

**The 90% token saving is a property of this workload, not a promise.** The
stream is 90% repeats by construction. Real traffic repeats less, and the saving
scales with how much it does. A benchmark tuned to flatter the product is worth
nothing; this one states its own workload so you can judge whether it resembles
yours.

**Latency improves because a cache hit skips the provider entirely.** The p99
gap is the more interesting half: the tail is where failover retries live, and
answers served from cache never enter that path.

**Rate-limited requests are counted separately from failures.** A 429 with
`Retry-After` is admission control working, not the gateway breaking. The first
version of this harness conflated the two and reported 54 failures that were all
the rate limiter doing its job.

**The one derived figure is labelled.** A request Continuum had to fail over is
one a direct caller would have seen fail, because a direct caller has no second
provider to try.

## Why it is reproducible

The workload is seeded. The injected failures are evenly spaced by construction
rather than drawn at random: at rate *r*, call *n* fails iff
`floor((n+1)r) > floor(nr)`, which yields exactly `floor(Nr)` failures in *N*
calls. Two runs of the same command produce the same counts, which is what makes
the numbers usable as an argument that something improved.

The spacing matters as well as the count. Clustered failures would exhaust a
failover chain and produce visible errors, and the benchmark would then be
measuring the clustering rather than the product.
`ProviderFailureInjectionTest` asserts both properties.

## What it does not measure

- **Real providers.** Everything here runs against the mock, so the latency
  figures are Continuum's own overhead and not a provider's. Point it at a
  deployment with real keys to get end-to-end numbers.
- **Quality.** Whether the cache served an *appropriate* answer is the
  similarity threshold's job, and this harness does not judge it.
- **Concurrency.** The workload is sequential. Behaviour under parallel load is
  a different benchmark.
