// Feature coverage: which Continuum features this session has shown, and how to show the rest.
import { h, api } from "../lib.js";

let root = null;
let features = [];

export async function mount(el) {
  root = el;
  el.innerHTML = `
    <div class="scene-head">
      <div>
        <h1>Feature coverage</h1>
        <p>Every Continuum feature this app can show, ticked off as the inspector sees it in Continuum's responses.
          Some features act inside Continuum without changing the response (the breaker watching answer quality,
          the decision trail); those are shown in the Continuum console, on the page named on each card.</p>
      </div>
      <div class="actions"><button class="btn ghost" id="refresh">Refresh</button></div>
    </div>
    <section class="card" style="margin-bottom:16px">
      <div class="row" style="margin-bottom:8px"><h2 id="cov-title">…</h2><span class="spacer"></span><span class="muted small" id="cov-sub"></span></div>
      <div class="coverage-bar"><i id="cov-bar" style="width:0%"></i></div>
    </section>
    <div class="features" id="features"></div>`;
  el.querySelector("#refresh").addEventListener("click", load);
  window.addEventListener("seen", onSeen);
  await load();
}

export function unmount() {
  window.removeEventListener("seen", onSeen);
  root = null;
}

function onSeen(e) {
  render(e.detail || {});
}

async function load() {
  const data = await api("/app/features");
  if (!root || data.ok === false) return;
  features = data.features;
  render(data.seen || {});
}

function render(seen) {
  if (!root || !features.length) return;
  const n = features.filter((f) => seen[f.key]).length;
  root.querySelector("#cov-title").textContent = `${n} of ${features.length} features shown`;
  root.querySelector("#cov-sub").textContent = n === features.length ? "Everything has been demonstrated." : "Use the hints below for the rest.";
  root.querySelector("#cov-bar").style.width = `${Math.round((n / features.length) * 100)}%`;
  const ordered = [...features].sort((a, b) => Number(!!seen[b.key]) - Number(!!seen[a.key]));
  root.querySelector("#features").innerHTML = ordered
    .map((f) => `<div class="feature ${seen[f.key] ? "seen" : ""}">
      <div class="name">${seen[f.key] ? `<span class="chip ok">✓ ${seen[f.key]}</span>` : `<span class="chip">not yet</span>`}${h(f.name)}</div>
      <div class="how">${h(f.how)}</div>
      <div class="where">Console: ${h(f.console)}</div>
    </div>`)
    .join("");
}
