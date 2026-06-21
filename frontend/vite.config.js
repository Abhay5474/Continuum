import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
// During local dev, proxy /api to the backend so no CORS or env config is needed.
export default defineConfig({
    plugins: [react()],
    server: {
        port: 5173,
        proxy: {
            "/api": "http://localhost:8080",
            "/actuator": "http://localhost:8080",
        },
    },
});
