<script setup lang="ts">
import { computed, ref, watch } from "vue";
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
    name: "新配置",
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

watch(
  () => store.editingProfileId,
  (id) => {
    if (id) openEditor(store.profiles.find((p) => p.id === id) ?? null);
  },
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
  draft.value = copy;
  showErrors.value = false;
  saveError.value = "";
}

function closeEditor() {
  draft.value = null;
  store.editingProfileId = null;
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
  const name = draft.value.name.trim() || "未命名配置";
  if (store.profiles.some((p) => p.name === name && p.id !== draft.value!.id)) {
    saveError.value = `已存在同名配置「${name}」，请换一个名称`;
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
  draft.value.name = draft.value.name.trim() || "未命名配置";
  draft.value.updatedAt = Date.now();
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
    applyProxySave(created);
    return;
  }
  if (!d.name.trim()) {
    proxyBatchError.value = "名称不能为空";
    return;
  }
  if (taken.includes(d.name.trim())) {
    proxyBatchError.value = `代理名「${d.name.trim()}」与其他规则重复`;
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
  visitorDialog.value = true;
}

function editVisitor(index: number) {
  visitorDraft.value = JSON.parse(JSON.stringify(draft.value!.config.visitors[index]));
  visitorEditingIndex.value = index;
  visitorDialog.value = true;
}

function saveVisitor() {
  const v = visitorDraft.value;
  if (!v || !draft.value) return;
  if (!v.name.trim()) return;
  const list = [...draft.value.config.visitors];
  if (visitorEditingIndex.value == null) list.push(v);
  else list[visitorEditingIndex.value] = v;
  draft.value.config.visitors = list;
  visitorDialog.value = false;
}

// ---------- 导入 / 导出 ----------

async function onImportFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    const profile = await api.importToml(
      file.name.replace(/\.[^.]+$/, "") || "导入配置",
      await file.text(),
    );
    await store.saveProfile(profile);
    if (store.profiles.length === 1 || !store.settings?.defaultProfileId) {
      await store.saveSettings({ ...store.settings!, defaultProfileId: profile.id });
    }
    message.value = `已导入「${profile.name}」`;
  } catch (err) {
    message.value = `导入失败：${err}`;
  }
  (e.target as HTMLInputElement).value = "";
}

async function exportToml(p: ServerProfile) {
  const text = await api.exportProfileToml(p.id);
  const path = await defaultPath(`${p.name}.toml`);
  await api.writeTextFile(path, text);
  message.value = `已导出到 ${path}`;
}

async function exportAll() {
  const path = await defaultPath("frpcdash-profiles.json");
  await api.writeTextFile(path, await api.exportBundle());
  message.value = `已导出 ${store.profiles.length} 份配置到 ${path}`;
}

async function onImportBundle(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  try {
    const s = await api.importBundle(await file.text(), false);
    message.value =
      `已导入：新增 ${s.added} 份、更新 ${s.updated} 份` +
      (s.skipped.length ? `，跳过 ${s.skipped.length} 条（${s.skipped.join("、")}）` : "");
    await store.refreshProfiles();
  } catch (err) {
    message.value = `导入失败：${err}`;
  }
  (e.target as HTMLInputElement).value = "";
}

async function defaultPath(name: string): Promise<string> {
  const dir = await api.getDataDir();
  const sep = dir.includes("\\") ? "\\" : "/";
  return `${dir.replace(/[\\/]+$/, "")}${sep}${name.replace(/[\\/]/g, "-")}`;
}

async function removeProfile(p: ServerProfile) {
  if (store.runningId === p.id) {
    message.value = `「${p.name}」正在运行，请先停止再删除`;
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
  message.value = "已清空全部配置";
}
</script>

<template>
  <!-- ============ 编辑器（全页） ============ -->
  <div v-if="draft" class="editor-root">
    <div
      style="display: flex; align-items: center; justify-content: space-between; padding: 16px 24px; border-bottom: 0.5px solid var(--border)"
    >
      <div style="display: flex; align-items: center; gap: 4px">
        <button class="icon-btn" title="返回" @click="closeEditor">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M20 11H7.83l5.59-5.59L12 4l-8 8 8 8 1.41-1.41L7.83 13H20v-2z" />
          </svg>
        </button>
        <h2 class="page-title" style="margin: 0">{{ isNew ? "新建配置" : "编辑配置" }}</h2>
      </div>
      <div style="display: flex; gap: 12px">
        <button class="btn" @click="closeEditor">取消</button>
        <button class="btn filled" @click="save">保存</button>
      </div>
    </div>

    <div class="page" style="max-width: 960px">
      <p v-if="saveError" class="section-card error-text" style="margin: 0">{{ saveError }}</p>

      <div v-if="runningThis" class="section-card">
        <h3 class="card-title">该配置正在运行</h3>
        <p class="muted" style="margin: 0">
          可以直接改并保存，但 frpc 仍在使用旧配置，需要「重载配置」（或停止后重新启动）才会生效。
        </p>
      </div>

      <div v-if="showErrors && errorIssues.length" class="section-card">
        <h3 class="card-title" style="color: var(--error)">请先修正以下问题</h3>
        <p v-for="(it, i) in errorIssues" :key="i" class="error-text" style="margin: 0">
          • {{ validationText(it) }}
        </p>
      </div>

      <div v-if="warningIssues.length" class="section-card">
        <h3 class="card-title" style="color: var(--text-2)">建议改进</h3>
        <p v-for="(it, i) in warningIssues" :key="i" class="muted" style="margin: 0">
          • {{ validationText(it) }}
        </p>
      </div>

      <div class="section-card">
        <h3 class="card-title">服务器</h3>
        <div class="field">
          <span class="field-label">配置名称</span>
          <input v-model="draft.name" />
          <span class="support">只在本机区分多份配置，不会写进 frpc.toml</span>
        </div>
        <div class="form-row">
          <div class="field" style="flex: 2">
            <span class="field-label">服务器地址</span>
            <input v-model="draft.config.serverAddr" placeholder="frps.example.com" />
            <span class="support">frps 的地址或域名，不要带 http:// 前缀</span>
          </div>
          <div class="field" style="flex: 1">
            <span class="field-label">服务器端口</span>
            <input v-model.number="draft.config.serverPort" type="number" />
            <span class="support">tcp/websocket 填 bind_port；kcp/quic 填 kcp_bind_port / quic_bind_port</span>
          </div>
        </div>
        <div class="form-row">
          <div class="field">
            <span class="field-label">代理名前缀（user）</span>
            <input v-model="draft.config.user" placeholder="留空 = 不加前缀" />
            <span class="support">代理在 frps 上的最终名 =「前缀.代理名」</span>
          </div>
          <div class="field">
            <span class="field-label">客户端标识（clientID）</span>
            <input v-model="draft.config.clientID" />
            <span class="support">frps 面板用来区分设备；留空由 frpc 生成</span>
          </div>
        </div>
        <div class="switch-row">
          <div class="sw-text">
            <div class="sw-label">首次登录失败就退出</div>
            <div class="sw-support">关闭（默认）= 一直重连，适合移动网络；开启 = 首次连不上直接退出</div>
          </div>
          <label class="switch">
            <input v-model="draft.config.loginFailExit" type="checkbox" />
            <span class="track"></span><span class="thumb"></span>
          </label>
        </div>
        <div class="field">
          <span class="field-label">STUN 服务器</span>
          <input v-model="draft.config.natHoleStunServer" placeholder="留空 = stun.easyvoip.com:3478" />
          <span class="support">只有 xtcp 打洞会用到；默认地址在国内常超时，建议换成可连通的</span>
        </div>
      </div>

      <div class="section-card">
        <h3 class="card-title">认证</h3>
        <div class="field">
          <span class="field-label">认证方式</span>
          <select v-model="draft.config.auth!.method">
            <option value="token">Token（与 frps 的 auth.token 一致）</option>
            <option value="none">无认证（frps 没开 token）</option>
          </select>
        </div>
        <div v-if="draft.config.auth?.method === 'token'" class="field">
          <span class="field-label">认证 Token</span>
          <input v-model="draft.config.auth!.token" />
          <span class="support">必须与 frps 的 auth.token 完全一致（注意首尾空格）</span>
        </div>
        <p v-else class="muted small" style="margin: 0">
          frps 没开 auth.token 时选「无认证」；填了 token 的服务器选了它会被拒绝登录。
        </p>
        <div class="field">
          <span class="field-label">多用户令牌（metadatas.token，可选）</span>
          <input
            :value="draft.config.metadatas?.token ?? ''"
            @input="
              (e) => {
                const v = (e.target as HTMLInputElement).value.trim();
                draft!.config.metadatas = v ? { token: v } : null;
              }
            "
          />
          <span class="support">只有 frps 开了 multiuser 插件才需要填；普通服务器留空</span>
        </div>
        <p
          v-if="draft.config.auth && !['token', 'none'].includes(draft.config.auth.method)"
          class="error-text small"
          style="margin: 0"
        >
          当前配置的认证方式是「{{ draft.config.auth.method }}」，App 不支持编辑；请导出 frpc.toml 后手动改回 token 或 none，否则 frpc 会因缺少 oidc 参数启动失败。
        </p>
      </div>

      <div class="section-card">
        <h3 class="card-title">状态监控</h3>
        <div class="switch-row">
          <div class="sw-text">
            <div class="sw-label">开启状态接口（webServer）</div>
            <div class="sw-support">只监听 127.0.0.1；App 靠它读取真实远端端口与在线状态</div>
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
              <span class="field-label">监听地址</span>
              <input v-model="draft.config.webServer.addr" />
            </div>
            <div class="field" style="flex: 1">
              <span class="field-label">端口</span>
              <input v-model.number="draft.config.webServer.port" type="number" />
              <span class="support">同一台机器跑多份配置时要各用不同端口</span>
            </div>
          </div>
          <div class="form-row">
            <div class="field">
              <span class="field-label">面板用户名</span>
              <input v-model="draft.config.webServer.user" />
            </div>
            <div class="field">
              <span class="field-label">面板密码</span>
              <input v-model="draft.config.webServer.password" />
              <span class="support">浏览器打开状态页时要登录</span>
            </div>
          </div>
        </template>
      </div>

      <div class="section-card">
        <h3 class="card-title">传输</h3>
        <div class="form-row">
          <div class="field">
            <span class="field-label">连接协议</span>
            <select v-model="draft.config.transport!.protocol">
              <option v-for="p in ['tcp', 'kcp', 'quic', 'websocket', 'wss']" :key="p" :value="p">{{ p }}</option>
            </select>
          </div>
          <div class="switch-row" style="flex: 1">
            <div class="sw-text">
              <div class="sw-label">启用 TLS</div>
              <div class="sw-support">frp ≥ v0.50 默认开启；frps 关了 TLS 时才需要在这里关掉</div>
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
              <div class="sw-label">TCP 多路复用</div>
              <div class="sw-support">必须与 frps 的 tcp_mux 保持一致</div>
            </div>
            <label class="switch">
              <input v-model="draft.config.transport!.tcpMux" type="checkbox" />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
        </div>
        <div class="field">
          <span class="field-label">预连接数</span>
          <input v-model.number="draft.config.transport!.poolCount" type="number" />
          <span class="support">预先与 frps 建好的连接数，用来降低首次访问延迟；frp 默认 1</span>
        </div>

        <button class="btn text-btn" @click="showTransportAdvanced = !showTransportAdvanced">
          {{ showTransportAdvanced ? "收起高级连接参数" : "高级连接参数（心跳 / 保活 / 绑定地址）" }}
        </button>
        <div v-if="showTransportAdvanced" style="display: flex; flex-direction: column; gap: 12px">
          <div class="form-row">
            <div class="field">
              <span class="field-label">心跳间隔（秒）</span>
              <input v-model.number="draft.config.transport!.heartbeatInterval" type="number" placeholder="10" />
              <span class="support">留空用 frpc 默认 10</span>
            </div>
            <div class="field">
              <span class="field-label">心跳超时（秒）</span>
              <input v-model.number="draft.config.transport!.heartbeatTimeout" type="number" placeholder="90" />
              <span class="support">超过这么久没心跳就判定断线</span>
            </div>
            <div class="field">
              <span class="field-label">多路复用保活（秒）</span>
              <input v-model.number="draft.config.transport!.tcpMuxKeepaliveInterval" type="number" placeholder="30" />
              <span class="support">仅 tcpMux 开启时有效</span>
            </div>
          </div>
          <div class="field">
            <span class="field-label">连接 frps 的本地出口 IP（可选）</span>
            <input v-model="draft.config.transport!.connectServerLocalIP" placeholder="192.168.1.10" />
            <span class="support">多网卡 / 多出口时指定用哪个源地址连 frps；留空由系统选</span>
          </div>
          <div class="field">
            <span class="field-label">连接代理（proxyURL，可选）</span>
            <input v-model="draft.config.transport!.proxyURL" placeholder="socks5://127.0.0.1:1080" />
            <span class="support">仅 protocol = tcp 有效。App 默认会清掉系统 HTTP_PROXY，要走上游代理只能填这里</span>
          </div>
        </div>

        <hr class="hr" />
        <button class="btn text-btn" @click="showTlsAdvanced = !showTlsAdvanced">
          {{ showTlsAdvanced ? "收起 TLS 证书" : "TLS 高级（双向认证 / 私有 CA）" }}
        </button>
        <div v-if="showTlsAdvanced" style="display: flex; flex-direction: column; gap: 12px">
          <div class="field">
            <span class="field-label">客户端证书 certFile</span>
            <input v-model="draft.config.transport!.tls!.certFile" placeholder="/etc/frp/client.crt" />
          </div>
          <div class="field">
            <span class="field-label">客户端私钥 keyFile</span>
            <input v-model="draft.config.transport!.tls!.keyFile" placeholder="/etc/frp/client.key" />
          </div>
          <div class="field">
            <span class="field-label">CA 证书 trustedCaFile</span>
            <input v-model="draft.config.transport!.tls!.trustedCaFile" placeholder="/etc/frp/ca.crt" />
            <span class="support">frps 用自签证书时填它的 CA，否则会报证书校验失败</span>
          </div>
          <div class="switch-row">
            <div class="sw-text">
              <div class="sw-label">发送自定义 TLS 首字节</div>
              <div class="sw-support">对应 frp 的 disableCustomTLSFirstByte。默认不发送；只有老版本 frps 要求时才打开</div>
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
            <div class="sw-label">自定义传输配置</div>
            <div class="sw-support">关闭时不写 transport 段，全部用 frpc 默认值</div>
          </div>
          <label class="switch">
            <input
              type="checkbox"
              :checked="!!draft.config.transport"
              @change="
                (e) => {
                  draft!.config.transport = (e.target as HTMLInputElement).checked
                    ? {
                        protocol: 'tcp',
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
                      }
                    : null;
                }
              "
            />
            <span class="track"></span><span class="thumb"></span>
          </label>
        </div>
      </div>

      <div class="section-card">
        <h3 class="card-title">日志（frpc 自身）</h3>
        <div class="form-row">
          <div class="field" style="flex: 1">
            <span class="field-label">日志级别</span>
            <select v-model="draft!.config.log!.level">
              <option v-for="l in ['trace', 'debug', 'info', 'warn', 'error']" :key="l" :value="l">{{ l }}</option>
            </select>
          </div>
          <div class="field" style="flex: 1">
            <span class="field-label">日志保留天数</span>
            <input v-model.number="draft!.config.log!.maxDays" type="number" />
            <span class="support">只对写入文件的日志有效</span>
          </div>
        </div>
        <div class="switch-row">
          <div class="sw-text">
            <div class="sw-label">关闭控制台颜色</div>
            <div class="sw-support">App 自己给日志着色，关掉可少一堆 ANSI 转义字符</div>
          </div>
          <label class="switch">
            <input v-model="draft!.config.log!.disablePrintColor" type="checkbox" />
            <span class="track"></span><span class="thumb"></span>
          </label>
        </div>
        <p class="muted small" style="margin: 0">
          日志固定输出到 console，由 App 采集后在「日志」页实时展示（排障时切到 debug）。
        </p>
      </div>

      <div class="section-card">
        <h3 class="card-title">
          <span>代理规则（{{ draft.config.proxies.length }}）</span>
          <button class="btn filled small" @click="addProxy">添加代理</button>
        </h3>
        <p class="muted small" style="margin: 0">
          把本机服务暴露到 frps：tcp/udp 走端口，http/https/tcpmux 走域名
        </p>
        <p v-if="!draft.config.proxies.length" class="muted" style="margin: 0">
          还没有代理规则：点右上角「添加代理」，或到「配置」页导入现成的 frpc.toml
        </p>
        <div v-if="draft.config.proxies.length > 5" class="field">
          <span class="field-label">筛选代理（名称 / 类型 / 端口）</span>
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
          <button class="icon-btn" title="编辑" @click="editProxy(index)">
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z" />
            </svg>
          </button>
          <button
            class="icon-btn"
            title="删除"
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
          <span>访问者（{{ draft.config.visitors.length }}）</span>
          <button class="btn small" @click="addVisitor">添加访问者</button>
        </h3>
        <p class="muted small" style="margin: 0">
          反向用法：本机主动连接别人的 stcp/sudp/xtcp 私密代理，并在本机开一个端口给本地程序用
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
          <button class="icon-btn" title="编辑" @click="editVisitor(index)">
            <svg viewBox="0 0 24 24" fill="currentColor">
              <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z" />
            </svg>
          </button>
          <button
            class="icon-btn"
            title="删除"
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
        <h3 class="modal-title">{{ proxyEditingIndex == null ? "添加代理" : "编辑代理" }}</h3>
        <p v-if="proxyBatchError" class="error-text" style="margin: 0">{{ proxyBatchError }}</p>
        <div class="form-row">
          <div class="field" style="flex: 2">
            <span class="field-label">代理名称</span>
            <input v-model="proxyDraft.name" />
          </div>
          <div class="field">
            <span class="field-label">类型</span>
            <select v-model="proxyDraft.type">
              <option v-for="t in PROXY_TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
          </div>
          <div class="switch-row" style="align-self: flex-end; padding-bottom: 6px">
            <span class="sw-label">启用</span>
            <label class="switch">
              <input v-model="proxyDraft.enabled" type="checkbox" />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
        </div>
        <div class="form-row">
          <div class="field">
            <span class="field-label">本地地址</span>
            <input v-model="proxyDraft.localIP" />
          </div>
          <div class="field">
            <span class="field-label">本地端口</span>
            <input v-model.number="proxyDraft.localPort" type="number" />
          </div>
        </div>
        <div v-if="typeCaps(proxyDraft.type).remotePort" class="field">
          <span class="field-label">
            远程端口（留空随机；支持批量：6001-6003、8080,8081 会按「名称-端口」创建多条）
          </span>
          <input v-model="proxyBatchPorts" :placeholder="proxyEditingIndex == null ? '例如 6001 或 6001-6003' : ''" />
        </div>
        <div v-if="typeCaps(proxyDraft.type).domain" class="form-row">
          <div class="field">
            <span class="field-label">子域名</span>
            <input v-model="proxyDraft.subdomain" />
          </div>
          <div class="field">
            <span class="field-label">自定义域名（逗号分隔）</span>
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
              <span class="field-label">HTTP 账号</span>
              <input v-model="proxyDraft.httpUser" />
            </div>
            <div class="field">
              <span class="field-label">HTTP 密码</span>
              <input v-model="proxyDraft.httpPassword" />
            </div>
            <div class="field">
              <span class="field-label">按 HTTP 用户路由</span>
              <input v-model="proxyDraft.routeByHTTPUser" />
            </div>
          </div>
        </template>
        <template v-if="typeCaps(proxyDraft.type).httpAdv">
          <div class="field">
            <span class="field-label">Host 重写</span>
            <input v-model="proxyDraft.hostHeaderRewrite" />
          </div>
          <div class="field">
            <span class="field-label">路由路径（逗号分隔）</span>
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
            <span class="field-label">多路复用器</span>
            <input v-model="proxyDraft.multiplexer" placeholder="httpconnect" />
          </div>
        </template>
        <template v-if="typeCaps(proxyDraft.type).secretKey">
          <div class="field">
            <span class="field-label">私密密钥</span>
            <input v-model="proxyDraft.secretKey" type="password" />
          </div>
          <div class="field">
            <span class="field-label">允许的访问者（逗号分隔，* 全部）</span>
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
            <span class="field-label">带宽限制</span>
            <input v-model="proxyDraft.transport!.bandwidthLimit" placeholder="1MB" />
          </div>
          <div class="field">
            <span class="field-label">限速方</span>
            <select v-model="proxyDraft.transport!.bandwidthLimitMode">
              <option value=""></option>
              <option value="client">client</option>
              <option value="server">server</option>
            </select>
          </div>
          <div class="switch-row" style="align-self: flex-end; padding-bottom: 6px">
            <span class="sw-label">加密</span>
            <label class="switch">
              <input v-model="proxyDraft.transport!.useEncryption" type="checkbox" />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
          <div class="switch-row" style="align-self: flex-end; padding-bottom: 6px">
            <span class="sw-label">压缩</span>
            <label class="switch">
              <input v-model="proxyDraft.transport!.useCompression" type="checkbox" />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
        </div>
        <template v-if="typeCaps(proxyDraft.type).httpsPlugin">
          <div class="field">
            <span class="field-label">插件类型</span>
            <select v-model="proxyDraft.plugin!.type" @change="proxyDraft.plugin = { type: proxyDraft.plugin!.type } as ProxyPluginConfig">
              <option value="">不使用</option>
              <option value="https2http">https2http</option>
              <option value="tls2raw">tls2raw</option>
            </select>
          </div>
          <template v-if="proxyDraft.plugin?.type">
            <div class="field">
              <span class="field-label">插件本地地址</span>
              <input v-model="proxyDraft.plugin!.localAddr" placeholder="127.0.0.1:80" />
            </div>
            <div class="form-row">
              <div class="field">
                <span class="field-label">证书文件</span>
                <input v-model="proxyDraft.plugin!.crtPath" />
              </div>
              <div class="field">
                <span class="field-label">密钥文件</span>
                <input v-model="proxyDraft.plugin!.keyPath" />
              </div>
            </div>
            <div v-if="proxyDraft.plugin!.type === 'https2http'" class="field">
              <span class="field-label">Host 重写</span>
              <input v-model="proxyDraft.plugin!.hostHeaderRewrite" />
            </div>
          </template>
        </template>
        <div class="modal-actions">
          <button class="btn text-btn" @click="proxyDialog = false">取消</button>
          <button class="btn filled" @click="saveProxy">保存</button>
        </div>
      </div>
    </div>

    <!-- 访问者编辑对话框 -->
    <div v-if="visitorDialog && visitorDraft" class="modal-mask" @click.self="visitorDialog = false">
      <div class="modal" style="width: 640px">
        <h3 class="modal-title">{{ visitorEditingIndex == null ? "添加访问者" : "编辑访问者" }}</h3>
        <div class="form-row">
          <div class="field" style="flex: 2">
            <span class="field-label">访问者名称</span>
            <input v-model="visitorDraft.name" />
          </div>
          <div class="field">
            <span class="field-label">类型</span>
            <select v-model="visitorDraft.type">
              <option v-for="t in VISITOR_TYPES" :key="t" :value="t">{{ t }}</option>
            </select>
          </div>
          <div class="switch-row" style="align-self: flex-end; padding-bottom: 6px">
            <span class="sw-label">启用</span>
            <label class="switch">
              <input v-model="visitorDraft.enabled" type="checkbox" />
              <span class="track"></span><span class="thumb"></span>
            </label>
          </div>
        </div>
        <div class="form-row">
          <div class="field" style="flex: 2">
            <span class="field-label">对方代理名</span>
            <input v-model="visitorDraft.serverName" />
          </div>
          <div class="field">
            <span class="field-label">私密密钥</span>
            <input v-model="visitorDraft.secretKey" type="password" />
          </div>
        </div>
        <div class="form-row">
          <div class="field">
            <span class="field-label">绑定地址</span>
            <input v-model="visitorDraft.bindAddr" />
          </div>
          <div class="field">
            <span class="field-label">本地监听端口</span>
            <input v-model.number="visitorDraft.bindPort" type="number" />
          </div>
          <div class="field">
            <span class="field-label">对方设备前缀（serverUser）</span>
            <input v-model="visitorDraft.serverUser" />
          </div>
        </div>
        <template v-if="visitorDraft.type === 'xtcp'">
          <div class="form-row">
            <div class="field">
              <span class="field-label">打洞失败转交给</span>
              <input v-model="visitorDraft.fallbackTo" />
            </div>
            <div class="field">
              <span class="field-label">打洞等待时长（ms）</span>
              <input v-model.number="visitorDraft.fallbackTimeoutMs" type="number" />
            </div>
            <div class="switch-row" style="align-self: flex-end; padding-bottom: 6px">
              <span class="sw-label">保持隧道</span>
              <label class="switch">
                <input v-model="visitorDraft.keepTunnelOpen" type="checkbox" />
                <span class="track"></span><span class="thumb"></span>
              </label>
            </div>
          </div>
        </template>
        <div class="modal-actions">
          <button class="btn text-btn" @click="visitorDialog = false">取消</button>
          <button class="btn filled" @click="saveVisitor">保存</button>
        </div>
      </div>
    </div>

    <!-- 保存运行中的配置 -->
    <div v-if="confirmSaveRunning" class="modal-mask" @click.self="confirmSaveRunning = false">
      <div class="modal" style="width: 480px">
        <h3 class="modal-title">保存运行中的配置</h3>
        <p style="margin: 0">保存后 frpc 仍在使用旧配置。是否立即重载让它生效？</p>
        <div class="modal-actions">
          <button class="btn text-btn" style="color: var(--brand)" @click="confirmSaveRunning = false; doSave(true)">
            保存并重载
          </button>
          <button class="btn text-btn" @click="confirmSaveRunning = false; doSave(false)">仅保存</button>
          <button class="btn text-btn" @click="confirmSaveRunning = false">取消</button>
        </div>
      </div>
    </div>
  </div>

  <!-- ============ 列表态 ============ -->
  <div v-else class="page">
    <div style="display: flex; align-items: center; justify-content: space-between; gap: 12px; flex-wrap: wrap">
      <div>
        <h2 class="page-title">服务器配置</h2>
        <p class="page-sub">每份配置对应一个 frps；导入的 frpc.toml 会按代理类型清理掉多余字段</p>
      </div>
      <div style="display: flex; gap: 12px">
        <label class="btn">
          导入
          <input type="file" accept=".toml" hidden @change="onImportFile" />
        </label>
        <button class="btn filled" @click="openEditor(null)">新建配置</button>
      </div>
    </div>

    <div style="display: flex; gap: 12px; align-items: center; flex-wrap: wrap">
      <div class="field" style="flex: 1">
        <input v-model="query" placeholder="名称 / 服务器地址" />
      </div>
      <button class="btn" @click="exportAll">导出全部</button>
      <label class="btn">
        导入全部
        <input ref="bundleFile" type="file" accept=".json" hidden @change="onImportBundle" />
      </label>
      <button class="btn text-btn danger-text" @click="clearAllConfirm = true">清空全部</button>
    </div>

    <div v-if="message" class="section-card" style="flex-direction: row; justify-content: space-between; align-items: center">
      <span>{{ message }}</span>
      <button class="btn text-btn" @click="message = ''">关闭</button>
    </div>

    <div v-if="!store.profiles.length" class="section-card">
      <div style="font-size: 15px; font-weight: 500">还没有任何配置</div>
      <p class="muted" style="margin: 0">点右上角「新建配置」从零填，或「导入」现成的 frpc.toml</p>
    </div>
    <div v-else-if="!visible.length" class="section-card">
      <p class="muted" style="margin: 0">没有匹配「{{ query }}」的配置</p>
    </div>

    <div v-for="profile in visible" :key="profile.id" class="section-card">
      <div style="display: flex; align-items: center; gap: 8px">
        <div style="flex: 1; min-width: 0">
          <div style="display: flex; align-items: center; gap: 8px">
            <span style="font-size: 15px; font-weight: 500">{{ profile.name }}</span>
            <span v-if="store.runningId === profile.id" class="status-chip chip-running">运行中</span>
            <span v-if="store.pendingReload === profile.id" class="status-chip chip-error">待重载</span>
            <span v-if="store.settings?.defaultProfileId === profile.id" class="status-chip chip-brand">默认</span>
          </div>
          <div class="muted">
            {{ `${profile.config.serverAddr}:${profile.config.serverPort}` }} · 代理
            {{ profile.config.proxies.length }} 个<template v-if="profile.config.visitors.length">
              · 访问者 {{ profile.config.visitors.length }} 个</template
            >
          </div>
          <div
            v-if="store.runningId === profile.id"
            class="small"
            :class="store.pendingReload === profile.id ? 'error-text' : 'muted'"
          >
            {{
              store.pendingReload === profile.id
                ? "已保存，尚未重载，仍在使用旧配置"
                : "运行中，保存后需「重载配置」才生效"
            }}
          </div>
        </div>
        <button class="icon-btn" title="编辑" @click="openEditor(profile)">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M3 17.25V21h3.75L17.81 9.94l-3.75-3.75L3 17.25zM20.71 7.04c.39-.39.39-1.02 0-1.41l-2.34-2.34a.9959.9959 0 0 0-1.41 0l-1.83 1.83 3.75 3.75 1.83-1.83z" />
          </svg>
        </button>
        <button class="icon-btn" title="复制" @click="store.duplicateProfile(profile.id)">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M16 1H4c-1.1 0-2 .9-2 2v14h2V3h12V1zm3 4H8c-1.1 0-2 .9-2 2v14c0 1.1.9 2 2 2h11c1.1 0 2-.9 2-2V7c0-1.1-.9-2-2-2zm0 16H8V7h11v14z" />
          </svg>
        </button>
        <button class="icon-btn" title="导出" @click="exportToml(profile)">
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M19 9h-4V3H9v6H5l7 7 7-7zM5 18v2h14v-2H5z" />
          </svg>
        </button>
        <button
          class="icon-btn"
          title="设为默认"
          :disabled="store.settings?.defaultProfileId === profile.id"
          @click="store.saveSettings({ ...store.settings!, defaultProfileId: profile.id })"
        >
          <svg viewBox="0 0 24 24" fill="currentColor">
            <path d="M12 17.27 18.18 21l-1.64-7.03L22 9.24l-7.19-.61L12 2 9.19 8.63 2 9.24l5.46 4.73L5.82 21z" />
          </svg>
        </button>
        <button
          class="icon-btn"
          title="删除"
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
        <h3 class="modal-title">清空全部配置</h3>
        <p style="margin: 0">
          将删除 {{ store.profiles.length }} 份配置（含代理与访问者规则），并停止正在运行的服务。该操作不可撤销——建议先点「导出全部」备份。
        </p>
        <div class="modal-actions">
          <button class="btn text-btn" style="color: var(--error)" @click="clearAll">清空</button>
          <button class="btn text-btn" @click="clearAllConfirm = false">取消</button>
        </div>
      </div>
    </div>

    <div v-if="deleteTarget" class="modal-mask" @click.self="deleteTarget = null">
      <div class="modal" style="width: 480px">
        <h3 class="modal-title">删除配置</h3>
        <p style="margin: 0">
          确定删除「{{ deleteTarget.name }}」（{{ `${deleteTarget.config.serverAddr}:${deleteTarget.config.serverPort}` }}，代理
          {{ deleteTarget.config.proxies.length }} 个）？该操作不可撤销。
        </p>
        <div class="modal-actions">
          <button class="btn text-btn" style="color: var(--error)" @click="confirmDelete">删除</button>
          <button class="btn text-btn" @click="deleteTarget = null">取消</button>
        </div>
      </div>
    </div>
  </div>
</template>
