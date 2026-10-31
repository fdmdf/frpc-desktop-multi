<script setup lang="ts">
import { onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { api, type EngineInfo, type SettingsData } from "../api";
import { useEngineStore } from "../stores/engine";

const { locale } = useI18n();
const store = useEngineStore();
const draft = ref<SettingsData | null>(null);
const engineInfo = ref<EngineInfo | null>(null);
const dataDir = ref("");
const saved = ref(false);

onMounted(async () => {
  draft.value = await api.getSettings();
  engineInfo.value = await api.getEngineInfo();
  dataDir.value = await api.getDataDir();
});

async function save() {
  if (!draft.value) return;
  await store.saveSettings(draft.value);
  saved.value = true;
  setTimeout(() => (saved.value = false), 1500);
}

function setLocale(l: string) {
  locale.value = l;
  localStorage.setItem("locale", l);
}
</script>

<template>
  <section class="view" v-if="draft">
    <h2>{{ $t("nav.settings") }} <span v-if="saved" class="pill ok">{{ $t("settings.saved") }}</span></h2>

    <div class="card">
      <h3>{{ $t("settings.language") }}</h3>
      <div class="controls">
        <button class="btn" :class="{ primary: locale === 'zh-CN' }" @click="setLocale('zh-CN')">简体中文</button>
        <button class="btn" :class="{ primary: locale === 'en' }" @click="setLocale('en')">English</button>
      </div>

      <h3>{{ $t("settings.defaultProfile") }}</h3>
      <select v-model="draft.defaultProfileId" class="input">
        <option :value="null">{{ $t("settings.none") }}</option>
        <option v-for="p in store.profiles" :key="p.id" :value="p.id">{{ p.name }}</option>
      </select>
      <label class="check-row">
        <input v-model="draft.autoStartOnLaunch" type="checkbox" />
        {{ $t("settings.autoStartOnLaunch") }}
      </label>

      <h3>{{ $t("logs.filter") }}</h3>
      <label class="check-row">
        <input v-model="draft.showAccessLogs" type="checkbox" />
        {{ $t("settings.showAccessLogs") }}
      </label>

      <h3>{{ $t("settings.closeToTray") }}</h3>
      <label class="check-row">
        <input v-model="draft.closeToTray" type="checkbox" />
        {{ $t("settings.closeToTray") }}
      </label>

      <h3>{{ $t("settings.engine") }}</h3>
      <p class="muted small">
        {{ $t("settings.engineBundledVersion") }}：{{ engineInfo?.bundledVersion ?? "unknown" }}
        · {{ engineInfo?.usingCustom ? $t("settings.engineCustom") : "" }}
        <span v-if="engineInfo && !engineInfo.exists" class="error-text">{{ $t("settings.engineMissing") }}</span>
      </p>
      <input v-model="draft.enginePath" class="input" :placeholder="$t('settings.enginePathHint')" />
      <p class="muted small">{{ $t("settings.enginePathHint") }}</p>

      <h3>{{ $t("settings.dataDir") }}</h3>
      <p class="muted small">{{ dataDir }}</p>

      <div class="controls">
        <button class="btn primary" @click="save">{{ $t("common.save") }}</button>
      </div>
    </div>
  </section>
</template>
