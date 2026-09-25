/**
 * How much prose each console page puts on screen.
 *
 *   node tools/audit-prose.mjs <session-token> [--detail]
 *
 * Counts visible text blocks of 12 words or more — sentences, not labels or
 * figures — per route, and the total words they carry. A console is read by
 * scanning; a page that needs paragraphs to be understood is asking the reader
 * to read. The guide exists for explanation; the page should show.
 */
import { chromium } from "playwright";

const TOKEN = process.argv[2] ?? process.env.CONTINUUM_SESSION;
const DETAIL = process.argv.includes("--detail");
const BASE = process.env.CONSOLE_URL ?? "http://127.0.0.1:5199";
const ROUTES = ["/dashboard", "/workflows", "/workflows/console", "/gateway", "/router", "/specialists", "/pipelines",
  "/context", "/mmu", "/memory", "/guard", "/cache", "/cascade", "/confidence", "/quality", "/breaker",
  "/admission", "/scheduling", "/cost-limits", "/compression", "/counterfactual", "/loops", "/saga",
  "/provenance", "/replay", "/chaos", "/ai-chaos", "/dag", "/autopilot", "/godmode", "/portal", "/billing", "/settings"];

const browser = await chromium.launch({ executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? "/opt/pw-browsers/chromium" });
const page = await browser.newPage({ viewport: { width: 1360, height: 900 } });
await page.goto(BASE);
await page.evaluate((t) => localStorage.setItem("continuum.portal.session", t), TOKEN);
let total = 0;
const rows = [];
for (const route of ROUTES) {
  await page.goto(BASE + route, { waitUntil: "networkidle" }).catch(() => {});
  await page.waitForTimeout(400);
  const blocks = await page.evaluate(() => {
    const out = [];
    const main = document.querySelector("main");
    if (!main) return out;
    const walker = document.createTreeWalker(main, NodeFilter.SHOW_ELEMENT);
    for (let el = walker.currentNode; el; el = walker.nextNode()) {
      const e = el;
      if (!(e instanceof HTMLElement)) continue;
      // A block whose text is mostly its own (not a container of blocks).
      const own = [...e.childNodes].filter((n) => n.nodeType === 3 || (n.nodeType === 1 && /^(B|I|EM|STRONG|CODE|A|SPAN|KBD)$/.test(n.tagName)))
        .map((n) => n.textContent).join("").replace(/\s+/g, " ").trim();
      if (!own) continue;
      const st = getComputedStyle(e);
      // A truncated line shows only what fits; count what is on screen.
      const shown = st.textOverflow === "ellipsis" && e.scrollWidth > e.clientWidth
        ? e.clientWidth / e.scrollWidth : 1;
      const words = Math.round(own.split(" ").length * shown);
      if (words < 12) continue;
      const r = e.getBoundingClientRect();
      if (r.width === 0 || r.height === 0 || st.visibility === "hidden" || st.display === "none") continue;
      if (e.closest("details:not([open])") && !e.matches("summary")) continue;
      out.push({ words, text: own.slice(0, 90), cls: e.className?.toString?.().slice(0, 60) ?? "" });
    }
    return out;
  });
  const words = blocks.reduce((a, b) => a + b.words, 0);
  total += words;
  rows.push({ route, blocks: blocks.length, words, detail: blocks });
}
await browser.close();
rows.sort((a, b) => b.words - a.words);
for (const r of rows) {
  console.log(`${String(r.words).padStart(5)} words  ${String(r.blocks).padStart(3)} blocks  ${r.route}`);
  if (DETAIL) for (const b of r.detail) console.log(`        ${String(b.words).padStart(3)}  ${b.text}`);
}
console.log(`total: ${total} words of prose across ${ROUTES.length} routes`);
