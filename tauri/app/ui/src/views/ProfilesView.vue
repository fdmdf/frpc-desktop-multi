<script setup lang="ts">
import { computed, ref } from "vue";
import { useI18n } from "vue-i18n";
import {
  api,
  emptyConfig,
  type ProxyConfig,
  type ServerProfile,
  type ValidationIssue,
  type VisitorConfig,
} from "../api";
import { useEngineStore } from "../stores/engine";

const { t } = useI18n();
const store = useEngineStore();

const editing = ref<ServerProfile | null>(null);
const issues = ref<ValidationIssue[]>([]);
const showImport = ref(false);
const importText = ref("");
const busy = ref(false);
const notice = ref("");

const PROXY_TYPES = ["tcp", "udp", "http", "https", "stcp", "sudp", "xtcp", "tcpmux"] as const;
const VISITOR_TYPES = ["stcp", "sudp", "xtcp"] as const;

// 与 frpc-core::model ProxyType 能力位一致，驱动表单按类型显隐字段
const caps = (type: string) => ({
  remotePort: type === "tcp" || type === "udp",
  secretKey: ["stcp", "sudp", "xtcp"].includes(type),
  domain: ["http", "https", "tcpmux"].includes(type),
  httpAdv: type === "http",
  httpAuth: type === "http" || type === "tcpmux",
  mux: type === "tcpmux",
  httpsPlugin: type === "https",
});

function newProfile(): ServerProfile {
  return {
    id: crypto.randomUUID(),
    name: "",
    config: emptyConfig(),
    createdAt: Date.now(),
    updatedAt: Date.now(),
    lastUsedAt: 0,
  };
}

function edit(p: ServerProfile | null) {
  const copy: ServerProfile = p ? JSON.parse(JSON.stringify(p)) : newProfile();
  // 归一化：transport / plugin 恒为对象，表单可直接双向绑定（保存时 None 字段自动省略）
  for (const proxy of copy.config.proxies) {
    proxy.transport = proxy.transport ?? {
      bandwidthLimit: null,
      bandwidthLimitMode: null,
      useEncryption: null,
      useCompression: null,
      proxyProtocolVersion: null,
    };
  }
  editing.value = copy;
  issues.value = [];
}

function addProxy(type: string) {
  const cfg = editing.value?.config;
  if (!cfg) return;
  cfg.proxies.push({
    name: `${type}-${cfg.proxies.length + 1}`,
    type,
    enabled: true,
    localIP: "127.0.0.1",
    localPort: null,
    ...(type === "tcp" || type === "udp" ? { remotePort: null } : {}),
  } as ProxyConfig);
}

function addVisitor(type: string) {
  const cfg = editing.value?.config;
  if (!cfg) return;
  cfg.visitors.push({
    name: `visitor-${cfg.visitors.length + 1}`,
    type,
    serverName: "",
    bindAddr: "127.0.0.1",
    bindPort: 6000,
    enabled: true,
  } as VisitorConfig);
}

const csvWrite = (arr?: string[] | null) => (arr ?? []).join(",");
const csvRead = (v: string) =>
  v
    .split(/[,，]/)
    .map((s) => s.trim())
    .filter(Boolean);

async function save() {
  if (!editing.value || busy.value) return;
  busy.value = true;
  issues.value = [];
  try {
    await store.saveProfile(editing.value);
    editing.value = null;
    notice.value = t("profiles.saved");
    setTimeout(() => (notice.value = ""), 1500);
  } catch (e: unknown) {
    issues.value = e as ValidationIssue[];
  } finally {
    busy.value = false;
  }
}

async function validateNow() {
  if (!editing.value) return;
  issues.value = await api.validateConfig(editing.value.config);
}

const hasError = computed(() => issues.value.some((i) => i.severity === "ERROR"));

async function doImport() {
  try {
    const profile = await api.importToml("导入配置", importText.value);
    editing.value = profile;
    showImport.value = false;
    importText.value = "";
  } catch (e) {
    alert(String(e));
  }
}

async function onBundleFile(e: Event) {
  const file = (e.target as HTMLInputElement).files?.[0];
  if (!file) return;
  const text = await file.text();
  try {
    const summary = await api.importBundle(text, false);
    notice.value = t("profiles.bundleMerged", { added: summary.added, updated: summary.updated });
    await store.refreshProfiles();
  } catch (e) {
    alert(String(e));
  }
  (e.target as HTMLInputElement).value = "";
}

async function exportBundle() {
  const text = await api.exportBundle();
  const path = await dataDirPath("frpc-profiles.json");
  await api.writeTextFile(path, text);
  notice.value = t("profiles.exportedTo", { path });
}

async function exportToml(p: ServerProfile) {
  const text = await api.exportProfileToml(p.id);
  const path = await dataDirPath(`${p.name || "profile"}.toml`);
  await api.writeTextFile(path, text);
  notice.value = t("profiles.exportedTo", { path });
}

async function dataDirPath(name: string): Promise<string> {
  const dir = await api.getDataDir();
  const sep = dir.includes("\\") ? "\\" : "/";
  return `${dir.replace(/[\\/]+$/, "")}${sep}${name.replace(/[\\/]/g, "-")}`;
}

async function removeProfile(p: ServerProfile) {
  if (confirm(t("profiles.confirmDelete", { name: p.name }))) {
    await store.deleteProfile(p.id);
  }
}

function newProfileFromImportPlaceholder() {
  return t("profiles.importPlaceholder");
}

const placeholder = computed(() => newProfileFromImportPlaceholder());
</script>

<template>
  <section class="view">
    <h2>{{ $t("nav.profiles") }}</h2>

    <p v-if="notice" class="pill ok inline">{{ notice }}</p>

    <div v-if="!editing" class="card">
      <div class="toolbar">
        <button class="btn primary" @click="edit(null)">{{ $t("profiles.new") }}</button>
        <button class="btn" @click="showImport = !showImport">{{ $t("profiles.import") }}</button>
        <label class="btn file-btn">
          {{ $t("profiles.importBundle") }}
          <input type="file" accept=".json" hidden @change="onBundleFile" />
        </label>
        <button class="btn" @click="exportBundle">{{ $t("profiles.exportBundle") }}</button>
      </div>

      <div v-if="showImport" class="import-box">
        <textarea
          v-model="importText"
          class="input"
          rows="8"
          :placeholder="placeholder"
        ></textarea>
        <div class="controls">
          <button class="btn primary" :disabled="!importText.trim()" @click="doImport">
            {{ $t("profiles.importBtn") }}
          </button>
        </div>
      </div>

      <table class="table" v-if="store.profiles.length">
        <thead>
          <tr>
            <th>{{ $t("profiles.name") }}</th>
            <th>{{ $t("profiles.serverSummary") }}</th>
            <th></th>
          </tr>
        </thead>
        <tbody>
          <tr v-for="p in store.profiles" :key="p.id">
            <td><strong>{{ p.name }}</strong></td>
            <td class="muted small">{{ `${p.config.serverAddr}:${p.config.serverPort}` }}</td>
            <td class="row-actions">
              <button class="btn small" @click="edit(p)">{{ $t("profiles.edit") }}</button>
              <button class="btn small" @click="store.duplicateProfile(p.id)">{{ $t("profiles.duplicate") }}</button>
              <button class="btn small" @click="exportToml(p)">{{ $t("profiles.exportToml") }}</button>
              <button class="btn small danger" @click="removeProfile(p)">{{ $t("profiles.delete") }}</button>
            </td>
          </tr>
        </tbody>
      </table>
    </div>

    <!-- ============ 编辑器 ============ -->
    <div v-else class="card editor">
      <div class="toolbar">
        <input v-model="editing.name" class="input grow" :placeholder="$t('profiles.name')" />
        <button class="btn" @click="validateNow">{{ $t("profiles.validate") }}</button>
        <button class="btn primary" :disabled="busy" @click="save">{{ $t("common.save") }}</button>
        <button class="btn" @click="editing = null">{{ $t("common.cancel") }}</button>
      </div>

      <div v-if="issues.length" class="issues">
        <p v-for="(i, idx) in issues" :key="idx" class="issue" :class="{ error: i.severity === 'ERROR' }">
          <strong>{{ i.severity === "ERROR" ? "✕" : "⚠" }}</strong>
          {{ i.field }} — {{ $t(`reason.${i.reason}`) }}
          <span v-if="i.entityName">（{{ i.entityName }}）</span>
          <span v-if="i.detail">：{{ i.detail }}</span>
        </p>
        <p v-if="!hasError" class="pill ok inline">{{ $t("profiles.validationOk") }}</p>
      </div>

      <!-- 服务器 -->
      <h3>{{ $t("profiles.sections.server") }}</h3>
      <div class="form-grid">
        <label>{{ $t("profiles.serverAddr") }}<input v-model="editing.config.serverAddr" class="input" /></label>
        <label>{{ $t("profiles.serverPort") }}<input v-model.number="editing.config.serverPort" type="number" class="input" /></label>
        <label>{{ $t("profiles.user") }}<input v-model="editing.config.user" class="input" /></label>
        <label>{{ $t("profiles.clientID") }}<input v-model="editing.config.clientID" class="input" /></label>
        <label>{{ $t("profiles.natHoleStunServer") }}<input v-model="editing.config.natHoleStunServer" class="input" /></label>
        <label class="check-row">
          <input v-model="editing.config.loginFailExit" type="checkbox" />{{ $t("profiles.loginFailExit") }}
        </label>
      </div>

      <!-- 认证 -->
      <h3>{{ $t("profiles.sections.auth") }}</h3>
      <div class="form-grid">
        <label>
          {{ $t("profiles.authMethod") }}
          <select v-model="editing.config.auth!.method" class="input">
            <option value="token">token</option>
            <option value="none">none</option>
          </select>
        </label>
        <label v-if="editing.config.auth?.method === 'token'">
          {{ $t("profiles.token") }}<input v-model="editing.config.auth!.token" type="password" class="input" />
        </label>
      </div>

      <!-- 状态接口 -->
      <h3>
        <label class="check-row inline">
          <input
            type="checkbox"
            :checked="!!editing.config.webServer"
            @change="
              editing.config.webServer = ($event.target as HTMLInputElement).checked
                ? { addr: '127.0.0.1', port: 7400, user: 'admin', password: 'admin', pprofEnable: false }
                : null
            "
          />
          {{ $t("profiles.sections.webServer") }}
        </label>
      </h3>
      <div v-if="editing.config.webServer" class="form-grid">
        <label>{{ $t("profiles.wsAddr") }}<input v-model="editing.config.webServer.addr" class="input" /></label>
        <label>{{ $t("profiles.wsPort") }}<input v-model.number="editing.config.webServer.port" type="number" class="input" /></label>
        <label>{{ $t("profiles.wsUser") }}<input v-model="editing.config.webServer.user" class="input" /></label>
        <label>{{ $t("profiles.wsPassword") }}<input v-model="editing.config.webServer.password" type="password" class="input" /></label>
      </div>

      <!-- 代理规则 -->
      <h3>{{ $t("profiles.sections.proxies") }}</h3>
      <div class="type-buttons">
        <button v-for="ty in PROXY_TYPES" :key="ty" class="btn small" @click="addProxy(ty)">
          + {{ ty }}
        </button>
      </div>
      <div v-for="(p, pi) in editing.config.proxies" :key="pi" class="proxy-card">
        <div class="form-grid">
          <label>{{ $t("profiles.proxyName") }}<input v-model="p.name" class="input" /></label>
          <label>
            {{ $t("profiles.type") }}
            <select v-model="p.type" class="input">
              <option v-for="ty in PROXY_TYPES" :key="ty" :value="ty">{{ ty }}</option>
            </select>
          </label>
          <label>{{ $t("profiles.localIP") }}<input v-model="p.localIP" class="input" /></label>
          <label>{{ $t("profiles.localPort") }}<input v-model.number="p.localPort" type="number" class="input" /></label>
          <template v-if="caps(p.type).remotePort">
            <label>
              {{ $t("profiles.remotePort") }}
              <input v-model.number="p.remotePort" type="number" class="input" :placeholder="$t('profiles.disableHint')" />
            </label>
          </template>
          <label class="check-row"><input v-model="p.enabled" type="checkbox" />{{ $t("profiles.enabled") }}</label>
          <template v-if="caps(p.type).secretKey">
            <label>{{ $t("profiles.secretKey") }}<input v-model="p.secretKey" type="password" class="input" /></label>
            <label>{{ $t("profiles.allowUsers") }}<input :value="csvWrite(p.allowUsers)" class="input" @change="p.allowUsers = csvRead(($event.target as HTMLInputElement).value)" /></label>
          </template>
          <template v-if="caps(p.type).domain">
            <label>{{ $t("profiles.subdomain") }}<input v-model="p.subdomain" class="input" /></label>
            <label>{{ $t("profiles.customDomains") }}<input :value="csvWrite(p.customDomains)" class="input" @change="p.customDomains = csvRead(($event.target as HTMLInputElement).value)" /></label>
          </template>
          <template v-if="caps(p.type).mux">
            <label>{{ $t("profiles.multiplexer") }}<input v-model="p.multiplexer" class="input" placeholder="httpconnect" /></label>
          </template>
          <template v-if="caps(p.type).httpAuth">
            <label>{{ $t("profiles.httpUser") }}<input v-model="p.httpUser" class="input" /></label>
            <label>{{ $t("profiles.httpPassword") }}<input v-model="p.httpPassword" type="password" class="input" /></label>
            <label>{{ $t("profiles.routeByHTTPUser") }}<input v-model="p.routeByHTTPUser" class="input" /></label>
          </template>
          <template v-if="caps(p.type).httpAdv">
            <label>{{ $t("profiles.hostHeaderRewrite") }}<input v-model="p.hostHeaderRewrite" class="input" /></label>
            <label>{{ $t("profiles.locations") }}<input :value="csvWrite(p.locations)" class="input" @change="p.locations = csvRead(($event.target as HTMLInputElement).value)" /></label>
          </template>
        </div>

        <details class="transport-details">
          <summary>{{ $t("profiles.transportTitle") }}</summary>
          <div class="form-grid">
            <label>{{ $t("profiles.bandwidthLimit") }}<input v-model="p.transport!.bandwidthLimit" class="input" placeholder="1MB" /></label>
            <label>
              {{ $t("profiles.bandwidthLimitMode") }}
              <select v-model="p.transport!.bandwidthLimitMode" class="input">
                <option value=""></option>
                <option value="client">client</option>
                <option value="server">server</option>
              </select>
            </label>
            <label class="check-row"><input v-model="p.transport!.useEncryption" type="checkbox" />{{ $t("profiles.useEncryption") }}</label>
            <label class="check-row"><input v-model="p.transport!.useCompression" type="checkbox" />{{ $t("profiles.useCompression") }}</label>
          </div>
        </details>

        <div v-if="caps(p.type).httpsPlugin" class="form-grid plugin-grid">
          <label class="check-row">
            <input
              type="checkbox"
              :checked="!!p.plugin"
              @change="
                ($event.target as HTMLInputElement).checked
                  ? (p.plugin = { type: 'https2http' })
                  : (p.plugin = null)
              "
            />
            {{ $t("profiles.pluginEnable") }}
          </label>
          <template v-if="p.plugin">
            <label>
              {{ $t("profiles.pluginType") }}
              <select v-model="p.plugin.type" class="input">
                <option value="https2http">https2http</option>
                <option value="tls2raw">tls2raw</option>
              </select>
            </label>
            <label>{{ $t("profiles.pluginLocalAddr") }}<input v-model="p.plugin.localAddr" class="input" /></label>
            <label>{{ $t("profiles.pluginCrt") }}<input v-model="p.plugin.crtPath" class="input" /></label>
            <label>{{ $t("profiles.pluginKey") }}<input v-model="p.plugin.keyPath" class="input" /></label>
          </template>
        </div>

        <div class="row-actions">
          <button class="btn small danger" @click="editing.config.proxies.splice(pi, 1)">
            {{ $t("profiles.delete") }}
          </button>
        </div>
      </div>

      <!-- 访问者 -->
      <h3>{{ $t("profiles.sections.visitors") }}</h3>
      <div class="type-buttons">
        <button v-for="ty in VISITOR_TYPES" :key="ty" class="btn small" @click="addVisitor(ty)">
          + {{ ty }}
        </button>
      </div>
      <div v-for="(v, vi) in editing.config.visitors" :key="vi" class="proxy-card">
        <div class="form-grid">
          <label>{{ $t("profiles.visitorName") }}<input v-model="v.name" class="input" /></label>
          <label>
            {{ $t("profiles.type") }}
            <select v-model="v.type" class="input">
              <option v-for="ty in VISITOR_TYPES" :key="ty" :value="ty">{{ ty }}</option>
            </select>
          </label>
          <label>{{ $t("profiles.serverName") }}<input v-model="v.serverName" class="input" /></label>
          <label>{{ $t("profiles.secretKey") }}<input v-model="v.secretKey" type="password" class="input" /></label>
          <label>{{ $t("profiles.bindAddr") }}<input v-model="v.bindAddr" class="input" /></label>
          <label>{{ $t("profiles.bindPort") }}<input v-model.number="v.bindPort" type="number" class="input" /></label>
          <label>{{ $t("profiles.serverUser") }}<input v-model="v.serverUser" class="input" /></label>
          <label class="check-row"><input v-model="v.enabled" type="checkbox" />{{ $t("profiles.enabled") }}</label>
          <template v-if="v.type === 'xtcp'">
            <label>{{ $t("profiles.fallbackTo") }}<input v-model="v.fallbackTo" class="input" /></label>
            <label>{{ $t("profiles.fallbackTimeoutMs") }}<input v-model.number="v.fallbackTimeoutMs" type="number" class="input" /></label>
            <label class="check-row"><input v-model="v.keepTunnelOpen" type="checkbox" />{{ $t("profiles.keepTunnelOpen") }}</label>
          </template>
        </div>
        <div class="row-actions">
          <button class="btn small danger" @click="editing.config.visitors.splice(vi, 1)">
            {{ $t("profiles.delete") }}
          </button>
        </div>
      </div>

      <div class="controls">
        <button class="btn primary" :disabled="busy" @click="save">{{ $t("common.save") }}</button>
        <button class="btn" @click="editing = null">{{ $t("common.cancel") }}</button>
      </div>
    </div>
  </section>
</template>
