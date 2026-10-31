<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "../api";
import { useEngineStore } from "../stores/engine";

const { t } = useI18n();
const store = useEngineStore();
const level = ref("ALL");
const keyword = ref("");

const filtered = computed(() =>
  store.logs.filter((l) => {
    if (level.value !== "ALL" && l.level !== level.value) return false;
    if (keyword.value && !l.message.toLowerCase().includes(keyword.value.toLowerCase())) return false;
    return true;
  }),
);

const levelClass = (lv: string) => ({
  TRACE: "lv-trace",
  DEBUG: "lv-debug",
  INFO: "lv-info",
  WARN: "lv-warn",
  ERROR: "lv-error",
}[lv] ?? "lv-info");

function fmtTime(ms: number): string {
  return new Date(ms).toLocaleTimeString(undefined, { hour12: false });
}

async function exportLogs() {
  const path = await api.exportLogs();
  alert(t("logs.exported", { path }));
}
</script>

<template>
  <section class="view">
    <h2>{{ $t("nav.logs") }}</h2>

    <div class="toolbar">
      <select v-model="level" class="input narrow">
        <option v-for="lv in ['ALL', 'TRACE', 'DEBUG', 'INFO', 'WARN', 'ERROR']" :key="lv" :value="lv">
          {{ $t(`logs.levels.${lv}`) }}
        </option>
      </select>
      <input v-model="keyword" class="input grow" :placeholder="$t('logs.search')" />
      <button class="btn" @click="exportLogs">{{ $t("logs.export") }}</button>
      <button class="btn" @click="store.clearLogs()">{{ $t("logs.clear") }}</button>
    </div>

    <div class="log-box">
      <p v-if="!filtered.length" class="muted">{{ $t("engine.noLog") }}</p>
      <div v-for="(l, i) in filtered" :key="i" class="log-line">
        <span class="log-time">{{ fmtTime(l.timeMs) }}</span>
        <span class="log-level" :class="levelClass(l.level)">{{ l.level }}</span>
        <span v-if="l.tag" class="log-tag">[{{ l.tag }}]</span>
        <span class="log-msg">{{ l.message }}</span>
      </div>
    </div>
  </section>
</template>
