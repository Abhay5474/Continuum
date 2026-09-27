import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import csp from "./csp.json";

// During local dev, proxy /api (and the OpenAI-compatible /v1) to the backend so
// no CORS or env config is needed.
export default defineConfig({
  plugins: [react()],
  // `vite preview` serves the build with the production security headers, so a
  // policy violation shows up locally before it does on Vercel.
  preview: {
    headers: {
      "Content-Security-Policy": csp.policy,
      "X-Content-Type-Options": "nosniff",
      "Referrer-Policy": "strict-origin-when-cross-origin",
      "X-Frame-Options": "DENY",
    },
    proxy: {
      "/api": "http://localhost:8080",
      "/v1": "http://localhost:8080",
    },
  },
  server: {
    port: 5173,
    proxy: {
      "/api": "http://localhost:8080",
      "/v1": "http://localhost:8080",
      "/actuator": "http://localhost:8080",
    },
  },
});
