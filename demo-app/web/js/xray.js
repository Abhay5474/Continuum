// The Continuum X-ray: for each call the store made, what Continuum did.
import { h, api, bus, countTo, animateIn, state } from "./lib.js";
import { BY_KEY } from "./catalog.js";

// The path a chat request takes through Continuum, and which features act where.
const STAGES = [
  { key: "guard", name: "Guard", icon: "🛡️", hue: "#f43f5e", feats: ["prompt-firewall"] },
  { key: "context", name: "Context", icon: "📚", hue: "#a855f7", feats: ["context-transformers", "context-optimizer", "prompt-compression", "adaptive-policy"] },
  { key: "cache", name: "Cache", icon: "⚡", hue: "#22c55e", feats: ["semantic-cache"] },
  { key: "traffic", name: "Traffic", icon: "🚦", hue: "#f59e0b", feats: ["cost-limits", "admission", "priority-deadlines", "billing"] },
  { key: "routing", name: "Routing", icon: "🧭", hue: "#3b82f6", feats: ["routing-modes", "model-cascade", "autopilot", "semantic-breaker", "hedging"] },
  { key: "model", name: "AI model", icon: "🤖", hue: "#06b6d4", feats: ["gateway", "streaming", "tools", "json-mode", "vision", "failover", "degradation", "ai-chaos"] },
  { key: "checks", name: "Checks", icon: "✅", hue: "#10b981", feats: ["quality-gate", "confidence", "verification-engine"] },
];

const calls = new Map();
let lastId = 0;
let selectedId = null;
let pinnedUntil = 0;
let showRaw = false;
let trail = null; // {id, data|error|loading}
let root;

export function initXray() {
  root = document.getElementById("xray");
  root.innerHTML = `
    <div class="x-head" id="x-head">
      <div class="x-title"><span class="logo-c">◎</span><b>Continuum X-ray</b><span class="x-peek" id="x-peek"></span>
        <span class="x-live"><i></i>LIVE</span></div>
      <p class="x-sub">Every AI call Brightcart makes goes through Continuum with one API key. This is what Continuum did with it.</p>
    </div>
    <div class="x-stats">
      <div class="x-stat"><b id="x-n-calls">0</b><span>AI calls</span></div>
      <div class="x-stat"><b id="x-n-saved">0</b><span>Tokens saved</span></div>
      <div class="x-stat"><b id="x-n-cache">0</b><span>Cache hits</span></div>
    </div>
    <div class="x-body" id="x-body"><div id="x-current"></div><div class="x-recent" id="x-recent"></div></div>`;
  root.querySelector("#x-head").addEventListener("click", () => {
    if (matchMedia("(max-width: 920px)").matches) root.classList.toggle("open");
  });
  bus.on("calls", (list) => ingest(list));
  bus.on("xray-focus", (id) => select(id, true));
  renderCurrent();
  poll();
}

/** Opens the X-ray on phones, where it is a sheet. */
export function revealXray() {
  if (matchMedia("(max-width: 920px)").matches) root.classList.add("open");
}

async function poll() {
  try {
    const res = await fetch(`/app/calls?after=${lastId}`);
    const data = await res.json();
    if (data.calls?.length) ingest(data.calls);
    if (data.seen) bus.emit("seen", data.seen);
  } catch {
    /* the store's server restarting; try again */
  }
  setTimeout(poll, 1500);
}

function ingest(list) {
  let pick = null;
  let fresh = false;
  for (const c of list) {
    if (!c || calls.has(c.id)) continue;
    calls.set(c.id, c);
    fresh = true;
    lastId = Math.max(lastId, c.id);
    if (!c.console) {
      // Within one store action, show the AI call where Continuum did the most
      // (the tool-calling round, not the bookkeeping around it); later wins ties.
      const score = (x) => (isChat(x) ? 100 : 0)
        + (x.features || []).reduce((n, f) => n + (f.key === "gateway" || f.key === "quality-gate" ? 1 : 3), 0);
      if (!pick || score(c) >= score(pick)) pick = c;
    }
    bus.emit("call", c);
  }
  if (!fresh) return;
  if (pick && Date.now() > pinnedUntil) select(pick.id, false);
  else renderRecent();
  renderStats();
}

function select(id, byUser) {
  if (!calls.has(id)) return;
  selectedId = id;
  if (byUser) pinnedUntil = Date.now() + 8000;
  showRaw = false;
  trail = null;
  renderCurrent();
  renderRecent();
}

const isChat = (c) => (c.path || "").startsWith("/v1/chat/completions");

function savedTokens(c) {
  let n = 0;
  for (const f of c.features || []) {
    const m = /([\d,]+)\s*(?:->|→)\s*([\d,]+)\s*tokens/.exec(f.detail || "");
    if (m) n += Math.max(0, num(m[1]) - num(m[2]));
  }
  if (c.path?.startsWith("/api/gateway/context/transform")) {
    const s = c.response?.tokenStats;
    if (s && s.saved > 0) n += s.saved;
  }
  return n;
}
const num = (s) => Number(String(s).replace(/,/g, ""));

function renderStats() {
  let n = 0, saved = 0, cache = 0;
  for (const c of calls.values()) {
    if (c.console) continue;
    if (isChat(c) || c.path?.startsWith("/api/gateway/pipeline")) n++;
    saved += savedTokens(c);
    if ((c.features || []).some((f) => f.key === "semantic-cache")) cache++;
  }
  countTo(root.querySelector("#x-n-calls"), n);
  countTo(root.querySelector("#x-n-saved"), saved);
  countTo(root.querySelector("#x-n-cache"), cache);
}

function renderRecent() {
  const box = root.querySelector("#x-recent");
  const list = [...calls.values()].slice(-16).reverse();
  if (!list.length) {
    box.innerHTML = "";
    return;
  }
  box.innerHTML = `<h5>Recent calls</h5>` + list.map((c) => {
    const bad = c.status >= 400 || c.error;
    const n = (c.features || []).filter((f) => f.key !== "gateway").length;
    return `<button class="x-row ${c.id === selectedId ? "sel" : ""}" data-id="${c.id}">
      <i class="${c.console ? "console" : bad ? "bad" : ""}"></i>
      <span>${h(c.action)}${n ? `<span class="n">${n}</span>` : ""}</span>
      <small>${c.console ? "switch" : c.ms + " ms"}</small></button>`;
  }).join("");
  box.querySelectorAll(".x-row").forEach((b) => b.addEventListener("click", () => select(Number(b.dataset.id), true)));
  const sel = calls.get(selectedId);
  const peek = root.querySelector("#x-peek");
  if (sel) {
    const n = (sel.features || []).length;
    peek.textContent = `· ${sel.action} · ${n} feature${n === 1 ? "" : "s"}`;
  }
}

function renderCurrent() {
  const box = root.querySelector("#x-current");
  const c = calls.get(selectedId);
  if (!c) {
    box.innerHTML = `<div class="x-empty"><div class="rings"><i></i><i></i><i></i><b>◎</b></div>
      Use the store, or start the <b>Feature tour</b>.<br>Every AI call shows up here as it happens.</div>`;
    return;
  }
  const bad = c.status >= 400 || c.error;
  const model = c.response?.model || null;
  const provider = c.response?.continuum?.provider;
  box.innerHTML = `<div class="x-call" data-id="${c.id}">
    <div class="x-call-top"><h4>${h(c.action)}</h4></div>
    <div class="x-meta">
      ${c.console ? `<span class="x-chip">Presenter control</span>` : `<span class="x-chip mono">${h(c.method)} ${h(c.path.split("?")[0])}</span>`}
      ${c.console ? "" : `<span class="x-chip ${bad ? "bad" : "ok"}">${c.status || "no answer"}${statusWord(c)}</span>`}
      ${c.ms && !c.console ? `<span class="x-chip">${c.ms.toLocaleString("en-IN")} ms</span>` : ""}
      ${model && !String(model).startsWith("degraded") ? `<span class="x-chip">${h(provider ? provider + " · " : "")}${h(model)}</span>` : ""}
    </div>
    ${c.console ? consoleBody(c) : isChat(c) ? flow(c) : mini(c)}
    ${c.console ? "" : `<div class="x-actions">
      ${isChat(c) && c.requestId ? `<button class="x-btn ${trail ? "on" : ""}" id="x-why">Why this answer?</button>` : ""}
      <button class="x-btn ${showRaw ? "on" : ""}" id="x-raw">${showRaw ? "Hide" : "Show"} request &amp; response</button>
    </div>`}
    <div id="x-extra">${extra(c)}</div>
  </div>`;
  box.querySelector("#x-raw")?.addEventListener("click", () => {
    showRaw = !showRaw;
    renderCurrent();
  });
  box.querySelector("#x-why")?.addEventListener("click", () => loadTrail(c));
  animateIn(box);
}

function statusWord(c) {
  const code = c.response?.error?.code || c.response?.error;
  if (c.status === 200) return " OK";
  if (c.status === 403) return " blocked";
  if (c.status === 429) return code === "cost_limited" ? " over cost limit" : " shed";
  if (c.status === 422) return " refused";
  if (!c.status) return "";
  return "";
}

function flow(c) {
  const failed = c.status >= 400 || c.error;
  const byKey = Object.fromEntries((c.features || []).map((f) => [f.key, f]));
  let lit = 0;
  return `<div class="x-flow">${STAGES.map((s) => {
    let feats = s.feats.filter((k) => byKey[k]);
    if (s.key === "model" && failed) feats = feats.filter((k) => k !== "gateway" && k !== "json-mode" && k !== "tools");
    const on = feats.length > 0;
    const delay = on ? lit++ * 140 : 0;
    const body = on
      ? feats.map((k, i) => featLine(c, byKey[k], delay + i * 80)).join("")
      : `<div class="x-stage-none">${s.key === "model" && failed ? "No AI model was called." : "Nothing needed."}</div>`;
    return `<div class="x-stage ${on ? "on" : ""}" style="--hue:${s.hue};--d:${delay}ms">
      <div class="x-node">${s.icon}</div>
      <div><div class="x-stage-name">${s.name}</div>${body}</div></div>`;
  }).join("")}</div>`;
}

function featLine(c, f, delay) {
  const info = BY_KEY[f.key] || { name: f.label, icon: "•" };
  let detail = f.detail || "";
  if (f.key === "gateway") {
    const m = c.response?.model;
    const p = c.response?.continuum?.provider;
    detail = m ? `Answered by ${m}${p ? " (" + p + ")" : ""}, chosen by Continuum.` : "Sent through Continuum's OpenAI-compatible API.";
    if (c.response?.continuum?.latency_ms) detail += ` ${c.response.continuum.latency_ms} ms at the provider.`;
  }
  return `<div class="x-feat" style="--d:${delay}ms"><b>${info.icon} ${h(info.name)}</b><p>${h(cap(detail))}</p>${bars(detail)}</div>`;
}

const cap = (s) => (s ? s.charAt(0).toUpperCase() + s.slice(1) : s);

function bars(detail) {
  const t = /([\d,]+)\s*(?:->|→)\s*([\d,]+)\s*tokens/.exec(detail);
  if (t) {
    const a = num(t[1]), b = num(t[2]);
    const pct = a ? Math.round((1 - b / a) * 100) : 0;
    return `<div class="x-bar"><div class="b"><i class="before" data-w="100%"></i></div><span>${a.toLocaleString("en-IN")}</span></div>
      <div class="x-bar"><div class="b"><i data-w="${a ? Math.max(2, (b / a) * 100) : 0}%"></i></div><span>${b.toLocaleString("en-IN")} · ${pct >= 0 ? "−" + pct : "+" + -pct}%</span></div>`;
  }
  const s = /score (\d(?:\.\d+)?)/.exec(detail);
  if (s) return `<div class="x-bar"><div class="b"><i data-w="${Number(s[1]) * 100}%"></i></div><span>${Number(s[1]).toFixed(2)}</span></div>`;
  const p = /confidence (\d+)%/.exec(detail);
  if (p) return `<div class="x-bar"><div class="b"><i data-w="${p[1]}%"></i></div><span>${p[1]}%</span></div>`;
  return "";
}

function mini(c) {
  const feats = (c.features || []).map((f, i) => featLine(c, f, i * 90)).join("");
  return `<div class="x-mini">${feats || `<div class="x-stage-none">${c.error ? h(c.error) : "A supporting call."}</div>`}${special(c)}</div>`;
}

function special(c) {
  const r = c.response;
  const p = c.path || "";
  if (!r || typeof r !== "object") return "";
  if (p.startsWith("/api/gateway/context/transform") && r.tokenStats) {
    const s = r.tokenStats;
    return `<div class="x-feat"><b>📄 ${h(r.contextLabel || "Rewritten")}</b>
      <div class="x-bar"><div class="b"><i class="before" data-w="100%"></i></div><span>${s.before.toLocaleString("en-IN")} raw</span></div>
      <div class="x-bar" style="--hue:#a855f7"><div class="b"><i data-w="${Math.min(100, (s.after / Math.max(1, s.before)) * 100)}%"></i></div><span>${s.after.toLocaleString("en-IN")} for the AI</span></div></div>`;
  }
  if (p.startsWith("/api/gateway/pipeline") && Array.isArray(r.trace)) {
    return `<div class="x-steps">${r.trace.map((t) => `<span class="x-chip ${/FAIL/.test(t.status) ? "bad" : "done"}">${h(t.kind || t.type || "")} ${h(t.label || t.name || "")}</span>`).join("")}</div>`;
  }
  if (p.startsWith("/api/gateway/workflows/") && Array.isArray(r.steps)) {
    return `<div class="x-steps">${r.steps.map((s) => `<span class="x-chip ${/FAIL/.test(s.status) ? "bad" : /COMPLETE|SUCC/.test(s.status) ? "done" : ""}">${h(s.step || s.id)} · ${h(String(s.status || "").toLowerCase())}${s.attempts > 1 ? ` ×${s.attempts}` : ""}</span>`).join("")}</div>`;
  }
  if (p.startsWith("/api/gateway/memory/retrieve") && Array.isArray(r)) {
    return r.length ? `<div class="x-steps">${r.slice(0, 5).map((m) => `<span class="x-chip">${h((m.content || "").slice(0, 70))}</span>`).join("")}</div>` : `<div class="x-stage-none">Nothing remembered yet.</div>`;
  }
  if (p.startsWith("/v1/models") && Array.isArray(r.data)) {
    return `<div class="x-steps">${r.data.map((m) => `<span class="x-chip">${h(m.id)}</span>`).join("")}</div>`;
  }
  return "";
}

function consoleBody(c) {
  return `<div class="x-note">⚙️ The presenter changed a Continuum setting from its console. The store's own code did not change; only what Continuum does with the store's next calls.</div>`;
}

function extra(c) {
  let out = "";
  if (trail && trail.id === c.id) {
    if (trail.loading) out += `<div class="x-note">Reading the decision trail…</div>`;
    else if (trail.error) out += `<div class="x-note">${h(trail.error)}</div>`;
    else if (!trail.data.found) out += `<div class="x-note">No decision trail was recorded for this call. Decision provenance may be off in the Continuum console.</div>`;
    else out += `<div class="x-trail">${trail.data.decisions.map((d, i) => `<div class="x-dec" style="animation-delay:${i * 70}ms"><span>${h(d.stage)}</span><div><b>${h(d.choice)}</b><p>${h(d.reason || "")}</p></div></div>`).join("")}</div>`;
  }
  if (showRaw) out += `<pre class="x-raw">${h(JSON.stringify({ request: c.request, response: c.response }, null, 2))}</pre>`;
  return out;
}

export async function loadTrail(c) {
  if (trail && trail.id === c.id && !trail.loading) {
    trail = null;
    renderCurrent();
    return null;
  }
  if (!state.presenter.signedIn) {
    trail = { id: c.id, error: "Continuum keeps decision trails behind the console sign-in. Sign in as the presenter (top right) to read this one." };
    renderCurrent();
    return null;
  }
  trail = { id: c.id, loading: true };
  renderCurrent();
  const res = await api("/app/presenter/do", { action: "provenance", args: { requestId: c.requestId } });
  trail = res.ok ? { id: c.id, data: res.result } : { id: c.id, error: res.error?.message || "Could not read the trail." };
  if (selectedId === c.id) renderCurrent();
  return trail.data || null;
}

export function callById(id) {
  return calls.get(id);
}

export function lastChatCall() {
  return [...calls.values()].reverse().find((c) => isChat(c) && c.status === 200) || null;
}
