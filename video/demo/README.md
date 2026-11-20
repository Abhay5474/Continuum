# Continuum feature demo video

`out/continuum-demo.mp4`: a captioned walk-through of Continuum's features, recorded live
against a running stack. The Brightcart shop (an app built on Continuum) is used to show
how an application benefits; the focus is what Continuum does with each AI call.

Each chapter introduces what is about to happen, runs it live, then dims and blurs
everything except the result, points at it with a labelled arrow and explains it in a
caption. Chapters are written into the MP4, so a player can jump between features.

| # | Chapter | Shown in |
|---|---|---|
| 1 | One key, any model | shop + X-ray |
| 2 | Real data, not guesses (tool calling) | shop chat |
| 3 | Prompt firewall | shop desk, console Prompt Guard |
| 4 | Semantic cache | shop help centre |
| 5 | Provider failover (30% of calls failed on purpose) | console Chaos, shop chat, console Gateway |
| 6 | Answer confidence | shop |
| 7 | Long-term memory (stored in the console, recalled by the shop) | console Memory, shop chat |
| 8 | Durable workflows (a refund) | shop orders, console run timeline |
| 9 | Saga compensation (undo when a step keeps failing) | shop orders |
| 10 | Evidence pipeline with a specialist model | shop back office |
| 11 | Decision provenance | X-ray |
| 12 | Command Centre | console |

## Recording it yourself

You need the whole stack running, with a real model key, on one machine:

1. Continuum backend with `CONTINUUM_DECLARATIVE_ALLOW_PRIVATE_TARGETS=true` and a
   `GEMINI_API_KEY` or `GROQ_API_KEY`.
2. The console: `cd frontend && npm run dev` (port 5173).
3. The shop, set up once and running: `cd demo-app && python setup_continuum.py --email you@example.com`,
   then `python server.py` (port 5050).

Then, on Linux (needs `Xvfb`, `ffmpeg`, Node and Playwright):

```sh
cd video/demo
export DEMO_EMAIL=you@example.com DEMO_PASSWORD='your console password'
./record.sh
```

The script signs the shop's presenter in, drives a real browser on a virtual 1920x1080
screen, and captures it with ffmpeg at 30 fps. It takes about eight minutes, because
every answer is a real model call. URLs can be changed with `SHOP_URL`, `CONSOLE_URL`
and `CONTINUUM_URL`.

For a clean take, the account should not already hold the memory the video stores, or
cached answers to the questions it asks; otherwise those steps show the earlier result.

## Files

- `overlay.js`: the on-screen direction (captions, chapter badge, spotlight and blur,
  arrows, pointer, title cards), injected into every page as `window.__demo`.
- `record.mjs`: the film itself, chapter by chapter.
- `record.sh`: virtual display, screen capture, trimming and chapter metadata.
