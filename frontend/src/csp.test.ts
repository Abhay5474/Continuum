import { readFileSync } from "node:fs";
import { describe, expect, it } from "vitest";

const root = new URL("../", import.meta.url);
const read = (p: string) => readFileSync(new URL(p, root), "utf8");
const policy: string = JSON.parse(read("csp.json")).policy;

describe("the Content-Security-Policy", () => {
  it("is the same on Vercel, in nginx and in vite preview", () => {
    const vercel = JSON.parse(read("vercel.json")).headers[0].headers
      .find((h: { key: string }) => h.key === "Content-Security-Policy").value;
    expect(vercel).toBe(policy);
    expect(read("nginx.conf")).toContain(`Content-Security-Policy "${policy}"`);
    expect(read("vite.config.ts")).toContain("csp.policy");
  });

  it("allows scripts from this origin only, so index.html has no inline script", () => {
    expect(policy).toContain("script-src 'self';");
    expect(read("index.html")).not.toMatch(/<script>(?!\s*<\/script>)/);
  });
});
