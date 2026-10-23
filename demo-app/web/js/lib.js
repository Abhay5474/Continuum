// Small helpers shared by every part of the page.

/** HTML-escapes a value for use in a template string. */
export function h(v) {
  return String(v ?? "").replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);
}

/** Calls the store's own server (/app/...). Never throws: errors come back as {ok:false, error}. */
export async function api(path, body, method) {
  const opts = { method: method || (body === undefined ? "GET" : "POST"), headers: {} };
  if (body !== undefined) {
    opts.headers["Content-Type"] = "application/json";
    opts.body = JSON.stringify(body);
  }
  try {
    const res = await fetch(path, opts);
    const data = await res.json().catch(() => ({ ok: false, error: { message: `HTTP ${res.status}` } }));
    if (!res.ok && data.ok !== false) data.ok = false;
    if (data.calls?.length) bus.emit("calls", data.calls);
    if (data.presenter) bus.emit("presenter", data.presenter);
    return data;
  } catch (e) {
    return { ok: false, error: { message: "The demo server is not answering. Is server.py running?" } };
  }
}

export const sleep = (ms) => new Promise((r) => setTimeout(r, ms));

/** ₹ with Indian digit grouping. */
export function inr(n, paise = false) {
  return "₹" + Number(n || 0).toLocaleString("en-IN", { minimumFractionDigits: paise ? 2 : 0, maximumFractionDigits: paise ? 2 : 0 });
}

export function ago(minutes) {
  if (minutes < 1) return "now";
  if (minutes < 60) return `${minutes} min ago`;
  const h = Math.round(minutes / 60);
  return `${h} h ago`;
}

export function initials(name) {
  return (name || "?").split(/\s+/).map((p) => p[0]).slice(0, 2).join("").toUpperCase();
}

/** A stable warm colour per name, for avatars. */
export function hue(name) {
  let x = 0;
  for (const c of name || "") x = (x * 31 + c.charCodeAt(0)) % 360;
  return `linear-gradient(135deg, hsl(${x} 70% 52%), hsl(${(x + 40) % 360} 72% 46%))`;
}

export function toast(text, bad = false) {
  const box = document.getElementById("toasts");
  const t = document.createElement("div");
  t.className = "toast" + (bad ? " bad" : "");
  t.textContent = text;
  box.appendChild(t);
  setTimeout(() => t.remove(), 4200);
}

/** An event hub, so the store, the X-ray and the tour do not import each other in circles. */
export const bus = {
  handlers: {},
  on(name, fn) {
    (this.handlers[name] ||= []).push(fn);
  },
  emit(name, payload) {
    for (const fn of this.handlers[name] || []) {
      try {
        fn(payload);
      } catch (e) {
        console.error(e);
      }
    }
  },
};

/** Shared state: who is signed in to the store, the catalogue, the presenter. */
export const state = {
  customers: [],
  customer: null,
  products: [],
  presenter: { signedIn: false },
  status: null,
};

export function features(calls) {
  const out = {};
  for (const c of calls || []) for (const f of c.features || []) out[f.key] = f;
  return out;
}

export function errorText(res) {
  const e = res?.error;
  if (!e) return "Something went wrong.";
  return e.message || e.error || String(e);
}

/** Reads a sample file shipped with the demo as a data: URL. */
export async function sampleDataUrl(name) {
  const res = await fetch("/samples/" + name);
  const blob = await res.blob();
  return new Promise((resolve) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result);
    r.readAsDataURL(blob);
  });
}

export async function sampleText(name) {
  const res = await fetch("/samples/" + name);
  return res.text();
}

export function fileToDataUrl(file) {
  return new Promise((resolve, reject) => {
    const r = new FileReader();
    r.onload = () => resolve(r.result);
    r.onerror = reject;
    r.readAsDataURL(file);
  });
}

/** Counts a number up from where it was, for the X-ray's totals. */
export function countTo(el, value) {
  const from = Number(el.dataset.v || 0);
  el.dataset.v = value;
  if (from === value) {
    el.textContent = value.toLocaleString("en-IN");
    return;
  }
  const start = performance.now();
  const step = (t) => {
    const p = Math.min(1, (t - start) / 700);
    const e = 1 - Math.pow(1 - p, 3);
    el.textContent = Math.round(from + (value - from) * e).toLocaleString("en-IN");
    if (p < 1) requestAnimationFrame(step);
  };
  requestAnimationFrame(step);
}

/** A half-circle gauge (0–1) as SVG. */
export function gauge(value, color) {
  const v = Math.max(0, Math.min(1, value || 0));
  const len = 81.7; // half-circumference of r=26
  return `<svg viewBox="0 0 64 38" aria-hidden="true"><path class="g-bg" d="M6 34a26 26 0 0 1 52 0"/>
    <path class="g-fg" d="M6 34a26 26 0 0 1 52 0" stroke="${color || "var(--ok)"}" stroke-dasharray="${len}" stroke-dashoffset="${len}" data-to="${len * (1 - v)}"/></svg>`;
}

/** Starts the gauges/meters inside an element animating to their values. */
export function animateIn(root) {
  requestAnimationFrame(() =>
    requestAnimationFrame(() => {
      root.querySelectorAll("[data-to]").forEach((p) => (p.style.strokeDashoffset = p.dataset.to));
      root.querySelectorAll("[data-w]").forEach((i) => (i.style.width = i.dataset.w));
    }),
  );
}
