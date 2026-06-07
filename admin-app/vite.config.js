import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In dev, proxy API calls to the FastAPI backend so the browser sees a single
// origin (no CORS needed) and the app can just call "/api/...".
// Override the backend target with VITE_BACKEND_URL if it runs elsewhere.
const backend = process.env.VITE_BACKEND_URL || "http://localhost:8000";

export default defineConfig({
  plugins: [react()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: backend, changeOrigin: true },
      "/health": { target: backend, changeOrigin: true },
    },
  },
});
