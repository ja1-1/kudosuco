import { defineConfig } from "vite";
import react from "@vitejs/plugin-react";

export default defineConfig({
  plugins: [react()],
  server: {
    port: Number(process.env.FRONTEND_PORT || 5177),
    strictPort: true,
    proxy: {
      "/api": {
        target: process.env.API_TARGET || "http://127.0.0.1:8084",
        changeOrigin: false,
      },
    },
  },
});
