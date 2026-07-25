/**
 * Instant parsing that tolerates both shapes the API returns.
 *
 * Jackson serialises a Java {@code Instant} as epoch *seconds* (a float), while
 * some endpoints send an ISO string. Passing the number straight to
 * {@code new Date()} reads it as milliseconds, which silently collapses a run
 * that took eight seconds into a single timestamp — every event appearing to
 * happen at once. So the unit is decided here, once.
 */
export function toDate(v: unknown): Date | null {
  if (v == null) return null;
  if (v instanceof Date) return Number.isNaN(v.getTime()) ? null : v;

  if (typeof v === "number") {
    // Epoch seconds and epoch milliseconds are ~3 orders of magnitude apart, so
    // the magnitude is an unambiguous discriminator for any plausible date.
    const ms = v < 1e11 ? v * 1000 : v;
    const d = new Date(ms);
    return Number.isNaN(d.getTime()) ? null : d;
  }

  if (typeof v === "string") {
    const n = Number(v);
    if (v.trim() !== "" && Number.isFinite(n)) return toDate(n);
    const d = new Date(v);
    return Number.isNaN(d.getTime()) ? null : d;
  }
  return null;
}

/** Milliseconds since the epoch, or null when the value is not a time. */
export function toMillis(v: unknown): number | null {
  return toDate(v)?.getTime() ?? null;
}

export function timeOf(v: unknown, fallback = "—"): string {
  return toDate(v)?.toLocaleTimeString() ?? fallback;
}

export function dateTimeOf(v: unknown, fallback = "—"): string {
  return toDate(v)?.toLocaleString() ?? fallback;
}

export function dateOf(v: unknown, fallback = "—"): string {
  return toDate(v)?.toLocaleDateString() ?? fallback;
}

/** Human duration between two instants, tolerant of either shape. */
export function elapsed(from: unknown, to: unknown): number | null {
  const a = toMillis(from);
  const b = toMillis(to);
  return a == null || b == null ? null : Math.max(0, b - a);
}

export function humanMs(ms: number | null | undefined, fallback = "—"): string {
  if (ms == null) return fallback;
  if (ms < 1000) return `${Math.round(ms)}ms`;
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`;
  return `${Math.floor(ms / 60_000)}m ${Math.round((ms % 60_000) / 1000)}s`;
}
