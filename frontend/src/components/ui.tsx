import {
  createContext,
  useCallback,
  useContext,
  useEffect,
  useRef,
  useState,
  type ReactNode,
} from "react";

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
}

const ToastContext = createContext<ToastFn | null>(null);

export function useToast(): ToastFn {
  const ctx = useContext(ToastContext);
  return ctx ?? (() => {});
}

function useToastMaybe(): ToastFn | null {
  return useContext(ToastContext);
}

export function ToastProvider({ children }: { children: ReactNode }) {
  const [toasts, setToasts] = useState<Toast[]>([]);
  const idRef = useRef(0);

  const push = useCallback<ToastFn>((message, kind = "info") => {
    const id = ++idRef.current;
    setToasts((t) => [...t, { id, message, kind }]);
    setTimeout(() => setToasts((t) => t.filter((x) => x.id !== id)), 3200);
  }, []);

  return (
    <ToastContext.Provider value={push}>
      {children}
      <div className="pointer-events-none fixed bottom-4 right-4 z-50 flex w-full max-w-xs flex-col gap-2">
        {toasts.map((t) => (
          <div
            key={t.id}
            role="status"
            className="pointer-events-auto flex items-start gap-2.5 border border-l-[3px] px-3 py-2.5 text-[12.5px] animate-fade-up"
            style={{
              borderRadius: "var(--r-lg)",
              borderColor: "rgb(var(--card-edge))",
              borderLeftColor:
                t.kind === "success"
                  ? "var(--state-healthy-ink)"
                  : t.kind === "error"
                    ? "var(--state-critical-ink)"
                    : "var(--accent)",
              background: "rgb(var(--card))",
              boxShadow: "0 4px 6px -2px rgba(0,0,0,.14), 0 12px 26px -8px rgba(0,0,0,.32)",
            }}
          >
            <span
              aria-hidden
              className="mt-[1px] shrink-0"
              style={{
                color:
                  t.kind === "success"
                    ? "var(--state-healthy-ink)"
                    : t.kind === "error"
                      ? "var(--state-critical-ink)"
                      : "var(--accent-ink)",
              }}
            >
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" stroke="currentColor"
                   strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round">
                {t.kind === "success" ? (
                  <><circle cx="8" cy="8" r="6.3" /><path d="m5.2 8.2 2 2 3.6-4" /></>
                ) : t.kind === "error" ? (
                  <><path d="M8 2.2 1.6 13.4h12.8L8 2.2Z" /><path d="M8 6.4v3M8 11.3v.1" /></>
                ) : (
                  <><circle cx="8" cy="8" r="6.3" /><path d="M8 7.4v4M8 4.7v.1" /></>
                )}
              </svg>
            </span>
            <span className="flex-1 leading-relaxed text-slate-200">{t.message}</span>
          </div>
        ))}
      </div>
    </ToastContext.Provider>
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
      title={theme === "dark" ? "Switch to light" : "Switch to dark"}
      className={`flex h-8 w-8 items-center justify-center rounded-lg border border-edge text-sm text-slate-300 transition-colors hover:border-neon/50 hover:text-neon ${className}`}
    >
      {theme === "dark" ? "☀" : "☾"}
    </button>
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
