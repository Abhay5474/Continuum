import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";
import { FEATURES, featureFor } from "./features";

/** The routes main.tsx registers, as absolute paths. */
const ROUTES = [...readFileSync(new URL("../main.tsx", import.meta.url), "utf8").matchAll(/path: "([^"]+)"/g)]
  .map((m) => (m[1].startsWith("/") ? m[1] : `/${m[1]}`));

describe("the feature registry", () => {
  it("points every menu entry and tab at a route that exists", () => {
    const missing = FEATURES.flatMap((f) => f.views.map((v) => v.to)).filter((to) => !ROUTES.includes(to));
    expect(missing).toEqual([]);
  });

  it("gives every feature one name and every screen one owner", () => {
    const names = FEATURES.map((f) => f.name);
    expect(new Set(names).size).toBe(names.length);
    const screens = FEATURES.flatMap((f) => f.views.map((v) => v.to));
    expect(new Set(screens).size).toBe(screens.length);
  });

  it("gives a screen its feature's tab strip only when the feature has several screens", () => {
    expect(featureFor("/quality")?.name).toBe("Answer Assurance");
    expect(featureFor("/dag")).toBeUndefined();
  });

  it("marks exactly the research features as Labs", () => {
    expect(FEATURES.filter((f) => f.labs).map((f) => f.name).sort())
      .toEqual(["Adaptive Policy", "Context Optimizer", "Verification Engine"]);
  });
});
