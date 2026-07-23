import { Link } from "react-router-dom";
import { Reveal, CodeBlock, ThemeToggle } from "../components/ui";

/**
 * The front door. Standalone marketing page (no app chrome) — says what
 * Continuum is, who it's for, and the value props, JetBrains-product style:
 * animated gradients, scroll-reveal, tasteful motion.
 */
export default function Landing() {
  return (
    <div className="min-h-full">
      <LandingHeader />

      {/* ---------- HERO ---------- */}
      <section className="relative overflow-hidden">
        <div className="pointer-events-none absolute inset-0 -z-10">
          <div className="absolute -top-40 left-1/2 h-[520px] w-[820px] -translate-x-1/2 rounded-full bg-aurora/20 blur-[120px] animate-glow-pulse" />
          <div className="absolute top-40 right-0 h-[360px] w-[360px] rounded-full bg-neon/10 blur-[100px]" />
        </div>
        <div className="mx-auto max-w-6xl px-6 pt-20 pb-16 text-center sm:pt-28">
          <div className="inline-flex items-center gap-2 rounded-full border border-edge bg-panel/60 px-3 py-1 text-xs text-slate-400 animate-fade-up">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-stream-dot" />
            Durable execution for AI — event-sourced, self-healing, verifiable
          </div>
          <h1 className="mx-auto mt-6 max-w-4xl text-4xl font-bold leading-[1.1] tracking-tight sm:text-6xl animate-fade-up">
            The reliability layer <br className="hidden sm:block" />
            <span className="text-gradient">between your app and the AI.</span>
          </h1>
          <p className="mx-auto mt-6 max-w-2xl text-base leading-relaxed text-slate-400 sm:text-lg animate-fade-up">
            Continuum treats the LLM as just another unreliable dependency. Point your app at it and
            get crash-proof workflows, exactly-once side effects, automatic provider failover,
            verified answers and huge cost savings — without changing your code.
          </p>
          <div className="mt-8 flex flex-wrap items-center justify-center gap-3 animate-fade-up">
            <Link
              to="/portal"
              className="rounded-xl bg-gradient-to-r from-aurora to-neon px-6 py-3 text-sm font-semibold text-ink shadow-glow transition-transform hover:-translate-y-0.5"
            >
              Get your API key →
            </Link>
            <Link
              to="/docs"
              className="rounded-xl border border-edge px-6 py-3 text-sm font-medium text-slate-200 transition-colors hover:border-neon/50 hover:text-neon"
            >
              Read the docs
            </Link>
            <Link
              to="/dashboard"
              className="rounded-xl px-6 py-3 text-sm font-medium text-slate-400 transition-colors hover:text-slate-200"
            >
              Live dashboard ↗
            </Link>
          </div>

          {/* hero code */}
          <div className="mx-auto mt-14 max-w-2xl text-left animate-fade-up">
            <CodeBlock
              language="bash"
              code={`curl https://your-continuum/api/gateway/chat \\
  -H "Authorization: Bearer cnt_live_…" \\
  -d '{"model":"auto","messages":[{"role":"user","content":"Hello!"}]}'`}
            />
            <p className="mt-2 text-center text-xs text-slate-500">
              OpenAI-compatible. Same request shape you already use — Continuum handles the rest.
            </p>
          </div>
        </div>
      </section>

      {/* ---------- VALUE PROPS ---------- */}
      <section className="mx-auto max-w-6xl px-6 py-16">
        <div className="grid gap-4 sm:grid-cols-2 lg:grid-cols-4">
          {[
            ["🛡️", "Never loses work", "Crash mid-task and it resumes exactly where it left off — no repeated work, no lost progress."],
            ["✅", "Exactly-once", "Emails and charges fire once, even through retries and crashes. No double receipts."],
            ["🔀", "Auto failover", "A provider goes down and Continuum switches instantly. Your users never notice."],
            ["💸", "Big cost savings", "Right-sized routing, prompt compression and context paging cut token spend dramatically."],
          ].map(([icon, title, body], i) => (
            <Reveal key={title} delay={i * 80}>
              <div className="glass h-full p-5 transition-all hover:-translate-y-1 hover:shadow-glow">
                <div className="text-2xl">{icon}</div>
                <div className="mt-3 text-sm font-semibold">{title}</div>
                <div className="mt-1 text-xs leading-relaxed text-slate-400">{body}</div>
              </div>
            </Reveal>
          ))}
        </div>
      </section>

      {/* ---------- FEATURE SPOTLIGHT ---------- */}
      <section className="mx-auto max-w-6xl px-6 py-12">
        <Reveal>
          <h2 className="text-center text-2xl font-bold tracking-tight sm:text-3xl">
            One gateway. <span className="text-gradient">Every reliability guarantee.</span>
          </h2>
          <p className="mx-auto mt-3 max-w-2xl text-center text-sm text-slate-400">
            Each capability is opt-in and off by default — turn on exactly what you need.
          </p>
        </Reveal>
        <div className="mt-10 grid gap-4 md:grid-cols-2 lg:grid-cols-3">
          {[
            ["Verified answers", "Break high-stakes questions into a parallel DAG of solvers and verifiers, resolved by Bayesian math — with a confidence score and full audit trail.", "/dag"],
            ["Autonomous optimization", "A Thompson-sampling autopilot learns the best routing from real traffic and only changes what's proven better, with a digital-twin safety net.", "/autopilot"],
            ["Context virtualization", "An OS-style paging MMU keeps only what matters in the model's window and pages the rest — infinite context without bigger token limits.", "/mmu"],
            ["Long-term memory", "A four-tier memory that summarizes, distills and forgets like a brain — bounded, private, and per-tenant.", "/godmode"],
            ["Prompt firewall", "PII redaction and prompt-injection blocking inbound; secret-leak scanning outbound. Compliance-grade by design.", "/portal"],
            ["Self-healing deploys", "Ship changed workflow code over in-flight jobs — the engine reconciles the divergence automatically. No crashes.", "/dag"],
          ].map(([title, body, to], i) => (
            <Reveal key={title} delay={(i % 3) * 80}>
              <Link
                to={to as string}
                className="group flex h-full flex-col rounded-xl border border-edge bg-panel/60 p-5 transition-all hover:-translate-y-1 hover:border-aurora/40 hover:shadow-glow-sm"
              >
                <div className="text-sm font-semibold text-slate-100 group-hover:text-white">{title}</div>
                <div className="mt-2 flex-1 text-xs leading-relaxed text-slate-400">{body}</div>
                <div className="mt-3 text-xs font-medium text-neon opacity-0 transition-opacity group-hover:opacity-100">
                  Explore →
                </div>
              </Link>
            </Reveal>
          ))}
        </div>
      </section>

      {/* ---------- HOW IT WORKS ---------- */}
      <section className="mx-auto max-w-4xl px-6 py-16">
        <Reveal>
          <h2 className="text-center text-2xl font-bold tracking-tight sm:text-3xl">Live in three steps</h2>
        </Reveal>
        <div className="mt-10 space-y-4">
          {[
            ["1", "Get a key", "Sign up in the Developer Portal and issue a Continuum API key."],
            ["2", "Point your app", "Swap your base URL — Continuum speaks the OpenAI chat format."],
            ["3", "Flip on features", "Toggle verification, memory, compression or the firewall as you need them."],
          ].map(([n, title, body], i) => (
            <Reveal key={title} delay={i * 80}>
              <div className="flex items-start gap-4 rounded-xl border border-edge bg-panel/50 p-5">
                <div className="flex h-9 w-9 shrink-0 items-center justify-center rounded-lg bg-gradient-to-br from-aurora to-neon text-sm font-bold text-ink">
                  {n}
                </div>
                <div>
                  <div className="text-sm font-semibold">{title}</div>
                  <div className="mt-0.5 text-xs text-slate-400">{body}</div>
                </div>
              </div>
            </Reveal>
          ))}
        </div>
        <Reveal>
          <div className="mt-10 text-center">
            <Link
              to="/portal"
              className="inline-block rounded-xl bg-gradient-to-r from-aurora to-neon px-6 py-3 text-sm font-semibold text-ink shadow-glow transition-transform hover:-translate-y-0.5"
            >
              Start building — free →
            </Link>
          </div>
        </Reveal>
      </section>

      <footer className="border-t border-edge/60">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-4 px-6 py-8 text-xs text-slate-500">
          <span className="text-gradient font-bold">⟳ Continuum</span>
          <span>Durable execution engine for AI agents</span>
          <div className="ml-auto flex gap-4">
            <Link to="/docs" className="hover:text-slate-300">Docs</Link>
            <Link to="/dashboard" className="hover:text-slate-300">Dashboard</Link>
            <Link to="/portal" className="hover:text-slate-300">Portal</Link>
          </div>
        </div>
      </footer>
    </div>
  );
}

function LandingHeader() {
  return (
    <header className="sticky top-0 z-30 border-b border-edge/50 bg-ink/70 backdrop-blur-md">
      <div className="mx-auto flex max-w-6xl items-center gap-4 px-6 py-3">
        <Link to="/" className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-aurora to-neon text-lg font-bold text-ink shadow-glow-sm">
            ⟳
          </span>
          <span className="text-lg font-bold tracking-tight text-gradient">Continuum</span>
        </Link>
        <nav className="ml-auto flex items-center gap-1 sm:gap-2">
          <Link to="/docs" className="rounded-lg px-3 py-1.5 text-sm text-slate-300 transition-colors hover:text-white">
            Docs
          </Link>
          <Link to="/dashboard" className="hidden rounded-lg px-3 py-1.5 text-sm text-slate-300 transition-colors hover:text-white sm:block">
            Dashboard
          </Link>
          <ThemeToggle />
          <Link
            to="/portal"
            className="rounded-lg bg-gradient-to-r from-aurora to-neon px-4 py-1.5 text-sm font-semibold text-ink shadow-glow-sm transition-transform hover:-translate-y-0.5"
          >
            Sign in
          </Link>
        </nav>
      </div>
    </header>
  );
}
