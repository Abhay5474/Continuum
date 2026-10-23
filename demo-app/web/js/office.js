// The back office (staff): receipt checks, data files for the AI, models,
// the plan, and switches that break the store's own systems on purpose.
import { h, api, state, sleep, inr, errorText, sampleDataUrl, sampleText, fileToDataUrl, animateIn, toast, bus } from "./lib.js";
import { viaButton, bindVia } from "./ui.js";

let root;
let file = null; // {name, text}
const RECEIPTS = [
  ["receipt-BC-1042.pdf", "🧾 BC-1042 · PDF"],
  ["receipt-BC-3001-wrong-total.txt", "🧾 BC-3001 · edited total"],
  ["receipt-BC-2001-old.txt", "🧾 BC-2001 · 51 days old"],
];
const FILES = [
  ["checkout-errors.log", "📜 checkout-errors.log"],
  ["orders-export.csv", "📊 orders-export.csv"],
  ["support-thread.eml", "✉️ support-thread.eml"],
];
const SERVICES = [
  ["payments", "💳", "Payments", { ok: "Working", "lost-response": "Loses replies" }],
  ["courier", "🚚", "Courier pickup", { ok: "Working", flaky: "Flaky", down: "Down" }],
  ["email", "✉️", "Customer email", { ok: "Working", down: "Down" }],
];

export function show(el) {
  root = el;
  el.innerHTML = `
    <div class="section-title" style="margin-top:4px"><h2>Back office</h2><span class="muted small">ops.brightcart.in · staff only</span></div>
    <div class="office">
      <section class="card" id="receipts">
        <div class="card-head"><span class="ico">🧾</span><div><h3>Receipt check</h3><p>Is this receipt good for a refund? One call to a Continuum pipeline.</p></div></div>
        <label class="drop" id="r-drop">Drop a receipt (PDF or text) or pick a sample<input type="file" accept=".pdf,.txt,text/plain,application/pdf"></label>
        <div class="samples">${RECEIPTS.map(([f, l]) => `<button class="sample" data-receipt="${f}">${l}</button>`).join("")}</div>
        <div id="r-out"></div>
      </section>
      <section class="card" id="files">
        <div class="card-head"><span class="ico" style="background:var(--plum-soft)">📄</span><div><h3>Data for the AI</h3><p>Logs, spreadsheets and email threads, rewritten so a model can read them.</p></div></div>
        <div class="samples">${FILES.map(([f, l]) => `<button class="sample" data-file="${f}">${l}</button>`).join("")}</div>
        <div id="f-out"></div>
      </section>
      <section class="card" id="systems">
        <div class="card-head"><span class="ico" style="background:var(--bad-soft)">🧯</span><div><h3>Store systems</h3><p>Break Brightcart's own services to see Continuum's workflows cope.</p></div></div>
        <div class="systems" id="sys"></div>
        <div class="small muted" style="margin-top:12px">Calls Continuum made to these systems</div>
        <div class="ledger" id="ledger"></div>
      </section>
      <section class="card" id="models">
        <div class="card-head"><span class="ico" style="background:var(--info-soft)">📚</span><div><h3>AI models in use</h3><p>What Continuum can route Brightcart's requests to right now.</p></div></div>
        <div id="m-out"><button class="btn sm" id="m-load">Ask Continuum</button></div>
      </section>
      <section class="card" id="plan">
        <div class="card-head"><span class="ico" style="background:var(--ok-soft)">📊</span><div><h3>Plan &amp; usage</h3><p>This month's AI usage against the store's Continuum plan.</p></div></div>
        <div id="p-out"><button class="btn sm" id="p-load">Show usage</button></div>
      </section>
    </div>`;
  el.querySelectorAll("[data-receipt]").forEach((b) => b.addEventListener("click", () => checkReceipt(b.dataset.receipt)));
  el.querySelectorAll("[data-file]").forEach((b) => b.addEventListener("click", () => prepareFile(b.dataset.file)));
  el.querySelector("#m-load").addEventListener("click", () => loadModels());
  el.querySelector("#p-load").addEventListener("click", () => loadPlan());
  const drop = el.querySelector("#r-drop");
  const input = drop.querySelector("input");
  input.addEventListener("change", () => input.files[0] && uploadReceipt(input.files[0]));
  drop.addEventListener("dragover", (e) => {
    e.preventDefault();
    drop.classList.add("over");
  });
  drop.addEventListener("dragleave", () => drop.classList.remove("over"));
  drop.addEventListener("drop", (e) => {
    e.preventDefault();
    drop.classList.remove("over");
    if (e.dataTransfer.files[0]) uploadReceipt(e.dataTransfer.files[0]);
  });
  systems();
}

// ---- receipts -----------------------------------------------------------------

async function uploadReceipt(f) {
  return runReceipt(f.name, await fileToDataUrl(f));
}

export async function checkReceipt(name) {
  root.querySelectorAll("[data-receipt]").forEach((b) => b.classList.toggle("on", b.dataset.receipt === name));
  root.querySelector("#receipts").scrollIntoView({ behavior: "smooth", block: "center" });
  const data = name.endsWith(".pdf") ? await sampleDataUrl(name) : null;
  const text = data ? null : await sampleText(name);
  return runReceipt(name, data, text);
}

const CHAIN = [
  ["📤", "Upload", "the receipt"],
  ["🔬", "Receipt reader", "Brightcart's own"],
  ["🧱", "Evidence", "facts + confidence"],
  ["🤖", "AI model", "never sees the file"],
  ["⚖️", "Verdict", ""],
];

async function runReceipt(name, data, text) {
  const out = root.querySelector("#r-out");
  out.innerHTML = `<div class="chain">${CHAIN.map(([i, l, s], k) => `${k ? `<div class="c-link"></div>` : ""}<div class="c-node"><div class="c-ico">${i}</div><span>${l}</span><small>${s}</small></div>`).join("")}</div>`;
  const nodes = [...out.querySelectorAll(".c-node")];
  const links = [...out.querySelectorAll(".c-link")];
  let step = 0;
  const light = () => {
    if (step < nodes.length - 1) {
      nodes[step].classList.add("on");
      if (links[step - 1]) links[step - 1].classList.add("on");
      step++;
    }
  };
  light();
  const timer = setInterval(light, 650);
  const res = await api("/app/receipt", data ? { filename: name, data } : { filename: name, text });
  clearInterval(timer);
  while (step < nodes.length) {
    nodes[step].classList.add("on");
    if (links[step - 1]) links[step - 1].classList.add("on");
    step++;
    await sleep(160);
  }
  if (res.ok === false) {
    nodes[nodes.length - 1].classList.add("fail");
    out.insertAdjacentHTML("beforeend", `<div class="verdict bad"><span class="v-ico">⚠️</span><div><b>The check did not run</b><span>${h(errorText(res))}</span></div></div>`);
    return { res };
  }
  const r = res.result || {};
  const answer = String(r.response || "");
  const fields = receiptFields(r);
  // The model's verdict when it states one; otherwise what the reader's facts
  // say under the store's rules, labelled as such.
  let v = /not eligible/i.test(answer) ? "bad" : /cannot tell/i.test(answer) ? "warn" : /eligible/i.test(answer) ? "ok" : null;
  let byFacts = false;
  if (!v && fields.some(([, , good]) => good !== undefined)) {
    v = fields.some(([, , good]) => good === false) ? "bad" : "ok";
    byFacts = true;
  }
  v ||= "warn";
  out.insertAdjacentHTML("beforeend", `
    <div class="verdict ${v}"><span class="v-ico">${v === "ok" ? "✅" : v === "bad" ? "❌" : "❔"}</span><div><b>${v === "ok" ? "Eligible for a refund" : v === "bad" ? "Not eligible" : "Needs a person to check"}${byFacts ? " (from the reader's facts)" : ""}</b><span>${h(answer.replace(/\*\*/g, ""))}</span></div></div>
    ${fields.length ? `<dl class="facts">${fields.map(([k, val, good]) => `<dt>${h(k)}</dt><dd>${good === true ? "✅ " : good === false ? "❌ " : ""}${h(val)}</dd>`).join("")}</dl>` : ""}
    <div class="row" style="margin-top:10px">${r.evidenceConfidence != null ? `<span class="pill">evidence ${Math.round(r.evidenceConfidence * 100)}%</span>` : ""}<span class="spacer"></span>${viaButton(res.calls)}</div>`);
  bindVia(out);
  return { res, verdict: v, fields };
}

/** The receipt reader's findings, read from the pipeline's trace (its evidence items). */
function receiptFields(r) {
  const found = {};
  for (const step of r.trace || []) {
    if (step.kind !== "SPECIALIST") continue;
    for (const e of step.detail?.evidence || []) if (e.label) found[e.label] = String(e.text ?? "");
  }
  const out = [];
  if (found.order_id) out.push(["Order", found.order_id + (found.customer ? ` · ${found.customer}` : "")]);
  if (found.order_found) out.push(["Order in our system", found.order_found, found.order_found === "yes"]);
  if (found.receipt_total) out.push(["Receipt total", inr(Number(found.receipt_total), true) + (found.order_total ? ` (we charged ${inr(Number(found.order_total), true)})` : ""), found.totals_match ? found.totals_match === "yes" : undefined]);
  if (found.within_return_window) out.push(["Within 30 days", found.return_window_note || found.within_return_window, found.within_return_window === "yes"]);
  return out;
}

// ---- data files -------------------------------------------------------------------

export async function prepareFile(name) {
  root.querySelectorAll("[data-file]").forEach((b) => b.classList.toggle("on", b.dataset.file === name));
  root.querySelector("#files").scrollIntoView({ behavior: "smooth", block: "center" });
  const text = await sampleText(name);
  file = { name, text };
  const out = root.querySelector("#f-out");
  out.innerHTML = `<div class="row" style="margin-top:12px"><span class="spin" style="color:var(--plum)"></span><span class="small muted">Continuum is reading ${h(name)}…</span></div>`;
  const res = await api("/app/transform", { filename: name, text });
  if (res.ok === false) {
    out.innerHTML = `<div class="verdict bad"><span class="v-ico">⚠️</span><div><b>Could not prepare the file</b><span>${h(errorText(res))}</span></div></div>`;
    return { res };
  }
  const r = res.result || {};
  const s = r.tokenStats || { before: 0, after: 0 };
  const pct = s.before ? Math.round((1 - s.after / s.before) * 100) : 0;
  const struct = r.structure || {};
  const facts = Object.entries(struct).filter(([, v]) => typeof v === "number" && v > 0).slice(0, 4).map(([k, v]) => `<span class="pill">${v.toLocaleString("en-IN")} ${h(k.replace(/([A-Z])/g, " $1").toLowerCase())}</span>`).join("");
  out.innerHTML = `
    <div class="row" style="margin-top:12px"><span class="pill plum">${h(r.contextLabel || "Rewritten")}</span>${facts}</div>
    <div class="tokens">
      <div class="t-row"><span>As pasted</span><div class="meter"><i data-w="100%" style="width:0;background:#cfc7bf"></i></div><span>${s.before.toLocaleString("en-IN")} tokens</span></div>
      <div class="t-row"><span>For the AI</span><div class="meter ok"><i data-w="${Math.min(100, (s.after / Math.max(1, s.before)) * 100)}%" style="width:0"></i></div><span>${s.after.toLocaleString("en-IN")} tokens</span></div>
    </div>
    <div class="small muted" style="margin-top:6px">${pct > 0 ? `${pct}% smaller, and organised: ` : "About the same size, but organised: "}${h(r.transformer === "spreadsheet" ? "columns typed, measures marked, every row kept" : r.transformer === "logs" ? "repeated lines grouped into patterns, errors and a timeline" : "quoted replies and signatures removed, each message with its sender")}.</div>
    <pre class="preview">${h(String(r.rendered || "").slice(0, 2400))}</pre>
    <form class="help-form" id="f-ask"><input class="input" id="f-q" placeholder="Ask a question about this file…" value="${h(defaultQuestion(name))}"><button class="btn plum">Ask</button></form>
    <div id="f-answer"></div>`;
  out.querySelector("#f-ask").addEventListener("submit", (e) => {
    e.preventDefault();
    askFile(out.querySelector("#f-q").value);
  });
  animateIn(out);
  return { res, stats: s };
}

function defaultQuestion(name) {
  if (name.endsWith(".csv")) return "Which product is returned most often, and why?";
  if (name.endsWith(".log")) return "Why did checkout start failing, and when?";
  return "What does the customer want, and what was promised before?";
}

export async function askFile(question) {
  if (!file) return null;
  const box = root.querySelector("#f-answer");
  box.innerHTML = `<div class="answer"><q>${h(question)}</q><div class="typing"><i></i><i></i><i></i></div></div>`;
  const res = await api("/app/ask-file", { filename: file.name, text: file.text, question });
  box.innerHTML = res.ok === false
    ? `<div class="answer"><q>${h(question)}</q><p>${h(errorText(res))}</p></div>`
    : `<div class="answer"><q>${h(question)}</q><p>${h(res.answer)}</p><div class="row"><span class="spacer"></span>${viaButton(res.calls)}</div></div>`;
  bindVia(box);
  return res;
}

// ---- models and plan ---------------------------------------------------------------

export async function loadModels() {
  root.querySelector("#models").scrollIntoView({ behavior: "smooth", block: "center" });
  const out = root.querySelector("#m-out");
  out.innerHTML = `<span class="spin" style="color:var(--info)"></span>`;
  const res = await api("/app/models");
  const list = (res.models?.data || []).map((m) => m.id);
  if (res.ok === false) {
    out.innerHTML = `<span class="pill bad">${h(errorText(res))}</span>`;
    return { res, list };
  }
  out.innerHTML = `<div class="models">${list.map((id, i) => `<span class="model-chip" style="animation-delay:${i * 60}ms"><i class="${/gemini/.test(id) ? "g" : id === "auto" ? "" : "q"}">${id === "auto" ? "✦" : /gemini/.test(id) ? "G" : "Q"}</i>${id === "auto" ? "auto · Continuum picks" : h(id)}</span>`).join("")}</div>
    <div class="small muted" style="margin-top:10px">${list.length - 1} models · Continuum re-checks them every few days and retires ones the providers turn off.</div>`;
  return { res, list };
}

export async function loadPlan() {
  root.querySelector("#plan").scrollIntoView({ behavior: "smooth", block: "center" });
  const out = root.querySelector("#p-out");
  if (!state.presenter.signedIn) {
    out.innerHTML = `<div class="small muted">Usage lives in the Continuum console. Sign in as the presenter to show it here.</div><button class="btn sm" style="margin-top:8px" id="p-sign">Presenter sign-in</button>`;
    out.querySelector("#p-sign").addEventListener("click", () => bus.emit("presenter-open"));
    return null;
  }
  out.innerHTML = `<span class="spin" style="color:var(--ok)"></span>`;
  const res = await api("/app/presenter/do", { action: "billing" });
  if (res.ok === false) {
    out.innerHTML = `<span class="pill bad">${h(errorText(res))}</span>`;
    return null;
  }
  const b = res.result;
  const frac = Math.min(1, b.usageFraction || 0);
  const C = 2 * Math.PI * 48;
  const days = (b.daily || []).slice(-14);
  const max = Math.max(1, ...days.map((d) => d.tokens));
  out.innerHTML = `<div class="plan">
      <svg class="donut" viewBox="0 0 120 120"><defs><linearGradient id="plangrad" x1="0" x2="1"><stop offset="0" stop-color="#22c55e"/><stop offset="1" stop-color="#11875a"/></linearGradient></defs>
        <circle class="d-bg" cx="60" cy="60" r="48"/><circle class="d-fg" cx="60" cy="60" r="48" transform="rotate(-90 60 60)" stroke-dasharray="${C}" stroke-dashoffset="${C}" data-to="${C * (1 - frac)}"/>
        <text x="60" y="58" text-anchor="middle">${Math.round(frac * 100)}%</text><text x="60" y="76" text-anchor="middle" style="font-size:10px;font-weight:600;fill:var(--ink-3)">of quota</text></svg>
      <div><span class="pill ok">${h(b.plan)} plan</span>
        <div style="margin-top:8px"><b>${(b.tokensUsed || 0).toLocaleString("en-IN")}</b> <span class="muted small">of ${(b.monthlyTokenQuota || 0).toLocaleString("en-IN")} tokens</span></div>
        <div class="small muted">${(b.requestsThisPeriod || 0).toLocaleString("en-IN")} requests this month · ${b.overQuota ? "over quota" : "within quota"}</div></div>
    </div>
    <div class="bars" title="Tokens per day">${days.map((d) => `<i style="height:${Math.max(4, (d.tokens / max) * 100)}%" title="${d.day}: ${d.tokens} tokens"></i>`).join("")}</div>`;
  animateIn(out);
  return b;
}

// ---- store systems ---------------------------------------------------------------

async function systems() {
  const res = await api("/app/faults");
  if (!root || res.ok === false) return;
  paintSystems(res.faults || {});
  ledger();
}

function paintSystems(faults) {
  const box = root.querySelector("#sys");
  box.innerHTML = SERVICES.map(([key, icon, name, modes]) => `
    <div class="system"><span class="s-ico">${icon}</span><div><b>${name}</b><small>${faults[key] === "ok" ? "Healthy" : "Broken on purpose"}</small></div>
      <div class="seg">${Object.entries(modes).map(([m, l]) => `<button class="${faults[key] === m ? "on" + (m === "ok" ? "" : m === "flaky" ? " warn" : " bad") : ""}" data-svc="${key}" data-mode="${m}">${l}</button>`).join("")}</div></div>`).join("");
  box.querySelectorAll("[data-svc]").forEach((b) => b.addEventListener("click", () => setFault(b.dataset.svc, b.dataset.mode)));
}

export async function setFault(service, mode) {
  const res = await api("/app/faults", { service, mode });
  if (res.ok === false) {
    toast(errorText(res), true);
    return;
  }
  if (root) paintSystems(res.faults || {});
}

async function ledger() {
  const box = root?.querySelector("#ledger");
  if (!box) return;
  const res = await api("/app/ledger");
  const rows = (res.ledger || []).slice(-12).reverse();
  box.innerHTML = rows.length ? rows.map((l) => `<div class="event ${l.duplicate ? "dupe" : l.status >= 400 ? "bad" : "ok"}"><span class="e-dot"></span><span><b>${h(l.endpoint)}</b> ${h(l.note)}</span></div>`).join("") : `<div class="small muted">None yet. Start a refund from My orders.</div>`;
  if (root && !root.hidden) setTimeout(ledger, 3000);
}
