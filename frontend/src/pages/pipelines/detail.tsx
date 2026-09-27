import type { ReactNode } from "react";
import { Dot, Facts, Ghost, KindMark, Segmented, kindOf } from "../../system/hub";
import { Area, INPUT_LABEL, Pipeline, RoutingStep, Specialist } from "./types";
import { Endpoint } from "./endpoint";
import { RoutingControls } from "./routing";
import { PolicyControls } from "./policy";
import { VerificationControls } from "./verification";
import { TryIt } from "./chain";

/* -------------------------------------------------------------------------- *
 * The selected pipeline
 * -------------------------------------------------------------------------- */

export function Detail({
  p,
  specialists,
  busy,
  area,
  onArea,
  onEnable,
  onDelete,
  onRan,
  onPolicy,
}: {
  p: Pipeline;
  specialists: Specialist[];
  busy: boolean;
  area: Area;
  onArea: (a: Area) => void;
  onEnable: (next: boolean) => void;
  onDelete: () => void;
  onRan: () => void;
  onPolicy: (body: {
    policyEnabled?: boolean; strongThreshold?: number; weakThreshold?: number;
    declineOnNoEvidence?: boolean; routingEnabled?: boolean; routing?: RoutingStep[];
    verificationMode?: string;
  }) => void;
}) {
  return (
    <div>
      <div className="flex flex-wrap items-start justify-between gap-x-6 gap-y-3">
        <div className="min-w-0">
          <h2 className="flex items-center gap-2.5 text-[17px] font-semibold tracking-tight text-slate-100">
            {p.name}
            <Dot tone={p.enabled ? "ok" : "idle"} label={p.enabled ? "accepting requests" : "off"} />
          </h2>
          {p.description && <p className="mt-1 text-[12.5px] text-slate-500">{p.description}</p>}
          <div className="mt-2">
            <Facts
              items={[
                { k: "accepts", v: INPUT_LABEL[p.inputKind] ?? p.inputKind },
                { k: "runs", v: p.runs },
                { k: "policy", v: p.policyEnabled ? "on" : "off" },
                { k: "verification", v: p.verificationMode.toLowerCase() },
              ]}
            />
          </div>
        </div>
        <Ghost tone="danger" onClick={onDelete} disabled={busy}>
          Remove
        </Ghost>
      </div>

      {/* The chain, drawn. This is the answer to "what is this thing" and it
          belongs above every control, not inside one of them. */}
      <div className="mt-5">
        <ChainDiagram p={p} specialists={specialists} />
      </div>

      <div className="mt-6">
        <Segmented<Area>
          value={area}
          onChange={onArea}
          options={[
            { value: "run", label: "Run it" },
            { value: "chain", label: "Chain" },
            {
              value: "guards",
              label: "Guards",
              badge:
                p.policyEnabled || p.verificationMode !== "OFF" ? (
                  <span
                    className="h-1.5 w-1.5 rounded-full"
                    style={{ background: "var(--accent)" }}
                    aria-hidden
                  />
                ) : undefined,
            },
            { value: "endpoint", label: "Endpoint" },
          ]}
        />
      </div>

      <div className="mt-5">
        {area === "run" && <TryIt pipeline={p} onRan={onRan} />}
        {area === "chain" && (
          <RoutingControls p={p} specialists={specialists} busy={busy} onChange={onPolicy} />
        )}
        {area === "guards" && (
          <div className="space-y-7">
            <PolicyControls p={p} busy={busy} onChange={onPolicy} />
            <VerificationControls p={p} busy={busy} onChange={onPolicy} />
          </div>
        )}
        {area === "endpoint" && <Endpoint p={p} busy={busy} onEnable={onEnable} />}
      </div>
    </div>
  );
}

/**
 * Input, the specialists in order, the model, the answer.
 *
 * <p>The old page said this in a sentence — {@code "detector → classifier"} —
 * next to the description. A sentence cannot show that the model never sees the
 * image, which is the single most important fact about the architecture, so this
 * draws it: the input terminates at the specialists, and only their findings
 * carry on to the right.
 */
function ChainDiagram({ p, specialists }: { p: Pipeline; specialists: Specialist[] }) {
  const named = p.steps.map((id) => specialists.find((s) => s.id === id) ?? null);
  const Node = ({
    children,
    accent = false,
    bad = false,
    sub,
    mark,
  }: {
    children: ReactNode;
    accent?: boolean;
    /** A specialist that has never answered. Stated here rather than only on
        its own page, because a chain drawn as healthy when a link in it is dead
        is the diagram lying. */
    bad?: boolean;
    sub?: ReactNode;
    mark?: ReactNode;
  }) => (
    <div
      className="flex min-w-0 shrink-0 items-center gap-2 rounded-lg px-3 py-2"
      style={{
        background: bad
          ? "color-mix(in srgb, var(--state-critical-ink) 7%, transparent)"
          : accent
            ? "var(--accent-wash)"
            : "rgb(var(--panel))",
        boxShadow: bad
          ? "inset 0 0 0 1px color-mix(in srgb, var(--state-critical-ink) 34%, transparent)"
          : accent
            ? "inset 0 0 0 1px var(--accent-edge)"
            : "inset 0 0 0 1px rgb(var(--edge))",
      }}
    >
      {mark}
      <div className="min-w-0">
        <div className="truncate text-[12.5px] text-slate-200">{children}</div>
        {sub && (
          <div
            className="truncate text-[10.5px]"
            style={{ color: bad ? "var(--state-critical-ink)" : undefined }}
          >
            <span className={bad ? "" : "text-slate-500"}>{sub}</span>
          </div>
        )}
      </div>
    </div>
  );
  const Link = ({ label }: { label?: string }) => (
    <div className="flex shrink-0 flex-col items-center justify-center gap-0.5 px-0.5">
      <svg width="20" height="8" viewBox="0 0 20 8" fill="none" className="text-slate-700" aria-hidden>
        <path d="M0 4h15m0 0-3.5-3M15 4l-3.5 3" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
      </svg>
      {label && <span className="text-[9.5px] leading-none text-slate-600">{label}</span>}
    </div>
  );

  return (
    <div className="-mx-1 overflow-x-auto px-1 pb-1">
      <div className="flex min-w-max items-center gap-1">
        <Node sub="from your app" mark={<KindMark kind={kindOf(p.inputKind)} size={24} />}>
          {INPUT_LABEL[p.inputKind] ?? p.inputKind}
        </Node>
        {named.length === 0 ? (
          <>
            <Link />
            <Node sub="nothing configured">no specialist</Node>
          </>
        ) : (
          named.map((s, i) => (
            <span key={i} className="flex items-center gap-1">
              <Link label={i === 0 ? "raw" : undefined} />
              <Node
                accent
                bad={s?.status === "FAILED" || s?.status === "UNPARSEABLE"}
                sub={s?.status === "READY" ? "ready" : (s?.status ?? "unknown").toLowerCase()}
                mark={<KindMark kind={kindOf(`${s?.name ?? ""} ${s?.inputKind ?? ""}`)} size={24} />}
              >
                {s?.name ?? `#${p.steps[i]}`}
              </Node>
            </span>
          ))
        )}
        <Link label="findings" />
        <Node sub="never sees the input">language model</Node>
        <Link />
        <Node sub="answer + trace">your app</Node>
      </div>
    </div>
  );
}
