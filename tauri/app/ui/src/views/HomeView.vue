<script setup lang="ts">
import { computed, onMounted, onUnmounted, ref } from "vue";
import { useRouter } from "vue-router";
import { useI18n } from "vue-i18n";
import { api } from "../api";
import { errorText, formatDateTime, formatDuration, formatMemoryKb, formatPercent, snapshotAll, snapshotItem, targetServerProxyName } from "../format";
import { useEngineStore } from "../stores/engine";

const store = useEngineStore();
const router = useRouter();
const { t } = useI18n();
// Android 上无法用 xdg-open 唤起浏览器，面板改成复制地址让用户自己粘贴
const isAndroid = /Android/i.test(navigator.userAgent || "");
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
  if (s === "RUNNING" && store.serverConnected === false)
    return { label: t("state.noServer"), cls: "chip-error" };
  if (s === "RUNNING") return { label: t("state.running"), cls: "chip-running" };
  if (s === "STARTING") return { label: t("state.starting"), cls: "chip-starting" };
  if (s === "STOPPING") return { label: t("state.stopping"), cls: "chip-starting" };
  if (s === "ERROR") return { label: t("state.error"), cls: "chip-error" };
  return { label: t("state.notRunning"), cls: "chip-idle" };
}

function proxyChip(proxy: (typeof proxies.value)[number], adminItem?: { status?: string } | undefined): Chip {
  const logState = store.proxyStates[proxy.name];
  if (proxy.enabled === false) return { label: t("state.disabled"), cls: "chip-idle" };
  if (adminItem?.status === "running") return { label: t("state.online"), cls: "chip-running" };
  if (adminItem?.status === "error") return { label: t("state.error"), cls: "chip-error" };
  if (adminItem) return { label: adminItem.status ?? "", cls: "chip-idle" };
  if (logState?.phase === "RUNNING") return { label: t("state.online"), cls: "chip-running" };
  if (logState?.phase === "ERROR") return { label: t("state.error"), cls: "chip-error" };
  if (logState?.phase === "PENDING") return { label: t("state.starting"), cls: "chip-starting" };
  if (running.value) return { label: t("common.unknown"), cls: "chip-idle" };
  return { label: t("state.notRunning"), cls: "chip-idle" };
}

/// 远程地址展示优先级：状态接口给的真实地址（域名或 IP + 分配到的端口）→ 配置里的
/// 自定义域名/子域名 → 固定远程端口（拼上服务器地址）→ 随机端口占位文案。
/// 真机出现过「随机端口一直显示『远程端口随机』」，根因是后端把 remote_addr 序列化成了
/// snake_case，前端读 remoteAddr 永远拿不到值 —— 已在 frpc-core::admin 修掉。
function remoteOf(
  proxy: (typeof proxies.value)[number],
  adminItem?: { remoteAddr?: string; err?: string } | undefined,
): string {
  const live = normalizeRemote(adminItem?.remoteAddr);
  if (live) return live;
  const domain = proxy.customDomains?.[0] || proxy.subdomain;
  if (domain) return domain;
  if (proxy.remotePort != null && proxy.remotePort !== 0) {
    const host = currentProfile.value?.config.serverAddr?.trim();
    return host ? `${host}:${proxy.remotePort}` : t("home.remotePort", { port: proxy.remotePort });
  }
  if (proxy.remotePort === 0) return t("home.remoteRandom");
  return t("home.privateProxy");
}

/// 状态接口偶尔只给「:端口」（不带主机部分），补上当前档案的服务器地址，
/// 否则复制出来的是一个没法直接用的裸端口。
function normalizeRemote(raw?: string | null): string {
  const v = (raw ?? "").trim();
  if (!v) return "";
  const host = currentProfile.value?.config.serverAddr?.trim() ?? "";
  if (/^:\d+$/.test(v)) return host ? `${host}${v}` : v.slice(1);
  if (/^\d+$/.test(v)) return host ? `${host}:${v}` : v;
  return v;
}

const onlineSummary = computed(() => {
  const total = running.value ? store.activeCounts.proxies : enabledProxies.value.length;
  const fromAdmin = snapshotAll(store.status);
  const totalCount = fromAdmin ? fromAdmin.length : total;
  const online = fromAdmin.filter((i) => i.status === "running").length;
  return totalCount > 0 ? `${online}/${totalCount}` : "--";
});

// 轻提示（复制结果 / 开关操作失败），底部居中显示
const toastMsg = ref("");
let toastTimer: number | undefined;
function toast(text: string) {
  toastMsg.value = text;
  window.clearTimeout(toastTimer);
  toastTimer = window.setTimeout(() => (toastMsg.value = ""), 2600);
}

/// 可复制的真实访问地址；拿不到（尚未分配端口 / 私密代理 / 还没上线）时返回 null
function copyableAddr(proxy: (typeof proxies.value)[number]): string | null {
  const item = snapshotItem(store.status, proxy.name);
  const live = normalizeRemote(item?.remoteAddr);
  if (live) return live;
  const domain = proxy.customDomains?.[0] || proxy.subdomain;
  if (domain) return domain;
  const host = currentProfile.value?.config.serverAddr?.trim();
  if (proxy.remotePort != null && proxy.remotePort !== 0) {
    return host ? `${host}:${proxy.remotePort}` : String(proxy.remotePort);
  }
  return null;
}

function visitorAddr(visitor: (typeof visitors.value)[number]): string | null {
  if (visitor.bindPort < 0) return null;
  return `${visitor.bindAddr}:${visitor.bindPort}`;
}

async function copyRemote(text: string | null) {
  if (!text) return;
  try {
    if (!navigator.clipboard) throw new Error(t("home.clipboardUnavailable"));
    await navigator.clipboard.writeText(text);
    toast(t("home.copied", { text }));
  } catch {
    toast(t("home.copyFailed", { text }));
  }
}

/// 开关入口：只翻转这一条规则的启用状态，运行中登记「待重载」并如实提示
/// —— 不能在这里做整份配置校验（运行中会把自己的 admin 端口报成被占用），
/// 也不该自动重载（用户需要知道旧配置还在生效）。
/// 保存失败要把 checkbox 拨回去：`:checked` 的绑定值没变时 Vue 不会再写 DOM。
async function onToggle(name: string, visitor: boolean, e: Event) {
  const input = e.target as HTMLInputElement;
  const next = input.checked;
  try {
    await toggleEnabled(name, next, visitor);
    if (running.value) {
      toast(t(next ? "home.ruleEnabledNeedReload" : "home.ruleDisabledNeedReload", { name }));
    } else {
      toast(t(next ? "home.ruleEnabled" : "home.ruleDisabled", { name }));
    }
  } catch (err) {
    input.checked = !next;
    toast(errorText(err));
  }
}

async function toggleEnabled(name: string, enabled: boolean, visitor: boolean) {
  const profile = currentProfile.value;
  if (!profile) return;
  await store.setRuleEnabled(profile.id, name, visitor, enabled);
}

/// 「重载配置」：等待真实生效（后端核对失败会自动退回重启），并把结果说清楚
async function doReload() {
  const before = store.runState.startedAtMs;
  try {
    await store.reload();
    const restarted = store.runState.startedAtMs !== before;
    toast(restarted ? t("home.reloadRestarted") : t("home.reloaded"));
  } catch (err) {
    toast(t("home.reloadFailed", { err: errorText(err) }));
  }
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
    blockedReason.value = errorText(e);
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
    blockedReason.value = errorText(e);
  }
}

const panelUrl = computed(() => `http://127.0.0.1:${store.runState.adminPort}/static/`);

function openPanel() {
  if (isAndroid) {
    void copyRemote(panelUrl.value);
    return;
  }
  void api.openUrl(panelUrl.value);
}

function editProfile() {
  if (!currentProfile.value) return;
  // 只置 editingProfileId 不够：配置页还没挂载，点了会像没反应 —— 必须同时跳转
  store.editingProfileId = currentProfile.value.id;
  void router.push("/profiles");
}

const uptime = computed(() =>
  store.runState.startedAtMs ? formatDuration(now.value - store.runState.startedAtMs) : "--",
);
</script>

<template>
  <div class="page">
    <div style="display: flex; align-items: center; justify-content: space-between">
      <div>
        <h2 class="page-title">{{ $t("home.title") }}</h2>
        <p class="page-sub">{{ $t("home.subtitle", { version: store.engineVersion }) }}</p>
      </div>
      <div v-if="store.profiles.length > 1" class="field fixed-field">
        <span class="field-label">{{ $t("home.currentProfile") }}</span>
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
          {{ store.runState.pid ? " · " : "" }}{{ $t("home.startedAt", { time: formatDateTime(store.runState.startedAtMs) }) }}
        </template>
        <template v-if="store.runState.restartCount > 0">
          · {{ $t("home.restarts", { n: store.runState.restartCount }) }}
        </template>
      </p>
      <p v-if="store.runState.error" class="error-text" style="margin: 0">{{ store.runState.error }}</p>
      <p
        v-if="running && store.serverConnected === false"
        class="error-text"
        style="margin: 0"
      >
        {{
          $t("home.loginFailed", {
            reason: store.loginError || $t("home.unknownReason"),
          })
        }}
      </p>
      <div
        v-if="running && store.runState.adminEnabled && store.runState.adminPort > 0"
        style="display: flex; align-items: center; gap: 10px; flex-wrap: wrap"
      >
        <button class="btn text-btn" @click="openPanel">
          {{ isAndroid ? $t("home.copyPanel") : $t("home.openPanel") }}
        </button>
        <span class="muted small">http://127.0.0.1:{{ store.runState.adminPort }}/static/</span>
        <span v-if="!store.adminReachable" class="error-text small">
          {{ $t("home.adminUnreachable", { port: store.runState.adminPort }) }}
        </span>
      </div>
      <p v-if="!currentProfile" class="muted" style="margin: 0">
        {{ $t("home.noProfile") }}
      </p>
      <div style="display: flex; gap: 12px">
        <button
          class="btn filled"
          style="flex: 1; justify-content: center"
          :disabled="!currentProfile || store.runState.phase === 'STARTING' || store.runState.phase === 'STOPPING'"
          @click="toggleRun"
        >
          {{ running ? $t("home.stop") : $t("home.start") }}
        </button>
        <button class="btn" :disabled="!running" @click="doReload()">{{ $t("home.reload") }}</button>
        <button class="btn" :disabled="!currentProfile" @click="editProfile">{{ $t("home.editConfig") }}</button>
      </div>
    </div>

    <div
      v-if="running && store.pendingReload && store.pendingReload === store.runState.profileId"
      class="section-card"
    >
      <h3 class="card-title">{{ $t("home.pendingTitle") }}</h3>
      <p class="error-text" style="margin: 0">{{ $t("home.pendingBody") }}</p>
      <button class="btn filled" style="justify-content: center" @click="doReload()">{{ $t("home.reload") }}</button>
    </div>

    <div class="section-card">
      <h3 class="card-title">{{ $t("home.overview") }}</h3>
      <div class="stat-row">
        <div class="stat-tile">
          <div class="label">{{ $t("home.cpu") }}</div>
          <div class="value">{{ formatPercent(store.metrics?.cpuPercent) }}</div>
        </div>
        <div class="stat-tile">
          <div class="label">{{ $t("home.memory") }}</div>
          <div class="value">{{ formatMemoryKb(store.metrics?.rssKb) }}</div>
        </div>
        <div class="stat-tile">
          <div class="label">{{ $t("home.uptime") }}</div>
          <div class="value">{{ store.runState.startedAtMs ? uptime : "--" }}</div>
        </div>
        <div class="stat-tile">
          <div class="label">{{ $t("home.onlineProxies") }}</div>
          <div class="value">{{ onlineSummary }}</div>
        </div>
      </div>
      <hr class="hr" />
      <p class="muted small" style="margin: 0">
        {{
          !running
            ? $t("home.hintIdle")
            : store.runState.adminEnabled
              ? $t("home.hintAdmin")
              : $t("home.hintLog")
        }}
      </p>
    </div>

    <template v-if="proxies.length">
      <div style="display: flex; align-items: center; justify-content: space-between">
        <h3 class="page-title" style="font-size: 16px; color: var(--brand); margin: 4px 0 0">
          {{ $t("home.proxiesTitle", { n: proxies.length }) }}<span
            v-if="proxies.length !== enabledProxies.length"
            class="muted small"
            style="font-weight: 400"
          >
            {{ $t("home.proxyCounts", { enabled: enabledProxies.length, disabled: proxies.length - enabledProxies.length }) }}
          </span>
        </h3>
        <span v-if="!isAndroid && toastMsg" class="muted small">{{ toastMsg }}</span>
      </div>

      <div v-for="proxy in proxies" :key="proxy.name" class="section-card">
        <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px">
          <div style="flex: 1; min-width: 0">
            <div style="font-size: 15px; font-weight: 500">{{ proxy.name }}</div>
            <!-- 箭头与地址之间用不换行空格：窄屏换行时整段一起下移，不会只剩一个「→」 -->
            <div class="muted">
              {{ proxy.type.toUpperCase() }} · {{ $t("home.local", { addr: `${proxy.localIP || "127.0.0.1"}:${proxy.localPort ?? "?"}` }) }}
              →&nbsp;{{ remoteOf(proxy, snapshotItem(store.status, proxy.name)) }}
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
            :title="$t('home.copyAddr')"
            :disabled="!copyableAddr(proxy)"
            @click="copyRemote(copyableAddr(proxy))"
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
              @change="onToggle(proxy.name, false, $event)"
            />
            <span class="track"></span><span class="thumb"></span>
          </label>
        </div>
      </div>
    </template>
    <div v-else class="section-card">
      <h3 class="card-title">{{ $t("home.proxyRules") }}</h3>
      <p class="muted" style="margin: 0">{{ $t("home.noRules") }}</p>
    </div>

    <template v-if="visitors.length">
      <h3 class="page-title" style="font-size: 16px; color: var(--brand); margin: 4px 0 0">
        {{ $t("home.visitorsTitle", { n: enabledVisitors.length }) }}
      </h3>
      <div v-for="visitor in visitors" :key="visitor.name" class="section-card">
        <div style="display: flex; align-items: center; justify-content: space-between; gap: 8px">
          <div style="flex: 1; min-width: 0">
            <div style="font-size: 15px; font-weight: 500">{{ visitor.name }}</div>
            <div class="muted">
              {{ visitor.type.toUpperCase() }} ·
              {{
                visitor.bindPort < 0
                  ? $t("home.noBindPort")
                  : $t("home.local", { addr: `${visitor.bindAddr}:${visitor.bindPort}` })
              }}
              · {{ $t("home.accessTo", { name: targetServerProxyName(currentProfile?.config.user, visitor.serverUser, visitor.serverName) }) }}
            </div>
          </div>
          <button
            class="icon-btn"
            :title="$t('home.copyBind')"
            :disabled="!visitorAddr(visitor)"
            @click="copyRemote(visitorAddr(visitor))"
          >
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />
            </svg>
          </button>
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
                ? $t("state.disabled")
                : running && store.visitors.includes(visitor.name)
                  ? $t("state.online")
                  : running
                    ? $t("state.starting")
                    : $t("state.notRunning")
            }}
          </span>
          <label class="switch">
            <input
              type="checkbox"
              :checked="visitor.enabled !== false"
              @change="onToggle(visitor.name, true, $event)"
            />
            <span class="track"></span><span class="thumb"></span>
          </label>
        </div>
      </div>
    </template>

    <div style="height: 8px"></div>

    <!-- 复制结果提示：放在底部固定层，任何位置点了复制都看得见 -->
    <div v-if="toastMsg" class="toast">{{ toastMsg }}</div>

    <div v-if="switchTargetId" class="modal-mask" @click.self="switchTargetId = ''">
      <div class="modal" style="width: 480px">
        <h3 class="modal-title">{{ $t("home.switchTitle") }}</h3>
        <p style="margin: 0">
          {{ $t("home.switchBody", { current: currentProfile?.name ?? "", target: switchTargetName }) }}
        </p>
        <div class="modal-actions">
          <button class="btn text-btn" @click="switchTargetId = ''">{{ $t("common.cancel") }}</button>
          <button class="btn text-btn" style="color: var(--brand)" @click="confirmSwitch">{{ $t("home.switchConfirm") }}</button>
        </div>
      </div>
    </div>

    <div v-if="blockedReason" class="modal-mask" @click.self="blockedReason = ''">
      <div class="modal" style="width: 480px">
        <h3 class="modal-title">{{ $t("home.cannotStart") }}</h3>
        <p style="margin: 0">{{ blockedReason }}</p>
        <div class="modal-actions">
          <button class="btn text-btn" @click="blockedReason = ''">{{ $t("common.gotIt") }}</button>
          <button class="btn text-btn" style="color: var(--brand)" @click="blockedReason = ''; editProfile()">
            {{ $t("home.goProfiles") }}
          </button>
        </div>
      </div>
    </div>
  </div>
</template>
