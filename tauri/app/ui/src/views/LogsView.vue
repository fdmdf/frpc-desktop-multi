<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import { api } from "../api";
import { formatLogTime } from "../format";
import { useEngineStore } from "../stores/engine";

const store = useEngineStore();
const { t } = useI18n();
const level = ref<string | null>(null);
const query = ref("");
const autoScroll = ref(true);
const saved = ref("");
const tab = ref(0); // 0=连接日志 1=应用日志
const listEl = ref<HTMLElement | null>(null);

const APP_TAG = "app";

const filtered = computed(() =>
  store.logs.filter((line) => {
    const byTab = tab.value === 0 ? line.tag !== APP_TAG : line.tag === APP_TAG;
    return (
      byTab &&
      (level.value === null || line.level === level.value) &&
      (!query.value || line.message.toLowerCase().includes(query.value.toLowerCase()))
    );
  }),
);

async function scrollBottom() {
  if (!autoScroll.value || !filtered.value.length) return;
  await nextTick();
  listEl.value?.scrollTo({ top: listEl.value.scrollHeight });
}
watch(filtered, scrollBottom, { immediate: true });
// 重新打开「自动滚动」时立刻跳到底部，否则要等下一条日志才有反应
watch(autoScroll, (on) => {
  if (on) void scrollBottom();
});

const levelColor = (lv: string) =>
  lv === "ERROR"
    ? "var(--error)"
    : lv === "WARN"
      ? "var(--starting)"
      : lv === "DEBUG" || lv === "TRACE"
        ? "var(--text-2)"
        : "var(--text)";

async function save() {
  // 对齐旧版 FileDialog.SAVE：弹系统保存对话框自选位置
  const { save: saveDialog } = await import("@tauri-apps/plugin-dialog");
  const target = await saveDialog({
    title: t("logs.saveDialogTitle"),
    defaultPath: "frpc-log.txt",
    filters: [{ name: t("logs.textFile"), extensions: ["txt"] }],
  });
  if (!target) return;
  // 所见即所得：界面上有筛选（标签页 / 级别 / 搜索）时只导出筛选出来的行
  const filtering = filtered.value.length !== store.logs.length;
  const text = filtering
    ? filtered.value.map((l) => `${formatLogTime(l.timeMs)} ${l.message}`).join("\n")
    : await api.getLogsText();
  await api.writeTextFile(target, text);
  saved.value = filtering
    ? t("logs.savedFiltered", { n: filtered.value.length, path: target })
    : t("logs.saved", { path: target });
}

// Android 属于 unix 但没有 xdg-open，也没有可唤起的文件管理器
const isAndroid = /Android/i.test(navigator.userAgent || "");

async function openLogsDir() {
  const dataDir = await api.getDataDir();
  const sep = dataDir.includes("\\") ? "\\" : "/";
  await api.openPath(`${dataDir.replace(/[\\/]+$/, "")}${sep}logs`);
}
</script>

<template>
  <div class="page" style="height: 100%; max-width: none">
    <div style="display: flex; align-items: flex-end; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <div>
        <h2 class="page-title">{{ $t("logs.title") }}</h2>
        <p class="small muted" style="margin: 0">
          {{ $t("logs.showing", { shown: filtered.length, total: store.logs.length }) }}
        </p>
      </div>
      <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
        <div class="field fixed-field">
          <input v-model="query" :placeholder="$t('logs.search')" />
        </div>
        <button class="btn filled" @click="save">{{ $t("logs.save") }}</button>
        <button class="btn filled" @click="store.clearLogs()">{{ $t("logs.clear") }}</button>
        <button v-if="!isAndroid" class="btn" @click="openLogsDir">{{ $t("logs.openDir") }}</button>
      </div>
    </div>

    <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
      <button class="filter-chip" :class="{ selected: tab === 0 }" @click="tab = 0">
        {{ $t("logs.tabConnection", { n: store.logs.filter((l) => l.tag !== APP_TAG).length }) }}
      </button>
      <button class="filter-chip" :class="{ selected: tab === 1 }" @click="tab = 1">
        {{ $t("logs.tabApp", { n: store.logs.filter((l) => l.tag === APP_TAG).length }) }}
      </button>
      <span class="muted">·</span>
      <button class="filter-chip" :class="{ selected: level === null }" @click="level = null">{{ $t("logs.all") }}</button>
      <button
        class="filter-chip"
        :class="{ selected: level === 'ERROR' }"
        @click="level = level === 'ERROR' ? null : 'ERROR'"
      >
        {{ $t("logs.levelError") }}
      </button>
      <button
        class="filter-chip"
        :class="{ selected: level === 'WARN' }"
        @click="level = level === 'WARN' ? null : 'WARN'"
      >
        {{ $t("logs.levelWarn") }}
      </button>
      <button
        class="filter-chip"
        :class="{ selected: level === 'INFO' }"
        @click="level = level === 'INFO' ? null : 'INFO'"
      >
        {{ $t("logs.levelInfo") }}
      </button>
      <button
        class="filter-chip"
        :class="{ selected: level === 'DEBUG' }"
        @click="level = level === 'DEBUG' ? null : 'DEBUG'"
      >
        {{ $t("logs.levelDebug") }}
      </button>
      <label class="switch-row" style="gap: 8px; margin-left: 12px; width: auto">
        <span class="sw-label">{{ $t("logs.autoScroll") }}</span>
        <span class="switch">
          <input v-model="autoScroll" type="checkbox" />
          <span class="track"></span><span class="thumb"></span>
        </span>
      </label>
      <span v-if="saved" class="muted small">{{ saved }}</span>
    </div>

    <div ref="listEl" class="log-list">
      <div v-for="(line, i) in filtered" :key="i" class="log-line">
        <span class="log-time">{{ formatLogTime(line.timeMs) }}</span>
        <span :style="{ color: levelColor(line.level) }">{{ line.message }}</span>
      </div>
    </div>
  </div>
</template>
