import { chromium } from "playwright";
const b = await chromium.launch({ executablePath: "/opt/pw-browsers/chromium" });
const p = await (await b.newContext({ viewport: { width: 1360, height: 900 } })).newPage(); const B = "http://127.0.0.1:5199";
await p.goto(B); await p.evaluate((t) => localStorage.setItem("continuum.portal.session", t), "REVWRUxPUEVSOmRldl8zZWNhZmQ2MzQ1NmE0MmNhYjdiYzoxNzkwMzk4MzEx.vJy-uKL7kQEPYq-hXiWNaac-QyiP7w9PiQZ83U9tLi0");
for (const r of process.argv.slice(2)) { await p.goto(B + r, { waitUntil: "networkidle" }); await p.waitForTimeout(500); await p.screenshot({ path: "/tmp/claude-0/-home-user-Continuum/3769f4af-4cde-5854-bfdf-6356279d52ce/scratchpad/u" + r.replace(/\//g, "_") + ".png", fullPage: true }); }
await b.close();
