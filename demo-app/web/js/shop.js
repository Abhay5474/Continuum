// The storefront: festive banner, products in ₹, and the public help centre.
import { h, api, inr, state, features, sleep, errorText } from "./lib.js";
import { viaButton, bindVia } from "./ui.js";
import * as chat from "./chat.js";

let root;
let category = "All";
let cart = 0;
const SUGGEST = ["How long does delivery to Pune take?", "Is cash on delivery available?", "Can I return a gift?", "Do you deliver on Sundays?"];

export function show(el) {
  root = el;
  const cats = ["All", ...new Set(state.products.map((p) => p.category))];
  el.innerHTML = `
    <section class="hero">
      <span class="kicker">Diwali Dhamaka · till 2 Nov</span>
      <h1>Up to 40% off on everything you need for the festive season</h1>
      <p>Free delivery across India above ₹499 · Cash on delivery · Easy 30-day returns</p>
      <div class="row"><button class="btn" id="hero-shop">Shop the sale</button>
        <button class="btn ghost" style="color:#fff" id="hero-help">✦ Ask our assistant</button></div>
      <div class="hero-art" aria-hidden="true"><span>🪔</span><span>🎁</span><span>✨</span></div>
    </section>
    <div class="cats" role="tablist">${cats.map((c) => `<button class="${c === category ? "on" : ""}" data-cat="${h(c)}">${h(c)}</button>`).join("")}</div>
    <div class="section-title"><h2>Festive bestsellers</h2><span class="muted small">Prices include GST</span></div>
    <div class="grid" id="grid"></div>
    <section class="help" id="help">
      <div class="help-head"><span class="ico">💬</span><div><h3>Help centre</h3><p>Answers from our store policy, written by AI. Popular questions are answered instantly.</p></div></div>
      <form class="help-form" id="help-form"><input class="input" id="help-q" placeholder="Ask about delivery, returns, payments…" autocomplete="off"><button class="btn primary" id="help-go">Ask</button></form>
      <div class="help-suggest">${SUGGEST.map((s) => `<button type="button">${h(s)}</button>`).join("")}</div>
      <div class="answers" id="answers"></div>
    </section>`;
  el.querySelectorAll(".cats button").forEach((b) => b.addEventListener("click", () => {
    category = b.dataset.cat;
    el.querySelectorAll(".cats button").forEach((x) => x.classList.toggle("on", x === b));
    grid();
  }));
  el.querySelector("#hero-shop").addEventListener("click", () => el.querySelector("#grid").scrollIntoView({ behavior: "smooth" }));
  el.querySelector("#hero-help").addEventListener("click", () => chat.open());
  el.querySelector("#help-form").addEventListener("submit", (e) => {
    e.preventDefault();
    const q = el.querySelector("#help-q").value.trim();
    if (q) askHelp(q);
  });
  el.querySelectorAll(".help-suggest button").forEach((b) => b.addEventListener("click", () => askHelp(b.textContent)));
  grid();
}

function grid() {
  const g = root.querySelector("#grid");
  const items = state.products.filter((p) => category === "All" || p.category === category);
  g.innerHTML = items.map((p, i) => {
    const off = Math.round((1 - p.price / p.mrp) * 100);
    return `<article class="product" style="--tint:${p.tint};animation-delay:${i * 40}ms">
      <div class="pimg"><span class="emo">${p.emoji}</span><span class="pill ok off">${off}% off</span></div>
      <div class="pbody">
        <div class="pname">${h(p.name)}</div>
        <div class="rating"><b>${p.rating} ★</b>(${p.reviews.toLocaleString("en-IN")})</div>
        <div class="price"><b>${inr(p.price)}</b><s>${inr(p.mrp)}</s><span class="pct">${off}% off</span></div>
        <div class="eta">${p.stock > 0 ? (p.stock < 5 ? `Only ${p.stock} left · ` : "") + "Free delivery by " + day(2) : "Out of stock"}</div>
        <div class="pactions"><button class="btn sm primary" data-add="${p.sku}">Add to cart</button><button class="btn sm ask" data-ask="${p.sku}" title="Ask the assistant">✦ Ask</button></div>
      </div></article>`;
  }).join("");
  g.querySelectorAll("[data-add]").forEach((b) => b.addEventListener("click", () => {
    cart++;
    const n = document.getElementById("cart-n");
    n.textContent = cart;
    n.classList.remove("bump");
    void n.offsetWidth;
    n.classList.add("bump");
    b.textContent = "Added ✓";
    setTimeout(() => (b.textContent = "Add to cart"), 1400);
  }));
  g.querySelectorAll("[data-ask]").forEach((b) => b.addEventListener("click", () => {
    const p = state.products.find((x) => x.sku === b.dataset.ask);
    chat.open();
    chat.prefill(`Is the ${p.name} a good gift for Diwali? What do buyers say?`);
  }));
}

function day(n) {
  const d = new Date(Date.now() + n * 864e5);
  return d.toLocaleDateString("en-IN", { weekday: "short", day: "numeric", month: "short" });
}

/** Asks the help centre; returns {res, cached, ms}. Used by the tour too. */
export async function askHelp(question, { typed = false } = {}) {
  const input = root?.querySelector("#help-q");
  if (input && typed) {
    input.value = "";
    root.querySelector("#help").scrollIntoView({ behavior: "smooth", block: "center" });
    for (const ch of question) {
      input.value += ch;
      await sleep(18);
    }
    await sleep(250);
  } else if (input) input.value = question;
  const box = root?.querySelector("#answers");
  const card = document.createElement("div");
  card.className = "answer";
  card.innerHTML = `<q>${h(question)}</q><div class="typing"><i></i><i></i><i></i></div>`;
  box?.prepend(card);
  const started = performance.now();
  const res = await api("/app/help", { question });
  const ms = Math.round(performance.now() - started);
  const f = features(res.calls);
  const cached = !!f["semantic-cache"];
  if (res.ok === false) {
    card.innerHTML = `<q>${h(question)}</q><p>${h(errorText(res))}</p>`;
    return { res, cached, ms };
  }
  const width = Math.min(100, Math.max(3, ms / 30));
  card.innerHTML = `<q>${h(question)}</q><p>${h(res.answer)}</p>
    <div class="row">
      ${cached ? `<span class="pill ok pop">⚡ Instant answer from cache · no AI cost</span>` : `<span class="pill info pop">🤖 Written by AI · ${h(res.model || "")}</span>`}
      <span class="speed ${cached ? "fast" : ""}"><i style="width:0"></i></span><span class="small muted">${ms.toLocaleString("en-IN")} ms</span>
      <span class="spacer"></span>${viaButton(res.calls)}
    </div>`;
  requestAnimationFrame(() => requestAnimationFrame(() => (card.querySelector(".speed i").style.width = width + "%")));
  bindVia(card);
  if (input) input.value = "";
  return { res, cached, ms };
}
