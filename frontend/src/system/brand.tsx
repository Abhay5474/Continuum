/**
 * The Continuum mark: a loop closing on itself, on the accent.
 *
 * <p>One component so the console, the front door and the sign-in page carry
 * the same mark. They had drifted — a glyph in a gradient tile outside, a
 * drawn loop on a flat tile inside — and a product that looks like two
 * products at the login screen reads as two products.
 */
export function BrandMark({ size = 26 }: { size?: number }) {
  const icon = Math.round(size * 0.58);
  return (
    <span
      className="flex shrink-0 items-center justify-center"
      style={{
        width: size,
        height: size,
        borderRadius: Math.round(size * 0.27),
        background: "var(--accent-strong)",
        color: "var(--accent-on)",
      }}
      aria-hidden
    >
      <svg width={icon} height={icon} viewBox="0 0 16 16" fill="none" stroke="currentColor"
           strokeWidth="1.9" strokeLinecap="round" strokeLinejoin="round">
        <path d="M13.5 8a5.5 5.5 0 1 1-1.9-4.15" />
        <path d="M13.7 1.9v3.4h-3.4" />
      </svg>
    </span>
  );
}
