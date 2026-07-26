import { useEffect, useRef } from "react";
import { createWorld } from "./engine";
import { FORMATIONS } from "./formations";

/**
 * The environment the landing narrative happens inside.
 *
 * <p>Two render targets, not one. Everything further from the camera than the
 * type plane draws behind the page; everything nearer draws in front of it. The
 * headline therefore sits *within* the volume — matter passes across the words
 * and behind them — which is what separates a lit space from a canvas used as
 * wallpaper.
 *
 * <p>Scroll and pointer are read here and handed to the renderer as plain
 * functions. The canvas never re-renders React and React never drives a frame.
 */
export default function World() {
  const farRef = useRef<HTMLCanvasElement>(null);
  const nearRef = useRef<HTMLCanvasElement>(null);
  const progress = useRef(0);
  const pointer = useRef({ x: 0, y: 0 });

  useEffect(() => {
    const far = farRef.current;
    const near = nearRef.current;
    if (!far) return;

    const world = createWorld(
      far,
      {
        progress: () => progress.current,
        pointer: () => pointer.current,
        formations: FORMATIONS,
        accent: "#4C8BF5",
        dim: "#8FA3C8",
      },
      near ?? undefined
    );

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

    // Sampled into a ref and read on the next frame, so moving the cursor can
    // never queue more work than the renderer consumes.
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
    <>
      {/* Behind the page. */}
      <div className="pointer-events-none fixed inset-0 -z-10" aria-hidden="true">
        <canvas ref={farRef} className="h-full w-full" />
        <div
          className="absolute inset-0"
          style={{
            background:
              "radial-gradient(125% 95% at 50% 45%, transparent 42%, rgb(var(--ink) / 0.3) 74%, rgb(var(--ink) / 0.8) 100%)",
          }}
        />
      </div>
      {/* In front of it. Nothing here is interactive, so it never intercepts a
          click — the page underneath stays fully usable. */}
      <canvas
        ref={nearRef}
        aria-hidden="true"
        className="pointer-events-none fixed inset-0 z-20 h-full w-full"
      />
    </>
  );
}
