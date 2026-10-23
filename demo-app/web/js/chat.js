// The shop assistant: a chat widget like any store's, backed by Continuum.
import { h, api, bus, state, sleep, features, errorText, sampleDataUrl, fileToDataUrl } from "./lib.js";
import { viaButton, bindVia } from "./ui.js";

let panel, body, input;
let stream = false;
let photo = null; // {url, name}
const convos = new Map(); // customer id → [{role, content}]
let busy = false;

const QUICK = {
  default: ["Where is my order?", "I want to return something", "How long is delivery to my city?", "Any Diwali offers?"],
};

export function init() {
  panel = document.getElementById("chat");
  document.getElementById("chat-launch").addEventListener("click", () => open());
  bus.on("customer", () => {
    if (!panel.hidden) renderAll();
  });
}

function history() {
  const id = state.customer?.id || "guest";
  if (!convos.has(id)) convos.set(id, []);
  return convos.get(id);
}

export function isOpen() {
  return !panel.hidden && !closeTimer;
}

let closeTimer = null;

export function open() {
  if (closeTimer) {
    // Reopened while closing: stay open.
    clearTimeout(closeTimer);
    closeTimer = null;
    panel.classList.remove("closing");
    return;
  }
  if (!panel.hidden) return;
  panel.hidden = false;
  panel.classList.remove("closing");
  document.getElementById("chat-launch").hidden = true;
  const first = state.customer?.name.split(" ")[0] || "there";
  panel.innerHTML = `
    <header class="chat-head">
      <span class="bot-face">✦</span>
      <div style="flex:1;min-width:0"><b>Brightcart Assistant</b><small>AI · usually replies in seconds</small></div>
      <button class="btn ghost sm" id="chat-more" aria-label="Chat options">⋯</button>
      <button class="btn ghost sm" id="chat-close" aria-label="Close chat">✕</button>
    </header>
    <div class="chat-body" id="chat-body"></div>
    <div class="chat-quick" id="chat-quick"></div>
    <div class="attach-preview" id="attach-preview" hidden></div>
    <form class="chat-input" id="chat-form">
      <button type="button" class="icon-btn" id="chat-attach" aria-label="Attach a photo">📎</button>
      <textarea id="chat-text" rows="1" placeholder="Message Brightcart…" aria-label="Message"></textarea>
      <button class="icon-btn send" aria-label="Send">➤</button>
    </form>`;
  body = panel.querySelector("#chat-body");
  input = panel.querySelector("#chat-text");
  panel.querySelector("#chat-close").addEventListener("click", close);
  panel.querySelector("#chat-more").addEventListener("click", menu);
  panel.querySelector("#chat-attach").addEventListener("click", attachMenu);
  panel.querySelector("#chat-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const t = input.value.trim();
    if (t || photo) send(t);
  });
  input.addEventListener("keydown", (e) => {
    if (e.key === "Enter" && !e.shiftKey) {
      e.preventDefault();
      panel.querySelector("#chat-form").requestSubmit();
    }
  });
  input.addEventListener("input", grow);
  renderAll(first);
  setTimeout(() => input.focus(), 300);
}

export function close() {
  if (panel.hidden || closeTimer) return;
  panel.classList.add("closing");
  closeTimer = setTimeout(() => {
    closeTimer = null;
    panel.hidden = true;
    panel.classList.remove("closing");
    document.getElementById("chat-launch").hidden = false;
  }, 260);
}

function grow() {
  input.style.height = "auto";
  input.style.height = Math.min(110, input.scrollHeight) + "px";
}

function renderAll() {
  if (!body) return;
  const c = state.customer;
  body.innerHTML = `<div class="chat-day">Today</div>`;
  bot(`Namaste ${c ? c.name.split(" ")[0] : ""}! 👋 I'm Brightcart's assistant. I can track orders, start returns and answer questions about delivery and payments.`, { greet: true });
  for (const m of history()) {
    if (m.role === "user") userBubble(m.content);
    else bot(m.content, { restore: true });
  }
  quick();
}

function quick() {
  const q = panel.querySelector("#chat-quick");
  q.innerHTML = QUICK.default.map((t) => `<button type="button">${h(t)}</button>`).join("");
  q.querySelectorAll("button").forEach((b) => b.addEventListener("click", () => send(b.textContent)));
}

function scroll() {
  body.scrollTop = body.scrollHeight;
}

const CARD = /\b(?:\d[ -]?){12,19}\b/g;
const SECRET = /\b(CVV|PIN|OTP|password)\b[^\d]{0,12}(\d{3,6})/gi;

function userBubble(text, { image } = {}) {
  const el = document.createElement("div");
  el.className = "msg user";
  el.innerHTML = (image ? `<img src="${image}" alt="Attached photo">` : "") + h(text);
  body.appendChild(el);
  scroll();
  return el;
}

/** Marks what the prompt firewall removed, in the customer's own bubble. */
function maskSecrets(el, text) {
  let html = h(text).replace(CARD, (m) => `<span class="masked">${m}</span>`);
  html = html.replace(SECRET, (m, word, digits) => m.replace(digits, `<span class="masked">${digits}</span>`));
  const img = el.querySelector("img");
  el.innerHTML = (img ? img.outerHTML : "") + html;
}

function bot(text, { greet, restore, broken, cached } = {}) {
  const el = document.createElement("div");
  el.className = "msg bot" + (broken ? " broken" : "") + (cached ? " cached" : "");
  el.textContent = text;
  body.appendChild(el);
  if (!restore && !greet) scroll();
  return el;
}

function typing() {
  const el = document.createElement("div");
  el.className = "typing";
  el.innerHTML = "<i></i><i></i><i></i>";
  body.appendChild(el);
  scroll();
  return el;
}

function chip(text, cls = "") {
  const el = document.createElement("div");
  el.className = "toolchip " + cls;
  el.innerHTML = text;
  body.appendChild(el);
  scroll();
  return el;
}

export function prefill(text) {
  if (!input) return;
  input.value = text;
  grow();
  input.focus();
}

/** Types a message into the box like a person, then sends it. Used by the tour. */
export async function typeAndSend(text, opts = {}) {
  open();
  await sleep(350);
  input.value = "";
  const step = Math.max(8, Math.min(26, 1400 / Math.max(1, text.length)));
  for (const ch of text) {
    input.value += ch;
    grow();
    await sleep(step);
  }
  await sleep(220);
  return send(text, opts);
}

export function newConversation() {
  convos.set(state.customer?.id || "guest", []);
  if (body) renderAll();
}

export function setStream(on) {
  stream = !!on;
}

export async function usePhoto(name = "damaged-mug.png") {
  photo = { url: await sampleDataUrl(name), name };
  showPhoto();
}

function showPhoto() {
  const p = panel.querySelector("#attach-preview");
  if (!photo) {
    p.hidden = true;
    return;
  }
  p.hidden = false;
  p.innerHTML = `<img src="${photo.url}" alt=""><span>${h(photo.name)}</span><span class="spacer"></span><button class="btn xs ghost" type="button">Remove</button>`;
  p.querySelector("button").addEventListener("click", () => {
    photo = null;
    showPhoto();
  });
}

/** Sends one message; returns {res, calls}. */
export async function send(text, { mode } = {}) {
  if (busy) return { res: { ok: false, error: { message: "busy" } }, calls: [] };
  busy = true;
  try {
    input.value = "";
    grow();
    if (photo) return await sendPhoto(text);
    const uel = userBubble(text);
    const hist = history();
    hist.push({ role: "user", content: text });
    if (stream) return await sendStream(uel, text, hist, mode);
    const t = typing();
    const res = await api("/app/chat", { customerId: state.customer?.id, messages: hist, mode });
    t.remove();
    return render(uel, text, res, hist);
  } finally {
    busy = false;
  }
}

function render(uel, text, res, hist) {
  const f = features(res.calls);
  if (f["prompt-firewall"]) {
    maskSecrets(uel, text);
    chip("🛡️ Card number and PIN removed before any AI saw them", "stop");
  }
  if (res.ok === false) {
    const blocked = res.error?.error === "request_blocked" || res.error?.status === 403;
    bot(blocked ? "Sorry, I can't help with that message. (Blocked for safety before it reached our AI.)" : errorText(res), { broken: true });
    if (!blocked) hist.pop();
    return { res, calls: res.calls || [] };
  }
  for (const m of (res.memories || []).slice(0, 1)) chip(`🧠 Remembered from before: “${h(m)}”`, "mem");
  for (const a of res.actions || []) actionChip(a);
  const applied = (res.meta?.applied || []).join(" ");
  const broken = /ai-chaos/.test(applied) || /\[(HALLUCINATED|DEGRADED)\]/.test(res.reply || "");
  const el = bot(res.reply, { broken, cached: !!f["semantic-cache"] });
  if (res.loop) chip("🛑 Continuum stopped the assistant: it was repeating itself", "stop");
  hist.push({ role: "assistant", content: res.reply });
  foot(el, res.calls, res.meta?.request_id);
  return { res, calls: res.calls || [], el };
}

function actionChip(a) {
  const r = a.result || {};
  if (a.tool === "lookup_order") {
    chip(r.error ? `🔎 Order lookup: ${h(r.error)}` : `🔎 Looked up order <b>${h(r.id)}</b> · ${h(r.item)} · ${h(r.status)}`);
  } else if (a.tool === "list_my_orders") {
    chip(`📦 Checked your ${(r.orders || []).length} orders`);
  } else if (a.tool === "save_preference") {
    chip(r.error ? `💾 Could not remember that: ${h(r.error)}` : `💾 Remembered for next time: “${h(a.args?.preference || "")}”`, "mem");
  } else if (a.tool === "start_refund") {
    if (r.error) {
      chip(`↩️ Refund not started: ${h(r.error)}`, "stop");
      return;
    }
    const card = document.createElement("div");
    card.className = "inline-card";
    card.innerHTML = `<b>↩️ Refund started for ${h(a.args?.order_id || "")}</b><div class="small muted">Continuum is running the refund step by step.</div>
      <div class="row"><button class="btn sm primary">Track refund</button></div>`;
    card.querySelector("button").addEventListener("click", () => state.go("orders"));
    body.appendChild(card);
    scroll();
  }
}

function foot(el, calls, requestId) {
  const f = document.createElement("div");
  f.className = "msg-foot";
  f.dataset.rid = requestId || "";
  f.innerHTML = `${viaButton(calls)}${requestId ? `<button class="rate" data-s="1" aria-label="Helpful">👍</button><button class="rate" data-s="0" aria-label="Not helpful">👎</button>` : ""}`;
  el.after(f);
  bindVia(f);
  f.querySelectorAll(".rate").forEach((b) => b.addEventListener("click", () => rate(b, requestId)));
  scroll();
}

async function rate(btn, requestId) {
  btn.parentElement.querySelectorAll(".rate").forEach((x) => x.classList.toggle("on", x === btn));
  const res = await api("/app/feedback", { requestId, score: Number(btn.dataset.s), comment: btn.dataset.s === "1" ? "helpful" : "not helpful" });
  return res;
}

/** Rates the newest answer, as a customer tapping 👍 would. */
export async function rateLast(up = true) {
  const feet = body?.querySelectorAll('.msg-foot[data-rid]:not([data-rid=""])');
  const last = feet?.[feet.length - 1];
  if (!last) return null;
  const btn = last.querySelector(`.rate[data-s="${up ? 1 : 0}"]`);
  btn.scrollIntoView({ block: "nearest" });
  await sleep(300);
  return rate(btn, last.dataset.rid);
}

async function sendStream(uel, text, hist, mode) {
  const el = bot("", {});
  el.classList.add("caret");
  let reply = "";
  let done = null;
  try {
    const res = await fetch("/app/chat/stream", {
      method: "POST", headers: { "Content-Type": "application/json" },
      body: JSON.stringify({ customerId: state.customer?.id, messages: hist, mode }),
    });
    const reader = res.body.getReader();
    const dec = new TextDecoder();
    let buf = "";
    for (;;) {
      const { value, done: end } = await reader.read();
      if (end) break;
      buf += dec.decode(value, { stream: true });
      let i;
      while ((i = buf.indexOf("\n\n")) >= 0) {
        const line = buf.slice(0, i).trim();
        buf = buf.slice(i + 2);
        if (!line.startsWith("data:")) continue;
        const msg = JSON.parse(line.slice(5));
        if (msg.delta) {
          reply += msg.delta;
          el.textContent = reply;
          scroll();
        }
        if (msg.done) done = msg;
      }
    }
  } catch (e) {
    done = { error: { message: "The stream was interrupted." } };
  }
  el.classList.remove("caret");
  if (done?.calls?.length) bus.emit("calls", done.calls);
  if (done?.error) {
    el.textContent = done.error.message || "Something went wrong.";
    el.classList.add("broken");
    hist.pop();
    return { res: { ok: false, error: done.error, calls: done.calls }, calls: done?.calls || [] };
  }
  hist.push({ role: "assistant", content: reply });
  foot(el, done?.calls, done?.meta?.request_id);
  return { res: { ok: true, reply, meta: done?.meta, firstTokenMs: done?.firstTokenMs, calls: done?.calls }, calls: done?.calls || [], el };
}

async function sendPhoto(text) {
  const p = photo;
  photo = null;
  showPhoto();
  const question = text || "Is this item damaged? Can I get a refund?";
  userBubble(question, { image: p.url });
  const t = typing();
  const res = await api("/app/photo", { image: p.url, question, customerId: state.customer?.id });
  t.remove();
  if (res.ok === false) {
    bot(errorText(res), { broken: true });
    return { res, calls: res.calls || [] };
  }
  chip("📷 The AI looked at your photo");
  const el = bot(res.answer);
  foot(el, res.calls, res.meta?.request_id);
  return { res, calls: res.calls || [], el };
}

/** A question at the end of a long conversation (62 earlier messages). */
export async function longChat(question) {
  open();
  newConversation();
  const fold = document.createElement("div");
  fold.className = "history-fold";
  fold.innerHTML = `🕘 <b>62 earlier messages</b> · ${h(state.customer?.name.split(" ")[0] || "")} has been planning Diwali shopping for an hour`;
  body.appendChild(fold);
  scroll();
  await sleep(500);
  const q = question || "Going back to the very start of our chat: which of my orders was stuck, and what did you say you would do about it?";
  const step = Math.max(8, Math.min(22, 1400 / q.length));
  input.value = "";
  for (const ch of q) {
    input.value += ch;
    grow();
    await sleep(step);
  }
  await sleep(200);
  input.value = "";
  grow();
  userBubble(q);
  const t = typing();
  const res = await api("/app/long-chat", { customerId: state.customer?.id, question: q });
  t.remove();
  if (res.ok === false) {
    bot(errorText(res), { broken: true });
    return { res, calls: res.calls || [] };
  }
  fold.innerHTML = `🕘 <b>${res.transcript.length} earlier messages</b> · ${Math.round(res.chars / 4).toLocaleString("en-IN")} tokens of history`;
  const el = bot(res.reply);
  foot(el, res.calls, res.meta?.request_id);
  return { res, calls: res.calls || [], el };
}

export function addNote(html, cls = "") {
  open();
  return chip(html, cls);
}

function menu(e) {
  e.stopPropagation();
  const old = panel.querySelector(".chat-menu");
  if (old) {
    old.remove();
    return;
  }
  const m = document.createElement("div");
  m.className = "chat-menu";
  m.innerHTML = `<label><input type="checkbox" ${stream ? "checked" : ""}> Type replies as they arrive (streaming)</label>
    <button type="button" data-a="new">🗒️ New conversation</button>
    <button type="button" data-a="long">🕘 Continue a 62-message chat</button>`;
  panel.appendChild(m);
  m.querySelector("input").addEventListener("change", (ev) => (stream = ev.target.checked));
  m.querySelector('[data-a="new"]').addEventListener("click", () => {
    newConversation();
    m.remove();
  });
  m.querySelector('[data-a="long"]').addEventListener("click", () => {
    m.remove();
    longChat();
  });
  setTimeout(() => document.addEventListener("click", function off(ev) {
    if (!m.contains(ev.target)) {
      m.remove();
      document.removeEventListener("click", off);
    }
  }), 0);
}

function attachMenu(e) {
  e.stopPropagation();
  const old = panel.querySelector(".chat-menu");
  if (old) old.remove();
  const m = document.createElement("div");
  m.className = "chat-menu";
  m.style.top = "auto";
  m.style.bottom = "64px";
  m.style.left = "12px";
  m.style.right = "auto";
  m.innerHTML = `<button type="button" data-a="sample">☕ Photo of the cracked mug (sample)</button>
    <button type="button" data-a="upload">📁 Upload a photo…</button><input type="file" accept="image/*" hidden>`;
  panel.appendChild(m);
  m.querySelector('[data-a="sample"]').addEventListener("click", async () => {
    m.remove();
    await usePhoto();
  });
  const file = m.querySelector("input");
  m.querySelector('[data-a="upload"]').addEventListener("click", () => file.click());
  file.addEventListener("change", async () => {
    if (file.files[0]) {
      photo = { url: await fileToDataUrl(file.files[0]), name: file.files[0].name };
      showPhoto();
    }
    m.remove();
  });
  setTimeout(() => document.addEventListener("click", function off(ev) {
    if (!m.contains(ev.target)) {
      m.remove();
      document.removeEventListener("click", off);
    }
  }), 0);
}
