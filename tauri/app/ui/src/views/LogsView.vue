<script setup lang="ts">
import { computed, nextTick, ref, watch } from "vue";
import { api } from "../api";
import { formatLogTime } from "../format";
import { useEngineStore } from "../stores/engine";

const store = useEngineStore();
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
    title: "保存日志",
    defaultPath: "frpc-log.txt",
    filters: [{ name: "文本文件", extensions: ["txt"] }],
  });
  if (!target) return;
  await api.writeTextFile(target, await api.getLogsText());
  saved.value = `已保存到 ${target}`;
}

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
        <h2 class="page-title">日志</h2>
        <p class="small muted" style="margin: 0">
          显示 {{ filtered.length }} / 共 {{ store.logs.length }} 行 · 时间按本机时区
        </p>
      </div>
      <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
        <div class="field" style="width: 240px">
          <input v-model="query" placeholder="搜索" />
        </div>
        <button class="btn filled" @click="save">保存</button>
        <button class="btn filled" @click="store.clearLogs()">清空</button>
        <button class="btn" @click="openLogsDir">日志目录</button>
      </div>
    </div>

    <div style="display: flex; gap: 8px; align-items: center; flex-wrap: wrap">
      <button class="filter-chip" :class="{ selected: tab === 0 }" @click="tab = 0">
        连接日志 {{ store.logs.filter((l) => l.tag !== APP_TAG).length }}
      </button>
      <button class="filter-chip" :class="{ selected: tab === 1 }" @click="tab = 1">
        应用日志 {{ store.logs.filter((l) => l.tag === APP_TAG).length }}
      </button>
      <span class="muted">·</span>
      <button class="filter-chip" :class="{ selected: level === null }" @click="level = null">全部</button>
      <button
        class="filter-chip"
        :class="{ selected: level === 'ERROR' }"
        @click="level = level === 'ERROR' ? null : 'ERROR'"
      >
        错误
      </button>
      <button
        class="filter-chip"
        :class="{ selected: level === 'WARN' }"
        @click="level = level === 'WARN' ? null : 'WARN'"
      >
        警告
      </button>
      <button
        class="filter-chip"
        :class="{ selected: level === 'INFO' }"
        @click="level = level === 'INFO' ? null : 'INFO'"
      >
        信息
      </button>
      <button
        class="filter-chip"
        :class="{ selected: level === 'DEBUG' }"
        @click="level = level === 'DEBUG' ? null : 'DEBUG'"
      >
        调试
      </button>
      <label class="switch-row" style="gap: 8px; margin-left: 12px; width: auto">
        <span class="sw-label">自动滚动</span>
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
