# tauri/ — Tauri 2 重写工作区

> 迁移方案：`docs/tauri-migration-plan.md` · 基线数据：`docs/tauri-baseline.md`
> 分支：`tauri-rewrite`（自 `dev` 切出）；技术栈：Tauri 2 + Rust + Vue 3

## 结构

```
tauri/
  Cargo.toml              # Cargo workspace（target 统一在 tauri/target/）
  crates/
    frpc-core/            # 核心逻辑：model / toml / validate / admin / supervisor / metrics / logs
                          # 不依赖 Tauri，可独立编译与测试；对拍测试随 M1 移植
  app/
    src-tauri/            # Tauri 壳：IPC 胶水（commands/events）、插件装配、图标、能力清单
    ui/                   # Vue 3 + Vite + TS + Pinia + vue-i18n（双语：zh-CN / en）
```

## 本地开发

前置：Rust stable（Windows 需 MSVC 工具链）、Node 22、WebView2（Win10/11 系统自带）。

```bash
# 首次：安装 UI 依赖
cd tauri/app/ui && npm install

# 核心层单测（不编译 Tauri 壳，最快）
cargo test --manifest-path tauri/Cargo.toml -p frpc-core

# 开发模式（UI 热更新，自动拉起桌面壳；从 tauri/app 运行）
cd tauri/app && ui/node_modules/.bin/tauri dev

# 打包（NSIS / DMG / deb 等，产物在 tauri/target/release/bundle/）
cd tauri/app && ui/node_modules/.bin/tauri build
```

## 约定

- **业务逻辑一律进 `frpc-core`**（不 import Tauri）；`app/src-tauri` 只做 IPC 胶水，
  保证核心层可独立测试、Android 可按能力子集复用（见方案第 5 节）。
- 从 Kotlin 移植逻辑时**必须同步移植对应测试**（映射表见方案第 6 节）。
- 迁移期间 `dev` 分支 Kotlin 代码冻结（只修致命 bug，方案第 4 节）。
- CI：`.github/workflows/tauri.yml`（核心层测试 + 三平台打包矩阵）。
