/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        // Surface colors are CSS-variable-driven so the whole app flips between
        // dark (default) and light themes without touching component classes.
        ink: "rgb(var(--ink) / <alpha-value>)",
        panel: "rgb(var(--panel) / <alpha-value>)",
        edge: "rgb(var(--edge) / <alpha-value>)",
        // One system accent — a cool signal blue, deliberately free of the
        // purple cast that reads as "AI product". Everything else that carries
        // colour in this UI is a system state (see src/system/tokens.ts).
        // Names kept for backwards compatibility across existing components.
        // The console's accent, theme-aware: aurora blue on instrument black, coral
        // on paper. Every `aurora` utility in the app — borders, washes, active
        // states, dots — follows the theme rather than staying blue on white.
        aurora: "rgb(var(--accent-rgb) / <alpha-value>)",
        neon: "#7DA9FF",
        // The card plane and its edge. Separate from `panel`/`edge` because a
        // card is not a recess: on paper it is *above* the page, and it needs a
        // border that is visible against white rather than against black.
        card: "rgb(var(--card) / <alpha-value>)",
        "card-edge": "rgb(var(--card-edge) / <alpha-value>)",
      },
      boxShadow: {
        card: "var(--card-shadow)",
        // Soft, subtle elevation — no neon halos.
        glow: "0 8px 30px -16px rgba(76, 139, 245, 0.5)",
        "glow-cyan": "0 8px 30px -16px rgba(125, 169, 255, 0.42)",
        "glow-sm": "0 4px 14px -10px rgba(76, 139, 245, 0.55)",
      },
      keyframes: {
        fadeUp: {
          "0%": { opacity: "0", transform: "translateY(10px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
        glowPulse: {
          "0%, 100%": { boxShadow: "0 4px 18px -10px rgba(76, 139, 245, 0.5)" },
          "50%": { boxShadow: "0 6px 26px -10px rgba(125, 169, 255, 0.5)" },
        },
        shimmer: {
          "0%": { backgroundPosition: "-200% 0" },
          "100%": { backgroundPosition: "200% 0" },
        },
        floaty: {
          "0%, 100%": { transform: "translateY(0)" },
          "50%": { transform: "translateY(-4px)" },
        },
        streamDot: {
          "0%": { opacity: "0.15", transform: "scale(0.7)" },
          "50%": { opacity: "1", transform: "scale(1.15)" },
          "100%": { opacity: "0.15", transform: "scale(0.7)" },
        },
        pinch: {
          "0%": { transform: "scale(1)" },
          "40%": { transform: "scale(0.86, 0.94)" },
          "70%": { transform: "scale(1.05, 1.02)" },
          "100%": { transform: "scale(1)" },
        },
        branchGrow: {
          "0%": { strokeDashoffset: "600" },
          "100%": { strokeDashoffset: "0" },
        },
        collapse: {
          "0%": { opacity: "1" },
          "100%": { opacity: "0.08" },
        },
      },
      animation: {
        "fade-up": "fadeUp 0.5s ease-out both",
        "glow-pulse": "glowPulse 3s ease-in-out infinite",
        shimmer: "shimmer 2.4s linear infinite",
        floaty: "floaty 5s ease-in-out infinite",
        "stream-dot": "streamDot 1.2s ease-in-out infinite",
        pinch: "pinch 0.8s cubic-bezier(0.34, 1.56, 0.64, 1) both",
        "branch-grow": "branchGrow 1.6s ease-out both",
        collapse: "collapse 0.9s ease-out both",
      },
    },
  },
  plugins: [],
};
