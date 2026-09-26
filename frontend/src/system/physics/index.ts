/**
 * Continuum's physics: one motion language for the whole console.
 *
 *   spring.ts   the solver, the presets, the ticker, velocity from gestures
 *   tokens.ts   motion.* roles, scale/blur/shadow tokens, CSS installation
 *   hooks.ts    React bindings — presence, per-frame painting, drag
 *   surface.ts  document-wide press origin and surface light
 *
 * Components import from here, never from the files directly, so the set of
 * things a component can reach for is the set that is meant to be reused.
 */
export * from "./spring";
export * from "./tokens";
export * from "./hooks";
export { installSurfaceLight } from "./surface";
export { useLiquidIndicator } from "./indicator";
export { installTooltips } from "./tooltip";
export { installRipple } from "./ripple";
