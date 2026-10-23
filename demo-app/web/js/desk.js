// The support desk (staff): the inbox, AI triage, reply drafts, and an agent sandbox.
import { h, api, sleep, ago, initials, hue, features, gauge, animateIn, errorText } from "./lib.js";
import { viaButton, bindVia } from "./ui.js";

let root;
let tickets = [];
let customers = {};
let selected = null;
const results = {}; // ticket id → triage outcome
const drafts = {}; // ticket id → {quick, careful}

const CHANNEL = { "live chat": "💬", chat: "💬", email: "✉️", "web form": "🌐" };
const MOOD = { angry: "😡", frustrated: "😤", neutral: "😐", happy: "😊" };

export async function show(el) {
  root = el;
  el.innerHTML = `
    <div class="desk-bar"><h2>Inbox</h2><span class="pill" id="inbox-n">…</span><span class="small muted">Meera · Support agent</span><span class="spacer"></span>
      <button class="btn sm" id="agent-btn">🤖 Agent sandbox</button>
      <button class="btn sm primary" id="triage-all">✦ AI triage all</button></div>
    <div id="desk-extra"></div>
    <div class="desk"><div class="tickets" id="tickets"></div><div class="detail" id="detail"></div></div>`;
  el.querySelector("#triage-all").addEventListener("click", () => triageAll());
  el.querySelector("#agent-btn").addEventListener("click", () => runAgent());
  const data = await api("/app/tickets");
  if (root !== el) return;
  tickets = data.tickets || [];
  customers = data.customers || {};
  el.querySelector("#inbox-n").textContent = `${tickets.length} open`;
  renderList();
  select(selected || tickets[0]?.id);
}

function renderList() {
  const box = root.querySelector("#tickets");
  box.innerHTML = tickets.map((t, i) => {
    const c = customers[t.customer];
    const name = c?.name || "Unknown sender";
    return `<button class="ticket ${t.id === selected ? "on" : ""}" data-id="${t.id}" style="animation-delay:${i * 40}ms">
      <span class="avatar" style="background:${c ? hue(name) : "#9ca3af"}">${c ? initials(name) : "?"}</span>
      <span style="min-width:0">
        <span class="t-top"><b>${h(name)}</b>${c && c.tier !== "Standard" ? `<span class="pill ${c.tier === "Platinum" ? "plum" : "warn"}" style="height:19px;font-size:11px">${h(c.tier)}</span>` : ""}<time>${CHANNEL[t.channel] || ""} ${ago(t.minutes_ago)}</time></span>
        <span class="t-subj">${h(t.subject)}</span>
        <span class="t-snip">${h(t.body)}</span>
        <span class="t-tags" id="tags-${t.id}">${tags(t.id)}</span>
      </span></button>`;
  }).join("");
  box.querySelectorAll(".ticket").forEach((b) => b.addEventListener("click", () => select(b.dataset.id)));
}

function tags(id) {
  const r = results[id];
  if (!r) return "";
  if (r.pending) return `<span class="pill">✦ triaging…</span>`;
  if (r.error) {
    const code = r.error.error;
    if (code === "request_blocked" || r.error.status === 403) return `<span class="pill bad pop">⛔ Blocked: prompt attack</span>`;
    if (code === "cost_limited") return `<span class="pill warn pop">💸 Over the cost limit, try later</span>`;
    if (code === "deadline_unreachable") return `<span class="pill warn pop">⏱️ Refused: deadline can't be met</span>`;
    if (code === "capacity" || r.error.status === 429) return `<span class="pill warn pop">⏸️ Held back: AI busy, retry later</span>`;
    return `<span class="pill bad pop">${h(r.error.message || "failed").slice(0, 60)}</span>`;
  }
  const t = r.triage || {};
  const u = Math.max(0, Math.min(5, Number(t.urgency) || 0));
  return [
    t.category ? `<span class="pill info pop">${h(t.category)}</span>` : "",
    `<span class="urgency pop" title="Urgency ${u}/5">${[1, 2, 3, 4, 5].map((i) => `<i class="${i <= u ? "on" : ""}"></i>`).join("")}</span>`,
    t.sentiment ? `<span class="pill pop">${MOOD[t.sentiment] || ""} ${h(t.sentiment)}</span>` : "",
    t.needs_refund ? `<span class="pill brand pop">refund</span>` : "",
    r.firewall ? `<span class="pill bad pop">🛡️ card & PIN removed</span>` : "",
    r.criticality === "CRITICAL" ? `<span class="pill plum pop">⚡ urgent · ${r.ms} ms</span>` : "",
    !r.triage ? `<span class="pill warn pop">unreadable answer</span>` : "",
  ].join("");
}

function paintTags(id) {
  const el = root?.querySelector("#tags-" + id);
  if (el) el.innerHTML = tags(id);
  if (id === selected) renderDetail();
}

export function select(id) {
  selected = id;
  root.querySelectorAll(".ticket").forEach((b) => b.classList.toggle("on", b.dataset.id === id));
  renderDetail();
}

function renderDetail() {
  const box = root.querySelector("#detail");
  const t = tickets.find((x) => x.id === selected);
  if (!t) {
    box.innerHTML = "";
    return;
  }
  const c = customers[t.customer];
  const r = results[t.id];
  const d = drafts[t.id] || {};
  box.innerHTML = `<div class="card">
    <div class="cust"><span class="avatar" style="background:${c ? hue(c.name) : "#9ca3af"}">${c ? initials(c.name) : "?"}</span>
      <div><b>${h(c?.name || "Unknown sender")}</b><div class="small muted">${c ? `${h(c.tier)} · ${h(c.city)} · customer since ${h(c.since)}` : "Not a signed-in customer"}</div></div>
      <span class="spacer"></span><span class="pill">${CHANNEL[t.channel] || ""} ${h(t.channel)}</span></div>
    <h3 style="margin-bottom:8px">${h(t.subject)} <span class="muted small mono">${t.id}</span></h3>
    <div class="bubble ${/ERROR|WARN/.test(t.body) ? "log" : ""}">${h(t.body)}</div>
    ${r?.triage ? `<div class="verdict ok" style="background:var(--info-soft);color:var(--info)"><span class="v-ico">✦</span><div><b>${h(r.triage.summary || "")}</b><span>Next: ${h(r.triage.next_action || "")}</span></div></div>` : ""}
    <div class="row" style="margin-top:12px">
      <button class="btn sm" data-a="triage">✦ AI triage</button>
      <button class="btn sm" data-a="quick">⚡ Quick draft</button>
      <button class="btn sm plum" data-a="careful">🎯 Careful draft</button>
      ${r && !r.pending ? viaButton(r.calls) : ""}
    </div>
    ${d.quick && d.careful ? `<div class="compare">${draftCard(d.quick)}${draftCard(d.careful)}</div>` : d.careful ? draftCard(d.careful) : d.quick ? draftCard(d.quick) : ""}
  </div>`;
  box.querySelector('[data-a="triage"]').addEventListener("click", () => triage(t.id));
  box.querySelector('[data-a="quick"]').addEventListener("click", () => draft(t.id, { quick: true }));
  box.querySelector('[data-a="careful"]').addEventListener("click", () => draft(t.id, { careful: true }));
  bindVia(box);
  animateIn(box);
}

function draftCard(d) {
  if (d.pending) return `<div class="draft"><div class="row"><span class="spin" style="color:var(--brand)"></span><span class="small muted">${d.careful ? "Writing carefully with a stronger model…" : "Writing a quick draft…"}</span></div></div>`;
  if (d.error) return `<div class="draft"><span class="pill bad">${h(d.error)}</span></div>`;
  const conf = d.meta?.confidence;
  return `<div class="draft">
    <div class="row"><span class="pill ${d.careful ? "plum" : "info"}">${d.careful ? "🎯 Careful · best quality" : "⚡ Quick · lowest cost"}</span><span class="small muted">${h(d.model || "")}</span><span class="spacer"></span>${viaButton(d.calls)}</div>
    ${conf != null ? `<div class="gauge" style="margin-top:8px">${gauge(conf, conf > 0.7 ? "var(--ok)" : conf > 0.45 ? "var(--warn)" : "var(--bad)")}<div><b>${Math.round(conf * 100)}%</b><div class="small muted">confident: the model agreed with itself</div></div></div>` : ""}
    <p class="draft-text">${h(d.draft)}</p>
    <div class="row"><button class="btn sm primary" onclick="this.textContent='Sent ✓';this.disabled=true">Send reply</button></div></div>`;
}

/** Triage one ticket. Returns the outcome. */
export async function triage(id, { bulk = false, deadlineMs } = {}) {
  results[id] = { pending: true };
  paintTags(id);
  const started = performance.now();
  const res = await api("/app/triage", { ticketId: id, bulk, deadlineMs });
  const ms = Math.round(performance.now() - started);
  const f = features(res.calls);
  results[id] = res.ok === false
    ? { error: res.error, calls: res.calls, ms }
    : { triage: res.triage, criticality: res.criticality, deadlineMs: res.deadlineMs, model: res.model, calls: res.calls, firewall: !!f["prompt-firewall"], ms };
  paintTags(id);
  return results[id];
}

/** Every ticket at once: urgent ones as CRITICAL, the rest as background work. */
export async function triageAll() {
  const btn = root.querySelector("#triage-all");
  btn.disabled = true;
  btn.innerHTML = `<span class="spin"></span> Triaging ${tickets.length}…`;
  const urgent = (t) => customers[t.customer]?.tier === "Platinum" || t.channel === "live chat";
  const extra = root.querySelector("#desk-extra");
  extra.innerHTML = `<div class="card" style="margin-bottom:12px"><div class="card-head"><span class="ico">🚦</span><div><h3>Sending ${tickets.length} tickets to the AI at once</h3><p>Urgent tickets are marked critical; the rest are background work Continuum can pace.</p></div></div>
    <div class="lanes">
      <div class="lane crit"><span>Critical</span><div class="track" data-lane="CRITICAL"></div><span data-n="CRITICAL">0</span></div>
      <div class="lane bg"><span>Background</span><div class="track" data-lane="BACKGROUND"></div><span data-n="BACKGROUND">0</span></div>
    </div><div class="small muted" id="lane-sum" style="margin-top:10px"></div></div>`;
  const counts = { CRITICAL: 0, BACKGROUND: 0 };
  const outcomes = await Promise.all(tickets.map(async (t, i) => {
    await sleep(i * 60);
    const lane = urgent(t) ? "CRITICAL" : "BACKGROUND";
    const r = await triage(t.id, { bulk: lane === "BACKGROUND" });
    counts[lane]++;
    const dot = document.createElement("i");
    dot.title = t.id;
    if (r.error) dot.style.background = "var(--warn)";
    extra.querySelector(`[data-lane="${lane}"]`)?.appendChild(dot);
    const n = extra.querySelector(`[data-n="${lane}"]`);
    if (n) n.textContent = counts[lane];
    return r;
  }));
  const ok = outcomes.filter((r) => !r.error).length;
  const limited = outcomes.filter((r) => r.error?.error === "cost_limited").length;
  const held = outcomes.filter((r) => r.error && r.error.error !== "cost_limited" && r.error.status === 429).length;
  const blocked = outcomes.filter((r) => r.error?.error === "request_blocked").length;
  extra.querySelector("#lane-sum").innerHTML = `<b>${ok}</b> triaged${blocked ? ` · <b>${blocked}</b> blocked by the firewall` : ""}${limited ? ` · <b>${limited}</b> stopped by the cost limit` : ""}${held ? ` · <b>${held}</b> background tickets held back so urgent work got through` : ""}`;
  btn.disabled = false;
  btn.innerHTML = "✦ AI triage all";
  return outcomes;
}

/** A reply draft: quick (LOW_COST) or careful (HIGH_QUALITY + confidence). */
export async function draft(id, { careful = false, quick = false } = {}) {
  drafts[id] ||= {};
  const slot = careful ? "careful" : "quick";
  drafts[id][slot] = { pending: true, careful };
  if (id === selected) renderDetail();
  const res = await api("/app/draft", { ticketId: id, careful, quick });
  drafts[id][slot] = res.ok === false ? { error: errorText(res), careful } : { ...res, careful };
  if (id === selected) renderDetail();
  return drafts[id][slot];
}

/** A badly built agent that keeps looking up the same order; Continuum stops it. */
export async function runAgent() {
  const extra = root.querySelector("#desk-extra");
  extra.innerHTML = `<div class="card" style="margin-bottom:12px"><div class="card-head"><span class="ico">🤖</span><div><h3>Auto-resolve agent (beta)</h3><p>Working on T-502. Each step is checked by Continuum's loop detector before the next one runs.</p></div></div>
    <div class="loop-steps" id="loop-steps"></div></div>`;
  extra.scrollIntoView({ behavior: "smooth", block: "start" });
  const res = await api("/app/loop-drill", {});
  const box = extra.querySelector("#loop-steps");
  if (res.ok === false) {
    box.innerHTML = `<div class="loop-step halt">${h(errorText(res))}</div>`;
    return res;
  }
  const steps = res.steps || [];
  for (let i = 0; i < steps.length; i++) {
    const row = document.createElement("div");
    row.className = "loop-step" + (i > 0 ? " dup" : "");
    row.innerHTML = `<b>${i + 1}</b> ${h(steps[i])}${i > 0 ? ` <span class="spacer"></span><span class="pill warn" style="height:19px">same as before</span>` : ""}`;
    box.appendChild(row);
    await sleep(420);
  }
  const v = res.verdict || {};
  const halt = document.createElement("div");
  halt.className = "loop-step halt";
  halt.innerHTML = res.stoppedAt && v.looping !== false
    ? `🛑 Continuum stopped the agent at step ${res.stoppedAt}: ${h(v.reason || v.message || "it was repeating itself without making progress")}. Passed to a human instead.`
    : v.enabled === false ? "Loop detection is off for this account (Continuum console → Loop Detection)." : "The agent finished without looping.";
  box.appendChild(halt);
  bindVia(extra);
  return res;
}

export function ticketIds() {
  return tickets.map((t) => t.id);
}
