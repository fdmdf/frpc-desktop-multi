# 更新日志

本项目遵循 [语义化版本](https://semver.org/lang/zh-CN/)，格式参考 [Keep a Changelog](https://keepachangelog.com/zh-CN/1.1.0/)。

## [2.0.0] - 2026-11-01

首个版本。

### 功能

- **一套实现，四个平台**：Rust 核心层 `frpc-core` + Vue 3 界面，Windows / Linux / macOS 与 Android 共用同一份代码。
- **可视化配置**：支持 tcp / udp / http / https / tcpmux / stcp / sudp / xtcp 八种代理类型，以及 http / https / socks5 三种访问者类型。
- **进程监管**：启动前校验端口与地址，进程异常退出按指数退避自动重启。
- **真实状态**：优先读 frpc 的 admin API（含真实远程端口），接口关闭时回退到日志推断。
- **监控与日志**：CPU / 内存 / 线程 / 运行时长实时采样；日志分级过滤、搜索、导出。
- **开箱即用**：内置各平台原生编译的 frpc 引擎，用户不需要装 Go、Java，也不用手动下载二进制。
- **双语界面**：简体中文 / English，应用内切换，也可跟随系统。
- **Android 保活**：前台服务 + 常驻通知，显示运行状态、当前配置与在线代理数。

### 已知限制

- 健康检查（`healthCheck`）、`loadBalancer`、`includes`、代理注解等高级字段尚未建模，导入 TOML 时会原样保留但不可编辑。
- Android 目前只打 `arm64-v8a`。
- macOS 产物未签名、未公证，首次启动需在系统设置里放行。
- 运行中的配置不可编辑，需先停止服务。

[2.0.0]: https://github.com/fdmdf/frpc-desktop-multi/releases/tag/v2.0.0
