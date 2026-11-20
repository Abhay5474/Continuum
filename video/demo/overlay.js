// On-screen direction for the demo recording: captions, chapter badge, a spotlight that
// dims and blurs everything except the subject, labelled arrows, a visible pointer and
// full-screen title cards. Injected into every page by record.mjs (window.__demo).
(() => {
  if (window.__demo) return;
  const NS = "http://www.w3.org/2000/svg";
  const css = `
  #dm-root{position:fixed;inset:0;pointer-events:none;z-index:2147483000;font-family:Inter,"Liberation Sans",Arial,sans-serif}
  #dm-root *{box-sizing:border-box}
  .dm-pane{position:fixed;background:rgba(9,12,22,.52);backdrop-filter:blur(5px) saturate(.7);-webkit-backdrop-filter:blur(5px) saturate(.7);
    transition:all .55s cubic-bezier(.2,.8,.2,1);opacity:0}
  .dm-on .dm-pane{opacity:1}
  .dm-ring{position:fixed;border-radius:16px;border:3px solid #ff7a59;box-shadow:0 0 0 6px rgba(255,122,89,.22),0 0 42px rgba(255,122,89,.55);
    transition:all .55s cubic-bezier(.2,.8,.2,1);opacity:0}
  .dm-on .dm-ring{opacity:1;animation:dmPulse 1.8s ease-in-out infinite}
  @keyframes dmPulse{50%{box-shadow:0 0 0 10px rgba(255,122,89,.12),0 0 60px rgba(255,122,89,.7)}}
  .dm-cap{position:fixed;left:50%;bottom:34px;transform:translate(-50%,24px);opacity:0;max-width:1320px;min-width:640px;
    background:linear-gradient(180deg,rgba(17,20,33,.94),rgba(10,12,22,.96));color:#fff;border-radius:18px;padding:20px 30px 22px;
    box-shadow:0 18px 60px rgba(0,0,0,.45);border:1px solid rgba(255,255,255,.08);transition:all .45s cubic-bezier(.2,.8,.2,1)}
  .dm-cap.show{opacity:1;transform:translate(-50%,0)}
  .dm-cap.top{bottom:auto;top:96px}
  .dm-cap .k{font-size:15px;letter-spacing:.14em;text-transform:uppercase;color:#ff9a7f;font-weight:700;margin-bottom:6px}
  .dm-cap .t{font-size:30px;font-weight:700;line-height:1.25}
  .dm-cap .s{font-size:22px;line-height:1.45;color:#d7dbe7;margin-top:6px}
  .dm-cap b{color:#ffcf9e}
  .dm-chap{position:fixed;left:26px;top:22px;display:flex;align-items:center;gap:12px;background:rgba(14,17,29,.92);color:#fff;
    padding:10px 18px 10px 12px;border-radius:999px;box-shadow:0 10px 30px rgba(0,0,0,.35);opacity:0;transform:translateY(-12px);
    transition:all .4s ease}
  .dm-chap.show{opacity:1;transform:none}
  .dm-chap .n{background:#ff6a45;color:#fff;font-weight:800;border-radius:999px;padding:4px 11px;font-size:16px}
  .dm-chap .g{font-size:14px;color:#aab1c5;text-transform:uppercase;letter-spacing:.12em}
  .dm-chap .f{font-size:19px;font-weight:700}
  .dm-call{position:fixed;max-width:430px;background:#fff;color:#141824;border-radius:14px;padding:14px 18px;font-size:20px;line-height:1.35;
    font-weight:600;box-shadow:0 14px 40px rgba(0,0,0,.35);border:2px solid #ff7a59;opacity:0;transform:scale(.92);transition:all .4s ease}
  .dm-call.show{opacity:1;transform:none}
  .dm-call small{display:block;font-weight:500;font-size:16px;color:#4a5164;margin-top:4px}
  .dm-arrow{position:fixed;inset:0;width:100vw;height:100vh;overflow:visible}
  .dm-arrow path{fill:none;stroke:#ff7a59;stroke-width:5;stroke-linecap:round;stroke-dasharray:1200;stroke-dashoffset:1200;animation:dmDraw .7s ease forwards}
  @keyframes dmDraw{to{stroke-dashoffset:0}}
  .dm-ptr{position:fixed;width:30px;height:30px;margin:-4px 0 0 -4px;opacity:0;transition:left .7s cubic-bezier(.3,.7,.2,1),top .7s cubic-bezier(.3,.7,.2,1),opacity .3s}
  .dm-ptr.show{opacity:1}
  .dm-ptr.click::after{content:"";position:absolute;left:-14px;top:-14px;width:44px;height:44px;border-radius:50%;border:3px solid #ff7a59;animation:dmClick .5s ease-out}
  @keyframes dmClick{from{transform:scale(.3);opacity:1}to{transform:scale(1.4);opacity:0}}
  .dm-card{position:fixed;inset:0;display:flex;align-items:center;justify-content:center;flex-direction:column;opacity:0;transition:opacity .6s ease;
    background:radial-gradient(1200px 700px at 30% 20%,#2a1a3f 0%,#121423 55%,#0b0d17 100%);color:#fff;text-align:center;padding:60px}
  .dm-card.show{opacity:1}
  .dm-card .logo{width:96px;height:96px;border-radius:26px;background:linear-gradient(135deg,#ff6a45,#e8455d);display:flex;align-items:center;
    justify-content:center;font-size:54px;font-weight:900;box-shadow:0 20px 60px rgba(255,106,69,.45);margin-bottom:30px}
  .dm-card h1{font-size:76px;margin:0 0 14px;letter-spacing:-.02em}
  .dm-card h2{font-size:34px;margin:0 0 30px;font-weight:500;color:#cfd4e3;max-width:1300px;line-height:1.35}
  .dm-card p{font-size:26px;color:#aeb5c8;max-width:1250px;line-height:1.5;margin:0}
  .dm-card ul{list-style:none;padding:0;margin:26px 0 0;display:grid;grid-template-columns:repeat(3,minmax(300px,1fr));gap:18px 26px;max-width:1500px}
  .dm-card li{background:rgba(255,255,255,.06);border:1px solid rgba(255,255,255,.1);border-radius:18px;padding:22px 24px;text-align:left;
    font-size:24px;line-height:1.35;opacity:0;transform:translateY(14px);animation:dmUp .6s ease forwards}
  .dm-card li b{display:block;font-size:27px;color:#ffb199;margin-bottom:6px}
  @keyframes dmUp{to{opacity:1;transform:none}}
  .dm-card .foot{position:absolute;bottom:34px;font-size:17px;color:#7f879c}`;

  const root = document.createElement("div");
  root.id = "dm-root";
  const style = document.createElement("style");
  style.textContent = css;
  const panes = [0, 1, 2, 3].map(() => Object.assign(document.createElement("div"), { className: "dm-pane" }));
  const ring = Object.assign(document.createElement("div"), { className: "dm-ring" });
  const cap = Object.assign(document.createElement("div"), { className: "dm-cap" });
  const chap = Object.assign(document.createElement("div"), { className: "dm-chap" });
  const ptr = Object.assign(document.createElement("div"), { className: "dm-ptr" });
  ptr.innerHTML = `<svg width="30" height="30" viewBox="0 0 24 24"><path d="M3 2l7.5 19 2.6-7.9L21 10.6z" fill="#fff" stroke="#111" stroke-width="1.6" stroke-linejoin="round"/></svg>`;
  const layer = document.createElement("div");
  const mount = () => {
    if (!document.body || document.getElementById("dm-root")) return;
    document.head.appendChild(style);
    root.append(...panes, ring, layer, chap, cap, ptr);
    document.body.appendChild(root);
  };
  if (document.readyState === "loading") document.addEventListener("DOMContentLoaded", mount);
  else mount();

  let target = null, pad = 12, raf = 0;
  const rectOf = (t) => {
    if (!t) return null;
    if (typeof t === "string") {
      const els = [...document.querySelectorAll(t)].filter((e) => e.getBoundingClientRect().width > 0);
      if (!els.length) return null;
      const rs = els.map((e) => e.getBoundingClientRect());
      const l = Math.min(...rs.map((r) => r.left)), tp = Math.min(...rs.map((r) => r.top));
      const r = Math.max(...rs.map((r) => r.right)), b = Math.max(...rs.map((r) => r.bottom));
      return { left: l, top: tp, width: r - l, height: b - tp };
    }
    if (t instanceof Element) return t.getBoundingClientRect();
    return t;
  };
  const place = () => {
    const W = innerWidth, H = innerHeight;
    const r0 = rectOf(target);
    if (!r0) return;
    const x = Math.max(0, r0.left - pad), y = Math.max(0, r0.top - pad);
    const w = Math.min(W - x, r0.width + pad * 2), h = Math.min(H - y, r0.height + pad * 2);
    const set = (el, a) => Object.assign(el.style, { left: a[0] + "px", top: a[1] + "px", width: Math.max(0, a[2]) + "px", height: Math.max(0, a[3]) + "px" });
    set(panes[0], [0, 0, W, y]);
    set(panes[1], [0, y + h, W, H - y - h]);
    set(panes[2], [0, y, x, h]);
    set(panes[3], [x + w, y, W - x - w, h]);
    set(ring, [x, y, w, h]);
  };
  const loop = () => {
    place();
    raf = requestAnimationFrame(loop);
  };

  const anchorPoint = (r, side) => ({
    left: { x: r.left - 6, y: r.top + r.height / 2 }, right: { x: r.left + r.width + 6, y: r.top + r.height / 2 },
    top: { x: r.left + r.width / 2, y: r.top - 6 }, bottom: { x: r.left + r.width / 2, y: r.top + r.height + 6 },
  })[side];

  window.__demo = {
    focus(t, opts = {}) {
      mount();
      target = t;
      pad = opts.pad ?? 12;
      cancelAnimationFrame(raf);
      loop();
      root.classList.add("dm-on");
    },
    unfocus() {
      root.classList.remove("dm-on");
      cancelAnimationFrame(raf);
      target = null;
    },
    caption({ kicker = "", title = "", text = "" } = {}) {
      mount();
      cap.innerHTML = `${kicker ? `<div class="k">${kicker}</div>` : ""}${title ? `<div class="t">${title}</div>` : ""}${text ? `<div class="s">${text}</div>` : ""}`;
      // Never cover what is in the spotlight: a subject in the lower part of the
      // screen moves the caption to the top.
      const r = target ? rectOf(target) : null;
      cap.classList.toggle("top", !!r && r.bottom > innerHeight - 260 && r.top > 200);
      cap.classList.add("show");
    },
    hideCaption() { cap.classList.remove("show"); },
    chapter({ n, total, group = "", name = "" } = {}) {
      mount();
      if (!name) return chap.classList.remove("show");
      chap.innerHTML = `<span class="n">${n} / ${total}</span><span class="g">${group}</span><span class="f">${name}</span>`;
      chap.classList.add("show");
    },
    // A labelled arrow from a callout box to the subject. side: where the callout sits.
    callout(t, { text = "", sub = "", side = "left", dist = 120 } = {}) {
      mount();
      const r = rectOf(t);
      if (!r) return;
      const box = Object.assign(document.createElement("div"), { className: "dm-call" });
      box.innerHTML = `${text}${sub ? `<small>${sub}</small>` : ""}`;
      layer.appendChild(box);
      const bw = Math.min(430, box.offsetWidth || 380), bh = box.offsetHeight || 80;
      const a = anchorPoint(r, side);
      let bx, by;
      if (side === "left") { bx = a.x - dist - bw; by = a.y - bh / 2; }
      if (side === "right") { bx = a.x + dist; by = a.y - bh / 2; }
      if (side === "top") { bx = a.x - bw / 2; by = a.y - dist - bh; }
      if (side === "bottom") { bx = a.x - bw / 2; by = a.y + dist; }
      bx = Math.max(16, Math.min(innerWidth - bw - 16, bx));
      by = Math.max(16, Math.min(innerHeight - bh - 16, by));
      Object.assign(box.style, { left: bx + "px", top: by + "px" });
      const from = side === "left" ? { x: bx + bw, y: by + bh / 2 } : side === "right" ? { x: bx, y: by + bh / 2 }
        : side === "top" ? { x: bx + bw / 2, y: by + bh } : { x: bx + bw / 2, y: by };
      const svg = document.createElementNS(NS, "svg");
      svg.setAttribute("class", "dm-arrow");
      const mx = (from.x + a.x) / 2 + (side === "top" || side === "bottom" ? 40 : 0), my = (from.y + a.y) / 2 - (side === "left" || side === "right" ? 40 : 0);
      svg.innerHTML = `<defs><marker id="dmHead" viewBox="0 0 10 10" refX="7" refY="5" markerWidth="5" markerHeight="5" orient="auto-start-reverse">
        <path d="M0 0L10 5L0 10z" fill="#ff7a59" style="stroke:none;animation:none"/></marker></defs>
        <path d="M${from.x} ${from.y} Q${mx} ${my} ${a.x} ${a.y}" marker-end="url(#dmHead)"/>`;
      layer.appendChild(svg);
      requestAnimationFrame(() => box.classList.add("show"));
    },
    clearCallouts() { layer.innerHTML = ""; },
    clear() {
      this.unfocus();
      this.clearCallouts();
      this.hideCaption();
    },
    pointer(x, y, click = false) {
      mount();
      ptr.classList.add("show");
      ptr.style.left = x + "px";
      ptr.style.top = y + "px";
      if (click) {
        ptr.classList.remove("click");
        void ptr.offsetWidth;
        ptr.classList.add("click");
      }
    },
    hidePointer() { ptr.classList.remove("show"); },
    card({ title = "", subtitle = "", text = "", items = [], foot = "" } = {}) {
      mount();
      const c = Object.assign(document.createElement("div"), { className: "dm-card" });
      c.innerHTML = `<div class="logo">C</div>${title ? `<h1>${title}</h1>` : ""}${subtitle ? `<h2>${subtitle}</h2>` : ""}${text ? `<p>${text}</p>` : ""}
        ${items.length ? `<ul>${items.map((it, i) => `<li style="animation-delay:${0.25 + i * 0.18}s"><b>${it[0]}</b>${it[1]}</li>`).join("")}</ul>` : ""}
        ${foot ? `<div class="foot">${foot}</div>` : ""}`;
      c.id = "dm-card";
      document.getElementById("dm-card")?.remove();
      root.appendChild(c);
      requestAnimationFrame(() => c.classList.add("show"));
    },
    hideCard() {
      const c = document.getElementById("dm-card");
      if (c) { c.classList.remove("show"); setTimeout(() => c.remove(), 650); }
    },
  };
})();
