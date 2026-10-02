import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";
import tailwindcss from "@tailwindcss/vite";

/**
 * In der Entwicklung laeuft das Dashboard auf 5173 und der Master auf 8080.
 *
 * Der Proxy leitet /api und /ws weiter, damit beides aus Sicht des Browsers von
 * derselben Herkunft kommt - sonst waeren Cookies und CORS ein Thema, das im Betrieb
 * gar nicht existiert: Dort liefert Javalin die gebauten Dateien selbst aus.
 */
export default defineConfig({
  plugins: [react(), tailwindcss()],
  server: {
    port: 5173,
    proxy: {
      "/api": { target: "http://localhost:8080", changeOrigin: true },
      "/ws": { target: "ws://localhost:8080", ws: true },
    },
  },
  build: {
    // Javalin liefert sie spaeter aus master/dashboard/ aus.
    outDir: "dist",
  },
});
