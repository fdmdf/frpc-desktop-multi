<script setup lang="ts">
import { onMounted, watch } from "vue";
import { useRoute } from "vue-router";
import { useEngineStore } from "./stores/engine";
import logo from "./assets/logo.svg";

const route = useRoute();
const store = useEngineStore();

onMounted(async () => {
  await store.init();
});

watch(
  () => store.settings?.theme,
  (t) => {
    const dark =
      t === "dark" || (t !== "light" && window.matchMedia("(prefers-color-scheme: dark)").matches);
    document.documentElement.dataset.theme = dark ? "dark" : "light";
  },
  { immediate: true },
);

const tabs = [
  { to: "/", label: "主页", icon: "home" },
  { to: "/profiles", label: "配置", icon: "dashboard" },
  { to: "/logs", label: "日志", icon: "article" },
  { to: "/monitor", label: "监控", icon: "monitor" },
  { to: "/settings", label: "设置", icon: "settings" },
] as const;

const icons: Record<string, string> = {
  home: "M10 20v-6h4v6h5v-8h3L12 3 2 12h3v8z",
  dashboard: "M3 13h8V3H3v10zm0 8h8v-6H3v6zm10 0h8V11h-8v10zm0-18v6h8V3h-8z",
  article:
    "M19 3H5c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h14c1.1 0 2-.9 2-2V5c0-1.1-.9-2-2-2zm-5 14H7v-2h7v2zm3-4H7v-2h10v2zm0-4H7V7h10v2z",
  monitor:
    "M22 12h-4l-3 9L9 3l-3 9H2v2h5.63l2.37-7.1L13.63 19l3-9H22v2zM11 13h2v6h-2z",
  settings:
    "M19.14 12.94c.04-.3.06-.61.06-.94 0-.32-.02-.64-.07-.94l2.03-1.58c.18-.14.23-.41.12-.61l-1.92-3.32c-.12-.22-.37-.29-.59-.22l-2.39.96c-.5-.38-1.03-.7-1.62-.94l-.36-2.54c-.04-.24-.24-.41-.48-.41h-3.84c-.24 0-.43.17-.47.41l-.36 2.54c-.59.24-1.13.57-1.62.94l-2.39-.96c-.22-.08-.47 0-.59.22L2.74 8.87c-.12.21-.08.47.12.61l2.03 1.58c-.05.3-.09.63-.09.94s.02.64.07.94l-2.03 1.58c-.18.14-.23.41-.12.61l1.92 3.32c.12.22.37.29.59.22l2.39-.96c.5.38 1.03.7 1.62.94l.36 2.54c.05.24.24.41.48.41h3.84c.24 0 .44-.17.47-.41l.36-2.54c.59-.24 1.13-.56 1.62-.94l2.39.96c.22.08.47 0 .59-.22l1.92-3.32c.12-.22.07-.47-.12-.61l-2.01-1.58zM12 15.6A3.61 3.61 0 0 1 8.4 12c0-1.98 1.62-3.6 3.6-3.6s3.6 1.62 3.6 3.6-1.62 3.6-3.6 3.6z",
};
</script>

<template>
  <div class="shell">
    <!-- 移动端顶栏（窄屏才显示）：桌面端用侧栏标题 -->
    <header class="topbar">
      <img class="brand-logo" :src="logo" alt="" />
      <span class="topbar-title">frpc-desktop-multi</span>
      <span v-if="store.appVersion" class="topbar-ver">v{{ store.appVersion }}</span>
    </header>
    <aside class="sidebar">
      <div class="brand">
        <img class="brand-logo" :src="logo" alt="" />
        <span>frpc-desktop-multi</span>
      </div>
      <RouterLink
        v-for="t in tabs"
        :key="t.to"
        :to="t.to"
        class="nav-item"
        :class="{ active: route.path === t.to }"
      >
        <svg viewBox="0 0 24 24" fill="currentColor"><path :d="icons[t.icon]" /></svg>
        <span>{{ t.label }}</span>
      </RouterLink>
      <div class="sidebar-foot">
        引擎 frp {{ store.engineVersion }}
        <span v-if="store.appVersion"> · v{{ store.appVersion }}</span>
      </div>
    </aside>
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>
