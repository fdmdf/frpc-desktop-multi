<script setup lang="ts">
import { computed } from "vue";
import { formatDuration, formatMemoryKb, formatPercent, snapshotAll } from "../format";
import { useEngineStore } from "../stores/engine";

const store = useEngineStore();

const cpuHistory = computed(() =>
  store.metricsHistory.map((m) => m.cpu).filter((v): v is number => v != null),
);
const memHistory = computed(() =>
  store.metricsHistory.map((m) => m.rssKb == null ? null : m.rssKb / 1024).filter((v): v is number => v != null),
);

const now = computed(() => Date.now());

const phaseText = computed(() => {
  const s = store.runState;
  return {
    RUNNING: "frpc 正在运行",
    IDLE: "frpc 未运行",
    STARTING: "正在启动",
    STOPPING: "正在停止",
    ERROR: s.error ?? "运行异常",
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
    <h2 class="page-title">监控</h2>

    <div class="section-card">
      <h3 class="card-title">进程</h3>
      <div class="stat-row">
        <div class="stat-tile">
          <div class="label">PID</div>
          <div class="value">{{ store.runState.pid ?? "--" }}</div>
        </div>
        <div class="stat-tile">
          <div class="label">线程数</div>
          <div class="value">{{ store.metrics?.threads ?? "--" }}</div>
        </div>
        <div class="stat-tile">
          <div class="label">运行时长</div>
          <div class="value">
            {{ store.runState.startedAtMs ? formatDuration(now - store.runState.startedAtMs) : "--" }}
          </div>
        </div>
        <div class="stat-tile">
          <div class="label">自动重启</div>
          <div class="value">{{ store.runState.restartCount }}</div>
        </div>
      </div>
      <hr class="hr" />
      <p class="muted" style="margin: 0">{{ phaseText }}</p>
    </div>

    <div class="section-card">
      <h3 class="card-title">CPU（%）</h3>
      <p class="muted" style="margin: 0">
        当前 {{ formatPercent(store.metrics?.cpuPercent) }} · 峰值
        {{ cpuHistory.length ? formatPercent(Math.max(...cpuHistory)) : "--" }}
      </p>
      <p v-if="cpuHistory.length < 2" class="muted small" style="margin: 0">
        采样中…（每 2 秒采一次，凑够 2 个点才画曲线）
      </p>
      <svg class="spark" :viewBox="`0 0 600 120`" preserveAspectRatio="none">
        <line x1="0" y1="0" x2="600" y2="0" stroke="var(--border)" stroke-width="1" />
        <line x1="0" y1="60" x2="600" y2="60" stroke="var(--border)" stroke-width="1" />
        <line x1="0" y1="120" x2="600" y2="120" stroke="var(--border)" stroke-width="1" />
        <polyline :points="sparkline(cpuHistory, cpuMax)" fill="none" stroke="var(--brand)" stroke-width="2.2" />
      </svg>
    </div>

    <div class="section-card">
      <h3 class="card-title">内存（MB）</h3>
      <p class="muted" style="margin: 0">
        当前 {{ formatMemoryKb(store.metrics?.rssKb) }} · 峰值
        {{ memHistory.length ? formatMemoryKb(Math.max(...memHistory) * 1024) : "--" }}
      </p>
      <p v-if="memHistory.length < 2" class="muted small" style="margin: 0">采样中…（每 2 秒采一次）</p>
      <svg class="spark" :viewBox="`0 0 600 120`" preserveAspectRatio="none">
        <line x1="0" y1="0" x2="600" y2="0" stroke="var(--border)" stroke-width="1" />
        <line x1="0" y1="60" x2="600" y2="60" stroke="var(--border)" stroke-width="1" />
        <line x1="0" y1="120" x2="600" y2="120" stroke="var(--border)" stroke-width="1" />
        <polyline :points="sparkline(memHistory, memMax)" fill="none" stroke="var(--brand)" stroke-width="2.2" />
      </svg>
    </div>

    <div class="section-card">
      <h3 class="card-title">代理状态</h3>
      <p
        v-if="!statusRows.length && !Object.keys(store.proxyStates).length"
        class="muted"
        style="margin: 0"
      >
        暂无数据：启动 frpc 并开启状态监控后这里会显示实时状态
      </p>
      <div v-for="item in statusRows" :key="item.name" class="proxy-row" style="justify-content: space-between">
        <div style="flex: 1; min-width: 0">
          <div style="font-weight: 500">{{ item.name }}</div>
          <div class="muted small">{{ item.type }} · {{ item.addr }}</div>
          <div v-if="item.err" class="error-text small">{{ item.err }}</div>
        </div>
        <span class="status-chip" :class="item.status === 'running' ? 'chip-running' : 'chip-idle'">
          {{ item.status === "running" ? "在线" : item.status }}
        </span>
      </div>
      <div v-if="!statusRows.length">
        <div v-for="(logState, name) in store.proxyStates" :key="name" class="proxy-row" style="justify-content: space-between">
          <div style="font-weight: 500">{{ name }}</div>
          <span
            class="status-chip"
            :class="logState.phase === 'RUNNING' ? 'chip-running' : logState.phase === 'PENDING' ? 'chip-starting' : 'chip-error'"
          >
            {{ logState.phase === "RUNNING" ? "在线（日志）" : logState.phase === "PENDING" ? "启动中" : "错误" }}
          </span>
        </div>
      </div>
    </div>

    <div v-if="currentProfile?.config.visitors.length" class="section-card">
      <h3 class="card-title">访问者状态</h3>
      <div
        v-for="visitor in currentProfile.config.visitors"
        :key="visitor.name"
        class="proxy-row"
        style="justify-content: space-between"
      >
        <div style="flex: 1; min-width: 0">
          <div style="font-weight: 500">{{ visitor.name }}</div>
          <div class="muted small">
            {{ visitor.type.toUpperCase() }} · 本地
            {{ visitor.bindPort < 0 ? "不监听端口" : `${visitor.bindAddr}:${visitor.bindPort}` }}
            · 访问 {{ visitor.serverName }}
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
              ? "已禁用"
              : store.runState.phase !== "RUNNING"
                ? "未运行"
                : store.visitors.includes(visitor.name)
                  ? "在线"
                  : "启动中"
          }}
        </span>
      </div>
    </div>
  </div>
</template>
