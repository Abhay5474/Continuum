/** @type {import('tailwindcss').Config} */
export default {
  content: ["./index.html", "./src/**/*.{ts,tsx}"],
  theme: {
    extend: {
      colors: {
        ink: "#070a12",
        panel: "#0d1322",
        edge: "#1e2739",
        aurora: "#8b5cf6",
        neon: "#22d3ee",
      },
      boxShadow: {
        glow: "0 0 24px -6px rgba(139, 92, 246, 0.5)",
        "glow-cyan": "0 0 24px -6px rgba(34, 211, 238, 0.45)",
        "glow-sm": "0 0 12px -4px rgba(139, 92, 246, 0.6)",
      },
      keyframes: {
        fadeUp: {
          "0%": { opacity: "0", transform: "translateY(10px)" },
          "100%": { opacity: "1", transform: "translateY(0)" },
        },
        glowPulse: {
          "0%, 100%": { boxShadow: "0 0 18px -6px rgba(139, 92, 246, 0.55)" },
          "50%": { boxShadow: "0 0 34px -4px rgba(34, 211, 238, 0.55)" },
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
