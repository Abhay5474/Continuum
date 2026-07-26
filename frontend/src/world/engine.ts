/**
 * The Continuum world — one spatial system, many states.
 *
 * <p>The landing page is not a stack of sections with animations bolted on; it
 * is a single population of nodes that reorganises as you scroll. Scene changes
 * are not transitions between different graphics, they are the same nodes moving
 * to new targets, which is why the page reads as one continuous space rather
 * than a slideshow. It also happens to be an honest picture of the product: one
 * fabric, many arrangements.
 *
 * <p>Rendered with a hand-written perspective projection onto Canvas 2D rather
 * than WebGL. A node-and-edge field needs depth, not shading, and 2D gives one
 * code path on every device — no context loss, no fallback branch, and nothing
 * added to the bundle. The renderer is isolated here, so a WebGL backend could
 * replace it without touching anything above.
 *
 * <p>Everything expensive is guarded: the loop stops when the tab is hidden or
 * the canvas is off-screen, the population scales to the viewport, device pixel
 * ratio is capped, and reduced-motion draws one settled frame and stops.
 */

export type NodeKind =
  | "agent"
  | "context"
  | "model"
  | "task"
  | "memory"
  | "core";

export interface WorldNode {
  /** Current position in world space. */
  x: number;
  y: number;
  z: number;
  /** Where the current formation wants this node. */
  tx: number;
  ty: number;
  tz: number;
  kind: NodeKind;
  /** 0..1 — how strongly this node reads as active right now. */
  energy: number;
  tenergy: number;
  /** Per-node phase so pulses do not fire in lockstep. */
  phase: number;
  /** Formation-assigned index, stable across formations. */
  i: number;
}

export interface WorldEdge {
  a: number;
  b: number;
  /** 0..1 — edge opacity target; 0 means "this connection does not exist here". */
  strength: number;
  /** Current interpolated strength. */
  s: number;
}

/** A travelling unit of work drawn along an edge. */
interface Packet {
  edge: number;
  t: number;
  speed: number;
  hue: string;
}

export interface Formation {
  /** Positions every node and returns the edges that exist in this state. */
  layout(nodes: WorldNode[], w: number, h: number): WorldEdge[];
  /** How much traffic flows here, 0..1. */
  flow: number;
  /** Camera distance multiplier — lets a scene push in or pull back. */
  dolly?: number;
}

export interface WorldOptions {
  /** Scroll progress 0..1 across the whole narrative. */
  progress: () => number;
  formations: Formation[];
  /** Pointer in normalised device coords, -1..1. */
  pointer: () => { x: number; y: number };
  accent: string;
  dim: string;
}

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

/** Population sized to the viewport: a phone does not need 260 nodes to read. */
function populationFor(width: number): number {
  if (width < 640) return 90;
  if (width < 1024) return 150;
  return 240;
}

export function createWorld(canvas: HTMLCanvasElement, opts: WorldOptions) {
  const ctx = canvas.getContext("2d", { alpha: true })!;
  const reduced =
    typeof window !== "undefined" &&
    window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;

  let width = 0;
  let height = 0;
  let dpr = 1;
  let nodes: WorldNode[] = [];
  let edges: WorldEdge[] = [];
  let packets: Packet[] = [];
  let raf = 0;
  let running = false;
  let visible = true;
  let lastFormation = -1;

  /** Smoothed camera state, so pointer motion feels like mass rather than a jump cut. */
  const cam = { rx: 0, ry: 0, z: 0, trx: 0, try_: 0, tz: 0 };

  function build() {
    const n = populationFor(width);
    nodes = Array.from({ length: n }, (_, i) => ({
      x: (Math.random() - 0.5) * width * 1.6,
      y: (Math.random() - 0.5) * height * 1.6,
      z: Math.random() * 1400 - 700,
      tx: 0,
      ty: 0,
      tz: 0,
      kind: "task" as NodeKind,
      energy: 0,
      tenergy: 0,
      phase: Math.random() * Math.PI * 2,
      i,
    }));
    lastFormation = -1;
  }

  function resize() {
    const rect = canvas.getBoundingClientRect();
    dpr = Math.min(2, window.devicePixelRatio || 1);
    width = rect.width;
    height = rect.height;
    canvas.width = Math.floor(width * dpr);
    canvas.height = Math.floor(height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    if (nodes.length === 0 || populationFor(width) !== nodes.length) {
      build();
    }
  }

  /**
   * Which formation the scroll position sits in, plus how far between it and
   * the next. The blend is what makes one scene become the next instead of
   * cutting to it.
   */
  function formationAt(p: number) {
    const n = opts.formations.length;
    const scaled = clamp(p, 0, 0.9999) * (n - 1);
    const index = Math.floor(scaled);
    return { index, next: Math.min(n - 1, index + 1), t: scaled - index };
  }

  /** Perspective projection. Returns null for anything behind the camera. */
  function project(x: number, y: number, z: number) {
    // Rotate about Y then X by the camera's current attitude.
    const cosY = Math.cos(cam.ry);
    const sinY = Math.sin(cam.ry);
    const cosX = Math.cos(cam.rx);
    const sinX = Math.sin(cam.rx);

    const x1 = x * cosY - z * sinY;
    const z1 = x * sinY + z * cosY;
    const y1 = y * cosX - z1 * sinX;
    const z2 = y * sinX + z1 * cosX;

    const depth = z2 + 1350 + cam.z;
    if (depth < 60) return null;
    // Focal length. Chosen so the field fills the frame with presence rather
    // than sitting far away as a dusting of specks.
    const k = 1400 / depth;
    return { sx: width / 2 + x1 * k, sy: height / 2 + y1 * k, k, depth };
  }

  function applyFormation(p: number) {
    const { index, next, t } = formationAt(p);

    // Layouts are recomputed only when the pair of formations changes; between
    // those points the targets are simply blended, which is far cheaper than
    // re-laying out 240 nodes every frame.
    if (index !== lastFormation) {
      const a = opts.formations[index];
      const b = opts.formations[next];
      const ea = a.layout(nodes, width, height);
      const targetsA = nodes.map((n) => ({ x: n.tx, y: n.ty, z: n.tz, k: n.kind, e: n.tenergy }));
      const eb = b.layout(nodes, width, height);
      const targetsB = nodes.map((n) => ({ x: n.tx, y: n.ty, z: n.tz, k: n.kind, e: n.tenergy }));

      pairs = { a: targetsA, b: targetsB, ea, eb };
      lastFormation = index;
      edgesStale = true;
    }

    if (!pairs) return;
    for (let i = 0; i < nodes.length; i++) {
      const A = pairs.a[i];
      const B = pairs.b[i];
      const n = nodes[i];
      n.tx = lerp(A.x, B.x, t);
      n.ty = lerp(A.y, B.y, t);
      n.tz = lerp(A.z, B.z, t);
      n.tenergy = lerp(A.e, B.e, t);
      // Kind flips at the halfway point rather than blending: a node is either
      // a memory page or an agent, never half of each.
      n.kind = t < 0.5 ? A.k : B.k;
    }

    // Edges from both states coexist during the blend, each fading in or out,
    // so connections form and dissolve rather than popping.
    //
    // The array is rebuilt only when the formation pair changes, never per
    // frame: each edge carries an interpolated opacity that has to survive
    // between frames, and recreating the objects would reset it to zero every
    // time — which is exactly the bug that left the structure invisible.
    if (edges.length !== pairs.ea.length + pairs.eb.length || edgesStale) {
      edges = [
        ...pairs.ea.map((e) => ({ ...e, s: 0 })),
        ...pairs.eb.map((e) => ({ ...e, s: 0 })),
      ];
      edgesStale = false;
    }
    const split = pairs.ea.length;
    for (let i = 0; i < edges.length; i++) {
      const src = i < split ? pairs.ea[i] : pairs.eb[i - split];
      edges[i].strength = src.strength * (i < split ? 1 - t : t);
    }
  }

  /** Set when the formation pair changes, so the edge array is rebuilt once. */
  let edgesStale = true;

  let pairs:
    | {
        a: { x: number; y: number; z: number; k: NodeKind; e: number }[];
        b: { x: number; y: number; z: number; k: NodeKind; e: number }[];
        ea: WorldEdge[];
        eb: WorldEdge[];
      }
    | null = null;

  function spawnPackets(flow: number) {
    if (edges.length === 0) return;
    const want = Math.floor(flow * (width < 640 ? 10 : 26));
    while (packets.length < want) {
      const edge = Math.floor(Math.random() * edges.length);
      if (edges[edge].strength < 0.2) break;
      packets.push({
        edge,
        t: Math.random(),
        speed: 0.004 + Math.random() * 0.006,
        hue: Math.random() < 0.18 ? opts.accent : opts.dim,
      });
    }
    if (packets.length > want) packets.length = want;
  }

  function frame() {
    if (!running) return;

    const p = opts.progress();
    const { index, next, t } = formationAt(p);
    const fa = opts.formations[index];
    const fb = opts.formations[next];

    applyFormation(p);

    const ptr = opts.pointer();
    // The camera answers the pointer, but slowly and by a small amount — the
    // world should feel heavy, not attached to the mouse.
    cam.trx = ptr.y * 0.14;
    cam.try_ = ptr.x * 0.2;
    cam.tz = lerp(0, -260, lerp(fa.dolly ?? 0, fb.dolly ?? 0, t));
    cam.rx += (cam.trx - cam.rx) * 0.045;
    cam.ry += (cam.try_ - cam.ry) * 0.045;
    cam.z += (cam.tz - cam.z) * 0.03;

    // Settle nodes toward their targets. The easing is what reads as the
    // system reorganising itself under its own power.
    const now = performance.now() / 1000;
    for (const n of nodes) {
      n.x += (n.tx - n.x) * 0.055;
      n.y += (n.ty - n.y) * 0.055;
      n.z += (n.tz - n.z) * 0.055;
      n.energy += (n.tenergy - n.energy) * 0.06;
    }
    for (const e of edges) e.s += (e.strength - e.s) * 0.08;

    spawnPackets(lerp(fa.flow, fb.flow, t));
    for (const pk of packets) {
      pk.t += pk.speed;
      if (pk.t > 1) {
        pk.t = 0;
        pk.edge = Math.floor(Math.random() * edges.length);
      }
    }

    draw(now);
    raf = requestAnimationFrame(frame);
  }

  function draw(now: number) {
    ctx.clearRect(0, 0, width, height);

    // Project once per frame; everything downstream reads these.
    const proj = nodes.map((n) => project(n.x, n.y, n.z));

    // --- connections -------------------------------------------------------
    ctx.lineWidth = 1;
    for (const e of edges) {
      if (e.s < 0.02) continue;
      const A = proj[e.a];
      const B = proj[e.b];
      if (!A || !B) continue;
      // Depth fades the line, so the far side of the field recedes properly.
      const depthFade = clamp((A.k + B.k) * 0.55, 0.05, 1);
      ctx.strokeStyle = withAlpha(opts.dim, e.s * 0.5 * depthFade);
      ctx.beginPath();
      ctx.moveTo(A.sx, A.sy);
      ctx.lineTo(B.sx, B.sy);
      ctx.stroke();
    }

    // --- packets -----------------------------------------------------------
    for (const pk of packets) {
      const e = edges[pk.edge];
      if (!e || e.s < 0.15) continue;
      const A = proj[e.a];
      const B = proj[e.b];
      if (!A || !B) continue;
      const x = lerp(A.sx, B.sx, pk.t);
      const y = lerp(A.sy, B.sy, pk.t);
      const k = lerp(A.k, B.k, pk.t);
      const r = clamp(k * 2.1, 0.8, 3.2);
      ctx.fillStyle = withAlpha(pk.hue, clamp(k * 0.95, 0.2, 1));
      ctx.beginPath();
      ctx.arc(x, y, r, 0, Math.PI * 2);
      ctx.fill();
    }

    // --- nodes -------------------------------------------------------------
    // Painted far-to-near so nearer nodes occlude correctly.
    const order = nodes
      .map((_, i) => ({ i, d: proj[i]?.depth ?? Infinity }))
      .sort((a, b) => b.d - a.d);

    for (const { i } of order) {
      const P = proj[i];
      if (!P) continue;
      const n = nodes[i];
      const pulse = 0.75 + 0.25 * Math.sin(now * 1.6 + n.phase);
      const r = clamp(P.k * (1.9 + n.energy * 3.4) * pulse, 0.5, 9);
      const alpha = clamp(P.k * (0.34 + n.energy * 0.62), 0.05, 0.95);

      ctx.fillStyle = withAlpha(n.energy > 0.55 ? opts.accent : opts.dim, alpha);
      ctx.beginPath();
      ctx.arc(P.sx, P.sy, r, 0, Math.PI * 2);
      ctx.fill();

      // Only genuinely active nodes get a halo. If everything glowed, nothing
      // would read as significant.
      if (n.energy > 0.7 && P.k > 0.5) {
        ctx.fillStyle = withAlpha(opts.accent, alpha * 0.16);
        ctx.beginPath();
        ctx.arc(P.sx, P.sy, r * 3.8, 0, Math.PI * 2);
        ctx.fill();
      }
    }
  }

  /** Draws exactly one settled frame — the reduced-motion and paused path. */
  function drawStill() {
    applyFormation(opts.progress());
    for (const n of nodes) {
      n.x = n.tx;
      n.y = n.ty;
      n.z = n.tz;
      n.energy = n.tenergy;
    }
    for (const e of edges) e.s = e.strength;
    draw(0);
  }

  const onResize = () => {
    resize();
    if (reduced || !running) drawStill();
  };

  const onVisibility = () => {
    if (document.hidden) stop();
    else if (visible) start();
  };

  function start() {
    if (running || reduced) return;
    running = true;
    raf = requestAnimationFrame(frame);
  }

  function stop() {
    running = false;
    cancelAnimationFrame(raf);
  }

  resize();
  window.addEventListener("resize", onResize, { passive: true });
  document.addEventListener("visibilitychange", onVisibility);

  // Off-screen canvases cost nothing: the loop simply stops.
  const io = new IntersectionObserver(
    ([entry]) => {
      visible = entry.isIntersecting;
      if (visible && !document.hidden) start();
      else stop();
    },
    { threshold: 0 }
  );
  io.observe(canvas);

  if (reduced) {
    drawStill();
  } else {
    start();
  }

  return {
    /** Re-settles the still frame when scroll moves and motion is reduced. */
    nudge() {
      if (reduced) drawStill();
    },
    destroy() {
      stop();
      io.disconnect();
      window.removeEventListener("resize", onResize);
      document.removeEventListener("visibilitychange", onVisibility);
    },
  };
}

/** Applies an alpha to a hex colour without allocating a colour object. */
function withAlpha(hex: string, a: number): string {
  const v = hex.replace("#", "");
  const r = parseInt(v.slice(0, 2), 16);
  const g = parseInt(v.slice(2, 4), 16);
  const b = parseInt(v.slice(4, 6), 16);
  return `rgba(${r},${g},${b},${a.toFixed(3)})`;
}

export { clamp, lerp };
