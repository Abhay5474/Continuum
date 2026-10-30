import { useLayoutEffect, useRef, type ReactNode } from "react";
import { useLocation } from "react-router-dom";
import { enterPage } from "./navigation";

/**
 * Where every console page is drawn, and how it arrives.
 *
 * <p>How a page leaves and how the next one takes its place — sideways for a
 * tab, upward for a new section, a card growing into the page it opens — is
 * the view transition's job (see navigation.ts). What happens once the page is
 * here is this component's: its blocks rise into place in reading order, and
 * focus moves onto it so keyboard and screen-reader users land on the new page
 * rather than on the link that left the old one.
 *
 * <p>Keyed on the path, so each page is a fresh element with a fresh entrance.
 */
export function PageStage({ className, children }: { className: string; children: ReactNode }) {
  const location = useLocation();
  const main = useRef<HTMLElement>(null);
  const prev = useRef<string | null>(null);
  const live = useRef<{ stop: () => void; stopped: boolean } | null>(null);

  useLayoutEffect(() => {
    const from = prev.current;
    prev.current = location.pathname;
    const m = main.current;
    if (!m) return;
    // StrictMode runs this twice on mount, cleaning up in between; the second
    // run replays the entrance the cleanup stopped, without moving focus again.
    const replay = from === location.pathname;
    if (replay && live.current && !live.current.stopped) return;
    if (!replay && from !== null) {
      // Not while someone is typing in a field that survived the navigation.
      const active = document.activeElement as HTMLElement | null;
      const typing = !!active && document.contains(active) && !m.contains(active)
        && (active.isContentEditable || /^(INPUT|TEXTAREA|SELECT)$/.test(active.tagName));
      if (!typing) m.focus({ preventScroll: true });
    }
    const handle = { stop: enterPage(m), stopped: false };
    live.current = handle;
    return () => {
      handle.stopped = true;
      handle.stop();
    };
  }, [location.pathname]);

  return (
    <main
      id="main"
      tabIndex={-1}
      key={location.pathname}
      ref={main}
      data-path={location.pathname}
      className={`relative z-[1] outline-none ${className}`}
    >
      {children}
    </main>
  );
}
