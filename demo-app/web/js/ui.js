// Bits of store UI that point at Continuum.
import { bus } from "./lib.js";
import { revealXray } from "./xray.js";

/** "◎ via Continuum" — opens that call in the X-ray. */
export function viaButton(calls) {
  const list = [...(calls || [])].reverse();
  const chatCall = list.find((c) => (c.path || "").startsWith("/v1/chat")) || list.find((c) => !c.console);
  if (!chatCall) return "";
  const n = (chatCall.features || []).filter((f) => f.key !== "gateway").length;
  return `<button class="via" data-call="${chatCall.id}">via Continuum${n ? ` · ${n} feature${n > 1 ? "s" : ""}` : ""}</button>`;
}

export function bindVia(el) {
  el.querySelectorAll("[data-call]").forEach((b) => b.addEventListener("click", () => {
    bus.emit("xray-focus", Number(b.dataset.call));
    revealXray();
  }));
}
