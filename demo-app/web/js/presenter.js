// The presenter's sign-in: lets the tour flip Continuum's console switches.
import { h, api, bus, state, errorText } from "./lib.js";

let wrap;

export function init() {
  wrap = document.getElementById("presenter");
  document.getElementById("presenter-chip").addEventListener("click", open);
  bus.on("presenter", (p) => {
    state.presenter = p;
    paintChip();
  });
  bus.on("presenter-open", open);
  paintChip();
}

function paintChip() {
  const p = state.presenter || {};
  const dot = document.getElementById("presenter-dot");
  dot.className = "dot" + (p.signedIn ? (p.operator ? " op" : " on") : "");
  document.getElementById("presenter-label").innerHTML = p.signedIn ? (p.operator ? "Presenter<span class=\"long\"> · operator</span>" : "Presenter") : "Presenter<span class=\"long\"> sign-in</span>";
}

export function open() {
  return new Promise((resolve) => {
    wrap.hidden = false;
    render(resolve);
  });
}

function render(resolve) {
  const p = state.presenter || {};
  const done = () => {
    wrap.hidden = true;
    resolve(state.presenter);
  };
  if (p.signedIn) {
    wrap.innerHTML = `<div class="modal" role="dialog" aria-modal="true" aria-labelledby="pm-t">
      <h3 id="pm-t">Presenter controls are on</h3>
      <p>Signed in to Continuum as <b>${h(p.email)}</b>. The tour can now switch Continuum features on and off, arm chaos drills and read decision trails, and it puts everything back afterwards.</p>
      <ul>
        <li>${p.keyMatches ? "✅ This account issued the store's API key." : "⚠️ This account did <b>not</b> issue the store's API key, so switches would change a different account. Sign in with the account you ran setup with."}</li>
        <li>${p.operator ? `✅ Operator access for ${p.operatorMinutesLeft} more minutes (needed for hedging, which is engine-wide).` : "Operator access: no. Only the hedging step needs it."}</li>
      </ul>
      <div class="acts"><button class="btn ghost" id="pm-out">Sign out</button><button class="btn primary" id="pm-ok">Done</button></div></div>`;
    wrap.querySelector("#pm-ok").addEventListener("click", done);
    wrap.querySelector("#pm-out").addEventListener("click", async () => {
      const res = await api("/app/presenter/logout", {});
      state.presenter = res.presenter || { signedIn: false };
      paintChip();
      render(resolve);
    });
  } else {
    wrap.innerHTML = `<form class="modal" role="dialog" aria-modal="true" aria-labelledby="pm-t">
      <h3 id="pm-t">Presenter sign-in</h3>
      <p>The store holds only an API key, like any real app, and a key cannot change Continuum's settings. To let the tour switch features on and off, sign in with the Continuum account you ran setup with. The password is used once and not kept.</p>
      <label for="pm-email">Continuum email</label><input class="input" id="pm-email" type="email" autocomplete="username" required>
      <label for="pm-pass">Password</label><input class="input" id="pm-pass" type="password" autocomplete="current-password" required>
      <div class="err" id="pm-err" hidden></div>
      <div class="acts"><button type="button" class="btn ghost" id="pm-cancel">Not now</button><button class="btn primary" id="pm-go">Sign in</button></div></form>`;
    const form = wrap.querySelector("form");
    wrap.querySelector("#pm-cancel").addEventListener("click", done);
    form.addEventListener("submit", async (e) => {
      e.preventDefault();
      const btn = wrap.querySelector("#pm-go");
      btn.disabled = true;
      btn.innerHTML = `<span class="spin"></span> Signing in…`;
      const res = await api("/app/presenter/login", { email: wrap.querySelector("#pm-email").value, password: wrap.querySelector("#pm-pass").value });
      if (res.ok === false) {
        const err = wrap.querySelector("#pm-err");
        err.hidden = false;
        err.textContent = errorText(res);
        btn.disabled = false;
        btn.textContent = "Sign in";
        return;
      }
      state.presenter = res.presenter;
      paintChip();
      render(resolve);
    });
    setTimeout(() => wrap.querySelector("#pm-email")?.focus(), 50);
  }
  wrap.onclick = (e) => {
    if (e.target === wrap) done();
  };
}

/** Runs a presenter action; throws with Continuum's message when it fails. */
export async function run(action, args) {
  const res = await api("/app/presenter/do", { action, args });
  if (res.ok === false) throw new Error(errorText(res));
  return res.result;
}
