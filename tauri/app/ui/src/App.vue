<script setup lang="ts">
import { useAppStore } from "./stores/app";

const app = useAppStore();

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
        <span class="engine-dot" :class="{ on: app.engineRunning }"></span>
        {{ app.engineRunning ? $t("engine.running") : $t("engine.idle") }}
      </footer>
    </aside>
    <main class="content">
      <RouterView />
    </main>
  </div>
</template>
