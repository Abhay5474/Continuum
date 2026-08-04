import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { Micro, Switch } from "../system/primitives";
import { ErrorState, useToast } from "../components/ui";
import { dateTimeOf } from "../system/time";
import { BeforeAfter, ChartFrame, Donut } from "../system/charts";
import {
  Dot,
  Empty,
  Hop,
  KindMark,
  Rail,
  Route,
  Row,
  RowSkeleton,
  Segmented,
  Stage,
  Stat,
  Stats,
} from "../system/hub";

/**
 * Prompt Guard — the two things that sit between a prompt and the model.
 *
 * <p>Both shipped working: the firewall redacts PII and blocks injection
 * attempts, compression trims a prompt before it is billed. They are on one page
 * because they are one decision: what happens to a prompt on its way out. Both
 * are per-account and off by default; while off the prompt is forwarded verbatim.
 *
 * <p><b>On the shape of this screen.</b> Two switches in a box, then two bands
 * of four stat cards, then a table. Nothing on it said where in a request these
 * two things happen, which is the only thing someone arriving here needs to
 * know. So the page opens with the path — your prompt, the firewall, the
 * compressor, the provider — with the stage you are looking at lit.
 */

type Status = { firewallEnabled: boolean; compressionEnabled: boolean };

/**
 * {@code PROMPT_INJECTION} is a constant name, not a thing to show a reader.
 *
 * <p>Acronyms are left alone: sentence-casing {@code SSN} gives "Ssn", which is
 * a word nobody has ever read.
 */
const ACRONYMS = new Set(["SSN", "PII", "URL", "IP", "API", "IBAN", "NHS", "PAN"]);

function humanise(raw: string) {
  return String(raw ?? "")
    .split("_")
    .map((w, i) =>
      ACRONYMS.has(w.toUpperCase())
        ? w.toUpperCase()
        : i === 0
          ? w.charAt(0).toUpperCase() + w.slice(1).toLowerCase()
          : w.toLowerCase()
    )
    .join(" ");
}

type Which = "firewall" | "compression";

export default function PromptGuard() {
  const toast = useToast();
  const [status, setStatus] = useState<Status | null>(null);
  const [fw, setFw] = useState<any | null>(null);
  const [cp, setCp] = useState<any | null>(null);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState<Which | null>(null);
  const [which, setWhich] = useState<Which>("firewall");

  const load = useCallback(async () => {
    try {
      const [s, f, c] = await Promise.all([
        portal.v8.status(),
        portal.v8.firewallProfile(),
        portal.v8.compressionProfile(),
      ]);
      setStatus(s);
      setFw(f);
      setCp(c);
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Could not load prompt guard settings.");
    }
  }, []);

  useEffect(() => {
    load();
    const t = setInterval(load, 6000);
    return () => clearInterval(t);
  }, [load]);

  const toggle = async (target: Which, next: boolean) => {
    setBusy(target);
    // Reflect the intent immediately; the reload below is the source of truth.
    setStatus((s) => (s ? { ...s, [`${target}Enabled`]: next } as Status : s));
    try {
      const s =
        target === "firewall" ? await portal.v8.setFirewall(next) : await portal.v8.setCompression(next);
      setStatus(s);
      toast(`${target === "firewall" ? "Firewall" : "Compression"} ${next ? "enabled" : "disabled"}`);
    } catch (e: any) {
      await load();
      toast(e?.message ?? "Could not change that setting", "error");
    } finally {
      setBusy(null);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  const fwOn = status?.firewallEnabled ?? false;
  const cpOn = status?.compressionEnabled ?? false;
  const acted = (fw?.piiRedacted ?? 0) + (fw?.injectionsBlocked ?? 0) + (fw?.injectionsFlagged ?? 0);

  return (
    <div className="page-enter">
      <header>
        <h1 className="text-[22px] font-semibold tracking-tight text-slate-100">Prompt Guard</h1>
        <p className="mt-1 max-w-2xl text-[13px] leading-relaxed text-slate-500">
          What happens to a prompt between your request and the provider. Both controls are
          per-account and off by default — while off, the prompt is forwarded verbatim.
        </p>
      </header>

      <div className="mt-6">
        <Route>
          <Stage label="your prompt" sub="as your app sent it" />
          <Hop />
          <Stage
            label="Firewall"
            sub={fwOn ? (acted > 0 ? `${acted} acted on` : "watching") : "off"}
            state={fwOn ? "on" : "off"}
            mark={<KindMark kind="moderation" size={26} />}
            selected={which === "firewall"}
            onClick={() => setWhich("firewall")}
          />
          <Hop label="redacted" />
          <Stage
            label="Compression"
            sub={cpOn ? `${Math.round((cp?.reductionPct ?? 0) * 100)}% smaller` : "off"}
            state={cpOn ? "on" : "off"}
            mark={<KindMark kind="extraction" size={26} />}
            selected={which === "compression"}
            onClick={() => setWhich("compression")}
          />
          <Hop label="billed" />
          <Stage label="the provider" sub="sees only what got through" />
        </Route>
      </div>

      <div className="mt-7">
        <Segmented<Which>
          value={which}
          onChange={setWhich}
          options={[
            {
              value: "firewall",
              label: "Firewall",
              badge: fwOn ? (
                <span className="h-1.5 w-1.5 rounded-full" style={{ background: "var(--accent)" }} aria-hidden />
              ) : undefined,
            },
            {
              value: "compression",
              label: "Compression",
              badge: cpOn ? (
                <span className="h-1.5 w-1.5 rounded-full" style={{ background: "var(--accent)" }} aria-hidden />
              ) : undefined,
            },
          ]}
        />
      </div>

      <div className="mt-6">
        {which === "firewall" ? (
          <Firewall
            fw={fw}
            on={fwOn}
            busy={busy === "firewall" || status === null}
            onToggle={(next) => toggle("firewall", next)}
          />
        ) : (
          <Compression
            cp={cp}
            on={cpOn}
            busy={busy === "compression" || status === null}
            onToggle={(next) => toggle("compression", next)}
          />
        )}
      </div>
    </div>
  );
}

/* -------------------------------------------------------------------------- *
 * Firewall
 * -------------------------------------------------------------------------- */

function Firewall({
  fw,
  on,
  busy,
  onToggle,
}: {
  fw: any | null;
  on: boolean;
  busy: boolean;
  onToggle: (next: boolean) => void;
}) {
  const redacted = fw?.piiRedacted ?? 0;
  const blocked = fw?.injectionsBlocked ?? 0;
  const flagged = fw?.injectionsFlagged ?? 0;
  const events = fw?.events ?? 0;
  // Whatever the firewall saw and did not act on. Named rather than left as the
  // gap between two numbers, because "it looked and was happy" is a result.
  const passed = Math.max(0, events - redacted - blocked - flagged);

  return (
    <div className="space-y-7">
      <Switch
        label="Prompt firewall"
        hint="Redacts personal data and blocks known injection patterns before the prompt leaves the engine. Every action is recorded below."
        checked={on}
        busy={busy}
        onChange={onToggle}
      />

      {fw === null ? (
        <RowSkeleton rows={3} />
      ) : (
        <>
          <Stats>
            <Stat label="Prompts seen" value={events} />
            <Stat label="PII redacted" value={redacted} tone={redacted ? "ok" : undefined} />
            <Stat
              label="Injections blocked"
              value={blocked}
              tone={blocked ? "bad" : undefined}
              hint="The prompt never reached the provider."
            />
            <Stat
              label="Flagged"
              value={flagged}
              tone={flagged ? "warn" : undefined}
              hint="Suspicious, but sent — recorded so you can decide whether to tighten the rule."
            />
          </Stats>

          {/* What the firewall did with what it saw. A donut rather than four
              more numbers: the useful question is the proportion acted on, and
              a proportion is what a donut is for. */}
          {events > 0 && (
            <ChartFrame
              title="What the firewall did"
              caption="Every prompt it saw, by outcome. A prompt can be counted once only."
              unit="prompts"
              data={[
                { key: "passed", label: "Passed through", value: passed },
                { key: "pii", label: "PII redacted", value: redacted },
                { key: "flagged", label: "Flagged, sent", value: flagged },
                { key: "blocked", label: "Blocked", value: blocked },
              ]}
            >
              <Donut
                data={[
                  { key: "passed", label: "Passed through", value: passed },
                  { key: "pii", label: "PII redacted", value: redacted },
                  { key: "flagged", label: "Flagged, sent", value: flagged },
                  { key: "blocked", label: "Blocked", value: blocked },
                ]}
                centerValue={String(events)}
                centerLabel="prompts"
              />
            </ChartFrame>
          )}

          <section>
            <h2 className="text-[13px] font-semibold tracking-tight text-slate-200">Recent activity</h2>
            <div className="mt-3">
              {fw.recent?.length ? (
                <Rail>
                  {fw.recent.map((r: any, i: number) => {
                    const bad = r.action === "BLOCKED";
                    const injection = String(r.category).includes("INJECTION");
                    return (
                      <Row
                        key={r.id ?? i}
                        mark={<KindMark kind={injection ? "moderation" : "extraction"} size={26} />}
                        title={humanise(r.category)}
                        status={
                          <Dot
                            tone={bad ? "bad" : r.action === "FLAGGED" ? "warn" : "ok"}
                            label={String(r.action).toLowerCase()}
                          />
                        }
                        // One line, not three. This is a log: thirty of them
                        // should fit on a screen, and a three-line row for four
                        // short values turns thirty into a scroll.
                        subtitle={`${String(r.direction).toLowerCase()} · ${r.matchCount} ${
                          r.matchCount === 1 ? "match" : "matches"
                        } · ${dateTimeOf(r.createdAt)}`}
                      />
                    );
                  })}
                </Rail>
              ) : (
                <Empty
                  title="Nothing intercepted yet"
                  hint={
                    on
                      ? "The firewall is on and has not had to act on a prompt."
                      : "Turn the firewall on and send a request through the gateway."
                  }
                />
              )}
            </div>
          </section>
        </>
      )}
    </div>
  );
}

/* -------------------------------------------------------------------------- *
 * Compression
 * -------------------------------------------------------------------------- */

function Compression({
  cp,
  on,
  busy,
  onToggle,
}: {
  cp: any | null;
  on: boolean;
  busy: boolean;
  onToggle: (next: boolean) => void;
}) {
  const saved = cp?.tokensSaved ?? 0;
  const pct = cp?.reductionPct ?? 0;
  // The API reports what was removed and by how much; the original size follows
  // from those two rather than being guessed. With no reduction there is nothing
  // to draw, and drawing a made-up baseline would be worse than an empty state.
  const before = pct > 0 ? Math.round(saved / pct) : 0;
  const after = Math.max(0, before - saved);

  return (
    <div className="space-y-7">
      <Switch
        label="Prompt compression"
        hint="Trims redundant context before the prompt is billed, protecting code, quotes and identifiers from being touched."
        checked={on}
        busy={busy}
        onChange={onToggle}
      />

      {cp === null ? (
        <RowSkeleton rows={2} />
      ) : (
        <>
          <Stats>
            <Stat label="Requests" value={cp.requests ?? 0} />
            <Stat label="Tokens saved" value={saved} tone={saved ? "ok" : undefined} />
            <Stat
              label="Reduction"
              value={Math.round(pct * 100)}
              unit="%"
              tone={pct ? "ok" : undefined}
            />
            <Stat
              label="Protected spans"
              value={cp.protectedSpans ?? 0}
              hint="Code, quotes and identifiers, left untouched by design."
            />
          </Stats>

          {before > 0 ? (
            <ChartFrame
              title="Tokens billed, before and after"
              caption="Across every compressed request on this account. Protected spans are counted in both bars — they are never removed."
              unit="tokens"
              data={[
                { key: "before", label: "Before", value: before },
                { key: "after", label: "After", value: after },
              ]}
            >
              <BeforeAfter before={before} after={after} unit=" tok" goodDirection="down" />
            </ChartFrame>
          ) : (
            <Empty
              title="Nothing compressed yet"
              hint={
                on
                  ? "Compression is on. It has not yet found a prompt with enough redundancy to be worth trimming."
                  : "Turn compression on and send a request through the gateway."
              }
            />
          )}

          <p className="max-w-2xl text-xs leading-relaxed text-slate-600">
            <Micro>What is never touched</Micro>
            Code blocks, quoted text and anything that looks like an identifier are protected spans:
            they are left byte-for-byte intact. A compressor that shortened an API key or a stack
            trace would save tokens and break the request, which is not a trade worth making.
          </p>
        </>
      )}
    </div>
  );
}
