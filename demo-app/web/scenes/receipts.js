// Receipts & photos: a Continuum pipeline with the store's own specialist, and vision.
import { h, md, api, state, toast, errorText, fileToDataUrl, sample, pct, featureChips, modelChip } from "../lib.js";

const SAMPLES = [
  ["receipt-BC-1042.pdf", "Receipt, PDF (valid)"],
  ["receipt-BC-1042.txt", "Receipt, text (valid)"],
  ["receipt-BC-3001-wrong-total.txt", "Receipt with the wrong total"],
  ["receipt-BC-2001-old.txt", "Receipt outside the return window"],
  ["damaged-mug.png", "A photo (no text to read)"],
];

let root = null;
let receiptFile = null;
let photoUrl = null;

export function mount(el) {
  root = el;
  el.innerHTML = `
    <div class="scene-head">
      <div>
        <h1>Receipts &amp; photos</h1>
        <p>A receipt goes to a Continuum <b>pipeline</b>: Continuum first calls this store's own receipt reader (a
          custom <b>specialist</b>, reached with a token Continuum keeps), turns what it found into evidence with
          stated confidence, then asks a model — which is told it has not seen the file. A photo goes straight to a
          vision model through the gateway.</p>
      </div>
    </div>
    <div class="grid halves">
      <section class="card">
        <div class="card-head"><h2>Check a receipt</h2><span class="sub">pipeline · specialist · model</span></div>
        <div class="stack">
          <label class="drop" id="receipt-drop">
            <input type="file" id="receipt-file" accept=".pdf,.txt,.png,.jpg,.jpeg,text/plain,application/pdf,image/*">
            <span id="receipt-name">Drop a receipt (PDF, text or image) or click to choose</span>
          </label>
          <div class="row small"><span class="muted">Samples:</span>${SAMPLES.map(([f, l]) => `<button type="button" class="btn small" data-sample="${h(f)}">${h(l)}</button>`).join("")}</div>
          <label class="field"><span>Question for the pipeline</span>
            <input type="text" id="receipt-prompt" value="Is this receipt valid for a refund?"></label>
          <div class="row"><span class="spacer"></span><button class="btn primary" id="receipt-go" disabled>Check receipt</button></div>
          <div id="receipt-out"></div>
        </div>
      </section>
      <section class="card">
        <div class="card-head"><h2>Ask about a photo</h2><span class="sub">vision through the gateway</span></div>
        <div class="stack">
          <label class="drop" id="photo-drop">
            <input type="file" id="photo-file" accept="image/*">
            <span id="photo-name">Drop a product photo or click to choose</span>
          </label>
          <div class="row small"><span class="muted">Sample:</span><button type="button" class="btn small" id="photo-sample">Damaged mug</button></div>
          <img id="photo-preview" class="preview-img" alt="" hidden>
          <label class="field"><span>Question</span>
            <input type="text" id="photo-q" value="Is this item damaged, and would it qualify for a refund?"></label>
          <div class="row"><span class="spacer"></span><button class="btn primary" id="photo-go" disabled>Ask</button></div>
          <div id="photo-out"></div>
        </div>
      </section>
    </div>`;
  const q = (s) => el.querySelector(s);
  q("#receipt-file").addEventListener("change", (e) => pickReceipt(e.target.files[0]));
  q("#photo-file").addEventListener("change", (e) => pickPhoto(e.target.files[0]));
  for (const [id, fn] of [["#receipt-drop", pickReceipt], ["#photo-drop", pickPhoto]]) {
    const zone = q(id);
    zone.addEventListener("dragover", (e) => { e.preventDefault(); zone.classList.add("over"); });
    zone.addEventListener("dragleave", () => zone.classList.remove("over"));
    zone.addEventListener("drop", (e) => { e.preventDefault(); zone.classList.remove("over"); fn(e.dataTransfer.files[0]); });
  }
  el.querySelectorAll("[data-sample]").forEach((b) =>
    b.addEventListener("click", async () => pickReceipt(await sample(b.dataset.sample))));
  q("#photo-sample").addEventListener("click", async () => pickPhoto(await sample("damaged-mug.png")));
  q("#receipt-go").addEventListener("click", checkReceipt);
  q("#photo-go").addEventListener("click", askPhoto);
}

export function unmount() {
  root = null;
}

function pickReceipt(file) {
  if (!file || !root) return;
  receiptFile = file;
  root.querySelector("#receipt-name").textContent = `${file.name} · ${(file.size / 1024).toFixed(1)} KB`;
  root.querySelector("#receipt-go").disabled = false;
}

async function pickPhoto(file) {
  if (!file || !root) return;
  if (!file.type.startsWith("image/")) return toast("Choose an image file", true);
  photoUrl = await fileToDataUrl(file);
  const img = root.querySelector("#photo-preview");
  img.src = photoUrl;
  img.alt = file.name;
  img.hidden = false;
  root.querySelector("#photo-name").textContent = `${file.name} · ${(file.size / 1024).toFixed(1)} KB`;
  root.querySelector("#photo-go").disabled = false;
}

async function checkReceipt() {
  if (!receiptFile) return;
  const btn = root.querySelector("#receipt-go");
  const out = root.querySelector("#receipt-out");
  btn.disabled = true;
  out.innerHTML = `<div class="muted small">Continuum is running the pipeline…</div>`;
  const data = await api("/app/receipt", {
    method: "POST",
    body: { filename: receiptFile.name, data: await fileToDataUrl(receiptFile), prompt: root.querySelector("#receipt-prompt").value },
  });
  if (!root) return;
  btn.disabled = false;
  if (data.ok === false) {
    out.innerHTML = `<div class="banner bad">${h(errorText(data.error))}</div>`;
    return;
  }
  const r = data.result || {};
  const trace = (r.trace || []).map(traceLine).join("");
  const conf = typeof r.evidenceConfidence === "number" ? r.evidenceConfidence : null;
  out.innerHTML = `
    <div class="stats" style="margin:4px 0 10px">
      <div class="stat"><b>${r.findings ?? 0}</b><span>findings from the reader</span></div>
      <div class="stat"><b>${conf == null ? (r.anythingFound ? "unscored" : "—") : pct(conf)}</b><span>evidence confidence</span></div>
      <div class="stat"><b>${r.analysisRan ? "yes" : "no"}</b><span>specialist ran</span></div>
      <div class="stat"><b>${h(r.latencyMs ?? "—")}<small> ms</small></b><span>end to end</span></div>
    </div>
    ${conf != null ? `<div class="meter" aria-hidden="true"><i style="width:${Math.round(conf * 100)}%"></i></div>` : ""}
    <div class="result" style="margin-top:10px"><div class="answer">${md(r.response || "(no answer)")}</div>
      <div class="msg-meta" style="margin-top:8px">${modelChip(r.model)}
        ${featureChips((data.calls || []).flatMap((c) => c.features || []))}
        ${r.policy?.action ? `<span class="chip c" title="Continuum's confidence policy">policy: ${h(String(r.policy.action).toLowerCase())}</span>` : ""}
        ${r.traceId ? `<span class="chip" title="Find this run under Pipelines → traces in the console">trace ${h(String(r.traceId).slice(0, 8))}</span>` : ""}</div></div>
    ${trace ? `<details style="margin-top:10px"><summary class="small">How Continuum got there (${r.trace.length} steps)</summary><ol class="trace">${trace}</ol></details>` : ""}`;
}

function traceLine(t) {
  if (!t || typeof t !== "object") return `<li>${h(t)}</li>`;
  const name = t.label || t.stage || t.step || t.name || t.kind || "step";
  const detail = t.detail || t.summary || t.reason || t.status || "";
  const ms = t.latencyMs != null ? ` · ${t.latencyMs} ms` : "";
  return `<li><b>${h(name)}</b>${detail ? ` — ${h(detail)}` : ""}<span class="muted">${h(ms)}</span></li>`;
}

async function askPhoto() {
  if (!photoUrl) return;
  const btn = root.querySelector("#photo-go");
  const out = root.querySelector("#photo-out");
  btn.disabled = true;
  out.innerHTML = `<div class="muted small">Sending the photo through Continuum…</div>`;
  const data = await api("/app/photo", {
    method: "POST",
    body: { image: photoUrl, question: root.querySelector("#photo-q").value, customerId: state.customer },
  });
  if (!root) return;
  btn.disabled = false;
  if (data.ok === false) {
    out.innerHTML = `<div class="banner bad">${h(errorText(data.error))}</div>`;
    return;
  }
  out.innerHTML = `<div class="result"><div class="answer">${md(data.answer)}</div>
    <div class="msg-meta" style="margin-top:8px">${modelChip(data.model)}
      ${featureChips((data.calls || []).flatMap((c) => c.features || []))}</div></div>`;
}
