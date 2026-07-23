import { useEffect, useState } from "react";
import { Link } from "react-router-dom";
import { api } from "../api";
import type { CostReport, Meta, Stats, WorkflowSummary } from "../types";
import StatusBadge from "../components/StatusBadge";
import { SkeletonCards, SkeletonRows, EmptyState, ErrorState, Spinner, useToast, CodeBlock } from "../components/ui";

function StatCard({ label, value, accent }: { label: string; value: string | number; accent?: string }) {
  return (
    <div className="rounded-lg border border-edge bg-panel p-4 transition-all hover:border-aurora/30">
      <div className="text-xs uppercase tracking-wide text-slate-400">{label}</div>
      <div className={`mt-1 text-2xl font-semibold ${accent ?? ""}`}>{value}</div>
    </div>
  );
}

export default function Dashboard() {
  const [stats, setStats] = useState<Stats | null>(null);
  const [meta, setMeta] = useState<Meta | null>(null);
  const [costs, setCosts] = useState<CostReport | null>(null);
  const [workflows, setWorkflows] = useState<WorkflowSummary[]>([]);
  const [type, setType] = useState("CustomerAnalysis");
  const [customerId, setCustomerId] = useState("C-1001");
  const [busy, setBusy] = useState(false);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const toast = useToast();

  async function refresh() {
    try {
      const [s, w, c] = await Promise.all([api.stats(), api.workflows(), api.costs()]);
      setStats(s);
      setWorkflows(w);
      setCosts(c);
      setError(null);
    } catch (e: any) {
      setError(e?.message ?? "Failed to load dashboard");
    } finally {
      setLoading(false);
    }
  }

  useEffect(() => {
    api.meta().then(setMeta).catch(() => {});
    refresh();
    const t = setInterval(() => refresh(), 2500);
    return () => clearInterval(t);
  }, []);

  async function start() {
    setBusy(true);
    try {
      const input =
        type === "CustomerAnalysis"
          ? { customerId, notifyEmail: `${customerId}@example.com` }
          : { name: customerId };
      await api.start(type, input);
      toast(`Started ${type}`, "success");
      await refresh();
    } catch (e: any) {
      toast(e?.message ?? "Failed to start workflow", "error");
    } finally {
      setBusy(false);
    }
  }

  if (loading) {
    return (
      <div className="space-y-6">
        <SkeletonCards />
        <div className="grid gap-6 lg:grid-cols-3">
          <div className="rounded-lg border border-edge bg-panel p-4 lg:col-span-2">
            <SkeletonRows rows={5} />
          </div>
          <div className="rounded-lg border border-edge bg-panel p-4">
            <SkeletonRows rows={4} />
          </div>
        </div>
      </div>
    );
  }

  if (error && !stats) {
    return <ErrorState message={error} onRetry={refresh} />;
  }

  return (
    <div className="space-y-6 animate-fade-up">
      <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
        <StatCard label="Total" value={stats?.total ?? "—"} />
        <StatCard label="Running" value={stats?.running ?? "—"} accent="text-amber-300" />
        <StatCard label="Completed" value={stats?.completed ?? "—"} accent="text-emerald-300" />
        <StatCard label="Failed" value={stats?.failed ?? "—"} accent="text-rose-300" />
        <StatCard label="Deliveries" value={stats?.outboxDeliveries ?? "—"} />
        <StatCard
          label="Dup. Deliveries"
          value={stats ? stats.duplicateDeliveries.length : "—"}
          accent={stats && stats.duplicateDeliveries.length > 0 ? "text-rose-400" : "text-emerald-300"}
        />
      </div>

      <div className="grid gap-6 lg:grid-cols-3">
        <div className="rounded-lg border border-edge bg-panel lg:col-span-2">
          <div className="flex items-center border-b border-edge px-4 py-3">
            <span className="font-medium">Workflows</span>
            {error && <span className="ml-auto text-xs text-rose-400">reconnecting…</span>}
          </div>
          {workflows.length === 0 ? (
            <div className="p-4">
              <EmptyState
                icon="⟳"
                title="No workflows yet"
                hint="Start one from the panel on the right, or kick off the flagship crash-recovery demo from the CLI."
              >
                <CodeBlock
                  language="bash"
                  code={`curl -X POST localhost:8080/api/workflows \\
  -H 'Content-Type: application/json' \\
  -d '{"workflowType":"DurableDemo","input":{"name":"demo"}}'`}
                />
              </EmptyState>
            </div>
          ) : (
            <div className="divide-y divide-edge">
              {workflows.map((w) => (
                <Link
                  key={w.workflowId}
                  to={`/workflows/${w.workflowId}`}
                  className="flex items-center gap-3 px-4 py-3 transition-colors hover:bg-edge/50"
                >
                  <StatusBadge status={w.status} />
                  <span className="text-sm font-medium">{w.workflowType}</span>
                  <span className="font-mono text-xs text-slate-400">{w.workflowId.slice(0, 8)}</span>
                  <span className="ml-auto text-xs text-slate-400">{w.currentSequence} events</span>
                </Link>
              ))}
            </div>
          )}
        </div>

        <div className="space-y-6">
          <div className="rounded-lg border border-edge bg-panel p-4">
            <div className="font-medium">Start a workflow</div>
            <label className="mt-3 block text-xs text-slate-400">Type</label>
            <select
              value={type}
              onChange={(e) => setType(e.target.value)}
              className="mt-1 w-full rounded-md border border-edge bg-ink px-3 py-2 text-sm"
            >
              {(meta?.workflowTypes ?? ["CustomerAnalysis", "DurableDemo"]).map((t) => (
                <option key={t}>{t}</option>
              ))}
            </select>
            <label className="mt-3 block text-xs text-slate-400">
              {type === "CustomerAnalysis" ? "Customer ID" : "Name"}
            </label>
            <input
              value={customerId}
              onChange={(e) => setCustomerId(e.target.value)}
              className="mt-1 w-full rounded-md border border-edge bg-ink px-3 py-2 text-sm"
            />
            <button
              onClick={start}
              disabled={busy}
              className="mt-4 flex w-full items-center justify-center gap-2 rounded-md bg-indigo-600 px-3 py-2 text-sm font-medium text-white transition-colors hover:bg-indigo-500 disabled:opacity-50"
            >
              {busy && <Spinner />}
              {busy ? "Starting…" : "Start workflow"}
            </button>
          </div>

          <div className="rounded-lg border border-edge bg-panel p-4">
            <div className="font-medium">Cost & Tokens</div>
            <div className="mt-2 text-sm">
              <div className="flex justify-between">
                <span className="text-slate-400">Total cost</span>
                <span>${(costs?.totalCostUsd ?? 0).toFixed(6)}</span>
              </div>
              <div className="flex justify-between">
                <span className="text-slate-400">Total tokens</span>
                <span>{costs?.totalTokens ?? 0}</span>
              </div>
            </div>
            <div className="mt-3 space-y-1">
              {costs?.byProvider.map((p) => (
                <div key={p.provider} className="flex justify-between text-xs text-slate-400">
                  <span>{p.provider}</span>
                  <span>
                    {p.tokens} tok · {p.calls} calls
                  </span>
                </div>
              ))}
            </div>
            <div className="mt-3 text-xs text-slate-500">
              Providers: {(meta?.providerFailoverChain ?? []).join(" → ") || "none"}
            </div>
          </div>
        </div>
      </div>
    </div>
  );
}
