import { Link } from "react-router-dom";
import { CodeBlock, ThemeToggle } from "../components/ui";
import { Magnetic } from "../system/motion";
import World from "../world/World";
import IntroVideo from "../components/IntroVideo";
import { Facts, Scene } from "../world/Scene";
import ContextMmuDemo from "../world/demos/ContextMmuDemo";
import WorkflowDemo from "../world/demos/WorkflowDemo";
import ConsensusDemo from "../world/demos/ConsensusDemo";
import RoutingDemo from "../world/demos/RoutingDemo";
import LoadDemo from "../world/demos/LoadDemo";

/**
 * The front door.
 *
 * <p>Not a stack of sections. A single spatial system sits behind the whole page
 * and reorganises as you scroll — scattered systems resolving into a fabric,
 * the fabric becoming an execution graph, the graph becoming a memory
 * hierarchy, and so on — while the copy names what you are looking at. Scenes
 * do not each bring their own graphic; there is one world in seven states.
 *
 * <p>The claims here are the ones the system can actually back: durable
 * execution, exactly-once effects, deterministic replay, failover. No capability
 * is described that the console cannot show you.
 */
export default function Landing() {
  return (
    <div className="relative">
      <World />
      <LandingHeader />

      {/* ---------- ENTRY ---------- */}
      <section className="relative flex min-h-screen flex-col items-center justify-center px-6 text-center">
        <div className="animate-fade-up">
          <div className="inline-flex items-center gap-2 rounded-full border border-edge/80 bg-panel/40 px-3 py-1 text-[11px] tracking-wide text-slate-400 backdrop-blur-sm">
            <span className="h-1.5 w-1.5 rounded-full bg-emerald-400 animate-stream-dot" />
            Event-sourced · deterministic replay · exactly-once
          </div>

          <h1 className="mx-auto mt-8 max-w-5xl text-[3.1rem] font-semibold leading-[0.94] tracking-[-0.035em] text-slate-50 sm:text-[6.1rem]">
            An operating layer
            <br />
            <span className="text-gradient">for AI systems.</span>
          </h1>

          <p className="mx-auto mt-7 max-w-xl text-base leading-relaxed text-slate-400">
            AI systems fail in ways ordinary software does not: providers vanish mid-call, context
            outgrows the window, answers change between runs. Continuum is the layer underneath that
            makes them survivable — and inspectable.
          </p>

          <div className="mt-10 flex flex-wrap items-center justify-center gap-3">
            <Magnetic pull={7}>
              <Link
                to="/signin"
                className="inline-block rounded-lg bg-gradient-to-r from-aurora to-neon px-6 py-3 text-sm font-semibold text-ink shadow-glow"
              >
                Get an API key
              </Link>
            </Magnetic>
            <Link
              to="/docs"
              className="rounded-lg border border-edge px-6 py-3 text-sm font-medium text-slate-200 transition-colors hover:border-neon/50 hover:text-neon"
            >
              Read the docs
            </Link>
          </div>
        </div>

        {/* The introduction sits under the claim rather than in place of it.
            Someone who already knows what they want clicks the button; someone
            who does not gets seventy nine seconds that explain it. */}
        <div className="mt-12 w-full animate-fade-up [animation-delay:220ms]">
          <IntroVideo />
        </div>

        <div className="mt-14 flex flex-col items-center gap-2 text-[10px] uppercase tracking-[0.3em] text-slate-600">
          <span>Descend</span>
          <span className="h-8 w-px animate-pulse bg-gradient-to-b from-slate-600 to-transparent" />
        </div>
      </section>

      {/* ---------- THE NARRATIVE ---------- */}
      <Scene
        index="01"
        label="The problem"
        title={
          <>
            Every AI system becomes
            <br />a distributed system.
          </>
        }
        body={
          <>
            One model call becomes six. Six become an agent. Agents call tools, tools call services,
            and each hop is a place to fail. The parts cannot see each other, so nothing can recover
            anything else — and when something breaks at three in the morning, there is no record of
            what the system was thinking.
          </>
        }
      />

      <Scene
        index="02"
        label="The layer"
        side="right"
        title={
          <>
            Continuum is the fabric
            <br />
            they run on.
          </>
        }
        body={
          <>
            Every step is written to an event log before it happens and again when it completes.
            That log is the source of truth: it is what lets a crashed run resume from its last
            completed step instead of the beginning, and what makes a run reproducible months later.
          </>
        }
      >
        <Facts
          items={[
            ["Recovery", "last completed step"],
            ["Side effects", "exactly once"],
            ["History", "fully replayable"],
          ]}
        />
      </Scene>

      <Scene
        index="03"
        label="Durable execution"
        aside={<WorkflowDemo />}
        title={<>Work that survives the process that started it.</>}
        body={
          <>
            Publish a workflow as a graph of steps. Continuum runs it durably: independent steps go
            in parallel, retries never repeat work that already succeeded, waits cost no worker, and
            every call carries a stable idempotency key so your services can dedupe. Kill the engine
            mid-run and it picks up where it stopped.
          </>
        }
      >
        <Facts
          items={[
            ["Steps", "parallel by dependency"],
            ["Retries", "4xx settles, 5xx retries"],
            ["Waits", "durable timers"],
          ]}
        />
      </Scene>

      <Scene
        index="04"
        label="Context virtualization"
        side="right"
        aside={<ContextMmuDemo />}
        title={<>Most of what a model could see should not be resident.</>}
        body={
          <>
            Context is paged like memory. A working set stays in the window; the rest lives in colder
            tiers and faults back on reference. The model behaves as though it has the whole history,
            while the tokens you pay for stay bounded.
          </>
        }
      >
        <Facts
          items={[
            ["Tiers", "L1 / L2 / L3 / durable"],
            ["Resident", "working set only"],
            ["Cold pages", "fault in on reference"],
          ]}
        />
      </Scene>

      <Scene
        index="05"
        label="Verification"
        aside={<ConsensusDemo />}
        title={<>One answer is a guess. Several that agree is evidence.</>}
        body={
          <>
            For decisions worth checking, a question is decomposed into solvers and verifiers that
            work independently and are resolved by Bayesian aggregation. What comes back is not just
            an answer but a confidence and the trace that produced it.
          </>
        }
      />

      <Scene
        index="06"
        label="Adaptive routing"
        side="right"
        aside={<RoutingDemo />}
        title={<>Providers fail. Traffic should already be elsewhere.</>}
        body={
          <>
            Requests are scored per call on capability, latency, cost and live provider health, and a
            failing provider is routed around before your users notice. Fault drills run against your
            own traffic — scoped to your account, safe to run in production.
          </>
        }
      >
        <Facts
          items={[
            ["Selection", "scored per request"],
            ["Failover", "automatic"],
            ["Drills", "scoped to your account"],
          ]}
        />
      </Scene>

      <Scene
        index="07"
        label="Adaptive concurrency"
        aside={<LoadDemo />}
        title={<>Refusing work is a decision, not a failure.</>}
        body={
          <>
            Past a certain arrival rate every extra request makes the others slower, and an
            unbounded queue eventually fails all of them. Continuum holds a concurrency limit
            derived from observed latency and refuses the excess immediately, so what is accepted
            is still served quickly.
          </>
        }
      >
        <Facts
          items={[
            ["Limit", "derived from latency"],
            ["Excess", "refused at the door"],
            ["Failure mode", "bounded, not total"],
          ]}
        />
      </Scene>

      <Scene
        index="08"
        label="Continuum"
        side="center"
        title={<>One fabric. Every guarantee.</>}
        body={
          <>
            Execution, context, verification and routing are not four products stitched together —
            they are the same event log seen from four angles. That is why a run can be replayed, why
            a side effect fires once, and why the console can show you exactly what happened.
          </>
        }
      >
        <div className="mx-auto max-w-2xl text-left">
          <CodeBlock
            language="bash"
            code={`curl https://your-continuum/api/gateway/chat \\
  -H "Authorization: Bearer cnt_live_…" \\
  -d '{"model":"auto","messages":[{"role":"user","content":"Hello!"}]}'`}
          />
          <p className="mt-3 text-center text-xs text-slate-500">
            OpenAI-compatible. The request shape you already send — Continuum handles what happens
            after it.
          </p>
          <div className="mt-8 flex flex-wrap items-center justify-center gap-3">
            <Magnetic pull={7}>
              <Link
                to="/signin"
                className="inline-block rounded-lg bg-gradient-to-r from-aurora to-neon px-6 py-3 text-sm font-semibold text-ink shadow-glow"
              >
                Start building
              </Link>
            </Magnetic>
            <Link
              to="/dashboard"
              className="rounded-lg border border-edge px-6 py-3 text-sm font-medium text-slate-300 transition-colors hover:border-neon/50 hover:text-neon"
            >
              Open the console
            </Link>
          </div>
        </div>
      </Scene>

      <footer className="relative border-t border-edge/60 bg-ink/60 backdrop-blur-sm">
        <div className="mx-auto flex max-w-6xl flex-wrap items-center gap-4 px-6 py-8 text-xs text-slate-500">
          <span className="font-semibold text-slate-300">⟳ Continuum</span>
          <span>Durable execution for AI systems</span>
          <div className="ml-auto flex gap-4">
            <Link to="/docs" className="hover:text-slate-300">Docs</Link>
            <Link to="/dashboard" className="hover:text-slate-300">Console</Link>
            <Link to="/portal" className="hover:text-slate-300">Portal</Link>
          </div>
        </div>
      </footer>
    </div>
  );
}

function LandingHeader() {
  return (
    <header className="sticky top-0 z-30 border-b border-edge/40 bg-ink/50 backdrop-blur-md">
      <div className="mx-auto flex max-w-6xl items-center gap-4 px-6 py-3">
        <Link to="/" className="flex items-center gap-2.5">
          <span className="flex h-8 w-8 items-center justify-center rounded-lg bg-gradient-to-br from-aurora to-neon text-lg font-bold text-ink shadow-glow-sm">
            ⟳
          </span>
          <span className="text-lg font-semibold tracking-tight text-slate-100">Continuum</span>
        </Link>
        <nav className="ml-auto flex shrink-0 items-center gap-1 sm:gap-2">
          <Link to="/docs" className="rounded-lg px-2.5 py-1.5 text-sm text-slate-300 transition-colors hover:text-white sm:px-3">
            Docs
          </Link>
          <Link
            to="/dashboard"
            className="hidden rounded-lg px-3 py-1.5 text-sm text-slate-300 transition-colors hover:text-white sm:block"
          >
            Console
          </Link>
          <ThemeToggle />
          <Link
            to="/signin"
            className="whitespace-nowrap rounded-lg bg-gradient-to-r from-aurora to-neon px-3 py-1.5 text-sm font-semibold text-ink shadow-glow-sm transition-transform hover:-translate-y-0.5 sm:px-4"
          >
            Sign in
          </Link>
        </nav>
      </div>
    </header>
  );
}
