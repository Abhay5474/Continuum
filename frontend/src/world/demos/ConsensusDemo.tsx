import { useRef, useState } from "react";
import DemoFrame, { DemoButton } from "./DemoFrame";
import { pulse } from "../activity";
import { clamp, seeded, useDemo } from "./useDemo";

/**
 * Verification, driveable.
 *
 * <p>Solvers answer independently and mostly disagree at first. Their verdicts
 * are combined in log-odds — the same aggregation the engine uses — so a
 * confident solver moves the posterior further than a hesitant one, and two
 * solvers that agree are worth more than the sum of their individual
 * confidences.
 *
 * <p>The slider that matters is difficulty. On an easy question the solvers
 * converge quickly and the confidence is high; on a hard one they split, the
 * posterior hovers near even, and the honest output is low confidence rather
 * than a confident guess. Watching that happen is the argument for verification.
 */

interface Solver {
  id: number;
  angle: number;
  /** The verdict this solver reached: 1 for, 0 against. */
  verdict: number;
  /** How sure it is, 0.5..0.99. */
  confidence: number;
  /** 0..1 progress through its own reasoning. */
  progress: number;
  speed: number;
  /** Animated radius from the hub. */
  r: number;
  tr: number;
}

interface State {
  solvers: Solver[];
  /** 0..1 — how hard the question is. Drives how much solvers disagree. */
  difficulty: number;
  running: boolean;
  /** Aggregated posterior probability, 0..1. */
  posterior: number;
  settled: boolean;
  ticks: number;
  rng: () => number;
  seed: number;
}

const COUNT = 7;

function build(difficulty: number, seed: number): State {
  const rng = seeded(seed);
  return {
    solvers: Array.from({ length: COUNT }, (_, id) => {
      // The harder the question, the more likely a solver lands on the wrong
      // side of it — which is precisely why one answer is not evidence.
      const correct = rng() > difficulty * 0.55;
      return {
        id,
        angle: (id / COUNT) * Math.PI * 2 - Math.PI / 2,
        verdict: correct ? 1 : 0,
        // Confidence also degrades with difficulty; a hard question produces
        // hedged answers, not merely wrong ones.
        confidence: 0.55 + (1 - difficulty) * 0.4 * (0.6 + rng() * 0.4),
        progress: 0,
        speed: 0.006 + rng() * 0.012,
        r: 1,
        tr: 1,
      };
    }),
    difficulty,
    running: false,
    posterior: 0.5,
    settled: false,
    ticks: 0,
    rng,
    seed,
  };
}

/** Combines finished verdicts in log-odds. Independent evidence adds. */
function aggregate(s: State): number {
  let logOdds = 0;
  for (const v of s.solvers) {
    if (v.progress < 1) continue;
    const c = clamp(v.confidence, 0.5001, 0.9999);
    const l = Math.log(c / (1 - c));
    logOdds += v.verdict === 1 ? l : -l;
  }
  return 1 / (1 + Math.exp(-logOdds));
}

export default function ConsensusDemo() {
  const stateRef = useRef<State>(build(0.35, 11));
  const [, force] = useState(0);

  const canvasRef = useDemo<State>({
    state: stateRef.current,
    step(s) {
      s.ticks++;
      if (s.running) {
        let allDone = true;
        for (const v of s.solvers) {
          if (v.progress < 1) {
            v.progress = Math.min(1, v.progress + v.speed);
            allDone = false;
          }
          // Disagreement is spatial: a solver that has answered pulls toward the
          // hub if it agrees with the emerging consensus and drifts out if not.
          const agrees = v.progress >= 1 && (s.posterior > 0.5) === (v.verdict === 1);
          v.tr = v.progress < 1 ? 1 : agrees ? 0.45 : 1.15;
        }
        s.posterior = aggregate(s);
        if (allDone) {
          s.running = false;
          s.settled = true;
          // Convergence is the event. A confident verdict lands harder than a
          // split one, which is the honest weighting.
          pulse(0.4 + Math.abs(s.posterior - 0.5) * 1.2);
        }
      }
      for (const v of s.solvers) v.r += (v.tr - v.r) * 0.06;
    },
    draw(ctx, s, w, h) {
      const cx = w / 2;
      const cy = h / 2;
      const R = Math.min(w, h) * 0.33;
      const confident = Math.abs(s.posterior - 0.5) * 2;

      // The hub: the resolved answer. It only looks resolved when it is.
      const hubColour = !s.settled
        ? "rgba(143,163,200,0.35)"
        : confident > 0.6
          ? "#34D399"
          : confident > 0.25
            ? "#F5B544"
            : "#F4566E";

      for (const v of s.solvers) {
        const x = cx + Math.cos(v.angle) * R * v.r;
        const y = cy + Math.sin(v.angle) * R * v.r * 0.82;
        const done = v.progress >= 1;

        ctx.strokeStyle = done
          ? v.verdict === 1
            ? "rgba(52,211,153,0.45)"
            : "rgba(244,86,110,0.4)"
          : "rgba(143,163,200,0.18)";
        ctx.lineWidth = done ? 1 + v.confidence * 1.6 : 1;
        ctx.beginPath();
        ctx.moveTo(cx, cy);
        ctx.lineTo(x, y);
        ctx.stroke();

        // A solver still reasoning shows an arc filling; finished ones show a
        // filled disc whose size is their confidence.
        const rad = 7 + (done ? v.confidence * 7 : 3);
        ctx.fillStyle = "rgba(14,17,24,0.95)";
        ctx.beginPath();
        ctx.arc(x, y, rad, 0, Math.PI * 2);
        ctx.fill();

        ctx.strokeStyle = done
          ? v.verdict === 1
            ? "#34D399"
            : "#F4566E"
          : "rgba(143,163,200,0.5)";
        ctx.lineWidth = 1.6;
        ctx.beginPath();
        ctx.arc(x, y, rad, -Math.PI / 2, -Math.PI / 2 + Math.PI * 2 * (done ? 1 : v.progress));
        ctx.stroke();

        if (done) {
          ctx.fillStyle = v.verdict === 1 ? "#34D399" : "#F4566E";
          ctx.font = "600 9px ui-monospace, monospace";
          ctx.textAlign = "center";
          ctx.fillText(`${Math.round(v.confidence * 100)}`, x, y + 3);
          ctx.textAlign = "left";
        }
      }

      ctx.fillStyle = "rgba(14,17,24,0.98)";
      ctx.beginPath();
      ctx.arc(cx, cy, 34, 0, Math.PI * 2);
      ctx.fill();
      ctx.strokeStyle = hubColour;
      ctx.lineWidth = 2;
      ctx.beginPath();
      ctx.arc(cx, cy, 34, 0, Math.PI * 2);
      ctx.stroke();

      ctx.textAlign = "center";
      ctx.fillStyle = hubColour;
      ctx.font = "600 15px ui-monospace, monospace";
      ctx.fillText(s.settled ? `${Math.round(s.posterior * 100)}%` : "—", cx, cy + 2);
      ctx.fillStyle = "rgba(143,163,200,0.55)";
      ctx.font = "9px ui-monospace, monospace";
      ctx.fillText("posterior", cx, cy + 16);
      ctx.textAlign = "left";

      if (s.settled && confident < 0.25) {
        ctx.fillStyle = "rgba(244,86,110,0.85)";
        ctx.font = "10px ui-monospace, monospace";
        ctx.fillText("solvers split — answer returned with low confidence", 14, h - 14);
      }
    },
  });

  const s = stateRef.current;
  const agree = s.solvers.filter((v) => v.progress >= 1 && v.verdict === 1).length;
  const answered = s.solvers.filter((v) => v.progress >= 1).length;

  const run = (difficulty: number) => {
    Object.assign(s, build(difficulty, s.seed + 1));
    s.running = true;
    force((n) => n + 1);
  };

  return (
    <DemoFrame
      title="Verification — solvers and consensus"
      hint="raise the difficulty and watch confidence collapse"
      controls={
        <>
          <DemoButton primary onClick={() => run(s.difficulty)}>
            Ask again
          </DemoButton>
          <label className="flex items-center gap-2 text-[11px] text-slate-400">
            Difficulty
            <input
              type="range"
              min={0}
              max={100}
              value={Math.round(s.difficulty * 100)}
              onChange={(e) => run(Number(e.target.value) / 100)}
              className="h-1 w-28 cursor-pointer accent-aurora"
            />
            <span className="readout w-8 text-slate-300">{Math.round(s.difficulty * 100)}</span>
          </label>
        </>
      }
      readouts={[
        ["Answered", `${answered}/${COUNT}`],
        ["For / against", `${agree} / ${answered - agree}`],
        [
          "Verdict",
          !s.settled
            ? "pending"
            : Math.abs(s.posterior - 0.5) * 2 < 0.25
              ? "inconclusive"
              : s.posterior > 0.5
                ? "supported"
                : "rejected",
        ],
      ]}
    >
      <canvas ref={canvasRef} className="h-[300px] w-full" />
    </DemoFrame>
  );
}
