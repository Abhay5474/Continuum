import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useLayoutEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";
import {
  motion,
  prefersReducedMotion,
  projectedRest,
  rubberband,
  useDrag,
  useSpring,
} from "../system/physics";

/* ============================ Skeletons ============================ */

export function Skeleton({ className = "" }: { className?: string }) {
  return (
    <div
      className={`relative overflow-hidden rounded-md bg-edge/40 ${className}`}
      aria-hidden
    >
      <div className="absolute inset-0 shimmer-line opacity-40" />
    </div>
  );
}

export function SkeletonRows({ rows = 4 }: { rows?: number }) {
  return (
    <div className="space-y-2">
      {Array.from({ length: rows }).map((_, i) => (
        <div key={i} className="flex items-center gap-3">
          <Skeleton className="h-5 w-16" />
          <Skeleton className="h-5 flex-1" />
          <Skeleton className="h-5 w-20" />
        </div>
      ))}
    </div>
  );
}

export function SkeletonCards({ count = 6 }: { count?: number }) {
  return (
    <div className="grid grid-cols-2 gap-3 sm:grid-cols-3 lg:grid-cols-6">
      {Array.from({ length: count }).map((_, i) => (
        <Skeleton key={i} className="h-20" />
      ))}
    </div>
  );
}

/* ============================ Spinner ============================ */

export function Spinner({ className = "h-4 w-4" }: { className?: string }) {
  return (
    <span
      className={`inline-block animate-spin rounded-full border-2 border-edge border-t-neon ${className}`}
      role="status"
      aria-label="loading"
    />
  );
}

/* ============================ Empty state ============================ */

export function EmptyState({
  icon = "✧",
  title,
  hint,
  children,
}: {
  icon?: string;
  title: string;
  hint?: string;
  children?: ReactNode;
}) {
  return (
    <div className="flex min-w-0 flex-col items-center justify-center rounded-xl border border-dashed border-edge/80 px-6 py-12 text-center animate-fade-up">
      <div className="mb-3 flex h-12 w-12 items-center justify-center rounded-full bg-aurora/10 text-2xl text-indigo-300">
        {icon}
      </div>
      <div className="text-sm font-semibold text-slate-200">{title}</div>
      {hint && <div className="mt-1 max-w-md text-xs text-slate-500">{hint}</div>}
      {children && <div className="mt-4 w-full min-w-0 max-w-lg">{children}</div>}
    </div>
  );
}

/* ============================ Error state ============================ */

export function ErrorState({
  message,
  onRetry,
}: {
  message: string;
  onRetry?: () => void;
}) {
  return (
    <div
      className="flex flex-col items-center justify-center border px-6 py-12 text-center animate-fade-up"
      style={{
        borderRadius: "var(--r-lg)",
        borderColor: "rgb(var(--card-edge))",
        background: "rgb(var(--card))",
      }}
    >
      <span
        aria-hidden
        className="grid h-10 w-10 place-items-center rounded-[var(--r-lg)]"
        style={{ background: "var(--wash-bad)", color: "var(--state-critical-ink)" }}
      >
        <svg width="19" height="19" viewBox="0 0 16 16" fill="none" stroke="currentColor"
             strokeWidth="1.4" strokeLinecap="round" strokeLinejoin="round">
          <path d="M8 2.2 1.6 13.4h12.8L8 2.2Z" /><path d="M8 6.4v3M8 11.3v.1" />
        </svg>
      </span>
      <div className="mt-3 text-[13px] font-semibold text-slate-100">Something went wrong</div>
      <div className="mt-1.5 max-w-md break-words text-xs leading-relaxed text-slate-500">{message}</div>
      {onRetry && (
        <button
          onClick={onRetry}
          style={{ height: "var(--h-md)", borderRadius: "var(--r-md)", borderColor: "rgb(var(--card-edge))" }}
          className="mt-4 inline-flex items-center gap-1.5 border px-3 text-[12.5px] font-medium text-slate-200 transition-colors duration-150 hover:border-slate-500/60 hover:bg-[color:rgb(var(--card-hover))]"
        >
          ↻ Retry
        </button>
      )}
    </div>
  );
}

/* ============================ Copy button ============================ */

export function CopyButton({
  text,
  label = "Copy",
  className = "",
}: {
  text: string;
  label?: string;
  className?: string;
}) {
  const [copied, setCopied] = useState(false);
  const toast = useToastMaybe();
  const copy = async () => {
    try {
      await navigator.clipboard.writeText(text);
    } catch {
      /* clipboard may be blocked; still show feedback */
    }
    setCopied(true);
    toast?.("Copied to clipboard", "success");
    setTimeout(() => setCopied(false), 1400);
  };
  return (
    <button
      onClick={copy}
      className={`rounded-md border border-edge px-2.5 py-1 text-xs font-medium transition-all ${
        copied ? "border-emerald-400/50 text-emerald-300" : "text-slate-400 hover:border-neon/50 hover:text-neon"
      } ${className}`}
    >
      {copied ? "✓ Copied" : label}
    </button>
  );
}

/* ============================ Code block ============================ */

export function CodeBlock({
  code,
  language,
  className = "",
}: {
  code: string;
  language?: string;
  className?: string;
}) {
  return (
    <div className={`group relative w-full min-w-0 overflow-hidden rounded-lg border border-edge bg-ink ${className}`}>
      <div className="flex items-center justify-between border-b border-edge/60 px-3 py-1.5">
        <span className="text-[10px] uppercase tracking-widest text-slate-500">{language ?? "code"}</span>
        <CopyButton text={code} />
      </div>
      <pre className="overflow-x-auto p-3 text-xs leading-relaxed text-slate-300">
        <code>{code}</code>
      </pre>
    </div>
  );
}

/* ============================ Toasts ============================ */

type ToastKind = "success" | "error" | "info";
type ToastFn = (message: string, kind?: ToastKind) => void;
interface Toast {
  id: number;
  message: string;
  kind: ToastKind;
  /** Centre of whatever caused it, when that can be known. */
  origin: { x: number; y: number } | null;
}

const ToastContext = createContext<ToastFn | null>(null);

export function useToast(): ToastFn {
  const ctx = useContext(ToastContext);
  return ctx ?? (() => {});
}

function useToastMaybe(): ToastFn | null {
  return useContext(ToastContext);
}

/**
 * Where a notification came from.
 *
 * <p>When toast() is called, the control that caused it — the button just
 * pressed — almost always still has focus. Its position is the notification's
 * contextual origin: the toast arrives from that direction, so the eye connects
 * "I pressed Save" with "Saved" without reading either.
 */
function originOfCause(): { x: number; y: number } | null {
  const el = document.activeElement as HTMLElement | null;
  if (!el || el === document.body || el === document.documentElement) return null;
  const r = el.getBoundingClientRect();
  if (r.width === 0 || r.bottom < 0 || r.top > window.innerHeight) return null;
  return { x: r.left + r.width / 2, y: r.top + r.height / 2 };
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const idRef = useRef(0);

  const push = useCallback<ToastFn>((message, kind = "info") => {
    const id = ++idRef.current;
    setToasts((t) => [...t, { id, message, kind, origin: originOfCause() }]);
  }, []);

  const remove = useCallback((id: number) => {
    setToasts((t) => t.filter((x) => x.id !== id));
  }, []);

  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-full max-w-xs flex-col gap-2">
        {toasts.map((t) => (
          <ToastItem key={t.id} toast={t} onGone={() => remove(t.id)} />
        ))}
      </div>
    </ToastContext.Provider>
  );
}

const TOAST_INK: Record<ToastKind, string> = {
  success: "var(--state-healthy-ink)",
  error: "var(--state-critical-ink)",
  info: "var(--accent-ink)",
};

/**
 * One notification, as an object.
 *
 * <ul>
 *   <li><b>Arrives from its cause.</b> It starts part of the way toward the
 *       control that raised it and travels to its place in the stack on the
 *       elastic spring, landing with a small settle. Part of the way, not all:
 *       the direction is the message, and a flight across the whole screen
 *       would be louder than what it says.</li>
 *   <li><b>Makes room physically.</b> When a toast above it leaves, it springs
 *       into the gap rather than snapping (FLIP, on springs).</li>
 *   <li><b>Can be thrown away.</b> Drag it right; on release its momentum
 *       decides. A toast held under the pointer does not time out, so reading
 *       one never races the clock.</li>
 * </ul>
 */
function ToastItem({ toast, onGone }: { toast: Toast; onGone: () => void }) {
  const el = useRef<HTMLDivElement>(null);
  const enter = useSpring(0, { config: motion.elastic, precision: 0.001 });
  const leave = useSpring(0, { config: motion.fast, kind: "fade", precision: 0.002 });
  const dy = useSpring(0, { config: motion.standard, precision: 0.1 });
  const dx = useSpring(0, { config: motion.gesture, precision: 0.1 });
  const from = useRef({ x: 0, y: 0 });
  // Where the layout put it on screen, excluding the transform painted on top.
  // Measured against the viewport, not the container: the stack is anchored
  // to the bottom of the screen, so when the top toast leaves the container
  // shrinks and every offsetTop changes while nothing actually moves.
  const lastTop = useRef<number | null>(null);
  const paintedY = useRef(0);
  const layoutTop = () => (el.current ? el.current.getBoundingClientRect().top - paintedY.current : 0);
  const leaving = useRef(false);
  const timer = useRef<ReturnType<typeof setTimeout>>();
  const remaining = useRef(toast.kind === "error" ? 6500 : 4200);
  const startedAt = useRef(0);

  const go = useCallback(
    (velocity = 0) => {
      if (leaving.current) return;
      leaving.current = true;
      clearTimeout(timer.current);
      if (velocity > 0) {
        // Thrown: keep the throw's momentum and let it leave the way it went.
        dx.set(420, { velocity, config: motion.gesture });
      }
      leave.onRest = (v) => v === 1 && onGone();
      leave.set(1);
    },
    [dx, leave, onGone]
  );

  const arm = useCallback(() => {
    clearTimeout(timer.current);
    startedAt.current = performance.now();
    timer.current = setTimeout(() => go(), remaining.current);
  }, [go]);
  const pause = () => {
    clearTimeout(timer.current);
    remaining.current = Math.max(1200, remaining.current - (performance.now() - startedAt.current));
  };

  // Where it starts: a third of the way toward its cause, capped.
  useLayoutEffect(() => {
    const node = el.current;
    if (!node) return;
    const r = node.getBoundingClientRect();
    if (toast.origin && !prefersReducedMotion()) {
      const vx = toast.origin.x - (r.left + r.width / 2);
      const vy = toast.origin.y - (r.top + r.height / 2);
      const len = Math.hypot(vx, vy) || 1;
      const k = Math.min(0.3 * len, 220) / len;
      from.current = { x: vx * k, y: vy * k };
    } else {
      from.current = { x: 0, y: 14 };
    }
    lastTop.current = layoutTop();
    enter.set(1);
    arm();
    return () => clearTimeout(timer.current);
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Make room physically: if the stack reflowed under it, start from where it
  // was and spring to where it now is.
  useLayoutEffect(() => {
    const node = el.current;
    if (!node) return;
    const top = layoutTop();
    if (lastTop.current !== null && Math.abs(top - lastTop.current) > 0.5) {
      dy.jump(dy.value + (lastTop.current - top));
      dy.set(0);
    }
    lastTop.current = top;
  });

  useLayoutEffect(() => {
    const node = el.current;
    if (!node) return;
    const paint = () => {
      const e = enter.value;
      const l = leave.value;
      const reduced = prefersReducedMotion();
      const x = (1 - e) * from.current.x + dx.value + (reduced ? 0 : l * 24);
      const y = (1 - e) * from.current.y + dy.value;
      const sc = reduced ? 1 : (0.9 + 0.1 * Math.min(1, e)) * (1 - 0.04 * l);
      node.style.transform = `translate3d(${x.toFixed(2)}px,${y.toFixed(2)}px,0) scale(${sc.toFixed(4)})`;
      paintedY.current = y;
      const drift = Math.max(0, dx.value) / 320;
      node.style.opacity = String(Math.max(0, Math.min(1, e * 1.6) * (1 - l) * (1 - drift)));
    };
    const offs = [enter, leave, dy, dx].map((s) => s.subscribe(paint));
    return () => offs.forEach((f) => f());
  }, [enter, leave, dy, dx]);

  useDrag(el, {
    axis: "x",
    onStart: pause,
    onMove: ({ offset }) => dx.jump(offset >= 0 ? offset : rubberband(offset, 60)),
    onEnd: ({ offset, velocity }) => {
      if (projectedRest(offset, velocity) > 140 || velocity > 700) go(Math.max(velocity, 600));
      else {
        dx.set(0, { velocity, config: motion.gesture });
        arm();
      }
    },
  });

  return (
    <div
      ref={el}
      role="status"
      onPointerEnter={pause}
      onPointerLeave={() => !leaving.current && arm()}
      className="pointer-events-auto flex cursor-grab touch-pan-y select-none items-start gap-2.5 border border-l-[3px] px-3 py-2.5 text-[12.5px] active:cursor-grabbing"
      style={{
        borderRadius: "var(--r-lg)",
        borderColor: "rgb(var(--card-edge))",
        borderLeftColor: toast.kind === "info" ? "var(--accent)" : TOAST_INK[toast.kind],
        background: "rgb(var(--card))",
        boxShadow: "var(--shadow-float)",
        opacity: 0,
        willChange: "transform, opacity",
      }}
    >
      <span aria-hidden className="mt-[1px] shrink-0" style={{ color: TOAST_INK[toast.kind] }}>
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor"
             strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
          {toast.kind === "success" ? (
            <><circle cx="8" cy="8" r="6.3" /><path d="m5.2 8.2 2 2 3.6-4" className="draw-in" /></>
          ) : toast.kind === "error" ? (
            <><path d="M8 2.2 1.6 13.4h12.8L8 2.2Z" /><path d="M8 6.4v3M8 11.3v.1" /></>
          ) : (
            <><circle cx="8" cy="8" r="6.3" /><path d="M8 7.4v4M8 4.7v.1" /></>
          )}
        </svg>
      </span>
      <span className="flex-1 leading-relaxed text-slate-200">{toast.message}</span>
    </div>
  );
}

/* ============================ Theme (dark default) ============================ */

const THEME_KEY = "continuum.theme";

function applyTheme(theme: "dark" | "light") {
  const root = document.documentElement;
  if (theme === "light") root.setAttribute("data-theme", "light");
  else root.removeAttribute("data-theme"); // dark is the default (no attribute)
}

// Apply the persisted theme as early as possible.
if (typeof document !== "undefined") {
  const saved = (localStorage.getItem(THEME_KEY) as "dark" | "light" | null) ?? "dark";
  applyTheme(saved);
}

export function ThemeToggle({ className = "" }: { className?: string }) {
  const [theme, setTheme] = useState<"dark" | "light">(
    () => (typeof localStorage !== "undefined" && (localStorage.getItem(THEME_KEY) as any)) || "dark"
  );
  useEffect(() => {
    applyTheme(theme);
    localStorage.setItem(THEME_KEY, theme);
  }, [theme]);
  return (
    <button
      onClick={() => setTheme((t) => (t === "dark" ? "light" : "dark"))}
      aria-label="Toggle theme"
      data-tip={theme === "dark" ? "Switch to light" : "Switch to dark"}
      className={`flex h-8 w-8 items-center justify-center rounded-lg border border-edge text-slate-300 hover:border-neon/50 hover:text-neon ${className}`}
    >
      {/* One drawing in two states, rather than two glyphs swapped: the change
          is seen happening. The mask id is unique per instance because the
          landing page and the console bar can both show a toggle. */}
      <ThemeIcon mode={theme} />
    </button>
  );
}

let themeIconSeq = 0;
function ThemeIcon({ mode }: { mode: "dark" | "light" }) {
  const id = useRef(`theme-mask-${++themeIconSeq}`).current;
  // In dark mode the icon offers the sun (switch to light); in light, the moon.
  const shows = mode === "dark" ? "light" : "dark";
  return (
    <svg className="theme-icon" data-mode={shows} width="16" height="16" viewBox="0 0 24 24" aria-hidden
         fill="none" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round">
      <mask id={id}>
        <rect width="24" height="24" fill="white" />
        <circle className="shade" cx="17" cy="7" r="6" fill="black" />
      </mask>
      <circle className="disc" cx="12" cy="12" r="4.5" fill="currentColor" stroke="none" mask={`url(#${id})`} />
      <g className="rays">
        {[0, 45, 90, 135, 180, 225, 270, 315].map((a) => (
          <line key={a} x1="12" y1="2.6" x2="12" y2="4.4" transform={`rotate(${a} 12 12)`} />
        ))}
      </g>
    </svg>
  );
}

/* ============================ Scroll reveal ============================ */

/** Fades/slides children in when they scroll into view (JetBrains-style). */
export function Reveal({
  children,
  delay = 0,
  className = "",
}: {
  children: ReactNode;
  delay?: number;
  className?: string;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [shown, setShown] = useState(false);
  useEffect(() => {
    const el = ref.current;
    if (!el) return;
    const io = new IntersectionObserver(
      ([e]) => {
        if (e.isIntersecting) {
          setShown(true);
          io.disconnect();
        }
      },
      { threshold: 0.12 }
    );
    io.observe(el);
    return () => io.disconnect();
  }, []);
  return (
    <div
      ref={ref}
      className={`transition-all duration-700 ease-out ${
        shown ? "translate-y-0 opacity-100" : "translate-y-6 opacity-0"
      } ${className}`}
      style={{ transitionDelay: `${delay}ms` }}
    >
      {children}
    </div>
  );
}
