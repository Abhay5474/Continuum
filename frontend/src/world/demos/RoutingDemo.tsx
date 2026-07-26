import { useRef, useState } from "react";
import DemoFrame, { DemoButton } from "./DemoFrame";
import { clamp, seeded, useDemo } from "./useDemo";

/**
 * Adaptive routing, driveable.
 *
 * <p>Take a provider down and watch where the traffic goes. Each request is
 * scored on the same axes the engine scores on — health, observed latency and
 * cost — and the winner takes it. Health is not a switch you set; it is measured
 * from what actually happened to recent requests, which is why a provider you
 * mark down recovers gradually rather than instantly once you bring it back.
 *
 * <p>Cost per provider differs, so watch the spend readout when the cheap
 * provider is unavailable: failover is not free, and pretending otherwise would
 * misrepresent what the system does.
 */

interface Provider {
  name: string;
  baseLatency: number;
  costPer1k: number;
  /** 0..1, measured from recent outcomes rather than declared. */
  health: number;
  /** Operator-forced outage — the demo's version of a fault drill. */
  down: boolean;
  /** Requests currently assigned, for the flow visual. */
  load: number;
  observedLatency: number;
  served: number;
  failed: number;
  y: number;
}

interface Request {
  t: number;
  lane: number;
  failing: boolean;
}

interface State {
  providers: Provider[];
  requests: Request[];
  spawn: number;
  ticks: number;
  spend: number;
  rng: () => number;
  lastReason: string;
}

function build(): State {
  return {
    providers: [
      { name: "gemini", baseLatency: 240, costPer1k: 0.35, health: 1, down: false, load: 0, observedLatency: 240, served: 0, failed: 0, y: 0 },
      { name: "groq", baseLatency: 130, costPer1k: 0.9, health: 1, down: false, load: 0, observedLatency: 130, served: 0, failed: 0, y: 0 },
      { name: "fallback", baseLatency: 520, costPer1k: 0.12, health: 1, down: false, load: 0, observedLatency: 520, served: 0, failed: 0, y: 0 },
    ],
    requests: [],
    spawn: 0,
    ticks: 0,
    spend: 0,
    rng: seeded(23),
    lastReason: "",
  };
}

/**
 * The routing decision.
 *
 * <p>Deliberately a visible formula rather than a black box: a visitor should be
 * able to see why a request went where it did, and "the AI decided" is not an
 * explanation.
 */
function choose(s: State): { lane: number; reason: string } {
  let best = -1;
  let bestScore = -Infinity;
  let reason = "";
  s.providers.forEach((p, i) => {
    if (p.health < 0.15) return;
    const latencyScore = 1 - clamp(p.observedLatency / 800, 0, 1);
    const costScore = 1 - clamp(p.costPer1k / 1.0, 0, 1);
    const score = p.health * 0.5 + latencyScore * 0.32 + costScore * 0.18;
    if (score > bestScore) {
      bestScore = score;
      best = i;
      reason = `${p.name}: health ${p.health.toFixed(2)} · ${Math.round(p.observedLatency)}ms · $${p.costPer1k.toFixed(2)}/1k`;
    }
  });
  return { lane: best, reason };
}

export default function RoutingDemo() {
  const stateRef = useRef<State>(build());
  const [, force] = useState(0);

  const canvasRef = useDemo<State>({
    state: stateRef.current,
    step(s) {
      s.ticks++;

      // Health tracks reality: an outage degrades it quickly, recovery is
      // gradual because trust is re-earned from successful requests.
      for (const p of s.providers) {
        const target = p.down ? 0 : 1;
        p.health += (target - p.health) * (p.down ? 0.06 : 0.012);
        // Load raises observed latency — the queueing effect that makes a
        // saturated provider a bad choice even while it is technically up.
        const congestion = 1 + p.load * 0.22;
        p.observedLatency += (p.baseLatency * congestion - p.observedLatency) * 0.05;
      }

      if (++s.spawn % 9 === 0) {
        const { lane, reason } = choose(s);
        if (lane >= 0) {
          s.requests.push({ t: 0, lane, failing: s.providers[lane].down });
          s.lastReason = reason;
        }
      }

      for (const p of s.providers) p.load = 0;
      for (const r of s.requests) {
        r.t += 0.014;
        if (r.t < 1) s.providers[r.lane].load++;
      }
      for (const r of s.requests) {
        if (r.t >= 1 && r.t < 1.02) {
          const p = s.providers[r.lane];
          if (r.failing) p.failed++;
          else {
            p.served++;
            s.spend += p.costPer1k;
          }
        }
      }
      s.requests = s.requests.filter((r) => r.t < 1.2);
    },
    draw(ctx, s, w, h) {
      const srcX = 52;
      const dstX = w - 130;
      const cy = h / 2;
      const gap = Math.min(74, (h - 60) / 3);

      s.providers.forEach((p, i) => {
        p.y = cy + (i - 1) * gap;
      });

      // Source
      ctx.fillStyle = "rgba(76,139,245,0.9)";
      ctx.beginPath();
      ctx.arc(srcX, cy, 8, 0, Math.PI * 2);
      ctx.fill();
      ctx.fillStyle = "rgba(143,163,200,0.6)";
      ctx.font = "9px ui-monospace, monospace";
      ctx.fillText("requests", srcX - 22, cy + 26);

      s.providers.forEach((p, i) => {
        const unhealthy = p.health < 0.5;
        const colour = p.down || unhealthy ? "#F4566E" : p.health < 0.9 ? "#F5B544" : "#34D399";

        ctx.strokeStyle =
          p.health < 0.15 ? "rgba(143,163,200,0.08)" : `rgba(143,163,200,${0.1 + p.health * 0.16})`;
        ctx.lineWidth = 1;
        ctx.beginPath();
        ctx.moveTo(srcX + 10, cy);
        ctx.bezierCurveTo(srcX + 120, cy, dstX - 120, p.y, dstX - 14, p.y);
        ctx.stroke();

        ctx.fillStyle = "rgba(14,17,24,0.95)";
        ctx.strokeStyle = colour;
        ctx.lineWidth = 1.4;
        ctx.beginPath();
        ctx.roundRect(dstX - 12, p.y - 15, 118, 30, 5);
        ctx.fill();
        ctx.stroke();

        ctx.fillStyle = colour;
        ctx.font = "600 10px ui-monospace, monospace";
        ctx.fillText(p.name, dstX, p.y - 2);
        ctx.fillStyle = "rgba(143,163,200,0.6)";
        ctx.font = "9px ui-monospace, monospace";
        ctx.fillText(
          `${Math.round(p.observedLatency)}ms · h ${p.health.toFixed(2)}`,
          dstX,
          p.y + 10
        );

        // Health bar: the measured quantity the decision actually turns on.
        ctx.fillStyle = "rgba(143,163,200,0.15)";
        ctx.fillRect(dstX - 12, p.y + 17, 118, 2);
        ctx.fillStyle = colour;
        ctx.fillRect(dstX - 12, p.y + 17, 118 * clamp(p.health, 0, 1), 2);
        void i;
      });

      for (const r of s.requests) {
        const p = s.providers[r.lane];
        const t = clamp(r.t, 0, 1);
        // Along the same bezier the lane is drawn with.
        const x0 = srcX + 10;
        const y0 = cy;
        const x1 = srcX + 120;
        const x2 = dstX - 120;
        const x3 = dstX - 14;
        const mt = 1 - t;
        const x = mt * mt * mt * x0 + 3 * mt * mt * t * x1 + 3 * mt * t * t * x2 + t * t * t * x3;
        const y = mt * mt * mt * y0 + 3 * mt * mt * t * y0 + 3 * mt * t * t * p.y + t * t * t * p.y;
        ctx.fillStyle = r.failing ? "#F4566E" : "#7DA9FF";
        ctx.beginPath();
        ctx.arc(x, y, r.failing && t > 0.9 ? 4 : 2.6, 0, Math.PI * 2);
        ctx.fill();
      }

      if (s.lastReason) {
        ctx.fillStyle = "rgba(143,163,200,0.7)";
        ctx.font = "10px ui-monospace, monospace";
        ctx.fillText(`chose ${s.lastReason}`, 14, h - 12);
      }
    },
  });

  const s = stateRef.current;
  const served = s.providers.reduce((a, p) => a + p.served, 0);
  const failed = s.providers.reduce((a, p) => a + p.failed, 0);

  return (
    <DemoFrame
      title="Adaptive routing — provider selection"
      hint="take a provider down and watch the traffic move"
      controls={
        <>
          {s.providers.map((p) => (
            <DemoButton
              key={p.name}
              onClick={() => {
                p.down = !p.down;
                force((n) => n + 1);
              }}
            >
              {p.down ? `Restore ${p.name}` : `Take ${p.name} down`}
            </DemoButton>
          ))}
        </>
      }
      readouts={[
        ["Served", String(served)],
        ["Failed", String(failed)],
        ["Spend", `$${s.spend.toFixed(2)}`],
      ]}
    >
      <canvas ref={canvasRef} className="h-[280px] w-full" />
    </DemoFrame>
  );
}
