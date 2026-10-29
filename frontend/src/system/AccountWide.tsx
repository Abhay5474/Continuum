import { appLabel, useApps, useSelectedApp } from "./apps";

/**
 * Said on a page that covers the whole account, when one app is in view.
 * Everywhere else the app picker narrows what a page shows; here it cannot —
 * one plan quota, one autopilot policy — and a page silently ignoring the
 * picker would read as the picker being broken.
 */
export function AccountWide({ what }: { what: string }) {
  const app = useSelectedApp();
  const { apps } = useApps();
  if (app === "all") return null;
  return (
    <p
      role="note"
      className="flex items-start gap-2 rounded-[var(--r-md)] px-3 py-2 text-[12px] leading-relaxed"
      style={{ background: "var(--accent-wash)", color: "var(--accent-ink)" }}
    >
      <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"
           strokeLinecap="round" aria-hidden className="mt-[2px] shrink-0">
        <circle cx="8" cy="8" r="6.2" />
        <path d="M8 7.3v3.6M8 5.2v.1" />
      </svg>
      <span>
        {what} covers every app on this account, so this page shows all of them, not only{" "}
        <b>{appLabel(app, apps)}</b>.
      </span>
    </p>
  );
}
