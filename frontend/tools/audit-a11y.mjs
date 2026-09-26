/**
 * Runs axe-core on every console route, in both themes.
 *
 *   node tools/audit-a11y.mjs <session-token>
 *
 * Prints one line per distinct (rule, selector) violation, with the route it
 * was first seen on. Contrast is left to audit-contrast.mjs, which composites
 * translucent layers properly. Silence is a pass.
 */
import { chromium } from "playwright";
import { readFileSync } from "node:fs";
import { createRequire } from "node:module";

const TOKEN = process.argv[2] ?? process.env.CONTINUUM_SESSION;
const BASE = process.env.CONSOLE_URL ?? "http://127.0.0.1:5199";
if (!TOKEN) {
  console.error("usage: node tools/audit-a11y.mjs <session-token>");
  process.exit(2);
}
const AXE = readFileSync(createRequire(import.meta.url).resolve("axe-core/axe.min.js"), "utf8");
const ROUTES = ["/dashboard", "/workflows", "/workflows/console", "/gateway", "/router", "/specialists", "/pipelines",
  "/context", "/mmu", "/memory", "/guard", "/cache", "/cascade", "/confidence", "/quality", "/breaker",
  "/admission", "/scheduling", "/cost-limits", "/compression", "/counterfactual", "/loops", "/saga",
  "/provenance", "/replay", "/chaos", "/ai-chaos", "/dag", "/autopilot", "/godmode", "/portal", "/billing",
  "/settings", "/nope-404",
  // "route>Tab" opens the route, then that tab: views that have no URL of their own.
  "/workflows>Editor"];

const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? "/opt/pw-browsers/chromium" });
const page = await browser.newPage({ viewport: { width: 1360, height: 900 } });
await page.goto(BASE);
await page.evaluate((t) => localStorage.setItem("continuum.portal.session", t), TOKEN);
const seen = new Map();
for (const theme of ["light", "dark"]) {
  await page.evaluate((t) => (t === "dark" ? localStorage.setItem("continuum.theme.choice", "dark") : localStorage.removeItem("continuum.theme.choice")), theme);
  for (const route of ROUTES) {
    const [path, tab] = route.split(">");
    await page.goto(BASE + path, { waitUntil: "networkidle" }).catch(() => {});
    if (tab) await page.getByRole("tab", { name: tab, exact: true }).first().click().catch(() => {});
    await page.waitForTimeout(400);
    await page.addScriptTag({ content: AXE });
    const found = await page.evaluate(async () => {
      const r = await window.axe.run(document, { resultTypes: ["violations"], rules: { "color-contrast": { enabled: false } } });
      return r.violations.map((v) => ({ id: v.id, impact: v.impact, help: v.help, nodes: v.nodes.map((n) => n.target.join(" ")).slice(0, 4) }));
    });
    for (const v of found) for (const n of v.nodes) {
      const key = `${v.id}|${n}`;
      if (!seen.has(key)) seen.set(key, { ...v, node: n, route, theme });
    }
  }
}
await browser.close();
const byRule = {};
for (const v of seen.values()) (byRule[v.id] ??= []).push(v);
for (const [id, vs] of Object.entries(byRule)) {
  console.log(`${vs[0].impact} ${id} — ${vs[0].help} (${vs.length})`);
  for (const v of vs.slice(0, 6)) console.log(`    ${v.route}  ${v.node}`);
}
console.log(seen.size === 0 ? "a11y: clean" : `a11y: ${seen.size} distinct findings`);
process.exit(seen.size === 0 ? 0 : 1);
