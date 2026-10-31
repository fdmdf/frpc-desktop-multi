<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { api } from "../api";
import { formatDateTime, formatDuration, formatMemoryKb, formatPercent, snapshotAll, snapshotItem, targetServerProxyName } from "../format";
import { useEngineStore } from "../stores/engine";

const store = useEngineStore();
const now = ref(Date.now());
let timer: number | undefined;
onMounted(() => {
  timer = window.setInterval(() => (now.value = Date.now()), 1000);
});
onUnmounted(() => window.clearInterval(timer));

const currentProfile = computed(() => {
  const id = store.runState.profileId ?? store.settings?.defaultProfileId;
  return store.profiles.find((p) => p.id === id) ?? store.profiles[0];
});

const running = computed(() => store.runState.phase === "RUNNING");
const proxies = computed(() => currentProfile.value?.config.proxies ?? []);
const visitors = computed(() => currentProfile.value?.config.visitors ?? []);
const enabledProxies = computed(() => proxies.value.filter((p) => p.enabled !== false));
const enabledVisitors = computed(() => visitors.value.filter((v) => v.enabled !== false));

interface Chip {
  label: string;
  cls: string;
}
function statusChip(): Chip {
  const s = store.runState.phase;
  if (s === "RUNNING" && store.serverConnected === false) return { label: "未连上服务器", cls: "chip-error" };
  if (s === "RUNNING") return { label: "运行中", cls: "chip-running" };
  if (s === "STARTING") return { label: "启动中", cls: "chip-starting" };
  if (s === "STOPPING") return { label: "停止中", cls: "chip-starting" };
  if (s === "ERROR") return { label: "异常", cls: "chip-error" };
  return { label: "未运行", cls: "chip-idle" };
}

function proxyChip(proxy: (typeof proxies.value)[number], adminItem?: { status?: string } | undefined): Chip {
  const logState = store.proxyStates[proxy.name];
  if (proxy.enabled === false) return { label: "已禁用", cls: "chip-idle" };
  if (adminItem?.status === "running") return { label: "在线", cls: "chip-running" };
  if (adminItem?.status === "error") return { label: "错误", cls: "chip-error" };
  if (adminItem) return { label: adminItem.status ?? "", cls: "chip-idle" };
  if (logState?.phase === "RUNNING") return { label: "在线", cls: "chip-running" };
  if (logState?.phase === "ERROR") return { label: "错误", cls: "chip-error" };
  if (logState?.phase === "PENDING") return { label: "启动中", cls: "chip-starting" };
  if (running.value) return { label: "未知", cls: "chip-idle" };
  return { label: "未运行", cls: "chip-idle" };
}

function remoteOf(proxy: (typeof proxies.value)[number], adminItem?: { remoteAddr?: string; err?: string } | undefined): string {
  if (adminItem?.remoteAddr) return adminItem.remoteAddr;
  const domain = proxy.customDomains?.[0] || proxy.subdomain;
  if (proxy.remotePort != null && proxy.remotePort !== 0) return `远程 :${proxy.remotePort}`;
  if (proxy.remotePort === 0) return "远程端口随机";
  if (domain) return domain;
  return "私密代理（由访问者接入）";
}

const onlineSummary = computed(() => {
  const total = running.value ? store.activeCounts.proxies : enabledProxies.value.length;
  const fromAdmin = snapshotAll(store.status);
  const totalCount = fromAdmin ? fromAdmin.length : total;
  const online = fromAdmin.filter((i) => i.status === "running").length;
  return totalCount > 0 ? `${online}/${totalCount}` : "--";
});

const copied = ref("");
function copyRemote(text: string) {
  navigator.clipboard?.writeText(text).catch(() => {});
  copied.value = text;
  setTimeout(() => (copied.value = ""), 1500);
}

async function toggleEnabled(name: string, enabled: boolean, visitor: boolean) {
  const profile = currentProfile.value;
  if (!profile) return;
  const updated = JSON.parse(JSON.stringify(profile)) as typeof profile;
  if (visitor) {
    updated.config.visitors = updated.config.visitors.map((v) =>
      v.name === name ? { ...v, enabled: enabled ? null : false } : v,
    );
  } else {
    updated.config.proxies = updated.config.proxies.map((p) =>
      p.name === name ? { ...p, enabled: enabled ? null : false } : p,
    );
  }
  await store.saveProfile(updated);
  if (running.value) await store.reload();
}

const blockedReason = ref("");
const switchTargetId = ref("");
const switchTargetName = computed(
  () => store.profiles.find((p) => p.id === switchTargetId.value)?.name ?? "",
);

function onProfileSwitch(e: Event) {
  const target = (e.target as HTMLSelectElement).value;
  if (running.value && target !== currentProfile.value?.id) {
    switchTargetId.value = target;
    (e.target as HTMLSelectElement).value = currentProfile.value?.id ?? "";
  } else {
    void setDefaultProfile(target);
  }
}

async function setDefaultProfile(id: string) {
  const settings = store.settings;
  if (!settings) return;
  await store.saveSettings({ ...settings, defaultProfileId: id });
}

async function confirmSwitch() {
  const target = store.profiles.find((p) => p.id === switchTargetId.value);
  switchTargetId.value = "";
  if (!target) return;
  await setDefaultProfile(target.id);
  await store.stop();
  const deadline = Date.now() + 10_000;
  while (Date.now() < deadline) {
    const s = await api.getEngineState();
    if (s.phase === "IDLE" || s.phase === "ERROR") break;
    await new Promise((r) => setTimeout(r, 150));
  }
  try {
    await store.start(target.id);
  } catch (e) {
    blockedReason.value = String(e).replace(/^Error:\s*/, "");
  }
}

async function toggleRun() {
  if (running.value) {
    await store.stop();
    return;
  }
  const profile = currentProfile.value;
  if (!profile) return;
  try {
    await store.start(profile.id);
  } catch (e) {
    blockedReason.value = String(e).replace(/^Error:\s*/, "");
  }
}

function editProfile() {
  if (currentProfile.value) {
    store.editingProfileId = currentProfile.value.id;
  }
}

const uptime = computed(() =>
  store.runState.startedAtMs ? formatDuration(now.value - store.runState.startedAtMs) : "--",
);
</script>

<template>
  <div class="page">
    <div style="display: flex; align-items: center; justify-content: space-between">
      <div>
        <h2 class="page-title">运行状态</h2>
        <p class="page-sub">frpc 引擎 {{ store.engineVersion }}</p>
      </div>
      <div v-if="store.profiles.length > 1" class="field fixed-field">
        <span class="field-label">当前配置</span>
        <select :value="currentProfile?.id" @change="onProfileSwitch">
          <option v-for="p in store.profiles" :key="p.id" :value="p.id">{{ p.name }}</option>
        </select>
      </div>
    </div>

    <div class="section-card">
      <div style="display: flex; align-items: center; gap: 12px; flex-wrap: wrap">
        <span class="status-chip" :class="statusChip().cls">{{ statusChip().label }}</span>
        <strong>{{ store.runState.profileName || currentProfile?.name }}</strong>
        <span class="muted">
          {{ currentProfile ? `${currentProfile.config.serverAddr}:${currentProfile.config.serverPort}` : "" }}
        </span>
      </div>
      <p v-if="store.runState.pid || store.runState.startedAtMs || store.runState.restartCount > 0" class="muted" style="margin: 0">
        <template v-if="store.runState.pid">PID {{ store.runState.pid }}</template>
        <template v-if="store.runState.startedAtMs">
          {{ store.runState.pid ? " · " : "" }}启动于 {{ formatDateTime(store.runState.startedAtMs) }}
        </template>
        <template v-if="store.runState.restartCount > 0">
          · 已自动重启 {{ store.runState.restartCount }} 次
        </template>
      </p>
      <p v-if="store.runState.error" class="error-text" style="margin: 0">{{ store.runState.error }}</p>
      <p
        v-if="running && store.serverConnected === false"
        class="error-text"
        style="margin: 0"
      >
        连接 frps 失败：{{ store.loginError || "未知原因" }}。frpc 会持续重试；常见原因是 token
        不一致或服务器地址/端口不对，详情见日志页。
      </p>
      <div
        v-if="running && store.runState.adminEnabled && store.runState.adminPort > 0"
        style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap"
      >
        <button
          class="btn text-btn"
          @click="api.openUrl(`http://127.0.0.1:${store.runState.adminPort}/static/`)"
        >
          打开 frp 面板（浏览器需登录）
        </button>
        <span class="muted small">http://127.0.0.1:{{ store.runState.adminPort }}/static/</span>
        <span v-if="!store.adminReachable" class="error-text small">
          · 状态接口暂不可达（同机多个 frpc 时，检查端口 {{ store.runState.adminPort }} 是否被占用）
        </span>
      </div>
      <p v-if="!currentProfile" class="muted" style="margin: 0">
        还没有配置：先在「配置」里新建一份，或导入现成的 frpc.toml
      </p>
      <div style="display: flex; gap: 12px">
        <button
          class="btn filled"
          style="flex: 1; justify-content: center"
          :disabled="!currentProfile || store.runState.phase === 'STARTING' || store.runState.phase === 'STOPPING'"
          @click="toggleRun"
        >
          {{ running ? "停止" : "启动" }}
        </button>
        <button class="btn" :disabled="!running" @click="store.reload()">重载配置</button>
        <button class="btn" :disabled="!currentProfile" @click="editProfile">编辑配置</button>
      </div>
    </div>

    <div
      v-if="running && store.pendingReload && store.pendingReload === store.runState.profileId"
      class="section-card"
    >
      <h3 class="card-title">配置尚未生效</h3>
      <p class="error-text" style="margin: 0">配置已保存但还没有重载，frpc 仍在使用旧配置。</p>
      <button class="btn filled" style="justify-content: center" @click="store.reload()">重载配置</button>
    </div>

    <div class="section-card">
      <h3 class="card-title">概览</h3>
      <div class="stat-row">
        <div class="stat-tile">
          <div class="label">CPU</div>
          <div class="value">{{ formatPercent(store.metrics?.cpuPercent) }}</div>
        </div>
        <div class="stat-tile">
          <div class="label">内存</div>
          <div class="value">{{ formatMemoryKb(store.metrics?.rssKb) }}</div>
        </div>
        <div class="stat-tile">
          <div class="label">运行时长</div>
          <div class="value">{{ store.runState.startedAtMs ? uptime : "--" }}</div>
        </div>
        <div class="stat-tile">
          <div class="label">在线代理</div>
          <div class="value">{{ onlineSummary }}</div>
        </div>
      </div>
      <hr class="hr" />
      <p class="muted small" style="margin: 0">
        {{
          !running
            ? "启动后显示 CPU / 内存 / 运行时长与在线代理数"
            : store.runState.adminEnabled
              ? "代理状态与真实端口来自状态接口（webServer），每 4 秒刷新；访问者状态来自日志"
              : "未开启状态接口：代理与访问者状态都按日志推断，可能拿不到真实远端端口"
        }}
      </p>
    </div>

    <template v-if="proxies.length">
      <div style="display: flex; align-items: center; justify-content: space-between">
        <h3 class="page-title" style="font-size: 16px; color: var(--brand); margin: 4px 0 0">
          代理规则（{{ enabledProxies.length }}）
        </h3>
        <span v-if="copied" class="muted small">已复制：{{ copied }}</span>
      </div>

      <div v-for="proxy in proxies" :key="proxy.name" class="section-card">
        <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px">
          <div style="flex: 1; min-width: 0">
            <div style="font-size: 15px; font-weight: 500">{{ proxy.name }}</div>
            <div class="muted">
              {{ proxy.type.toUpperCase() }} · 本地 {{ proxy.localIP || "127.0.0.1" }}:{{ proxy.localPort ?? "?" }} →
              {{ remoteOf(proxy, snapshotItem(store.status, proxy.name)) }}
            </div>
            <div
              v-if="snapshotItem(store.status, proxy.name)?.err || store.proxyStates[proxy.name]?.error"
              class="error-text"
            >
              {{ snapshotItem(store.status, proxy.name)?.err || store.proxyStates[proxy.name]?.error }}
            </div>
          </div>
          <button
            class="icon-btn"
            title="复制访问地址"
            @click="copyRemote(remoteOf(proxy, snapshotItem(store.status, proxy.name)))"
          >
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />
            </svg>
          </button>
          <span class="status-chip" :class="proxyChip(proxy, snapshotItem(store.status, proxy.name)).cls">
            {{ proxyChip(proxy, snapshotItem(store.status, proxy.name)).label }}
          </span>
          <label class="switch">
            <input
              type="checkbox"
              :checked="proxy.enabled !== false"
              @change="toggleEnabled(proxy.name, ($event.target as HTMLInputElement).checked, false)"
            />
            <span class="track"></span><span class="thumb"></span>
          </label>
        </div>
      </div>
    </template>
    <div v-else class="section-card">
      <h3 class="card-title">代理规则</h3>
      <p class="muted" style="margin: 0">这个配置还没有代理规则，去「配置」里添加</p>
    </div>

    <template v-if="visitors.length">
      <h3 class="page-title" style="font-size: 16px; color: var(--brand); margin: 4px 0 0">
        访问者（{{ enabledVisitors.length }}）
      </h3>
      <div v-for="visitor in visitors" :key="visitor.name" class="section-card">
        <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px">
          <div style="flex: 1; min-width: 0">
            <div style="font-size: 15px; font-weight: 500">{{ visitor.name }}</div>
            <div class="muted">
              {{ visitor.type.toUpperCase() }} · 本地
              {{ visitor.bindPort < 0 ? "不监听端口" : `${visitor.bindAddr}:${visitor.bindPort}` }}
              · 访问
              {{ targetServerProxyName(currentProfile?.config.user, visitor.serverUser, visitor.serverName) }}
            </div>
          </div>
          <span
            class="status-chip"
            :class="
              visitor.enabled === false
                ? 'chip-idle'
                : running && store.visitors.includes(visitor.name)
                  ? 'chip-running'
                  : running
                    ? 'chip-starting'
                    : 'chip-idle'
            "
          >
            {{
              visitor.enabled === false
                ? "已禁用"
                : running && store.visitors.includes(visitor.name)
                  ? "在线"
                  : running
                    ? "启动中"
                    : "未运行"
            }}
          </span>
          <label class="switch">
            <input
              type="checkbox"
              :checked="visitor.enabled !== false"
              @change="toggleEnabled(visitor.name, ($event.target as HTMLInputElement).checked, true)"
            />
            <span class="track"></span><span class="thumb"></span>
          </label>
        </div>
      </div>
    </template>

    <div style="height: 8px"></div>

    <div v-if="switchTargetId" class="modal-mask" @click.self="switchTargetId = ''">
      <div class="modal" style="width: 480px">
        <h3 class="modal-title">切换配置</h3>
        <p style="margin: 0">
          frpc 正在运行「{{ currentProfile?.name }}」。切换到「{{ switchTargetName }}」会停止当前进程并用新配置重新启动，现有连接会中断。
        </p>
        <div class="modal-actions">
          <button class="btn text-btn" @click="switchTargetId = ''">取消</button>
          <button class="btn text-btn" style="color: var(--brand)" @click="confirmSwitch">切换并重启</button>
        </div>
      </div>
    </div>

    <div v-if="blockedReason" class="modal-mask" @click.self="blockedReason = ''">
      <div class="modal" style="width: 480px">
        <h3 class="modal-title">无法启动</h3>
        <p style="margin: 0">{{ blockedReason }}</p>
        <div class="modal-actions">
          <button class="btn text-btn" @click="blockedReason = ''">知道了</button>
          <button class="btn text-btn" style="color: var(--brand)" @click="blockedReason = ''; editProfile()">
            去配置
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
