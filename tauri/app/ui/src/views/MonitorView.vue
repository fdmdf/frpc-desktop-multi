<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { formatDuration, formatMemoryKb, formatPercent, snapshotAll } from "../format";
import { useEngineStore } from "../stores/engine";

const store = useEngineStore();
const { t } = useI18n();

const cpuHistory = computed(() =>
  store.metricsHistory.map((m) => m.cpu).filter((v): v is number => v != null),
);
const memHistory = computed(() =>
  store.metricsHistory.map((m) => m.rssKb == null ? null : m.rssKb / 1024).filter((v): v is number => v != null),
);

// 运行时长要自己走：computed 里没有响应式依赖，Date.now() 只会算一次（旧实现导致时长停滞）
const now = ref(Date.now());
let timer: number | undefined;
onMounted(() => {
  timer = window.setInterval(() => (now.value = Date.now()), 1000);
});
onUnmounted(() => window.clearInterval(timer));

const phaseText = computed(() => {
  const s = store.runState;
  return {
    RUNNING: t("monitor.phaseRunning"),
    IDLE: t("monitor.phaseIdle"),
    STARTING: t("monitor.phaseStarting"),
    STOPPING: t("monitor.phaseStopping"),
    ERROR: s.error ?? t("monitor.phaseError"),
  }[s.phase];
});

function sparkline(values: number[], maxValue: number, w = 600, h = 120): string {
  if (values.length < 2) return "";
  const stepX = w / (values.length - 1);
  return values
    .map((v, i) => {
      const x = stepX * i;
      const y = h - (v / maxValue) * (h - 4) - 2;
      return `${x.toFixed(1)},${y.toFixed(1)}`;
    })
    .join(" ");
}

const cpuMax = computed(() => Math.max(...cpuHistory.value, 1));
const memMax = computed(() => Math.max(...memHistory.value, 1));

interface StatusRow {
  name: string;
  type: string;
  status: string;
  err: string;
  addr: string;
}
const statusRows = computed<StatusRow[]>(() => {
  return snapshotAll(store.status).map((i) => ({
    name: i.name,
    type: i.type.toUpperCase(),
    status: i.status,
    err: i.err,
    addr: i.remoteAddr || i.localAddr || "",
  }));
});

const currentProfile = computed(() =>
  store.profiles.find((p) => p.id === store.runState.profileId),
);
</script>

<template>
  <div class="page">
    <h2 class="page-title">{{ $t("monitor.title") }}</h2>

    <div class="section-card">
      <h3 class="card-title">{{ $t("monitor.process") }}</h3>
      <div class="stat-row">
        <div class="stat-tile">
          <div class="label">{{ $t("monitor.pid") }}</div>
          <div class="value">{{ store.runState.pid ?? "--" }}</div>
        </div>
        <div class="stat-tile">
          <div class="label">{{ $t("monitor.threads") }}</div>
          <div class="value">{{ store.metrics?.threads ?? "--" }}</div>
        </div>
        <div class="stat-tile">
          <div class="label">{{ $t("monitor.uptime") }}</div>
          <div class="value">
            {{ store.runState.startedAtMs ? formatDuration(now - store.runState.startedAtMs) : "--" }}
          </div>
        </div>
        <div class="stat-tile">
          <div class="label">{{ $t("monitor.restarts") }}</div>
          <div class="value">{{ store.runState.restartCount }}</div>
        </div>
      </div>
      <hr class="hr" />
      <p class="muted" style="margin: 0">{{ phaseText }}</p>
    </div>

    <div class="section-card">
      <h3 class="card-title">{{ $t("monitor.cpuTitle") }}</h3>
      <p class="muted" style="margin: 0">
        {{
          $t("monitor.currentPeak", {
            current: formatPercent(store.metrics?.cpuPercent),
            peak: cpuHistory.length ? formatPercent(Math.max(...cpuHistory)) : "--",
          })
        }}
      </p>
      <p v-if="cpuHistory.length < 2" class="muted small" style="margin: 0">
        {{ $t("monitor.sampling") }}
      </p>
      <svg class="spark" :viewBox="`0 0 600 120`" preserveAspectRatio="none">
        <line x1="0" y1="0" x2="600" y2="0" stroke="var(--border)" stroke-width="1" />
        <line x1="0" y1="60" x2="600" y2="60" stroke="var(--border)" stroke-width="1" />
        <line x1="0" y1="120" x2="600" y2="120" stroke="var(--border)" stroke-width="1" />
        <polyline :points="sparkline(cpuHistory, cpuMax)" fill="none" stroke="var(--brand)" stroke-width="2.2" />
      </svg>
    </div>

    <div class="section-card">
      <h3 class="card-title">{{ $t("monitor.memTitle") }}</h3>
      <p class="muted" style="margin: 0">
        {{
          $t("monitor.currentPeak", {
            current: formatMemoryKb(store.metrics?.rssKb),
            peak: memHistory.length ? formatMemoryKb(Math.max(...memHistory) * 1024) : "--",
          })
        }}
      </p>
      <p v-if="memHistory.length < 2" class="muted small" style="margin: 0">{{ $t("monitor.samplingShort") }}</p>
      <svg class="spark" :viewBox="`0 0 600 120`" preserveAspectRatio="none">
        <line x1="0" y1="0" x2="600" y2="0" stroke="var(--border)" stroke-width="1" />
        <line x1="0" y1="60" x2="600" y2="60" stroke="var(--border)" stroke-width="1" />
        <line x1="0" y1="120" x2="600" y2="120" stroke="var(--border)" stroke-width="1" />
        <polyline :points="sparkline(memHistory, memMax)" fill="none" stroke="var(--brand)" stroke-width="2.2" />
      </svg>
    </div>

    <div class="section-card">
      <h3 class="card-title">{{ $t("monitor.proxyStatus") }}</h3>
      <p
        v-if="!statusRows.length && !Object.keys(store.proxyStates).length"
        class="muted"
        style="margin: 0"
      >
        {{ $t("monitor.noData") }}
      </p>
      <div v-for="item in statusRows" :key="item.name" class="proxy-row" style="justify-content: space-between">
        <div style="flex: 1; min-width: 0">
          <div style="font-weight: 500">{{ item.name }}</div>
          <div class="muted small">{{ item.type }} · {{ item.addr }}</div>
          <div v-if="item.err" class="error-text small">{{ item.err }}</div>
        </div>
        <span class="status-chip" :class="item.status === 'running' ? 'chip-running' : 'chip-idle'">
          {{ item.status === "running" ? $t("state.online") : item.status }}
        </span>
      </div>
      <div v-if="!statusRows.length">
        <div v-for="(logState, name) in store.proxyStates" :key="name" class="proxy-row" style="justify-content: space-between">
          <div style="font-weight: 500">{{ name }}</div>
          <span
            class="status-chip"
            :class="logState.phase === 'RUNNING' ? 'chip-running' : logState.phase === 'PENDING' ? 'chip-starting' : 'chip-error'"
          >
            {{ logState.phase === "RUNNING" ? $t("state.onlineByLog") : logState.phase === "PENDING" ? $t("state.starting") : $t("state.error") }}
          </span>
        </div>
      </div>
    </div>

    <div v-if="currentProfile?.config.visitors.length" class="section-card">
      <h3 class="card-title">{{ $t("monitor.visitorStatus") }}</h3>
      <div
        v-for="visitor in currentProfile.config.visitors"
        :key="visitor.name"
        class="proxy-row"
        style="justify-content: space-between"
      >
        <div style="flex: 1; min-width: 0">
          <div style="font-weight: 500">{{ visitor.name }}</div>
          <div class="muted small">
            {{ visitor.type.toUpperCase() }} ·
            {{
              visitor.bindPort < 0
                ? $t("home.noBindPort")
                : $t("home.local", { addr: `${visitor.bindAddr}:${visitor.bindPort}` })
            }}
            · {{ $t("home.accessTo", { name: visitor.serverName }) }}
          </div>
        </div>
        <span
          class="status-chip"
          :class="
            visitor.enabled === false || store.runState.phase !== 'RUNNING'
              ? 'chip-idle'
              : store.visitors.includes(visitor.name)
                ? 'chip-running'
                : 'chip-starting'
          "
        >
          {{
            visitor.enabled === false
              ? $t("state.disabled")
              : store.runState.phase !== "RUNNING"
                ? $t("state.notRunning")
                : store.visitors.includes(visitor.name)
                  ? $t("state.online")
                  : $t("state.starting")
          }}
        </span>
      </div>
    </div>
  </div>
</template>
