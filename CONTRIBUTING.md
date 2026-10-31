# 贡献指南

感谢你愿意参与本项目。以下流程能让协作更顺畅。

## 开发环境

- Rust 1.85+（`rustup` 稳定版即可）
- Node 20+（前端 `tauri/app/ui`）
- Go 1.21+（仅在重建 frpc 引擎时需要）
- Android SDK（platform 36 / NDK 27.2）与 JDK 17：仅打 APK 时需要

```bash
cargo test --manifest-path tauri/Cargo.toml -p frpc-core   # 核心层单测
cargo fmt --all --manifest-path tauri/Cargo.toml -- --check # 格式
```

## 目录约定

| 目录 | 说明 |
| --- | --- |
| `tauri/crates/frpc-core/` | Rust 核心层。**不依赖 Tauri**，可独立单测：模型 / TOML / 校验 / admin API / 日志 / 指标 / 端口 / 存储 / 进程监管 |
| `tauri/app/src-tauri/` | Tauri 外壳：命令桥、托盘、单实例、引擎管理（平台相关） |
| `tauri/app/ui/` | Vue 3 界面，桌面与 Android 共用同一份代码 |
| `scripts/` | 可复现的构建脚本（引擎编译、Android 工程初始化、端到端测试） |

新增业务逻辑优先放进 `frpc-core`，并补上对应单测；界面改动改 `tauri/app/ui` 一次即可两端生效。

## 代码规范

- Rust：`cargo fmt` + `cargo clippy` 默认档，4 空格缩进
- 前端：Vue 3 组合式 API，状态走 Pinia，界面文案走 `vue-i18n`（中英双语都要补）
- 与 frpc 配置相关的字段变更必须同步：
  1. `frpc-core/src/model.rs` 的模型
  2. `frpc-core/src/toml.rs` 的编解码（含未知字段保留）
  3. `frpc-core/src/validate.rs` 的校验规则
  4. 对应的单元测试
  5. `README.md` 的兼容性说明（如涉及协议行为）
- 提交前请确保测试全绿，桌面端可正常启动

## 提交信息

采用约定式提交（Conventional Commits），正文用中文：

```
feat(tray): 新增托盘重启 frpc 菜单项
fix(supervisor): 修复无 tokio 上下文启动 panic
docs(tauri): 补充 M1 移植对照表
chore(frpc): bump engine to v0.71.2
```

## 提交 PR

1. Fork 并从 `main` 切出分支：`feat/xxx`、`fix/xxx`
2. 保持改动聚焦，一个 PR 只做一件事
3. PR 描述中说明：动机、改动点、验证方式（测试命令 / 截图 / 日志）
4. 涉及 UI 的改动请附截图（深色/浅色各一张更佳）

## 报告问题

使用 Issue 模板提交，务必包含：

- 系统与版本（Windows / Linux / macOS / Android）
- App 版本（设置 → 关于）
- 引擎版本（设置 → frpc 引擎）
- 相关日志（日志页「保存」导出）
- 复现步骤

## 引擎升级

`scripts/build-frpc.sh` 支持 `FRP_VERSION` 环境变量：

```bash
FRP_VERSION=v0.72.0 bash scripts/build-frpc.sh desktop
```

升级后请更新 `README.md` 兼容性矩阵，并跑一遍协议矩阵测试（见 Wiki：协议兼容性）。
