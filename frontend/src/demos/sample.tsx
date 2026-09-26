import { Gauge } from "../system/viz";
import { BeforeAfter } from "../system/charts";
import type { Tone } from "../system/hub";
import type { DemoSpec } from "../system/demo";
import { Beads, Big, HBar, Key, Pill, Quote, Scale, Squares, Verdict, pct, usd } from "./kit";

/**
 * Demos over sample data. These features act on live traffic — a provider
 * failing, a saga unwinding, a breaker opening — so the demo runs the
 * feature's rules here, on a fixed sample, instead of disturbing real state.
 * The rules are the ones the feature pages and docs describe; the samples are
 * chosen so each rule visibly fires.
 */

const done = <T,>(v: T) => Promise.resolve(v);

/* ---------------- Gateway: failover ----------------------------------- */

type Attempt = { provider: string; ms: number; outcome: "ok" | "timeout" | "rate-limited" | "error" };
const gateway: DemoSpec<{ prompt: string; attempts: Attempt[] }, Attempt[]> = {
  title: "Gateway failover",
  live: false,
  samples: [
    {
      label: "Primary times out",
      input: {
        prompt: "Summarise this support thread in two sentences.",
        attempts: [
          { provider: "openai · gpt-4o-mini", ms: 3000, outcome: "timeout" },
          { provider: "anthropic · haiku", ms: 820, outcome: "ok" },
        ],
      },
    },
    {
      label: "Rate limited twice",
      input: {
        prompt: "Translate the release notes into Spanish.",
        attempts: [
          { provider: "openai · gpt-4o-mini", ms: 120, outcome: "rate-limited" },
          { provider: "gemini · flash", ms: 95, outcome: "rate-limited" },
          { provider: "groq · llama-3.1-8b", ms: 410, outcome: "ok" },
        ],
      },
    },
    {
      label: "Healthy",
      input: { prompt: "Write a haiku about latency.", attempts: [{ provider: "openai · gpt-4o-mini", ms: 640, outcome: "ok" }] },
    },
  ],
  steps: ["Send to first provider", "Watch deadline", "Fail over", "Return answer"],
  run: ({ attempts }) => done(attempts),
  Input: ({ input }) => (
    <div className="space-y-3">
      <Quote>{input.prompt}</Quote>
      <div className="flex flex-wrap items-center gap-1.5 text-[12px] text-slate-400">
        Chain:
        {input.attempts.map((a, i) => (
          <span key={a.provider} className="inline-flex items-center gap-1.5">
            {i > 0 && "→"} <Pill tone="mute">{a.provider}</Pill>
          </span>
        ))}
      </div>
    </div>
  ),
  Output: ({ output }) => {
    const total = output.reduce((n, a) => n + a.ms, 0);
    const ok = output[output.length - 1].outcome === "ok";
    return (
      <div className="space-y-4">
        <Verdict tone={ok ? "ok" : "bad"} sub={`${output.length} attempt${output.length > 1 ? "s" : ""} · ${total} ms end to end`}>
          {ok ? "Answered" : "Failed"}
        </Verdict>
        <div className="space-y-1.5">
          {output.map((a, i) => {
            const start = output.slice(0, i).reduce((n, x) => n + x.ms, 0);
            const tone: Tone = a.outcome === "ok" ? "ok" : a.outcome === "timeout" ? "bad" : "warn";
            return (
              <div key={i} className="grid grid-cols-[8.5rem_1fr] items-center gap-2 text-[11.5px]">
                <span className="truncate text-slate-300">{a.provider}</span>
                <div className="relative h-5 rounded bg-edge/50">
                  <div
                    className="demo-grow absolute inset-y-0 grid place-items-center overflow-hidden rounded text-[10px] font-semibold text-white"
                    style={{ left: `${(start / total) * 100}%`, width: `${Math.max(3, (a.ms / total) * 100)}%`, background: `var(--state-${tone === "ok" ? "healthy" : tone === "bad" ? "critical" : "warning"}-ink)`, animationDelay: `${i * 250}ms` }}
                  >
                    {a.ms > total * 0.18 ? a.outcome : ""}
                  </div>
                </div>
              </div>
            );
          })}
        </div>
        <p className="text-[11px] text-slate-500">Time runs left to right; each bar is one attempt.</p>
      </div>
    );
  },
};

/* ---------------- Autopilot: policy proposal --------------------------- */

type Prov = { name: string; costPer1k: number; p95: number; quality: number };
const autopilot: DemoSpec<Prov[], { before: Prov[]; after: Prov[] }> = {
  title: "Optimization autopilot",
  live: false,
  samples: [
    {
      label: "Last 7 days of traffic",
      input: [
        { name: "gpt-4o", costPer1k: 5.0, p95: 2100, quality: 0.93 },
        { name: "haiku", costPer1k: 0.8, p95: 900, quality: 0.91 },
        { name: "llama-3.1-70b", costPer1k: 0.6, p95: 1400, quality: 0.84 },
      ],
    },
  ],
  steps: ["Read measured cost & quality", "Rank by value", "Guard quality floor", "Propose"],
  run: (provs) => {
    const floor = 0.88;
    const after = [...provs].sort((a, b) => {
      const ok = (p: Prov) => (p.quality >= floor ? 0 : 1);
      return ok(a) - ok(b) || a.costPer1k / a.quality - b.costPer1k / b.quality;
    });
    return done({ before: provs, after });
  },
  Input: ({ input }) => (
    <div className="space-y-3">
      {input.map((p, i) => (
        <div key={p.name} className="grid grid-cols-[6.5rem_1fr] items-center gap-2">
          <span className="text-[12px] text-slate-200">{p.name}</span>
          <div className="flex flex-wrap gap-1.5 text-[11px]">
            <Pill tone="mute">{usd(p.costPer1k)}/1k</Pill>
            <Pill tone="mute">p95 {p.p95} ms</Pill>
            <Pill tone={p.quality >= 0.88 ? "ok" : "warn"}>quality {pct(p.quality)}</Pill>
          </div>
          {i === 0 && <span className="sr-only">current first choice</span>}
        </div>
      ))}
    </div>
  ),
  Output: ({ output }) => {
    const mix = [0.7, 0.2, 0.1];
    const cost = (ps: Prov[]) => ps.reduce((n, p, i) => n + p.costPer1k * mix[i], 0) * 30;
    const p95 = (ps: Prov[]) => ps.reduce((n, p, i) => n + p.p95 * mix[i], 0);
    return (
      <div className="space-y-4">
        <div>
          <div className="micro mb-2">Provider order</div>
          <div className="grid grid-cols-2 gap-3 text-[12px]">
            {[["Now", output.before], ["Proposed", output.after]].map(([label, list]) => (
              <ol key={label as string} className="space-y-1">
                <li className="text-[11px] text-slate-500">{label as string}</li>
                {(list as Prov[]).map((p, i) => (
                  <li key={p.name} className="demo-pop flex items-center gap-2" style={{ animationDelay: `${i * 90}ms` }}>
                    <span className="grid h-5 w-5 place-items-center rounded-full bg-slate-500/15 text-[10px] font-bold text-slate-300">{i + 1}</span>
                    {p.name}
                  </li>
                ))}
              </ol>
            ))}
          </div>
        </div>
        <BeforeAfter before={cost(output.before)} after={cost(output.after)} beforeLabel="Monthly spend now" afterLabel="Proposed" format={(v) => `$${v.toFixed(0)}`} />
        <BeforeAfter before={p95(output.before)} after={p95(output.after)} beforeLabel="p95 latency now" afterLabel="Proposed" format={(v) => `${Math.round(v)} ms`} />
        <div className="flex flex-wrap gap-1.5">
          <Pill tone="info">starts as a 10% canary</Pill>
          <Pill tone="ok">rolls back if quality drops</Pill>
        </div>
      </div>
    );
  },
};

/* ---------------- Counterfactual --------------------------------------- */

type Logged = { prompt: string; complexity: number; spent: number };
const LOGGED: Logged[] = [
  { prompt: "Reset my password", complexity: 0.08, spent: 0.012 },
  { prompt: "What are your hours?", complexity: 0.05, spent: 0.011 },
  { prompt: "Explain this stack trace", complexity: 0.62, spent: 0.048 },
  { prompt: "Summarise a 30-page contract", complexity: 0.81, spent: 0.093 },
  { prompt: "Translate one sentence", complexity: 0.14, spent: 0.013 },
  { prompt: "Plan a data migration", complexity: 0.74, spent: 0.071 },
  { prompt: "Is this email spam?", complexity: 0.2, spent: 0.012 },
  { prompt: "Write a SQL window query", complexity: 0.47, spent: 0.031 },
];
const counterfactual: DemoSpec<{ at: number }, { rows: (Logged & { cheap: boolean; would: number })[] }> = {
  title: "Counterfactual replay",
  live: false,
  samples: [
    { label: "Cheap model below 0.30", input: { at: 0.3 } },
    { label: "Cheap model below 0.55", input: { at: 0.55 } },
  ],
  steps: ["Load logged requests", "Apply candidate policy", "Price each outcome"],
  run: ({ at }) => done({ rows: LOGGED.map((r) => ({ ...r, cheap: r.complexity < at, would: r.complexity < at ? r.spent * 0.12 : r.spent })) }),
  Input: ({ input }) => (
    <div className="space-y-3">
      <div className="text-[12px] text-slate-300">
        Policy: send anything simpler than <b>{input.at.toFixed(2)}</b> to the cheap model.
      </div>
      <ul className="space-y-1">
        {LOGGED.map((r) => (
          <li key={r.prompt} className="flex items-center justify-between gap-2 text-[11.5px]">
            <span className="truncate text-slate-300">{r.prompt}</span>
            <span className="readout text-slate-500">{r.complexity.toFixed(2)}</span>
          </li>
        ))}
      </ul>
    </div>
  ),
  Output: ({ input, output }) => {
    const before = output.rows.reduce((n, r) => n + r.spent, 0);
    const after = output.rows.reduce((n, r) => n + r.would, 0);
    return (
      <div className="space-y-4">
        <div className="relative h-8 rounded-lg bg-edge/40">
          <span aria-hidden className="absolute inset-y-0 w-0.5 bg-slate-300" style={{ left: `${input.at * 100}%` }} />
          {output.rows.map((r, i) => (
            <span
              key={r.prompt}
              title={r.prompt}
              className="demo-pop absolute top-1/2 h-3.5 w-3.5 -translate-x-1/2 -translate-y-1/2 rounded-full border-2 border-white"
              style={{ left: `${r.complexity * 100}%`, background: r.cheap ? "var(--state-healthy-ink)" : "var(--hue-violet-ink)", animationDelay: `${i * 60}ms` }}
            />
          ))}
        </div>
        <Key items={[{ tone: "ok", label: "would go to cheap model" }, { tone: "violet", label: "stays on current model" }]} />
        <BeforeAfter before={before} after={after} beforeLabel="What it cost" afterLabel="Under the policy" format={(v) => `$${v.toFixed(3)}`} />
        <Pill tone="info">No request was re-sent: priced from the log</Pill>
      </div>
    );
  },
};

/* ---------------- Admission -------------------------------------------- */

type Req = { id: string; cls: "CRITICAL" | "NORMAL" | "BACKGROUND" };
const POINTS = { BACKGROUND: 0.7, NORMAL: 1.0, CRITICAL: 1.3 };
const admission: DemoSpec<{ utilisation: number; reqs: Req[] }, { id: string; cls: string; admitted: boolean }[]> = {
  title: "Admission control",
  live: false,
  samples: [
    {
      label: "Provider at 85%",
      input: { utilisation: 0.85, reqs: [{ id: "checkout", cls: "CRITICAL" }, { id: "chat reply", cls: "NORMAL" }, { id: "nightly digest", cls: "BACKGROUND" }, { id: "search", cls: "NORMAL" }, { id: "re-index", cls: "BACKGROUND" }] },
    },
    {
      label: "Overloaded at 115%",
      input: { utilisation: 1.15, reqs: [{ id: "checkout", cls: "CRITICAL" }, { id: "chat reply", cls: "NORMAL" }, { id: "nightly digest", cls: "BACKGROUND" }, { id: "fraud check", cls: "CRITICAL" }] },
    },
  ],
  steps: ["Measure utilisation", "Compare with class limit", "Admit or shed"],
  run: ({ utilisation, reqs }) => done(reqs.map((r) => ({ ...r, admitted: utilisation < POINTS[r.cls] }))),
  Input: ({ input }) => (
    <div className="space-y-3">
      <Gauge value={input.utilisation} max={1.4} label="Provider utilisation" display={pct(input.utilisation)} warnAt={0.5} badAt={0.71} size={130} />
      <div className="flex flex-wrap gap-1.5">
        {input.reqs.map((r) => (
          <Pill key={r.id} tone={r.cls === "CRITICAL" ? "bad" : r.cls === "NORMAL" ? "info" : "mute"}>
            {r.id} · {r.cls.toLowerCase()}
          </Pill>
        ))}
      </div>
    </div>
  ),
  Output: ({ input, output }) => (
    <div className="space-y-4">
      <div className="relative pt-5">
        <div className="h-2 rounded-full bg-edge" />
        {(Object.keys(POINTS) as (keyof typeof POINTS)[]).map((k) => (
          <span key={k} className="absolute top-0 flex -translate-x-1/2 flex-col items-center text-[10px] text-slate-400" style={{ left: `${(POINTS[k] / 1.4) * 100}%` }}>
            {k.toLowerCase()}
            <span aria-hidden className="mt-0.5 h-3 w-px bg-slate-400" />
          </span>
        ))}
        <span className="demo-slide absolute top-[18px] h-4 w-1 -translate-x-1/2 rounded bg-[color:var(--accent-strong)]" style={{ left: `${(input.utilisation / 1.4) * 100}%` }} />
      </div>
      <ul className="space-y-1.5">
        {output.map((r, i) => (
          <li key={r.id} className="rise-in flex items-center justify-between gap-2 text-[12.5px]" style={{ animationDelay: `${i * 80}ms` }}>
            <span className="text-slate-200">{r.id}</span>
            <Pill tone={r.admitted ? "ok" : "bad"}>{r.admitted ? "admitted" : "shed"}</Pill>
          </li>
        ))}
      </ul>
      <p className="text-[11px] text-slate-500">Each class has its own limit: background work gives way first, critical work last.</p>
    </div>
  ),
};

/* ---------------- Cost limits ------------------------------------------ */

const costLimits: DemoSpec<{ rpm: number; tpm: number; burst: number[] }, { tokens: number; ok: boolean; why?: string; usedR: number; usedT: number }[]> = {
  title: "Cost limits",
  live: false,
  samples: [{ label: "A burst of large prompts", input: { rpm: 8, tpm: 12000, burst: [900, 1200, 4000, 800, 3500, 2600, 700, 400, 300, 500] } }],
  steps: ["Count requests", "Count tokens", "Refuse what doesn't fit"],
  run: ({ rpm, tpm, burst }) => {
    let r = 0;
    let t = 0;
    return done(
      burst.map((tokens) => {
        if (r + 1 > rpm) return { tokens, ok: false, why: "requests", usedR: r, usedT: t };
        if (t + tokens > tpm) return { tokens, ok: false, why: "tokens", usedR: r, usedT: t };
        r += 1;
        t += tokens;
        return { tokens, ok: true, usedR: r, usedT: t };
      }),
    );
  },
  Input: ({ input }) => (
    <div className="space-y-3">
      <div className="flex flex-wrap gap-1.5">
        <Pill tone="info">{input.rpm} requests / min</Pill>
        <Pill tone="info">{input.tpm.toLocaleString()} tokens / min</Pill>
      </div>
      <div className="flex h-20 items-end gap-1">
        {input.burst.map((t, i) => (
          <div key={i} className="flex-1 rounded-t bg-[color:var(--hue-blue-ink)]" style={{ height: `${(t / 4000) * 100}%` }} title={`${t} tokens`} />
        ))}
      </div>
      <div className="text-[11px] text-slate-500">Ten requests in one minute; bar height is prompt size.</div>
    </div>
  ),
  Output: ({ input, output }) => {
    const last = output.filter((o) => o.ok).pop();
    return (
      <div className="space-y-4">
        <div className="flex h-20 items-end gap-1">
          {output.map((o, i) => (
            <div
              key={i}
              className="demo-grow-y flex-1 rounded-t"
              title={o.ok ? "served" : `refused: ${o.why}`}
              style={{ height: `${(o.tokens / 4000) * 100}%`, background: o.ok ? "var(--state-healthy-ink)" : "var(--state-critical-ink)", animationDelay: `${i * 60}ms` }}
            />
          ))}
        </div>
        <Key items={[{ tone: "ok", label: "served" }, { tone: "bad", label: "refused (429)" }]} />
        <div className="flex flex-wrap justify-center gap-4">
          <Gauge value={last?.usedR ?? 0} max={input.rpm} label="Requests used" display={`${last?.usedR ?? 0}/${input.rpm}`} size={110} />
          <Gauge value={last?.usedT ?? 0} max={input.tpm} label="Tokens used" display={pct((last?.usedT ?? 0) / input.tpm)} size={110} />
        </div>
      </div>
    );
  },
};

/* ---------------- Cascade ---------------------------------------------- */

type CasIn = { threshold: number; prompts: { text: string; cheapConfidence: number }[] };
const cascade: DemoSpec<CasIn, { text: string; cheapConfidence: number; escalated: boolean }[]> = {
  title: "Model cascade",
  live: false,
  samples: [
    {
      label: "Four customer questions",
      input: {
        threshold: 0.75,
        prompts: [
          { text: "What time do you open?", cheapConfidence: 0.96 },
          { text: "Can I change my delivery address?", cheapConfidence: 0.88 },
          { text: "Why was I charged tax twice on an EU order?", cheapConfidence: 0.52 },
          { text: "Is the blender dishwasher safe?", cheapConfidence: 0.91 },
        ],
      },
    },
  ],
  steps: ["Ask the cheap model", "Check its confidence", "Escalate the unsure ones"],
  run: ({ threshold, prompts }) => done(prompts.map((p) => ({ ...p, escalated: p.cheapConfidence < threshold }))),
  Input: ({ input }) => (
    <ul className="space-y-1.5">
      {input.prompts.map((p) => (
        <li key={p.text} className="text-[12px] text-slate-300">
          {p.text}
        </li>
      ))}
    </ul>
  ),
  Output: ({ input, output }) => {
    const cheap = 0.0004;
    const strong = 0.006;
    const before = output.length * strong;
    const after = output.reduce((n, o) => n + cheap + (o.escalated ? strong : 0), 0);
    return (
      <div className="space-y-4">
        <div className="space-y-3">
          {output.map((o, i) => (
            <HBar
              key={o.text}
              label={o.text}
              value={o.cheapConfidence}
              max={1}
              marker={input.threshold}
              note={o.escalated ? "→ strong model" : "cheap model"}
              tone={o.escalated ? "warn" : "ok"}
              delay={i * 90}
            />
          ))}
        </div>
        <BeforeAfter before={before} after={after} beforeLabel="All on strong model" afterLabel="With the cascade" format={(v) => `$${v.toFixed(4)}`} />
      </div>
    );
  },
};

/* ---------------- Durable workflow ------------------------------------- */

const STEPS = ["Reserve stock", "Charge card", "Create shipment", "Email receipt"];
const workflow: DemoSpec<{ crashAt: number }, { crashAt: number }> = {
  title: "Durable workflow",
  live: false,
  samples: [
    { label: "Server dies during step 3", input: { crashAt: 2 } },
    { label: "No failure", input: { crashAt: -1 } },
  ],
  steps: ["Log each step", "Crash", "Restart", "Resume from the log"],
  run: (i) => done(i),
  Input: ({ input }) => (
    <div className="space-y-3">
      <Beads steps={STEPS.map((s) => ({ label: s, tone: "mute" }))} />
      {input.crashAt >= 0 && <Pill tone="bad">the process is killed during “{STEPS[input.crashAt]}”</Pill>}
    </div>
  ),
  Output: ({ output }) => (
    <div className="space-y-4">
      <div>
        <div className="micro mb-2">First run</div>
        <Beads
          steps={STEPS.map((s, i) => ({
            label: s,
            tone: output.crashAt < 0 || i < output.crashAt ? "ok" : i === output.crashAt ? "bad" : "mute",
            note: output.crashAt >= 0 && i === output.crashAt ? "crashed" : undefined,
          }))}
        />
      </div>
      {output.crashAt >= 0 && (
        <div>
          <div className="micro mb-2">After restart</div>
          <Beads
            steps={STEPS.map((s, i) => ({
              label: s,
              tone: i < output.crashAt ? "mute" : "ok",
              note: i < output.crashAt ? "from log, not re-run" : "run",
            }))}
          />
        </div>
      )}
      <div className="flex flex-wrap gap-1.5">
        <Pill tone="ok">card charged exactly once</Pill>
        <Pill tone="info">{output.crashAt >= 0 ? `${STEPS.length - output.crashAt} steps run after restart` : "all steps logged"}</Pill>
      </div>
    </div>
  ),
};

/* ---------------- Saga ------------------------------------------------- */

const SAGA = [
  { step: "Book flight", undo: "Cancel flight" },
  { step: "Book hotel", undo: "Cancel hotel" },
  { step: "Rent car", undo: "Cancel car" },
  { step: "Charge card", undo: "Refund card" },
];
const saga: DemoSpec<{ failAt: number }, { failAt: number }> = {
  title: "Compensation (saga)",
  live: false,
  samples: [
    { label: "Car rental fails", input: { failAt: 2 } },
    { label: "Payment declined", input: { failAt: 3 } },
  ],
  steps: ["Run steps forward", "Hit a failure", "Undo in reverse"],
  run: (i) => done(i),
  Input: ({ input }) => (
    <div className="space-y-3">
      <Beads steps={SAGA.map((s) => ({ label: s.step, tone: "mute" }))} />
      <Pill tone="bad">“{SAGA[input.failAt].step}” will fail</Pill>
    </div>
  ),
  Output: ({ output }) => (
    <div className="space-y-4">
      <div>
        <div className="micro mb-2">Forward</div>
        <Beads steps={SAGA.map((s, i) => ({ label: s.step, tone: i < output.failAt ? "ok" : i === output.failAt ? "bad" : "mute", note: i === output.failAt ? "failed" : i > output.failAt ? "never ran" : undefined }))} />
      </div>
      <div>
        <div className="micro mb-2">Compensation, newest first</div>
        <Beads steps={SAGA.slice(0, output.failAt).reverse().map((s) => ({ label: s.undo, tone: "warn" }))} />
      </div>
      <Pill tone="ok">nothing left half-booked</Pill>
    </div>
  ),
};

/* ---------------- Replay audit ----------------------------------------- */

type RStep = { name: string; original: string; replay: string; volatile?: boolean };
const replay: DemoSpec<RStep[], RStep[]> = {
  title: "Replay audit",
  live: false,
  samples: [
    {
      label: "A recorded support run",
      input: [
        { name: "classify", original: "billing", replay: "billing" },
        { name: "lookup order", original: "#4821", replay: "#4821" },
        { name: "timestamp", original: "10:02:11", replay: "14:37:52", volatile: true },
        { name: "draft reply", original: "Refund issued…", replay: "Refund issued…" },
        { name: "tone check", original: "friendly", replay: "friendly" },
        { name: "choose model", original: "haiku", replay: "gpt-4o-mini" },
      ],
    },
  ],
  steps: ["Load the event log", "Re-run each step", "Compare outputs", "Explain differences"],
  run: (s) => done(s),
  Input: ({ input }) => (
    <ul className="space-y-1">
      {input.map((s) => (
        <li key={s.name} className="flex justify-between gap-2 text-[12px]">
          <span className="text-slate-300">{s.name}</span>
          <span className="readout text-slate-500">{s.original}</span>
        </li>
      ))}
    </ul>
  ),
  Output: ({ output }) => {
    const same = output.filter((s) => s.original === s.replay).length;
    const explained = output.filter((s) => s.original !== s.replay && s.volatile).length;
    return (
      <div className="space-y-4">
        <div className="grid items-center gap-4 sm:grid-cols-[auto_1fr]">
          <Gauge value={same} max={output.length} label="Reproduced" display={`${same}/${output.length}`} tone={same === output.length ? "ok" : "warn"} size={120} />
          <div>
            <Squares
              items={output.map((s) => ({
                tone: s.original === s.replay ? "ok" : s.volatile ? "info" : "bad",
                label: `${s.name}: ${s.original === s.replay ? "same" : s.volatile ? "expected to differ" : "diverged"}`,
              }))}
              size={24}
            />
            <Key items={[{ tone: "ok", label: "same" }, { tone: "info", label: "expected to differ" }, { tone: "bad", label: "diverged" }]} />
          </div>
        </div>
        <ul className="space-y-1.5">
          {output
            .filter((s) => s.original !== s.replay)
            .map((s) => (
              <li key={s.name} className="rounded-lg border border-edge/70 p-2 text-[11.5px]">
                <div className="font-medium text-slate-200">{s.name}</div>
                <div className="text-slate-400">
                  {s.original} → {s.replay}
                </div>
              </li>
            ))}
        </ul>
        <Pill tone={explained < output.length - same ? "bad" : "ok"}>
          {output.length - same - explained} unexplained divergence{output.length - same - explained === 1 ? "" : "s"}
        </Pill>
      </div>
    );
  },
};

/* ---------------- DAG verification ------------------------------------- */

type Claim = { text: string; evidence?: string; supported: boolean };
const dag: DemoSpec<{ question: string; claims: Claim[] }, Claim[]> = {
  title: "Answer verification",
  live: false,
  samples: [
    {
      label: "Contract question",
      input: {
        question: "What are the termination terms in the Acme contract?",
        claims: [
          { text: "Either party may end it with 60 days' notice", evidence: "§12.1 “…sixty (60) days' written notice…”", supported: true },
          { text: "There is no early-termination fee", evidence: "§12.3 “No fee shall apply…”", supported: true },
          { text: "It renews automatically every two years", supported: false },
        ],
      },
    },
  ],
  steps: ["Split the answer into claims", "Find evidence for each", "Score support"],
  run: ({ claims }) => done(claims),
  Input: ({ input }) => (
    <div className="space-y-2">
      <Quote>{input.question}</Quote>
      <div className="text-[11px] text-slate-500">Draft answer: {input.claims.map((c) => c.text).join(". ")}.</div>
    </div>
  ),
  Output: ({ output }) => {
    const ok = output.filter((c) => c.supported).length;
    return (
      <div className="space-y-4">
        <div className="grid items-center gap-4 sm:grid-cols-[auto_1fr]">
          <Gauge value={ok} max={output.length} label="Claims supported" display={`${ok}/${output.length}`} tone={ok === output.length ? "ok" : "warn"} size={120} />
          <Verdict tone={ok === output.length ? "ok" : "warn"} sub={ok === output.length ? "" : "unsupported claim removed"}>
            {ok === output.length ? "Verified" : "Corrected"}
          </Verdict>
        </div>
        <ul className="space-y-2">
          {output.map((c, i) => (
            <li key={i} className="rise-in rounded-lg border border-edge/70 p-2.5" style={{ animationDelay: `${i * 90}ms` }}>
              <div className="flex items-start justify-between gap-2">
                <span className={`text-[12px] ${c.supported ? "text-slate-200" : "text-slate-500 line-through"}`}>{c.text}</span>
                <Pill tone={c.supported ? "ok" : "bad"}>{c.supported ? "supported" : "no evidence"}</Pill>
              </div>
              {c.evidence && <div className="mt-1 text-[11px] text-slate-400">{c.evidence}</div>}
            </li>
          ))}
        </ul>
      </div>
    );
  },
};

/* ---------------- Pipelines & specialists ------------------------------ */

function Receipt() {
  return (
    <svg viewBox="0 0 120 150" className="h-36 w-auto rounded-lg border border-edge/70 bg-white" aria-label="A photographed receipt">
      <text x="12" y="20" fontSize="9" fontWeight="700" fill="#111">CORNER CAFÉ</text>
      {[["Flat white", "4.20"], ["Croissant", "3.10"], ["Orange juice", "3.90"]].map(([k, v], i) => (
        <g key={k}>
          <text x="12" y={44 + i * 14} fontSize="7" fill="#333">{k}</text>
          <text x="92" y={44 + i * 14} fontSize="7" fill="#333">{v}</text>
        </g>
      ))}
      <line x1="12" x2="108" y1="92" y2="92" stroke="#999" strokeDasharray="2 2" />
      <text x="12" y="106" fontSize="8" fontWeight="700" fill="#111">TOTAL</text>
      <text x="88" y="106" fontSize="8" fontWeight="700" fill="#111">11.20</text>
      <text x="12" y="126" fontSize="6" fill="#666">VAT incl. · 12 Mar</text>
    </svg>
  );
}
const pipelines: DemoSpec<{ question: string }, { fields: [string, string, number][]; answer: string }> = {
  title: "Pipeline run",
  live: false,
  samples: [{ label: "Receipt photo + question", input: { question: "How much did I spend, and on what?" } }],
  steps: ["Detect input type", "Call OCR specialist", "Build evidence", "Answer from evidence"],
  run: () =>
    done({
      fields: [
        ["Flat white", "4.20", 0.98],
        ["Croissant", "3.10", 0.97],
        ["Orange juice", "3.90", 0.95],
        ["Total", "11.20", 0.99],
      ],
      answer: "You spent £11.20: a flat white (£4.20), a croissant (£3.10) and an orange juice (£3.90).",
    }),
  Input: ({ input }) => (
    <div className="flex flex-wrap items-start gap-3">
      <Receipt />
      <div className="min-w-0 flex-1">
        <Quote>{input.question}</Quote>
      </div>
    </div>
  ),
  Output: ({ output }) => (
    <div className="space-y-4">
      <div>
        <div className="micro mb-2">Evidence the model is given</div>
        <div className="space-y-2.5">
          {output.fields.map(([k, v, c], i) => (
            <HBar key={k} label={`${k} · ${v}`} value={c} max={1} note={`read at ${pct(c)}`} tone="ok" delay={i * 80} />
          ))}
        </div>
      </div>
      <div>
        <div className="micro mb-1.5">Answer</div>
        <Quote>{output.answer}</Quote>
      </div>
      <Pill tone="info">the model never sees the raw image</Pill>
    </div>
  ),
};

type Ev = { input: string; specialist: string; result: string; confidence: number | null };
const specialists: DemoSpec<Ev[], Ev[]> = {
  title: "Specialists",
  live: false,
  samples: [
    {
      label: "Three kinds of input",
      input: [
        { input: "photo of a dented car door", specialist: "Roboflow · damage detector", result: "dent · front-left door", confidence: 0.91 },
        { input: "voicemail.m4a (42 s)", specialist: "Deepgram · transcription", result: "“Hi, calling about my claim…”", confidence: null },
        { input: "scanned invoice.pdf", specialist: "OCR · document reader", result: "Invoice INV-2291 · €1,480.00", confidence: 0.97 },
      ],
    },
  ],
  steps: ["Recognise each input", "Pick a specialist", "Collect evidence"],
  run: (e) => done(e),
  Input: ({ input }) => (
    <ul className="space-y-1.5">
      {input.map((e) => (
        <li key={e.input} className="text-[12px] text-slate-300">
          {e.input}
        </li>
      ))}
    </ul>
  ),
  Output: ({ output }) => (
    <ul className="space-y-2.5">
      {output.map((e, i) => (
        <li key={e.input} className="rise-in rounded-xl border border-edge/70 p-2.5" style={{ animationDelay: `${i * 100}ms` }}>
          <div className="flex flex-wrap items-center justify-between gap-2">
            <span className="text-[11px] text-slate-400">{e.specialist}</span>
            {e.confidence === null ? <Pill tone="mute">no score measured</Pill> : <Pill tone="ok">confidence {pct(e.confidence)}</Pill>}
          </div>
          <div className="mt-1 text-[12.5px] text-slate-100">{e.result}</div>
        </li>
      ))}
      <li className="text-[11px] text-slate-500">Only measured scores are shown; a transcript is not given an invented one.</li>
    </ul>
  ),
};

/* ---------------- MMU -------------------------------------------------- */

type Chunk = { name: string; relevance: number; recency: number; tokens: number };
const mmu: DemoSpec<{ budget: number; chunks: Chunk[] }, (Chunk & { score: number; kept: boolean })[]> = {
  title: "Context optimizer",
  live: false,
  samples: [
    {
      label: "8 chunks, room for ~3,000 tokens",
      input: {
        budget: 3000,
        chunks: [
          { name: "system prompt", relevance: 1, recency: 1, tokens: 400 },
          { name: "user's last question", relevance: 0.95, recency: 1, tokens: 120 },
          { name: "order #4821 details", relevance: 0.9, recency: 0.6, tokens: 700 },
          { name: "refund policy", relevance: 0.8, recency: 0.3, tokens: 900 },
          { name: "chat from yesterday", relevance: 0.35, recency: 0.5, tokens: 1100 },
          { name: "shipping FAQ", relevance: 0.3, recency: 0.2, tokens: 800 },
          { name: "greeting small talk", relevance: 0.1, recency: 0.9, tokens: 200 },
          { name: "old support ticket", relevance: 0.25, recency: 0.05, tokens: 1200 },
        ],
      },
    },
  ],
  steps: ["Score relevance (70%)", "Score recency (30%)", "Fill the window", "Page the rest out"],
  run: ({ budget, chunks }) => {
    const scored = chunks.map((c) => ({ ...c, score: 0.7 * c.relevance + 0.3 * c.recency, kept: false })).sort((a, b) => b.score - a.score);
    let used = 0;
    for (const c of scored) if (used + c.tokens <= budget) ((c.kept = true), (used += c.tokens));
    return done(scored);
  },
  Input: ({ input }) => (
    <ul className="space-y-1">
      {input.chunks.map((c) => (
        <li key={c.name} className="flex justify-between gap-2 text-[12px]">
          <span className="text-slate-300">{c.name}</span>
          <span className="readout text-slate-500">{c.tokens} tok</span>
        </li>
      ))}
    </ul>
  ),
  Output: ({ input, output }) => {
    const used = output.filter((c) => c.kept).reduce((n, c) => n + c.tokens, 0);
    return (
      <div className="space-y-4">
        <div className="space-y-2.5">
          {output.map((c, i) => (
            <HBar key={c.name} label={c.name} value={c.score} max={1} note={c.kept ? "in window" : "paged out"} tone={c.kept ? "ok" : "mute"} delay={i * 60} />
          ))}
        </div>
        <Gauge value={used} max={input.budget} label="Window used" display={`${(used / 1000).toFixed(1)}k`} sub={`of ${(input.budget / 1000).toFixed(1)}k tokens`} size={120} />
      </div>
    );
  },
};

/* ---------------- Memory ----------------------------------------------- */

type Fact = { text: string; tier: "working" | "episodic" | "semantic"; match: number };
const memory: DemoSpec<{ query: string; facts: Fact[] }, Fact[]> = {
  title: "Memory",
  live: false,
  samples: [
    {
      label: "What does the user prefer?",
      input: {
        query: "How should I format the report for Sam?",
        facts: [
          { text: "Sam prefers bullet points over prose", tier: "semantic", match: 0.86 },
          { text: "Last week Sam asked for charts in the appendix", tier: "episodic", match: 0.71 },
          { text: "Current task: Q3 revenue report", tier: "working", match: 0.64 },
          { text: "Sam's timezone is CET", tier: "semantic", match: 0.22 },
          { text: "Sam liked the blue theme in May", tier: "episodic", match: 0.35 },
        ],
      },
    },
  ],
  steps: ["Embed the question", "Search all tiers", "Rank and return"],
  run: ({ facts }) => done([...facts].sort((a, b) => b.match - a.match)),
  Input: ({ input }) => (
    <div className="space-y-3">
      <Quote>{input.query}</Quote>
      <div className="text-[11px] text-slate-500">{input.facts.length} facts stored across working, episodic and semantic memory.</div>
    </div>
  ),
  Output: ({ output }) => {
    const tone = { working: "info", episodic: "warn", semantic: "violet" } as const;
    return (
      <div className="space-y-3">
        {output.map((f, i) => (
          <div key={f.text} className="flex items-start gap-2">
            <Pill tone={tone[f.tier]}>{f.tier}</Pill>
            <div className="min-w-0 flex-1">
              <HBar label={f.text} value={f.match} max={1} note={f.match.toFixed(2)} tone={i < 3 ? "ok" : "mute"} delay={i * 70} />
            </div>
          </div>
        ))}
        <Pill tone="ok">top 3 go into the prompt</Pill>
      </div>
    );
  },
};

/* ---------------- Quality gate ----------------------------------------- */

type Dim = { name: string; before: number; after: number };
const quality: DemoSpec<{ question: string; answer: string; dims: Dim[] }, Dim[]> = {
  title: "Quality gate",
  live: false,
  samples: [
    {
      label: "An incomplete answer",
      input: {
        question: "List three ways to reduce cold-start time in AWS Lambda.",
        answer: "Use provisioned concurrency.",
        dims: [
          { name: "Completeness", before: 0.33, after: 0.95 },
          { name: "Relevance", before: 0.9, after: 0.93 },
          { name: "Format", before: 0.6, after: 0.92 },
          { name: "Grounding", before: 0.85, after: 0.88 },
        ],
      },
    },
  ],
  steps: ["Score each dimension", "Find the defect", "Ask for a repair", "Re-score"],
  run: ({ dims }) => done(dims),
  Input: ({ input }) => (
    <div className="space-y-2">
      <Quote>{input.question}</Quote>
      <div className="text-[11px] text-slate-500">Model's answer:</div>
      <Quote>{input.answer}</Quote>
    </div>
  ),
  Output: ({ output }) => {
    const avg = (k: "before" | "after") => output.reduce((n, d) => n + d[k], 0) / output.length;
    return (
      <div className="space-y-4">
        <div className="space-y-2.5">
          {output.map((d, i) => (
            <div key={d.name} className="grid grid-cols-2 gap-3">
              <HBar label={d.name} value={d.before} max={1} note={d.before.toFixed(2)} tone={d.before < 0.7 ? "bad" : "mute"} marker={0.7} delay={i * 60} />
              <HBar label="after repair" value={d.after} max={1} note={d.after.toFixed(2)} tone="ok" marker={0.7} delay={300 + i * 60} />
            </div>
          ))}
        </div>
        <BeforeAfter before={avg("before")} after={avg("after")} beforeLabel="Score" afterLabel="After one repair" goodDirection="up" format={(v) => v.toFixed(2)} />
        <Pill tone="info">repair asked for: “give all three ways, as a list”</Pill>
      </div>
    );
  },
};

/* ---------------- Confidence ------------------------------------------- */

const confidence: DemoSpec<{ question: string; samples: string[] }, { clusters: [string, number][]; conf: number }> = {
  title: "Confidence",
  live: false,
  samples: [
    { label: "Models agree", input: { question: "What is the boiling point of water at sea level in °C?", samples: ["100", "100", "100", "100", "100"] } },
    { label: "Models disagree", input: { question: "Which year did the company first turn a profit?", samples: ["2019", "2021", "2019", "2018", "2021"] } },
  ],
  steps: ["Ask 5 times", "Group equal answers", "Measure agreement"],
  run: ({ samples }) => {
    const m = new Map<string, number>();
    samples.forEach((s) => m.set(s, (m.get(s) ?? 0) + 1));
    const clusters = [...m.entries()].sort((a, b) => b[1] - a[1]);
    return done({ clusters, conf: clusters[0][1] / samples.length });
  },
  Input: ({ input }) => <Quote>{input.question}</Quote>,
  Output: ({ input, output }) => {
    const hue: Tone[] = ["info", "violet", "amber", "pink"];
    const idx = (s: string) => output.clusters.findIndex(([k]) => k === s);
    const high = output.conf >= 0.6;
    return (
      <div className="space-y-4">
        <div>
          <div className="micro mb-2">Five samples</div>
          <Squares items={input.samples.map((s) => ({ tone: hue[idx(s) % hue.length], label: s, mark: "" }))} size={30} step={90} />
          <Key items={output.clusters.map(([k, n], i) => ({ tone: hue[i % hue.length], label: `${k} ×${n}` }))} />
        </div>
        <div className="grid items-center gap-4 sm:grid-cols-[auto_1fr]">
          <Gauge value={output.conf} max={1} label="Confidence" display={pct(output.conf)} tone={high ? "ok" : "warn"} size={120} />
          <Verdict tone={high ? "ok" : "warn"} sub={high ? `answers “${output.clusters[0][0]}”` : "says it isn't sure, instead of guessing"}>
            {high ? "Confident" : "Low confidence"}
          </Verdict>
        </div>
      </div>
    );
  },
};

/* ---------------- Breaker ---------------------------------------------- */

const TRACE = [0.91, 0.9, 0.92, 0.89, 0.9, 0.88, 0.7, 0.62, 0.58, 0.6, 0.66, 0.8, 0.88, 0.9, 0.91, 0.9];
const breaker: DemoSpec<number[], { states: ("closed" | "open" | "half-open")[] }> = {
  title: "Semantic breaker",
  live: false,
  samples: [{ label: "A model's answers get worse", input: TRACE }],
  steps: ["Track answer quality", "Compare with baseline", "Open, then probe", "Close when recovered"],
  run: (trace) => {
    const base = 0.9;
    let st: "closed" | "open" | "half-open" = "closed";
    const states = trace.map((q) => {
      if (st === "closed" && q < base - 0.15) st = "open";
      else if (st === "open" && q > base - 0.15) st = "half-open";
      else if (st === "half-open" && q >= base - 0.03) st = "closed";
      return st;
    });
    return done({ states });
  },
  Input: ({ input }) => (
    <div>
      <div className="flex h-24 items-end gap-1">
        {input.map((q, i) => (
          <div key={i} className="flex-1 rounded-t bg-[color:var(--hue-blue-ink)]" style={{ height: `${q * 100}%` }} title={q.toFixed(2)} />
        ))}
      </div>
      <div className="mt-1 text-[11px] text-slate-500">Quality score of 16 consecutive answers from one model.</div>
    </div>
  ),
  Output: ({ input, output }) => {
    const tone = { closed: "ok", open: "bad", "half-open": "warn" } as const;
    return (
      <div className="space-y-4">
        <div className="relative">
          <div className="flex h-24 items-end gap-1">
            {input.map((q, i) => (
              <div key={i} className="demo-grow-y flex-1 rounded-t" style={{ height: `${q * 100}%`, background: `var(--state-${tone[output.states[i]] === "ok" ? "healthy" : tone[output.states[i]] === "bad" ? "critical" : "warning"}-ink)`, animationDelay: `${i * 40}ms` }} />
            ))}
          </div>
          <span aria-hidden className="absolute inset-x-0 border-t border-dashed border-slate-400" style={{ bottom: `${0.75 * 100}%` }} />
        </div>
        <Key items={[{ tone: "ok", label: "closed — serving" }, { tone: "bad", label: "open — traffic moved away" }, { tone: "warn", label: "half-open — probing" }]} />
        <Pill tone="info">dashed line: the opening threshold (baseline − 0.15)</Pill>
      </div>
    );
  },
};

/* ---------------- Provenance ------------------------------------------- */

type Stage = { name: string; ms: number; decision: string; tone: Tone };
const provenance: DemoSpec<string, Stage[]> = {
  title: "Decision provenance",
  live: false,
  samples: [{ label: "One request, end to end", input: "Where is my order #4821?" }],
  steps: ["Record each decision", "Time it", "Assemble the trail"],
  run: () =>
    done([
      { name: "Firewall", ms: 2, decision: "1 order id kept, no PII", tone: "ok" },
      { name: "Cache", ms: 1, decision: "miss (best 0.71 < 0.92)", tone: "mute" },
      { name: "Admission", ms: 1, decision: "admitted at 42%", tone: "ok" },
      { name: "Router", ms: 3, decision: "haiku → gpt-4o-mini", tone: "info" },
      { name: "Provider", ms: 640, decision: "haiku answered", tone: "violet" },
      { name: "Quality gate", ms: 12, decision: "0.91 — passed", tone: "ok" },
    ]),
  Input: ({ input }) => <Quote>{input}</Quote>,
  Output: ({ output }) => {
    const total = output.reduce((n, s) => n + s.ms, 0);
    let at = 0;
    return (
      <div className="space-y-2">
        {output.map((s, i) => {
          const left = at;
          at += s.ms;
          return (
            <div key={s.name} className="grid grid-cols-[6rem_1fr] items-center gap-2 text-[11.5px]">
              <span className="text-slate-300">{s.name}</span>
              <div>
                <div className="relative h-3 rounded bg-edge/50">
                  <div className="demo-grow absolute inset-y-0 rounded" style={{ left: `${(left / total) * 100}%`, width: `${Math.max(1.2, (s.ms / total) * 100)}%`, background: `var(--state-active-ink)`, animationDelay: `${i * 120}ms` }} />
                </div>
                <div className="mt-0.5 flex justify-between gap-2 text-[10.5px] text-slate-500">
                  <span className="truncate">{s.decision}</span>
                  <span className="readout">{s.ms} ms</span>
                </div>
              </div>
            </div>
          );
        })}
        <div className="pt-1 text-[11px] text-slate-500">{total} ms total — every decision above can be asked “why?”.</div>
      </div>
    );
  },
};

/* ---------------- Chaos ------------------------------------------------ */

const chaos: DemoSpec<{ rate: number; n: number }, ("first" | "retry" | "failover" | "failed")[]> = {
  title: "Fault injection",
  live: false,
  samples: [
    { label: "30% of calls time out", input: { rate: 0.3, n: 40 } },
    { label: "60% of calls time out", input: { rate: 0.6, n: 40 } },
  ],
  steps: ["Inject faults", "Retry once", "Fail over", "Count outcomes"],
  run: ({ rate, n }) => {
    let seed = Math.round(rate * 1000) + 7;
    const rnd = () => ((seed = (seed * 16807) % 2147483647) / 2147483647);
    return done(
      Array.from({ length: n }, () => {
        if (rnd() >= rate) return "first" as const;
        if (rnd() >= rate) return "retry" as const;
        return rnd() >= 0.08 ? ("failover" as const) : ("failed" as const);
      }),
    );
  },
  Input: ({ input }) => (
    <div className="space-y-3">
      <Gauge value={input.rate} max={1} label="Injected failure rate" display={pct(input.rate)} size={130} warnAt={0.2} badAt={0.5} />
      <div className="text-[11px] text-slate-500">{input.n} requests go through the gateway.</div>
    </div>
  ),
  Output: ({ output }) => {
    const ok = output.filter((o) => o !== "failed").length;
    const tone = { first: "ok", retry: "info", failover: "warn", failed: "bad" } as const;
    return (
      <div className="space-y-4">
        <Squares items={output.map((o) => ({ tone: tone[o], label: o }))} step={20} />
        <Key items={[{ tone: "ok", label: "first try" }, { tone: "info", label: "after a retry" }, { tone: "warn", label: "after failover" }, { tone: "bad", label: "failed" }]} />
        <Big label="Served" value={pct(ok / output.length)} tone={ok / output.length > 0.95 ? "ok" : "warn"} sub={`${ok} of ${output.length} requests`} />
      </div>
    );
  },
};

type Fault = { kind: string; caughtBy: string | null };
const aiChaos: DemoSpec<Fault[], Fault[]> = {
  title: "Model failures",
  live: false,
  samples: [
    {
      label: "Twelve corrupted answers",
      input: [
        ...Array(4).fill({ kind: "hallucinated fact", caughtBy: "verification" }),
        ...Array(3).fill({ kind: "broken JSON", caughtBy: "schema check" }),
        ...Array(2).fill({ kind: "injection in output", caughtBy: "firewall" }),
        { kind: "truncated answer", caughtBy: "quality gate" },
        { kind: "truncated answer", caughtBy: "quality gate" },
        { kind: "off-topic answer", caughtBy: null },
      ],
    },
  ],
  steps: ["Corrupt model output", "Run the guards", "Tally what got through"],
  run: (f) => done(f),
  Input: ({ input }) => {
    const kinds = [...new Set(input.map((f) => f.kind))];
    return (
      <div className="flex flex-wrap gap-1.5">
        {kinds.map((k) => (
          <Pill key={k} tone="bad">
            {k} ×{input.filter((f) => f.kind === k).length}
          </Pill>
        ))}
      </div>
    );
  },
  Output: ({ output }) => {
    const caught = output.filter((f) => f.caughtBy).length;
    const by = [...new Set(output.map((f) => f.caughtBy).filter(Boolean))] as string[];
    return (
      <div className="space-y-4">
        <div className="grid items-center gap-4 sm:grid-cols-[auto_1fr]">
          <Gauge value={caught} max={output.length} label="Caught" display={`${caught}/${output.length}`} tone={caught === output.length ? "ok" : "warn"} size={120} />
          <div className="space-y-2.5">
            {by.map((b, i) => {
              const n = output.filter((f) => f.caughtBy === b).length;
              return <HBar key={b} label={b} value={n} max={output.length} note={`${n}`} tone="ok" delay={i * 80} />;
            })}
          </div>
        </div>
        {caught < output.length && <Pill tone="bad">{output.length - caught} got through: {output.filter((f) => !f.caughtBy).map((f) => f.kind).join(", ")}</Pill>}
      </div>
    );
  },
};

/* ---------------- Adaptive policy (God mode) --------------------------- */

type Signal = { label: string; value: string; tone: Tone };
const godmode: DemoSpec<Signal[], { setting: string; from: string; to: string }[]> = {
  title: "Adaptive policy",
  live: false,
  samples: [
    {
      label: "Error spike on one provider",
      input: [
        { label: "openai error rate", value: "18% (usually 0.4%)", tone: "bad" },
        { label: "p95 latency", value: "4.1 s (usually 1.2 s)", tone: "warn" },
        { label: "cache hit rate", value: "31%", tone: "mute" },
      ],
    },
  ],
  steps: ["Read live signals", "Simulate candidate policies", "Pick the safest", "Apply with rollback"],
  run: () =>
    done([
      { setting: "Primary provider", from: "openai", to: "anthropic" },
      { setting: "Hedging", from: "off", to: "on after 1.5 s" },
      { setting: "Cache threshold", from: "0.92", to: "0.88" },
    ]),
  Input: ({ input }) => (
    <ul className="space-y-1.5">
      {input.map((s) => (
        <li key={s.label} className="flex items-center justify-between gap-2 text-[12px]">
          <span className="text-slate-300">{s.label}</span>
          <Pill tone={s.tone}>{s.value}</Pill>
        </li>
      ))}
    </ul>
  ),
  Output: ({ output }) => (
    <div className="space-y-3">
      {output.map((c, i) => (
        <div key={c.setting} className="rise-in grid grid-cols-[1fr_auto] items-center gap-2 rounded-lg border border-edge/70 p-2.5 text-[12px]" style={{ animationDelay: `${i * 100}ms` }}>
          <span className="text-slate-200">{c.setting}</span>
          <span className="flex items-center gap-1.5">
            <Pill tone="mute">{c.from}</Pill>→<Pill tone="accent">{c.to}</Pill>
          </span>
        </div>
      ))}
      <Scale value={0.18} left="low risk" right="high risk" tone="ok" />
      <Pill tone="ok">reverts on its own if error rate doesn't fall</Pill>
    </div>
  ),
};

/* ---------------- Models: a retirement caught -------------------------- */

type Known = { id: string; seen: boolean; isDefault?: boolean };
type Listed = { id: string; kind: "chat" | "speech" | "safety"; preview?: boolean; answers: "ok" | "no-free-quota" };
type Row = { id: string; outcome: "retired" | "in use" | "not free" | "not for chat"; note: string };
const models: DemoSpec<{ known: Known[]; listed: Listed[] }, { rows: Row[]; defaultModel: string; replaced: string; lists: number; tests: number }> = {
  title: "Model catalogue check",
  live: false,
  samples: [
    {
      label: "Groq retires Llama 3.3",
      input: {
        known: [
          { id: "llama-3.3-70b-versatile", seen: false, isDefault: true },
          { id: "llama-3.1-8b-instant", seen: false },
        ],
        listed: [
          { id: "openai/gpt-oss-120b", kind: "chat", answers: "ok" },
          { id: "openai/gpt-oss-20b", kind: "chat", answers: "ok" },
          { id: "qwen/qwen3.6-27b", kind: "chat", preview: true, answers: "ok" },
          { id: "whisper-large-v3", kind: "speech", answers: "ok" },
          { id: "meta-llama/llama-guard-4-12b", kind: "safety", answers: "ok" },
        ],
      },
    },
    {
      label: "Gemini ships 3.8, 3.5 goes",
      input: {
        known: [
          { id: "gemini-3.5-flash", seen: true, isDefault: true },
          { id: "gemini-3.5-flash-lite", seen: true },
        ],
        listed: [
          { id: "gemini-3.5-flash-lite", kind: "chat", answers: "ok" },
          { id: "gemini-3.8-flash", kind: "chat", answers: "ok" },
          { id: "gemini-3.9-pro-preview", kind: "chat", preview: true, answers: "no-free-quota" },
        ],
      },
    },
  ],
  steps: ["Read the provider's model list", "Retire what it no longer lists", "Test each new chat model once", "Choose the default and the replacement"],
  run: ({ known, listed }) => {
    const ids = new Set(listed.map((l) => l.id));
    const rows: Row[] = [];
    for (const k of known) {
      if (!ids.has(k.id)) rows.push({ id: k.id, outcome: "retired", note: k.seen ? "missing from two lists in a row" : "never in the provider's list" });
    }
    let tests = 0;
    for (const l of listed) {
      if (l.kind !== "chat") {
        rows.push({ id: l.id, outcome: "not for chat", note: `${l.kind} model — catalogued, never tested or routed` });
        continue;
      }
      if (known.some((k) => k.id === l.id)) {
        rows.push({ id: l.id, outcome: "in use", note: "still listed" });
        continue;
      }
      tests++;
      rows.push(l.answers === "ok"
        ? { id: l.id, outcome: "in use", note: `answered a test call${l.preview ? " · preview, so never the default while a stable one works" : ""}` }
        : { id: l.id, outcome: "not free", note: "quota limit 0 on the free key" });
    }
    const old = known.find((k) => k.isDefault)!.id;
    const fam = (id: string) => id.replace(/\d+(\.\d+)*/g, "#");
    const usable = listed.filter((l) => l.kind === "chat" && l.answers === "ok" && !l.preview).map((l) => l.id);
    const size = (id: string) => Number(id.match(/(\d+)b\b/)?.[1] ?? 50);
    const stillThere = usable.includes(old);
    const pick = stillThere
      ? old
      : usable.find((id) => fam(id) === fam(old)) ?? [...usable].sort((a, b) => size(b) - size(a))[0];
    return done({ rows, defaultModel: pick, replaced: stillThere ? "" : old, lists: 1, tests });
  },
  Input: ({ input }) => (
    <div className="space-y-2 text-[12px]">
      <div className="text-slate-500">Known before the check</div>
      {input.known.map((k) => (
        <div key={k.id} className="font-mono text-slate-300">{k.id}{k.isDefault ? "  (default)" : ""}</div>
      ))}
      <div className="pt-1 text-slate-500">What the provider now lists</div>
      {input.listed.map((l) => (
        <div key={l.id} className="font-mono text-slate-300">{l.id}</div>
      ))}
    </div>
  ),
  Output: ({ output }) => {
    const tone: Record<Row["outcome"], Tone> = { retired: "bad", "in use": "ok", "not free": "warn", "not for chat": "mute" };
    return (
      <div className="space-y-4">
        {output.replaced ? (
          <Verdict tone="violet" sub={`requests naming ${output.replaced} now go here`}>Default → {output.defaultModel}</Verdict>
        ) : (
          <Verdict tone="ok" sub="the working default is kept; a newer model is not a reason to switch">Default stays {output.defaultModel}</Verdict>
        )}
        <ul className="space-y-1.5">
          {output.rows.map((r, i) => (
            <li key={r.id} className="demo-pop flex flex-wrap items-baseline gap-2 text-[12px]" style={{ animationDelay: `${i * 70}ms` }}>
              <Pill tone={tone[r.outcome]}>{r.outcome}</Pill>
              <span className="font-mono text-slate-200">{r.id}</span>
              <span className="text-slate-500">{r.note}</span>
            </li>
          ))}
        </ul>
        <div className="text-[11.5px] text-slate-500">Cost: {output.lists} list request, {output.tests} test call{output.tests === 1 ? "" : "s"}.</div>
      </div>
    );
  },
};

export const SAMPLE: Record<string, DemoSpec> = {
  "/gateway": gateway,
  "/autopilot": autopilot,
  "/counterfactual": counterfactual,
  "/admission": admission,
  "/cost-limits": costLimits,
  "/cascade": cascade,
  "/workflows": workflow,
  "/workflows/console": workflow,
  "/saga": saga,
  "/replay": replay,
  "/dag": dag,
  "/pipelines": pipelines,
  "/specialists": specialists,
  "/mmu": mmu,
  "/memory": memory,
  "/quality": quality,
  "/confidence": confidence,
  "/breaker": breaker,
  "/provenance": provenance,
  "/chaos": chaos,
  "/ai-chaos": aiChaos,
  "/godmode": godmode,
  "/models": models,
};
