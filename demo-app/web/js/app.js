// Boots the page: the store's tabs, the signed-in customer, the X-ray and the tour.
import { api, bus, state, h, initials, hue, toast } from "./lib.js";
import { initXray } from "./xray.js";
import * as shop from "./shop.js";
import * as chat from "./chat.js";
import * as orders from "./orders.js";
import * as desk from "./desk.js";
import * as office from "./office.js";
import * as presenter from "./presenter.js";
import * as tour from "./tour.js";

const VIEWS = { shop, orders, desk, office };
const URLS = { shop: "brightcart.in", orders: "brightcart.in/account/orders", desk: "desk.brightcart.in/inbox", office: "ops.brightcart.in" };
let current = null;

/** Shows a tab; resolves once its content has loaded. */
export function go(tab) {
  if (!VIEWS[tab]) tab = "shop";
  if (current === tab) return Promise.resolve();
  current = tab;
  document.querySelectorAll(".tabs a").forEach((a) => a.classList.toggle("on", a.dataset.tab === tab));
  document.querySelectorAll(".view").forEach((v) => (v.hidden = v.dataset.view !== tab));
  document.getElementById("url").textContent = URLS[tab];
  const el = document.getElementById("view-" + tab);
  const loaded = VIEWS[tab].show(el);
  document.getElementById("views").scrollTop = 0;
  if (location.hash !== "#" + tab) history.replaceState(null, "", "#" + tab);
  return Promise.resolve(loaded);
}

export function setCustomer(id) {
  const c = state.customers.find((x) => x.id === id) || state.customers[0];
  if (!c) return;
  const changed = state.customer?.id !== c.id;
  state.customer = c;
  document.getElementById("who-name").textContent = c.name.split(" ")[0];
  const av = document.getElementById("who-avatar");
  av.textContent = initials(c.name);
  av.style.background = hue(c.name);
  document.getElementById("deliver-to").textContent = `${c.city} ${c.pin}`;
  if (changed) bus.emit("customer", c);
}

function whoMenu() {
  const menu = document.getElementById("who-menu");
  const btn = document.getElementById("who");
  btn.addEventListener("click", (e) => {
    e.stopPropagation();
    menu.hidden = !menu.hidden;
    if (!menu.hidden) {
      menu.innerHTML = `<div class="small muted" style="padding:6px 8px">Switch customer (demo)</div>` + state.customers.map((c) =>
        `<button role="option" aria-selected="${c.id === state.customer?.id}" data-id="${c.id}">
          <span class="avatar" style="background:${hue(c.name)}">${initials(c.name)}</span>
          <span><b>${h(c.name)}</b><small>${h(c.tier)} member · ${h(c.city)}</small></span></button>`).join("");
      menu.querySelectorAll("button").forEach((b) => b.addEventListener("click", () => {
        setCustomer(b.dataset.id);
        menu.hidden = true;
      }));
    }
  });
  document.addEventListener("click", () => (menu.hidden = true));
}

async function boot() {
  state.go = go;
  state.setCustomer = setCustomer;
  initXray();
  const status = await api("/app/status");
  if (status.ok === false) {
    toast(status.error?.message || "The demo server did not answer.", true);
    return;
  }
  state.status = status;
  state.customers = status.customers || [];
  state.presenter = status.presenter || state.presenter;
  if (!status.continuum?.keyConfigured) toast("No Continuum API key yet: run setup_continuum.py (see README).", true);
  else if (!status.continuum?.reachable) toast(`Continuum is not answering at ${status.continuum.url}. Start it, then reload.`, true);
  const prods = await api("/app/products");
  state.products = prods.products || [];
  setCustomer("C-1001");
  whoMenu();

  document.querySelectorAll("[data-tab]").forEach((a) => a.addEventListener("click", (e) => {
    e.preventDefault();
    go(a.dataset.tab);
  }));
  window.addEventListener("hashchange", () => go(location.hash.slice(1)));

  const xbtn = document.getElementById("xray-toggle");
  xbtn.addEventListener("click", () => {
    const on = xbtn.getAttribute("aria-pressed") !== "true";
    xbtn.setAttribute("aria-pressed", String(on));
    document.getElementById("stage").classList.toggle("no-xray", !on);
  });

  chat.init();
  presenter.init();
  tour.init();
  go(location.hash.slice(1) || "shop");
}

boot();
