import { createRouter, createWebHashHistory } from "vue-router";

// WebView 环境下 hash 路由最稳（无服务端 SPA fallback 依赖）；
// M2 验证 Tauri 自定义协议的 index.html 回退行为后，再决定是否切 history 模式
export const router = createRouter({
  history: createWebHashHistory(),
  routes: [
    { path: "/", name: "home", component: () => import("../views/HomeView.vue") },
    { path: "/monitor", name: "monitor", component: () => import("../views/MonitorView.vue") },
    { path: "/logs", name: "logs", component: () => import("../views/LogsView.vue") },
    { path: "/profiles", name: "profiles", component: () => import("../views/ProfilesView.vue") },
    { path: "/settings", name: "settings", component: () => import("../views/SettingsView.vue") },
  ],
});
