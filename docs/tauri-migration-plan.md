# Frpc-Android-Multi · Tauri 2 重写迁移方案

> 状态：**评审稿 v1**（2026-10-31） · 分支：`tauri-rewrite`（自 `dev` 切出）
> 技术栈定版：Tauri 2 + Rust 核心层 + Vue 3 前端
> 评审通过后按 M0 → M5 顺序推进，每个里程碑有独立验收标准。

---

## 1. 背景与目标

| # | 目标 | 说明 |
|---|---|---|
| G1 | 提升运行速度、降低内存占用 | 去 JVM，主进程换 Rust 原生二进制 |
| G2 | 安装包小 | 去掉捆绑 JRE，改用系统 WebView |
| G3 | 新功能多端一份代码 | 核心逻辑 1 份（Rust）+ UI 1 份（Vue，响应式适配桌面/手机） |

非目标：iOS 不在范围内（沙盒与后台限制决定 frpc 客户端在 iOS 无合规形态）；现有 Kotlin 版本在收敛前保持可用。

## 2. 现状基线

| 指标 | 现状 | 备注 |
|---|---|---|
| 冷启动 | 2767 ms → **1684 ms** | `2b2bc51` 已优化一轮（-39%），JVM 路线已接近瓶颈 |
| 安装包 | 捆绑 JRE（jpackage） | Windows setup 约 80~150 MB，以 CI 产物实测为准 |
| 稳态内存 | JVM 典型 200~400 MB | 以任务管理器实测为准 |
| UI 代码 | **两套**：`desktopApp/ui/` 与 `androidApp/` 各一份 Compose | `shared/` 仅共享逻辑 |

M0 需要把基线实测落档（各平台安装包体积、内存、启动时间），作为迁移后验收对照。

## 3. 技术选型

| 项 | 选择 | 理由 |
|---|---|---|
| 框架 | Tauri 2.x 最新稳定 | 桌面三平台成熟；Android 支持 Kotlin 插件扩展 |
| Rust | stable（workspace，edition 2021+） | 核心层独立成 crate，不依赖 Tauri，可单独测试 |
| 前端 | Vue 3 + Vite + TypeScript + Pinia | 已定版；vue-i18n 延续简中/English 双语 |
| UI 组件 | 自建轻量组件（桌面/移动自适应） | 页面以表单+列表为主；备选 Naive UI（仅限桌面端页面） |
| 关键 crate | `tokio` / `serde` / `toml_edit` / `reqwest` / `sysinfo` / `tracing` | 见第 6 节映射 |

**升级点**：现版导入 TOML 时忽略未建模字段（README 已知限制）。改用 `toml_edit` 做保留注释与未知字段的无损往返，顺带解掉该限制。

## 4. 分支与仓库策略

- 新代码全部位于 **`tauri/` 子目录**，与 Kotlin 代码在同一分支并存，便于逐文件对照移植。
- **冻结规则**：迁移期间 `dev` 上 Kotlin 代码只修致命 bug，避免双头修改。
- **收敛**：M5 验收通过后 `tauri-rewrite` 合回 `main`；Kotlin 版本从主干移除，
  归档为 `archive/kotlin-legacy` 分支（git 历史亦可回溯）。
- CI：新增 Tauri 构建流水线（桌面三平台 + Android APK）；旧流水线 `ci.yml` /
  `release.yml` 已随 Kotlin 代码一并下线。

```
tauri/
  Cargo.toml              # Cargo workspace
  crates/
    frpc-core/            # 核心逻辑（model / toml / validate / admin / supervisor / metrics / logs）
  app/
    src-tauri/            # Tauri 壳：commands、events、插件注册
    src-tauri/android/    # (M4) Android Kotlin 插件：FGS 监管、开机自启、libfrpc.so
    ui/                   # Vue 3 应用（views / stores / i18n / components）
```

## 5. 目标架构与接口设计

分层：`Vue 3（WebView） ⇄ Tauri IPC（invoke/event） ⇄ Rust 核心层 ⇄ frpc 子进程`。

**Commands（前端 → Rust）**

| 分组 | 命令 |
|---|---|
| 档案 | `list_profiles` / `save_profile` / `delete_profile` / `import_toml` / `export_toml` |
| 引擎 | `start_tunnel` / `stop_tunnel` / `reload_tunnel` / `get_engine_state` |
| 状态 | `get_proxy_status` / `get_visitor_status` |
| 日志 | `get_logs` / `set_log_filter` / `export_logs` |
| 设置 | `get_settings` / `set_settings` |

**Events（Rust → 前端）**：`log-line`、`state-changed`（含指数退避重启状态）、`metrics-tick`（CPU/内存/线程/时长）、`status-refreshed`。

**Android 分叉原则**：Rust 核心层设计为能力子集——Android 上 `supervisor` 命令转发给 Kotlin FGS 插件执行，`model/toml/validate/admin/logs` 照常走 Rust；前端以能力检测适配，UI 代码始终只有一套。

## 6. 模块映射与移植清单

| Kotlin 现状 | Rust 目标 | 对拍测试（必须同步移植） |
|---|---|---|
| `shared: FrpcConfig / ProxyConfig / FrpcNaming` | `frpc-core::model` | `FrpcNamingTest` |
| `shared: FrpcToml` | `frpc-core::toml`（toml_edit） | `FrpcTomlTest` / `FrpcTomlAdvancedTest` / `FrpcTomlResourceTest` |
| `shared: ConfigValidator / PortRange` | `frpc-core::validate` | `ConfigValidatorTest` / `ProxyTypeFieldTest` / `PortRangeTest` |
| `shared: AdminModels / FrpcAdminClient` | `frpc-core::admin`（reqwest） | `FrpcAdminClientTest` |
| `desktop: EngineManager / FrpcSupervisor` | `frpc-core::supervisor`（tokio 子进程 + 指数退避） | 集成测试：真跑 frpc 验证启停/重启 |
| `desktop: ProcessMetrics`（/proc + 平台分支） | `frpc-core::metrics`（sysinfo，一套通吃） | `ProcessStatsTest` 对拍口径 |
| `desktop: LogParser`、`shared: FrpcErrorHints` | `frpc-core::logs`（错误码返回前端） | `FrpcErrorHintsTest` → 错误码断言 |
| `desktop: ProfileStore / SettingsStore` | `tauri-plugin-store`（JSON，数据量小、可读） | 读写往返测试 |
| `desktop: SingleInstance / AppTray / TrayMenu*` | `tauri-plugin-single-instance` + Tauri 托盘 API + 透明置顶小窗 | 手动冒烟清单 |
| UI 7 页面（Home / Monitor / Log / Profiles / ProfileEdit / Settings / 托盘菜单） | Vue `views/` 一套（响应式） | 组件冒烟 + 手动清单 |
| `androidApp: FrpcSupervisor / FrpcBinaryManager / 前台服务` | (M4) Kotlin 插件，逻辑原样搬迁 | 真机用例 |
| 双语文案（Kotlin 内嵌） | 抽取为 `ui/src/i18n/{zh-CN,en}.json` | — |

## 7. 里程碑

| 阶段 | 内容 | 工期 | 验收标准 |
|---|---|---|---|
| **M0** 脚手架 | workspace + Tauri/Vue 骨架 + CI 流水线 + 实测基线落档 | 0.5 周 | CI 三平台编译通过；基线表入库 |
| **M1** 核心层 | `frpc-core` 全模块移植 | 1~2 周 | 第 6 节测试用例 100% 移植且全绿 |
| **M2** 桌面 UI | 7 页面 + 托盘/单实例/自启/双语 | 2~3 周 | 桌面端可日常自用（功能 parity 主观核验） |
| **M3** 打包分发 | 三平台产物 + 自动更新链路 | 0.5 周 | 达成第 8 节量化指标 |
| **M4** Android | Kotlin FGS 插件 + libfrpc.so + 移动 UI 适配 | 1~2 周 | 真机：前后台切换/杀进程隧道不断/开机自启/通知合规 |
| **M5** e2e 收敛 | 八协议 e2e + parity 清单 + 合回 main | 0.5~1 周 | e2e 全绿；README 特性清单逐条核验 |

总计 **5.5~9 周**（全职口径；业余节奏按实际投入折算）。

## 8. 验收标准（量化）

1. 安装包 ≤ **15 MB**/平台（NSIS / DMG / deb）
2. 冷启动 ≤ **1.0 s**（对照基线 1684 ms）
3. 稳态内存 ≤ **150 MB**（主页 + 监控页常开）
4. 八协议端到端测试全绿（复用 `scripts/e2e/e2e-protocols.sh` 思路：Rust 编码器导出 toml → `frpc verify` → 真实建隧道）
5. 功能 parity：README 特性清单逐条核验通过
6. Android 8.0+：FGS 通知合规、杀进程隧道不断、开机自启可用

## 9. 风险清单与对策

| # | 风险 | 等级 | 对策 |
|---|---|---|---|
| R1 | UI 重写工作量超预期（占总量 ~60%） | 高 | 里程碑制推进；M2 结束即恢复日常可用，可提前止损 |
| R2 | Android WebView 版本碎片（国内老内核） | 中 | UI 避免新 CSS 特性；支持矩阵写入 README；真机冒烟清单 |
| R3 | Android W^X 限制 / FGS 生命周期 | 中 | 复用现有 `libfrpc.so`（nativeLibraryDir）方案；FGS 留 Kotlin，不硬塞 Rust |
| R4 | TOML 无损往返行为与旧版"忽略未知字段"不一致 | 中 | 作为升级点处理：`toml_edit` 保留未知字段与注释；对拍测试覆盖 |
| R5 | Linux 老发行版 webkit2gtk 兼容 | 低 | 声明支持矩阵（Ubuntu 22.04+ / Debian 12+）；deb 依赖由包管理解决 |
| R6 | macOS 未签名/未公证（现状遗留） | 低 | 本期维持现状；后续接 Tauri 签名/公证链路 |
| R7 | 双版本并存期间的双改冲突 | 中 | 第 4 节冻结规则；`tauri/` 目录外改动需评审 |

## 10. dev / prod 环境说明

- **dev**：`tauri dev` 热更新；`tracing` debug 日志；允许未签名调试构建；frpc 引擎直接用 `.build/` 缓存产物。
- **prod**：Windows 可选代码签名；macOS 暂维持"未签名 + 右键打开"（同现状）；更新走 `tauri-plugin-updater` + 更新 JSON endpoint；日志按大小轮转。
- **引擎分发**：延续内置策略（`scripts/build-frpc.sh` 产物随包），不改为首次下载，保证离线可用。

## 11. 决策记录

| 决策 | 结论 | 备选（已排除原因） |
|---|---|---|
| 前端框架 | **Vue 3** | React（效果相当，按偏好定版） |
| 代码位置 | **git 分支 `tauri-rewrite` + `tauri/` 子目录** | 独立仓库（对照迁移不便） |
| 整体方案 | Tauri 2 重写 | 维持 Compose Multiplatform（G1/G2 无法达成）；Electron（体积/内存更差）；Flutter+Rust（双语言栈，Android 后台同样受限） |
| iOS | 不做 | 沙盒 + 后台限制，无合规形态 |

---

*本文档位于 `tauri-rewrite` 分支。评审意见直接在本文件上批注或提交 issue；通过后从 M0 开工。*
