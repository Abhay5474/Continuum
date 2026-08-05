import { useEffect, useState } from "react";
import { portal } from "../api";
import { SkeletonRows, ErrorState, useToast, Spinner } from "../components/ui";

/**
 * Billing & usage metering (mock Stripe). Shows the current plan, this month's
 * token usage vs quota with a live meter, and plan switching.
 */
export default function Billing() {
  const loggedIn = !!portal.session();
  const [data, setData] = useState<any | null>(null);
  const [loading, setLoading] = useState(true);
  const [error, setError] = useState<string | null>(null);
  const [switching, setSwitching] = useState<string | null>(null);
  const toast = useToast();

  const load = () => {
    setError(null);
    portal
      .billing()
      .then((d) => setData(d))
      .catch((e) => setError(e?.message ?? "Failed to load billing"))
      .finally(() => setLoading(false));
  };
  useEffect(() => {
    if (loggedIn) load();
    else setLoading(false);
  }, [loggedIn]);

  const changePlan = async (plan: string) => {
    setSwitching(plan);
    try {
      const d = await portal.setPlan(plan);
      setData(d);
      toast(`Switched to ${plan} plan`, "success");
    } catch (e: any) {
      toast(e?.message ?? "Could not change plan", "error");
    } finally {
      setSwitching(null);
    }
  };

  if (!loggedIn) {
    return (
      <div className="plane mx-auto mt-16 max-w-md p-8 text-center">
        <div className="text-3xl">💳</div>
        <h1 className="mt-2 text-lg font-semibold">Billing</h1>
        <p className="mt-1 text-sm text-slate-400 max-w-2xl leading-relaxed">Sign in through the Developer Portal to view your plan and usage.</p>
        <a href="/portal" className="mt-4 inline-block rounded-lg bg-gradient-to-r from-aurora to-neon px-4 py-2 text-sm font-semibold text-ink">
          Open Developer Portal →
        </a>
      </div>
    );
  }

  if (loading) {
    return (
      <div className="space-y-8">
        <div className="plane p-6"><SkeletonRows rows={3} /></div>
        <div className="plane p-6"><SkeletonRows rows={3} /></div>
      </div>
    );
  }
  if (error) return <ErrorState message={error} onRetry={load} />;

  const pct = Math.round((data?.usageFraction ?? 0) * 100);
  const hue = 140 - (data?.usageFraction ?? 0) * 140; // green → red as it fills
  const plans: any[] = data?.plans ?? [];

  return (
    <div className="space-y-6 animate-fade-up">
      <div>
        <h1 className="text-lg font-semibold tracking-tight text-slate-100">Billing &amp; Usage</h1>
        <p className="mt-0.5 text-sm text-slate-500 max-w-2xl leading-relaxed">Your plan, this month's token usage, and quota.</p>
      </div>

      {/* usage meter */}
      <div className="plane p-6">
        <div className="flex flex-wrap items-center gap-3">
          <div>
            <div className="text-xs uppercase tracking-wide text-slate-500">Current plan</div>
            <div className="text-2xl font-semibold text-slate-100">{data?.plan}</div>
          </div>
          <div className="ml-auto text-right">
            <div className="text-xs text-slate-500">This month</div>
            <div className="text-sm">
              <span className="font-semibold" style={{ color: `hsl(${hue} 80% 60%)` }}>
                {Number(data?.tokensUsed ?? 0).toLocaleString()}
              </span>{" "}
              / {Number(data?.monthlyTokenQuota ?? 0).toLocaleString()} tokens
            </div>
          </div>
        </div>
        <div className="mt-4 h-3 w-full overflow-hidden rounded-full bg-ink">
          <div
            className="h-full rounded-full transition-all duration-700"
            style={{ width: `${Math.min(100, pct)}%`, background: `linear-gradient(90deg, hsl(${hue} 80% 55%), hsl(${hue - 20} 80% 55%))` }}
          />
        </div>
        <div className="mt-2 flex flex-wrap items-center gap-x-6 gap-y-1 text-xs text-slate-400">
          <span>{pct}% used</span>
          <span>{Number(data?.tokensRemaining ?? 0).toLocaleString()} tokens remaining</span>
          <span>{data?.requestsThisPeriod ?? 0} requests</span>
          <span>${Number(data?.costThisPeriodUsd ?? 0).toFixed(5)} cost</span>
          {data?.overQuota && (
            <span className="rounded bg-rose-500/20 px-2 py-0.5 font-semibold text-rose-300">
              Over quota — requests are blocked until you upgrade
            </span>
          )}
        </div>
      </div>

      {/* plans */}
      <div className="grid gap-4 sm:grid-cols-3">
        {plans.map((p) => {
          const current = p.id === data?.plan;
          // An upgrade needs a settled payment. Where the deployment cannot take
          // one, say so on the button rather than letting it come back 402.
          const currentPrice = plans.find((x) => x.id === data?.plan)?.monthlyPriceUsd ?? 0;
          const isUpgrade = p.monthlyPriceUsd > currentPrice;
          const blocked = isUpgrade && !data?.paymentConfigured;
          return (
            <div
              key={p.id}
              className={`rounded-xl border p-5 transition-all ${current ? "border-aurora/50 bg-aurora/5 shadow-glow-sm" : "border-edge bg-panel/60 hover:-translate-y-1 hover:border-aurora/30"}`}
            >
              <div className="flex items-center justify-between">
                <div className="text-sm font-bold">{p.id}</div>
                {current && <span className="rounded-full bg-aurora/20 px-2 py-0.5 text-[10px] font-semibold text-indigo-300">Current</span>}
              </div>
              <div className="mt-2 text-2xl font-bold">
                ${p.monthlyPriceUsd}
                <span className="text-xs font-normal text-slate-500">/mo</span>
              </div>
              <div className="mt-1 text-xs text-slate-400">
                {Number(p.monthlyTokenQuota).toLocaleString()} tokens / month
              </div>
              <button
                disabled={current || blocked || switching === p.id}
                title={blocked ? "No payment processor is configured on this deployment" : undefined}
                onClick={() => changePlan(p.id)}
                className={`mt-4 flex w-full items-center justify-center gap-2 rounded-lg px-3 py-2 text-sm font-semibold transition-all ${
                  current
                    ? "cursor-default border border-edge text-slate-500"
                    : "bg-gradient-to-r from-aurora to-neon text-ink hover:-translate-y-0.5 disabled:opacity-60"
                }`}
              >
                {switching === p.id && <Spinner className="h-4 w-4 border-ink/40 border-t-ink" />}
                {current ? "Your plan" : blocked ? "Contact the operator" : isUpgrade ? `Upgrade to ${p.id}` : `Switch to ${p.id}`}
              </button>
            </div>
          );
        })}
      </div>
      <p className="text-center text-xs text-slate-600 max-w-2xl leading-relaxed">
        {data?.paymentConfigured
          ? `Payments are processed by ${data.paymentProvider}. Downgrades apply immediately.`
          : "No payment processor is configured on this deployment, so paid plans cannot be self-served. Downgrades apply immediately; ask the operator to apply a paid plan."}
        {data?.planSource === "OPERATOR_GRANT" && data?.grantedBy
          ? ` Your current plan was applied by ${data.grantedBy}.`
          : ""}
      </p>
    </div>
  );
}
