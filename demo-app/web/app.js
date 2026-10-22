// Brightcart Support Desk: shell, routing and status.
import { h, api, state, toast } from "./lib.js";
import { startInspector } from "./inspector.js";
import * as chat from "./scenes/chat.js";
import * as inbox from "./scenes/inbox.js";
import * as refunds from "./scenes/refunds.js";
import * as receipts from "./scenes/receipts.js";
import * as files from "./scenes/files.js";
import * as coverage from "./scenes/coverage.js";

const SCENES = { chat, inbox, refunds, receipts, files, coverage };
let current = null;

async function loadStatus() {
  const s = await api("/app/status");
  const el = document.getElementById("status");
  if (s.ok === false) {
    el.className = "status bad";
    el.textContent = s.error?.message || "Brightcart server not reachable";
    return;
  }
  state.status = s;
  const c = s.continuum;
  if (!c.keyConfigured) {
    el.className = "status warn";
    el.textContent = "No Continuum API key — run setup_continuum.py";
  } else if (!c.reachable) {
    el.className = "status bad";
    el.textContent = `Continuum not reachable at ${c.url}`;
  } else {
    el.className = "status ok";
    el.textContent = `Continuum ${c.url.replace(/^https?:\/\//, "")} · key ${c.keyHint}`;
  }
  el.title = `This app keeps the API key on its server; the browser never sees it.\nContinuum calls back at ${s.publicUrl}`;
  if (!state.customers.length) {
    state.customers = s.customers;
    const select = document.getElementById("customer");
    select.innerHTML = s.customers
      .map((cu) => `<option value="${h(cu.id)}">${h(cu.name)} · ${h(cu.tier)}</option>`)
      .join("");
    select.value = state.customer;
  }
}

function route() {
  const name = (location.hash || "#chat").slice(1);
  const scene = SCENES[name] ? name : "chat";
  if (current === scene) return;
  if (current && SCENES[current].unmount) SCENES[current].unmount();
  current = scene;
  for (const a of document.querySelectorAll(".sidebar a")) {
    a.classList.toggle("active", a.dataset.scene === scene);
    if (a.dataset.scene === scene) a.setAttribute("aria-current", "page");
    else a.removeAttribute("aria-current");
  }
  const view = document.getElementById("view");
  view.innerHTML = "";
  view.scrollTop = 0;
  SCENES[scene].mount(view);
}

function updateCoverageCount(seen) {
  const n = Object.keys(seen || {}).length;
  document.getElementById("coverage-count").textContent = n ? `${n}` : "";
}

window.addEventListener("hashchange", route);
window.addEventListener("seen", (e) => updateCoverageCount(e.detail));
window.addEventListener("error", (e) => toast(`Page error: ${e.message}`, true));
window.addEventListener("unhandledrejection", (e) => toast(`Page error: ${e.reason?.message || e.reason}`, true));

document.getElementById("customer").addEventListener("change", (e) => {
  state.customer = e.target.value;
  window.dispatchEvent(new CustomEvent("customer", { detail: state.customer }));
});
document.getElementById("toggle-inspector").addEventListener("click", (e) => {
  const shell = document.getElementById("shell");
  const hidden = shell.classList.toggle("no-inspector");
  e.currentTarget.setAttribute("aria-expanded", String(!hidden));
});
if (window.matchMedia("(max-width: 1000px)").matches) {
  document.getElementById("shell").classList.add("no-inspector");
  document.getElementById("toggle-inspector").setAttribute("aria-expanded", "false");
}

startInspector();
loadStatus().then(route);
setInterval(loadStatus, 15000);
