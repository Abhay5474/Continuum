import { useEffect, useRef, useState } from "react";
import { demoStarted, demoStopped } from "../activity";

/**
 * The loop behind an interactive explainer.
 *
 * <p>Five of these can be on the page at once, so the default has to be that
 * they cost nothing: each one runs only while it is actually on screen, stops
 * when the tab is hidden, and under reduced motion draws a single settled frame
 * and never starts a loop at all.
 *
 * <p>The simulation is handed a fixed timestep rather than the real frame delta.
 * A demo that steps by wall-clock time behaves differently on a slow machine,
 * and these are explanations — the same interaction has to produce the same
 * explanation for everyone.
 */
export interface DemoHandle<S> {
  /** Mutable simulation state. Never replaced, so the loop keeps its reference. */
  state: S;
  /** Advances one fixed tick. */
  step: (state: S) => void;
  /** Paints the current state. */
  draw: (ctx: CanvasRenderingContext2D, state: S, w: number, h: number) => void;
}

export function useDemo<S>(handle: DemoHandle<S>) {
  const canvasRef = useRef<HTMLCanvasElement>(null);
  const ref = useRef(handle);
  ref.current = handle;

  // The simulation mutates its state outside React, so nothing would ever
  // re-render the readouts and they would sit at their initial values while the
  // canvas animated beside them — which is worse than having no readouts, since
  // it looks like working instrumentation reporting the wrong numbers.
  //
  // A counter bumped a few times a second is enough: these are a handful of
  // text nodes, and re-rendering them at the frame rate would be waste.
  const [, publish] = useState(0);

  useEffect(() => {
    const canvas = canvasRef.current;
    if (!canvas) return;
    const ctx = canvas.getContext("2d", { alpha: true });
    if (!ctx) return;

    const reduced = window.matchMedia?.("(prefers-reduced-motion: reduce)").matches;
    let width = 0;
    let height = 0;
    let raf = 0;
    let running = false;
    let onScreen = false;

    const resize = () => {
      const rect = canvas.getBoundingClientRect();
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      width = rect.width;
      height = rect.height;
      canvas.width = Math.floor(width * dpr);
      canvas.height = Math.floor(height * dpr);
      ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
      paint();
    };

    const paint = () => {
      ctx.clearRect(0, 0, width, height);
      ref.current.draw(ctx, ref.current.state, width, height);
    };

    let sincePublish = 0;
    const frame = () => {
      if (!running) return;
      ref.current.step(ref.current.state);
      paint();
      if (++sincePublish >= 8) {
        sincePublish = 0;
        publish((n) => n + 1);
      }
      raf = requestAnimationFrame(frame);
    };

    const start = () => {
      if (running || reduced) return;
      running = true;
      // Tell the world it is background now, so it can give up half its frames
      // to the thing the visitor is actually interacting with.
      demoStarted();
      raf = requestAnimationFrame(frame);
    };
    const stop = () => {
      if (!running) return;
      running = false;
      demoStopped();
      cancelAnimationFrame(raf);
    };

    const onVisibility = () => (document.hidden ? stop() : onScreen && start());

    const io = new IntersectionObserver(
      ([e]) => {
        onScreen = e.isIntersecting;
        if (onScreen && !document.hidden) start();
        else stop();
      },
      { threshold: 0.15 }
    );

    resize();
    io.observe(canvas);
    window.addEventListener("resize", resize, { passive: true });
    document.addEventListener("visibilitychange", onVisibility);
    if (reduced) {
      // One settled frame: the diagram is still readable, it simply does not move.
      for (let i = 0; i < 120; i++) ref.current.step(ref.current.state);
      paint();
      publish((n) => n + 1);
    }

    return () => {
      stop();
      io.disconnect();
      window.removeEventListener("resize", resize);
      document.removeEventListener("visibilitychange", onVisibility);
    };
  }, []);

  return canvasRef;
}

export const clamp = (v: number, lo: number, hi: number) => Math.max(lo, Math.min(hi, v));
export const lerp = (a: number, b: number, t: number) => a + (b - a) * t;

/** Deterministic pseudo-random, so a demo explains the same thing every time. */
export function seeded(seed: number) {
  let s = seed >>> 0;
  return () => {
    s = (s * 1664525 + 1013904223) >>> 0;
    return s / 4294967296;
  };
}
