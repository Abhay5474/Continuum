// Data files: Continuum's context transformers, and questions about pasted data.
import { h, md, api, toast, errorText, fileToDataUrl, sample, featureChips, modelChip } from "../lib.js";

const SAMPLES = [
  ["orders-export.csv", "Order export (CSV)"],
  ["checkout-errors.log", "Checkout error log"],
  ["support-thread.eml", "Customer email thread"],
];
const QUESTIONS = {
  "orders-export.csv": "Which product has the most returns, and what is the total refunded?",
  "checkout-errors.log": "What is breaking checkout, since when, and which orders are affected?",
  "support-thread.eml": "Summarise what the customer wants and what we have promised so far.",
};

let root = null;
let current = null; // {name, data (data URL)}

export function mount(el) {
  root = el;
  el.innerHTML = `
    <div class="scene-head">
      <div>
        <h1>Data files</h1>
        <p>Support teams paste spreadsheets, logs and email threads. Continuum's <b>context transformers</b> turn
          them into the form a model reads best — a table summary, an incident timeline, a clean conversation —
          usually in far fewer tokens. <b>Transform</b> shows what Continuum makes of a file; <b>Ask</b> pastes the raw
          file into a chat, where Continuum transforms (and compresses) it on the way to the model.</p>
      </div>
    </div>
    <div class="grid two">
      <section class="card">
        <div class="card-head"><h2>File</h2></div>
        <div class="stack">
          <label class="drop" id="drop"><input type="file" id="file" accept=".csv,.tsv,.log,.txt,.eml,.json,text/*">
            <span id="file-name">Drop a CSV, log or email file, or click to choose</span></label>
          <div class="row small"><span class="muted">Samples:</span>${SAMPLES.map(([f, l]) => `<button type="button" class="btn small" data-sample="${h(f)}">${h(l)}</button>`).join("")}</div>
          <div class="row">
            <label class="field"><span>Budget</span>
              <select id="budget"><option value="compact">Compact</option><option value="standard" selected>Standard</option><option value="full">Full</option></select></label>
            <span class="spacer"></span>
            <button class="btn c" id="transform" disabled>Transform</button>
          </div>
          <div id="t-out"></div>
        </div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Ask about it</h2></div>
        <div class="stack">
          <label class="field"><span>Question</span><textarea id="question" rows="3">Summarise this file.</textarea></label>
          <div class="row"><span class="spacer"></span><button class="btn primary" id="ask" disabled>Ask</button></div>
          <div id="a-out"></div>
        </div>
      </section>
    </div>`;
  const q = (s) => el.querySelector(s);
  q("#file").addEventListener("change", (e) => pick(e.target.files[0]));
  const zone = q("#drop");
  zone.addEventListener("dragover", (e) => { e.preventDefault(); zone.classList.add("over"); });
  zone.addEventListener("dragleave", () => zone.classList.remove("over"));
  zone.addEventListener("drop", (e) => { e.preventDefault(); zone.classList.remove("over"); pick(e.dataTransfer.files[0]); });
  el.querySelectorAll("[data-sample]").forEach((b) => b.addEventListener("click", async () => pick(await sample(b.dataset.sample))));
  q("#transform").addEventListener("click", transform);
  q("#ask").addEventListener("click", ask);
}

export function unmount() {
  root = null;
}

async function pick(file) {
  if (!file || !root) return;
  if (file.size > 2_000_000) return toast("Keep files under 2 MB for the demo", true);
  current = { name: file.name, data: await fileToDataUrl(file) };
  root.querySelector("#file-name").textContent = `${file.name} · ${(file.size / 1024).toFixed(1)} KB`;
  root.querySelector("#transform").disabled = false;
  root.querySelector("#ask").disabled = false;
  if (QUESTIONS[file.name]) root.querySelector("#question").value = QUESTIONS[file.name];
}

async function transform() {
  if (!current) return;
  const out = root.querySelector("#t-out");
  const btn = root.querySelector("#transform");
  btn.disabled = true;
  out.innerHTML = `<div class="muted small">Continuum is reading the file…</div>`;
  const data = await api("/app/transform", {
    method: "POST",
    body: { filename: current.name, data: current.data, budget: root.querySelector("#budget").value },
  });
  if (!root) return;
  btn.disabled = false;
  if (data.ok === false) {
    out.innerHTML = `<div class="banner bad">${h(errorText(data.error))}</div>`;
    return;
  }
  const r = data.result || {};
  const s = r.tokenStats || {};
  const reduction = typeof s.reduction === "number" ? Math.round(s.reduction * 100) : null;
  out.innerHTML = `
    <div class="row" style="margin-bottom:8px">
      <span class="chip c">${h(r.contextLabel || r.contextType || "unrecognised")}</span>
      ${r.transformer ? `<span class="chip">${h(r.transformer)}</span>` : ""}
      ${r.transformed ? "" : `<span class="chip warn">passed through unchanged</span>`}
    </div>
    <div class="stats" style="margin-bottom:10px">
      <div class="stat"><b>${s.before ?? "—"}</b><span>tokens as pasted</span></div>
      <div class="stat"><b>${s.after ?? "—"}</b><span>tokens after</span></div>
      <div class="stat"><b>${reduction == null ? "—" : `${reduction > 0 ? "−" : "+"}${Math.abs(reduction)}%`}</b><span>${reduction != null && reduction < 0 ? "larger (more structure)" : "saved"}</span></div>
      ${r.ambiguities?.length ? `<div class="stat"><b>${r.ambiguities.length}</b><span>things it refused to guess</span></div>` : ""}
    </div>
    <div class="out">${h(r.rendered || "")}</div>`;
}

async function ask() {
  if (!current) return;
  const out = root.querySelector("#a-out");
  const btn = root.querySelector("#ask");
  btn.disabled = true;
  out.innerHTML = `<div class="muted small">Asking through Continuum…</div>`;
  const data = await api("/app/ask-file", {
    method: "POST",
    body: { filename: current.name, data: current.data, question: root.querySelector("#question").value },
  });
  if (!root) return;
  btn.disabled = false;
  if (data.ok === false) {
    out.innerHTML = `<div class="banner bad">${h(errorText(data.error))}</div>`;
    return;
  }
  const chips = featureChips((data.calls || []).flatMap((c) => c.features || []));
  out.innerHTML = `<div class="result"><div class="answer">${md(data.answer)}</div>
    <div class="msg-meta" style="margin-top:8px">${modelChip(data.model)}${chips}</div>
    ${chips.includes("Context transformers") ? "" : `<p class="small muted" style="margin-top:6px">No transformer acted on this chat. Turn on <b>Context Transformers</b> (chat-context) in the Continuum console to have pasted data rewritten on the way to the model.</p>`}</div>`;
}
