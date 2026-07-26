import { useRef, useState } from "react";
import DemoFrame, { DemoButton, DemoToggle } from "./DemoFrame";
import { pulse } from "../activity";
import { useDemo } from "./useDemo";

/**
 * Durable execution, driveable.
 *
 * <p>Run the graph and watch it execute: independent steps go together, a step
 * that fails on a 5xx backs off and retries without repeating the work that
 * already succeeded, and a step that fails on a 4xx settles immediately rather
 * than burning its retry budget. Those are the engine's real rules, not a
 * plausible-looking animation — the same classification runs in
 * HttpStepActivity.
 *
 * <p>Killing the engine mid-run is the point of the whole system, so it is a
 * button. Completed steps keep their recorded results and execution resumes at
 * the first step that had not finished.
 */

type StepState = "pending" | "running" | "retrying" | "done" | "failed" | "skipped";

interface Step {
  id: string;
  layer: number;
  row: number;
  state: StepState;
  attempt: number;
  /** Frames remaining in the current phase. */
  t: number;
  /** Set when this step is the one chosen to fail. */
  faultKind: "" | "5xx" | "4xx";
}

interface State {
  steps: Step[];
  layer: number;
  running: boolean;
  injectTransient: boolean;
  injectPermanent: boolean;
  log: string[];
  ticks: number;
  crashed: boolean;
}

const SHAPE: [string, number, number][] = [
  ["reserve", 0, 0],
  ["charge", 0, 1],
  ["score", 1, 0],
  ["wait", 1, 1],
  ["confirm", 2, 0],
  ["notify", 2, 1],
  ["settle", 3, 0],
];

function build(prev?: State): State {
  return {
    steps: SHAPE.map(([id, layer, row]) => ({
      id,
      layer,
      row,
      state: "pending",
      attempt: 0,
      t: 0,
      faultKind: "",
    })),
    layer: -1,
    running: false,
    injectTransient: prev?.injectTransient ?? false,
    injectPermanent: prev?.injectPermanent ?? false,
    log: [],
    ticks: 0,
    crashed: false,
  };
}

function say(s: State, line: string) {
  s.log.unshift(line);
  if (s.log.length > 6) s.log.pop();
}

function startLayer(s: State, layer: number) {
  s.layer = layer;
  const inLayer = s.steps.filter((x) => x.layer === layer);
  if (inLayer.length === 0) {
    s.running = false;
    say(s, "run completed");
    return;
  }
  for (const step of inLayer) {
    if (step.state === "done") continue;
    step.state = "running";
    step.attempt += 1;
    step.t = 40 + step.row * 12;
    // Faults are assigned deterministically to one step so the explanation is
    // the same every time you press the button.
    if (step.id === "score" && s.injectTransient && step.attempt <= 2) step.faultKind = "5xx";
    else if (step.id === "notify" && s.injectPermanent) step.faultKind = "4xx";
    else step.faultKind = "";
  }
  say(s, `layer ${layer + 1} scheduled · ${inLayer.length} step${inLayer.length > 1 ? "s" : ""}`);
}

export default function WorkflowDemo() {
  const stateRef = useRef<State>(build());
  const [, force] = useState(0);

  const canvasRef = useDemo<State>({
    state: stateRef.current,
    step(s) {
      if (!s.running) return;
      s.ticks++;
      let busy = false;

      for (const step of s.steps) {
        if (step.state === "running" || step.state === "retrying") {
          busy = true;
          if (--step.t > 0) continue;

          if (step.state === "retrying") {
            step.state = "running";
            step.attempt += 1;
            step.t = 40;
            step.faultKind = s.injectTransient && step.id === "score" && step.attempt <= 2 ? "5xx" : "";
            continue;
          }
          if (step.faultKind === "5xx") {
            // Transient: retried with backoff. The attempts already completed are
            // not repeated — only this step runs again.
            step.state = "retrying";
            step.t = 34 * step.attempt;
            say(s, `${step.id} failed 503 · retry ${step.attempt} after backoff`);
            pulse(0.4);
          } else if (step.faultKind === "4xx") {
            // A decision, not a blip: settled on the first attempt.
            step.state = "failed";
            say(s, `${step.id} failed 422 · settled, no retry`);
          } else {
            step.state = "done";
            say(s, `${step.id} completed`);
            pulse(0.18);
          }
        }
      }

      if (busy) return;

      // A failed step stops its dependents: the run cannot honestly continue.
      if (s.steps.some((x) => x.state === "failed")) {
        for (const x of s.steps) if (x.state === "pending") x.state = "skipped";
        s.running = false;
        say(s, "run failed · downstream steps not scheduled");
        return;
      }
      const next = s.layer + 1;
      if (next > Math.max(...s.steps.map((x) => x.layer))) {
        s.running = false;
        say(s, "run completed · every side effect fired once");
        return;
      }
      startLayer(s, next);
    },
    draw(ctx, s, w, h) {
      const layers = Math.max(...s.steps.map((x) => x.layer)) + 1;
      const padX = 60;
      const colW = (w - padX * 2) / (layers - 1);
      const rowH = 62;
      const top = h / 2 - rowH / 2 - 18;

      const pos = (st: Step) => ({
        x: padX + st.layer * colW,
        y: top + st.row * rowH,
      });

      // Dependencies: every step depends on the whole previous layer, which is
      // what makes a layer a barrier.
      ctx.lineWidth = 1;
      for (const st of s.steps) {
        if (st.layer === 0) continue;
        for (const prev of s.steps.filter((x) => x.layer === st.layer - 1)) {
          const a = pos(prev);
          const b = pos(st);
          const live = prev.state === "done" && st.state !== "pending";
          ctx.strokeStyle = live ? "rgba(76,139,245,0.5)" : "rgba(143,163,200,0.14)";
          ctx.beginPath();
          ctx.moveTo(a.x + 22, a.y);
          ctx.bezierCurveTo(a.x + colW * 0.5, a.y, b.x - colW * 0.5, b.y, b.x - 22, b.y);
          ctx.stroke();
        }
      }

      // Font is set once per group rather than per label: assigning ctx.font
      // re-parses and re-shapes, and this loop ran it fourteen times a frame.
      ctx.textAlign = "center";
      for (const st of s.steps) {
        const { x, y } = pos(st);
        const c =
          st.state === "done"
            ? "#34D399"
            : st.state === "failed"
              ? "#F4566E"
              : st.state === "retrying"
                ? "#F5B544"
                : st.state === "running"
                  ? "#4C8BF5"
                  : st.state === "skipped"
                    ? "#3A4152"
                    : "#5A6478";

        if (st.state === "running") {
          const pulse = 0.5 + 0.5 * Math.sin(s.ticks * 0.12);
          ctx.fillStyle = `rgba(76,139,245,${0.1 + pulse * 0.14})`;
          ctx.beginPath();
          ctx.arc(x, y, 30, 0, Math.PI * 2);
          ctx.fill();
        }

        ctx.fillStyle = "rgba(14,17,24,0.95)";
        ctx.strokeStyle = c;
        ctx.lineWidth = 1.4;
        ctx.beginPath();
        ctx.roundRect(x - 22, y - 13, 44, 26, 5);
        ctx.fill();
        ctx.stroke();

        ctx.fillStyle = c;
        ctx.font = "600 9px ui-monospace, monospace";
        ctx.fillText(st.id, x, y + 3);

        if (st.attempt > 1) {
          ctx.fillStyle = "#F5B544";
          ctx.fillText(`×${st.attempt}`, x, y + 24);
        }
      }
      ctx.textAlign = "left";

      // The event log — the mechanism the whole guarantee rests on.
      ctx.font = "10px ui-monospace, monospace";
      s.log.forEach((line, i) => {
        ctx.fillStyle = `rgba(143,163,200,${0.75 - i * 0.11})`;
        ctx.fillText(line, 14, h - 12 - i * 13);
      });
    },
  });

  const s = stateRef.current;
  const done = s.steps.filter((x) => x.state === "done").length;

  return (
    <DemoFrame
      title="Durable execution — a run"
      hint="crash it mid-run and watch it resume"
      controls={
        <>
          <DemoButton
            primary
            onClick={() => {
              Object.assign(s, build(s));
              s.running = true;
              startLayer(s, 0);
              force((n) => n + 1);
            }}
          >
            Run
          </DemoButton>
          <DemoButton
            disabled={!s.running}
            onClick={() => {
              // Crash: in-flight work is lost, completed steps are not. This is
              // exactly what the event log buys.
              for (const st of s.steps) {
                if (st.state === "running" || st.state === "retrying") {
                  st.state = "pending";
                  st.attempt = 0;
                }
              }
              s.running = false;
              s.crashed = true;
              say(s, "engine killed · in-flight work lost");
              // The loudest thing that can happen to a run.
              pulse(1.2);
              force((n) => n + 1);
            }}
          >
            Kill engine
          </DemoButton>
          <DemoButton
            disabled={!s.crashed}
            onClick={() => {
              s.crashed = false;
              s.running = true;
              const resume = Math.min(
                ...s.steps.filter((x) => x.state !== "done").map((x) => x.layer)
              );
              say(s, `resumed at layer ${resume + 1} · ${done} step(s) replayed from history`);
              startLayer(s, resume);
              force((n) => n + 1);
            }}
          >
            Restart engine
          </DemoButton>
          <DemoToggle
            label="503 on score"
            on={s.injectTransient}
            onChange={(v) => {
              s.injectTransient = v;
              force((n) => n + 1);
            }}
          />
          <DemoToggle
            label="422 on notify"
            on={s.injectPermanent}
            onChange={(v) => {
              s.injectPermanent = v;
              force((n) => n + 1);
            }}
          />
        </>
      }
      readouts={[
        ["Completed", `${done}/${s.steps.length}`],
        ["State", s.crashed ? "crashed" : s.running ? "running" : done === s.steps.length ? "completed" : "idle"],
      ]}
    >
      <canvas ref={canvasRef} className="h-[300px] w-full" />
    </DemoFrame>
  );
}
