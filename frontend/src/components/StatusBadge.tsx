const COLORS: Record<string, string> = {
  RUNNING: "bg-amber-500/20 text-amber-300 border-amber-500/40",
  COMPLETED: "bg-emerald-500/20 text-emerald-300 border-emerald-500/40",
  FAILED: "bg-rose-500/20 text-rose-300 border-rose-500/40",
  PENDING: "bg-slate-500/20 text-slate-300 border-slate-500/40",
  SENT: "bg-emerald-500/20 text-emerald-300 border-emerald-500/40",
};

export default function StatusBadge({ status }: { status: string }) {
  const cls = COLORS[status] ?? "bg-slate-500/20 text-slate-300 border-slate-500/40";
  return (
    <span className={`inline-block rounded-full border px-2 py-0.5 text-xs font-medium ${cls}`}>
      {status}
    </span>
  );
}
