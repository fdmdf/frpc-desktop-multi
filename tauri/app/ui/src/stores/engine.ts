// 全局引擎/档案状态：Tauri 事件订阅 + 初始拉取 + 操作动作
import { defineStore } from "pinia";
import { listen, type UnlistenFn } from "@tauri-apps/api/event";
import { invoke } from "@tauri-apps/api/core";
import { getVersion } from "@tauri-apps/api/app";
import { api as A } from "../api";
import type {
  ActiveRuleCounts,
  EngineInfo,
  ErrorHint,
  LogLine,
  ProcMetrics,
  ProxyStatusSnapshot,
  RunState,
  ServerProfile,
  SettingsData,
} from "../api";

export const useEngineStore = defineStore("engine", {
  state: () => ({
    ready: false,
    engineVersion: "",
    /// 应用版本（tauri.conf.json 的 version，与仓库 tag 对齐）
    appVersion: "",
    editingProfileId: null as string | null,
    runState: {
      phase: "IDLE",
      profileName: "",
      adminEnabled: false,
      adminPort: 0,
      restartCount: 0,
    } as RunState,
    profiles: [] as ServerProfile[],
    settings: null as SettingsData | null,
    engineInfo: null as EngineInfo | null,
    logs: [] as LogLine[],
    hints: [] as (ErrorHint & { at: number })[],
    proxyStates: {} as Record<string, { phase: string; error?: string | null }>,
    visitors: [] as string[],
    status: null as ProxyStatusSnapshot | null,
    activeCounts: { proxies: 0, visitors: 0 } as ActiveRuleCounts,
    serverConnected: null as boolean | null,
    loginError: null as string | null,
    adminReachable: false,
    pendingReload: null as string | null,
    metrics: null as ProcMetrics | null,
    metricsHistory: [] as { t: number; cpu: number | null; rssKb: number | null }[],
    unlisteners: [] as UnlistenFn[],
  }),

  getters: {
    isRunning: (s) => s.runState.phase === "RUNNING" || s.runState.phase === "STARTING",
    runningId: (s) => (s.runState.phase === "RUNNING" ? s.runState.profileId : null),
    currentProfileId: (s) =>
      s.runState.profileId ?? s.settings?.defaultProfileId ?? s.profiles[0]?.id ?? null,
    uptimeMs: (s) =>
      s.runState.startedAtMs ? Date.now() - s.runState.startedAtMs : 0,
  },

  actions: {
    async init() {
      if (this.ready) return;
      try {
        this.appVersion = await getVersion();
      } catch {
        this.appVersion = "";
      }
      this.runState = await A.getEngineState();
      this.profiles = await A.listProfiles();
      this.settings = await A.getSettings();
      this.engineInfo = await A.getEngineInfo();
      this.engineVersion = this.engineInfo.bundledVersion;
      this.logs = await A.getLogs();
      this.proxyStates = await A.getProxyStates();
      this.visitors = await A.getVisitors();
      this.status = await A.getStatusSnapshot();
      this.activeCounts = await A.getActiveCounts();
      this.serverConnected = await A.getServerConnected();
      this.loginError = await A.getLoginError();
      this.adminReachable = await A.getAdminReachable();
      this.pendingReload = await A.getPendingReload();
      this.metrics = await A.getMetrics();

      const on = <T,>(event: string, fn: (v: T) => void) =>
        listen<T>(event, (e) => fn(e.payload)).then((u) => this.unlisteners.push(u));

      await on<RunState>("engine-state", (v) => (this.runState = v));
      await on<LogLine>("log-line", (v) => {
        this.logs.push(v);
        if (this.logs.length > 3000) this.logs.splice(0, this.logs.length - 3000);
      });
      await on<ErrorHint>("error-hint", (v) => {
        this.hints.push({ ...v, at: Date.now() });
        if (this.hints.length > 20) this.hints.splice(0, this.hints.length - 20);
      });
      await on<Record<string, { phase: string; error?: string | null }>>(
        "proxy-states",
        (v) => (this.proxyStates = v),
      );
      await on<string[]>("visitors-changed", (v) => (this.visitors = v));
      await on<ProxyStatusSnapshot | null>("status-refreshed", (v) => (this.status = v));
      await on<ActiveRuleCounts>("active-counts", (v) => (this.activeCounts = v));
      await on<boolean | null>("server-connected", (v) => (this.serverConnected = v));
      await on<string | null>("login-error", (v) => (this.loginError = v));
      await on<boolean>("admin-reachable", (v) => (this.adminReachable = v));
      await on<string | null>("pending-reload", (v) => (this.pendingReload = v));
      await on<ProcMetrics | null>("metrics-tick", (v) => {
        this.metrics = v;
        if (v) {
          this.metricsHistory.push({
            t: Date.now(),
            cpu: v.cpuPercent ?? null,
            rssKb: v.rssKb ?? null,
          });
          if (this.metricsHistory.length > 120) this.metricsHistory.shift();
        }
      });
      this.ready = true;
    },

    async refreshProfiles() {
      this.profiles = await A.listProfiles();
    },
    async refreshEngineInfo() {
      this.engineInfo = await A.getEngineInfo();
      this.engineVersion = this.engineInfo.bundledVersion;
    },

    async start(profileId: string) {
      const pre = await A.checkBeforeStart(profileId);
      if (pre) throw new Error(pre);
      await A.startTunnel(profileId);
    },
    async stop() {
      await A.stopTunnel();
    },
    async reload() {
      await A.reloadTunnel();
    },
    markPendingReload(id: string) {
      void invoke("mark_pending_reload", { profileId: id });
    },
    clearPendingReload() {
      void invoke("clear_pending_reload");
    },
    async saveProfile(profile: ServerProfile) {
      const saved = await A.saveProfile(profile);
      await this.refreshProfiles();
      return saved;
    },
    /// 主页/配置列表的启用开关：只改 enabled 标记，不做整份配置校验
    /// （运行中校验会把自己的 admin 端口报成「被占用」）。
    async setRuleEnabled(profileId: string, name: string, visitor: boolean, enabled: boolean) {
      const saved = await A.setRuleEnabled(profileId, name, visitor, enabled);
      await this.refreshProfiles();
      this.pendingReload = await A.getPendingReload();
      return saved;
    },
    async deleteProfile(id: string) {
      await A.deleteProfile(id);
      await this.refreshProfiles();
    },
    async duplicateProfile(id: string) {
      await A.duplicateProfile(id);
      await this.refreshProfiles();
    },
    async saveSettings(settings: SettingsData) {
      await A.setSettings(settings);
      this.settings = settings;
    },
    clearLogs() {
      this.logs = [];
      return A.clearLogs();
    },
  },
});
