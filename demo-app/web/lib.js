// Shared helpers for the Brightcart page. No framework: plain modules.

/** Escapes text for HTML. Everything from the server or a model goes through this. */
export function h(v) {
  return String(v ?? "")
    .replace(/&/g, "&amp;")
    .replace(/</g, "&lt;")
    .replace(/>/g, "&gt;")
    .replace(/"/g, "&quot;")
    .replace(/'/g, "&#39;");
}

/** A model's answer as safe HTML: escaped first, then **bold**, `code`, lists and paragraphs. */
export function md(text) {
  const lines = h(text || "").split(/\r?\n/);
  const out = [];
  let list = null;
  const inline = (s) =>
    s.replace(/\*\*(.+?)\*\*/g, "<b>$1</b>").replace(/`([^`]+)`/g, "<code>$1</code>");
  for (const raw of lines) {
    const line = raw.trimEnd();
    const item = /^\s*(?:[-*•]|\d+[.)])\s+(.*)$/.exec(line);
    if (item) {
      if (!list) { list = []; }
      list.push(`<li>${inline(item[1])}</li>`);
      continue;
    }
    if (list) { out.push(`<ul>${list.join("")}</ul>`); list = null; }
    if (line.trim()) out.push(`<p>${inline(line)}</p>`);
  }
  if (list) out.push(`<ul>${list.join("")}</ul>`);
  return out.join("") || "<p></p>";
}

/** Calls this app's own API. Continuum's answers (even refusals) come back as {ok:false, error}. */
export async function api(path, { method = "GET", body } = {}) {
  let res;
  try {
    res = await fetch(path, {
      method,
      headers: body === undefined ? {} : { "Content-Type": "application/json" },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    return { ok: false, error: { code: "offline", message: "The Brightcart server is not reachable. Is server.py running?" }, calls: [] };
  }
  let data;
  try {
    data = await res.json();
  } catch {
    data = { ok: false, error: { code: "bad_response", message: `The server answered ${res.status}.` } };
  }
  if (!res.ok && data.ok !== false) data.ok = false;
  if (data.calls?.length) window.dispatchEvent(new CustomEvent("calls", { detail: data.calls }));
  return data;
}

let toastTimer;
export function toast(text, bad = false) {
  const el = document.getElementById("toast");
  el.textContent = text;
  el.className = "toast show" + (bad ? " bad" : "");
  clearTimeout(toastTimer);
  toastTimer = setTimeout(() => (el.className = "toast"), 3200);
}

/** A file (or a sample fetched from /samples) as a data URL. */
export function fileToDataUrl(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result);
    r.onerror = () => reject(r.error);
    r.readAsDataURL(file);
  });
}

export async function sample(name) {
  const res = await fetch(`/samples/${encodeURIComponent(name)}`);
  if (!res.ok) throw new Error(`Sample ${name} is missing`);
  const blob = await res.blob();
  return new File([blob], name, { type: blob.type });
}

/** Chips for the Continuum features a response shows (from the server's detection). */
export function featureChips(features = [], skip = ["gateway", "billing", "provenance"]) {
  return features
    .filter((f) => !skip.includes(f.key))
    .map((f) => `<span class="chip c" title="${h(f.detail || "")}">${h(f.label)}</span>`)
    .join("");
}

/** Chips for the `applied` lines Continuum returned on a chat answer. */
/** The model that answered. "auto" is what was asked for, not an answer (a cache hit reports it), so it is left out. */
export function modelChip(model) {
  return model && model !== "auto" ? `<span class="chip">${h(model)}</span>` : "";
}

export function appliedChips(applied = []) {
  return applied
    .map((line) => {
      const [k, ...rest] = line.split(":");
      return `<span class="chip c" title="${h(rest.join(":").trim())}">${h(label(k.trim()))}</span>`;
    })
    .join("");
}

const LABELS = {
  "prompt-firewall": "Firewall",
  "semantic-cache": "Cache hit",
  "model-cascade": "Cascade",
  "quality-gate": "Quality gate",
  confidence: "Confidence",
  "verification-engine": "Verified",
  "context-transformers": "Context transformed",
  "context-optimizer": "Context paged",
  "prompt-compression": "Compressed",
  "adaptive-policy": "Memory added",
  "semantic-breaker": "Breaker",
  hedging: "Hedged",
  failover: "Failover",
  "ai-chaos": "AI chaos",
  "degradation-ladder": "Degraded",
  autopilot: "Autopilot",
};
export function label(key) {
  return LABELS[key] || key;
}

export function errorText(err) {
  if (!err) return "Something went wrong.";
  const code = err.error || err.code;
  const hints = {
    request_blocked: "Continuum's Prompt Firewall refused this request before any model saw it.",
    no_provider: "Continuum has no model provider set up.",
    capacity: "Continuum's admission control shed this request: the provider is at capacity. Try again in a moment.",
    cost_limited: "Continuum's cost limits refused this request: over the allowance for now.",
    deadline_unreachable: "Continuum refused this request: it could not finish before its deadline.",
    quota_exceeded: "The Continuum plan's monthly quota is used up.",
    invalid_api_key: "Continuum did not accept the API key. Run setup_continuum.py again.",
    unreachable: "Continuum is not reachable from this app.",
    no_api_key: "No Continuum API key is configured yet.",
    invalid_pipeline: "Continuum could not run the pipeline (is it set up and enabled?).",
  };
  return [hints[code], err.message].filter(Boolean).join(" ");
}

export function codeClass(status) {
  if (!status) return "bad";
  if (status < 300) return "ok";
  if (status < 500 && status !== 401) return "warn";
  return "bad";
}

export function ago(iso) {
  const t = Date.parse(iso);
  if (Number.isNaN(t)) return "";
  const s = Math.max(0, Math.round((Date.now() - t) / 1000));
  if (s < 60) return `${s}s ago`;
  if (s < 3600) return `${Math.round(s / 60)}m ago`;
  return new Date(t).toLocaleTimeString();
}

export function money(n) {
  return typeof n === "number" ? n.toFixed(2) : n;
}

export function pct(x) {
  return typeof x === "number" ? `${Math.round(x * 100)}%` : "—";
}

/** Shared state the scenes read. */
export const state = {
  customer: "C-1001",
  customers: [],
  status: null,
};
