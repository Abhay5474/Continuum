// Records the Continuum feature demo: a real browser on a virtual screen, driving the
// Brightcart shop (an app built on Continuum) and the Continuum console, with captions,
// a spotlight and arrows added by overlay.js. Run through record.sh, which starts the
// virtual display and the screen capture. See README.md.
import fs from "node:fs";
import { createRequire } from "node:module";
import path from "node:path";
import { fileURLToPath } from "node:url";

const HERE = path.dirname(fileURLToPath(import.meta.url));
// CommonJS resolution, so a globally installed Playwright is found too.
const { chromium } = createRequire(import.meta.url)("playwright");
const SHOP = process.env.SHOP_URL || "http://localhost:5050";
const CONSOLE = process.env.CONSOLE_URL || "http://localhost:5173";
const API = process.env.CONTINUUM_URL || "http://localhost:8080";
const EMAIL = process.env.DEMO_EMAIL;
const PASSWORD = process.env.DEMO_PASSWORD;
const MARKS = process.env.MARKS_FILE || path.join(HERE, "out", "chapters.json");
if (!EMAIL || !PASSWORD) throw new Error("Set DEMO_EMAIL and DEMO_PASSWORD (the account the shop's key belongs to).");

const sleep = (ms) => new Promise((r) => setTimeout(r, ms));
const T0 = Date.now();
const marks = [];
const log = (...a) => console.log(`[${((Date.now() - T0) / 1000).toFixed(1)}s]`, ...a);

// ---------------------------------------------------------------- Continuum API (setup only)
async function api(method, p, body, token) {
  const r = await fetch(API + p, {
    method, headers: { "Content-Type": "application/json", ...(token ? { Authorization: "Bearer " + token } : {}) },
    body: body === undefined ? undefined : JSON.stringify(body),
  });
  const text = await r.text();
  try { return JSON.parse(text); } catch { return text; }
}
// Chaos off, and the semantic breaker cleared so no model stays benched after the failover scene.
async function resetChaos() {
  await api("POST", "/api/chaos/reset", undefined, SESSION);
  await api("DELETE", "/api/portal/developer/breaker", undefined, SESSION);
}
const login = await api("POST", "/api/portal/developer/login", { email: EMAIL, password: PASSWORD });
const SESSION = login.sessionToken;
if (!SESSION) throw new Error("Could not sign in to Continuum: " + JSON.stringify(login));
await fetch(SHOP + "/app/presenter/login", { method: "POST", headers: { "Content-Type": "application/json" },
  body: JSON.stringify({ email: EMAIL, password: PASSWORD }) });
await resetChaos();

// ---------------------------------------------------------------- browser on the virtual screen
const browser = await chromium.launch({
  headless: false,
  ignoreDefaultArgs: ["--enable-automation"],
  // 1080 px of page plus the browser toolbar; record.sh captures only the page.
  args: ["--window-position=0,0", `--window-size=1920,${1080 + Number(process.env.CHROME_TOP || 87)}`, "--force-device-scale-factor=1",
    "--disable-infobars", "--no-first-run", "--hide-crash-restore-bubble"],
});
const ctx = await browser.newContext({ viewport: null });
await ctx.addInitScript({ path: path.join(HERE, "overlay.js") });
await ctx.addInitScript(([t, port]) => {
  if (location.port === port) {
    localStorage.setItem("continuum.portal.session", t);
    localStorage.setItem("continuum.motion", "full");
  }
}, [SESSION, new URL(CONSOLE).port]);
ctx.on("page", (p) => p.on("dialog", (d) => d.accept()));

const shop = await ctx.newPage();
await shop.goto(SHOP, { waitUntil: "networkidle" });
const con = await ctx.newPage();
await con.goto(CONSOLE + "/dashboard", { waitUntil: "networkidle" });
let page = shop;
await shop.bringToFront();
await sleep(1500);

// ---------------------------------------------------------------- direction helpers
const D = (fn, arg) => page.evaluate(fn, arg);
async function show(p) {
  if (page !== p) { page = p; await p.bringToFront(); await sleep(500); }
}
async function card(o, ms) {
  await D((o) => __demo.card(o), o);
  await sleep(ms);
  await D(() => __demo.hideCard());
  await sleep(700);
}
async function caption(kicker, title, text, ms = 0) {
  await D((a) => __demo.caption({ kicker: a[0], title: a[1], text: a[2] }), [kicker, title, text]);
  if (ms) await sleep(ms);
}
const hideCaption = () => D(() => __demo.hideCaption());
async function chapter(n, group, name) {
  const title = `${n}. ${name}`;
  if (marks[marks.length - 1]?.title !== title) marks.push({ at: Date.now() / 1000, title });
  for (const p of [shop, con]) await p.evaluate((a) => __demo.chapter(a), { n, total: 12, group, name }).catch(() => {});
}
// Tag the smallest element containing `text` (then widen to a card-sized ancestor) and
// return a selector for it, so the spotlight can follow it.
async function tag(text, { within = "body", minW = 160, minH = 50, id = "t" } = {}) {
  return D(([text, within, minW, minH, id]) => {
    document.querySelectorAll(`[data-dm="${id}"]`).forEach((e) => e.removeAttribute("data-dm"));
    const scope = document.querySelector(within) || document.body;
    const all = [...scope.querySelectorAll("*")].filter((e) => {
      const r = e.getBoundingClientRect();
      return r.width > 0 && r.height > 0 && (e.innerText || "").toLowerCase().includes(text.toLowerCase());
    });
    if (!all.length) return null;
    all.sort((a, b) => a.getBoundingClientRect().width * a.getBoundingClientRect().height - b.getBoundingClientRect().width * b.getBoundingClientRect().height);
    let el = all[0];
    while (el.parentElement && (el.getBoundingClientRect().width < minW || el.getBoundingClientRect().height < minH)) el = el.parentElement;
    el.setAttribute("data-dm", id);
    return `[data-dm="${id}"]`;
  }, [text, within, minW, minH, id]);
}
async function focus(sel, opts) {
  if (!sel) return D(() => __demo.unfocus());
  await D(([s, o]) => __demo.focus(s, o || {}), [sel, opts]);
}
async function callout(sel, text, sub, side = "left", dist = 110) {
  if (!sel) return;
  await D(([s, t, sb, sd, d]) => __demo.callout(s, { text: t, sub: sb, side: sd, dist: d }), [sel, text, sub, side, dist]);
}
const clear = () => D(() => __demo.clear());
async function pointClick(locator) {
  const box = await locator.boundingBox();
  if (box) {
    await D(([x, y]) => __demo.pointer(x, y), [box.x + box.width / 2, box.y + box.height / 2]);
    await sleep(800);
    await D(([x, y]) => __demo.pointer(x, y, true), [box.x + box.width / 2, box.y + box.height / 2]);
  }
  await locator.click();
  await sleep(250);
}
async function typeInto(locator, text) {
  await pointClick(locator);
  await locator.pressSequentially(text, { delay: 38 });
}
// Starts one of the shop's own feature scenarios and waits for its result card.
async function runTour(key, timeout = 240000) {
  await shop.evaluate((k) => import("/js/tour.js").then((m) => { window.__tourRun = m.runFeature(k); }), key);
  await shop.waitForSelector("#result:not([hidden])", { timeout });
  await sleep(400);
  const out = await shop.evaluate(() => document.getElementById("result").innerText.replace(/\s+/g, " "));
  const chat = await shop.evaluate(() => [...document.querySelectorAll("#chat-body .msg.bot")].slice(-1).map((m) => m.innerText).join(""));
  if (!failoverScene && /every available model failed/i.test(out + " " + (key === "tools" ? chat : "")))
    throw new Error(`MODEL_FAILURE in tour ${key}: ` + out.slice(0, 200));
  log(`tour ${key}:`, out.slice(0, 140));
  return out;
}
async function closeResult() {
  await shop.click("#r-close").catch(() => {});
  await sleep(500);
}
async function ask(text) {
  if (!(await shop.locator("#chat").isVisible())) { await pointClick(shop.locator("#chat-launch")); await sleep(500); }
  const before = await shop.locator("#chat-body .msg.bot").count();
  await typeInto(shop.locator("#chat-text"), text);
  await shop.keyboard.press("Enter");
  await D(() => __demo.hidePointer());
  await shop.waitForFunction((n) => {
    const m = document.querySelectorAll("#chat-body .msg.bot");
    return m.length > n && !m[m.length - 1].classList.contains("typing") && m[m.length - 1].innerText.trim().length > 3;
  }, before, { timeout: 120000 });
  await sleep(1200);
  const answer = await shop.locator("#chat-body .msg.bot").last().innerText();
  // A take with a failed answer is not usable: stop at once rather than spend more model quota on it.
  if (/every available model failed/i.test(answer) && !failoverScene) throw new Error("MODEL_FAILURE: " + answer.slice(0, 200));
  return answer;
}
let failoverScene = false;
const X_CALL = "#x-current .x-call";

// ================================================================= the film
log("start");
// The film starts on the title card: everything captured before it (browser start-up, sign-in) is trimmed.
const filmStart = Date.now() / 1000 + 0.8;
await card({
  title: "Continuum",
  subtitle: "The reliability layer between your app and AI models",
  text: "Brightcart is an online store built on Continuum. Every AI request the store makes goes through Continuum. "
    + "Watch what Continuum does with each one: routing, protecting, saving, checking, remembering and recovering.",
  foot: "Recorded live: Brightcart + Continuum + Google Gemini. Nothing in this video is scripted output.",
}, 9000);

// 1 ---------------------------------------------------------------- the gateway
await chapter(1, "Talk to any AI", "One key, any model");
await focus("#xray", { pad: 6 });
await callout("#xray", "Continuum X-ray", "shows what Continuum did with every AI call", "left", 120);
await caption("How it fits", "Brightcart never talks to an AI company directly",
  "It sends every request to <b>Continuum</b> with one API key. This panel, the <b>X-ray</b>, shows live what Continuum did with each call.", 6500);
await clear();
await caption("AI gateway", "A customer asks a question",
  "Priya opens the shop assistant. Continuum chooses a model, calls it, checks the answer and records the cost.");
await runTour("gateway");
await focus(X_CALL, { pad: 6 });
await callout(`${X_CALL} .x-meta`, "Answered by Gemini", "Continuum picked the model for this request", "left", 120);
await caption("AI gateway", "Every call shows its path",
  "Guard → Context → Cache → Traffic → Routing → AI model → Checks. The lit steps are the ones Continuum used for this answer.", 7000);
await closeResult();
await clear();

// 2 ---------------------------------------------------------------- tools
await chapter(2, "Talk to any AI", "Real data, not guesses");
await caption("Tool calling", "\"Where is my order?\"",
  "Instead of making something up, the AI asks the store's own order system, through Continuum, and answers from real data.");
await runTour("tools");
await focus("#chat", { pad: 6 });
await callout("#chat-body .msg.bot:last-of-type", "Real order details", "looked up live with a tool call", "left", 140);
await caption("Tool calling", "The answer comes from Brightcart's database",
  "The model called <b>lookup_order</b>; Continuum ran the call and gave the result back to the model.", 6500);
await closeResult();
await clear();

// 3 ---------------------------------------------------------------- firewall
await chapter(3, "Protect the store", "Prompt firewall");
await caption("Privacy and safety", "A customer pastes a card number, and an attacker tries a trick",
  "Continuum screens every message <b>before</b> it leaves for the AI provider.");
await runTour("prompt-firewall");
const guard = await tag("Prompt firewall", { within: "#x-current", minW: 300, minH: 50, id: "g" });
await focus(X_CALL, { pad: 6 });
await callout(guard, "Blocked before any AI saw it", "\"Ignore all previous instructions…\"", "left", 140);
await caption("Prompt firewall", "The attack never reached the model",
  "Card numbers, CVV and PIN are masked, and prompt-injection attempts are refused outright.", 7000);
await closeResult();
await clear();
await show(con);
await con.goto(CONSOLE + "/guard", { waitUntil: "networkidle" });
await chapter(3, "Protect the store", "Prompt firewall");
await sleep(1800);
const seen = await tag("What the provider sees", { minW: 500, minH: 150, id: "p" });
await focus(seen || "main", { pad: 8 });
await callout(seen, "What the AI provider received", "personal details replaced by placeholders", "left", 90);
await caption("Continuum console", "The console shows exactly what left the building",
  "PII redacted, injections blocked, and the prompt as the provider saw it.", 7000);
await clear();

// 4 ---------------------------------------------------------------- semantic cache
await show(shop);
await chapter(4, "Spend less", "Semantic cache");
await caption("Spend less", "The same question, asked again",
  "Continuum remembers answers by <b>meaning</b>. A repeat is answered instantly, with no AI call and no cost.");
await runTour("semantic-cache");
const hit = await tag("Instant answer from cache", { minW: 600, minH: 60, id: "c" });
if (hit) {
  await focus(hit, { pad: 10 });
  await callout(hit, "Answered from cache", "milliseconds instead of seconds · no AI cost", "top", 90);
} else {
  await focus("#result", { pad: 8 });
}
await caption("Semantic cache", "Faster and free on repeated questions",
  "The first answer was written by the AI; the repeat came from Continuum's cache.", 7000);
await clear();
await focus("#result", { pad: 8 });
await caption("Semantic cache", "Measured live", "", 4500);
await closeResult();
await clear();

// 5 ---------------------------------------------------------------- failover
await chapter(5, "Never go down", "Provider failover");
await show(con);
failoverScene = true;
await api("POST", "/api/chaos/provider-failure-rate?rate=0.3", undefined, SESSION);
await con.goto(CONSOLE + "/chaos", { waitUntil: "networkidle" });
await chapter(5, "Never go down", "Provider failover");
await sleep(1500);
const rate = await tag("Provider Failure Rate", { minW: 260, minH: 60, id: "r" });
await focus(rate || "main", { pad: 10 });
await callout(rate, "30% of AI calls will now fail", "on purpose, with Continuum's chaos lab", "right", 100);
await caption("Chaos lab", "We break the AI provider on purpose",
  "Almost one in three calls to Gemini will now fail. In a normal app, those customers would see an error.", 7000);
await clear();
await show(shop);
await caption("Never go down", "The customer keeps chatting",
  "When a call fails, Continuum quietly tries the next model in the chain.");
let failoverShown = false;
for (const q of ["Is the brass diya set made of real brass?", "Can I gift-wrap the Banarasi saree?",
  "Do the wireless earbuds come with a warranty?", "Is the pressure cooker safe on an induction stove?"]) {
  await ask(q);
  const fo = await tag("Provider failover", { within: "#x-current", minW: 300, minH: 50, id: "f" });
  if (fo) {
    await focus(X_CALL, { pad: 6 });
    await callout(fo, "A failure was absorbed", "the customer saw a normal answer", "left", 140);
    await caption("Provider failover", "The provider failed, the store did not",
      "Continuum caught the failed call and answered from the next model. The customer never knew.", 7500);
    failoverShown = true;
    break;
  }
}
await clear();
await resetChaos();
failoverScene = false;
await show(con);
await con.goto(CONSOLE + "/gateway", { waitUntil: "networkidle" });
await chapter(5, "Never go down", "Provider failover");
await sleep(2000);
const absorbed = await tag("Absorbed failures", { minW: 200, minH: 90, id: "a" });
const reached = await tag("Reached your app", { minW: 200, minH: 90, id: "b" });
await focus(absorbed || "main", { pad: 8 });
await callout(absorbed, "Failures Continuum absorbed", "each one would have been an error page", "bottom", 70);
await caption("Continuum console", failoverShown ? "Every failure is counted" : "Failures absorbed, counted",
  "<b>Absorbed failures</b>: provider errors Continuum handled. <b>Reached your app</b>: the ones it could not.", 7500);
await clear();
log("failover shown:", failoverShown);

// 6 ---------------------------------------------------------------- confidence
await show(shop);
await chapter(6, "Trust the answers", "Answer confidence");
await caption("Trust the answers", "How sure is the AI?",
  "Continuum asks the model the same question several times. If the answers agree, it is sure. If they disagree, it is guessing.");
await runTour("confidence");
await closeResult();
// The score itself, on the reply draft the agent is about to send.
const gaugeSel = ".draft .gauge";
if (await shop.locator(gaugeSel).count()) {
  await focus(gaugeSel, { pad: 14 });
  await callout(gaugeSel, "Confidence, measured live", "the model was asked again and agreed with itself", "right", 120);
} else {
  await focus("#result", { pad: 8 });
}
await caption("Answer confidence", "A confidence score for every important answer",
  "High agreement: safe to send. Low agreement: flag it or check before acting.", 7000);
await clear();

// 7 ---------------------------------------------------------------- memory
await chapter(7, "Remember and learn", "Long-term memory");
await show(con);
await con.goto(CONSOLE + "/memory", { waitUntil: "networkidle" });
await chapter(7, "Remember and learn", "Long-term memory");
await sleep(1500);
await caption("Memory", "Teach the app a fact, once",
  "We store one fact in Continuum's memory, in the <b>brightcart</b> scope that the shop assistant reads for every customer.");
const chip = con.locator("button", { hasText: /^brightcart( ·.*)?$/ }).first();
if (await chip.count()) await pointClick(chip);
await typeInto(con.locator("textarea").first(), "The operator of the Brightcart app is Abhay.");
await pointClick(con.locator("button", { hasText: /^Long-term$/ }).first());
const storeBtn = con.locator("button", { hasText: /^Store$/ }).first();
await focus(await tag("Store memory", { minW: 500, minH: 200, id: "m" }), { pad: 8 });
await pointClick(storeBtn);
await D(() => __demo.hidePointer());
await sleep(2500);
await clear();
await show(shop);
await caption("Memory", "Now ask the shop assistant",
  "The shop's code did not change. Continuum recalls the fact and gives it to the model.");
// A fresh conversation, so the answer is the only thing in the chat.
await shop.evaluate(() => import("/js/chat.js").then((m) => m.newConversation()));
await sleep(600);
const who = await ask("Who is the operator of Brightcart?");
log("memory answer:", who.slice(0, 120));
await focus("#chat", { pad: 6 });
await callout("#chat-body .msg.bot:last-of-type", "Recalled from memory", "stored a moment ago in the console", "left", 140);
await caption("Long-term memory", "The assistant now knows",
  "Memories are kept per customer and per app, and ranked by relevance, recency and importance.", 7000);
await clear();

// 8 ---------------------------------------------------------------- durable workflows
await chapter(8, "Run real processes", "Durable workflows");
await caption("Durable workflows", "A refund is many steps across many systems",
  "Check the order, wait for fraud checks, refund the card, restock, email the customer, book a courier, note it in the CRM.");
await runTour("workflows");
const track = await tag("Check order", { within: "#views", minW: 1000, minH: 80, id: "w" });
await focus(track || "#views", { pad: 12 });
await callout(track, "Every step is recorded", "a crash resumes here, it never starts over", "bottom", 80);
await caption("Durable workflows", "Crash-safe and exactly once",
  "Continuum writes each step down before the next one starts. If the server dies, another worker carries on, and the card is <b>never refunded twice</b>.", 8000);
await closeResult();
await clear();
const runs = await api("GET", "/api/workflows", undefined, SESSION);
const latest = (Array.isArray(runs) ? runs : []).find((r) => r.workflowType === "Declarative");
if (latest) {
  await show(con);
  await con.goto(CONSOLE + "/workflows/" + latest.workflowId, { waitUntil: "networkidle" });
  await chapter(8, "Run real processes", "Durable workflows");
  await sleep(2500);
  await caption("Continuum console", "The same run, step by step",
    "Each step with its timing, retries and result: the full history behind one refund.", 7000);
  await clear();
}

// 9 ---------------------------------------------------------------- saga
await show(shop);
await chapter(9, "Run real processes", "Undo when things fail");
await caption("Saga compensation", "What if a later step keeps failing?",
  "This time the courier service is down. Continuum retries it, then <b>undoes</b> what was already done, newest first.");
await runTour("saga");
const undo = await tag("Saga rollback", { within: "#views", minW: 900, minH: 40, id: "s" });
const steps = await tag("Courier pickup", { within: "#views", minW: 1000, minH: 80, id: "s2" });
await focus(steps || "#views", { pad: 12 });
await callout(steps, "Courier failed 3 times", "restock and refund were undone", "bottom", 80);
await caption("Saga compensation", "No half-finished refunds",
  "The customer is not left with money taken and stock out of place. What could not be undone (an email already sent) is reported, not hidden.", 8500);
if (undo) { await focus(undo, { pad: 8 }); await sleep(3000); }
await closeResult();
await clear();

// 10 ---------------------------------------------------------------- specialists
await chapter(10, "Bring your own models", "Evidence before answers");
await caption("Pipelines and specialists", "Give the AI eyes",
  "A receipt is uploaded. Continuum first runs a <b>specialist model</b> that reads it, turns what it found into evidence, and only then asks the language model.");
await runTour("pipelines");
await focus("#result", { pad: 8 });
await caption("Evidence pipeline", "One request in, a checked verdict out",
  "The answer is based on what the specialist actually read, and Continuum checks it does not claim more than the evidence shows.", 8000);
await closeResult();
await clear();

// 11 ---------------------------------------------------------------- provenance
await chapter(11, "See why", "Decision provenance");
await caption("Nothing is a black box", "Why did the AI answer that?",
  "Continuum records every decision it took for a request: which model, why, what was checked, what it cost.");
await runTour("provenance");
await focus(X_CALL, { pad: 6 });
await caption("Decision provenance", "Every decision, recorded and explainable",
  "Readable here, or exported to any tracing tool.", 7000);
await closeResult();
await clear();

// 12 ---------------------------------------------------------------- the console overview
await show(con);
await con.goto(CONSOLE + "/dashboard", { waitUntil: "networkidle" });
await chapter(12, "Operate", "Command Centre");
await sleep(2500);
const tiles = await tag("Absorbed failures", { minW: 200, minH: 90, id: "d" });
await focus(tiles || "main", { pad: 10 });
await caption("Continuum console", "One place to see everything",
  "Requests, success rate, failures absorbed, spend, tokens and running workflows, across every app built on Continuum.", 8000);
await clear();
await D(() => __demo.chapter({}));

await card({
  title: "Continuum",
  subtitle: "Make AI failures harmless, costs predictable and uncertainty visible",
  items: [
    ["Never lose work", "durable workflows, exactly-once steps, undo on failure"],
    ["Never stuck", "failover across models and providers, traffic control"],
    ["Checks answers", "confidence, quality gate, verification"],
    ["Spends less", "semantic cache, cascade, compression"],
    ["Guards secrets", "PII redaction, prompt-injection screening"],
    ["Gives AI eyes", "specialist models and evidence pipelines"],
  ],
  foot: "One OpenAI-compatible endpoint · Java 21 · Spring Boot · PostgreSQL · React",
}, 11000);

fs.mkdirSync(path.dirname(MARKS), { recursive: true });
fs.writeFileSync(MARKS, JSON.stringify(marks, null, 1));
fs.writeFileSync(path.join(path.dirname(MARKS), "film-start.txt"), String(filmStart));
log("done");
await resetChaos();
await browser.close();
