# frpc-desktop-multi · Wiki

面向使用者的文档中心。项目主页见 [README](../../README.md)。

frpc-desktop-multi = 桌面端（Windows / Linux / macOS）+ Android 端，两套客户端共用同一个 Kotlin Multiplatform 共享层（配置模型、`frpc.toml` 编解码、校验、admin API 客户端）。

## 目录

| 页面 | 内容 |
| --- | --- |
| [快速开始](快速开始.md) | 下载、安装、第一个穿透配置 |
| [配置指南](配置指南.md) | 每个配置项的含义与推荐值，各代理类型示例 |
| [协议兼容性](协议兼容性.md) | 传输协议与代理类型的实测矩阵、服务端配置要求、xtcp 的 STUN 问题 |
| [故障排查](故障排查.md) | 常见报错与处理（启动失败、状态不可用、端口不通等） |
| [构建与发版](构建与发版.md) | 本地构建、引擎重编、CI 四平台产物、协议端到端测试 |
| [架构](架构.md) | 模块划分、共享层边界、关键设计取舍 |

## 三句话上手

1. **配置** 页新建服务器配置：填 frps 地址、端口、Token，保持「状态监控」开启
2. 添加代理规则（TCP：本地 22 → 远程 6001）
3. 回**主页**点「启动」，日志出现 `login to server success` 即成功

## 两端差异速查

| | 桌面端 | Android |
| --- | --- | --- |
| 界面 | Compose Desktop（1280×820，左侧导航） | Jetpack Compose（底部导航） |
| 常驻方式 | 托盘图标 + 关闭到托盘 | 前台服务 + 常驻通知 |
| 开机自启 | 随系统登录项（自行添加） | `BOOT_COMPLETED` 广播 |
| 数据目录 | `~/.frpcdash/` | 应用私有目录 `files/configs.json` |
| 额外能力 | `--smoke` / `--export-toml` 无界面模式 | 分享/复制配置、电池优化引导 |
