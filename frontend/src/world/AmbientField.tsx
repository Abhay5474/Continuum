import { useEffect, useMemo, useRef } from "react";
import { useLocation } from "react-router-dom";
import { createWorld } from "./engine";
import {
  consensus,
  continuum,
  fabric,
  fragmented,
  memory,
  routing,
  workflow,
} from "./formations";

/**
 * The console's signature layer.
 *
 * <p>The landing page and the console are the same product and should read that
 * way, but they are not doing the same job: one is arguing, the other is being
 * operated. So the console gets the same field at a fraction of the presence —
 * fewer nodes, much lower opacity, no foreground plane, no cursor lamp. It sits
 * behind everything and never competes with a number.
 *
 * <p>Which arrangement appears is chosen by what the page is for, so each
 * subsystem has its own signature: the workflow pages sit over an execution
 * graph, the context pages over a memory hierarchy, verification over a
 * converging consensus, routing over provider lanes. That is the same idea the
 * landing page uses — one fabric, arranged for the task — carried into the place
 * where the task actually happens.
 *
 * <p>Account pages get nothing. Changing a password is not a subsystem, and a
 * field behind it would be decoration.
 */

/** Route prefix to the arrangement that means something for that page. */
const SIGNATURE: [string, typeof workflow][] = [
  ["/workflows", workflow],
  ["/mmu", memory],
  ["/cache", memory],
  ["/guard", fabric],
  ["/memory", memory],
  ["/dag", consensus],
  ["/replay", consensus],
  ["/router", routing],
  ["/gateway", routing],
  ["/chaos", fragmented],
  ["/ai-chaos", fragmented],
  ["/autopilot", fabric],
  ["/godmode", fabric],
  ["/dashboard", continuum],
];

/** Pages where a background would be noise rather than identity. */
const BARE = ["/portal", "/billing", "/settings"];

export default function AmbientField() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const { pathname } = useLocation();

  const formation = useMemo(() => {
    if (BARE.some((p) => pathname.startsWith(p))) return null;
    const hit = SIGNATURE.find(([p]) => pathname.startsWith(p));
    return hit ? hit[1] : null;
  }, [pathname]);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas || !formation) return;

    const world = createWorld(canvas, {
      // A console page does not scroll a narrative, so the field holds one
      // arrangement. Passing it twice keeps the blend maths trivially valid.
      progress: () => 0,
      pointer: () => ({ x: 0, y: 0 }),
      formations: [formation, formation],
      accent: "#4C8BF5",
      dim: "#8FA3C8",
      // Deliberately faint. This is a watermark of the system, not a display.
      intensity: 0.42,
      density: 0.45,
      lamp: false,
    });
    return () => world.destroy();
  }, [formation]);

  if (!formation) return null;

  return (
    <div className="pointer-events-none fixed inset-0 -z-10" aria-hidden="true">
      <canvas ref={canvasRef} className="h-full w-full" />
      {/* Clears the centre of the frame, where the tables and readouts live. */}
      <div
        className="absolute inset-0"
        style={{
          background:
            "radial-gradient(112% 78% at 50% 34%, rgb(var(--ink) / 0.9) 26%, rgb(var(--ink) / 0.62) 62%, rgb(var(--ink) / 0.15) 100%)",
        }}
      />
    </div>
  );
}
