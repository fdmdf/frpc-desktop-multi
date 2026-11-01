import { defineConfig } from "vite";
import vue from "@vitejs/plugin-vue";

// Tauri 约定：固定 1420 端口，与 src-tauri/tauri.conf.json 的 devUrl 保持一致
export default defineConfig({
  plugins: [vue()],
  clearScreen: false,
  server: {
    port: 1420,
    strictPort: true,
    watch: { ignored: ["**/src-tauri/**"] },
  },
  build: {
    target: "es2022",
    outDir: "dist",
  },
});
