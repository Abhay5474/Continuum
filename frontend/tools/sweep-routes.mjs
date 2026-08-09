/**
 * Loads every console route in both themes and reports anything obviously broken:
 * an uncaught error, a console error, an error state on screen, or the page
 * scrolling sideways.
 *
 *   node tools/sweep-routes.mjs <session-token>
 *
 * Horizontal overflow is in here because it has been introduced twice by things
 * that do not look like layout changes — a side panel parked off-screen with a
 * transform still contributes to scrollWidth, and `overflow-x: hidden` on html
 * does not clip a fixed element.
 */
import { chromium } from "playwright";

const TOKEN = process.argv[2] ?? process.env.CONTINUUM_SESSION;
const BASE = process.env.CONSOLE_URL ?? "http://localhost:5199";
if (!TOKEN) {
  console.error("usage: node tools/sweep-routes.mjs <session-token>");
  process.exit(2);
}

const ROUTES = [
  "/dashboard", "/workflows", "/workflows/console", "/chaos", "/replay", "/router",
  "/ai-chaos", "/memory", "/gateway", "/portal", "/billing", "/settings", "/autopilot",
  "/godmode", "/dag", "/mmu", "/guard", "/cache", "/cascade", "/confidence", "/quality",
  "/breaker", "/specialists", "/pipelines", "/admission", "/scheduling", "/cost-limits",
  "/compression", "/context", "/counterfactual", "/loops", "/saga", "/provenance", "/docs",
];

const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? "/opt/pw-browsers/chromium",
});
const page = await browser.newPage({ viewport: { width: 1360, height: 1000 } });

const problems = [];
page.on("pageerror", (e) => problems.push(`uncaught | ${page.url()} | ${e.message}`));
page.on("console", (m) => {
  if (m.type() === "error") problems.push(`console | ${page.url()} | ${m.text().slice(0, 160)}`);
});

await page.goto(BASE, { waitUntil: "domcontentloaded" });
await page.evaluate((t) => localStorage.setItem("continuum.portal.session", t), TOKEN);

for (const theme of ["dark", "light"]) {
  for (const route of ROUTES) {
    await page.goto(BASE + route, { waitUntil: "networkidle" }).catch(() => {});
    await page.evaluate((t) => document.documentElement.setAttribute("data-theme", t), theme);
    await page.waitForTimeout(800);
    const info = await page.evaluate(() => ({
      overflow: document.documentElement.scrollWidth > window.innerWidth + 1
        ? document.documentElement.scrollWidth
        : 0,
      errorState: document.body.innerText.includes("Something went wrong"),
    }));
    if (info.overflow) problems.push(`overflow | ${theme} ${route} | ${info.overflow}px wide`);
    if (info.errorState) problems.push(`error-state | ${theme} ${route}`);
  }
}

await browser.close();
for (const p of problems) console.log(p);
console.log(problems.length === 0 ? `sweep: ${ROUTES.length} routes clean in both themes` : `sweep: ${problems.length} problems`);
process.exit(problems.length === 0 ? 0 : 1);
