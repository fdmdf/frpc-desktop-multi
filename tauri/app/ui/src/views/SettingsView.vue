<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api, type SettingsData } from "../api";
import logo from "../assets/logo.svg";
import { errorText } from "../format";
import { applyLocale, localeSetting, type LocaleSetting } from "../i18n";
import { useEngineStore } from "../stores/engine";

const store = useEngineStore();
const { t } = useI18n();
// 设置与档案卡/主页共用同一份数据（store.settings），避免两个副本互相覆盖
const settings = computed(() => store.settings);
const engineInfo = computed(() => store.engineInfo);
const dataDir = ref("");
const engineMessage = ref("");
const batteryMessage = ref("");
const aboutMessage = ref("");
const showEngineAdvanced = ref(false);
// 托盘是桌面专属：Android 上没有托盘，这个开关开了也不会生效，直接不展示
const isAndroid = /Android/i.test(navigator.userAgent || "");
const repoUrl = "https://github.com/fdmdf/frpc-desktop-multi";

/// 关于页要显示「运行在什么平台上」，UA 里的版本号比 navigator.platform 有信息量
const platformLabel = computed(() => {
  const ua = navigator.userAgent || "";
  if (/Android/i.test(ua)) {
    const m = ua.match(/Android\s([\d.]+)/);
    return m ? `Android ${m[1]}` : "Android";
  }
  if (/Windows/i.test(ua)) return "Windows";
  if (/Mac OS X|Macintosh/i.test(ua)) return "macOS";
  if (/Linux/i.test(ua)) return "Linux";
  return navigator.platform || "—";
});

onMounted(async () => {
  if (!store.settings) store.settings = await api.getSettings();
  if (!store.engineInfo) await store.refreshEngineInfo();
  dataDir.value = await api.getDataDir();
});

function update(patch: Partial<SettingsData>) {
  const base = store.settings;
  if (!base) return;
  void store.saveSettings({ ...base, ...patch });
}

/// 语言是纯界面偏好（localStorage + vue-i18n），不进 settings.json
function onLocaleChange(e: Event) {
  applyLocale((e.target as HTMLSelectElement).value as LocaleSetting);
}

async function checkEngine() {
  // 自定义路径改了要重新解析（内置/自定义、是否存在）
  await store.refreshEngineInfo();
  const info = store.engineInfo;
  engineMessage.value = info?.exists
    ? t("settings.engineReady", { path: info.path })
    : t("settings.engineBroken", { path: info?.path ?? t("settings.builtinEngine") });
}

/// 对齐旧版 FileDialog 选择 frpc 可执行文件
async function browseEngine() {
  const { open } = await import("@tauri-apps/plugin-dialog");
  const picked = await open({
    title: t("settings.chooseEngineTitle"),
    multiple: false,
    directory: false,
    filters: [
      { name: "frpc", extensions: ["exe", "so", "bin"] },
      { name: t("settings.allFiles"), extensions: ["*"] },
    ],
  });
  if (typeof picked === "string" && picked) {
    update({ enginePath: picked });
    engineMessage.value = "";
  }
}

/// 关于页外链：Android 走 Intent.ACTION_VIEW，桌面走系统默认浏览器
async function openLink(url: string) {
  try {
    await api.openUrl(url);
    aboutMessage.value = "";
  } catch (err) {
    aboutMessage.value = errorText(err);
  }
}

/// 复制诊断信息：反馈问题时这几个数字基本能定位一半原因
async function copyDiagnostics() {
  const lines = [
    `frpc-desktop-multi ${store.appVersion ? "v" + store.appVersion : "(dev)"}`,
    `${t("settings.aboutPlatform")}: ${platformLabel.value}`,
    `frp: ${engineInfo.value?.bundledVersion ?? "unknown"}`,
    `engine: ${engineInfo.value?.path ?? "-"}`,
    `data: ${dataDir.value}`,
  ];
  try {
    if (!navigator.clipboard) throw new Error(t("home.clipboardUnavailable"));
    await navigator.clipboard.writeText(lines.join("\n"));
    aboutMessage.value = t("settings.aboutCopied");
  } catch {
    aboutMessage.value = t("settings.aboutCopyFailed");
  }
}

/// 申请电池优化白名单：true = 本来就在白名单，false = 已跳到系统页面等用户确认
async function askBatteryExemption() {
  try {
    const already = await api.requestBatteryExemption();
    batteryMessage.value = already
      ? t("settings.keepAliveOk")
      : t("settings.keepAliveRequested");
  } catch (err) {
    batteryMessage.value = t("settings.keepAliveFailed", { err: errorText(err) });
  }
}
</script>

<template>
  <div class="page" v-if="settings">
    <h2 class="page-title">{{ $t("settings.title") }}</h2>

    <div class="section-card">
      <h3 class="card-title">{{ $t("settings.appearance") }}</h3>
      <div class="field">
        <span class="field-label">{{ $t("settings.theme") }}</span>
        <select :value="settings.theme" @change="update({ theme: ($event.target as HTMLSelectElement).value })">
          <option value="system">{{ $t("settings.themeSystem") }}</option>
          <option value="light">{{ $t("settings.themeLight") }}</option>
          <option value="dark">{{ $t("settings.themeDark") }}</option>
        </select>
      </div>
      <div class="field">
        <span class="field-label">{{ $t("settings.language") }}</span>
        <select :value="localeSetting" @change="onLocaleChange">
          <option value="system">{{ $t("settings.langSystem") }}</option>
          <option value="zh-CN">简体中文</option>
          <option value="en">English</option>
        </select>
      </div>
    </div>

    <div class="section-card">
      <h3 class="card-title">{{ $t("settings.startup") }}</h3>
      <div class="switch-row">
        <div class="sw-text">
          <div class="sw-label">{{ $t("settings.autoStart") }}</div>
          <div class="sw-support">
            {{
              store.profiles.length
                ? $t("settings.currentDefault", {
                    name: store.profiles.find((p) => p.id === settings!.defaultProfileId)?.name ?? store.profiles[0].name,
                  })
                : $t("settings.noProfiles")
            }}
          </div>
        </div>
        <label class="switch">
          <input
            type="checkbox"
            :checked="settings.autoStartOnLaunch"
            @change="update({ autoStartOnLaunch: ($event.target as HTMLInputElement).checked })"
          />
          <span class="track"></span><span class="thumb"></span>
        </label>
      </div>
      <div v-if="!isAndroid" class="switch-row">
        <div class="sw-text">
          <div class="sw-label">{{ $t("settings.closeToTray") }}</div>
          <div class="sw-support">{{ $t("settings.closeToTraySupport") }}</div>
        </div>
        <label class="switch">
          <input
            type="checkbox"
            :checked="settings.closeToTray"
            @change="update({ closeToTray: ($event.target as HTMLInputElement).checked })"
          />
          <span class="track"></span><span class="thumb"></span>
        </label>
      </div>
    </div>

    <!-- Android 专属：前台服务保活。隧道是应用进程的子进程，父亲的命就是儿子的命 -->
    <div v-if="isAndroid" class="section-card">
      <h3 class="card-title">{{ $t("settings.keepAliveTitle") }}</h3>
      <p class="muted small" style="margin: 0">{{ $t("settings.keepAliveHint") }}</p>
      <p class="muted small" style="margin: 0">{{ $t("settings.keepAliveBatteryHint") }}</p>
      <div style="display: flex; gap: 12px; align-items: center; flex-wrap: wrap">
        <button class="btn filled small" @click="askBatteryExemption">
          {{ $t("settings.keepAliveBattery") }}
        </button>
        <span v-if="batteryMessage" class="muted small">{{ batteryMessage }}</span>
      </div>
    </div>

    <div class="section-card">
      <h3 class="card-title">{{ $t("settings.logsSection") }}</h3>
      <div class="switch-row">
        <div class="sw-text">
          <div class="sw-label">{{ $t("settings.accessLogs") }}</div>
          <div class="sw-support">{{ $t("settings.accessLogsSupport") }}</div>
        </div>
        <label class="switch">
          <input
            type="checkbox"
            :checked="settings.showAccessLogs"
            @change="update({ showAccessLogs: ($event.target as HTMLInputElement).checked })"
          />
          <span class="track"></span><span class="thumb"></span>
        </label>
      </div>
    </div>

    <div class="section-card">
      <h3 class="card-title">{{ $t("settings.engine") }}</h3>
      <p style="margin: 0">{{ $t("settings.bundledVersion", { version: engineInfo?.bundledVersion ?? "unknown" }) }}</p>
      <p class="muted small" style="margin: 0">
        {{
          engineInfo?.usingCustom
            ? $t("settings.inUseCustom", { path: engineInfo?.path ?? "" })
            : $t("settings.inUseBundled", { path: engineInfo?.path ?? "" })
        }}
      </p>
      <!-- Android 只认随包内置的 libfrpc.so：10+ 禁止从可写目录 exec，选路径必然失败，
           所以整个「自定义引擎路径」入口都不展示，只留一句说明 -->
      <template v-if="!isAndroid">
        <button class="btn text-btn" @click="showEngineAdvanced = !showEngineAdvanced">
          {{ showEngineAdvanced ? $t("settings.advancedHide") : $t("settings.advancedShow") }}
        </button>
        <div v-if="showEngineAdvanced" style="display: flex; flex-direction: column; gap: 12px">
          <div class="field">
            <span class="field-label">{{ $t("settings.enginePath") }}</span>
            <input
              :value="settings.enginePath"
              placeholder="C:\tools\frpc.exe"
              @change="update({ enginePath: ($event.target as HTMLInputElement).value })"
            />
          </div>
          <div style="display: flex; gap: 12px; align-items: center; flex-wrap: wrap">
            <button class="btn" @click="browseEngine">{{ $t("settings.browse") }}</button>
            <button class="btn filled small" @click="checkEngine">{{ $t("settings.checkEngine") }}</button>
            <span v-if="engineMessage" class="muted small">{{ engineMessage }}</span>
          </div>
        </div>
      </template>
      <p v-else class="muted small" style="margin: 0">{{ $t("settings.androidEngineOnly") }}</p>
      <hr class="hr" />
      <div v-if="!isAndroid" style="display: flex; gap: 12px; flex-wrap: wrap">
        <button class="btn text-btn" @click="api.openPath(dataDir)">{{ $t("settings.openDataDir") }}</button>
        <button v-if="engineInfo?.path" class="btn text-btn" @click="api.openPath(engineInfo.path.replace(/[\\/]frpc(\.exe)?$/i, ''))">
          {{ $t("settings.openEngineDir") }}
        </button>
        <button class="btn text-btn" @click="api.openPath(dataDir + (dataDir.includes('\\') ? '\\run' : '/run'))">
          {{ $t("settings.openRunDir") }}
        </button>
      </div>
      <p v-else class="muted small" style="margin: 0">
        {{ $t("settings.androidDataHint") }}
      </p>
    </div>

    <div class="section-card">
      <h3 class="card-title">{{ $t("settings.about") }}</h3>
      <div class="about-head">
        <img class="about-logo" :src="logo" alt="" />
        <div style="min-width: 0">
          <div class="about-name">frpc-desktop-multi</div>
          <div class="muted small">
            {{ store.appVersion ? "v" + store.appVersion : $t("settings.aboutDevBuild") }} ·
            {{ platformLabel }}
          </div>
        </div>
      </div>
      <p class="muted small" style="margin: 0">{{ $t("settings.aboutTagline") }}</p>

      <div class="about-grid">
        <div class="about-item">
          <span class="about-key">{{ $t("settings.aboutEngineLabel") }}</span>
          <span class="about-val">frp {{ engineInfo?.bundledVersion ?? "unknown" }}</span>
        </div>
        <div class="about-item">
          <span class="about-key">{{ $t("settings.aboutStack") }}</span>
          <span class="about-val">Tauri 2 · Rust · Vue 3</span>
        </div>
        <div class="about-item">
          <span class="about-key">{{ $t("settings.aboutDataLabel") }}</span>
          <span class="about-val mono break-all">{{ dataDir || "—" }}</span>
        </div>
      </div>

      <div class="about-links">
        <button class="btn small" @click="openLink(repoUrl)">{{ $t("settings.aboutRepo") }}</button>
        <button class="btn small" @click="openLink(repoUrl + '/issues')">{{ $t("settings.aboutIssues") }}</button>
        <button class="btn small" @click="openLink(repoUrl + '/releases')">
          {{ $t("settings.aboutReleases") }}
        </button>
        <button class="btn text-btn small" @click="copyDiagnostics">
          {{ $t("settings.aboutCopyDiag") }}
        </button>
      </div>
      <p v-if="aboutMessage" class="muted small" style="margin: 0">{{ aboutMessage }}</p>
    </div>
    <div style="height: 8px"></div>
  </div>
</template>
