// 展示格式化与校验文案 —— 与旧版 Format.kt / ProfileEditScreen.kt 逐字对齐
import { i18n } from "./i18n";

/// vue-i18n 的类型化 key 在动态拼接（validate.reason.XXX）时无法静态校验，
/// 这里收窄成 string 签名的薄包装。调用点都在渲染过程中，locale 变化会重建依赖。
const tr = (key: string, params?: Record<string, unknown>): string =>
  (i18n.global.t as unknown as (k: string, p?: Record<string, unknown>) => string)(key, params);

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
  if (!tokens.length) return { ok: false, reason: tr("port.empty") };
  const ports: number[] = [];
  for (const token of tokens) {
    const dash = token.indexOf("-");
    if (dash < 0) {
      const port = Number(token);
      if (!Number.isInteger(port)) return { ok: false, reason: tr("port.notNumber", { token }) };
      if (port < 1 || port > 65535) return { ok: false, reason: tr("port.outOfRange", { token }) };
      if (!ports.includes(port)) ports.push(port);
    } else {
      const start = Number(token.slice(0, dash).trim());
      const end = Number(token.slice(dash + 1).trim());
      if (!Number.isInteger(start) || !Number.isInteger(end))
        return { ok: false, reason: tr("port.badRange", { token }) };
      if (start < 1 || end < 1 || start > 65535 || end > 65535)
        return { ok: false, reason: tr("port.outOfRange", { token }) };
      if (start > end) return { ok: false, reason: tr("port.startGtEnd", { token }) };
      if (end - start + 1 > maxExpand - ports.length)
        return { ok: false, reason: tr("port.tooMany", { token, max: maxExpand }) };
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
  s += tr("desc.local", { addr: `${p.localIP || "127.0.0.1"}:${p.localPort ?? "?"}` });
  if (caps.remotePort) {
    s +=
      p.remotePort == null || p.remotePort === 0
        ? tr("desc.remoteRandom")
        : tr("desc.remote", { port: p.remotePort });
  } else if (caps.domain) {
    s += ` → ${p.customDomains?.[0] || p.subdomain || tr("desc.domainPending")}`;
  } else {
    s += tr("desc.private");
  }
  if (p.enabled === false) s += tr("desc.disabled");
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
  s += v.bindPort < 0 ? tr("desc.noBindPort") : tr("desc.bind", { addr: `${v.bindAddr}:${v.bindPort}` });
  s += tr("desc.access", { name: targetServerProxyName(localUser, v.serverUser, v.serverName) });
  if (v.enabled === false) s += tr("desc.disabled");
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
// 字段显示名与各 reason 的文案都在语言包 validate.field.* / validate.reason.* 里

function fieldDisplayName(field: string): string {
  const key = field.split(".").pop()!.split("]")[0];
  const path = `validate.field.${key}`;
  return i18n.global.te(path) ? tr(path) : field;
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
      ? tr("validate.scopeGlobal")
      : issue.scope === "AUTH"
        ? tr("validate.scopeAuth")
        : issue.scope === "WEB_SERVER"
          ? tr("validate.scopeWebServer")
          : issue.scope === "TRANSPORT"
            ? tr("validate.scopeTransport")
            : issue.scope === "LOG"
              ? tr("validate.scopeLog")
              : issue.scope === SCOPE_PROXY
                ? tr("validate.scopeProxy", { name: issue.entityName ?? "" })
                : issue.scope === SCOPE_VISITOR
                  ? tr("validate.scopeVisitor", { name: issue.entityName ?? "" })
                  : issue.scope;
  const label = fieldDisplayName(issue.field);
  // 语言包没收录的 reason（后端新增时）直接显示原始 code，比显示 key 路径可读
  const reasonKey = `validate.reason.${issue.reason}`;
  const what = i18n.global.te(reasonKey)
    ? tr(reasonKey, { label, name: issue.entityName ?? "" })
    : issue.reason;
  return (
    tr("validate.text", { where, what }) +
    (issue.detail ? tr("validate.detail", { detail: issue.detail }) : "")
  );
}

// ---------- 错误文案 ----------

/// 把 IPC 抛出来的东西变成一句人能看懂的话。
///
/// 容易踩的坑：`save_profile` 的 Err 是 `Vec<ValidationIssue>`，到前端是一个**对象
/// 数组**，`String(err)` 只会得到 `[object Object]` —— 主页关代理时那句看不懂的
/// 提示就是这么来的。这里把数组逐条翻译成校验文案，只有最后一个兜底才 JSON 化。
export function errorText(e: unknown): string {
  if (Array.isArray(e)) {
    const items = e
      .map((it) =>
        typeof it === "string"
          ? it
          : it && typeof it === "object" && "reason" in (it as Record<string, unknown>)
            ? validationText(
                it as {
                  scope: string;
                  field: string;
                  reason: string;
                  entityName?: string | null;
                  detail?: string | null;
                },
              )
            : String(it),
      )
      .filter(Boolean);
    return items.join("；") || tr("err.unknown");
  }
  if (typeof e === "string") return e.replace(/^Error:\s*/, "");
  if (e instanceof Error) return e.message;
  if (e && typeof e === "object") {
    const o = e as { message?: unknown; reason?: unknown };
    if (typeof o.message === "string" && o.message) return o.message;
    if (typeof o.reason === "string" && o.reason) return o.reason;
    try {
      return JSON.stringify(e);
    } catch {
      return tr("err.unknown");
    }
  }
  return e == null ? tr("err.unknown") : String(e);
}

// ---------- 快照辅助（Rust 侧 all()/item() 是方法，JSON 无此字段） ----------

export function snapshotAll(snap: { byType: Record<string, { name: string; type: string; status: string; err: string; remoteAddr?: string; localAddr?: string }[]> } | null): { name: string; type: string; status: string; err: string; remoteAddr?: string; localAddr?: string }[] {
  if (!snap) return [];
  return Object.values(snap.byType).flat();
}

export function snapshotItem(snap: { byType: Record<string, { name: string; type: string; status: string; err: string; remoteAddr?: string; localAddr?: string }[]> } | null, name: string) {
  return snapshotAll(snap).find((i) => i.name === name);
}
