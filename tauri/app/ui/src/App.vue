<script setup lang="ts">
import { onMounted, watch } from "vue";
import { useI18n } from "vue-i18n";
import { useEngineStore } from "./stores/engine";

const { locale } = useI18n();
const store = useEngineStore();

onMounted(async () => {
  locale.value = localStorage.getItem("locale") || "zh-CN";
  await store.init();
});

// 主题：设置页改 theme 时切 data-theme（M2 仅浅色视觉，dark 做基础反色）
watch(
  () => store.settings?.theme,
  (t) => {
    document.documentElement.dataset.theme = t === "dark" ? "dark" : "light";
  },
  { immediate: true },
);

const navItems = [
  { to: "/", label: "nav.home" },
  { to: "/monitor", label: "nav.monitor" },
  { to: "/logs", label: "nav.logs" },
  { to: "/profiles", label: "nav.profiles" },
  { to: "/settings", label: "nav.settings" },
] as const;
</script>

<template>
  <div class="shell">
    <aside class="sidebar">
      <h1 class="brand">{{ $t("app.title") }}</h1>
      <nav class="nav">
        <RouterLink v-for="item in navItems" :key="item.to" :to="item.to" class="nav-item">
          {{ $t(item.label) }}
        </RouterLink>
      </nav>
      <footer class="sidebar-foot">
        <span class="engine-dot" :class="{ on: store.isRunning, err: store.runState.phase === 'ERROR' }"></span>
        {{ $t(`engine.${store.runState.phase.toLowerCase()}`) }}
      </footer>
    </aside>
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>
