// 展示格式化与校验文案 —— 与旧版 Format.kt / ProfileEditScreen.kt 逐字对齐

export function formatLogTime(epochMs: number): string {
  const d = new Date(epochMs);
  const today = new Date();
  const sameDay =
    d.getFullYear() === today.getFullYear() &&
    d.getMonth() === today.getMonth() &&
    d.getDate() === today.getDate();
  const p = (n: number, w = 2) => String(n).padStart(w, "0");
  const time = `${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}.${p(d.getMilliseconds(), 3)}`;
  return sameDay ? time : `${p(d.getMonth() + 1)}-${p(d.getDate())} ${time}`;
}

export function formatDateTime(epochMs: number): string {
  const d = new Date(epochMs);
  const p = (n: number, w = 2) => String(n).padStart(w, "0");
  return `${d.getFullYear()}-${p(d.getMonth() + 1)}-${p(d.getDate())} ${p(d.getHours())}:${p(d.getMinutes())}:${p(d.getSeconds())}`;
}

export function formatDuration(ms: number): string {
  if (ms <= 0) return "--";
  const total = Math.floor(ms / 1000);
  const days = Math.floor(total / 86400);
  const h = Math.floor((total % 86400) / 3600);
  const m = Math.floor((total % 3600) / 60);
  const s = total % 60;
  const hms = `${String(h).padStart(2, "0")}:${String(m).padStart(2, "0")}:${String(s).padStart(2, "0")}`;
  return days > 0 ? `${days}d ${hms}` : hms;
}

export function formatMemoryKb(kb?: number | null): string {
  if (kb == null || kb <= 0) return "--";
  return kb < 1024 ? `${kb} KB` : `${(kb / 1024).toFixed(1)} MB`;
}

export function formatPercent(v?: number | null): string {
  return v == null ? "--" : `${v.toFixed(1)}%`;
}

// ---------- 命名（frpc-core model::naming） ----------

export function targetServerProxyName(
  localUser?: string | null,
  serverUser?: string | null,
  serverName?: string,
): string {
  const prefix = serverUser?.trim() || localUser?.trim() || "";
  const name = serverName ?? "";
  return prefix ? `${prefix}.${name}` : name;
}

// ---------- 端口批量（frpc-core validate::PortRange） ----------

export function parsePortExpr(expr: string, maxExpand = 100): { ok: true; ports: number[] } | { ok: false; reason: string } {
  const tokens = expr
    .split(/[,， \n\t]/)
    .map((s) => s.trim())
    .filter(Boolean);
  if (!tokens.length) return { ok: false, reason: "端口为空" };
  const ports: number[] = [];
  for (const token of tokens) {
    const dash = token.indexOf("-");
    if (dash < 0) {
      const port = Number(token);
      if (!Number.isInteger(port)) return { ok: false, reason: `「${token}」不是数字` };
      if (port < 1 || port > 65535) return { ok: false, reason: `「${token}」超出 1-65535` };
      if (!ports.includes(port)) ports.push(port);
    } else {
      const start = Number(token.slice(0, dash).trim());
      const end = Number(token.slice(dash + 1).trim());
      if (!Number.isInteger(start) || !Number.isInteger(end))
        return { ok: false, reason: `「${token}」不是有效的范围` };
      if (start < 1 || end < 1 || start > 65535 || end > 65535)
        return { ok: false, reason: `「${token}」超出 1-65535` };
      if (start > end) return { ok: false, reason: `「${token}」起点大于终点` };
      if (end - start + 1 > maxExpand - ports.length)
        return { ok: false, reason: `「${token}」一次最多展开 ${maxExpand} 个端口` };
      for (let p = start; p <= end; p++) if (!ports.includes(p)) ports.push(p);
    }
  }
  return { ok: true, ports };
}

export function proxyNameFor(base: string, port: number): string {
  const trimmed = base.trim() || "proxy";
  return `${trimmed}-${port}`;
}

// ---------- 代理 / 访问者摘要（ProfileEditScreen describe*） ----------

export function describeProxy(p: {
  type: string;
  localIP?: string | null;
  localPort?: number | null;
  remotePort?: number | null;
  customDomains?: string[] | null;
  subdomain?: string | null;
  enabled?: boolean | null;
}): string {
  const caps = typeCaps(p.type);
  let s = p.type.toUpperCase();
  s += ` · 本地 ${p.localIP || "127.0.0.1"}:${p.localPort ?? "?"}`;
  if (caps.remotePort) {
    s += p.remotePort == null || p.remotePort === 0 ? " → 远程端口随机" : ` → 远程 :${p.remotePort}`;
  } else if (caps.domain) {
    s += ` → ${p.customDomains?.[0] || p.subdomain || "域名待配置"}`;
  } else {
    s += " → 私密代理（由访问者接入）";
  }
  if (p.enabled === false) s += " · 已禁用";
  return s;
}

export function describeVisitor(
  v: {
    type: string;
    bindPort: number;
    bindAddr: string;
    serverUser?: string | null;
    serverName: string;
    enabled?: boolean | null;
  },
  localUser?: string | null,
): string {
  let s = v.type.toUpperCase();
  s += v.bindPort < 0 ? " · 不监听端口" : ` · 本地 ${v.bindAddr}:${v.bindPort}`;
  s += ` · 访问 ${targetServerProxyName(localUser, v.serverUser, v.serverName)}`;
  if (v.enabled === false) s += " · 已禁用";
  return s;
}

// 与 frpc-core::model ProxyType 能力位一致
export function typeCaps(type: string) {
  return {
    remotePort: type === "tcp" || type === "udp",
    secretKey: ["stcp", "sudp", "xtcp"].includes(type),
    domain: ["http", "https", "tcpmux"].includes(type),
    httpAdv: type === "http",
    httpAuth: type === "http" || type === "tcpmux",
    mux: type === "tcpmux",
    httpsPlugin: type === "https",
  };
}

export const PROXY_TYPES = ["tcp", "udp", "http", "https", "stcp", "sudp", "xtcp", "tcpmux"];
export const VISITOR_TYPES = ["stcp", "sudp", "xtcp"];

// ---------- 校验文案（ProfileEditScreen validationText 逐字对齐） ----------

const FIELD_NAMES: Record<string, string> = {
  serverAddr: "服务器地址",
  serverPort: "服务器端口",
  user: "代理名前缀",
  clientID: "客户端标识",
  token: "认证 Token",
  method: "认证方式",
  addr: "监听地址",
  port: "端口",
  protocol: "连接协议",
  poolCount: "预连接数",
  bandwidthLimit: "带宽限制",
  proxyProtocolVersion: "PROXY Protocol",
  name: "名称",
  type: "类型",
  localIP: "本地地址",
  localPort: "本地端口",
  remotePort: "远程端口",
  subdomain: "子域名",
  customDomains: "自定义域名",
  locations: "路由路径",
  httpUser: "HTTP 账号",
  httpPassword: "HTTP 密码",
  secretKey: "私密密钥",
  multiplexer: "多路复用器",
  serverName: "对方代理名",
  serverUser: "对方设备前缀",
  bindAddr: "监听地址",
  bindPort: "本地监听端口",
  level: "日志级别",
  natHoleStunServer: "STUN 服务器",
  fallbackTo: "打洞失败转交给",
  fallbackTimeoutMs: "打洞等待时长",
  certFile: "客户端证书",
  keyFile: "客户端私钥",
  trustedCaFile: "CA 证书",
  heartbeatInterval: "心跳间隔",
  heartbeatTimeout: "心跳超时",
  tcpMuxKeepaliveInterval: "多路复用保活间隔",
  plugin: "插件",
  localAddr: "插件本地地址",
  crtPath: "证书文件",
  keyPath: "密钥文件",
  maxDays: "日志保留天数",
};

function fieldDisplayName(field: string): string {
  const key = field.split(".").pop()!.split("]")[0];
  return FIELD_NAMES[key] ?? field;
}

const SCOPE_PROXY = "PROXY";
const SCOPE_VISITOR = "VISITOR";

export function validationText(issue: {
  scope: string;
  field: string;
  reason: string;
  entityName?: string | null;
  detail?: string | null;
}): string {
  const where =
    issue.scope === "GLOBAL"
      ? "全局"
      : issue.scope === "AUTH"
        ? "认证"
        : issue.scope === "WEB_SERVER"
          ? "状态监控"
          : issue.scope === "TRANSPORT"
            ? "传输"
            : issue.scope === "LOG"
              ? "日志"
              : issue.scope === SCOPE_PROXY
                ? `代理「${issue.entityName ?? ""}」`
                : issue.scope === SCOPE_VISITOR
                  ? `访问者「${issue.entityName ?? ""}」`
                  : issue.scope;
  const label = fieldDisplayName(issue.field);
  let what: string;
  switch (issue.reason) {
    case "EMPTY_REQUIRED_FIELD": what = `${label} 不能为空`; break;
    case "PORT_OUT_OF_RANGE": what = `${label} 需在 1-65535`; break;
    case "PORT_IN_USE": what = `${label} 已被占用，换一个端口`; break;
    case "INVALID_VALUE": what = `${label} 取值不合法`; break;
    case "DUPLICATE_NAME": what = `代理名「${issue.entityName ?? ""}」重复，frps 上必须唯一`; break;
    case "TYPE_FIELD_MISSING": what = "该类型缺少必填字段"; break;
    case "DOMAIN_OR_SUBDOMAIN_REQUIRED": what = "需要自定义域名或子域名（frp 要求至少填一个）"; break;
    case "UNSUPPORTED_VALUE": what = `${label} 暂不支持，请导出 frpc.toml 手动配置`; break;
    case "FIELD_NOT_SUPPORTED_FOR_TYPE": what = `${label} 不适用于该类型（frp 严格解码，写进去会启动失败）`; break;
    case "BIND_PORT_REQUIRED": what = "本地监听端口不能为 0（frp 会报 bind port is required）；不监听请填 -1"; break;
    case "SUBDOMAIN_INVALID": what = "子域名不能包含 . 或 *（通配请用自定义域名）"; break;
    case "MULTIPLEXER_INVALID": what = "多路复用器只能是 httpconnect"; break;
    case "BANDWIDTH_LIMIT_INVALID": what = "带宽限制必须带 KB 或 MB 单位，例如 1MB"; break;
    case "FALLBACK_VISITOR_NOT_FOUND": what = "打洞失败转交的访问者不存在，打洞失败时没有退路"; break;
    case "SERVER_NAME_PREFIX_DOUBLED": what = `${label} 只填代理名。前缀取「对方设备前缀」，留空则用本机前缀`; break;
    case "XTCP_STUN_SERVER_NOT_SET":
      what = "用了 xtcp 却没填 STUN 服务器：frpc 会退回默认 stun.easyvoip.com:3478，该地址经常超时，会导致打洞失败（访问者侧报 context deadline exceeded）。建议填一个能连通的，例如 stun.miwifi.com:3478";
      break;
    default: what = issue.reason;
  }
  return `${where}：${what}${issue.detail ? `（${issue.detail}）` : ""}`;
}

// ---------- 快照辅助（Rust 侧 all()/item() 是方法，JSON 无此字段） ----------

export function snapshotAll(snap: { byType: Record<string, { name: string; type: string; status: string; err: string; remoteAddr?: string; localAddr?: string }[]> } | null): { name: string; type: string; status: string; err: string; remoteAddr?: string; localAddr?: string }[] {
  if (!snap) return [];
  return Object.values(snap.byType).flat();
}

export function snapshotItem(snap: { byType: Record<string, { name: string; type: string; status: string; err: string; remoteAddr?: string; localAddr?: string }[]> } | null, name: string) {
  return snapshotAll(snap).find((i) => i.name === name);
}
