import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import { readFileSync } from "node:fs";

// Read rather than import: JSON import attributes need Node 20.10+, and the
// Cloud Build image's Node version is not pinned by this repo.
const pkg = JSON.parse(readFileSync(new URL("./package.json", import.meta.url), "utf8"));

// In dev, proxy API calls to the FastAPI backend so the browser sees a single
// origin (no CORS needed) and the app can just call "/api/...".
// Override the backend target with VITE_BACKEND_URL if it runs elsewhere.
const backend = process.env.VITE_BACKEND_URL || "http://localhost:8000";

export default defineConfig({
  // Surface the package version to the app so the footer cannot drift from
  // package.json - one place to bump, and the deployed build says which it is.
  define: { __APP_VERSION__: JSON.stringify(pkg.version) },
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: backend, changeOrigin: true },
      "/health": { target: backend, changeOrigin: true },
    },
  },
});
