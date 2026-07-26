import { useRef, useState } from "react";
import DemoFrame, { DemoButton } from "./DemoFrame";
import { clamp, seeded, useDemo } from "./useDemo";

/**
 * Context paging, driveable.
 *
 * <p>Click a page and watch the request walk the hierarchy. If it is resident
 * you get a hit and the page is simply touched. If it is not, the request
 * continues down until it finds it, the page is promoted into L1, and — because
 * L1 has a fixed capacity — the least recently used resident page is evicted to
 * make room. That is the whole mechanism, and it is far easier to see than to
 * read.
 *
 * <p>The rules mirror the engine's: promotion on reference, LRU eviction, and a
 * cost that grows with the distance the request had to travel.
 */

const TIERS = ["L1", "L2", "L3", "Durable"] as const;
/** L1 is small on purpose: a working set that fits everything is not a working set. */
const CAPACITY = [6, 12, 22, 999];
/** Relative cost of reaching each tier — why a fault is worth avoiding. */
const TIER_COST = [1, 4, 18, 90];

interface Page {
  id: number;
  tier: number;
  /** Logical clock of last reference; drives LRU. */
  used: number;
  /** Animated position, so movement between tiers reads as travel. */
  x: number;
  y: number;
  tx: number;
  ty: number;
  /** Fades after a hit or fault so the outcome is visible for a moment. */
  flash: number;
  flashKind: "hit" | "fault" | "evict" | "";
}

interface Probe {
  page: number;
  /** How far down the hierarchy the request has walked, in tiers. */
  depth: number;
  target: number;
  done: boolean;
}

interface State {
  pages: Page[];
  clock: number;
  probes: Probe[];
  hits: number;
  faults: number;
  /** Rolling token cost, in arbitrary units, to show what paging saves. */
  cost: number;
  rng: () => number;
  auto: boolean;
  autoAt: number;
  w: number;
  h: number;
}

function build(): State {
  const rng = seeded(7);
  const pages: Page[] = Array.from({ length: 40 }, (_, id) => ({
    id,
    // Start with a plausible distribution: a few hot, most cold.
    tier: id < 5 ? 0 : id < 12 ? 1 : id < 22 ? 2 : 3,
    used: id,
    x: 0,
    y: 0,
    tx: 0,
    ty: 0,
    flash: 0,
    flashKind: "",
  }));
  return {
    pages,
    clock: pages.length,
    probes: [],
    hits: 0,
    faults: 0,
    cost: 0,
    rng,
    auto: false,
    autoAt: 0,
    w: 0,
    h: 0,
  };
}

/** Lays pages out in tier bands; called every frame so it survives a resize. */
function place(s: State, w: number, h: number) {
  s.w = w;
  s.h = h;
  const bandH = h / TIERS.length;
  const counts = [0, 0, 0, 0];
  for (const p of s.pages) {
    const i = counts[p.tier]++;
    const perRow = p.tier === 3 ? 12 : 8;
    const col = i % perRow;
    const row = Math.floor(i / perRow);
    const usable = w - 150;
    s.pages[p.id].tx = 122 + (col / (perRow - 1)) * usable;
    s.pages[p.id].ty = bandH * p.tier + bandH * 0.42 + row * 15;
  }
}

function request(s: State, pageId: number) {
  const page = s.pages[pageId];
  s.probes.push({ page: pageId, depth: 0, target: page.tier, done: false });
}

function settle(s: State, probe: Probe) {
  const page = s.pages[probe.page];
  const from = page.tier;
  s.cost += TIER_COST[from];

  if (from === 0) {
    s.hits++;
    page.flash = 1;
    page.flashKind = "hit";
  } else {
    s.faults++;
    page.flash = 1;
    page.flashKind = "fault";
    // Promotion on reference. The page becomes resident, which is the point of
    // the fault: the next reference to it is a hit.
    page.tier = 0;
  }
  page.used = ++s.clock;

  // Eviction. L1 is fixed, so making room is not optional — the least recently
  // used resident page goes back down a tier rather than being discarded.
  const resident = s.pages.filter((p) => p.tier === 0).sort((a, b) => a.used - b.used);
  while (resident.length > CAPACITY[0]) {
    const victim = resident.shift()!;
    victim.tier = 1;
    victim.flash = 1;
    victim.flashKind = "evict";
  }
  // The same pressure cascades: L2 overflowing pushes its coldest into L3.
  for (let t = 1; t < 3; t++) {
    const band = s.pages.filter((p) => p.tier === t).sort((a, b) => a.used - b.used);
    while (band.length > CAPACITY[t]) {
      const victim = band.shift()!;
      victim.tier = t + 1;
    }
  }
}

export default function ContextMmuDemo() {
  const stateRef = useRef<State>(build());
  const [, force] = useState(0);

  const canvasRef = useDemo<State>({
    state: stateRef.current,
    step(s) {
      // Requests walk one tier every few frames, so the descent is legible.
      for (const probe of s.probes) {
        if (probe.done) continue;
        probe.depth += 0.055;
        if (probe.depth >= probe.target) {
          probe.done = true;
          settle(s, probe);
        }
      }
      s.probes = s.probes.filter((p) => !p.done || false).concat();
      s.probes = s.probes.filter((p) => !p.done);

      for (const p of s.pages) {
        p.x += (p.tx - p.x) * 0.12;
        p.y += (p.ty - p.y) * 0.12;
        if (p.flash > 0) p.flash = Math.max(0, p.flash - 0.02);
      }

      if (s.auto && ++s.autoAt % 70 === 0) {
        // A realistic reference pattern: mostly the working set, occasionally
        // something cold. Uniform random access would make paging look useless,
        // which would be a lie about why it works.
        const hot = s.rng() < 0.72;
        const pool = s.pages.filter((p) => (hot ? p.tier <= 1 : p.tier >= 2));
        if (pool.length) request(s, pool[Math.floor(s.rng() * pool.length)].id);
      }
    },
    draw(ctx, s, w, h) {
      place(s, w, h);
      const bandH = h / TIERS.length;

      for (let t = 0; t < TIERS.length; t++) {
        const y = bandH * t;
        ctx.fillStyle = t === 0 ? "rgba(76,139,245,0.05)" : "rgba(143,163,200,0.02)";
        ctx.fillRect(0, y, w, bandH - 1);
        ctx.strokeStyle = "rgba(143,163,200,0.12)";
        ctx.beginPath();
        ctx.moveTo(0, y + bandH - 1);
        ctx.lineTo(w, y + bandH - 1);
        ctx.stroke();

        ctx.fillStyle = t === 0 ? "#7DA9FF" : "rgba(143,163,200,0.65)";
        ctx.font = "600 11px ui-monospace, monospace";
        ctx.fillText(TIERS[t], 16, y + 20);
        ctx.fillStyle = "rgba(143,163,200,0.4)";
        ctx.font = "10px ui-monospace, monospace";
        const n = s.pages.filter((p) => p.tier === t).length;
        ctx.fillText(t === 3 ? `${n} pages` : `${n}/${CAPACITY[t]}`, 16, y + 35);
      }

      // Requests, drawn as a probe descending the hierarchy.
      for (const probe of s.probes) {
        const page = s.pages[probe.page];
        const y = bandH * Math.min(probe.depth, TIERS.length - 1) + bandH * 0.42;
        ctx.strokeStyle = "rgba(76,139,245,0.5)";
        ctx.lineWidth = 1;
        ctx.setLineDash([3, 4]);
        ctx.beginPath();
        ctx.moveTo(page.tx, bandH * 0.42);
        ctx.lineTo(page.tx, y);
        ctx.stroke();
        ctx.setLineDash([]);
        ctx.fillStyle = "#4C8BF5";
        ctx.beginPath();
        ctx.arc(page.tx, y, 4, 0, Math.PI * 2);
        ctx.fill();
      }

      for (const p of s.pages) {
        const resident = p.tier === 0;
        const size = resident ? 9 : p.tier === 1 ? 7 : 5.5;
        let fill = resident ? "rgba(76,139,245,0.9)" : `rgba(143,163,200,${0.5 - p.tier * 0.11})`;
        if (p.flash > 0) {
          if (p.flashKind === "hit") fill = `rgba(52,211,153,${0.3 + p.flash * 0.7})`;
          if (p.flashKind === "fault") fill = `rgba(244,86,110,${0.3 + p.flash * 0.7})`;
          if (p.flashKind === "evict") fill = `rgba(245,181,68,${0.25 + p.flash * 0.6})`;
        }
        ctx.fillStyle = fill;
        ctx.beginPath();
        ctx.roundRect(p.x - size / 2, p.y - size / 2, size, size, 1.5);
        ctx.fill();
      }
    },
  });

  const s = stateRef.current;
  const onClick = (e: React.MouseEvent<HTMLCanvasElement>) => {
    const rect = e.currentTarget.getBoundingClientRect();
    const mx = e.clientX - rect.left;
    const my = e.clientY - rect.top;
    let best = -1;
    let bestD = 22;
    for (const p of s.pages) {
      const d = Math.hypot(p.x - mx, p.y - my);
      if (d < bestD) {
        bestD = d;
        best = p.id;
      }
    }
    if (best >= 0) request(s, best);
  };

  const total = s.hits + s.faults;
  return (
    <DemoFrame
      title="Context MMU — paging"
      hint="click any page to reference it"
      controls={
        <>
          <DemoButton
            primary
            onClick={() => {
              s.auto = !s.auto;
              force((n) => n + 1);
            }}
          >
            {s.auto ? "Pause traffic" : "Run traffic"}
          </DemoButton>
          <DemoButton
            onClick={() => {
              stateRef.current = Object.assign(s, build());
              force((n) => n + 1);
            }}
          >
            Reset
          </DemoButton>
        </>
      }
      readouts={[
        ["Hits", String(s.hits)],
        ["Faults", String(s.faults)],
        ["Hit rate", total ? `${Math.round((s.hits / total) * 100)}%` : "—"],
        ["Cost", `${s.cost} u`],
      ]}
    >
      <canvas
        ref={canvasRef}
        onClick={onClick}
        className="h-[300px] w-full cursor-pointer"
      />
    </DemoFrame>
  );
}

export { clamp };
