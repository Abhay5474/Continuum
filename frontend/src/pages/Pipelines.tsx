import { useCallback, useEffect, useState } from "react";
import { portal } from "../api";
import { InfoTip } from "../system/primitives";
import { CommandBar, Dot, Empty, Flow, Ghost, KindMark, Primary, Rail, Row, RowSkeleton, Section, Split, Stat, Stats, kindOf, Chip, Notice } from "../system/hub";
import { ErrorState, useToast } from "../components/ui";
import { Area, Pipeline, Specialist } from "./pipelines/types";
import { Detail } from "./pipelines/detail";
import { NewPipeline } from "./pipelines/create";

/**
 * Pipelines — what an external application actually calls.
 *
 * <p>The application sends an image and a question, and receives advice. It is
 * never told that a detector ran, what it was called, or who hosts it.
 *
 * <p>The chain is the page. Everything else here is configuration; the reason to
 * open this page is to watch a request go through the layer and come out the
 * other side, and to be able to read the exact words the model was given. A
 * pipeline described in prose is a promise. A pipeline whose chain you can watch
 * is a fact.
 *
 * <p><b>On the shape of this screen.</b> It used to be a stack of cards, each of
 * which opened into an accordion holding five bordered boxes stacked vertically:
 * routing, policy, verification, endpoint, try-it. That layout asserted those
 * five were equally important and simultaneously relevant, which is false — you
 * are doing exactly one of them at a time, and nine visits out of ten it is the
 * last one. So: the list of pipelines stays put on the left, the one you picked
 * fills the right, and its areas are a segmented control that opens on Run.
 */
export default function Pipelines() {
  const toast = useToast();
  const [pipelines, setPipelines] = useState<Pipeline[] | null>(null);
  const [specialists, setSpecialists] = useState<Specialist[]>([]);
  const [error, setError] = useState<string | null>(null);
  const [busy, setBusy] = useState(false);
  const [selected, setSelected] = useState<number | null>(null);
  const [area, setArea] = useState<Area>("run");
  const [q, setQ] = useState("");
  const [creating, setCreating] = useState(false);

  const load = useCallback(async () => {
    try {
      const [p, s] = await Promise.all([portal.pipelines.list(), portal.specialists.list()]);
      setPipelines(p);
      setSpecialists(s);
      setError(null);
      // Opening on nothing selected would show an empty right-hand column beside
      // a full list, which reads as broken rather than as a choice to be made.
      setSelected((cur) => (cur != null && p.some((x: Pipeline) => x.id === cur) ? cur : p[0]?.id ?? null));
    } catch (e: any) {
      setError(e?.message ?? "Could not load pipelines.");
    }
  }, []);

  useEffect(() => {
    void load();
  }, [load]);

  const ready = specialists.filter((s) => s.status !== "DRAFT");

  const act = async (fn: () => Promise<unknown>, ok?: string) => {
    setBusy(true);
    try {
      await fn();
      if (ok) toast(ok);
      await load();
    } catch (e: any) {
      toast(e?.message ?? "That did not work.", "error");
    } finally {
      setBusy(false);
    }
  };

  if (error) return <ErrorState message={error} onRetry={load} />;

  const all = pipelines ?? [];
  const needle = q.trim().toLowerCase();
  const shown = needle
    ? all.filter((p) =>
        [p.name, p.description ?? "", p.inputKind].join(" ").toLowerCase().includes(needle)
      )
    : all;
  const live = all.filter((p) => p.enabled).length;
  const guarded = all.filter((p) => p.policyEnabled).length;
  const totalRuns = all.reduce((n, p) => n + p.runs, 0);
  const current = all.find((p) => p.id === selected) ?? null;

  return (
    <section>
      <header>
        <div className="flex items-center gap-2.5">
          <Chip glyph="flow" tone="accent" size={28} />
          <h1 className="text-[20px] font-semibold tracking-[-0.011em] text-slate-100">Pipelines</h1>
        </div>
        <p className="mt-1 text-[13px] text-slate-500">One endpoint: input and question in, evidence-backed answer out</p>
      </header>

      <div className="mt-6">
        <Stats>
          <Stat label="Pipelines" value={all.length} />
          <Stat
            label="Accepting requests"
            value={live}
            tone={live > 0 ? "ok" : undefined}
            hint="A pipeline is off until you turn it on. Nothing runs by default."
          />
          <Stat
            label="Policy on"
            value={guarded}
            tone={guarded > 0 ? "accent" : undefined}
            hint="Pipelines where the strength of the evidence decides what the model may do with it."
          />
          <Stat label="Runs" value={totalRuns} />
        </Stats>
      </div>

      {ready.length === 0 && (
        <div className="mt-6">
          <Notice
            tone="warn"
            title="No probed specialist yet"
            body={
              <>
                Probe one first, so a failure shows up here — not in a customer's request.
              </>
            }
            right={
              <a
                href="/specialists"
                style={{ color: "var(--accent-ink)" }}
                className="underline underline-offset-2"
              >
                Register one and probe it
              </a>
            }
          />
        </div>
      )}

      {/* Before there is anything to list, the split is the wrong shape: the
          explanation of what a pipeline is would be set in a 254px column
          beside an empty pane. So the first-run state gets the whole width. */}
      {pipelines !== null && all.length === 0 ? (
        <div className="mt-10 max-w-2xl">
          <h2 className="flex items-center gap-1.5 text-[15px] font-semibold tracking-tight text-slate-100">
            No pipelines yet
          <InfoTip text="A pipeline is the endpoint your application calls. It takes the raw input, runs your specialists over it, turns what they found into something a language model can reason about, and returns the answer — without the model ever seeing the input itself." />
        </h2>
          <div className="mt-5">
            <Flow
              input="an image and a question"
              node="your specialists, then the model"
              nodeSub="findings in, prose out"
              output="an answer, and the chain behind it"
            />
          </div>
          <div className="mt-6">
            <Primary onClick={() => setCreating(true)} disabled={ready.length === 0}>
              New pipeline
            </Primary>
          </div>
        </div>
      ) : (
      <div className="mt-8">
        <Split
          list={
            <Section
              title="Pipelines"
              count={all.length}
              action={
                <Ghost tone="accent" onClick={() => setCreating(true)} disabled={ready.length === 0}>
                  New
                </Ghost>
              }
            >
              {all.length > 6 && (
                <div className="mb-3">
                  <CommandBar value={q} onChange={setQ} placeholder="Filter pipelines" />
                </div>
              )}
              {pipelines === null ? (
                <RowSkeleton rows={3} />
              ) : shown.length === 0 ? (
                <Empty
                  title={all.length === 0 ? "No pipelines yet" : "Nothing matches"}
                  hint={
                    all.length === 0
                      ? "A pipeline is the endpoint your application calls: it takes the raw input, runs your specialists over it, turns what they found into something a language model can reason about, and returns the answer."
                      : undefined
                  }
                />
              ) : (
                <Rail>
                  {shown.map((p) => (
                    <Row
                      key={p.id}
                      mark={<KindMark kind={kindOf(p.inputKind)} size={28} />}
                      title={p.name}
                      subtitle={p.description || `${p.steps.length} step${p.steps.length === 1 ? "" : "s"}`}
                      status={<Dot tone={p.enabled ? "ok" : "idle"} label={p.enabled ? "live" : "off"} />}
                      selected={p.id === selected}
                      onClick={() => {
                        setSelected(p.id);
                        setArea("run");
                      }}
                    />
                  ))}
                </Rail>
              )}
            </Section>
          }
          detail={
            current ? (
              <Detail
                key={current.id}
                p={current}
                specialists={specialists}
                busy={busy}
                area={area}
                onArea={setArea}
                onEnable={(next) =>
                  act(
                    () => portal.pipelines.update(current.id, { enabled: next }),
                    next ? `"${current.name}" is live.` : `"${current.name}" is off.`
                  )
                }
                onDelete={() => act(() => portal.pipelines.remove(current.id), "Pipeline removed.")}
                onRan={load}
                onPolicy={(body) => act(() => portal.pipelines.update(current.id, body))}
              />
            ) : (
              <div />
            )
          }
        />
      </div>
      )}

      <NewPipeline
        open={creating}
        specialists={ready}
        busy={busy}
        onClose={() => setCreating(false)}
        onAdd={(body) =>
          act(
            () => portal.pipelines.add(body),
            `Pipeline "${body.name}" created — it is off until you enable it.`
          )
        }
      />
    </section>
  );
}
