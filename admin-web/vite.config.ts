import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

// In development the page talks to the local admin backend through this proxy,
// so the login cookie stays on one address just like behind Caddy.
export default defineConfig({
  plugins: [react()],
  server: { port: 5173, proxy: { "/api": "http://127.0.0.1:9090" } },
  test: { environment: "jsdom" },
});
