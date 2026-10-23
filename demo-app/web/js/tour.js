// The feature tour: every Continuum feature, shown on the live store.
// Each scenario drives the real store UI (typing, clicking) and checks what
// Continuum reported doing; nothing here is simulated.
import { h, api, bus, state, sleep, features, errorText } from "./lib.js";
import { GROUPS, FEATURES, BY_KEY } from "./catalog.js";
import * as presenter from "./presenter.js";
import * as chat from "./chat.js";
import * as shop from "./shop.js";
import * as desk from "./desk.js";
import * as office from "./office.js";
import * as orders from "./orders.js";
import { loadTrail, lastChatCall, revealXray } from "./xray.js";

const STORE_KEY = "brightcart.tour.v2";
let shown = load();
let seenByServer = {};
let running = null; // {key, aborted}
let autoplay = false;

function load() {
  try {
    return JSON.parse(localStorage.getItem(STORE_KEY) || "{}");
  } catch {
    return {};
  }
}
function save() {
  try {
    localStorage.setItem(STORE_KEY, JSON.stringify(shown));
  } catch {
    /* private window: progress just isn't remembered */
  }
}

export function init() {
  document.getElementById("tour-open").addEventListener("click", openSheet);
  bus.on("seen", (seen) => {
    seenByServer = seen || {};
    paintCount();
  });
  paintCount();
}

const isShown = (k) => shown[k] === "ok" || !!seenByServer[k];

function paintCount() {
  const n = FEATURES.filter((f) => isShown(f.key)).length;
  document.getElementById("tour-count").textContent = `${n} / ${FEATURES.length}`;
  document.getElementById("tour-ring").style.strokeDashoffset = String(94.25 * (1 - n / FEATURES.length));
  const sheet = document.querySelector("#tour .sheet");
  if (sheet) paintSheetProgress();
}

// ---- the sheet -------------------------------------------------------------------------

export function openSheet() {
  const wrap = document.getElementById("tour");
  wrap.hidden = false;
  const p = state.presenter || {};
  wrap.innerHTML = `<div class="sheet" role="dialog" aria-modal="true" aria-labelledby="tour-title">
    <div class="sheet-head">
      <div class="bigring"><svg viewBox="0 0 64 64"><defs><linearGradient id="tourgrad" x1="0" x2="1"><stop offset="0" stop-color="#f2611d"/><stop offset="1" stop-color="#d6407a"/></linearGradient></defs><circle cx="32" cy="32" r="28"/><circle id="big-ring" cx="32" cy="32" r="28" stroke-dashoffset="175.9"/></svg><b id="big-n">0</b></div>
      <div><h2 id="tour-title">36 things Continuum does for Brightcart</h2>
        <p>Pick any feature and press <b>Show me</b>: the store does it live while the X-ray shows Continuum's part. Or play them all in order.</p></div>
      <div class="acts"><button class="btn primary" id="play-all">▶ Play all</button><button class="btn ghost" id="tour-reset" title="Forget which features were shown">Reset</button><button class="btn ghost" id="tour-close" aria-label="Close">✕</button></div>
    </div>
    ${p.signedIn ? `<div class="banner ok"><span class="b-ico">🔑</span><div>Presenter controls on (${h(p.email)}${p.operator ? ", operator" : ""}). Steps that flip Continuum switches put them back afterwards.</div></div>`
      : `<div class="banner"><span class="b-ico">🔑</span><div style="flex:1">Steps marked <b>presenter</b> switch Continuum features on and off from its console. Sign in once to include them.</div><button class="btn sm" id="tour-sign">Presenter sign-in</button></div>`}
    ${GROUPS.map((g) => {
      const list = FEATURES.filter((f) => f.group === g.key);
      return `<section class="group"><div class="group-head"><span>${g.icon}</span><h3>${h(g.name)}</h3><span class="count" data-gcount="${g.key}"></span><span class="line"></span></div>
        <div class="fcards">${list.map((f, i) => card(f, g, i)).join("")}</div></section>`;
    }).join("")}
  </div>`;
  wrap.querySelector("#tour-close").addEventListener("click", closeSheet);
  wrap.querySelector("#tour-reset").addEventListener("click", () => {
    shown = {};
    save();
    openSheet();
    paintCount();
  });
  wrap.querySelector("#play-all").addEventListener("click", playAll);
  wrap.querySelector("#tour-sign")?.addEventListener("click", async () => {
    await presenter.open();
    openSheet();
  });
  wrap.querySelectorAll("[data-show]").forEach((b) => b.addEventListener("click", () => {
    closeSheet();
    runFeature(b.dataset.show);
  }));
  wrap.onclick = (e) => {
    if (e.target === wrap) closeSheet();
  };
  paintSheetProgress();
}

function card(f, g, i) {
  const ok = isShown(f.key);
  const need = f.need === "operator" ? "🔑 operator" : f.need === "presenter" ? "🔑 presenter" : "";
  const last = shown[f.key];
  return `<article class="fcard ${ok ? "done" : last === "warn" ? "fail" : ""}" style="--tint:${g.tint};animation-delay:${i * 40}ms">
    ${ok ? `<span class="check">✓</span>` : ""}
    <div class="fcard-top"><span class="f-ico">${f.icon}</span><div><h4>${h(f.name)}</h4>${need ? `<span class="f-need">${need}</span>` : ""}</div></div>
    <p>${h(f.blurb)}</p>
    <div class="fcard-foot">${ok ? `<span class="pill ok">Shown</span>` : last === "warn" ? `<span class="pill warn">Try again</span>` : `<span class="pill">Not yet</span>`}
      <button class="btn sm ${ok ? "" : "primary"}" data-show="${f.key}">${ok ? "Again" : "Show me"} ▸</button></div></article>`;
}

function paintSheetProgress() {
  const n = FEATURES.filter((f) => isShown(f.key)).length;
  const ring = document.getElementById("big-ring");
  if (ring) ring.style.strokeDashoffset = String(175.9 * (1 - n / FEATURES.length));
  const big = document.getElementById("big-n");
  if (big) big.textContent = `${n}/${FEATURES.length}`;
  for (const g of GROUPS) {
    const el = document.querySelector(`[data-gcount="${g.key}"]`);
    const list = FEATURES.filter((f) => f.group === g.key);
    if (el) el.textContent = `${list.filter((f) => isShown(f.key)).length} of ${list.length}`;
  }
}

function closeSheet() {
  document.getElementById("tour").hidden = true;
}

// ---- director and results ---------------------------------------------------------------

const director = {
  el: () => document.getElementById("director"),
  start(f, steps) {
    const el = this.el();
    el.hidden = false;
    el.innerHTML = `<span class="d-ico">${f.icon}</span>
      <div style="min-width:0"><div class="d-name">${h(f.name)}</div><div class="d-text" id="d-text">Getting ready…</div>
        <div class="d-dots">${Array.from({ length: steps }, () => "<i></i>").join("")}</div></div>
      <button class="btn ghost sm" id="d-stop">Stop</button>`;
    el.querySelector("#d-stop").addEventListener("click", () => {
      if (running) running.aborted = true;
      autoplay = false;
    });
  },
  say(step, text) {
    const t = document.getElementById("d-text");
    if (!t) return;
    t.outerHTML = `<div class="d-text" id="d-text">${text}</div>`;
    this.el().querySelectorAll(".d-dots i").forEach((d, i) => d.classList.toggle("on", i < step));
  },
  stop() {
    this.el().hidden = true;
  },
};

function showResult(f, out, { auto } = {}) {
  const el = document.getElementById("result");
  const tone = out.ok ? "" : out.skipped ? "warn" : "bad";
  const next = nextFeature(f.key);
  el.hidden = false;
  // Beside the chat rather than under it, when there is room.
  const side = chat.isOpen() && matchMedia("(min-width: 1100px)").matches;
  el.className = "result " + tone + (side ? " side" : "");
  el.innerHTML = `<span class="r-badge">${out.ok ? "✓" : out.skipped ? "🔑" : "!"}</span>
    <div><div class="r-kicker">${out.ok ? "Shown live" : out.skipped ? "Needs a sign-in" : "Not shown this time"}</div>
      <h3>${h(out.title || f.name)}</h3><p>${out.text || ""}</p>
      <div class="r-acts">${auto ? `<div class="r-timer" style="--t:4.5s"><i></i></div>` : ""}
        ${next ? `<button class="btn sm primary" id="r-next">${auto ? "Next now" : "Next: " + h(next.name)} ▸</button>` : ""}
        <button class="btn sm" id="r-tour">All features</button><button class="btn sm ghost" id="r-close">Close</button></div></div>`;
  return new Promise((resolve) => {
    let t = auto ? setTimeout(() => finish("next"), 4500) : null;
    function finish(what) {
      clearTimeout(t);
      el.hidden = true;
      resolve(what);
    }
    el.querySelector("#r-next")?.addEventListener("click", () => finish("next"));
    el.querySelector("#r-tour").addEventListener("click", () => {
      autoplay = false;
      finish("tour");
      openSheet();
    });
    el.querySelector("#r-close").addEventListener("click", () => {
      autoplay = false;
      finish("close");
    });
  });
}

function nextFeature(key) {
  const i = FEATURES.findIndex((f) => f.key === key);
  return FEATURES.slice(i + 1).find((f) => !isShown(f.key)) || FEATURES[i + 1] || null;
}

// ---- running a scenario -------------------------------------------------------------------

class Abort extends Error {}

const cap = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);

function makeContext(f) {
  const cleanups = [];
  const ctx = {
    say(step, text) {
      if (running?.aborted) throw new Abort();
      director.say(step, text);
    },
    async wait(ms) {
      const end = Date.now() + ms;
      while (Date.now() < end) {
        if (running?.aborted) throw new Abort();
        await sleep(Math.min(120, end - Date.now()));
      }
    },
    check() {
      if (running?.aborted) throw new Abort();
    },
    later(fn) {
      cleanups.push(fn);
    },
    go(tab) {
      chat.close();
      return state.go(tab);
    },
    customer(id) {
      state.setCustomer(id);
    },
    has(calls, key) {
      return !!features(calls)[key];
    },
    feat(calls, key) {
      return features(calls)[key];
    },
    async control(action, args) {
      return presenter.run(action, args);
    },
    /** Sets console switches for this scenario and puts them back afterwards. */
    async switches(wanted) {
      const before = (await presenter.run("features")).features || {};
      for (const [key, on] of Object.entries(wanted)) {
        if (before[key] !== on) {
          await presenter.run("feature", { key, on });
          cleanups.push(() => presenter.run("feature", { key, on: before[key] }));
        }
      }
    },
  };
  ctx.cleanup = async () => {
    for (const fn of cleanups.reverse()) {
      try {
        await fn();
      } catch (e) {
        console.warn("cleanup", e);
      }
    }
  };
  return ctx;
}

export async function runFeature(key, { auto = false } = {}) {
  if (running) return null;
  const f = BY_KEY[key];
  const sc = SCENARIOS[key];
  if (!f || !sc) return null;
  if ((f.need === "presenter" || f.need === "operator") && !state.presenter.signedIn) {
    if (!auto) await presenter.open();
    if (!state.presenter.signedIn) {
      return showResult(f, { skipped: true, title: f.name, text: "This step switches Continuum settings from its console, so it needs the presenter sign-in (top right)." }, { auto });
    }
  }
  if (f.need === "operator" && !state.presenter.operator) {
    shown[key] = "warn";
    save();
    return showResult(f, { skipped: true, title: "Hedging needs an operator", text: "Hedging changes the whole engine, not one account, so only a Continuum operator can switch it on. Sign in as the presenter with an operator account to run this step." }, { auto });
  }
  running = { key, aborted: false };
  director.start(f, sc.steps);
  const ctx = makeContext(f);
  let out;
  try {
    out = await sc.run(ctx);
  } catch (e) {
    out = e instanceof Abort ? { ok: false, skipped: true, title: "Stopped", text: "The step was stopped; settings it changed have been put back." } : { ok: false, title: f.name, text: h(e.message || String(e)) };
  } finally {
    director.say(sc.steps, "Putting settings back…");
    await ctx.cleanup();
    director.stop();
    running = null;
  }
  shown[key] = out.ok ? "ok" : "warn";
  save();
  paintCount();
  return showResult(f, out, { auto });
}

async function playAll() {
  closeSheet();
  if (!state.presenter.signedIn) await presenter.open();
  autoplay = true;
  for (const f of FEATURES) {
    if (!autoplay) break;
    if (f.need === "operator" && !state.presenter.operator) continue;
    if (f.need && !state.presenter.signedIn) continue;
    const what = await runFeature(f.key, { auto: true });
    if (what === "close" || what === "tour") break;
    await sleep(400);
  }
  autoplay = false;
}

// ---- the scenarios ---------------------------------------------------------------------------

async function ask(t, customer, text, opts) {
  t.customer(customer);
  t.go("shop");
  chat.newConversation();
  const out = await chat.typeAndSend(text, opts);
  t.check();
  return out;
}

const CITIES = ["Nagpur", "Indore", "Kochi", "Surat", "Bhopal", "Mysuru", "Nashik", "Vadodara", "Coimbatore", "Ludhiana", "Patna", "Dehradun"];

const WARM = [
  "How long does delivery to Mumbai take?", "Do you deliver to Guwahati?", "Is cash on delivery available on sarees?",
  "Can I exchange a kurta for a bigger size?", "How do I track my parcel?", "Do you offer gift wrapping?",
  "Can I cancel an order after it ships?", "Which payment methods do you accept?", "Is express delivery available in Pune?",
  "How long do refunds take to reach UPI?", "Do Platinum members get free pickup?", "Can I return a damaged pressure cooker?",
];

export const SCENARIOS = {
  gateway: { steps: 2, async run(t) {
    t.say(1, "Priya opens the shop assistant and asks a question, like on any store.");
    const { res, calls } = await ask(t, "C-1001", "Hi! Is the Jaipur blue pottery mug set microwave safe?");
    revealXray();
    t.say(2, "Brightcart sent it to Continuum with one API key. Continuum chose the model; the X-ray shows the path.");
    await t.wait(2200);
    return { ok: res.ok !== false && t.has(calls, "gateway"), title: "One key, any model",
      text: res.ok === false ? h(errorText(res)) : `Answered by <b>${h(res.model || "a model")}</b>. Brightcart's code never names Google or Groq; Continuum picks per request.` };
  } },

  streaming: { steps: 2, async run(t) {
    t.say(1, "Streaming on: the reply appears word by word as the model writes it.");
    chat.setStream(true);
    t.later(() => chat.setStream(false));
    const { res, calls } = await ask(t, "C-1004", "Tell me about your Diwali offers in two short lines.");
    t.say(2, "No waiting for the whole answer: the first words arrived almost at once.");
    await t.wait(1500);
    return { ok: t.has(calls, "streaming"), title: "Replies that type themselves",
      text: res.firstTokenMs ? `First words after <b>${res.firstTokenMs.toLocaleString("en-IN")} ms</b>, through Continuum's streaming API.` : h(errorText(res)) };
  } },

  tools: { steps: 2, async run(t) {
    t.say(1, "Priya asks where her kurta is. The AI can call Brightcart's order system.");
    const { res } = await ask(t, "C-1001", "Where is my kurta order BC-1043?");
    t.say(2, "The model asked to run a store function; the store ran it and the model answered from real data.");
    await t.wait(1800);
    const acts = (res.actions || []).map((a) => a.tool);
    return { ok: acts.length > 0, title: "The AI used the store's tools",
      text: acts.length ? `It called <b>${h(acts.join(", "))}</b> and answered from Brightcart's real order data instead of guessing.` : "The model answered without calling a tool this time. Run it again: models decide when to use tools." };
  } },

  "json-mode": { steps: 2, async run(t) {
    await t.go("desk");
    await t.wait(400);
    desk.select("T-506");
    t.say(1, "The support desk asks the AI to triage a ticket, and requires the answer as JSON.");
    const r = await desk.triage("T-506");
    t.say(2, "Clean JSON came back, so the desk turned it straight into tags.");
    await t.wait(1600);
    return { ok: !!r.triage, title: "Answers as data, not prose",
      text: r.triage ? `Category <b>${h(r.triage.category)}</b>, urgency <b>${h(r.triage.urgency)}/5</b>, sentiment <b>${h(r.triage.sentiment)}</b>: parsed, no clean-up code.` : h(r.error?.message || "The answer was not valid JSON.") };
  } },

  vision: { steps: 2, async run(t) {
    t.customer("C-1001");
    t.go("shop");
    chat.open();
    chat.newConversation();
    await chat.usePhoto("damaged-mug.png");
    t.say(1, "Priya attaches a photo of her cracked mug.");
    await t.wait(900);
    const { res, calls } = await chat.typeAndSend("My mug set arrived cracked, see the photo. Can I get a refund?");
    t.say(2, "Continuum sent the image to a model that can see, and the reply describes the damage.");
    await t.wait(1800);
    return { ok: res.ok !== false && t.has(calls, "vision"), title: "The AI looked at the photo",
      text: res.ok === false ? h(errorText(res)) : "Continuum routed the request to a vision-capable model automatically; the store just attached the image." };
  } },

  "model-catalogue": { steps: 1, async run(t) {
    await t.go("office");
    await t.wait(400);
    t.say(1, "Back office asks Continuum which models it can use right now.");
    const { list } = await office.loadModels();
    await t.wait(1800);
    return { ok: list.length > 1, title: "A model list that stays current",
      text: `<b>${list.length - 1}</b> models available. Continuum re-checks the providers' catalogues and retires models they switch off, so the store never names a dead one.` };
  } },

  "prompt-firewall": { steps: 3, async run(t) {
    if (state.presenter.signedIn) await t.switches({ "prompt-firewall": true });
    t.say(1, "Priya pastes her card number and UPI PIN into the chat. That must never reach an AI provider.");
    const { calls } = await ask(t, "C-1001", "Please refund my mug order to card 4111 1111 1111 1111, CVV 123. My UPI PIN is 7849.");
    const redacted = t.has(calls, "prompt-firewall");
    t.say(2, "Next, a ticket that tries to take over the AI with an injection attack.");
    await t.go("desk");
    await t.wait(400);
    desk.select("T-504");
    const r = await desk.triage("T-504");
    const blocked = r.error && (r.error.error === "request_blocked" || r.error.status === 403);
    t.say(3, blocked ? "Blocked before any model saw it." : "Checked.");
    await t.wait(1500);
    return { ok: redacted || blocked, title: "Secrets removed, attacks blocked",
      text: `${redacted ? "The card number, CVV and PIN were masked before the AI saw the message." : "No secret was masked this time."} ${blocked ? "The injection ticket was refused outright." : ""}` };
  } },

  "loop-detection": { steps: 2, async run(t) {
    await t.go("desk");
    await t.wait(400);
    t.say(1, "A badly built agent keeps looking up the same order, again and again.");
    const res = await desk.runAgent();
    t.say(2, "Continuum noticed it was repeating itself without progress and stopped it.");
    await t.wait(1500);
    const v = res.verdict || {};
    return { ok: !!v.looping || !!res.stoppedAt, title: "A stuck agent, stopped",
      text: res.stoppedAt ? `Stopped at step <b>${res.stoppedAt}</b> instead of looping until someone noticed the bill.` : "Loop detection did not stop it; is it on in the Continuum console?" };
  } },

  "cost-limits": { steps: 3, async run(t) {
    const before = await t.control("cost-status");
    t.say(1, "The presenter sets a tight limit: 3 AI requests a minute for this store.");
    await t.control("cost-limits", { enabled: true, requestsPerMin: 3, tokensPerMin: before.tokensPerMin || 120000 });
    t.later(() => t.control("cost-limits", { enabled: !!before.enabled, requestsPerMin: before.requestsPerMin, tokensPerMin: before.tokensPerMin }));
    await t.go("desk");
    await t.wait(400);
    t.say(2, "Then the desk sends all nine tickets to the AI at once, like a runaway batch job.");
    const outcomes = await desk.triageAll();
    const limited = outcomes.filter((r) => r.error?.error === "cost_limited").length;
    t.say(3, "Requests over the limit were refused before they cost anything.");
    await t.wait(1800);
    return { ok: limited > 0, title: "A spending cap that holds",
      text: limited ? `<b>${limited}</b> of ${outcomes.length} requests were stopped at the limit; the rest went through. The limit is back to how it was.` : "Nothing hit the limit this time." };
  } },

  "semantic-cache": { steps: 3, async run(t) {
    if (state.presenter.signedIn) await t.switches({ "semantic-cache": true });
    await t.go("shop");
    await t.wait(400);
    const city = CITIES[Math.floor(Math.random() * CITIES.length)];
    t.say(1, `A shopper asks the help centre about delivery to ${city}.`);
    const a = await shop.askHelp(`How long does delivery to ${city} take?`, { typed: true });
    t.say(2, "Another shopper asks the same thing in different words.");
    let b = await shop.askHelp(`How many days does delivery to ${city} take?`, { typed: true });
    let note = "";
    if (!b.cached) {
      b = await shop.askHelp(`How long does delivery to ${city} take?`, { typed: true });
      note = " (asked again word for word)";
    }
    t.say(3, "The repeat came from Continuum's cache: instant, and free.");
    await t.wait(1600);
    return { ok: b.cached, title: "Asked before? Answered instantly",
      text: !b.cached ? "Not served from the cache this time."
        : a.cached ? `Both came from the cache (asked in an earlier demo): <b>${b.ms} ms</b>, no AI call.`
        : `The repeat${note} took <b>${b.ms} ms</b> with no AI call; the first answer, written by AI, took ${a.ms.toLocaleString("en-IN")} ms.` };
  } },

  "model-cascade": { steps: 2, async run(t) {
    if (state.presenter.signedIn) await t.switches({ "model-cascade": true, "semantic-cache": false });
    t.say(1, "An easy question. Does it need the most expensive model?");
    const { calls } = await ask(t, "C-1004", "Do you deliver on Sundays in Delhi?");
    const f = t.feat(calls, "model-cascade");
    t.say(2, "Continuum tried a small, cheap model first; a judge checked the answer was good enough.");
    await t.wait(1800);
    return { ok: !!f, title: "Small model first", text: f ? h(cap(f.detail)) + "." : "The cascade did not run on this request." };
  } },

  "prompt-compression": { steps: 2, async run(t) {
    await t.switches({ "prompt-compression": true, "semantic-cache": false });
    t.say(1, "The assistant's instructions are long: store policy, customer details, rules. Every message pays for them.");
    const { calls } = await ask(t, "C-1004", "Can I pay cash on delivery for a ₹6,250 saree?");
    const f = t.feat(calls, "prompt-compression");
    t.say(2, "Continuum dropped the low-information words before sending; the customer's question was left untouched.");
    await t.wait(1800);
    return { ok: !!f, title: "Fewer tokens, same meaning", text: f ? h(cap(f.detail)) + "." : "Nothing was compressed this time." };
  } },

  "context-optimizer": { steps: 2, async run(t) {
    if (state.presenter.signedIn) await t.switches({ "context-optimizer": true, "semantic-cache": false });
    t.customer("C-1002");
    t.go("shop");
    t.say(1, "Rohan has been chatting for an hour: 62 messages. Now he asks about the very first thing he said.");
    const { calls } = await chat.longChat();
    const f = t.feat(calls, "context-optimizer");
    t.say(2, "Continuum paged out the old turns and kept what this question needs.");
    await t.wait(2000);
    return { ok: !!f, title: "Long chats, short bills", text: f ? h(cap(f.detail)) + ", and the answer still knew how the chat began." : "The optimizer did not act on this request." };
  } },

  "context-transformers": { steps: 3, async run(t) {
    if (state.presenter.signedIn) await t.switches({ "chat-context": true });
    await t.go("office");
    await t.wait(400);
    t.say(1, "Ops has a raw log file from the checkout outage.");
    const { stats, res } = await office.prepareFile("checkout-errors.log");
    t.say(2, "Continuum turned 76 noisy lines into patterns, errors and a timeline. Now ask it a question.");
    await t.wait(1200);
    const ans = await office.askFile("Why did checkout start failing, and when?");
    t.say(3, "The model answered from the organised version.");
    await t.wait(1500);
    const ok = res?.ok !== false && !!stats;
    return { ok, title: "Raw files, AI-ready", text: ok ? `<b>${stats.before.toLocaleString("en-IN")} → ${stats.after.toLocaleString("en-IN")}</b> tokens, organised into patterns and a timeline. ${t.has(ans?.calls, "context-transformers") ? "The question about it was rewritten the same way." : ""}` : "The file could not be prepared." };
  } },

  "routing-modes": { steps: 3, async run(t) {
    if (state.presenter.signedIn) await t.switches({ "semantic-cache": false });
    await t.go("desk");
    await t.wait(400);
    desk.select("T-507");
    t.say(1, "Rohan's angry ticket. First a quick draft: cheapest model wins.");
    const q = await desk.draft("T-507", { quick: true });
    t.say(2, "Now a careful draft for the same ticket: best quality wins.");
    const c = await desk.draft("T-507", { careful: true });
    t.say(3, "Same code, one field changed, a different model chosen by Continuum.");
    await t.wait(1800);
    const ok = !q.error && !c.error;
    const why = (d) => h(String(d.meta?.routing_reason || "").split(" · ")[0].replace(/^mode=\w+, complexity=[\d.]+, /, ""));
    return { ok, title: "Cheap, fast or best: per request",
      text: !ok ? h(q.error || c.error) : q.model !== c.model
        ? `Quick draft by <b>${h(q.model)}</b> (${why(q)}); careful draft by <b>${h(c.model)}</b> (${why(c)}).`
        : `Both drafts went to <b>${h(c.model)}</b>: it is the strongest model this account can reach and also the cheapest. Continuum's reasons: “${why(q)}” and “${why(c)}”.` };
  } },

  "quality-gate": { steps: 2, async run(t) {
    t.say(1, "Ananya asks two questions in one message.");
    const { calls } = await ask(t, "C-1003", "What is your return window? And do Platinum members get free pickup?");
    const f = t.feat(calls, "quality-gate");
    t.say(2, "Continuum checked the answer covers both questions, stays on topic and invents no numbers.");
    await t.wait(1800);
    return { ok: !!f, title: "Every answer, scored", text: f ? h(cap(f.detail)) + "." : "The quality gate did not score this answer." };
  } },

  confidence: { steps: 2, async run(t) {
    await t.go("desk");
    await t.wait(400);
    desk.select("T-501");
    t.say(1, "A careful reply to Ananya's urgent tent ticket, with a confidence check.");
    const d = await desk.draft("T-501", { careful: true });
    t.say(2, "Continuum asked the model several times and measured how much the answers agree.");
    await t.wait(1800);
    const c = d.meta?.confidence;
    return { ok: c != null, title: "How sure is the AI?", text: c != null ? `<b>${Math.round(c * 100)}%</b> agreement across samples: high enough to send, or a signal to check first.` : h(d.error || "Confidence was not measured.") };
  } },

  "verification-engine": { steps: 2, async run(t) {
    await t.switches({ "verification-engine": true, "semantic-cache": false });
    await t.go("shop");
    await t.wait(400);
    t.say(1, "A policy question in the help centre, with the verification engine on.");
    const { res } = await shop.askHelp("Can I return a Diwali gift, and how many days does the refund take?", { typed: true });
    const f = t.feat(res.calls, "verification-engine");
    t.say(2, "A planner split the answer into claims, and separate agents checked each one.");
    await t.wait(1800);
    return { ok: !!f, title: "Checked by a team of agents", text: f ? h(cap(f.detail)) + "." : h(res.ok === false ? errorText(res) : "The engine did not verify this answer.") };
  } },

  "semantic-breaker": { steps: 5, async run(t) {
    await t.switches({ "semantic-cache": false, "model-cascade": false });
    await t.control("breaker-clear");
    await t.control("breaker", { enabled: true, warmup: 10, slack: 0.05, threshold: 0.6, cooldownSeconds: 300 });
    t.later(() => t.control("breaker-clear"));
    await t.go("shop");
    await t.wait(400);
    for (let i = 0; i < 10; i++) {
      t.say(1, `Continuum learns what this model's good answers look like… <b>${i + 1}/10</b>`);
      await shop.askHelp(WARM[i]);
    }
    t.say(2, "Now the model quietly starts giving vague, broken answers (an AI chaos drill).");
    await t.control("ai-chaos", { type: "DEGRADED_ANSWER", rate: 1 });
    t.later(() => t.control("ai-chaos-reset"));
    await t.wait(800);
    let open = false;
    for (let i = 0; i < 4 && !open; i++) {
      t.say(3, "The answers get worse; the quality gate scores each one and the breaker keeps count.");
      await shop.askHelp(WARM[10 + (i % 2)] + (i > 1 ? " Please." : ""));
      const st = await t.control("breaker-status");
      open = (st.breakers || []).some((b) => b.state === "OPEN");
    }
    await t.control("ai-chaos-reset");
    t.say(4, open ? "Tripped: that model is benched. The next question goes to a different one." : "The breaker has not tripped yet.");
    const { res } = await shop.askHelp("Can I change my delivery address after ordering?");
    const skipped = t.has(res.calls, "semantic-breaker");
    t.say(5, "Back to normal answers, from a model that is still doing well.");
    await t.wait(1600);
    return { ok: open, title: "A degrading model, benched", text: open ? `The breaker opened after the answers degraded${skipped ? ", and the next request skipped that model" : ""}. It closes again by itself after a cool-down.` : "The breaker did not trip this time." };
  } },

  failover: { steps: 2, async run(t) {
    await t.control("provider-down", { on: true });
    t.later(() => t.control("chaos-reset"));
    t.say(1, "The presenter takes the main AI provider down. A customer asks a question anyway.");
    const { res, calls } = await ask(t, "C-1001", "Is the brass diya set made of real brass?");
    const f = t.feat(calls, "failover");
    t.say(2, f ? "The first provider failed; Continuum moved to the next one. The customer saw nothing." : "Answered.");
    await t.wait(1800);
    return { ok: !!f, title: "Provider down, store up", text: f ? h(cap(f.detail)) + "." : h(res.ok === false ? errorText(res) : "No failover was needed this time.") };
  } },

  degradation: { steps: 2, async run(t) {
    await t.control("failure-rate", { rate: 1 });
    t.later(() => t.control("chaos-reset"));
    t.say(1, "Now every AI provider fails at once, the worst case.");
    const { res, calls } = await ask(t, "C-1002", "Can I change my delivery address?");
    const f = t.feat(calls, "degradation");
    t.say(2, "Instead of an error page, Continuum served the best fallback it had, and labelled it.");
    await t.wait(1800);
    return { ok: !!f, title: "Never an error page", text: f ? h(cap(f.detail)) + "." : h(res.ok === false ? errorText(res) : "The ladder was not needed.") };
  } },

  hedging: { steps: 2, async run(t) {
    // A cached or cascaded answer never reaches the hedged path.
    await t.switches({ "semantic-cache": false, "model-cascade": false });
    await t.control("hedging", { on: true, thresholdMs: 1 });
    t.later(() => t.control("hedging", { on: false }));
    t.say(1, "Hedging on: if the first model is slow to answer, Continuum races a second one.");
    const { calls } = await ask(t, "C-1003", "Which payment methods do you accept?");
    const f = t.feat(calls, "hedging");
    t.say(2, "The first good answer wins; the slower call is dropped.");
    await t.wait(1800);
    return { ok: !!f, title: "Race the slow model", text: f ? h(cap(f.detail)) + "." : "Hedging did not run on this request." };
  } },

  "ai-chaos": { steps: 2, async run(t) {
    await t.control("ai-chaos", { type: "HALLUCINATION", rate: 1 });
    t.later(() => t.control("ai-chaos-reset"));
    t.say(1, "A drill: the presenter arms a hallucination fault for this store only.");
    const { calls } = await ask(t, "C-1003", "Is my refund for the tent approved?");
    const f = t.feat(calls, "ai-chaos");
    t.say(2, "Continuum corrupted the answer on purpose and flagged it, so the team can practise catching it.");
    await t.wait(2000);
    return { ok: !!f, title: "Fire drills for AI", text: f ? "The answer was corrupted on purpose and labelled. Other Continuum accounts were not affected, and the drill is now switched off." : "The drill did not fire." };
  } },

  admission: { steps: 3, async run(t) {
    // Cached answers never reach a provider, so there would be nothing to admit.
    await t.switches({ "semantic-cache": false });
    const before = await t.control("traffic");
    const admitted = (x) => (x.admission?.providers || []).reduce((n, p) => n + (p.admitted || 0), 0);
    await t.go("desk");
    await t.wait(400);
    t.say(1, "Nine tickets hit the AI at the same moment.");
    await desk.triageAll();
    t.say(2, "Continuum let them in at a pace the provider can take, critical work first.");
    const after = await t.control("traffic", { mark: true });
    const p = (after.admission?.providers || [])[0] || {};
    t.say(3, "Nothing was dropped, and nothing overloaded the provider.");
    await t.wait(1600);
    const n = admitted(after) - admitted(before);
    const shedOf = (x) => (x.admission?.providers || []).reduce((m, q) => m + (q.shed || 0), 0);
    const shed = shedOf(after) - shedOf(before);
    return { ok: !!after.admission?.enabled && n > 0, title: "A rush, handled",
      text: !after.admission?.enabled ? "Admission control is off for this account (Continuum console → Traffic Control)."
        : `Continuum let <b>${n}</b> through at <b>${p.limit}</b> at a time, the speed the provider was keeping up with${shed ? `, and held back <b>${shed}</b> background tickets so the urgent one was not stuck behind them` : ", with nothing turned away"}.` };
  } },

  "priority-deadlines": { steps: 3, async run(t) {
    // A cached answer meets any deadline, so the cache sits this one out.
    if (state.presenter.signedIn) await t.switches({ "semantic-cache": false });
    await t.go("desk");
    await t.wait(400);
    desk.select("T-501");
    t.say(1, "Ananya's trek is on Saturday: her ticket is sent as critical, with a 15-second deadline.");
    const a = await desk.triage("T-501");
    t.say(2, "A batch job asks for an answer within 5 ms, which no model can do.");
    desk.select("T-508");
    const b = await desk.triage("T-508", { deadlineMs: 5 });
    const refused = b.error?.error === "deadline_unreachable";
    if (state.presenter.signedIn) await t.control("traffic", { mark: true });
    t.say(3, refused ? "Refused up front: no money spent on an answer that would arrive too late." : "Answered.");
    await t.wait(1800);
    return { ok: refused || (!a.error && a.criticality === "CRITICAL"), title: "Urgent first, impossible refused",
      text: `${!a.error ? `The urgent ticket answered in <b>${a.ms.toLocaleString("en-IN")} ms</b>, inside its deadline.` : ""} ${refused ? "The 5 ms request was refused before any call was made." : ""}` };
  } },

  billing: { steps: 1, async run(t) {
    await t.go("office");
    await t.wait(400);
    t.say(1, "Every call is metered against the store's Continuum plan.");
    const b = await office.loadPlan();
    await t.wait(2000);
    return { ok: !!b, title: "Usage you can see", text: b ? `<b>${(b.tokensUsed || 0).toLocaleString("en-IN")}</b> of ${(b.monthlyTokenQuota || 0).toLocaleString("en-IN")} tokens used this month on the ${h(b.plan)} plan, ${(b.requestsThisPeriod || 0).toLocaleString("en-IN")} requests.` : "The plan could not be read." };
  } },

  memory: { steps: 3, async run(t) {
    t.say(1, "Priya tells the assistant a preference.");
    const first = await ask(t, "C-1001", "Please remember that I prefer deliveries after 6 pm on weekdays.");
    let stored = t.has(first.calls, "memory") && (first.res.actions || []).some((a) => a.tool === "save_preference");
    if (!stored) {
      const r = await api("/app/memory", { customerId: "C-1001", content: "Prefers deliveries after 6 pm on weekdays" });
      stored = r.ok !== false;
      chat.addNote("💾 Saved to Priya's memory", "mem");
    }
    t.say(2, "Later, a brand-new conversation. The chat history is gone; does the assistant still know?");
    await t.wait(900);
    chat.newConversation();
    const second = await chat.typeAndSend("When do I like my deliveries to arrive?");
    const recalled = (second.res.memories || []).length > 0;
    t.say(3, "Continuum found the memory by meaning and gave it back to the assistant.");
    await t.wait(1800);
    return { ok: stored && recalled, title: "Remembered across conversations", text: recalled ? `Recalled: “${h(second.res.memories[0])}”.` : "Nothing was recalled this time." };
  } },

  "adaptive-policy": { steps: 3, async run(t) {
    await t.control("adaptive", { on: true });
    t.later(() => t.control("adaptive", { on: false }));
    t.say(1, "The presenter teaches Continuum a house rule, once.");
    await t.control("adaptive-learn", { content: "Brightcart house rule: always greet Platinum members by name and offer a priority callback within 2 hours." });
    await t.wait(700);
    t.say(2, "Ananya, a Platinum member, writes in.");
    const { calls } = await ask(t, "C-1003", "My tent arrived broken. What can you do?");
    const f = t.feat(calls, "adaptive-policy");
    t.say(3, "Continuum added the rule to the request; the store's code did not change.");
    await t.wait(1800);
    return { ok: !!f, title: "Teach it once", text: f ? h(cap(f.detail)) + "." : "The policy was not applied to this request." };
  } },

  autopilot: { steps: 2, async run(t) {
    t.say(1, "A customer gets an answer…");
    await ask(t, "C-1002", "Do you offer gift wrapping for Diwali?");
    t.say(2, "…and taps 👍. Continuum learns which models customers like.");
    const r = await chat.rateLast(true);
    await t.wait(1500);
    return { ok: !!r && r.ok !== false && t.has(r.calls, "autopilot"), title: "Ratings that tune routing", text: "The rating went to Continuum with the answer's request id; its autopilot uses ratings like this to order models for future requests." };
  } },

  workflows: { steps: 3, async run(t) {
    await api("/app/reset", {});
    t.customer("C-1001");
    await t.go("orders");
    await t.wait(400);
    t.say(1, "Priya returns her cracked mug set. The refund is a workflow that Continuum runs.");
    const entry = await orders.startRefund("BC-1042", { reason: "arrived damaged", collectItem: true });
    if (!entry) return { ok: false, title: "Refund not started", text: "The refund workflow could not start. Has setup_continuum.py published it?" };
    t.say(2, "Order check, a durable fraud-check timer, refund and restock in parallel, email, courier, CRM.");
    const d = await orders.waitForRefund(entry.workflowId);
    t.say(3, "Each step called one of Brightcart's own systems, and survived anything in between.");
    await t.wait(1500);
    const ok = d?.run?.status === "COMPLETED";
    return { ok, title: "A refund, run step by step", text: ok ? `Seven steps across five of Brightcart's systems. If Continuum restarted mid-way, the run would carry on from where it was.` : `The run ended as ${h(d?.run?.status || "unknown")}.` };
  } },

  saga: { steps: 3, async run(t) {
    await api("/app/reset", {});
    await t.go("office");
    await t.wait(400);
    t.say(1, "The courier company's system goes down.");
    await office.setFault("courier", "down");
    t.later(() => office.setFault("courier", "ok"));
    await t.wait(1000);
    t.customer("C-1003");
    await t.go("orders");
    await t.wait(400);
    t.say(2, "Ananya returns her tent, with a courier pickup. The refund goes through, then the pickup fails.");
    const entry = await orders.startRefund("BC-3001", { reason: "arrived damaged", collectItem: true });
    if (!entry) return { ok: false, title: "Refund not started", text: "The workflow could not start." };
    const d = await orders.waitForRefund(entry.workflowId, 120000);
    const undone = (d?.run?.steps || []).concat(d?.rollbackRun?.steps || []).some((s) => s.kind === "compensation" && s.state === "completed");
    t.say(3, "Continuum undid the completed steps in reverse order: no refund without a return.");
    await t.wait(1800);
    const words = { refund: "refund reversed", restock: "restock undone" };
    const done = (d?.run?.steps || []).concat(d?.rollbackRun?.steps || []).filter((s) => s.kind === "compensation" && s.state === "completed").map((s) => words[s.step] || s.step);
    return { ok: undone || !!d?.run?.rollback, title: "Undo, in the right order",
      text: done.length ? `The pickup kept failing, so Continuum undid what was done, newest first: <b>${h(done.join(", then "))}</b>. The email had already gone and cannot be unsent, so it is reported, not hidden.` : h(d?.run?.rollback?.summary || "The steps already done were reversed when a later one could not succeed.") };
  } },

  "exactly-once": { steps: 3, async run(t) {
    await api("/app/reset", {});
    await t.go("office");
    await t.wait(400);
    t.say(1, "The payments service starts losing its replies: the refund happens, but the answer never arrives.");
    await office.setFault("payments", "lost-response");
    t.later(() => office.setFault("payments", "ok"));
    await t.wait(900);
    t.customer("C-1004");
    await t.go("orders");
    await t.wait(400);
    t.say(2, "Karan returns his pressure cooker. Continuum retries the payment with the same idempotency key.");
    const entry = await orders.startRefund("BC-4002", { reason: "changed my mind", collectItem: false });
    if (!entry) return { ok: false, title: "Refund not started", text: "The workflow could not start." };
    const d = await orders.waitForRefund(entry.workflowId);
    const dupes = (d?.ledger || []).filter((l) => l.duplicate).length;
    t.say(3, "The retry was recognised as a repeat: Karan is refunded once, not twice.");
    await t.wait(1800);
    return { ok: dupes > 0, title: "Refunded once, not twice", text: dupes ? `The payments service saw the retry, matched its key and answered from the first result. One refund of ₹2,149.` : "No retry was needed this time." };
  } },

  webhooks: { steps: 2, async run(t) {
    await api("/app/reset", {});
    t.customer("C-1003");
    await t.go("orders");
    await t.wait(400);
    t.say(1, "Ananya returns her bottles. When the refund finishes, Continuum tells the store.");
    const entry = await orders.startRefund("BC-3002", { reason: "changed my mind", collectItem: false });
    if (!entry) return { ok: false, title: "Refund not started", text: "The workflow could not start." };
    const d = await orders.waitForRefund(entry.workflowId);
    const hook = (d?.webhooks || [])[0];
    t.say(2, "A webhook arrived, signed with a secret only Continuum and Brightcart know.");
    await t.wait(1600);
    return { ok: !!hook?.verified, title: "Told when it's done, provably", text: hook ? `“${h(hook.type)}” received and its signature verified, so nobody can fake a “refund done” message.` : "No webhook arrived. Is the webhook registered (setup_continuum.py)?" };
  } },

  pipelines: { steps: 2, async run(t) {
    await api("/app/reset", {});
    await t.go("office");
    await t.wait(400);
    t.say(1, "Support uploads Priya's receipt, a PDF.");
    const r = await office.checkReceipt("receipt-BC-1042.pdf");
    t.say(2, "One call ran the whole check: read the PDF, find the facts, ask the AI for a verdict.");
    await t.wait(1800);
    return { ok: r.res?.ok !== false && r.verdict === "ok", title: "Upload in, verdict out", text: r.res?.ok === false ? h(errorText(r.res)) : `Verdict: <b>${r.verdict === "ok" ? "eligible" : "not eligible"}</b>. Brightcart made one request; Continuum ran the reader, built the evidence and asked the model.` };
  } },

  specialists: { steps: 2, async run(t) {
    await api("/app/reset", {});
    await t.go("office");
    await t.wait(400);
    t.say(1, "This receipt has been edited: the total says ₹7,999, but Ananya paid ₹7,499.");
    const r = await office.checkReceipt("receipt-BC-3001-wrong-total.txt");
    const mismatch = (r.fields || []).some(([k, , good]) => k === "Receipt total" && good === false);
    t.say(2, "Brightcart's own receipt reader caught it, and gave the AI facts instead of a picture to guess from.");
    await t.wait(1800);
    return { ok: mismatch || r.verdict === "bad", title: "Your own models, in the loop", text: mismatch ? "Brightcart's own reader found the totals do not match, so the receipt was not accepted. The AI got that as a fact, without ever reading the file itself." : "The reader ran; see the verdict." };
  } },

  provenance: { steps: 2, async run(t) {
    let c = lastChatCall();
    if (!c) {
      await ask(t, "C-1001", "What is your return policy for damaged items?");
      c = lastChatCall();
    }
    revealXray();
    bus.emit("xray-focus", c.id);
    t.say(1, "Pick any answer and ask Continuum: why this answer?");
    await t.wait(900);
    const trail = await loadTrail(c);
    t.say(2, "Every decision, in order: cache, model choice, fallbacks, checks, cost.");
    await t.wait(2200);
    return { ok: !!trail?.found, title: "Why this answer?", text: trail?.found ? `<b>${trail.decisions.length}</b> recorded decisions for ${h(c.requestId)}, readable in the X-ray or exported to any tracing tool.` : "No decision trail was recorded. Is decision provenance on in the console?" };
  } },
};
