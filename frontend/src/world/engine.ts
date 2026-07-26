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
  /**
   * Overall presence, 0..1. The console runs this well under 1.
   *
   * <p>The same field that carries a marketing page would be an obstruction
   * behind a table of live numbers. Turning it down rather than turning it off
   * keeps the two halves of the product recognisably the same thing while
   * leaving the data unambiguously in front.
   */
  intensity?: number;
  /** Population multiplier. Fewer nodes where the field is only a signature. */
  density?: number;
  /** Suppresses the cursor lamp where a moving highlight would distract. */
  lamp?: boolean;
}

import { activeDemos, drainSurge } from "./activity";

/** Camera's focal distance into the field. The type plane rides on this. */
const BASE_DEPTH = 1050;

const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));
const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

/** Population sized to the viewport: a phone does not need 260 nodes to read. */
function populationFor(width: number): number {
  if (width < 640) return 90;
  if (width < 1024) return 150;
  return 240;
}

export function createWorld(
  canvas: HTMLCanvasElement,
  opts: WorldOptions,
  nearCanvas?: HTMLCanvasElement
) {
  const ctx = canvas.getContext("2d", { alpha: true })!;
  // A second target for everything closer to the camera than the type plane.
  // The page's headline is a DOM layer sandwiched between the two, so the field
  // genuinely passes in front of and behind the words instead of being a
  // backdrop they sit on. This is the difference between a lit volume and a
  // wallpaper, and it costs one extra canvas.
  const nctx = nearCanvas?.getContext("2d", { alpha: true }) ?? null;
  const intensity = opts.intensity ?? 1;
  const density = opts.density ?? 1;
  const lampOn = opts.lamp ?? true;
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
  let halfFrame = false;
  /** Transient activity handed over by the interactive explainers. */
  let surge = 0;

  /** Smoothed camera state, so pointer motion feels like mass rather than a jump cut. */
  const cam = { rx: 0, ry: 0, z: 0, trx: 0, try_: 0, tz: 0 };

  /**
   * Frame scratch, allocated once.
   *
   * <p>Projecting with map() and sorting a fresh array of objects every frame
   * produced 240 short-lived allocations per frame, which is enough GC churn to
   * cost real frames once the page also has interactive demos running. These are
   * reused in place instead.
   */
  let projX = new Float32Array(0);
  let projY = new Float32Array(0);
  let projK = new Float32Array(0);
  let projD = new Float32Array(0);
  let projOk = new Uint8Array(0);
  let order: Int32Array = new Int32Array(0);

  let lampGradient: CanvasGradient | null = null;
  let lampReach = -1;

  /** Quantisation steps for edge opacity when batching. */
  const EDGE_LEVELS = 5;
  /** Reused per-frame edge batches: [near][hot][level] -> flat x,y pairs. */
  const batches: number[][] = Array.from({ length: EDGE_LEVELS * 4 }, () => []);

  function ensureScratch(n: number) {
    if (projX.length === n) return;
    projX = new Float32Array(n);
    projY = new Float32Array(n);
    projK = new Float32Array(n);
    projD = new Float32Array(n);
    projOk = new Uint8Array(n);
    order = new Int32Array(n);
  }

  function build() {
    const n = Math.max(24, Math.round(populationFor(width) * density));
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
    // Capped below the display's own ratio on purpose.
    //
    // The field is soft-edged blobs and low-alpha lines with no text and no hard
    // detail, so the extra pixels of a 2x buffer are invisible here — but they
    // are half the fill cost, and the page also runs interactive demos that do
    // have text and do need the resolution. Measured: world and demo each hold
    // 60fps alone and drop to 40 together at 2x; this is what buys that back.
    dpr = Math.min(1.5, window.devicePixelRatio || 1);
    width = rect.width;
    height = rect.height;
    canvas.width = Math.floor(width * dpr);
    canvas.height = Math.floor(height * dpr);
    ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    if (nearCanvas && nctx) {
      nearCanvas.width = canvas.width;
      nearCanvas.height = canvas.height;
      nctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    }
    if (nodes.length === 0 || Math.max(24, Math.round(populationFor(width) * density)) !== nodes.length) {
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
    if (n === 1) return { index: 0, next: 0, t: 0 };
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

    // Base distance is deliberately inside the field's own z-extent, so a
    // fraction of the population ends up in front of the focal plane and reads
    // as foreground. A camera parked outside the volume can only ever produce a
    // backdrop, however well it is lit.
    const depth = z2 + BASE_DEPTH + cam.z;
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

    // Half rate while an instrument is live. The world keeps simulating so it
    // never jumps when the demo stops; it simply draws every other frame.
    if (activeDemos() > 0) {
      halfFrame = !halfFrame;
      if (halfFrame) {
        raf = requestAnimationFrame(frame);
        return;
      }
    }

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

    // Work happening in an instrument is work happening in the fabric, so the
    // field answers: more traffic and a brief lift in activity. Read once per
    // frame and decayed here, so the response fades on its own.
    surge = drainSurge();
    spawnPackets(clamp(lerp(fa.flow, fb.flow, t) + surge * 1.3, 0, 2.4));
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

  /**
   * Where the light is, in screen space.
   *
   * <p>The cursor is treated as a lamp moving through the volume rather than a
   * pointer on a surface: nodes near it brighten and swell, nodes far from it
   * fall away. Without a light there is no read of form — every node is the same
   * value and the field looks like confetti no matter how well it is arranged.
   */
  function lightAt() {
    const ptr = opts.pointer();
    return { x: (ptr.x * 0.5 + 0.5) * width, y: (ptr.y * 0.5 + 0.5) * height };
  }

  /** Depth cue: distant matter loses contrast to the atmosphere between. */
  function fog(depth: number) {
    return Math.exp(-Math.max(0, depth - 700) / 1500);
  }

  /**
   * The plane the page's typography occupies. Nearer than this draws in front.
   *
   * <p>It tracks the camera rather than sitting at a fixed distance. As an
   * absolute constant it worked at the top of the page and then failed silently:
   * once a scene dollied in, the whole formation fell in front of it, the far
   * canvas rendered nothing at all, and the type ended up behind every node
   * instead of inside the field. Anchoring it to the focal distance puts the
   * words in the middle of the volume by construction — roughly half the
   * population in front, half behind — at every scene.
   */
  const typePlane = () => BASE_DEPTH + cam.z;

  function draw(now: number) {
    ctx.clearRect(0, 0, width, height);
    nctx?.clearRect(0, 0, width, height);

    const light = lightAt();

    // The lamp made visible. Very low alpha — this is the light in the medium,
    // not a glowing blob; without it the cursor brightens things for no visible
    // reason, which reads as a bug rather than as illumination.
    if (lampOn) {
      const reachBg = Math.min(width, height) * 0.5;
      // Built at the origin once and translated into place, so the gradient is
      // not reconstructed and the fill covers only the lamp's own footprint.
      if (!lampGradient || lampReach !== reachBg) {
        lampReach = reachBg;
        lampGradient = ctx.createRadialGradient(0, 0, 0, 0, 0, reachBg);
        lampGradient.addColorStop(0, withAlpha(opts.accent, 0.06));
        lampGradient.addColorStop(0.55, withAlpha(opts.accent, 0.018));
        lampGradient.addColorStop(1, withAlpha(opts.accent, 0));
      }
      ctx.save();
      ctx.translate(light.x, light.y);
      ctx.fillStyle = lampGradient;
      ctx.fillRect(-reachBg, -reachBg, reachBg * 2, reachBg * 2);
      ctx.restore();
    }
    // Reach scales with the viewport so the pool of light is the same fraction
    // of the frame on a phone as on a display.
    const reach = Math.min(width, height) * 0.42;

    const plane = typePlane();
    /** Picks the render target by depth, which is what puts type inside the volume. */
    const target = (depth: number) => (nctx && depth < plane ? nctx : ctx);

    /**
     * How much of a mark survives on the near plane.
     *
     * <p>Matter in front of the type is there to give the frame a foreground,
     * not to be read. Left at full strength it competes with the copy for
     * attention and the page becomes hard to use — so anything drawn in front of
     * the words is heavily damped.
     */
    const nearDamp = (depth: number) => (nctx && depth < plane ? 0.42 : 1);

    /** 0..1 — how strongly the lamp falls on a projected point. */
    const lit = (sx: number, sy: number) => {
      if (!lampOn) return 0;
      const d = Math.hypot(sx - light.x, sy - light.y);
      const f = 1 - Math.min(1, d / reach);
      // Squared falloff reads as light rather than as a flat circular mask.
      return f * f;
    };

    // Project once per frame into preallocated buffers.
    ensureScratch(nodes.length);
    for (let i = 0; i < nodes.length; i++) {
      const n = nodes[i];
      const q = project(n.x, n.y, n.z);
      if (q) {
        projOk[i] = 1;
        projX[i] = q.sx;
        projY[i] = q.sy;
        projK[i] = q.k;
        projD[i] = q.depth;
      } else {
        projOk[i] = 0;
        projD[i] = Infinity;
      }
      order[i] = i;
    }
    /** Reads like the old projection result, without allocating one. */
    const proj = (i: number) =>
      projOk[i] ? { sx: projX[i], sy: projY[i], k: projK[i], depth: projD[i] } : null;

    // --- connections -------------------------------------------------------
    // Edges are batched into a handful of paths rather than stroked one at a time.
    //
    // A formation carries several hundred connections, and during a blend both
    // formations' edges are live at once — over a thousand stroke calls a frame,
    // which was the largest single cost in the renderer. Alpha is quantised into
    // a few steps so edges that look alike are drawn together; the banding is
    // invisible at these opacities and the draw calls drop by two orders of
    // magnitude.
    for (let b = 0; b < batches.length; b++) batches[b].length = 0;

    for (const e of edges) {
      if (e.s < 0.02) continue;
      const A = proj(e.a);
      const B = proj(e.b);
      if (!A || !B) continue;
      const mid = (A.depth + B.depth) / 2;
      const depthFade = clamp((A.k + B.k) * 0.55, 0.05, 1) * fog(mid);
      // A connection near the lamp is legible; the rest of the lattice stays as
      // structure you sense rather than read. This is what makes cursor
      // proximity reveal local topology without the whole screen reacting.
      const glow = Math.max(lit(A.sx, A.sy), lit(B.sx, B.sy));
      // Connections carry the surge too: the paths the work is taking.
      const alpha = e.s * (0.34 + glow * 0.7 + surge * 0.4) * depthFade * nearDamp(mid);
      if (alpha < 0.015) continue;

      const near = nctx && mid < plane ? 1 : 0;
      const hot = glow > 0.35 ? 1 : 0;
      const level = Math.min(EDGE_LEVELS - 1, Math.floor(alpha * EDGE_LEVELS * 1.5));
      batches[(near * 2 + hot) * EDGE_LEVELS + level].push(A.sx, A.sy, B.sx, B.sy);
    }

    for (let near = 0; near < 2; near++) {
      const g = near ? nctx : ctx;
      if (!g) continue;
      for (let hot = 0; hot < 2; hot++) {
        for (let level = 0; level < EDGE_LEVELS; level++) {
          const pts = batches[(near * 2 + hot) * EDGE_LEVELS + level];
          if (pts.length === 0) continue;
          g.lineWidth = hot ? 1.6 : 1;
          g.strokeStyle = withAlpha(
            hot ? opts.accent : opts.dim,
            ((level + 0.6) / (EDGE_LEVELS * 1.5)) * intensity
          );
          g.beginPath();
          for (let i = 0; i < pts.length; i += 4) {
            g.moveTo(pts[i], pts[i + 1]);
            g.lineTo(pts[i + 2], pts[i + 3]);
          }
          g.stroke();
        }
      }
    }

    // --- packets -----------------------------------------------------------
    for (const pk of packets) {
      const e = edges[pk.edge];
      if (!e || e.s < 0.15) continue;
      const A = proj(e.a);
      const B = proj(e.b);
      if (!A || !B) continue;
      const x = lerp(A.sx, B.sx, pk.t);
      const y = lerp(A.sy, B.sy, pk.t);
      const k = lerp(A.k, B.k, pk.t);
      const depth = lerp(A.depth, B.depth, pk.t);
      const g = target(depth);
      const r = clamp(k * 2.1, 0.8, 3.4);
      g.fillStyle = withAlpha(pk.hue, clamp(k * 0.95 * fog(depth), 0.2, 1) * nearDamp(depth) * intensity);
      g.beginPath();
      g.arc(x, y, r, 0, Math.PI * 2);
      g.fill();
    }

    // --- nodes -------------------------------------------------------------
    // Painted far-to-near so nearer nodes occlude correctly.
    // Painter's order, far to near, sorted in place over an index buffer.
    const sorted = Array.prototype.sort.call(order, (a: number, b: number) => projD[b] - projD[a]);
    void sorted;

    for (let oi = 0; oi < order.length; oi++) {
      const i = order[oi];
      const P = proj(i);
      if (!P) continue;
      const n = nodes[i];
      const g = target(P.depth);
      const glow = lit(P.sx, P.sy);
      const haze = fog(P.depth);
      const pulse = 0.75 + 0.25 * Math.sin(now * 1.6 + n.phase);

      // Matter very close to the camera goes soft and large, the way an
      // out-of-focus foreground does. It is what gives the frame a near plane.
      const nearness = clamp((520 - P.depth) / 460, 0, 1);

      const r = clamp(P.k * (1.9 + n.energy * 3.4) * pulse * (1 + nearness * 2.6), 0.5, 26);
      const alpha =
        clamp(P.k * (0.3 + n.energy * 0.55 + glow * 0.5 + surge * 0.3) * haze, 0.03, 0.95) *
        // Defocused matter is dimmer as it spreads, or the foreground shouts.
        (1 - nearness * 0.72) *
        nearDamp(P.depth) *
        intensity;

      g.fillStyle = withAlpha(n.energy > 0.55 || glow > 0.5 ? opts.accent : opts.dim, alpha);
      g.beginPath();
      g.arc(P.sx, P.sy, r, 0, Math.PI * 2);
      g.fill();

      // A halo only where there is genuinely something to see: an active node,
      // in focus, standing in the light. If everything glowed, nothing would
      // read as significant.
      if ((n.energy > 0.7 || glow > 0.6) && P.k > 0.5 && nearness < 0.25) {
        g.fillStyle = withAlpha(opts.accent, alpha * (0.1 + glow * 0.22));
        g.beginPath();
        g.arc(P.sx, P.sy, r * (3.4 + glow * 3), 0, Math.PI * 2);
        g.fill();
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
