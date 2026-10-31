<script setup lang="ts">
import { onMounted, watch } from "vue";
import { useRoute } from "vue-router";
import { useEngineStore } from "./stores/engine";

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
  router:
    "M20 2H4v20h16V2zm-2 2v3.75l-2 1.5V4h2zM9 4h5v5.25l-2.5 1.87L9 9.25V4zM7 4v5.25L5.5 8.13 4 9.5V4h3zM4 20v-8l5.5-4.12L11 9v11H4zm9 0V9l2.5 1.88L20 6.75V20h-7z",
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
    <aside class="sidebar">
      <div class="brand">
        <svg viewBox="0 0 24 24" fill="currentColor"><path :d="icons.router" /></svg>
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
      <div class="sidebar-foot">引擎 frp {{ store.engineVersion }}</div>
    </aside>
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>
