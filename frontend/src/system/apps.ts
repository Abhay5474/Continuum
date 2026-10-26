import { useCallback, useEffect, useState, useSyncExternalStore } from "react";
import { APP_CHANGED, portal, selectApp, selectedApp, type AppSummary } from "../api";

/** Fired on window when an app is added or renamed, so every list of them reloads. */
export const APPS_CHANGED = "continuum:apps-changed";

export function appsChanged() {
  window.dispatchEvent(new Event(APPS_CHANGED));
}

function subscribe(onChange: () => void) {
  window.addEventListener(APP_CHANGED, onChange);
  // Another tab switching app switches this one too: two tabs showing
  // different apps under one selector would be worse than either.
  window.addEventListener("storage", onChange);
  return () => {
    window.removeEventListener(APP_CHANGED, onChange);
    window.removeEventListener("storage", onChange);
  };
}

/** The app the console is looking at; re-renders when it changes. */
export function useSelectedApp(): string {
  return useSyncExternalStore(subscribe, selectedApp, () => "all");
}

/**
 * The account's apps. A selection naming an app that no longer exists (another
 * account's, after signing in as someone else) falls back to all apps rather
 * than leaving every page empty with no visible reason.
 */
export function useApps() {
  const [apps, setApps] = useState<AppSummary[] | null>(null);
  const load = useCallback(() => {
    if (!portal.session()) {
      setApps([]);
      return;
    }
    portal
      .apps()
      .then((list) => {
        setApps(list);
        const sel = selectedApp();
        if (/^\d+$/.test(sel) && !list.some((a) => String(a.id) === sel)) selectApp("all");
      })
      .catch(() => setApps([]));
  }, []);
  useEffect(() => {
    load();
    window.addEventListener(APPS_CHANGED, load);
    return () => window.removeEventListener(APPS_CHANGED, load);
  }, [load]);
  return { apps, reload: load };
}

/** What the selector shows for a selection. */
export function appLabel(sel: string, apps: AppSummary[] | null): string {
  if (sel === "all") return "All apps";
  if (sel === "untagged") return "Console & earlier";
  return apps?.find((a) => String(a.id) === sel)?.name ?? "App";
}
