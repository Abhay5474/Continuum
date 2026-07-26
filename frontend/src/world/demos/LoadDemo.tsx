import { useRef, useState } from "react";
import DemoFrame, { DemoToggle } from "./DemoFrame";
import { pulse } from "../activity";
import { clamp, useDemo } from "./useDemo";

/**
 * Adaptive concurrency and load shedding, driveable.
 *
 * <p>Push the arrival rate past what the system can serve and watch what
 * happens. With shedding off, the queue grows without bound and latency grows
 * with it — every request gets slower, including the ones that would have
 * succeeded, and eventually all of them time out. That failure mode is the
 * reason shedding exists, so it has to be visible.
 *
 * <p>With shedding on, the concurrency limit is adjusted from observed latency
 * and the excess is refused immediately. Fewer requests are accepted; the ones
 * that are accepted are served quickly. Refusing work is not the system
 * failing — it is the system choosing, and the graph makes the trade legible.
 */

interface State {
  /** Requests per tick the user is asking for. */
  arrival: number;
  shedding: boolean;
  /** Adaptive concurrency limit. */
  limit: number;
  inFlight: number;
  queue: number[];
  /** Rolling latency estimate, in ticks. */
  latency: number;
  served: number;
  shed: number;
  timedOut: number;
  history: { latency: number; queue: number; shed: number }[];
  ticks: number;
  carry: number;
}

/** How much work the system can actually retire per tick. */
const SERVICE_RATE = 1.0;
/**
 * Beyond this queueing delay a request is abandoned.
 *
 * <p>Short on purpose. The point of the demo is that an unbounded queue
 * eventually fails everything, and a timeout the visitor has to wait a minute to
 * reach would never show it.
 */
const TIMEOUT = 110;

function build(prev?: State): State {
  return {
    arrival: prev?.arrival ?? 0.6,
    shedding: prev?.shedding ?? true,
    limit: 12,
    inFlight: 0,
    queue: [],
    latency: 20,
    served: 0,
    shed: 0,
    timedOut: 0,
    history: [],
    ticks: 0,
    carry: 0,
  };
}

export default function LoadDemo() {
  const stateRef = useRef<State>(build());
  const [, force] = useState(0);

  const canvasRef = useDemo<State>({
    state: stateRef.current,
    step(s) {
      s.ticks++;

      // Arrivals.
      s.carry += s.arrival;
      while (s.carry >= 1) {
        s.carry -= 1;
        if (s.shedding && s.queue.length + s.inFlight >= s.limit) {
          // Refused at the door, immediately and cheaply. The caller learns now
          // rather than after a timeout.
          s.shed++;
          pulse(0.03);
        } else {
          s.queue.push(0);
        }
      }

      // Service.
      let capacity = SERVICE_RATE;
      while (capacity > 0 && s.queue.length > 0) {
        s.queue.shift();
        s.served++;
        capacity -= 1;
      }

      // Ageing and timeouts — the cost of an unbounded queue.
      for (let i = 0; i < s.queue.length; i++) s.queue[i]++;
      const before = s.queue.length;
      s.queue = s.queue.filter((age) => age < TIMEOUT);
      const died = before - s.queue.length;
      s.timedOut += died;
      // A request dying in the queue is a failure, and should read as one.
      if (died > 0) pulse(0.1 * died);

      s.inFlight = Math.min(s.queue.length, 3);
      const observed = s.queue.length / SERVICE_RATE;
      s.latency += (observed - s.latency) * 0.06;

      // The controller: latency above target means the limit is too generous.
      if (s.shedding) {
        const target = 45;
        const err = target - s.latency;
        s.limit = clamp(s.limit + err * 0.004, 3, 90);
      }

      if (s.ticks % 2 === 0) {
        s.history.push({ latency: s.latency, queue: s.queue.length, shed: s.shed });
        if (s.history.length > 190) s.history.shift();
      }
    },
    draw(ctx, s, w, h) {
      const padL = 46;
      const padR = 14;
      const plotH = h - 58;
      const plotW = w - padL - padR;
      const maxLatency = 150;

      ctx.strokeStyle = "rgba(143,163,200,0.1)";
      ctx.lineWidth = 1;
      for (let i = 0; i <= 3; i++) {
        const y = 12 + (plotH / 3) * i;
        ctx.beginPath();
        ctx.moveTo(padL, y);
        ctx.lineTo(w - padR, y);
        ctx.stroke();
        ctx.fillStyle = "rgba(143,163,200,0.4)";
        ctx.font = "9px ui-monospace, monospace";
        ctx.fillText(String(Math.round(maxLatency - (maxLatency / 3) * i)), 8, y + 3);
      }
      ctx.fillStyle = "rgba(143,163,200,0.4)";
      ctx.font = "9px ui-monospace, monospace";
      ctx.fillText("queue delay (ticks)", padL, h - 32);

      // The timeout line: cross it and requests are being abandoned.
      const ty = 12 + plotH * (1 - TIMEOUT / maxLatency);
      ctx.strokeStyle = "rgba(244,86,110,0.35)";
      ctx.setLineDash([4, 4]);
      ctx.beginPath();
      ctx.moveTo(padL, ty);
      ctx.lineTo(w - padR, ty);
      ctx.stroke();
      ctx.setLineDash([]);
      ctx.fillStyle = "rgba(244,86,110,0.7)";
      ctx.fillText("timeout", w - padR - 44, ty - 4);

      if (s.history.length > 1) {
        const px = (i: number) => padL + (i / (s.history.length - 1)) * plotW;
        const py = (v: number) => 12 + plotH * (1 - clamp(v / maxLatency, 0, 1));

        ctx.strokeStyle = "#4C8BF5";
        ctx.lineWidth = 1.6;
        ctx.beginPath();
        s.history.forEach((p, i) => (i ? ctx.lineTo(px(i), py(p.latency)) : ctx.moveTo(px(i), py(p.latency))));
        ctx.stroke();

        ctx.strokeStyle = "rgba(143,163,200,0.4)";
        ctx.lineWidth = 1;
        ctx.beginPath();
        s.history.forEach((p, i) => (i ? ctx.lineTo(px(i), py(p.queue)) : ctx.moveTo(px(i), py(p.queue))));
        ctx.stroke();
      }

      // Legend and the live limit, which is the thing being controlled.
      ctx.font = "9px ui-monospace, monospace";
      ctx.fillStyle = "#4C8BF5";
      ctx.fillText("— latency", padL + 120, h - 32);
      ctx.fillStyle = "rgba(143,163,200,0.6)";
      ctx.fillText("— queue depth", padL + 186, h - 32);

      ctx.fillStyle = s.shedding ? "rgba(52,211,153,0.85)" : "rgba(244,86,110,0.85)";
      ctx.font = "10px ui-monospace, monospace";
      ctx.fillText(
        s.shedding
          ? `concurrency limit ${Math.round(s.limit)} · excess refused at the door`
          : "no limit · queue grows unbounded, every request slows",
        padL,
        h - 12
      );
    },
  });

  const s = stateRef.current;
  return (
    <DemoFrame
      title="Adaptive concurrency — under load"
      hint="raise arrivals past capacity, then toggle shedding"
      controls={
        <>
          <label className="flex items-center gap-2 text-[11px] text-slate-400">
            Arrivals
            <input
              type="range"
              min={10}
              max={260}
              value={Math.round(s.arrival * 100)}
              onChange={(e) => {
                s.arrival = Number(e.target.value) / 100;
                force((n) => n + 1);
              }}
              className="h-1 w-32 cursor-pointer accent-aurora"
            />
            <span className="readout w-10 text-slate-300">{s.arrival.toFixed(2)}×</span>
          </label>
          <DemoToggle
            label="Load shedding"
            on={s.shedding}
            onChange={(v) => {
              s.shedding = v;
              force((n) => n + 1);
            }}
          />
        </>
      }
      readouts={[
        ["Served", String(s.served)],
        ["Shed", String(s.shed)],
        ["Timed out", String(s.timedOut)],
        ["Queue", String(s.queue.length)],
      ]}
    >
      <canvas ref={canvasRef} className="h-[260px] w-full" />
    </DemoFrame>
  );
}
