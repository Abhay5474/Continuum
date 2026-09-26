import { Gauge } from "../system/viz";
import { Pill, toneInk, type Tone } from "../system/hub";
import DataView from "../system/DataView";

/**
 * A gateway response, drawn.
 *
 * <p>The raw reply is a JSON object whose most useful part — the verification
 * summary, when the Verification Engine is on — arrives as indented text: each
 * claim, its prior, and every verifier's validity with "supports" or
 * "weakens". Read as text it is a wall; drawn, it is a confidence dial and a
 * row of bars per claim, green for evidence for and red for against. The raw
 * object stays one click away.
 */

type Verifier = { name: string; validity: number; supports: boolean };
type Claim = { text: string; p: number; prior?: number; verifiers: Verifier[] };

export function parseVerification(text: string) {
  const i = text.indexOf("— Verification summary");
  if (i < 0) return { answer: text.trim(), summary: null };
  const answer = text.slice(0, i).trim();
  const body = text.slice(i);
  const head = body.match(/confidence ([\d.]+)%, uncertainty (\w+)/);
  const claims: Claim[] = [];
  const seen = new Set<string>();
  for (const block of body.split(/\n(?=[✔✘✗x!] ?Claim)/u).slice(1)) {
    const m = block.match(/Claim \d+ \[P=([\d.]+)\]:\s*([^\n]*)/);
    if (!m) continue;
    const prior = block.match(/prior ([\d.]+)/);
    const verifiers = [...block.matchAll(/· ([A-Z_]+): validity ([\d.]+) \((supports|weakens)\)/g)].map((v) => ({
      name: v[1].toLowerCase().replace(/_/g, " "),
      validity: Number(v[2]),
      supports: v[3] === "supports",
    }));
    const key = `${m[2]}|${m[1]}|${verifiers.map((v) => v.name + v.validity).join()}`;
    if (seen.has(key)) continue; // the engine can list one claim twice
    seen.add(key);
    claims.push({ text: m[2].trim(), p: Number(m[1]), prior: prior ? Number(prior[1]) : undefined, verifiers });
  }
  return {
    answer,
    summary: head ? { confidence: Number(head[1]) / 100, uncertainty: head[2], claims } : null,
  };
}

export default function GatewayResult({ out }: { out: any }) {
  if (!out || out.error) return <DataView value={out} />;
  const { answer, summary } = parseVerification(String(out.response ?? ""));
  const unc: Tone = summary?.uncertainty === "LOW" ? "ok" : summary?.uncertainty === "MEDIUM" ? "warn" : "bad";
  return (
    <div className="space-y-4">
      <div className="flex flex-wrap gap-1.5">
        <Pill tone="accent">
          {out.provider} · {out.model}
        </Pill>
        {out.latency != null && <Pill tone="info">{out.latency} ms</Pill>}
        {out.tokens != null && <Pill tone="mute">{out.tokens} tokens</Pill>}
        {out.cost != null && <Pill tone="mute">${Number(out.cost).toFixed(6)}</Pill>}
        <Pill tone={out.failovers ? "warn" : "ok"}>{out.failovers ? `${out.failovers} failover${out.failovers > 1 ? "s" : ""}` : "no failover"}</Pill>
      </div>

      <div>
        <div className="micro mb-1.5">Answer</div>
        <div className="whitespace-pre-wrap rounded-xl border border-edge/70 bg-slate-500/[0.06] px-3 py-2.5 text-[13px] leading-relaxed text-slate-200">
          {answer || <span className="text-slate-500">(empty reply)</span>}
        </div>
      </div>

      {summary && (
        <div className="grid gap-4 sm:grid-cols-[auto_1fr]">
          <div className="flex flex-col items-center gap-2">
            <Gauge value={summary.confidence} max={1} label="Verified confidence" display={`${Math.round(summary.confidence * 100)}%`} invert warnAt={0.3} badAt={0.5} size={130} />
            <Pill tone={unc}>uncertainty {summary.uncertainty.toLowerCase()}</Pill>
          </div>
          <div className="space-y-3">
            {summary.claims.map((c, ci) => (
              <div key={ci} className="rise-in rounded-xl border border-edge/70 p-3" style={{ animationDelay: `${ci * 80}ms` }}>
                <div className="flex flex-wrap items-baseline justify-between gap-2">
                  <span className="text-[12.5px] font-medium text-slate-200">{c.text || `Claim ${ci + 1}`}</span>
                  <span className="readout text-[11px] text-slate-400">
                    {c.prior != null && <>prior {c.prior.toFixed(2)} → </>}P {c.p.toFixed(2)}
                  </span>
                </div>
                <ul className="mt-2 space-y-1.5">
                  {c.verifiers.map((v, vi) => (
                    <li key={vi} className="grid grid-cols-[9rem_1fr_3rem] items-center gap-2 text-[11px]">
                      <span className="truncate text-slate-400">{v.name}</span>
                      <span className="h-1.5 overflow-hidden rounded-full bg-edge">
                        <span
                          className="grow-x block h-full rounded-full"
                          style={{ width: `${Math.max(3, v.validity * 100)}%`, background: toneInk(v.supports ? "ok" : "bad"), animationDelay: `${ci * 80 + vi * 60}ms` }}
                        />
                      </span>
                      <span className="readout text-right" style={{ color: toneInk(v.supports ? "ok" : "bad") }}>
                        {v.supports ? "+" : "−"}
                        {v.validity.toFixed(2)}
                      </span>
                    </li>
                  ))}
                </ul>
              </div>
            ))}
            <p className="text-[11px] text-slate-500">Green bars support the claim, red bars weaken it; length is how valid that check found it.</p>
          </div>
        </div>
      )}

      <details className="text-[12px] text-slate-400">
        <summary className="cursor-pointer select-none text-slate-300">Raw response</summary>
        <div className="mt-2">
          <DataView value={out} />
        </div>
      </details>
    </div>
  );
}
