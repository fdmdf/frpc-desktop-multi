<script setup lang="ts">
import { computed, ref, watch } from "vue";
import { useI18n } from "vue-i18n";
import {
  api,
  emptyConfig,
  type ProxyConfig,
  type ProxyPluginConfig,
  type ServerProfile,
  type ValidationIssue,
  type VisitorConfig,
} from "../api";
import {
  describeProxy,
  describeVisitor,
  parsePortExpr,
  PROXY_TYPES,
  proxyNameFor,
  validationText,
  VISITOR_TYPES,
  typeCaps,
} from "../format";
import { useEngineStore } from "../stores/engine";

const store = useEngineStore();
const { t } = useI18n();

// ---------- 列表态 ----------
const query = ref("");
const message = ref("");
const deleteTarget = ref<ServerProfile | null>(null);
const clearAllConfirm = ref(false);
const bundleFile = ref<HTMLInputElement | null>(null);

const visible = computed(() =>
  store.profiles.filter(
    (p) =>
      !query.value ||
      p.name.toLowerCase().includes(query.value.toLowerCase()) ||
      `${p.config.serverAddr}:${p.config.serverPort}`.toLowerCase().includes(query.value.toLowerCase()),
  ),
);

function newDraft(): ServerProfile {
  return {
    id: crypto.randomUUID(),
    name: t("editor.newTitle"),
    config: { ...emptyConfig(), auth: { method: "token", token: "" } },
    createdAt: Date.now(),
    updatedAt: Date.now(),
    lastUsedAt: 0,
  };
}

// ---------- 编辑态（全页，对齐 ProfileEditScreen） ----------
const draft = ref<ServerProfile | null>(null);
const isNew = computed(() => draft.value && !store.profiles.some((p) => p.id === draft.value!.id));
const showErrors = ref(false);
const saveError = ref("");
const proxyQuery = ref("");
const showTransportAdvanced = ref(false);
const showTlsAdvanced = ref(false);
// 「自定义传输配置」开关：编辑实例始终存在（表单可安全绑定），
// 关闭时仅在保存时把 transport 置 null（不写 transport 段 = 全用 frpc 默认值）
const customTransport = ref(true);

function defaultTransport(): NonNullable<ServerProfile["config"]["transport"]> {
  return {
    protocol: "tcp",
    tcpMux: true,
    poolCount: 0,
    tls: null,
    dialServerTimeout: 10,
    dialServerKeepalive: 7200,
    connectServerLocalIP: null,
    proxyURL: null,
    heartbeatInterval: null,
    heartbeatTimeout: null,
    tcpMuxKeepaliveInterval: null,
  };
}

function defaultLog(): NonNullable<ServerProfile["config"]["log"]> {
  // to 空串 = 不写日志文件（frpc 默认输出到控制台）
  return { to: "", level: "info", maxDays: 3, disablePrintColor: false };
}

const proxyDialog = ref(false);
const proxyEditingIndex = ref<number | null>(null);
const proxyDraft = ref<ProxyConfig | null>(null);
const proxyBatchPorts = ref("");
const proxyBatchError = ref("");
const visitorDialog = ref(false);
const visitorEditingIndex = ref<number | null>(null);
const visitorDraft = ref<VisitorConfig | null>(null);
const confirmSaveRunning = ref(false);

const runningThis = computed(
  () => draft.value && store.runningId === draft.value.id,
);

// 从主页「编辑配置」跳过来时，editingProfileId 已经设好了 —— 组件刚挂载，
// 普通 watch 不会触发，必须 immediate；但要等 store.init() 拉完档案，
// 否则会把「找不到档案」当成新建配置。
let openedFor: string | null = null;
watch(
  () => [store.editingProfileId, store.ready] as const,
  ([id, ready]) => {
    if (!ready || !id) return;
    const profile = store.profiles.find((p) => p.id === id);
    if (!profile || openedFor === id) return;
    openedFor = id;
    openEditor(profile);
  },
  { immediate: true },
);

function openEditor(profile: ServerProfile | null) {
  const copy: ServerProfile = profile ? JSON.parse(JSON.stringify(profile)) : newDraft();
  for (const p of copy.config.proxies) {
    p.transport = p.transport ?? {
      bandwidthLimit: null,
      bandwidthLimitMode: null,
      useEncryption: null,
      useCompression: null,
      proxyProtocolVersion: null,
    };
  }
  // 可空段统一实例化为默认值：模板可安全绑定；保存时按开关决定是否写回。
  // （此前 transport/log 为 null 时模板裸访问直接抛错 → 点「编辑」整页白屏）
  customTransport.value = copy.config.transport != null || profile == null;
  copy.config.transport = copy.config.transport ?? defaultTransport();
  copy.config.log = copy.config.log ?? defaultLog();
  // Kotlin decode 语义：缺 [auth] 段 = method "none"
  copy.config.auth = copy.config.auth ?? { method: "none", token: "" };
  draft.value = copy;
  showErrors.value = false;
  showTransportAdvanced.value = false;
  showTlsAdvanced.value = false;
  saveError.value = "";
}

function closeEditor() {
  draft.value = null;
  store.editingProfileId = null;
  openedFor = null;
}

function toggleTlsAdvanced() {
  showTlsAdvanced.value = !showTlsAdvanced.value;
  // 展开双向认证 / 私有 CA 前确保 tls 段存在，否则模板裸访问会炸
  if (showTlsAdvanced.value && draft.value && !draft.value.config.transport?.tls) {
    draft.value.config.transport = {
      ...(draft.value.config.transport ?? defaultTransport()),
      tls: {
        enable: true,
        serverName: null,
        certFile: null,
        keyFile: null,
        trustedCaFile: null,
        disableCustomTLSFirstByte: null,
      },
    };
  }
}

// 校验走后端（含端口占用实测），保存时触发
const errorIssues = ref<ValidationIssue[]>([]);
const warningIssues = ref<ValidationIssue[]>([]);

async function save() {
  if (!draft.value) return;
  saveError.value = "";
  // 端口文本落到 config（serverPort / webServer.port 已双向绑定，无需额外处理）
  const all = await api.validateConfig(draft.value.config);
  errorIssues.value = all.filter((i) => i.severity === "ERROR");
  warningIssues.value = all.filter((i) => i.severity === "WARNING");
  if (errorIssues.value.length) {
    showErrors.value = true;
    return;
  }
  const name = draft.value.name.trim() || t("err.untitled");
  if (store.profiles.some((p) => p.name === name && p.id !== draft.value!.id)) {
    saveError.value = t("err.duplicateProfile", { name });
    showErrors.value = false;
    return;
  }
  if (runningThis.value) {
    confirmSaveRunning.value = true;
    return;
  }
  await doSave(false);
}

async function doSave(reload: boolean) {
  if (!draft.value) return;
  draft.value.name = draft.value.name.trim() || t("err.untitled");
  draft.value.updatedAt = Date.now();
  // 「自定义传输配置」关闭 = 不写 transport 段（全用 frpc 默认值）
  if (!customTransport.value) draft.value.config.transport = null;
  await store.saveProfile(draft.value);
  if (!store.settings!.defaultProfileId) {
    await store.saveSettings({ ...store.settings!, defaultProfileId: draft.value.id });
  }
  if (runningThis.value) {
    if (reload) {
      store.clearPendingReload();
      await store.reload();
    } else {
      store.markPendingReload(draft.value.id);
    }
  }
  closeEditor();
}

// ---------- 代理对话框 ----------
function addProxy() {
  proxyDraft.value = {
    name: "",
    type: "tcp",
    enabled: true,
    localIP: "127.0.0.1",
    localPort: null,
    ...( { remotePort: null } as Partial<ProxyConfig> ),
    transport: {
      bandwidthLimit: null,
      bandwidthLimitMode: null,
      useEncryption: null,
      useCompression: null,
      proxyProtocolVersion: null,
    },
  };
  proxyBatchPorts.value = "";
  proxyBatchError.value = "";
  proxyEditingIndex.value = null;
  proxyDialog.value = true;
}

function editProxy(index: number) {
  proxyDraft.value = JSON.parse(JSON.stringify(draft.value!.config.proxies[index]));
  if (!proxyDraft.value!.transport) {
    proxyDraft.value!.transport = {
      bandwidthLimit: null,
      bandwidthLimitMode: null,
      useEncryption: null,
      useCompression: null,
      proxyProtocolVersion: null,
    };
  }
  proxyBatchPorts.value = "";
  proxyBatchError.value = "";
  proxyEditingIndex.value = index;
  proxyDialog.value = true;
}

function saveProxy() {
  const d = proxyDraft.value;
  if (!d || !draft.value) return;
  const taken = draft.value.config.proxies
    .filter((_, i) => i !== proxyEditingIndex.value)
    .map((p) => p.name);
  const caps = typeCaps(d.type);
  if (caps.remotePort && proxyBatchPorts.value.trim()) {
    const parsed = parsePortExpr(proxyBatchPorts.value);
    if (!parsed.ok) {
      proxyBatchError.value = parsed.reason;
      return;
    }
    const created = parsed.ports.map((port) => ({
      ...JSON.parse(JSON.stringify(d)),
      name: proxyNameFor(d.name || "proxy", port),
      remotePort: port,
    })) as ProxyConfig[];
    // 批量生成也要查重：否则会和已有规则撞名，只能等保存时后端校验才报出来
    const conflicts = created.map((c) => c.name).filter((n) => taken.includes(n));
    if (conflicts.length) {
      proxyBatchError.value = t("err.duplicateProxyList", {
        list: conflicts.slice(0, 3).join(", "),
        more: conflicts.length > 3 ? t("err.duplicateProxyMore") : "",
      });
      return;
    }
    applyProxySave(created);
    return;
  }
  if (!d.name.trim()) {
    proxyBatchError.value = t("err.proxyNameEmpty");
    return;
  }
  if (taken.includes(d.name.trim())) {
    proxyBatchError.value = t("err.duplicateProxy", { name: d.name.trim() });
    return;
  }
  applyProxySave([JSON.parse(JSON.stringify({ ...d, name: d.name.trim() }))]);
}

function applyProxySave(created: ProxyConfig[]) {
  const list = [...draft.value!.config.proxies];
  const index = proxyEditingIndex.value;
  if (index == null) {
    list.push(...created);
  } else {
    list[index] = created[0];
    if (created.length > 1) list.splice(index + 1, 0, ...created.slice(1));
  }
  draft.value!.config.proxies = list;
  proxyDialog.value = false;
}

// ---------- 访问者对话框 ----------
function addVisitor() {
  visitorDraft.value = {
    name: "",
    type: "stcp",
    serverName: "",
    secretKey: null,
    bindAddr: "127.0.0.1",
    bindPort: 6000,
    serverUser: null,
    enabled: true,
  };
  visitorEditingIndex.value = null;
  visitorError.value = "";
  visitorDialog.value = true;
}

function editVisitor(index: number) {
  visitorDraft.value = JSON.parse(JSON.stringify(draft.value!.config.visitors[index]));
  visitorEditingIndex.value = index;
  visitorError.value = "";
  visitorDialog.value = true;
}

const visitorError = ref("");

function saveVisitor() {
  const v = visitorDraft.value;
  if (!v || !draft.value) return;
  visitorError.value = "";
  const name = v.name.trim();
  // 与代理对话框对齐：名称必填且不能重名（此前只静默 return，点了保存像没反应）
  if (!name) {
    visitorError.value = t("err.visitorNameEmpty");
    return;
  }
  const taken = draft.value.config.visitors
    .filter((_, i) => i !== visitorEditingIndex.value)
    .map((x) => x.name);
  if (taken.includes(name)) {
    visitorError.value = t("err.duplicateVisitor", { name });
    return;
  }
  const list = [...draft.value.config.visitors];
  const saved = { ...JSON.parse(JSON.stringify(v)), name };
  if (visitorEditingIndex.value == null) list.push(saved);
  else list[visitorEditingIndex.value] = saved;
  draft.value.config.visitors = list;
  visitorDialog.value = false;
}

// ---------- 导入 / 导出 ----------

async function onImportFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    const profile = await api.importToml(
      file.name.replace(/\.[^.]+$/, "") || t("profiles.import"),
      await file.text(),
    );
    await store.saveProfile(profile);
    if (store.profiles.length === 1 || !store.settings?.defaultProfileId) {
      await store.saveSettings({ ...store.settings!, defaultProfileId: profile.id });
    }
    message.value = t("profiles.imported", { name: profile.name });
  } catch (err) {
    message.value = t("profiles.importFailed", { err });
  }
  (e.target as HTMLInputElement).value = "";
}

async function exportToml(p: ServerProfile) {
  const text = await api.exportProfileToml(p.id);
  const path = await defaultPath(`${p.name}.toml`);
  await api.writeTextFile(path, text);
  message.value = t("profiles.exportedTo", { path });
}

async function exportAll() {
  const path = await defaultPath("frpcdash-profiles.json");
  await api.writeTextFile(path, await api.exportBundle());
  message.value = t("profiles.exportedAll", { n: store.profiles.length, path });
}

async function onImportBundle(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    const s = await api.importBundle(await file.text(), false);
    message.value =
      t("profiles.bundleImported", { added: s.added, updated: s.updated }) +
      (s.skipped.length
        ? t("profiles.bundleSkipped", { n: s.skipped.length, list: s.skipped.join(", ") })
        : "");
    await store.refreshProfiles();
  } catch (err) {
    message.value = t("profiles.importFailed", { err });
  }
  (e.target as HTMLInputElement).value = "";
}

async function defaultPath(name: string): Promise<string> {
  const dir = await api.getDataDir();
  const sep = dir.includes("\\") ? "\\" : "/";
  return `${dir.replace(/[\\/]+$/, "")}${sep}${name.replace(/[\\/]/g, "-")}`;
}

/// 档案卡上的启停：只有「这份正在运行」或「全部都没运行」两种情况下可操作，
/// 别的配置在跑时先提示，避免多处同时抢同一个 frpc 进程。
async function toggleProfile(p: ServerProfile) {
  if (store.runningId === p.id) {
    await store.stop();
    return;
  }
  if (store.runningId) {
    message.value = t("profiles.runningElsewhere", {
      current: store.runState.profileName || t("profiles.currentProfile"),
      name: p.name,
    });
    return;
  }
  try {
    await store.start(p.id);
  } catch (e) {
    message.value = String(e).replace(/^Error:\s*/, "");
  }
}

async function removeProfile(p: ServerProfile) {
  if (store.runningId === p.id) {
    message.value = t("profiles.runningCannotDelete", { name: p.name });
    return;
  }
  deleteTarget.value = p;
}

async function confirmDelete() {
  const p = deleteTarget.value;
  deleteTarget.value = null;
  if (!p) return;
  await store.deleteProfile(p.id);
  if (store.settings?.defaultProfileId === p.id) {
    await store.saveSettings({
      ...store.settings!,
      defaultProfileId: store.profiles[0]?.id ?? null,
    });
  }
}

async function clearAll() {
  clearAllConfirm.value = false;
  await store.stop();
  for (const p of store.profiles) await api.deleteProfile(p.id);
  await store.refreshProfiles();
  await store.saveSettings({ ...store.settings!, defaultProfileId: null });
  message.value = t("profiles.cleared");
}
</script>

<template>
  <!-- ============ 编辑器（全页） ============ -->
  <div v-if="draft" class="editor-root">
    <div
      style="display: flex; align-items: center; justify-content: space-between; padding: 16px 24px; border-bottom: 0.5px solid var(--border)"
    >
      <div style="display: flex; align-items: center; gap: 4px">
        <button class="icon-btn" :title="$t('editor.back')" @click="closeEditor">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
          </svg>
        </button>
        <h2 class="page-title" style="margin: 0">{{ isNew ? $t("editor.newTitle") : $t("editor.editTitle") }}</h2>
      </div>
      <div style="display: flex; gap: 12px">
        <button class="btn" @click="closeEditor">{{ $t("common.cancel") }}</button>
        <button class="btn filled" @click="save">{{ $t("common.save") }}</button>
      </div>
    </div>

    <div class="page page-narrow">
      <p v-if="saveError" class="section-card error-text" style="margin: 0">{{ saveError }}</p>

      <div v-if="runningThis" class="section-card">
        <h3 class="card-title">{{ $t("editor.runningTitle") }}</h3>
        <p class="muted" style="margin: 0">
          {{ $t("editor.runningBody") }}
        </p>
      </div>

      <div v-if="showErrors && errorIssues.length" class="section-card">
        <h3 class="card-title" style="color: var(--error)">{{ $t("editor.fixErrors") }}</h3>
        <p v-for="(it, i) in errorIssues" :key="i" class="error-text" style="margin: 0">
          • {{ validationText(it) }}
        </p>
      </div>

      <div v-if="warningIssues.length" class="section-card">
        <h3 class="card-title" style="color: var(--text-2)">{{ $t("editor.suggestions") }}</h3>
        <p v-for="(it, i) in warningIssues" :key="i" class="muted" style="margin: 0">
          • {{ validationText(it) }}
        </p>
      </div>

      <div class="section-card">
        <h3 class="card-title">{{ $t("editor.sectionServer") }}</h3>
        <div class="field">
          <span class="field-label">{{ $t("editor.name") }}</span>
          <input v-model="draft.name" />
          <span class="support">{{ $t("editor.nameSupport") }}</span>
        </div>
        <div class="form-row">
          <div class="field" style="flex: 2">
            <span class="field-label">{{ $t("editor.serverAddr") }}</span>
            <input v-model="draft.config.serverAddr" placeholder="frps.example.com" />
            <span class="support">{{ $t("editor.serverAddrSupport") }}</span>
          </div>
          <div class="field" style="flex: 1">
            <span class="field-label">{{ $t("editor.serverPort") }}</span>
            <input v-model.number="draft.config.serverPort" type="number" />
            <span class="support">{{ $t("editor.serverPortSupport") }}</span>
          </div>
        </div>
        <div class="form-row">
          <div class="field">
            <span class="field-label">{{ $t("editor.user") }}</span>
            <input v-model="draft.config.user" :placeholder="$t('editor.userPlaceholder')" />
            <span class="support">{{ $t("editor.userSupport") }}</span>
          </div>
          <div class="field">
            <span class="field-label">{{ $t("editor.clientID") }}</span>
            <input v-model="draft.config.clientID" />
            <span class="support">{{ $t("editor.clientIDSupport") }}</span>
          </div>
        </div>
        <div class="switch-row">
          <div class="sw-text">
            <div class="sw-label">{{ $t("editor.loginFailExit") }}</div>
            <div class="sw-support">{{ $t("editor.loginFailExitSupport") }}</div>
          </div>
          <label class="switch">
            <input v-model="draft.config.loginFailExit" type="checkbox" />
            <span class="track"></span><span class="thumb"></span>
          </label>
        </div>
        <div class="field">
          <span class="field-label">{{ $t("editor.stunServer") }}</span>
          <input v-model="draft.config.natHoleStunServer" :placeholder="$t('editor.stunPlaceholder')" />
          <span class="support">{{ $t("editor.stunSupport") }}</span>
        </div>
      </div>

      <div class="section-card">
        <h3 class="card-title">{{ $t("editor.sectionAuth") }}</h3>
        <div class="field">
          <span class="field-label">{{ $t("editor.authMethod") }}</span>
          <select v-model="draft.config.auth!.method">
            <option value="token">{{ $t("editor.authTokenOption") }}</option>
            <option value="none">{{ $t("editor.authNoneOption") }}</option>
          </select>
        </div>
        <div v-if="draft.config.auth?.method === 'token'" class="field">
          <span class="field-label">{{ $t("editor.authToken") }}</span>
          <input v-model="draft.config.auth!.token" />
          <span class="support">{{ $t("editor.authTokenSupport") }}</span>
        </div>
        <p v-else class="muted small" style="margin: 0">
          {{ $t("editor.authNoneHint") }}
        </p>
        <div class="field">
          <span class="field-label">{{ $t("editor.metadatasToken") }}</span>
          <input
            :value="draft.config.metadatas?.token ?? ''"
            @input="
              (e) => {
                const v = (e.target as HTMLInputElement).value.trim();
                draft!.config.metadatas = v ? { token: v } : null;
              }
            "
          />
          <span class="support">{{ $t("editor.metadatasTokenSupport") }}</span>
        </div>
        <p
          v-if="draft.config.auth && !['token', 'none'].includes(draft.config.auth.method)"
          class="error-text small"
          style="margin: 0"
        >
          {{ $t("editor.authUnsupported", { method: draft.config.auth.method }) }}
        </p>
      </div>

      <div class="section-card">
        <h3 class="card-title">{{ $t("editor.sectionWebServer") }}</h3>
        <div class="switch-row">
          <div class="sw-text">
            <div class="sw-label">{{ $t("editor.wsEnable") }}</div>
            <div class="sw-support">{{ $t("editor.wsEnableSupport") }}</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="!!draft.config.webServer"
              @change="
                (e) => {
                  draft!.config.webServer = (e.target as HTMLInputElement).checked
                    ? { addr: '127.0.0.1', port: 7400, user: 'admin', password: 'admin', pprofEnable: false }
                    : null;
                }
              "
            />
            <span class="track"></span><span class="thumb"></span>
          </label>
        </div>
        <template v-if="draft.config.webServer">
          <div class="form-row">
            <div class="field" style="flex: 2">
              <span class="field-label">{{ $t("editor.wsAddr") }}</span>
              <input v-model="draft.config.webServer.addr" />
            </div>
            <div class="field" style="flex: 1">
              <span class="field-label">{{ $t("editor.wsPort") }}</span>
              <input v-model.number="draft.config.webServer.port" type="number" />
              <span class="support">{{ $t("editor.wsPortSupport") }}</span>
            </div>
          </div>
          <div class="form-row">
            <div class="field">
              <span class="field-label">{{ $t("editor.wsUser") }}</span>
              <input v-model="draft.config.webServer.user" />
            </div>
            <div class="field">
              <span class="field-label">{{ $t("editor.wsPassword") }}</span>
              <input v-model="draft.config.webServer.password" />
              <span class="support">{{ $t("editor.wsPasswordSupport") }}</span>
            </div>
          </div>
        </template>
      </div>

      <div class="section-card">
        <h3 class="card-title">{{ $t("editor.sectionTransport") }}</h3>
        <div class="form-row">
          <div class="field">
            <span class="field-label">{{ $t("editor.protocol") }}</span>
            <select v-model="draft.config.transport!.protocol">
              <option v-for="p in ['tcp', 'kcp', 'quic', 'websocket', 'wss']" :key="p" :value="p">{{ p }}</option>
            </select>
          </div>
          <div class="switch-row" style="flex: 1">
            <div class="sw-text">
              <div class="sw-label">{{ $t("editor.tlsEnable") }}</div>
              <div class="sw-support">{{ $t("editor.tlsEnableSupport") }}</div>
            </div>
            <label class="switch">
              <input
                type="checkbox"
                :checked="draft.config.transport!.tls?.enable ?? true"
                @change="
                  (e) => {
                    const v = (e.target as HTMLInputElement).checked;
                    const t = draft!.config.transport!;
                    t.tls = { enable: v, ...(t.tls ?? {}) };
                  }
                "
              />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
          <div class="switch-row" style="flex: 1">
            <div class="sw-text">
              <div class="sw-label">{{ $t("editor.tcpMux") }}</div>
              <div class="sw-support">{{ $t("editor.tcpMuxSupport") }}</div>
            </div>
            <label class="switch">
              <input v-model="draft.config.transport!.tcpMux" type="checkbox" />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
        </div>
        <div class="field">
          <span class="field-label">{{ $t("editor.poolCount") }}</span>
          <input v-model.number="draft.config.transport!.poolCount" type="number" />
          <span class="support">{{ $t("editor.poolCountSupport") }}</span>
        </div>

        <button class="btn text-btn" @click="showTransportAdvanced = !showTransportAdvanced">
          {{ showTransportAdvanced ? $t("editor.advancedHide") : $t("editor.advancedShow") }}
        </button>
        <div v-if="showTransportAdvanced" style="display: flex; flex-direction: column; gap: 12px">
          <div class="form-row">
            <div class="field">
              <span class="field-label">{{ $t("editor.heartbeatInterval") }}</span>
              <input v-model.number="draft.config.transport!.heartbeatInterval" type="number" placeholder="10" />
              <span class="support">{{ $t("editor.heartbeatIntervalSupport") }}</span>
            </div>
            <div class="field">
              <span class="field-label">{{ $t("editor.heartbeatTimeout") }}</span>
              <input v-model.number="draft.config.transport!.heartbeatTimeout" type="number" placeholder="90" />
              <span class="support">{{ $t("editor.heartbeatTimeoutSupport") }}</span>
            </div>
            <div class="field">
              <span class="field-label">{{ $t("editor.muxKeepalive") }}</span>
              <input v-model.number="draft.config.transport!.tcpMuxKeepaliveInterval" type="number" placeholder="30" />
              <span class="support">{{ $t("editor.muxKeepaliveSupport") }}</span>
            </div>
          </div>
          <div class="field">
            <span class="field-label">{{ $t("editor.connectServerLocalIP") }}</span>
            <input v-model="draft.config.transport!.connectServerLocalIP" placeholder="192.168.1.10" />
            <span class="support">{{ $t("editor.connectServerLocalIPSupport") }}</span>
          </div>
          <div class="field">
            <span class="field-label">{{ $t("editor.proxyURL") }}</span>
            <input v-model="draft.config.transport!.proxyURL" placeholder="socks5://127.0.0.1:1080" />
            <span class="support">{{ $t("editor.proxyURLSupport") }}</span>
          </div>
        </div>

        <hr class="hr" />
        <button class="btn text-btn" @click="toggleTlsAdvanced">
          {{ showTlsAdvanced ? $t("editor.tlsAdvancedHide") : $t("editor.tlsAdvancedShow") }}
        </button>
        <div v-if="showTlsAdvanced" style="display: flex; flex-direction: column; gap: 12px">
          <div class="field">
            <span class="field-label">{{ $t("editor.certFile") }}</span>
            <input v-model="draft.config.transport!.tls!.certFile" placeholder="/etc/frp/client.crt" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("editor.keyFile") }}</span>
            <input v-model="draft.config.transport!.tls!.keyFile" placeholder="/etc/frp/client.key" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("editor.trustedCaFile") }}</span>
            <input v-model="draft.config.transport!.tls!.trustedCaFile" placeholder="/etc/frp/ca.crt" />
            <span class="support">{{ $t("editor.trustedCaFileSupport") }}</span>
          </div>
          <div class="switch-row">
            <div class="sw-text">
              <div class="sw-label">{{ $t("editor.customTlsFirstByte") }}</div>
              <div class="sw-support">{{ $t("editor.customTlsFirstByteSupport") }}</div>
            </div>
            <label class="switch">
              <input
                type="checkbox"
                :checked="draft.config.transport!.tls!.disableCustomTLSFirstByte === false"
                @change="
                  (e) => {
                    const on = (e.target as HTMLInputElement).checked;
                    const t = draft!.config.transport!;
                    t.tls = { ...(t.tls ?? { enable: true }), disableCustomTLSFirstByte: !on };
                  }
                "
              />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
        </div>

        <div class="switch-row">
          <div class="sw-text">
            <div class="sw-label">{{ $t("editor.customTransport") }}</div>
            <div class="sw-support">{{ $t("editor.customTransportSupport") }}</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="customTransport"
              @change="
                (e) => {
                  customTransport = (e.target as HTMLInputElement).checked;
                  if (customTransport && !draft!.config.transport) draft!.config.transport = defaultTransport();
                }
              "
            />
            <span class="track"></span><span class="thumb"></span>
          </label>
        </div>
      </div>

      <div class="section-card">
        <h3 class="card-title">{{ $t("editor.sectionLog") }}</h3>
        <div class="field">
          <span class="field-label">{{ $t("editor.logTo") }}</span>
          <input v-model="draft.config.log!.to" :placeholder="$t('editor.logToPlaceholder')" />
          <span class="support">{{ $t("editor.logToSupport") }}</span>
        </div>
        <div class="form-row">
          <div class="field" style="flex: 1">
            <span class="field-label">{{ $t("editor.logLevel") }}</span>
            <select v-model="draft!.config.log!.level">
              <option v-for="l in ['trace', 'debug', 'info', 'warn', 'error']" :key="l" :value="l">{{ l }}</option>
            </select>
          </div>
          <div class="field" style="flex: 1">
            <span class="field-label">{{ $t("editor.logMaxDays") }}</span>
            <input v-model.number="draft!.config.log!.maxDays" type="number" />
            <span class="support">{{ $t("editor.logMaxDaysSupport") }}</span>
          </div>
        </div>
        <div class="switch-row">
          <div class="sw-text">
            <div class="sw-label">{{ $t("editor.disablePrintColor") }}</div>
            <div class="sw-support">{{ $t("editor.disablePrintColorSupport") }}</div>
          </div>
          <label class="switch">
            <input v-model="draft!.config.log!.disablePrintColor" type="checkbox" />
            <span class="track"></span><span class="thumb"></span>
          </label>
        </div>
        <p class="muted small" style="margin: 0">
          {{ $t("editor.logConsoleHint") }}
        </p>
      </div>

      <div class="section-card">
        <h3 class="card-title">
          <span>{{ $t("editor.sectionProxies", { n: draft.config.proxies.length }) }}</span>
          <button class="btn filled small" @click="addProxy">{{ $t("editor.addProxy") }}</button>
        </h3>
        <p class="muted small" style="margin: 0">
          {{ $t("editor.proxiesSupport") }}
        </p>
        <p v-if="!draft.config.proxies.length" class="muted" style="margin: 0">
          {{ $t("editor.noProxies") }}
        </p>
        <div v-if="draft.config.proxies.length > 5" class="field">
          <span class="field-label">{{ $t("editor.filterProxies") }}</span>
          <input v-model="proxyQuery" />
        </div>
        <div
          v-for="(proxy, index) in draft.config.proxies"
          v-show="
            !proxyQuery ||
            proxy.name.toLowerCase().includes(proxyQuery.toLowerCase()) ||
            proxy.type.includes(proxyQuery.toLowerCase()) ||
            String(proxy.localPort ?? '').includes(proxyQuery) ||
            String(proxy.remotePort ?? '').includes(proxyQuery)
          "
          :key="index"
          class="proxy-row"
        >
          <div style="flex: 1; min-width: 0">
            <div :style="{ fontWeight: 500, color: proxy.enabled !== false ? 'var(--text)' : 'var(--text-2)' }">
              {{ proxy.name }}
            </div>
            <div class="muted small">{{ describeProxy(proxy) }}</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="proxy.enabled !== false"
              @change="
                (e) => {
                  const on = (e.target as HTMLInputElement).checked;
                  draft!.config.proxies = draft!.config.proxies.map((p, i) =>
                    i === index ? { ...p, enabled: on ? null : false } : p,
                  );
                }
              "
            />
            <span class="track"></span><span class="thumb"></span>
          </label>
          <button class="icon-btn" :title="$t('common.edit')" @click="editProxy(index)">
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z" />
            </svg>
          </button>
          <button
            class="icon-btn"
            :title="$t('common.delete')"
            @click="draft!.config.proxies = draft.config.proxies.filter((_, i) => i !== index)"
          >
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
            </svg>
          </button>
        </div>
      </div>

      <div class="section-card">
        <h3 class="card-title">
          <span>{{ $t("editor.sectionVisitors", { n: draft.config.visitors.length }) }}</span>
          <button class="btn small" @click="addVisitor">{{ $t("editor.addVisitor") }}</button>
        </h3>
        <p class="muted small" style="margin: 0">
          {{ $t("editor.visitorsSupport") }}
        </p>
        <div v-for="(visitor, index) in draft.config.visitors" :key="index" class="proxy-row">
          <div style="flex: 1; min-width: 0">
            <div :style="{ fontWeight: 500, color: visitor.enabled !== false ? 'var(--text)' : 'var(--text-2)' }">
              {{ visitor.name }}
            </div>
            <div class="muted small">
              {{ describeVisitor(visitor, draft!.config.user) }}
            </div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="visitor.enabled !== false"
              @change="
                (e) => {
                  const on = (e.target as HTMLInputElement).checked;
                  draft!.config.visitors = draft!.config.visitors.map((v, i) =>
                    i === index ? { ...v, enabled: on ? null : false } : v,
                  );
                }
              "
            />
            <span class="track"></span><span class="thumb"></span>
          </label>
          <button class="icon-btn" :title="$t('common.edit')" @click="editVisitor(index)">
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z" />
            </svg>
          </button>
          <button
            class="icon-btn"
            :title="$t('common.delete')"
            @click="draft!.config.visitors = draft.config.visitors.filter((_, i) => i !== index)"
          >
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
            </svg>
          </button>
        </div>
      </div>

      <div style="height: 24px"></div>
    </div>

    <!-- 代理编辑对话框 -->
    <div v-if="proxyDialog && proxyDraft" class="modal-mask" @click.self="proxyDialog = false">
      <div class="modal" style="width: 720px">
        <h3 class="modal-title">{{ proxyEditingIndex == null ? $t("proxyDialog.addTitle") : $t("proxyDialog.editTitle") }}</h3>
        <p v-if="proxyBatchError" class="error-text" style="margin: 0">{{ proxyBatchError }}</p>
        <div class="form-row">
          <div class="field" style="flex: 2">
            <span class="field-label">{{ $t("proxyDialog.name") }}</span>
            <input v-model="proxyDraft.name" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.type") }}</span>
            <select v-model="proxyDraft.type">
              <option v-for="t in PROXY_TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
          </div>
          <div class="switch-row" style="align-self: flex-end; padding-bottom: 6px">
            <span class="sw-label">{{ $t("proxyDialog.enabled") }}</span>
            <label class="switch">
              <input v-model="proxyDraft.enabled" type="checkbox" />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
        </div>
        <div class="form-row">
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.localIP") }}</span>
            <input v-model="proxyDraft.localIP" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.localPort") }}</span>
            <input v-model.number="proxyDraft.localPort" type="number" />
          </div>
        </div>
        <div v-if="typeCaps(proxyDraft.type).remotePort" class="field">
          <span class="field-label">
            {{ $t("proxyDialog.remotePort") }}
          </span>
          <input v-model="proxyBatchPorts" :placeholder="proxyEditingIndex == null ? $t('proxyDialog.remotePortPlaceholder') : ''" />
        </div>
        <div v-if="typeCaps(proxyDraft.type).domain" class="form-row">
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.subdomain") }}</span>
            <input v-model="proxyDraft.subdomain" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.customDomains") }}</span>
            <input
              :value="(proxyDraft.customDomains ?? []).join(',')"
              @change="
                (e) => {
                  proxyDraft!.customDomains = (e.target as HTMLInputElement).value
                    .split(/[,，]/)
                    .map((s) => s.trim())
                    .filter(Boolean);
                }
              "
            />
          </div>
        </div>
        <template v-if="typeCaps(proxyDraft.type).httpAuth">
          <div class="form-row">
            <div class="field">
              <span class="field-label">{{ $t("proxyDialog.httpUser") }}</span>
              <input v-model="proxyDraft.httpUser" />
            </div>
            <div class="field">
              <span class="field-label">{{ $t("proxyDialog.httpPassword") }}</span>
              <input v-model="proxyDraft.httpPassword" />
            </div>
            <div class="field">
              <span class="field-label">{{ $t("proxyDialog.routeByHTTPUser") }}</span>
              <input v-model="proxyDraft.routeByHTTPUser" />
            </div>
          </div>
        </template>
        <template v-if="typeCaps(proxyDraft.type).httpAdv">
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.hostHeaderRewrite") }}</span>
            <input v-model="proxyDraft.hostHeaderRewrite" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.locations") }}</span>
            <input
              :value="(proxyDraft.locations ?? []).join(',')"
              @change="
                (e) => {
                  proxyDraft!.locations = (e.target as HTMLInputElement).value
                    .split(/[,，]/)
                    .map((s) => s.trim())
                    .filter(Boolean);
                }
              "
            />
          </div>
        </template>
        <template v-if="typeCaps(proxyDraft.type).mux">
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.multiplexer") }}</span>
            <input v-model="proxyDraft.multiplexer" placeholder="httpconnect" />
          </div>
        </template>
        <template v-if="typeCaps(proxyDraft.type).secretKey">
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.secretKey") }}</span>
            <input v-model="proxyDraft.secretKey" type="password" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.allowUsers") }}</span>
            <input
              :value="(proxyDraft.allowUsers ?? []).join(',')"
              @change="
                (e) => {
                  proxyDraft!.allowUsers = (e.target as HTMLInputElement).value
                    .split(/[,，]/)
                    .map((s) => s.trim())
                    .filter(Boolean);
                }
              "
            />
          </div>
        </template>
        <div class="form-row">
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.bandwidthLimit") }}</span>
            <input v-model="proxyDraft.transport!.bandwidthLimit" placeholder="1MB" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.bandwidthLimitMode") }}</span>
            <select v-model="proxyDraft.transport!.bandwidthLimitMode">
              <option value=""></option>
              <option value="client">client</option>
              <option value="server">server</option>
            </select>
          </div>
          <div class="switch-row" style="align-self: flex-end; padding-bottom: 6px">
            <span class="sw-label">{{ $t("proxyDialog.encryption") }}</span>
            <label class="switch">
              <input v-model="proxyDraft.transport!.useEncryption" type="checkbox" />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
          <div class="switch-row" style="align-self: flex-end; padding-bottom: 6px">
            <span class="sw-label">{{ $t("proxyDialog.compression") }}</span>
            <label class="switch">
              <input v-model="proxyDraft.transport!.useCompression" type="checkbox" />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
        </div>
        <template v-if="typeCaps(proxyDraft.type).httpsPlugin">
          <div class="field">
            <span class="field-label">{{ $t("proxyDialog.pluginType") }}</span>
            <select
              :value="proxyDraft.plugin?.type ?? ''"
              @change="
                (e) => {
                  const p = (e.target as HTMLInputElement).value;
                  proxyDraft!.plugin = p ? ({ type: p } as ProxyPluginConfig) : null;
                }
              "
            >
              <option value="">{{ $t("proxyDialog.pluginNone") }}</option>
              <option value="https2http">https2http</option>
              <option value="tls2raw">tls2raw</option>
            </select>
          </div>
          <template v-if="proxyDraft.plugin?.type">
            <div class="field">
              <span class="field-label">{{ $t("proxyDialog.pluginLocalAddr") }}</span>
              <input v-model="proxyDraft.plugin!.localAddr" placeholder="127.0.0.1:80" />
            </div>
            <div class="form-row">
              <div class="field">
                <span class="field-label">{{ $t("proxyDialog.pluginCrt") }}</span>
                <input v-model="proxyDraft.plugin!.crtPath" />
              </div>
              <div class="field">
                <span class="field-label">{{ $t("proxyDialog.pluginKey") }}</span>
                <input v-model="proxyDraft.plugin!.keyPath" />
              </div>
            </div>
            <div v-if="proxyDraft.plugin!.type === 'https2http'" class="field">
              <span class="field-label">{{ $t("proxyDialog.hostHeaderRewrite") }}</span>
              <input v-model="proxyDraft.plugin!.hostHeaderRewrite" />
            </div>
          </template>
        </template>
        <div class="modal-actions">
          <button class="btn text-btn" @click="proxyDialog = false">{{ $t("common.cancel") }}</button>
          <button class="btn filled" @click="saveProxy">{{ $t("common.save") }}</button>
        </div>
      </div>
    </div>

    <!-- 访问者编辑对话框 -->
    <div v-if="visitorDialog && visitorDraft" class="modal-mask" @click.self="visitorDialog = false">
      <div class="modal" style="width: 640px">
        <h3 class="modal-title">{{ visitorEditingIndex == null ? $t("visitorDialog.addTitle") : $t("visitorDialog.editTitle") }}</h3>
        <div class="form-row">
          <div class="field" style="flex: 2">
            <span class="field-label">{{ $t("visitorDialog.name") }}</span>
            <input v-model="visitorDraft.name" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("visitorDialog.type") }}</span>
            <select v-model="visitorDraft.type">
              <option v-for="vt in VISITOR_TYPES" :key="vt" :value="vt">{{ vt }}</option>
            </select>
          </div>
          <div class="switch-row" style="align-self: flex-end; padding-bottom: 6px">
            <span class="sw-label">{{ $t("visitorDialog.enabled") }}</span>
            <label class="switch">
              <input v-model="visitorDraft.enabled" type="checkbox" />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
        </div>
        <div class="form-row">
          <div class="field" style="flex: 2">
            <span class="field-label">{{ $t("visitorDialog.serverName") }}</span>
            <input v-model="visitorDraft.serverName" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("visitorDialog.secretKey") }}</span>
            <input v-model="visitorDraft.secretKey" type="password" />
          </div>
        </div>
        <div class="form-row">
          <div class="field">
            <span class="field-label">{{ $t("visitorDialog.bindAddr") }}</span>
            <input v-model="visitorDraft.bindAddr" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("visitorDialog.bindPort") }}</span>
            <input v-model.number="visitorDraft.bindPort" type="number" />
          </div>
          <div class="field">
            <span class="field-label">{{ $t("visitorDialog.serverUser") }}</span>
            <input v-model="visitorDraft.serverUser" />
          </div>
        </div>
        <template v-if="visitorDraft.type === 'xtcp'">
          <div class="form-row">
            <div class="field">
              <span class="field-label">{{ $t("visitorDialog.fallbackTo") }}</span>
              <input v-model="visitorDraft.fallbackTo" />
            </div>
            <div class="field">
              <span class="field-label">{{ $t("visitorDialog.fallbackTimeout") }}</span>
              <input v-model.number="visitorDraft.fallbackTimeoutMs" type="number" />
            </div>
            <div class="switch-row" style="align-self: flex-end; padding-bottom: 6px">
              <span class="sw-label">{{ $t("visitorDialog.keepTunnelOpen") }}</span>
              <label class="switch">
                <input v-model="visitorDraft.keepTunnelOpen" type="checkbox" />
                <span class="track"></span><span class="thumb"></span>
              </label>
            </div>
          </div>
        </template>
        <p v-if="visitorError" class="error-text" style="margin: 0">{{ visitorError }}</p>
        <div class="modal-actions">
          <button class="btn text-btn" @click="visitorDialog = false">{{ $t("common.cancel") }}</button>
          <button class="btn filled" @click="saveVisitor">{{ $t("common.save") }}</button>
        </div>
      </div>
    </div>

    <!-- 保存运行中的配置 -->
    <div v-if="confirmSaveRunning" class="modal-mask" @click.self="confirmSaveRunning = false">
      <div class="modal" style="width: 480px">
        <h3 class="modal-title">{{ $t("editor.saveRunningTitle") }}</h3>
        <p style="margin: 0">{{ $t("editor.saveRunningBody") }}</p>
        <div class="modal-actions">
          <button class="btn text-btn" style="color: var(--brand)" @click="confirmSaveRunning = false; doSave(true)">
            {{ $t("common.saveReload") }}
          </button>
          <button class="btn text-btn" @click="confirmSaveRunning = false; doSave(false)">{{ $t("common.saveOnly") }}</button>
          <button class="btn text-btn" @click="confirmSaveRunning = false">{{ $t("common.cancel") }}</button>
        </div>
      </div>
    </div>
  </div>

  <!-- ============ 列表态 ============ -->
  <div v-else class="page">
    <div style="display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <div>
        <h2 class="page-title">{{ $t("profiles.title") }}</h2>
        <p class="page-sub">{{ $t("profiles.subtitle") }}</p>
      </div>
      <div style="display: flex; gap: 12px">
        <label class="btn">
          {{ $t("profiles.import") }}
          <input type="file" accept=".toml" hidden @change="onImportFile" />
        </label>
        <button class="btn filled" @click="openEditor(null)">{{ $t("profiles.new") }}</button>
      </div>
    </div>

    <div style="display: flex; gap: 12px; align-items: center; flex-wrap: wrap">
      <div class="field" style="flex: 1">
        <input v-model="query" :placeholder="$t('profiles.searchPlaceholder')" />
      </div>
      <button class="btn" @click="exportAll">{{ $t("profiles.exportAll") }}</button>
      <label class="btn">
        {{ $t("profiles.importAll") }}
        <input ref="bundleFile" type="file" accept=".json" hidden @change="onImportBundle" />
      </label>
      <button class="btn text-btn danger-text" @click="clearAllConfirm = true">{{ $t("profiles.clearAll") }}</button>
    </div>

    <div v-if="message" class="section-card" style="flex-direction: row; justify-content: space-between; align-items: center">
      <span>{{ message }}</span>
      <button class="btn text-btn" @click="message = ''">{{ $t("common.close") }}</button>
    </div>

    <div v-if="!store.profiles.length" class="section-card">
      <div style="font-size: 15px; font-weight: 500">{{ $t("profiles.empty") }}</div>
      <p class="muted" style="margin: 0">{{ $t("profiles.emptyHint") }}</p>
    </div>
    <div v-else-if="!visible.length" class="section-card">
      <p class="muted" style="margin: 0">{{ $t("profiles.noMatch", { q: query }) }}</p>
    </div>

    <div v-for="profile in visible" :key="profile.id" class="section-card">
      <div style="display: flex; align-items: center; gap: 8px">
        <div style="flex: 1; min-width: 0">
          <div style="display: flex; align-items: center; gap: 8px">
            <span style="font-size: 15px; font-weight: 500">{{ profile.name }}</span>
            <span v-if="store.runningId === profile.id" class="status-chip chip-running">{{ $t("profiles.chipRunning") }}</span>
            <span v-if="store.pendingReload === profile.id" class="status-chip chip-error">{{ $t("profiles.chipPending") }}</span>
            <span v-if="store.settings?.defaultProfileId === profile.id" class="status-chip chip-brand">{{ $t("profiles.chipDefault") }}</span>
          </div>
          <div class="muted">
            {{ `${profile.config.serverAddr}:${profile.config.serverPort}` }} ·
            {{ $t("profiles.metaProxies", { n: profile.config.proxies.length }) }}<template v-if="profile.config.visitors.length">
              · {{ $t("profiles.metaVisitors", { n: profile.config.visitors.length }) }}</template
            >
          </div>
          <div
            v-if="store.runningId === profile.id"
            class="small"
            :class="store.pendingReload === profile.id ? 'error-text' : 'muted'"
          >
            {{
              store.pendingReload === profile.id
                ? $t("profiles.pendingHint")
                : $t("profiles.runningHint")
            }}
          </div>
        </div>
        <button
          class="icon-btn"
          :title="store.runningId === profile.id ? $t('home.stop') : $t('home.start')"
          @click="toggleProfile(profile)"
        >
          <svg v-if="store.runningId === profile.id" viewBox="0 0 24 24" fill="currentColor">
            <path d="M6 6h12v12H6z" />
          </svg>
          <svg v-else viewBox="0 0 24 24" fill="currentColor">
            <path d="M8 5v14l11-7z" />
          </svg>
        </button>
        <button class="icon-btn" :title="$t('common.edit')" @click="openEditor(profile)">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z" />
          </svg>
        </button>
        <button class="icon-btn" :title="$t('common.duplicate')" @click="store.duplicateProfile(profile.id)">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />
          </svg>
        </button>
        <button class="icon-btn" :title="$t('common.export')" @click="exportToml(profile)">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" />
          </svg>
        </button>
        <button
          class="icon-btn"
          :title="$t('profiles.setDefault')"
          :disabled="store.settings?.defaultProfileId === profile.id"
          @click="store.saveSettings({ ...store.settings!, defaultProfileId: profile.id })"
        >
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" />
          </svg>
        </button>
        <button
          class="icon-btn"
          :title="$t('common.delete')"
          :disabled="store.runningId === profile.id"
          @click="removeProfile(profile)"
        >
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M6 19c0 1.1.9 2 2 2h8c1.1 0 2-.9 2-2V7H6v12zM19 4h-3.5l-1-1h-5l-1 1H5v2h14V4z" />
          </svg>
        </button>
      </div>
    </div>

    <div style="height: 8px"></div>

    <div v-if="clearAllConfirm" class="modal-mask" @click.self="clearAllConfirm = false">
      <div class="modal" style="width: 480px">
        <h3 class="modal-title">{{ $t("profiles.clearAllTitle") }}</h3>
        <p style="margin: 0">
          {{ $t("profiles.clearAllBody", { n: store.profiles.length }) }}
        </p>
        <div class="modal-actions">
          <button class="btn text-btn" style="color: var(--error)" @click="clearAll">{{ $t("common.clear") }}</button>
          <button class="btn text-btn" @click="clearAllConfirm = false">{{ $t("common.cancel") }}</button>
        </div>
      </div>
    </div>

    <div v-if="deleteTarget" class="modal-mask" @click.self="deleteTarget = null">
      <div class="modal" style="width: 480px">
        <h3 class="modal-title">{{ $t("profiles.deleteTitle") }}</h3>
        <p style="margin: 0">
          {{
            $t("profiles.deleteBody", {
              name: deleteTarget.name,
              server: `${deleteTarget.config.serverAddr}:${deleteTarget.config.serverPort}`,
              n: deleteTarget.config.proxies.length,
            })
          }}
        </p>
        <div class="modal-actions">
          <button class="btn text-btn" style="color: var(--error)" @click="confirmDelete">{{ $t("common.delete") }}</button>
          <button class="btn text-btn" @click="deleteTarget = null">{{ $t("common.cancel") }}</button>
        </div>
      </div>
    </div>
  </div>
</template>
