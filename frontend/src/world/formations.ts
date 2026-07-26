import type { Formation, NodeKind, WorldEdge, WorldNode } from "./engine";

/**
 * The seven states of the world.
 *
 * <p>Each formation positions the same population differently and declares which
 * connections exist. Because the node identities never change, moving between
 * two formations reads as the system reorganising rather than one graphic being
 * replaced by another — which is the point, and also the argument the page is
 * making: it is one fabric, arranged for the job at hand.
 *
 * <p>Every layout is deterministic given the node index. That matters: a scene
 * must look the same on the way back up as it did on the way down, and a layout
 * that reshuffled on each pass would feel like noise instead of structure.
 */

/** Deterministic pseudo-random in [0,1) from an integer — stable across passes. */
function rnd(i: number, salt = 1): number {
  const x = Math.sin(i * 127.1 + salt * 311.7) * 43758.5453;
  return x - Math.floor(x);
}

function set(n: WorldNode, x: number, y: number, z: number, kind: NodeKind, energy: number) {
  n.tx = x;
  n.ty = y;
  n.tz = z;
  n.kind = kind;
  n.tenergy = energy;
}

const edge = (a: number, b: number, strength = 1): WorldEdge => ({ a, b, strength, s: 0 });

/* ------------------------------------------------------------------ *
 * 01 — FRAGMENTED
 * Systems that do not know about each other. Dense inside, nothing across.
 * ------------------------------------------------------------------ */
export const fragmented: Formation = {
  flow: 0.25,
  dolly: 0,
  layout(nodes, w, h) {
    const clusters = 5;
    const edges: WorldEdge[] = [];
    const members: number[][] = Array.from({ length: clusters }, () => []);

    nodes.forEach((n, i) => {
      const c = i % clusters;
      members[c].push(i);
      const angle = (c / clusters) * Math.PI * 2 + 0.4;
      const spread = Math.min(w, h) * 0.42;
      const cx = Math.cos(angle) * spread;
      const cy = Math.sin(angle) * spread * 0.6;
      const cz = (c - clusters / 2) * 220;
      set(
        n,
        cx + (rnd(i, 1) - 0.5) * 190,
        cy + (rnd(i, 2) - 0.5) * 190,
        cz + (rnd(i, 3) - 0.5) * 190,
        "task",
        0.12 + rnd(i, 4) * 0.18
      );
    });

    // Connections exist only inside a cluster. The absence of anything crossing
    // between them is the whole content of this scene.
    for (const group of members) {
      for (let k = 0; k < group.length - 1; k += 2) {
        edges.push(edge(group[k], group[k + 1], 0.5));
      }
    }
    return edges;
  },
};

/* ------------------------------------------------------------------ *
 * 02 — FABRIC
 * The coordination layer arrives. The clusters resolve into one shell.
 * ------------------------------------------------------------------ */
export const fabric: Formation = {
  flow: 0.55,
  dolly: 0.15,
  layout(nodes, w, h) {
    const edges: WorldEdge[] = [];
    const R = Math.min(w, h) * 0.46;
    const N = nodes.length;

    nodes.forEach((n, i) => {
      // Fibonacci sphere: an even distribution with no seams or poles.
      const phi = Math.acos(1 - (2 * (i + 0.5)) / N);
      const theta = Math.PI * (1 + Math.sqrt(5)) * i;
      set(
        n,
        R * Math.sin(phi) * Math.cos(theta),
        R * Math.sin(phi) * Math.sin(theta) * 0.72,
        R * Math.cos(phi),
        i % 11 === 0 ? "core" : "task",
        i % 11 === 0 ? 0.85 : 0.3
      );
    });

    // Each node reaches to a neighbour a fixed stride away — a lattice, not a
    // hairball, so the shell reads as structure rather than fog.
    for (let i = 0; i < N; i++) {
      edges.push(edge(i, (i + 7) % N, 0.4));
      if (i % 3 === 0) edges.push(edge(i, (i + 23) % N, 0.28));
    }
    return edges;
  },
};

/* ------------------------------------------------------------------ *
 * 03 — WORKFLOW
 * A durable execution graph: layers left to right, work flowing along edges.
 * ------------------------------------------------------------------ */
export const workflow: Formation = {
  flow: 1,
  dolly: 0.35,
  layout(nodes, w, h) {
    const edges: WorldEdge[] = [];
    const layers = 7;
    const perLayer = Math.ceil(nodes.length / layers);
    const spanX = w * 0.78;

    nodes.forEach((n, i) => {
      const layer = Math.floor(i / perLayer);
      const within = i % perLayer;
      const x = -spanX / 2 + (layer / (layers - 1)) * spanX;
      // A gentle fan: later layers spread wider, the way a real DAG does.
      const spreadY = h * (0.1 + 0.045 * layer);
      const y = (within / Math.max(1, perLayer - 1) - 0.5) * spreadY * 2;
      set(
        n,
        x,
        y,
        (rnd(i, 5) - 0.5) * 320,
        within === 0 ? "agent" : "task",
        // The leading edge of execution is the bright part.
        layer === 3 ? 0.9 : 0.22 + rnd(i, 6) * 0.15
      );
    });

    // Edges only ever point forward one layer: a workflow with a backward edge
    // would not terminate, and the picture should not suggest one that does.
    for (let i = 0; i < nodes.length; i++) {
      const layer = Math.floor(i / perLayer);
      if (layer >= layers - 1) continue;
      const a = i;
      const b = Math.min(nodes.length - 1, (layer + 1) * perLayer + ((i * 3) % perLayer));
      edges.push(edge(a, b, 0.55));
    }
    return edges;
  },
};

/* ------------------------------------------------------------------ *
 * 04 — CONTEXT MEMORY
 * Four strata. Most of what a model could see is not resident, and that is
 * the entire idea — so most nodes belong to the cold layers.
 * ------------------------------------------------------------------ */
export const memory: Formation = {
  flow: 0.7,
  dolly: 0.5,
  layout(nodes, w, h) {
    const edges: WorldEdge[] = [];
    // Deliberately uneven: L1 is small and hot, persistence is vast and dim.
    const tiers = [
      { share: 0.1, y: -h * 0.3, energy: 0.95, spread: 0.34 },
      { share: 0.18, y: -h * 0.08, energy: 0.55, spread: 0.5 },
      { share: 0.28, y: h * 0.14, energy: 0.26, spread: 0.66 },
      { share: 0.44, y: h * 0.36, energy: 0.1, spread: 0.82 },
    ];

    let cursor = 0;
    const tierOf: number[] = [];
    tiers.forEach((tier, ti) => {
      const count = ti === tiers.length - 1 ? nodes.length - cursor : Math.round(nodes.length * tier.share);
      for (let k = 0; k < count && cursor < nodes.length; k++, cursor++) {
        const n = nodes[cursor];
        tierOf[cursor] = ti;
        const cols = Math.ceil(Math.sqrt(count * 2.2));
        const col = k % cols;
        const row = Math.floor(k / cols);
        set(
          n,
          (col / Math.max(1, cols - 1) - 0.5) * w * tier.spread,
          tier.y + row * 26,
          (rnd(cursor, 7) - 0.5) * 420,
          "memory",
          tier.energy * (0.7 + rnd(cursor, 8) * 0.5)
        );
      }
    });

    // A few vertical links: the paths a page takes when it faults in or is
    // evicted. Sparse, because most pages simply sit where they are.
    for (let i = 0; i < nodes.length; i += 9) {
      const j = (i + 13) % nodes.length;
      if (tierOf[i] !== tierOf[j]) edges.push(edge(i, j, 0.5));
    }
    return edges;
  },
};

/* ------------------------------------------------------------------ *
 * 05 — CONSENSUS
 * Independent solvers, disagreeing, resolving to one answer.
 * ------------------------------------------------------------------ */
export const consensus: Formation = {
  flow: 0.85,
  dolly: 0.4,
  layout(nodes, w, h) {
    const edges: WorldEdge[] = [];
    const arms = 6;
    const R = Math.min(w, h) * 0.4;
    // The resolved answer: one node, centre, bright.
    const hub = 0;
    set(nodes[hub], 0, 0, 0, "core", 1);

    for (let i = 1; i < nodes.length; i++) {
      const arm = i % arms;
      const depth = Math.floor(i / arms);
      const maxDepth = Math.ceil(nodes.length / arms);
      // Each arm converges: further out is wider, nearer the hub is tighter.
      const conv = 1 - depth / maxDepth;
      const angle = (arm / arms) * Math.PI * 2 + conv * 0.55;
      const r = R * (0.25 + conv * 0.95);
      set(
        nodes[i],
        Math.cos(angle) * r,
        Math.sin(angle) * r * 0.7,
        (rnd(i, 9) - 0.5) * 260 * conv,
        "agent",
        // Agreement brightens as the arms approach the centre.
        0.15 + (1 - conv) * 0.75
      );
      // Chain each arm inward, and tie the innermost of each to the hub — the
      // moment of convergence, drawn as the only edges that reach the centre.
      if (depth > 0) edges.push(edge(i, Math.max(1, i - arms), 0.5));
      else edges.push(edge(i, hub, 0.9));
    }
    return edges;
  },
};

/* ------------------------------------------------------------------ *
 * 06 — ROUTING UNDER LOAD
 * One saturated path, traffic moving off it. The asymmetry is the message.
 * ------------------------------------------------------------------ */
export const routing: Formation = {
  flow: 1,
  dolly: 0.3,
  layout(nodes, w, h) {
    const edges: WorldEdge[] = [];
    const lanes = 4;
    // Lane 1 is congested: it holds more nodes, packed tighter, and dimmer.
    const weights = [0.3, 0.12, 0.3, 0.28];
    const source = 0;
    set(nodes[source], -w * 0.42, 0, 0, "core", 1);

    let cursor = 1;
    const laneHeads: number[] = [];
    weights.forEach((weight, lane) => {
      const count = Math.round((nodes.length - 1) * weight);
      const laneY = (lane - (lanes - 1) / 2) * h * 0.19;
      const congested = lane === 1;
      for (let k = 0; k < count && cursor < nodes.length; k++, cursor++) {
        const t = k / Math.max(1, count - 1);
        set(
          nodes[cursor],
          -w * 0.28 + t * w * 0.7,
          laneY + (rnd(cursor, 10) - 0.5) * (congested ? 26 : 64),
          (rnd(cursor, 11) - 0.5) * 260,
          "model",
          congested ? 0.12 : 0.45 + rnd(cursor, 12) * 0.4
        );
        if (k === 0) laneHeads.push(cursor);
        else edges.push(edge(cursor - 1, cursor, congested ? 0.16 : 0.6));
      }
    });

    // The source feeds every lane, but the link into the congested one is faint:
    // that is the routing decision, drawn rather than captioned.
    laneHeads.forEach((head, lane) => edges.push(edge(source, head, lane === 1 ? 0.12 : 0.85)));
    return edges;
  },
};

/* ------------------------------------------------------------------ *
 * 07 — CONTINUUM
 * Everything resolved into one body. A torus: closed, circulating, no end.
 * ------------------------------------------------------------------ */
export const continuum: Formation = {
  flow: 0.9,
  dolly: 0.1,
  layout(nodes, w, h) {
    const edges: WorldEdge[] = [];
    const R = Math.min(w, h) * 0.34;
    const r = R * 0.36;
    const N = nodes.length;

    nodes.forEach((n, i) => {
      const u = (i / N) * Math.PI * 2 * 7;
      const v = (i / N) * Math.PI * 2 * 3;
      set(
        n,
        (R + r * Math.cos(v)) * Math.cos(u),
        (R + r * Math.cos(v)) * Math.sin(u) * 0.72,
        r * Math.sin(v),
        i % 9 === 0 ? "core" : "task",
        0.35 + 0.5 * Math.abs(Math.sin(u * 0.5))
      );
      edges.push(edge(i, (i + 1) % N, 0.45));
    });
    return edges;
  },
};

export const FORMATIONS: Formation[] = [
  fragmented,
  fabric,
  workflow,
  memory,
  consensus,
  routing,
  continuum,
];
