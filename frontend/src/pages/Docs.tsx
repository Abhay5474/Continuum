import { useState } from "react";
import { Link } from "react-router-dom";
import { CodeBlock, ThemeToggle } from "../components/ui";

/**
 * Developer docs — Quickstart (curl + JS + Python), API reference, and a short
 * page per feature. Sticky sidebar nav on desktop; scrollable sections on mobile.
 */
const SECTIONS = [
  { id: "quickstart", label: "Quickstart" },
  { id: "auth", label: "Authentication" },
  { id: "chat", label: "Chat API" },
  { id: "workflows", label: "Durable workflows" },
  { id: "features", label: "Features" },
  { id: "reference", label: "API reference" },
];

export default function Docs() {
  const [active, setActive] = useState("quickstart");

  return (
    <div className="min-h-full">
      <DocsHeader />
      <div className="mx-auto flex max-w-6xl gap-8 px-6 py-10">
        {/* sidebar */}
        <aside className="hidden w-52 shrink-0 lg:block">
          <div className="sticky top-24 space-y-1">
            <div className="mb-2 text-[10px] uppercase tracking-widest text-slate-500">Documentation</div>
            {SECTIONS.map((s) => (
              <a
                key={s.id}
                href={`#${s.id}`}
                onClick={() => setActive(s.id)}
                className={`block rounded-lg px-3 py-1.5 text-sm transition-colors ${
                  active === s.id
                    ? "bg-aurora/15 text-indigo-200 ring-1 ring-aurora/30"
                    : "text-slate-400 hover:text-slate-200"
                }`}
              >
                {s.label}
              </a>
            ))}
          </div>
        </aside>

        {/* content */}
        <div className="min-w-0 flex-1 space-y-14">
          <section id="quickstart" className="scroll-mt-24">
            <h1 className="text-3xl font-bold tracking-tight">Quickstart</h1>
            <p className="mt-3 max-w-2xl text-sm leading-relaxed text-slate-400">
              Continuum is an OpenAI-compatible gateway. Point your app at it, use your Continuum API
              key, and every request gets reliability, routing and failover for free. Your first call
              takes under two minutes.
            </p>

            <Step n={1} title="Get an API key">
              Open the{" "}
              <Link to="/portal" className="text-neon hover:underline">
                Developer Portal
              </Link>
              , sign up, and click <b>Issue new key</b>. Keys look like <code className="rounded bg-ink px-1 text-neon">cnt_live_…</code>.
            </Step>

            <Step n={2} title="Make your first call">
              <div className="mt-3 grid gap-3">
                <CodeBlock
                  language="bash"
                  code={`curl https://your-continuum/api/gateway/chat \\
  -H "Authorization: Bearer $CONTINUUM_KEY" \\
  -H "Content-Type: application/json" \\
  -d '{
    "model": "auto",
    "messages": [{"role":"user","content":"Say hello in one word."}]
  }'`}
                />
              </div>
            </Step>

            <Step n={3} title="You made your first call">
              You'll get back a standard chat response. Behind the scenes Continuum scored the task,
              picked a model, and would have failed over automatically if a provider was down — with
              zero changes on your side.
            </Step>
          </section>

          <section id="auth" className="scroll-mt-24">
            <h2 className="text-2xl font-bold tracking-tight">Authentication</h2>
            <p className="mt-2 max-w-2xl text-sm text-slate-400">
              Every gateway request is authenticated with a bearer token — your Continuum API key.
              Keys are stored hashed and shown only once at creation. Rotate or revoke them anytime in
              the portal.
            </p>
            <CodeBlock className="mt-3" language="http" code={`Authorization: Bearer cnt_live_xxxxxxxxxxxxxxxx`} />
          </section>

          <section id="chat" className="scroll-mt-24">
            <h2 className="text-2xl font-bold tracking-tight">Chat API</h2>
            <p className="mt-2 max-w-2xl text-sm text-slate-400">
              <code className="rounded bg-ink px-1 text-neon">POST /api/gateway/chat</code> (and the
              OpenAI-shaped alias <code className="rounded bg-ink px-1 text-neon">/v1/chat/completions</code>).
              Set <code className="rounded bg-ink px-1">model</code> to <code className="rounded bg-ink px-1">"auto"</code> to
              let Continuum route by complexity.
            </p>
            <div className="mt-4 grid gap-4 md:grid-cols-2">
              <div className="min-w-0">
                <div className="mb-1 text-xs font-semibold text-slate-300">JavaScript</div>
                <CodeBlock
                  language="javascript"
                  code={`const res = await fetch(
  "https://your-continuum/api/gateway/chat",
  {
    method: "POST",
    headers: {
      "Authorization": \`Bearer \${process.env.CONTINUUM_KEY}\`,
      "Content-Type": "application/json",
    },
    body: JSON.stringify({
      model: "auto",
      messages: [{ role: "user", content: "Hello!" }],
    }),
  }
);
const data = await res.json();
console.log(data.response);`}
                />
              </div>
              <div className="min-w-0">
                <div className="mb-1 text-xs font-semibold text-slate-300">Python</div>
                <CodeBlock
                  language="python"
                  code={`import os, requests

r = requests.post(
    "https://your-continuum/api/gateway/chat",
    headers={"Authorization": f"Bearer {os.environ['CONTINUUM_KEY']}"},
    json={
        "model": "auto",
        "messages": [{"role": "user", "content": "Hello!"}],
    },
)
print(r.json()["response"])`}
                />
              </div>
            </div>
          </section>

          <section id="workflows" className="scroll-mt-24">
            <h2 className="text-2xl font-bold tracking-tight">Durable workflows</h2>
            <p className="mt-2 max-w-2xl text-sm leading-relaxed text-slate-400">
              A workflow is a graph of HTTP calls you publish as JSON. Continuum executes it durably:
              each step's result is recorded, so a crash resumes from the last completed step rather
              than the beginning, retries never re-run work that already succeeded, and every call
              carries a stable idempotency key so your service can dedupe it. Author one in the
              console under <Link to="/workflows" className="text-indigo-300 hover:underline">Workflows</Link>,
              or publish over the API.
            </p>

            <h3 className="mt-6 text-sm font-semibold text-slate-200">A definition</h3>
            <CodeBlock
              language="json"
              code={`{
  "description": "Reserve stock, charge, then confirm",
  "steps": [
    { "id": "reserve",
      "call": { "method": "POST", "url": "https://api.acme.com/reserve",
                "body": { "sku": "\${input.sku}" } },
      "retries": 5, "timeoutSeconds": 30 },

    { "id": "charge",
      "call": { "method": "POST", "url": "https://api.acme.com/charge",
                "body": { "amount": "\${input.amount}" } } },

    { "id": "settle-window", "type": "WAIT", "waitSeconds": 300,
      "dependsOn": ["charge"] },

    { "id": "confirm", "dependsOn": ["reserve", "settle-window"],
      "condition": "\${steps.charge.paid} == true",
      "call": { "method": "POST", "url": "https://api.acme.com/confirm",
                "body": { "hold": "\${steps.reserve.holdId}" } } }
  ],
  "onComplete": { "url": "https://api.acme.com/webhooks/order-done" }
}`}
            />

            <div className="mt-5 grid gap-3 sm:grid-cols-2">
              {[
                ["dependsOn", "Ordering. Steps that do not depend on each other run in parallel automatically — you never schedule threads yourself."],
                ["References", "${input.field} is what the run was started with; ${steps.<id>.<field>} is an earlier step's response body. A reference used alone keeps its type; inside a longer string it is interpolated."],
                ["condition", "A guard. Comparisons (==, !=, >, >=, <, <=) joined by and/or — no arithmetic and no function calls, because a guard is re-evaluated on replay and must be a pure function of recorded values. A false guard skips the step and records it as skipped."],
                ["type: WAIT", "A durable timer, 1–86400 seconds. The task is simply not claimable until it is due, so a wait occupies no worker and survives a restart."],
                ["retries / timeoutSeconds", "Per step. A 5xx or a timeout is retried with backoff; a 4xx is treated as a decision, not a blip, and fails the run immediately instead of burning retries."],
                ["onComplete", "A callback delivered once the graph finishes, so you do not have to poll. It is an ordinary durable step, so it retries and carries its own idempotency key."],
              ].map(([title, body]) => (
                <div key={title} className="rounded-xl border border-edge bg-panel/50 p-4">
                  <div className="font-mono text-xs font-semibold text-slate-100">{title}</div>
                  <div className="mt-1 text-xs leading-relaxed text-slate-400">{body}</div>
                </div>
              ))}
            </div>

            <h3 className="mt-6 text-sm font-semibold text-slate-200">Publish and run</h3>
            <CodeBlock
              language="bash"
              code={`# Publish appends a new version; in-flight runs keep executing the
# version they started with, which is what makes replay deterministic.
curl -X POST https://api.continuum.dev/api/portal/developer/workflows/definitions/order-flow \\
  -H "Authorization: Bearer $CONTINUUM_SESSION" \\
  -H "Content-Type: application/json" \\
  -d @order-flow.json

curl -X POST https://api.continuum.dev/api/portal/developer/workflows/definitions/order-flow/run \\
  -H "Authorization: Bearer $CONTINUUM_SESSION" \\
  -H "Content-Type: application/json" \\
  -d '{"input": {"sku": "WIDGET-1", "amount": 4999}}'
# => {"workflowId":"…","definition":"order-flow","version":1,"status":"RUNNING"}`}
            />
            <p className="mt-3 max-w-2xl text-xs leading-relaxed text-slate-500">
              Step targets must be public HTTPS endpoints. Requests to loopback, private or
              link-local addresses are rejected, and redirects are not followed.
            </p>
          </section>

          <section id="features" className="scroll-mt-24">
            <h2 className="text-2xl font-bold tracking-tight">Features</h2>
            <p className="mt-2 max-w-2xl text-sm text-slate-400">
              Every advanced capability is opt-in and off by default. Turn each on with a single
              toggle in the portal — when off, the gateway behaves like a plain proxy.
            </p>
            <div className="mt-5 grid gap-3 sm:grid-cols-2">
              {[
                ["Verification Engine", "Turn on for high-stakes outputs (payments, contracts, generated SQL/config). Each answer is checked from multiple angles and returned with a confidence score and audit trail. Adds latency — use it where correctness beats speed."],
                ["Context Virtualization", "Turn on for long conversations. Continuum pages old context out of the model's window and back in on demand — big token savings, transparent to your app."],
                ["Autopilot", "Turn on to let Continuum learn the best routing from your real traffic and auto-tune it, with a canary + rollback safety net."],
                ["Long-term Memory", "Turn on to let the AI remember useful facts across sessions — summarized, bounded and private to your account."],
                ["Prompt Compression", "Turn on to strip low-information tokens from prompts (protecting numbers, IDs and code) for extra cost savings on every call."],
                ["Prompt Firewall", "Turn on to redact PII before it leaves for the provider and block prompt-injection attempts — compliance-grade safety."],
              ].map(([title, body]) => (
                <div key={title} className="rounded-xl border border-edge bg-panel/50 p-4">
                  <div className="text-sm font-semibold text-slate-100">{title}</div>
                  <div className="mt-1 text-xs leading-relaxed text-slate-400">{body}</div>
                </div>
              ))}
            </div>
          </section>

          <section id="reference" className="scroll-mt-24">
            <h2 className="text-2xl font-bold tracking-tight">API reference</h2>
            <p className="mt-2 text-sm text-slate-400">Core endpoints. All requests are JSON over HTTPS.</p>
            <div className="mt-4 overflow-x-auto rounded-xl border border-edge">
              <table className="w-full text-sm">
                <thead className="bg-panel/60 text-xs uppercase tracking-wide text-slate-500">
                  <tr className="text-left">
                    <th className="px-4 py-2">Method</th>
                    <th className="px-4 py-2">Path</th>
                    <th className="px-4 py-2">Description</th>
                  </tr>
                </thead>
                <tbody className="divide-y divide-edge/60">
                  {[
                    ["POST", "/api/gateway/chat", "Send a chat request through the gateway."],
                    ["POST", "/v1/chat/completions", "OpenAI-compatible alias for the above."],
                    ["GET", "/api/gateway/stats", "Aggregate gateway metrics (success, failovers prevented, cost)."],
                    ["POST", "/api/portal/developer/keys", "Issue a new Continuum API key."],
                    ["GET", "/api/portal/developer/stats", "Your usage: requests, success rate, tokens, cost."],
                    ["POST", "/api/portal/developer/v6/enable", "Enable the Verification Engine."],
                    ["POST", "/api/portal/developer/v7/enable", "Enable Context Virtualization."],
                    ["POST", "/api/portal/developer/v8/firewall/enable", "Enable the Prompt Firewall."],
                    ["GET", "/api/portal/developer/workflows/definitions", "List your workflow definitions."],
                    ["POST", "/api/portal/developer/workflows/definitions/{name}", "Publish a new version of a definition."],
                    ["POST", "/api/portal/developer/workflows/definitions/{name}/run", "Start a durable run."],
                    ["GET", "/api/workflows/{id}", "A run's status, event history and result."],
                  ].map(([m, p, d]) => (
                    <tr key={p} className="hover:bg-edge/30">
                      <td className="px-4 py-2">
                        <span
                          className={`rounded px-2 py-0.5 text-[10px] font-bold ${
                            m === "GET" ? "bg-sky-500/20 text-sky-300" : "bg-emerald-500/20 text-emerald-300"
                          }`}
                        >
                          {m}
                        </span>
                      </td>
                      <td className="px-4 py-2 font-mono text-xs text-neon">{p}</td>
                      <td className="px-4 py-2 text-xs text-slate-400">{d}</td>
                    </tr>
                  ))}
                </tbody>
              </table>
            </div>
            <div className="mt-8 rounded-xl border border-aurora/30 bg-aurora/5 p-5 text-center">
              <div className="text-sm font-semibold">Ready to build?</div>
              <Link
                to="/portal"
                className="mt-3 inline-block rounded-lg bg-gradient-to-r from-aurora to-neon px-5 py-2 text-sm font-semibold text-ink shadow-glow-sm transition-transform hover:-translate-y-0.5"
              >
                Open the Developer Portal →
              </Link>
            </div>
          </section>
        </div>
      </div>
    </div>
  );
}

function Step({ n, title, children }: { n: number; title: string; children: import("react").ReactNode }) {
  return (
    <div className="mt-6 flex gap-4">
      <div className="flex h-8 w-8 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-aurora to-neon text-sm font-bold text-ink">
        {n}
      </div>
      <div className="min-w-0 flex-1">
        <div className="text-sm font-semibold">{title}</div>
        <div className="mt-1 text-sm text-slate-400">{children}</div>
      </div>
    </div>
  );
}

function DocsHeader() {
  return (
    <header className="sticky top-0 z-30 border-b border-edge/50 bg-ink/70 backdrop-blur-md">
      <div className="mx-auto flex max-w-6xl items-center gap-4 px-6 py-3">
        <Link to="/" className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-aurora to-neon text-lg font-bold text-ink shadow-glow-sm">
            ⟳
          </span>
          <span className="text-lg font-semibold tracking-tight text-slate-100">Continuum</span>
          <span className="hidden text-[10px] uppercase tracking-widest text-slate-500 sm:block">Docs</span>
        </Link>
        <nav className="ml-auto flex min-w-0 shrink items-center gap-1.5 sm:gap-2">
          <Link to="/dashboard" className="rounded-lg px-3 py-1.5 text-sm text-slate-300 hover:text-white">
            Dashboard
          </Link>
          <ThemeToggle />
          <Link
            to="/portal"
            className="rounded-lg bg-gradient-to-r from-aurora to-neon px-4 py-1.5 text-sm font-semibold text-ink shadow-glow-sm transition-transform hover:-translate-y-0.5"
          >
            Get a key
          </Link>
        </nav>
      </div>
    </header>
  );
}
