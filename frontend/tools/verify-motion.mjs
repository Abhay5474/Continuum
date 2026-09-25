/**
 * Checks the motion system's promises by sampling real frames.
 *
 *   node tools/verify-motion.mjs <session-token>
 *
 * Screenshots cannot show motion, and "it looked fine" is how an animation
 * quietly regresses into a snap. Each check here samples inline styles or
 * computed values every animation frame and asserts a physical property:
 *
 *   - tokens      every --ease-* is a spring sampled into linear()
 *   - press       a released button overshoots its rest scale, then settles
 *   - interrupt   a panel reversed mid-flight turns around; it never teleports
 *   - presence    a closing panel stays mounted until its spring rests
 *   - menu        moving between menus slides one surface; it never closes
 *   - reduced     with reduced motion, nothing travels and presses do not scale
 *
 * Exits non-zero on the first broken promise.
 */
import { chromium } from "playwright";

const TOKEN = process.argv[2] ?? process.env.CONTINUUM_SESSION;
const BASE = process.env.CONSOLE_URL ?? "http://localhost:5199";
if (!TOKEN) {
  console.error("usage: node tools/verify-motion.mjs <session-token>");
  process.exit(2);
}

const browser = await chromium.launch({
  executablePath: process.env.PLAYWRIGHT_CHROMIUM ?? "/opt/pw-browsers/chromium",
});
let failed = 0;
const check = (name, ok, detail) => {
  console.log(`${ok ? "ok  " : "FAIL"} ${name.padEnd(10)} ${detail}`);
  if (!ok) failed++;
};

async function open(reducedMotion = "no-preference") {
  const ctx = await browser.newContext({ viewport: { width: 1360, height: 900 }, reducedMotion });
  const page = await ctx.newPage();
  await page.goto(BASE, { waitUntil: "domcontentloaded" });
  await page.evaluate((t) => localStorage.setItem("continuum.portal.session", t), TOKEN);
  return page;
}

/** Runs fn in the page once per animation frame, n times. */
const frames = (page, fn, n) =>
  page.evaluate(
    ([src, n]) =>
      new Promise((res) => {
        const f = new Function("return (" + src + ")()");
        const out = [];
        let i = 0;
        const tick = () => {
          out.push(f());
          if (++i < n) requestAnimationFrame(tick);
          else res(out);
        };
        requestAnimationFrame(tick);
      }),
    [fn.toString(), n]
  );

const panelX = () => {
  const a = document.querySelector('aside[role="dialog"]');
  return a ? +(a.style.transform.match(/translate3d\(([-\d.]+)px/) || [, NaN])[1] : null;
};

{
  const page = await open();
  await page.goto(BASE + "/specialists", { waitUntil: "networkidle" });

  const eases = await page.evaluate(() =>
    ["micro", "fast", "standard", "slow", "elastic", "modal", "page", "gesture", "fade"].map((n) =>
      getComputedStyle(document.documentElement).getPropertyValue(`--ease-${n}`).trim().slice(0, 7)
    )
  );
  check("tokens", eases.every((e) => e === "linear("), eases.join(" "));

  // Press, slide off, release: the button leaves :active and springs back,
  // but no click fires, so nothing the button does can disturb the sample.
  const btn = await page.$("main button");
  const bb = await btn.boundingBox();
  const at = [bb.x + bb.width / 2, bb.y + bb.height / 2];
  await page.evaluate(([x, y]) => (window.__pressed = document.elementFromPoint(x, y).closest("button")), at);
  await page.mouse.move(...at);
  await page.mouse.down();
  await page.waitForTimeout(250);
  await page.mouse.move(2, 2);
  await page.mouse.up();
  const scales = await frames(page, () => +getComputedStyle(window.__pressed).scale || 1, 45);
  const peak = Math.max(...scales);
  check("press", scales[0] < 0.99 && peak > 1.001 && Math.abs(scales.at(-1) - 1) < 0.002,
    `from ${scales[0].toFixed(3)}, peak ${peak.toFixed(4)}, rest ${scales.at(-1).toFixed(4)}`);

  const row = await page.$('[data-surface="row"]');
  if (row) {
    await row.click();
    await page.waitForTimeout(90);
    await page.keyboard.press("Escape");
    await page.waitForTimeout(60);
    const before = await page.evaluate(panelX);
    await row.click();
    const xs = [before, ...(await frames(page, panelX, 30))];
    const steps = xs.slice(1).map((x, i) => Math.abs(x - xs[i]));
    check("interrupt", Math.max(...steps) < 200 && Math.abs(xs.at(-1)) < 1,
      `largest frame step ${Math.max(...steps).toFixed(0)}px, came to rest at ${xs.at(-1).toFixed(1)}px`);

    await page.waitForTimeout(500);
    await page.keyboard.press("Escape");
    const closing = await frames(page, () => !!document.querySelector('aside[role="dialog"]'), 60);
    const mountedFor = closing.filter(Boolean).length;
    check("presence", mountedFor > 5 && !closing.at(-1),
      `mounted for ${mountedFor} frames after close, then removed`);
  } else {
    check("interrupt", false, "no specialist row to open (seed one first)");
  }

  await page.click('[data-menu-trigger="Traffic"]');
  await page.waitForTimeout(450);
  await page.hover('[data-menu-trigger="Intelligence"]');
  const menu = await frames(page, () => {
    const el = document.querySelector('[role="menu"]');
    return el ? +el.style.opacity : 0;
  }, 25);
  check("menu", Math.min(...menu) > 0.95, `surface opacity stayed ≥ ${Math.min(...menu).toFixed(2)} while sliding`);
  await page.close();
}

{
  const page = await open("reduce");
  await page.goto(BASE + "/specialists", { waitUntil: "networkidle" });
  const row = await page.$('[data-surface="row"]');
  if (row) {
    await row.click();
    const xs = (await frames(page, panelX, 12)).filter((x) => x !== null);
    check("reduced", xs.every((x) => Math.abs(x) < 0.5), `panel travel under reduced motion: ${Math.max(...xs.map(Math.abs))}px`);
    await page.keyboard.press("Escape");
  }
  const btn = await page.$("main button");
  const bb = await btn.boundingBox();
  await page.mouse.move(bb.x + 4, bb.y + 4);
  await page.mouse.down();
  await page.waitForTimeout(150);
  const held = await page.evaluate(([x, y]) => getComputedStyle(document.elementFromPoint(x, y).closest("button")).scale, [bb.x + 4, bb.y + 4]);
  await page.mouse.up();
  check("reduced", held === "none", `held button scale: ${held}`);
  await page.close();
}

await browser.close();
console.log(failed === 0 ? "motion: all promises kept" : `motion: ${failed} broken`);
process.exit(failed === 0 ? 0 : 1);
