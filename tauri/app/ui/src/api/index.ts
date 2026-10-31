// 类型化 IPC 层：与 frpc-core 的 serde 输出（camelCase）一一对应
import { invoke } from "@tauri-apps/api/core";

export interface FrpcConfig {
  serverAddr: string;
  serverPort: number;
  clientID?: string | null;
  user?: string | null;
  loginFailExit: boolean;
  natHoleStunServer?: string | null;
  log?: LogConfig | null;
  auth?: AuthConfig | null;
  webServer?: WebServerConfig | null;
  transport?: TransportConfig | null;
  udpPacketSize?: number | null;
  metadatas?: Record<string, string> | null;
  proxies: ProxyConfig[];
  visitors: VisitorConfig[];
}

export interface LogConfig {
  to: string;
  level: string;
  maxDays: number;
  disablePrintColor?: boolean | null;
}
export interface AuthConfig {
  method: string;
  token: string;
}
export interface WebServerConfig {
  addr: string;
  port: number;
  user: string;
  password: string;
  pprofEnable: boolean;
}
export interface TransportConfig {
  protocol: string;
  tcpMux: boolean;
  poolCount: number;
  tls?: TlsConfig | null;
  dialServerTimeout: number;
  dialServerKeepalive: number;
  connectServerLocalIP?: string | null;
  proxyURL?: string | null;
  heartbeatInterval?: number | null;
  heartbeatTimeout?: number | null;
  tcpMuxKeepaliveInterval?: number | null;
}
export interface TlsConfig {
  enable: boolean;
  serverName?: string | null;
  certFile?: string | null;
  keyFile?: string | null;
  trustedCaFile?: string | null;
  disableCustomTLSFirstByte?: boolean | null;
}
export interface ProxyConfig {
  name: string;
  type: string;
  enabled?: boolean | null;
  localIP?: string | null;
  localPort?: number | null;
  remotePort?: number | null;
  subdomain?: string | null;
  customDomains?: string[] | null;
  locations?: string[] | null;
  httpUser?: string | null;
  httpPassword?: string | null;
  hostHeaderRewrite?: string | null;
  routeByHTTPUser?: string | null;
  multiplexer?: string | null;
  secretKey?: string | null;
  allowUsers?: string[] | null;
  transport?: ProxyTransportConfig | null;
  plugin?: ProxyPluginConfig | null;
}
export interface ProxyTransportConfig {
  bandwidthLimit?: string | null;
  bandwidthLimitMode?: string | null;
  useEncryption?: boolean | null;
  useCompression?: boolean | null;
  proxyProtocolVersion?: string | null;
}
export interface ProxyPluginConfig {
  type: string;
  localAddr?: string | null;
  crtPath?: string | null;
  keyPath?: string | null;
  hostHeaderRewrite?: string | null;
}
export interface VisitorConfig {
  name: string;
  type: string;
  serverName: string;
  secretKey?: string | null;
  bindAddr: string;
  bindPort: number;
  serverUser?: string | null;
  enabled?: boolean | null;
  fallbackTo?: string | null;
  fallbackTimeoutMs?: number | null;
  keepTunnelOpen?: boolean | null;
}

export interface ServerProfile {
  id: string;
  name: string;
  config: FrpcConfig;
  createdAt: number;
  updatedAt: number;
  lastUsedAt: number;
}
export interface ImportSummary {
  added: number;
  updated: number;
  skipped: string[];
}
export interface SettingsData {
  theme: string;
  enginePath: string;
  showAccessLogs: boolean;
  defaultProfileId?: string | null;
  autoStartOnLaunch: boolean;
  closeToTray: boolean;
}

export type Phase = "IDLE" | "STARTING" | "RUNNING" | "STOPPING" | "ERROR";
export interface RunState {
  phase: Phase;
  profileId?: string | null;
  profileName: string;
  pid?: number | null;
  startedAtMs?: number | null;
  error?: string | null;
  adminEnabled: boolean;
  adminPort: number;
  restartCount: number;
}
export interface LogLine {
  timeMs: number;
  level: "TRACE" | "DEBUG" | "INFO" | "WARN" | "ERROR";
  tag: string;
  message: string;
}
export interface ProcMetrics {
  pid: number;
  cpuPercent?: number | null;
  rssKb?: number | null;
  threads?: number | null;
  state?: string | null;
}
export interface ProxyStatusItem {
  name: string;
  type: string;
  status: string;
  err: string;
  localAddr?: string;
  plugin?: string;
  remoteAddr?: string;
}
export interface ProxyStatusSnapshot {
  byType: Record<string, ProxyStatusItem[]>;
  fetchedAtEpochMs: number;
}
export interface ActiveRuleCounts {
  proxies: number;
  visitors: number;
}
export interface ValidationIssue {
  severity: "ERROR" | "WARNING";
  scope: string;
  field: string;
  reason: string;
  entityName?: string | null;
  detail?: string | null;
}
export interface ErrorHint {
  code: string;
  name?: string | null;
}
export interface EngineInfo {
  path: string;
  exists: boolean;
  usingCustom: boolean;
  bundledVersion: string;
}
export interface ListeningPort {
  protocol: string;
  address: string;
  port: number;
  pid?: number | null;
}

export const emptyConfig = (): FrpcConfig => ({
  serverAddr: "",
  serverPort: 7000,
  loginFailExit: false,
  webServer: { addr: "127.0.0.1", port: 7400, user: "admin", password: "admin", pprofEnable: false },
  auth: { method: "token", token: "" },
  proxies: [],
  visitors: [],
});

// ---------- invoke 封装 ----------

export const api = {
  listProfiles: () => invoke<ServerProfile[]>("list_profiles"),
  getProfile: (id: string) => invoke<ServerProfile>("get_profile", { id }),
  saveProfile: (profile: ServerProfile) =>
    invoke<ServerProfile>("save_profile", { profile }),
  deleteProfile: (id: string) => invoke<void>("delete_profile", { id }),
  duplicateProfile: (id: string) => invoke<ServerProfile>("duplicate_profile", { id }),
  exportProfileToml: (id: string) => invoke<string>("export_profile_toml", { id }),
  importToml: (name: string, text: string) =>
    invoke<ServerProfile>("import_toml", { name, text }),
  exportBundle: () => invoke<string>("export_bundle"),
  importBundle: (text: string, replace: boolean) =>
    invoke<ImportSummary>("import_bundle", { text, replace }),
  writeTextFile: (path: string, content: string) =>
    invoke<string>("write_text_file", { path, content }),
  validateConfig: (config: FrpcConfig) =>
    invoke<ValidationIssue[]>("validate_config_cmd", { config }),
  listListeningPorts: () => invoke<ListeningPort[]>("list_listening_ports"),
  getEngineInfo: () => invoke<EngineInfo>("get_engine_info"),
  startTunnel: (id: string) => invoke<void>("start_tunnel", { id }),
  stopTunnel: () => invoke<void>("stop_tunnel"),
  reloadTunnel: () => invoke<void>("reload_tunnel"),
  getEngineState: () => invoke<RunState>("get_engine_state"),
  checkBeforeStart: (id: string) => invoke<string | null>("check_before_start", { id }),
  getProxyStates: () =>
    invoke<Record<string, { phase: string; error?: string | null }>>("get_proxy_states"),
  getVisitors: () => invoke<string[]>("get_visitors"),
  getStatusSnapshot: () => invoke<ProxyStatusSnapshot | null>("get_status_snapshot"),
  getActiveCounts: () => invoke<ActiveRuleCounts>("get_active_counts"),
  getServerConnected: () => invoke<boolean | null>("get_server_connected"),
  getLoginError: () => invoke<string | null>("get_login_error"),
  getAdminReachable: () => invoke<boolean>("get_admin_reachable"),
  getMetrics: () => invoke<ProcMetrics | null>("get_metrics"),
  getPendingReload: () => invoke<string | null>("get_pending_reload"),
  getLogs: () => invoke<LogLine[]>("get_logs"),
  clearLogs: () => invoke<void>("clear_logs"),
  exportLogs: () => invoke<string>("export_logs"),
  getLogsText: () => invoke<string>("get_logs_text"),
  getSettings: () => invoke<SettingsData>("get_settings"),
  setSettings: (settings: SettingsData) => invoke<void>("set_settings", { settings }),
  getDataDir: () => invoke<string>("get_data_dir"),
  openUrl: (url: string) => invoke<void>("open_url", { url }),
  openPath: (path: string) => invoke<void>("open_path", { path }),
};
