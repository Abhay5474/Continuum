// My orders: order history, returns, and a live tracker for each refund.
// A refund is a durable workflow in Continuum that calls the store's own
// services (verify, refund, restock, email, courier, CRM).
import { h, api, bus, state, inr, sleep, errorText, toast } from "./lib.js";

let root;
const STEPS = [
  ["verify", "Check order", "🔎"],
  ["fraud-hold", "Fraud check", "⏳"],
  ["refund", "Refund", "💸"],
  ["restock", "Restock", "📦"],
  ["notify", "Email customer", "✉️"],
  ["pickup", "Courier pickup", "🚚"],
  ["crm", "Note in CRM", "🗂️"],
];
const runs = new Map(); // workflowId → latest data
const polling = new Set();

bus.on("customer", () => root && !root.hidden && show(root));

export async function show(el) {
  root = el;
  const c = state.customer;
  el.innerHTML = `
    <div class="section-title" style="margin-top:4px"><h2>My orders</h2><span class="muted small">${h(c.name)} · ${h(c.tier)} member since ${h(c.since)}</span></div>
    <div class="orders" id="order-list"><div class="empty">Loading your orders…</div></div>
    <div class="section-title"><h2>Refunds</h2><span class="muted small">Each refund runs as a workflow in Continuum, calling Brightcart's own systems.</span></div>
    <div id="refund-list"></div>`;
  const [o, r] = await Promise.all([api(`/app/orders?customer=${c.id}`), api("/app/refunds")]);
  if (root !== el) return;
  renderOrders(o.orders || []);
  const mine = (r.refunds || []).filter((x) => (o.orders || []).some((y) => y.id === x.orderId));
  const list = el.querySelector("#refund-list");
  list.innerHTML = mine.length ? "" : `<div class="card empty">No refunds yet. Use “Return &amp; refund” on a delivered order.</div>`;
  for (const ref of mine) {
    list.appendChild(trackerShell(ref));
    follow(ref.workflowId);
  }
}

function statusPill(o) {
  if (o.refund?.status === "refunded") return `<span class="pill ok">Refunded</span>`;
  if (o.refund?.status === "reversed") return `<span class="pill plum">Refund reversed</span>`;
  if (o.status === "delivered") return `<span class="pill ok">Delivered ${fmt(o.delivered_on)}</span>`;
  if (o.status === "shipped") return `<span class="pill info">Arriving ${fmt(o.eta)}</span>`;
  return `<span class="pill warn">Processing</span>`;
}

function fmt(d) {
  return d ? new Date(d).toLocaleDateString("en-IN", { day: "numeric", month: "short" }) : "";
}

function renderOrders(orders) {
  const box = root.querySelector("#order-list");
  box.innerHTML = orders.map((o, i) => `
    <div class="card order" data-order="${o.id}" style="animation-delay:${i * 60}ms">
      <div class="thumb" style="--tint:${o.tint}">${o.emoji}</div>
      <div><h4>${h(o.item)}</h4>
        <div class="meta">Order ${h(o.id)} · ${h(o.amount_text)} · ${h(o.card)}</div>
        <div class="row" style="margin-top:6px">${statusPill(o)}${!o.returnable && o.status === "delivered" && !o.refund ? `<span class="small muted">${h(o.returnable_reason)}</span>` : ""}</div>
      </div>
      <div class="acts">
        ${o.returnable ? `<button class="btn sm primary" data-return="${o.id}">Return &amp; refund</button>` : ""}
        <button class="btn sm ghost" data-help="${o.id}">✦ Get help</button>
      </div>
      <div class="return-form" id="rf-${o.id}" hidden style="grid-column:1/-1"></div>
    </div>`).join("") || `<div class="card empty">No orders yet.</div>`;
  box.querySelectorAll("[data-return]").forEach((b) => b.addEventListener("click", () => returnForm(b.dataset.return)));
  box.querySelectorAll("[data-help]").forEach((b) => b.addEventListener("click", async () => {
    const chat = await import("./chat.js");
    chat.open();
    chat.prefill(`I need help with order ${b.dataset.help}.`);
  }));
}

function returnForm(orderId, preset) {
  const box = root.querySelector("#rf-" + orderId);
  const premium = ["Gold", "Platinum"].includes(state.customer.tier);
  box.hidden = false;
  box.innerHTML = `<div class="card" style="margin-top:4px;background:var(--paper-2)">
    <b>Why are you returning it?</b>
    <div class="row" style="margin-top:8px">${["Arrived damaged", "Wrong size or colour", "Changed my mind"].map((r, i) => `<button class="sample ${i === 0 ? "on" : ""}" data-r="${h(r)}">${h(r)}</button>`).join("")}</div>
    <label class="row small" style="margin-top:10px"><input type="checkbox" id="pickup-${orderId}" ${premium ? "checked" : ""}> ${premium ? "Free pickup from my door (" + state.customer.tier + " benefit)" : "Book a courier pickup"}</label>
    <div class="row" style="margin-top:12px"><button class="btn primary" data-go>Start refund</button><button class="btn ghost" data-x>Cancel</button></div></div>`;
  let reason = "Arrived damaged";
  box.querySelectorAll("[data-r]").forEach((b) => b.addEventListener("click", () => {
    reason = b.dataset.r;
    box.querySelectorAll("[data-r]").forEach((x) => x.classList.toggle("on", x === b));
  }));
  box.querySelector("[data-x]").addEventListener("click", () => (box.hidden = true));
  box.querySelector("[data-go]").addEventListener("click", async (e) => {
    e.target.disabled = true;
    e.target.innerHTML = `<span class="spin"></span> Starting…`;
    await startRefund(orderId, { reason: reason.toLowerCase(), collectItem: box.querySelector(`#pickup-${orderId}`).checked });
  });
  if (preset) box.scrollIntoView({ behavior: "smooth", block: "center" });
}

/** Starts a refund and shows its tracker. Returns the store's entry (with workflowId). */
export async function startRefund(orderId, { reason = "arrived damaged", collectItem = false } = {}) {
  const res = await api("/app/refunds", { orderId, reason, collectItem });
  if (res.ok === false) {
    toast(errorText(res), true);
    return null;
  }
  if (root) {
    const form = root.querySelector("#rf-" + orderId);
    if (form) form.hidden = true;
    const list = root.querySelector("#refund-list");
    if (list) {
      list.querySelector(".empty")?.remove();
      const shell = trackerShell(res.refund);
      list.prepend(shell);
      shell.scrollIntoView({ behavior: "smooth", block: "center" });
    }
  }
  follow(res.refund.workflowId);
  return res.refund;
}

function trackerShell(ref) {
  const el = document.createElement("div");
  el.className = "card tracker";
  el.id = "tr-" + ref.workflowId;
  el.innerHTML = `<div class="tracker-head"><h4>${h(ref.item)} · ${inr(ref.amount)}</h4><span class="pill brand" data-status>Starting…</span>
    <span class="spacer"></span><span class="small muted mono">${h(ref.workflowId)}</span></div>
    <div class="steps" data-steps>${STEPS.map(([id, label, icon]) => `<div class="step" data-step="${id}"><div class="node">${icon}</div><label>${label}</label><small></small></div>`).join("")}</div>
    <div data-banner></div><div class="events" data-events></div>`;
  return el;
}

async function follow(wfId) {
  if (polling.has(wfId)) return;
  polling.add(wfId);
  try {
    for (let i = 0; i < 300; i++) {
      const d = await api(`/app/refunds/${encodeURIComponent(wfId)}`);
      if (d.ok !== false) {
        runs.set(wfId, d);
        paint(wfId, d);
        bus.emit("refund", { wfId, data: d });
        const running = d.run?.status === "RUNNING" || d.rollbackRun?.status === "RUNNING";
        // A webhook arrives a moment after the run ends; wait for it briefly.
        const settling = !running && !(d.webhooks || []).length && i < 40;
        if (!running && !settling) break;
      }
      await sleep(1200);
    }
  } finally {
    polling.delete(wfId);
  }
}

function paint(wfId, d) {
  const el = document.getElementById("tr-" + wfId);
  if (!el) return;
  const run = d.run || {};
  const byStep = new Map();
  const undo = [];
  for (const s of run.steps || []) {
    if (s.kind === "compensation") undo.push(s);
    else byStep.set(s.step, s);
  }
  for (const s of d.rollbackRun?.steps || []) if (s.kind === "compensation") undo.push(s);
  const finished = run.status && run.status !== "RUNNING";
  for (const [id] of STEPS) {
    const node = el.querySelector(`[data-step="${id}"]`);
    const s = byStep.get(id);
    let cls = "";
    let note = "";
    const st = s?.state;
    if (st === "completed") cls = "done";
    else if (st === "running") cls = s.attempts > 1 ? "retry" : "run";
    else if (st === "failed") cls = "fail";
    else if (st === "skipped" || (finished && id === "pickup" && !d.refund?.collectItem)) cls = "skip";
    else if (st === "cancelled" || st === "abandoned" || (finished && !st)) cls = "cancel";
    if (s?.attempts > 1) note = `${s.attempts} tries`;
    if (id === "fraud-hold" && st === "running") note = "durable timer";
    if (st === "failed" && s.httpStatus) note = `HTTP ${s.httpStatus}`;
    if (cls === "skip") note = "not needed";
    const undone = undo.some((u) => u.step === id && u.state === "completed");
    if (undone) {
      cls = "undo";
      note = "undone";
    }
    node.className = "step " + cls;
    node.querySelector("small").textContent = note;
    node.querySelector(".node").textContent = cls === "done" ? "✓" : cls === "fail" ? "✕" : cls === "undo" ? "↩" : STEPS.find((x) => x[0] === id)[2];
    let badge = node.querySelector(".badge");
    if (s?.attempts > 1) {
      if (!badge) {
        badge = document.createElement("span");
        badge.className = "badge";
        node.appendChild(badge);
      }
      badge.textContent = "×" + s.attempts;
    }
  }
  const pill = el.querySelector("[data-status]");
  const tone = { COMPLETED: "ok", FAILED: "bad", CANCELLED: "warn", STUCK: "bad", RUNNING: "brand" }[run.status] || "brand";
  pill.className = "pill " + tone;
  pill.textContent = { COMPLETED: "Refunded", FAILED: "Failed", CANCELLED: "Cancelled", STUCK: "Stuck", RUNNING: "In progress" }[run.status] || "Starting…";

  const banner = el.querySelector("[data-banner]");
  const rb = run.rollback;
  let html = "";
  if (run.status === "COMPLETED") {
    html = `<div class="verdict ok"><span class="v-ico">✓</span><div><b>${inr(d.refund.amount)} refunded to ${h(d.order?.card || "the original payment method")}</b><span>Item restocked and customer emailed${d.refund.collectItem ? "; courier booked" : ""}.</span></div></div>`;
  } else if (run.status === "FAILED" || run.status === "CANCELLED") {
    html = `<div class="verdict bad"><span class="v-ico">✕</span><div><b>${run.status === "FAILED" ? "A step kept failing" : "Cancelled"}</b><span>${h(run.error || "")}</span></div></div>`;
  }
  if (rb || undo.length) {
    html += `<div class="undo-lane">↩️ <b>Saga rollback:</b> ${h(rb?.summary || undo.map((u) => `${u.step} ${u.state}`).join(", "))}</div>`;
  }
  if (banner.dataset.html !== html) {
    banner.innerHTML = html;
    banner.dataset.html = html;
  }

  const ev = el.querySelector("[data-events]");
  const rows = [];
  for (const l of d.ledger || []) {
    const cls = l.duplicate ? "dupe" : l.status >= 500 ? "bad" : l.status >= 400 ? "warn" : "ok";
    rows.push(`<div class="event ${cls}"><span class="e-dot"></span><span>${l.duplicate ? "<b>Exactly once:</b> " : ""}${h(svcName(l.endpoint))}: ${h(l.note)}</span></div>`);
  }
  for (const w of d.webhooks || []) {
    rows.push(`<div class="event ${w.verified ? "ok" : "warn"}"><span class="e-dot"></span><span>🔏 <b>Signed webhook</b> from Continuum: ${h(w.type)} ${w.verified ? "· signature verified" : "· signature NOT verified"}</span></div>`);
  }
  const key = rows.join("");
  if (ev.dataset.key !== key) {
    ev.innerHTML = rows.length ? `<div class="small muted" style="margin-bottom:2px">What Brightcart's systems saw</div>` + key : "";
    ev.dataset.key = key;
  }
}

function svcName(endpoint) {
  return { "orders/verify": "Orders", "payments/refund": "Payments", "payments/reverse": "Payments", "inventory/restock": "Inventory", "inventory/release": "Inventory", "email/send": "Email", "courier/pickup": "Courier", "crm/note": "CRM", "hooks/refund-complete": "Store" }[endpoint] || endpoint;
}

/** Waits until a refund has finished (and its webhook arrived, briefly). */
export async function waitForRefund(wfId, timeoutMs = 90000) {
  const end = Date.now() + timeoutMs;
  while (Date.now() < end) {
    const d = runs.get(wfId);
    if (d && d.run?.status && d.run.status !== "RUNNING" && d.rollbackRun?.status !== "RUNNING" && !polling.has(wfId)) return d;
    if (!polling.has(wfId)) follow(wfId);
    await sleep(800);
  }
  return runs.get(wfId) || null;
}

export function openReturn(orderId) {
  returnForm(orderId, true);
}
