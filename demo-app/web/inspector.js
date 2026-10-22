// The Continuum inspector: every call this app made to Continuum, newest first.
import { h, api, featureChips, codeClass, ago } from "./lib.js";

const calls = new Map(); // id → call
let lastId = 0;
let seen = {};
let shown = new Set(); // ids already on screen: only newer cards slide in

export function seenFeatures() {
  return seen;
}

function add(list) {
  let changed = false;
  for (const c of list || []) {
    if (!c || calls.has(c.id)) continue;
    calls.set(c.id, c);
    lastId = Math.max(lastId, c.id);
    changed = true;
  }
  if (calls.size > 300) {
    for (const id of [...calls.keys()].sort((a, b) => a - b).slice(0, calls.size - 300)) calls.delete(id);
  }
  if (changed) render();
}

function render() {
  const el = document.getElementById("calls");
  const list = [...calls.values()].sort((a, b) => b.id - a.id);
  document.getElementById("insp-sub").textContent = list.length
    ? `${list.length} call${list.length === 1 ? "" : "s"} to Continuum from this app`
    : "Every call this app makes to Continuum";
  if (!list.length) {
    el.innerHTML = `<li class="empty">Nothing yet. Use the app — every request it sends to Continuum appears here,
      with what Continuum did to it.</li>`;
    return;
  }
  el.innerHTML = list
    .map(
      (c) => `<li class="call${shown.has(c.id) ? "" : " fresh"}" data-id="${c.id}" tabindex="0" role="button" aria-label="Details of ${h(c.action)}">
        <div class="line1">
          <span class="code ${codeClass(c.status)}">${c.status || "ERR"}</span>
          <span class="action">${h(c.action)}</span>
          <span class="ms">${c.ms ?? 0} ms</span>
        </div>
        <div class="path">${h(c.method)} ${h(c.path)}</div>
        ${c.features?.length ? `<div class="chips">${featureChips(c.features, ["billing"])}</div>` : ""}
      </li>`,
    )
    .join("");
  shown = new Set(list.map((c) => c.id));
}

function open(id) {
  const c = calls.get(Number(id));
  if (!c) return;
  const dialog = document.getElementById("dialog");
  document.getElementById("dialog-title").textContent = c.action;
  const features = (c.features || [])
    .map((f) => `<li><b>${h(f.label)}</b>${f.detail ? ` — ${h(f.detail)}` : ""}</li>`)
    .join("");
  document.getElementById("dialog-body").innerHTML = `
    <div class="row">
      <span class="code ${codeClass(c.status)}">${c.status || "ERR"}</span>
      <span class="mono">${h(c.method)} ${h(c.path)}</span>
      <span class="muted small">${c.ms} ms · ${h(ago(c.at))}</span>
      ${c.requestId ? `<span class="chip">request ${h(c.requestId)}</span>` : ""}
    </div>
    ${c.error ? `<div class="banner bad">${h(c.error)}</div>` : ""}
    ${features ? `<div><h3>What Continuum did</h3><ul>${features}</ul></div>` : ""}
    ${c.applied?.length ? `<div><h3>X-Continuum-Applied</h3><ul>${c.applied.map((a) => `<li class="small">${h(a)}</li>`).join("")}</ul></div>` : ""}
    <div><h3>Request (API key never shown; file contents shortened)</h3><pre>${h(JSON.stringify(c.request, null, 2) ?? "—")}</pre></div>
    <div><h3>Response</h3><pre>${h(JSON.stringify(c.response, null, 2) ?? "—")}</pre></div>
    ${c.requestId ? `<p class="muted small">Find this request in the Continuum console: Gateway → request feed, or Decision Provenance with id <b>${h(c.requestId)}</b>.</p>` : ""}`;
  dialog.showModal();
}

async function poll() {
  const data = await api(`/app/calls?after=${lastId}`);
  if (data.ok !== false) {
    seen = data.seen || seen;
    add(data.calls);
    window.dispatchEvent(new CustomEvent("seen", { detail: seen }));
  }
}

export function startInspector() {
  render();
  window.addEventListener("calls", (e) => add(e.detail));
  const el = document.getElementById("calls");
  el.addEventListener("click", (e) => {
    const li = e.target.closest(".call");
    if (li) open(li.dataset.id);
  });
  el.addEventListener("keydown", (e) => {
    const li = e.target.closest(".call");
    if (li && (e.key === "Enter" || e.key === " ")) {
      e.preventDefault();
      open(li.dataset.id);
    }
  });
  document.getElementById("clear-calls").addEventListener("click", async () => {
    await api("/app/calls", { method: "DELETE" });
    calls.clear();
    seen = {};
    lastId = 0;
    render();
    window.dispatchEvent(new CustomEvent("seen", { detail: seen }));
  });
  poll();
  setInterval(() => {
    if (document.visibilityState === "visible") poll();
  }, 1500);
}
