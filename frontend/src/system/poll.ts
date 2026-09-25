/**
 * {@code setInterval} for polling the server, minus the polls nobody sees.
 *
 * <p>Two dozen console pages refresh themselves every 1.5–6 seconds, and they
 * kept doing it in a background tab — all night, for a console left open — each
 * poll a request the server had to authenticate, query and serialise for a page
 * no one was looking at. This skips ticks while the document is hidden and,
 * when it becomes visible again, polls once straight away so the page is not
 * a full interval out of date at the moment the user returns.
 *
 * @returns the cleanup function, to return from an effect
 */
export function visibleInterval(fn: () => unknown, ms: number): () => void {
  let missed = false;
  const tick = () => {
    if (document.visibilityState === "hidden") {
      missed = true;
      return;
    }
    fn();
  };
  const onVisible = () => {
    if (document.visibilityState === "visible" && missed) {
      missed = false;
      fn();
    }
  };
  const id = setInterval(tick, ms);
  document.addEventListener("visibilitychange", onVisible);
  return () => {
    clearInterval(id);
    document.removeEventListener("visibilitychange", onVisible);
  };
}
