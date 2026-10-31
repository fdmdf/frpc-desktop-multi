<script setup lang="ts">
import { onMounted, ref } from "vue";
import { api, type EngineInfo, type SettingsData } from "../api";
import { useEngineStore } from "../stores/engine";

const store = useEngineStore();
const settings = ref<SettingsData | null>(null);
const engineInfo = ref<EngineInfo | null>(null);
const dataDir = ref("");
const engineMessage = ref("");
const showEngineAdvanced = ref(false);

onMounted(async () => {
  settings.value = await api.getSettings();
  engineInfo.value = await api.getEngineInfo();
  dataDir.value = await api.getDataDir();
});

function update(patch: Partial<SettingsData>) {
  if (!settings.value) return;
  settings.value = { ...settings.value, ...patch };
  void store.saveSettings(settings.value);
}

async function checkEngine() {
  // ensure_ready 的结果通过启动验证；这里直接尝试拉引擎信息
  engineInfo.value = await api.getEngineInfo();
  engineMessage.value = engineInfo.value.exists
    ? `引擎就绪：${engineInfo.value.path}`
    : `引擎不可用：未找到 ${engineInfo.value.path}`;
}

/// 对齐旧版 FileDialog 选择 frpc 可执行文件
async function browseEngine() {
  const { open } = await import("@tauri-apps/plugin-dialog");
  const picked = await open({
    title: "选择 frpc 可执行文件",
    multiple: false,
    directory: false,
    filters: [
      { name: "frpc", extensions: ["exe", "so", "bin"] },
      { name: "全部文件", extensions: ["*"] },
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
    <h2 class="page-title">设置</h2>

    <div class="section-card">
      <h3 class="card-title">外观</h3>
      <div class="field">
        <span class="field-label">主题</span>
        <select :value="settings.theme" @change="update({ theme: ($event.target as HTMLSelectElement).value })">
          <option value="system">跟随系统</option>
          <option value="light">浅色</option>
          <option value="dark">深色</option>
        </select>
      </div>
    </div>

    <div class="section-card">
      <h3 class="card-title">启动</h3>
      <div class="switch-row">
        <div class="sw-text">
          <div class="sw-label">启动应用时自动运行默认配置</div>
          <div class="sw-support">
            {{
              store.profiles.length
                ? `当前默认：${store.profiles.find((p) => p.id === settings!.defaultProfileId)?.name ?? store.profiles[0].name}`
                : "还没有任何配置"
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
      <div class="switch-row">
        <div class="sw-text">
          <div class="sw-label">关闭窗口时最小化到托盘</div>
          <div class="sw-support">关闭后 frpc 继续运行，可从托盘图标重新打开窗口或退出</div>
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
      <h3 class="card-title">日志</h3>
      <div class="switch-row">
        <div class="sw-text">
          <div class="sw-label">显示状态接口访问日志</div>
          <div class="sw-support">状态页每 4 秒刷新会产生两条日志，默认隐藏</div>
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
      <h3 class="card-title">frpc 引擎</h3>
      <p style="margin: 0">内置版本：{{ engineInfo?.bundledVersion ?? "unknown" }}</p>
      <p class="muted small" style="margin: 0">
        当前使用：{{ engineInfo?.path }}{{ engineInfo?.usingCustom ? "（自定义）" : "（内置）" }}
      </p>
      <button class="btn text-btn" @click="showEngineAdvanced = !showEngineAdvanced">
        {{ showEngineAdvanced ? "收起高级选项" : "高级：自定义引擎路径" }}
      </button>
      <div v-if="showEngineAdvanced" style="display: flex; flex-direction: column; gap: 12px">
        <div class="field">
          <span class="field-label">自定义引擎路径（留空用内置）</span>
          <input
            :value="settings.enginePath"
            placeholder="C:\tools\frpc.exe"
            @change="update({ enginePath: ($event.target as HTMLInputElement).value })"
          />
        </div>
        <div style="display: flex; gap: 12px; align-items: center; flex-wrap: wrap">
          <button class="btn" @click="browseEngine">浏览</button>
          <button class="btn filled small" @click="checkEngine">检查引擎</button>
          <span v-if="engineMessage" class="muted small">{{ engineMessage }}</span>
        </div>
      </div>
      <hr class="hr" />
      <div style="display: flex; gap: 12px; flex-wrap: wrap">
        <button class="btn text-btn" @click="api.openPath(dataDir)">打开数据目录</button>
        <button v-if="engineInfo?.path" class="btn text-btn" @click="api.openPath(engineInfo.path.replace(/[\\/]frpc(\.exe)?$/i, ''))">
          打开引擎目录
        </button>
        <button class="btn text-btn" @click="api.openPath(dataDir + (dataDir.includes('\\') ? '\\run' : '/run'))">
          打开运行目录
        </button>
      </div>
    </div>

    <div class="section-card">
      <h3 class="card-title">关于</h3>
      <p style="margin: 0">frpc-desktop-multi 0.1.0</p>
      <p class="muted small" style="margin: 0">
        内置引擎 frp {{ engineInfo?.bundledVersion ?? "unknown" }} · 配置文件 {{ dataDir }}
      </p>
    </div>
    <div style="height: 8px"></div>
  </div>
</template>
