<script setup lang="ts">
import { computed, onMounted, ref } from "vue";
import { useI18n } from "vue-i18n";
import { useEngineStore } from "../stores/engine";

const { t } = useI18n();
const store = useEngineStore();
const selectedId = ref<string>("");
const actionError = ref("");

onMounted(() => {
  if (!selectedId.value) {
    const d = store.settings?.defaultProfileId;
    selectedId.value = d && store.profiles.some((p) => p.id === d)
      ? d
      : store.profiles[0]?.id ?? "";
  }
});

const selected = computed(() => store.profiles.find((p) => p.id === selectedId.value));

const proxyRows = computed(() => {
  const cfg = selected.value?.config;
  if (!cfg) return [];
  return cfg.proxies
    .filter((p) => p.enabled !== false)
    .map((p) => ({
      name: p.name,
      type: p.type,
      detail: p.remotePort != null
        ? `${p.localIP ?? ""}:${p.localPort ?? ""} → ${p.remotePort}`
        : `${p.localIP ?? ""}:${p.localPort ?? ""} → ${t("settings.none")}`,
      state: store.proxyStates[p.name]?.phase ?? "PENDING",
      error: store.proxyStates[p.name]?.error ?? "",
    }));
});

const visitorRows = computed(() => {
  const cfg = selected.value?.config;
  if (!cfg) return [];
  return cfg.visitors.filter((v) => v.enabled !== false).map((v) => ({
    name: v.name,
    type: v.type,
    detail: `${v.bindAddr}:${v.bindPort} ← ${v.serverName}`,
  }));
});

function fmtUptime(ms: number): string {
  const s = Math.floor(ms / 1000);
  const h = Math.floor(s / 3600);
  const m = Math.floor((s % 3600) / 60);
  const ss = s % 60;
  return h > 0 ? `${h}h ${m}m ${ss}s` : m > 0 ? `${m}m ${ss}s` : `${ss}s`;
}

async function start() {
  actionError.value = "";
  try {
    await store.start(selectedId.value);
  } catch (e) {
    actionError.value = String(e).replace(/^Error:\s*/, "");
  }
}
</script>

<template>
  <section class="view">
    <h2>{{ $t("nav.home") }}</h2>

    <div v-if="store.hints.length" class="hints">
      <div v-for="(h, i) in store.hints.slice(-3)" :key="i" class="hint-card">
        {{ $t(`hint.${h.code}`, { name: h.name ?? "" }) }}
      </div>
    </div>

    <div class="card status-card">
      <div class="status-head">
        <span class="engine-dot big" :class="{ on: store.isRunning, err: store.runState.phase === 'ERROR' }"></span>
        <strong>{{ $t(`engine.${store.runState.phase.toLowerCase()}`) }}</strong>
        <span v-if="store.runState.profileId" class="muted">
          {{ store.runState.profileName }} · PID {{ store.runState.pid }}
        </span>
        <span v-if="store.isRunning" class="muted">{{ fmtUptime(store.uptimeMs) }}</span>
      </div>
      <p v-if="store.runState.error" class="error-text">{{ store.runState.error }}</p>
      <p v-if="store.loginError" class="error-text">{{ $t("engine.loginError") }}：{{ store.loginError }}</p>
      <p v-if="actionError" class="error-text">{{ actionError }}</p>

      <div class="controls">
        <select v-model="selectedId" :disabled="store.isRunning" class="input">
          <option disabled value="">{{ $t("engine.selectProfile") }}</option>
          <option v-for="p in store.profiles" :key="p.id" :value="p.id">
            {{ p.name }}（{{ p.config.serverAddr }}:{{ p.config.serverPort }}）
          </option>
        </select>
        <button class="btn primary" :disabled="store.isRunning || !selectedId" @click="start">
          {{ $t("engine.start") }}
        </button>
        <button class="btn" :disabled="!store.isRunning" @click="store.stop()">
          {{ $t("engine.stop") }}
        </button>
        <button class="btn" :disabled="store.runState.phase !== 'RUNNING'" @click="store.reload()">
          {{ $t("engine.reload") }}
        </button>
      </div>
      <p v-if="store.pendingReload" class="muted small">⚠ {{ $t("profiles.saved") }} — {{ $t("engine.reload") }}</p>
    </div>

    <div class="grid-2">
      <div class="card">
        <h3>{{ $t("engine.serverConnected") }}</h3>
        <p>
          <span class="pill" :class="store.serverConnected === true ? 'ok' : store.serverConnected === false ? 'bad' : ''">
            {{ store.serverConnected === true
              ? $t("engine.connected")
              : store.serverConnected === false
                ? $t("engine.disconnected")
                : $t("engine.unknown") }}
          </span>
          <span class="pill" :class="store.adminReachable ? 'ok' : ''">
            {{ $t("engine.adminReachable") }}：{{ store.adminReachable ? "OK" : "—" }}
          </span>
        </p>
        <h3>{{ $t("engine.rules") }}</h3>
        <p class="muted">
          {{ $t("engine.proxies") }} {{ store.activeCounts.proxies }} ·
          {{ $t("engine.visitors") }} {{ store.activeCounts.visitors }}
        </p>
        <p v-if="store.runState.restartCount > 0" class="muted small">
          {{ $t("engine.restarts") }}：{{ store.runState.restartCount }}
        </p>
      </div>

      <div class="card">
        <h3>{{ $t("proxy.list") }}</h3>
        <p v-if="!proxyRows.length" class="muted">{{ $t("proxy.noRules") }}</p>
        <table v-else class="table">
          <tbody>
            <tr v-for="row in proxyRows" :key="row.name">
              <td>
                <span class="pill" :class="row.state === 'RUNNING' ? 'ok' : row.state === 'ERROR' ? 'bad' : ''">
                  {{ $t(`proxy.phase.${row.state}`) }}
                </span>
              </td>
              <td><strong>{{ row.name }}</strong> <span class="muted small">{{ row.type }}</span></td>
              <td class="muted small">{{ row.detail }}</td>
            </tr>
          </tbody>
        </table>
        <h3 v-if="visitorRows.length">{{ $t("proxy.visitorList") }}</h3>
        <table v-if="visitorRows.length" class="table">
          <tbody>
            <tr v-for="row in visitorRows" :key="row.name">
              <td><strong>{{ row.name }}</strong> <span class="muted small">{{ row.type }}</span></td>
              <td class="muted small">{{ row.detail }}</td>
            </tr>
          </tbody>
        </table>
      </div>
    </div>
  </section>
</template>
