import { useEffect, useRef } from "react";
import { createWorld } from "./engine";
import { FORMATIONS } from "./formations";

/**
 * The persistent environment behind the landing narrative.
 *
 * <p>Fixed to the viewport and never unmounted while the page is open, so the
 * scroll story is a camera moving through one space rather than a sequence of
 * sections that each bring their own graphic. Scroll and pointer are read here
 * and handed to the renderer as plain functions — the canvas never re-renders
 * React, and React never drives a frame.
 */
export default function World() {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const progress = useRef(0);
  const pointer = useRef({ x: 0, y: 0 });

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;

    const accent = "#4C8BF5";
    const dim = "#8FA3C8";

    const world = createWorld(canvas, {
      progress: () => progress.current,
      pointer: () => pointer.current,
      formations: FORMATIONS,
      accent,
      dim,
    });

    // Scroll and pointer are sampled into refs and read on the next frame, so a
    // fast scroll or a moving cursor can never queue more work than the renderer
    // consumes.
    // Progress is measured in viewport heights, not in document fraction.
    //
    // The page is one full-height entry section followed by one section per
    // formation, so section k sits at scrollY = k * vh. Deriving progress from
    // total document height instead would drift the moment anything changed the
    // page length — a footer, a longer paragraph — and each scene would describe
    // a formation the world had already moved past.
    const onScroll = () => {
      const vh = window.innerHeight;
      const section = window.scrollY / vh - 1;
      progress.current = Math.min(1, Math.max(0, section / (FORMATIONS.length - 1)));
      world.nudge();
    };
    const onPointer = (e: PointerEvent) => {
      pointer.current = {
        x: (e.clientX / window.innerWidth) * 2 - 1,
        y: (e.clientY / window.innerHeight) * 2 - 1,
      };
    };

    onScroll();
    window.addEventListener("scroll", onScroll, { passive: true });
    window.addEventListener("pointermove", onPointer, { passive: true });

    return () => {
      window.removeEventListener("scroll", onScroll);
      window.removeEventListener("pointermove", onPointer);
      world.destroy();
    };
  }, []);

  return (
    <div className="pointer-events-none fixed inset-0 -z-10" aria-hidden="true">
      <canvas ref={canvasRef} className="h-full w-full" />
      {/* Vignette: pulls the eye to the centre and keeps text legible over the
          busiest part of the field without dimming the whole scene. */}
      <div
        className="absolute inset-0"
        style={{
          background:
            "radial-gradient(125% 95% at 50% 45%, transparent 42%, rgb(var(--ink) / 0.3) 74%, rgb(var(--ink) / 0.8) 100%)",
        }}
      />
    </div>
  );
}
