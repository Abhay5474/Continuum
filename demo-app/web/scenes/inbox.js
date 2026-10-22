// Ticket inbox: triage as JSON, priorities and deadlines, careful drafts with confidence.
import { h, md, api, toast, appliedChips, errorText, pct, modelChip } from "../lib.js";

let root = null;
let tickets = [];
let customers = {};
const results = new Map(); // ticket id → {triage | error | running}
const drafts = new Map(); // ticket id → {draft | error | running}
const expanded = new Set();

const SENTIMENT = { angry: "bad", frustrated: "warn", neutral: "", happy: "ok" };

export async function mount(el) {
  root = el;
  el.innerHTML = `
    <div class="scene-head">
      <div>
        <h1>Ticket inbox</h1>
        <p>Every ticket is triaged by a model through Continuum, in JSON mode. Urgent ones (Platinum customers, live
          chat) go as <b>CRITICAL</b> with a 15-second deadline; a bulk run goes as <b>BACKGROUND</b>, which
          Continuum's admission control sheds first when a provider is busy. Tickets T-503 and T-504 show the
          firewall; T-505 carries a pasted log.</p>
      </div>
      <div class="actions">
        <button class="btn" id="triage-all">Triage all (bulk)</button>
        <button class="btn ghost" id="clear">Clear results</button>
      </div>
    </div>
    <section class="card"><div class="list" id="tickets"><div class="empty">Loading…</div></div></section>`;
  el.querySelector("#triage-all").addEventListener("click", triageAll);
  el.querySelector("#clear").addEventListener("click", () => {
    results.clear();
    drafts.clear();
    render();
  });
  el.querySelector("#tickets").addEventListener("click", onClick);
  const data = await api("/app/tickets");
  if (!root) return;
  if (data.ok === false) {
    el.querySelector("#tickets").innerHTML = `<div class="banner bad">${h(errorText(data.error))}</div>`;
    return;
  }
  tickets = data.tickets;
  customers = data.customers;
  render();
}

export function unmount() {
  root = null;
}

function render() {
  if (!root) return;
  root.querySelector("#tickets").innerHTML = tickets.map(renderTicket).join("");
}

function renderTicket(t) {
  const c = customers[t.customer];
  const r = results.get(t.id);
  const d = drafts.get(t.id);
  const open = expanded.has(t.id);
  return `<article class="ticket" data-id="${h(t.id)}">
    <div class="top">
      <span class="id">${h(t.id)}</span>
      <span class="subject">${h(t.subject)}</span>
      <span class="chip">${h(t.channel)}</span>
      ${c ? `<span class="chip ${c.tier === "Platinum" ? "accent" : ""}">${h(c.name)} · ${h(c.tier)}</span>` : `<span class="chip warn">unknown sender</span>`}
      <span class="acts">
        <button class="btn small" data-act="triage" ${r?.running ? "disabled" : ""}>${r?.running ? "Triaging…" : "Triage"}</button>
        <button class="btn small" data-act="draft" ${d?.running ? "disabled" : ""}>Draft reply</button>
        <button class="btn small c" data-act="careful" ${d?.running ? "disabled" : ""} title="HIGH_QUALITY routing and a confidence measurement">Careful draft</button>
      </span>
    </div>
    <div class="body ${open ? "" : "clamp"}" data-act="toggle" title="${open ? "" : "Show all"}">${h(t.body)}</div>
    ${r ? renderTriage(r) : ""}
    ${d ? renderDraft(d) : ""}
  </article>`;
}

function renderTriage(r) {
  if (r.running) return `<div class="result small muted">Sending to Continuum (${h(r.criticality || "")})…</div>`;
  if (r.error) {
    return `<div class="result error small">${h(errorText(r.error))}
      ${r.error.status ? `<span class="chip bad">HTTP ${r.error.status}</span>` : ""}</div>`;
  }
  const t = r.triage;
  const meta = r.meta || {};
  const head = t
    ? `<div class="row">
        <span class="chip">${h(t.category || "?")}</span>
        <span class="chip ${t.urgency >= 4 ? "bad" : t.urgency >= 3 ? "warn" : ""} urgency">urgency ${h(t.urgency ?? "?")}/5</span>
        ${t.sentiment ? `<span class="chip ${SENTIMENT[t.sentiment] || ""}">${h(t.sentiment)}</span>` : ""}
        ${t.needs_refund ? `<span class="chip accent">needs refund</span>` : ""}
        ${t.contains_sensitive_data ? `<span class="chip warn">sensitive data</span>` : ""}
      </div>
      <div style="margin-top:6px">${h(t.summary || "")}</div>
      ${t.next_action ? `<div class="small muted" style="margin-top:2px">Next: ${h(t.next_action)}</div>` : ""}`
    : `<div class="small">The model did not return JSON: <span class="mono">${h(r.raw || "")}</span></div>`;
  return `<div class="result">${head}
    <div class="msg-meta" style="margin-top:8px">
      <span class="chip c" title="Continuum JSON mode">JSON mode</span>
      <span class="chip c" title="Priority sent to Continuum">${h(r.criticality)}${r.deadlineMs ? ` · ${r.deadlineMs / 1000}s deadline` : ""}</span>
      ${modelChip(r.model)}
      ${appliedChips(meta.applied)}
    </div></div>`;
}

function renderDraft(d) {
  if (d.running) return `<div class="result small muted">${d.careful ? "Drafting carefully (high-quality model, measuring confidence)…" : "Drafting…"}</div>`;
  if (d.error) return `<div class="result error small">${h(errorText(d.error))}</div>`;
  const meta = d.meta || {};
  return `<div class="result">
    <div class="small muted" style="margin-bottom:4px">Draft reply${d.careful ? " (careful)" : ""}</div>
    <div class="answer">${md(d.draft)}</div>
    <div class="msg-meta" style="margin-top:8px">
      ${modelChip(d.model)}
      ${d.careful ? `<span class="chip c">HIGH_QUALITY routing</span>` : ""}
      ${meta.confidence != null ? `<span class="chip ${meta.low_confidence ? "warn" : "c"}">Confidence ${pct(meta.confidence)}</span>` : d.careful ? `<span class="chip warn" title="Turn Confidence on (ON_DEMAND) in the Continuum console">confidence not measured</span>` : ""}
      ${appliedChips(meta.applied)}
    </div></div>`;
}

async function onClick(e) {
  const art = e.target.closest(".ticket");
  if (!art) return;
  const id = art.dataset.id;
  const act = e.target.closest("[data-act]")?.dataset.act;
  if (act === "toggle") {
    expanded.has(id) ? expanded.delete(id) : expanded.add(id);
    render();
  } else if (act === "triage") {
    await triage(id, false);
  } else if (act === "draft" || act === "careful") {
    await draft(id, act === "careful");
  }
}

async function triage(id, bulk) {
  results.set(id, { running: true, criticality: bulk ? "BACKGROUND" : "" });
  render();
  const data = await api("/app/triage", { method: "POST", body: { ticketId: id, bulk } });
  results.set(id, data.ok === false ? { error: data.error } : data);
  render();
  return data;
}

async function triageAll() {
  const btn = root.querySelector("#triage-all");
  btn.disabled = true;
  // All at once, on purpose: this is the burst admission control exists for.
  const all = await Promise.all(tickets.map((t) => triage(t.id, true)));
  btn.disabled = false;
  const refused = all.filter((d) => d.ok === false).length;
  toast(refused ? `${tickets.length - refused} triaged, ${refused} refused by Continuum (see each ticket)` : `All ${tickets.length} tickets triaged`, refused > 0);
}

async function draft(id, careful) {
  drafts.set(id, { running: true, careful });
  render();
  const data = await api("/app/draft", { method: "POST", body: { ticketId: id, careful } });
  drafts.set(id, data.ok === false ? { error: data.error } : { ...data, careful });
  render();
}
