/**
 * Measures text contrast on every console route, in both themes.
 *
 * Written because looking at a screen does not find a 4.3:1 string — the first
 * sweep by eye missed about ninety of them, and every colour change since has
 * moved a handful of pairs by a few hundredths. The surface tokens in
 * particular: lifting the dark card off black, or taking the light page off
 * pure white, changes the background under every muted string in the app.
 *
 *   node tools/audit-contrast.mjs <session-token>
 *
 * Prints one line per distinct failing (class, colour, ratio). Silence is a
 * pass. Composites alpha properly — an 8%-tinted chip is not a failure just
 * because the layer under it is translucent, which an earlier version of this
 * script got wrong and reported as 1.55:1.
 */
import { chromium } from "playwright";

const TOKEN = process.argv[2] ?? process.env.CONTINUUM_SESSION;
const BASE = process.env.CONSOLE_URL ?? "http://localhost:5199";
if (!TOKEN) {
  console.error("usage: node tools/audit-contrast.mjs <session-token>");
  process.exit(2);
}

const ROUTES = [
  "/dashboard", "/workflows", "/chaos", "/replay", "/router", "/ai-chaos", "/memory",
  "/gateway", "/portal", "/billing", "/settings", "/autopilot", "/godmode", "/dag",
  "/mmu", "/guard", "/cache", "/cascade", "/confidence", "/quality", "/breaker",
  "/specialists", "/pipelines", "/admission", "/scheduling", "/cost-limits",
  "/compression", "/context", "/counterfactual", "/loops", "/saga", "/provenance", "/docs",
];

/** Runs in the page: WCAG 2.1 contrast over a properly composited background. */
const AUDIT = () => {
  const parse = (c) => {
    const m = c.match(/rgba?\(([\d.]+)[,\s]+([\d.]+)[,\s]+([\d.]+)(?:[,\s/]+([\d.]+))?\)/);
    return m ? { r: +m[1], g: +m[2], b: +m[3], a: m[4] === undefined ? 1 : +m[4] } : null;
  };
  const over = (fg, bg) => ({
    r: fg.r * fg.a + bg.r * (1 - fg.a),
    g: fg.g * fg.a + bg.g * (1 - fg.a),
    b: fg.b * fg.a + bg.b * (1 - fg.a),
    a: 1,
  });
  const lum = (c) => {
    const f = (v) => {
      v /= 255;
      return v <= 0.03928 ? v / 12.92 : Math.pow((v + 0.055) / 1.055, 2.4);
    };
    return 0.2126 * f(c.r) + 0.7152 * f(c.g) + 0.0722 * f(c.b);
  };
  const ratio = (a, b) => {
    const l1 = lum(a), l2 = lum(b);
    return (Math.max(l1, l2) + 0.05) / (Math.min(l1, l2) + 0.05);
  };
  // Walk up collecting every translucent layer, then composite them in order.
  const bgOf = (el) => {
    const layers = [];
    for (let n = el; n; n = n.parentElement) {
      const c = parse(getComputedStyle(n).backgroundColor);
      if (c && c.a > 0) {
        layers.push(c);
        if (c.a === 1) break;
      }
      if (n === document.documentElement) break;
    }
    let base = { r: 255, g: 255, b: 255, a: 1 };
    for (let i = layers.length - 1; i >= 0; i--) base = over(layers[i], base);
    return base;
  };

  const out = [];
  for (const el of document.querySelectorAll("body *")) {
    // Only elements that own a text node — otherwise a wrapper is judged by
    // its child's colour.
    if (el.children.length && ![...el.childNodes].some((n) => n.nodeType === 3 && n.textContent.trim())) continue;
    if (!(el.textContent || "").trim()) continue;
    const r = el.getBoundingClientRect();
    if (r.width < 2 || r.height < 2) continue;
    const cs = getComputedStyle(el);
    if (cs.visibility === "hidden" || cs.opacity === "0") continue;
    // Disabled controls are exempt under WCAG 1.4.3, and gradients cannot be
    // read as a single background colour.
    if (el.closest("[disabled]") || el.matches(":disabled")) continue;
    if (cs.backgroundImage && cs.backgroundImage !== "none") continue;
    const fg = parse(cs.color);
    if (!fg) continue;
    const bg = bgOf(el);
    const c = ratio(over(fg, bg), bg);
    const size = parseFloat(cs.fontSize);
    const need = size >= 24 || (size >= 18.66 && +cs.fontWeight >= 700) ? 3 : 4.5;
    if (c < need) {
      out.push({
        t: (el.textContent || "").trim().slice(0, 44),
        c: +c.toFixed(2),
        need,
        color: cs.color,
        size,
        cls: (el.className || "").toString().slice(0, 70),
      });
    }
  }
  return out;
};

const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? "/opt/pw-browsers/chromium",
});
const page = await browser.newPage({ viewport: { width: 1360, height: 1000 } });
await page.goto(BASE, { waitUntil: "domcontentloaded" });
await page.evaluate((t) => localStorage.setItem("continuum.portal.session", t), TOKEN);

let failures = 0;
for (const theme of ["dark", "light"]) {
  const seen = new Set();
  for (const route of ROUTES) {
    await page.goto(BASE + route, { waitUntil: "networkidle" }).catch(() => {});
    await page.evaluate((t) => document.documentElement.setAttribute("data-theme", t), theme);
    await page.waitForTimeout(700);
    for (const f of await page.evaluate(AUDIT)) {
      const key = `${f.cls}|${f.color}|${f.c}`;
      if (seen.has(key)) continue;
      seen.add(key);
      failures++;
      console.log(`${theme} ${route} | ${f.c} < ${f.need} | ${f.color} ${f.size}px | ${JSON.stringify(f.t)} | ${f.cls}`);
    }
  }
}
await browser.close();
console.log(failures === 0 ? "contrast: clean in both themes" : `contrast: ${failures} failing`);
process.exit(failures === 0 ? 0 : 1);
