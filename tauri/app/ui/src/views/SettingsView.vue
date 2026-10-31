<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api, type SettingsData } from "../api";
import { applyLocale, localeSetting, type LocaleSetting } from "../i18n";
import { useEngineStore } from "../stores/engine";

const store = useEngineStore();
const { t } = useI18n();
// 设置与档案卡/主页共用同一份数据（store.settings），避免两个副本互相覆盖
const settings = computed(() => store.settings);
const engineInfo = computed(() => store.engineInfo);
const dataDir = ref("");
const engineMessage = ref("");
const showEngineAdvanced = ref(false);
// 托盘是桌面专属：Android 上没有托盘，这个开关开了也不会生效，直接不展示
const isAndroid = /Android/i.test(navigator.userAgent || "");

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
      <p style="margin: 0">
        frpc-desktop-multi {{ store.appVersion ? "v" + store.appVersion : "" }}
      </p>
      <p class="muted small" style="margin: 0">
        {{ $t("settings.aboutEngine", { version: engineInfo?.bundledVersion ?? "unknown", dir: dataDir }) }}
      </p>
    </div>
    <div style="height: 8px"></div>
  </div>
</template>
