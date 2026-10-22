// Refunds: a durable Continuum workflow calling the store's own services.
import { h, api, toast, errorText, ago, money } from "../lib.js";

const PLAN = [
  ["verify", "Verify the order", "orders/verify"],
  ["fraud-hold", "Fraud hold (durable 6 s timer)", null],
  ["refund", "Refund the card", "payments/refund"],
  ["restock", "Restock the item", "inventory/restock"],
  ["notify", "Email the customer", "email/send"],
  ["pickup", "Book courier pickup (only if asked)", "courier/pickup"],
  ["crm", "Note it in the CRM", "crm/note"],
];

const RECIPES = [
  ["Happy path", "Refund BC-1042 with courier pickup. Every step runs once; a signed webhook arrives when it finishes."],
  ["Retries", "Set courier → flaky. Pickup fails twice (HTTP 503); Continuum retries with backoff and it succeeds."],
  ["Exactly once", "Set payments → lost-response. The refund happens but the reply is lost; Continuum retries with the same Idempotency-Key and the card is refunded once (see the ledger)."],
  ["Saga rollback", "Set courier → down (or email → down). The run fails and, with saga on, Continuum undoes the refund and the restock in reverse order. The email cannot be unsent, so it is reported as uncompensated."],
  ["Cancel", "Start a refund and press Cancel during the fraud hold. Continuum stops the run and rolls back what it completed."],
  ["Not eligible", "Refund BC-2001 (51 days old). Verify answers 422, so Continuum fails the run at once instead of retrying."],
  ["Crash recovery", "Start a refund, stop Continuum during the fraud hold, start it again: the run resumes where it was."],
];

let root = null;
let selected = null;
let pollTimer = null;
let orders = [];

export async function mount(el) {
  root = el;
  el.innerHTML = `
    <div class="scene-head">
      <div>
        <h1>Refunds</h1>
        <p>A refund is a <b>durable workflow</b> in Continuum (<code>brightcart-refund</code>). Its steps call this
          store's own back-office services over HTTP; Continuum records every step, retries failures, sends each call
          with an Idempotency-Key, undoes completed steps when a later one fails, and signs a webhook when it ends.</p>
      </div>
    </div>
    <div class="grid two">
      <div class="stack">
        <section class="card">
          <div class="card-head"><h2>Start a refund</h2><span class="sub">as the support agent</span></div>
          <form id="start" class="stack">
            <div class="row">
              <label class="field" style="flex:1 1 200px"><span>Order</span><select id="order"></select></label>
              <label class="field" style="flex:1.4 1 180px"><span>Reason</span>
                <input type="text" id="reason" value="Arrived damaged"></label>
            </div>
            <div class="row">
              <label class="check"><input type="checkbox" id="collect" checked> Courier collects the item</label>
              <span class="spacer"></span>
              <button class="btn primary" id="start-btn">Start refund</button>
            </div>
          </form>
        </section>
        <section class="card" id="run-card"><div class="empty">Start a refund, or pick one below.</div></section>
        <section class="card">
          <div class="card-head"><h2>Refund runs</h2><span class="sub">started from this app</span></div>
          <div class="run-pick" id="runs"></div>
        </section>
      </div>
      <div class="stack">
        <section class="card">
          <div class="card-head"><h2>Back-office faults</h2><span class="sub">break the store's own services</span>
            <div class="right"><button type="button" class="btn ghost small" id="reset-store" title="Stock, refunds and these switches back to how they started">Reset store</button></div></div>
          <div id="faults" class="stack"></div>
        </section>
        <section class="card">
          <div class="card-head"><h2>Things to try</h2></div>
          <div class="list">${RECIPES.map(([t, d]) => `<div class="item"><div class="grow"><div class="title">${h(t)}</div><div class="small muted">${h(d)}</div></div></div>`).join("")}</div>
        </section>
      </div>
    </div>`;
  el.querySelector("#start").addEventListener("submit", start);
  el.querySelector("#reset-store").addEventListener("click", async () => {
    const data = await api("/app/reset", { method: "POST" });
    if (data.ok === false) return toast(errorText(data.error), true);
    loadFaults();
    toast("Store data and fault switches reset");
  });
  el.querySelector("#runs").addEventListener("click", (e) => {
    const b = e.target.closest("button[data-id]");
    if (b) select(b.dataset.id);
  });
  el.querySelector("#run-card").addEventListener("click", (e) => {
    if (e.target.closest("#cancel-run")) cancel();
  });
  const data = await api("/app/orders");
  orders = data.orders || [];
  if (!root) return;
  el.querySelector("#order").innerHTML = orders
    .map((o) => `<option value="${h(o.id)}">${h(o.id)} · ${h(o.item)} · ${money(o.amount)} ${o.returnable ? "" : "(not returnable)"}</option>`)
    .join("");
  el.querySelector("#order").value = "BC-1042";
  loadFaults();
  await loadRuns();
  if (selected) select(selected);
}

export function unmount() {
  clearTimeout(pollTimer);
  root = null;
}

async function loadFaults(data) {
  data = data || (await api("/app/faults"));
  if (!root || data.ok === false) return;
  const box = root.querySelector("#faults");
  box.innerHTML = Object.entries(data.modes)
    .map(([svc, modes]) => {
      const now = data.faults[svc];
      const help = data.help[`${svc}:${now}`];
      return `<div class="fault">
        <b>${h(svc)}</b>
        <div class="seg" role="radiogroup" aria-label="${h(svc)} fault">${modes
          .map((m) => `<button type="button" role="radio" aria-checked="${m === now}" class="${m === now ? "on" : ""}" data-svc="${h(svc)}" data-mode="${h(m)}">${h(m)}</button>`)
          .join("")}</div>
        ${help ? `<div class="fault-help">${h(help)}</div>` : ""}
      </div>`;
    })
    .join("");
  box.querySelectorAll("button[data-svc]").forEach((b) =>
    b.addEventListener("click", async () => {
      const res = await api("/app/faults", { method: "POST", body: { service: b.dataset.svc, mode: b.dataset.mode } });
      loadFaults(res);
    }),
  );
}

async function loadRuns() {
  const data = await api("/app/refunds");
  if (!root) return;
  const runs = data.refunds || [];
  root.querySelector("#runs").innerHTML = runs.length
    ? runs.map((r) => `<button type="button" data-id="${h(r.workflowId)}" class="${r.workflowId === selected ? "on" : ""}">
        <span class="chip ${statusTone(r.status)}">${h(r.status)}</span>
        <span><b>${h(r.orderId)}</b> · ${h(r.item)}</span>
        <span class="spacer"></span><span class="small muted">${h(ago(r.startedAt))}</span></button>`).join("")
    : `<span class="muted small">No refunds yet.</span>`;
}

function statusTone(s) {
  return s === "COMPLETED" ? "ok" : s === "RUNNING" ? "c" : s === "CANCELLED" ? "warn" : "bad";
}

async function start(e) {
  e.preventDefault();
  const btn = root.querySelector("#start-btn");
  btn.disabled = true;
  const data = await api("/app/refunds", {
    method: "POST",
    body: {
      orderId: root.querySelector("#order").value,
      reason: root.querySelector("#reason").value,
      collectItem: root.querySelector("#collect").checked,
    },
  });
  btn.disabled = false;
  if (data.ok === false) return toast(errorText(data.error), true);
  toast(`Refund workflow started in Continuum: ${data.refund.workflowId}`);
  selected = data.refund.workflowId;
  await loadRuns();
  select(selected);
}

async function select(id) {
  selected = id;
  clearTimeout(pollTimer);
  if (!root) return;
  root.querySelectorAll("#runs button").forEach((b) => b.classList.toggle("on", b.dataset.id === id));
  const data = await api(`/app/refunds/${encodeURIComponent(id)}`);
  if (!root || selected !== id) return;
  if (data.ok === false) {
    root.querySelector("#run-card").innerHTML = `<div class="banner bad">${h(errorText(data.error))}</div>`;
    return;
  }
  renderRun(data);
  const running = data.run?.status === "RUNNING" || data.rollbackRun?.status === "RUNNING";
  const settling = !running && !data.webhooks?.length && Date.now() - Date.parse(data.refund.startedAt) < 120000;
  if (running || settling) pollTimer = setTimeout(() => select(id), 1500);
  else loadRuns();
}

// "notify" → "Email the customer (notify)": the plan's words, with the id Continuum reports.
function stepLabel(id) {
  const step = PLAN.find(([key]) => key === id);
  return step ? `${h(step[1].replace(/ \(.*\)$/, ""))} <span class="mono small">(${h(id)})</span>` : h(id);
}

function renderRun(d) {
  const run = d.run || {};
  const r = d.refund;
  const byStep = new Map();
  const undo = [];
  for (const s of run.steps || []) {
    if (s.kind === "compensation") undo.push(s);
    else byStep.set(s.step, s);
  }
  for (const s of d.rollbackRun?.steps || []) if (s.kind === "compensation") undo.push(s);
  const finished = run.status && run.status !== "RUNNING";
  const steps = PLAN.map(([id, label]) => {
    const s = byStep.get(id);
    let st = s ? s.state : finished ? (id === "pickup" && !r.collectItem ? "skipped" : "not-run") : "pending";
    let detail = "";
    if (s) {
      if (s.waitSeconds) detail = `durable timer, ${s.waitSeconds}s`;
      if (s.attempts > 1) detail += `${detail ? " · " : ""}${s.attempts} attempts`;
      if (s.httpStatus) detail += `${detail ? " · " : ""}HTTP ${s.httpStatus}`;
      if (s.error) detail += `${detail ? " · " : ""}${s.error}`;
      if (s.state === "cancelled") detail = "never ran: the run was cancelled";
      if (s.state === "abandoned") detail = "never ran: the run ended first";
    } else if (st === "skipped") detail = "skipped by its condition (collectItem is false)";
    else if (st === "not-run") detail = "not reached";
    return `<div class="step ${h(st)}"><span class="dot"></span>
      <div><div class="name">${h(label)}</div>${detail ? `<div class="detail">${h(detail)}</div>` : ""}</div>
      <span class="chip ${st === "completed" ? "ok" : st === "failed" ? "bad" : st === "retrying" ? "warn" : ""}">${h(st.replace("-", " "))}</span></div>`;
  }).join("");
  const undoHtml = undo
    .map((s) => `<div class="step compensation ${h(s.state)}"><span class="dot"></span>
      <div><div class="name">${stepLabel(s.step)}</div>${s.error ? `<div class="detail">${h(s.error)}</div>` : ""}</div>
      <span class="chip ${s.state === "completed" ? "ok" : s.state === "failed" ? "bad" : ""}">${h(s.state)}</span></div>`)
    .join("");
  const rb = run.rollback;
  let banner = "";
  if (run.status === "COMPLETED") banner = `<div class="banner ok">Refund complete: ${money(r.amount)} back to the card, item restocked, customer emailed.</div>`;
  else if (run.status === "FAILED") banner = `<div class="banner bad">The run failed: ${h(run.error || "")}</div>`;
  else if (run.status === "CANCELLED") {
    const rolling = d.rollbackRun?.status === "RUNNING";
    banner = `<div class="banner warn">Cancelled by the agent${r.rollbackWorkflowId ? (rolling ? "; Continuum is rolling back the completed steps" : "; Continuum rolled back what it could") : ""}.</div>`;
  }
  else if (run.status === "STUCK") banner = `<div class="banner bad">Stuck: Continuum stopped retrying a decision. Use Try again on the run page in the console.</div>`;
  const rbHtml = rb
    ? `<div class="banner ${rb.complete ? "c" : "warn"}"><b>Saga rollback:</b> ${h(rb.summary)}
        ${rb.compensated?.length ? `<br>Undone, newest first: ${rb.compensated.map(stepLabel).join(" → ")}` : ""}
        ${rb.uncompensated?.length ? `<br>Could not be undone (still out there): ${rb.uncompensated.map(stepLabel).join(", ")}` : ""}</div>`
    : "";
  const ledger = (d.ledger || []).slice().reverse();
  const hooks = d.webhooks || [];
  root.querySelector("#run-card").innerHTML = `
    <div class="card-head">
      <h2>${h(r.orderId)} · ${h(r.item)}</h2>
      <span class="chip ${statusTone(run.status)}">${h(run.status || "…")}</span>
      <div class="right">
        ${run.status === "RUNNING" ? `<button class="btn small danger" id="cancel-run">Cancel</button>` : ""}
      </div>
    </div>
    <div class="small muted mono" style="margin-bottom:8px">workflow ${h(r.workflowId)}</div>
    <div class="stack">
      ${banner}
      <div class="steps">${steps}${undoHtml}</div>
      ${rbHtml}
      <div>
        <h3 style="margin-bottom:4px">Calls Continuum made to this store's services</h3>
        <div class="list ledger">${
          ledger.length
            ? ledger.map((l) => `<div class="item"><span class="code ${l.status < 300 ? "ok" : l.status < 500 ? "warn" : "bad"}">${l.status}</span>
                <div class="grow"><span class="ep">/svc/${h(l.endpoint)}</span> ${l.duplicate ? `<span class="chip c">duplicate suppressed</span>` : ""}
                  <div class="muted">${h(l.note)}</div>
                  ${l.idempotencyKey ? `<div class="muted mono" style="font-size:10.5px">Idempotency-Key ${h(l.idempotencyKey)}</div>` : ""}</div></div>`).join("")
            : `<div class="muted small item">None yet.</div>`
        }</div>
      </div>
      <div>
        <h3 style="margin-bottom:4px">Webhooks from Continuum</h3>
        <div class="list ledger">${
          hooks.length
            ? hooks.map((w) => `<div class="item"><span class="chip ${w.verified ? "ok" : "warn"}">${w.verified ? "signature ✓" : "unverified"}</span>
                <div class="grow"><b>${h(w.type)}</b> ${String(w.workflowId || "").endsWith(":rollback") ? `<span class="chip">the rollback run</span>` : ""} ${w.duplicate ? `<span class="chip c">repeat ignored</span>` : ""}
                  <div class="muted">${h(w.check)} · ${h(ago(w.at))}</div></div></div>`).join("")
            : `<div class="muted small item">${run.status === "RUNNING" ? "Arrives when the run ends." : "None received. Is the webhook registered (setup_continuum.py) and can Continuum reach this app?"}</div>`
        }</div>
      </div>
    </div>`;
}

async function cancel() {
  if (!selected) return;
  const data = await api(`/app/refunds/${encodeURIComponent(selected)}/cancel`, { method: "POST" });
  if (data.ok === false) return toast(errorText(data.error), true);
  toast(data.cancel.rollbackWorkflowId ? "Cancelled; Continuum is rolling back the completed steps" : `Cancelled (${data.cancel.status})`);
  select(selected);
}
