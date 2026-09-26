import { useEffect, useMemo, useState } from "react";
import { api, portal } from "../api";
import { Pill, Segmented } from "../system/hub";
import { Input, Labelled, Select } from "../system/controls";
import { useToast } from "./ui";
import { dateTimeOf, timeOf } from "../system/time";

/**
 * The optimization controls that had an endpoint and no screen: the targets it
 * optimises against (set once in the wizard and never again), the active policy
 * bundle as recorded, feedback on individual answers, and starting a canary
 * for a specific candidate by hand.
 */

type Profile = {
  applicationName: string;
  goal: string;
  maxCostPerRequest: number;
  maxLatencyMs: number;
  allowedProviders: string[];
  preferredModelClasses: string[];
  mode: string;
};

const PROVIDERS = ["gemini", "groq", "openai", "mock"];
const MODES = ["BALANCED", "LOW_COST", "LOW_LATENCY", "HIGH_QUALITY", "SAFETY_FIRST"];

/* ------------------------------------------------------------------ *
 * Targets
 * ------------------------------------------------------------------ */

export function TargetsEditor({ profile, onSaved }: { profile: Profile | undefined; onSaved: () => void }) {
  const toast = useToast();
  const [p, setP] = useState<Profile | null>(profile ?? null);
  const [dirty, setDirty] = useState(false);
  const [busy, setBusy] = useState(false);
  useEffect(() => {
    if (!dirty && profile) setP(profile);
  }, [profile, dirty]);
  if (!p) return null;

  const set = (patch: Partial<Profile>) => {
    setDirty(true);
    setP({ ...p, ...patch });
  };
  const save = async () => {
    setBusy(true);
    try {
      await portal.autopilot.setProfile(p);
      toast("Targets saved — the next proposal is judged against them", "success");
      setDirty(false);
      onSaved();
    } catch (e: any) {
      toast(e?.message ?? "Targets were not saved", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-4">
      <div className="grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Labelled label="Application">
          <Input value={p.applicationName} onChange={(e) => set({ applicationName: e.target.value })} />
        </Labelled>
        <Labelled label="Objective">
          <Select value={p.mode} onChange={(e) => set({ mode: e.target.value })}>
            {MODES.map((m) => (
              <option key={m} value={m}>
                {m.toLowerCase().replace(/_/g, " ")}
              </option>
            ))}
          </Select>
        </Labelled>
        <Labelled label="Max $ per request">
          <Input type="number" min={0} step="0.001" value={p.maxCostPerRequest} onChange={(e) => set({ maxCostPerRequest: Number(e.target.value) })} />
        </Labelled>
        <Labelled label="Max latency (ms)">
          <Input type="number" min={100} step={100} value={p.maxLatencyMs} onChange={(e) => set({ maxLatencyMs: Number(e.target.value) })} />
        </Labelled>
      </div>
      <Labelled label="Goal, in your words">
        <Input value={p.goal} onChange={(e) => set({ goal: e.target.value })} />
      </Labelled>
      <div role="group" aria-label="Providers it may use">
        <div className="micro mb-1.5">Providers it may use</div>
        <div className="flex flex-wrap gap-1.5">
          {[...new Set([...PROVIDERS, ...p.allowedProviders])].map((prov) => {
            const on = p.allowedProviders.includes(prov);
            return (
              <button
                key={prov}
                aria-pressed={on}
                onClick={() => set({ allowedProviders: on ? p.allowedProviders.filter((x) => x !== prov) : [...p.allowedProviders, prov] })}
                className="rounded-full border px-3 py-1 text-[12px] font-medium transition-colors"
                style={on ? { borderColor: "transparent", background: "var(--wash-ok)", color: "var(--state-healthy-ink)" } : { borderColor: "rgb(var(--card-edge))", color: "var(--text-2)" }}
              >
                {on ? "✓ " : ""}
                {prov}
              </button>
            );
          })}
        </div>
      </div>
      <div className="flex items-center justify-end gap-3">
        {p.allowedProviders.length === 0 && <span className="text-[12px]" style={{ color: "var(--state-critical-ink)" }}>Allow at least one provider</span>}
        {dirty && (
          <button onClick={() => { setDirty(false); setP(profile ?? null); }} className="text-[12px] text-slate-500 hover:text-slate-300">
            Discard
          </button>
        )}
        <button
          onClick={save}
          disabled={!dirty || busy || p.allowedProviders.length === 0}
          className="rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-3.5 py-1.5 text-[12.5px] font-medium text-white hover:opacity-90 disabled:opacity-50"
        >
          {busy ? "Saving…" : "Save targets"}
        </button>
      </div>
    </div>
  );
}

/* ------------------------------------------------------------------ *
 * Active bundle, as recorded
 * ------------------------------------------------------------------ */

export function ActiveBundle() {
  const [b, setB] = useState<any | null>(null);
  useEffect(() => {
    portal.autopilot.activeBundle().then(setB).catch(() => setB(null));
  }, []);
  if (!b?.entity) return null;
  const e = b.entity;
  return (
    <details className="mt-4 rounded-[var(--r-md)] border border-edge/70 px-3 py-2">
      <summary className="cursor-pointer select-none text-[12.5px] text-slate-300">
        Bundle v{e.version} <span className="text-slate-500">· {String(e.source ?? "").toLowerCase()} · since {dateTimeOf(e.createdAt)}</span>
      </summary>
      {e.notes && <p className="mt-2 text-[12px] text-slate-400">{e.notes}</p>}
      <pre className="mt-2 max-h-64 overflow-auto rounded-[var(--r-md)] p-2 font-mono text-[11px] text-slate-300" style={{ background: "var(--wash-mute)" }}>
        {JSON.stringify(b.policy, null, 2)}
      </pre>
    </details>
  );
}

/* ------------------------------------------------------------------ *
 * Feedback on an answer
 * ------------------------------------------------------------------ */

const SCORES = [
  { value: "-1", label: "Wrong" },
  { value: "-0.5", label: "Weak" },
  { value: "0", label: "OK" },
  { value: "0.5", label: "Good" },
  { value: "1", label: "Great" },
];

export function FeedbackForm() {
  const toast = useToast();
  const [requests, setRequests] = useState<any[]>([]);
  const [ref, setRef] = useState("");
  const [score, setScore] = useState("0.5");
  const [comment, setComment] = useState("");
  const [busy, setBusy] = useState(false);
  const [sent, setSent] = useState<{ ref: string; score: number }[]>([]);

  useEffect(() => {
    api
      .get<any[]>("/api/gateway/requests?limit=25")
      .then((r) => {
        setRequests(r);
        if (r[0]) setRef(r[0].traceId ?? String(r[0].id));
      })
      .catch(() => {});
  }, []);

  const label = useMemo(() => new Map(requests.map((r) => [r.traceId ?? String(r.id), r])), [requests]);

  const send = async () => {
    if (!ref) return;
    setBusy(true);
    try {
      await portal.autopilot.feedback({ requestRef: ref, score: Number(score), comment: comment.trim() || undefined });
      setSent((s) => [{ ref, score: Number(score) }, ...s].slice(0, 5));
      setComment("");
      toast("Feedback recorded — it weighs on the next proposal", "success");
    } catch (e: any) {
      toast(e?.message ?? "Feedback was not recorded", "error");
    } finally {
      setBusy(false);
    }
  };

  return (
    <div className="space-y-3">
      <p className="text-[12px] text-slate-500">Metrics say whether an answer was fast and cheap. Only you can say whether it was right.</p>
      <Labelled label="Which answer">
        {requests.length ? (
          <Select value={ref} onChange={(e) => setRef(e.target.value)}>
            {requests.map((r) => {
              const k = r.traceId ?? String(r.id);
              return (
                <option key={k} value={k}>
                  {timeOf(r.createdAt)} · {r.chosenProvider}/{r.chosenModel} · {r.latencyMs} ms {r.success ? "" : "· failed"}
                </option>
              );
            })}
          </Select>
        ) : (
          <Input value={ref} onChange={(e) => setRef(e.target.value)} placeholder="request or trace id" />
        )}
      </Labelled>
      <div>
        <div className="micro mb-1.5">How was it</div>
        <Segmented value={score} onChange={setScore} options={SCORES} />
      </div>
      <Labelled label="Why (optional)">
        <Input value={comment} onChange={(e) => setComment(e.target.value)} placeholder="e.g. missed the refund policy" maxLength={500} />
      </Labelled>
      <div className="flex items-center justify-end">
        <button
          onClick={send}
          disabled={!ref || busy}
          className="rounded-[var(--r-md)] bg-[color:var(--accent-strong)] px-3.5 py-1.5 text-[12.5px] font-medium text-white hover:opacity-90 disabled:opacity-50"
        >
          {busy ? "Sending…" : "Send feedback"}
        </button>
      </div>
      {sent.length > 0 && (
        <ul className="space-y-1 border-t border-edge/60 pt-2 text-[12px] text-slate-400">
          {sent.map((s, i) => (
            <li key={i} className="flex items-center gap-2">
              <Pill tone={s.score > 0 ? "ok" : s.score < 0 ? "bad" : "mute"}>{SCORES.find((x) => Number(x.value) === s.score)?.label}</Pill>
              <span className="truncate font-mono text-[11px]">{label.get(s.ref)?.chosenModel ?? s.ref}</span>
            </li>
          ))}
        </ul>
      )}
    </div>
  );
}
