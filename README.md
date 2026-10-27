<div align="center">
  <img src="docs/assets/icon.svg" width="96" height="96" alt="frpc-desktop-multi" />
  <h1>frpc-desktop-multi</h1>
  <p><b>frp 内网穿透的可视化客户端</b> · Windows / Linux / macOS / Android<br>配置、启停、日志、监控，全在一个界面里</p>

  <p>
    <img src="https://img.shields.io/badge/license-MIT-blue.svg" alt="License" />
    <img src="https://img.shields.io/badge/frpc-v0.71.1-orange" alt="frp" />
    <img src="https://img.shields.io/badge/Kotlin-2.4.21-7F52FF?logo=kotlin&logoColor=white" alt="Kotlin" />
    <img src="https://img.shields.io/badge/Compose-Desktop%20%26%20Android-4285F4?logo=jetpackcompose&logoColor=white" alt="Compose" />
  </p>

  <p>
    <a href="https://github.com/fdmdf/frpc-desktop-multi/releases/latest"><img src="https://img.shields.io/github/v/release/fdmdf/frpc-desktop-multi?label=%E4%B8%8B%E8%BD%BD" alt="Release" /></a>
    <a href="https://github.com/fdmdf/frpc-desktop-multi/actions/workflows/ci.yml"><img src="https://github.com/fdmdf/frpc-desktop-multi/actions/workflows/ci.yml/badge.svg" alt="CI" /></a>
  </p>
</div>

---

## 特性

- **可视化配置**：8 种代理类型 + 3 种访问者，按类型生成表单，保存前自动校验（必填、端口占用、重名、类型不支持的字段）
- **一键启停**：启动 / 停止 / 热重载；进程异常退出按指数退避自动重启
- **真实状态**：优先读 frpc 的 admin API（含真实远程端口），接口关闭时用日志推断；访问者状态同样可见
- **资源与日志**：CPU / 内存 / 线程 / 运行时长实时采样；日志分级过滤、搜索、导出
- **开箱即用**：内置 frpc 引擎，各平台原生编译，用户不需要装 Java、Go 或手动下载二进制
- **双语界面**：简体中文 / English，应用内切换，也可跟随系统
- **两端一致**：桌面端与 Android 共用同一个 Kotlin Multiplatform 核心模块，配置模型、`frpc.toml` 编解码、校验规则只有一份实现

## 界面

<table>
  <tr>
    <td><img src="docs/screenshots/desktop-home.svg" width="820" /><br><sub>桌面端 · 主页：运行状态、概览、代理规则与访问者</sub></td>
  </tr>
  <tr>
    <td><img src="docs/screenshots/desktop-monitor.svg" width="820" /><br><sub>桌面端 · 监控：进程、CPU / 内存曲线、代理与访问者状态</sub></td>
  </tr>
  <tr>
    <td align="center">
      <img src="docs/screenshots/android-home.svg" width="260" />
      <img src="docs/screenshots/android-monitor.svg" width="260" />
      <br><sub>Android · 主页 / 监控</sub>
    </td>
  </tr>
</table>

> 图为按实际界面布局绘制的示意图（同一套配色与信息层级），非截图。

## 下载

| 平台 | 文件 | 说明 |
|---|---|---|
| Windows x64 | `frpc-desktop-multi-<ver>-windows-x64-setup.exe` | 双击安装，自带 JRE，含开始菜单快捷方式 |
| Windows x64 | `frpc-desktop-multi-<ver>-windows-x64.zip` | 便携版，解压即用 |
| Linux x64 | `frpc-desktop-multi-<ver>-linux-x64.tar.gz` / `.deb` | 解压运行 `bin/frpc-desktop-multi`，或 `dpkg -i` |
| macOS arm64 | `frpc-desktop-multi-<ver>-macos-arm64.dmg` | Apple Silicon；未签名，首次需右键 → 打开 |
| macOS x64 | `frpc-desktop-multi-<ver>-macos-x64.dmg` | Intel；未签名，首次需右键 → 打开 |
| Android arm64 | `frpc-desktop-multi-<ver>-android-arm64.apk` | Android 8.0+，允许未知来源安装 |

macOS 的 `.tar.gz` 是同一个 `.app` 的压缩包（不便挂载 DMG 时用）。Android APK 由仓库签名密钥签名，签名未生效时工作流会直接终止，不会发出装不上的产物。

## 快速开始

1. 「配置」→ 新建：填 frps 地址、端口、Token，保持「状态接口（webServer）」开启
2. 添加代理规则，例如 TCP：本地 `22` → 远程 `6001`（留空则由 frps 随机分配）
3. 回「主页」点「启动」，日志出现 `login to server success` 即成功

数据目录：桌面端 `~/.frpcdash/`（配置、引擎、运行中的 toml）；Android 为应用私有目录。

## 协议支持

| 代理类型 | 服务端要求 | 说明 |
|---|---|---|
| tcp / udp | `allowPorts` 放行 remotePort | 固定端口转发 |
| http / https | `vhostHTTPPort` / `vhostHTTPSPort` | 按域名路由；https 为 TLS 透传，本地服务需自己说 HTTPS |
| tcpmux | `tcpmuxHTTPConnectPort` | `multiplexer = httpconnect`，走 HTTP CONNECT |
| stcp / sudp / xtcp | 无 | 私密代理 + 访问者；xtcp 需要能连上 STUN（可自建并填 `natHoleStunServer`） |

传输层支持 `tcp` / `kcp` / `quic` / `websocket` / `wss` 与 TLS 开关。

## 从源码构建

需要 **JDK 17**、**Go 1.21+**（编译 frpc 引擎），打包 APK 另需 Android SDK。

```bash
bash scripts/build-frpc.sh            # 编译引擎：桌面端 + Android（源码缓存在 .build/）
./gradlew :desktopApp:run             # 跑桌面端
./gradlew :androidApp:assembleDebug   # 打调试 APK
./gradlew :shared:jvmTest :androidApp:testDebugUnitTest   # 单元测试
bash scripts/e2e/e2e-protocols.sh     # 八协议端到端测试（真跑 frps + frpc）
```

端到端测试会用应用自己的编码器把档案导出成 `frpc.toml`，经 `frpc verify` 校验后真实建隧道，逐个协议验证 tcp / udp / http / https / stcp / xtcp / tcpmux / sudp 的连通性。

## 项目结构

```
shared/       Kotlin Multiplatform：配置模型、TOML 编解码、校验、admin API 客户端、进程采集
desktopApp/   Compose Desktop 客户端
androidApp/   Jetpack Compose 客户端（前台服务 + 开机自启）
scripts/      引擎编译、八协议端到端测试
docs/wiki/    使用文档
```

## 已知限制

- 健康检查（`healthCheck`）、`loadBalancer`、`includes`、代理注解等高级字段未建模，导入 TOML 时会被忽略；`plugin` 已支持 https 的 `https2http` / `tls2raw`，其它插件类型导入后原样保留但不可编辑
- Android 仅提供 `arm64-v8a`；`x86_64` 可自行用 `SKIP_X86_64=0` 编译
- macOS 产物未签名 / 未公证
- 运行中的配置不可编辑，需先停止服务（避免"改了没生效"的误解）
- xtcp 依赖 UDP 打洞：系统代理 / 虚拟网卡（TUN）、对称型 NAT、模拟器网络都可能让它建不上隧道。日志出现 `establishing nat hole connection successful` 仍不通时，先关掉系统代理或换网络再试

## 许可

MIT。内置引擎来自 [fatedier/frp](https://github.com/fatedier/frp)（Apache-2.0）。
